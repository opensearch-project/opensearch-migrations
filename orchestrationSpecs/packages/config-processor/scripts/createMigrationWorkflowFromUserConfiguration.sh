#!/bin/bash

set -e -x # Exit on any error

# Check if config filename argument is provided
if [ $# -eq 0 ]; then
    echo "Error: CONFIG_FILENAME argument is required"
    echo "Usage: $0 <config-filename> [additional-args...]"
    exit 1
fi

CONFIG_FILENAME=$1
shift  # Remove first argument, leaving any additional args in $@

# we copy the remaining args here since we wanna extract particular args which empties $@
# and pass "${ALL_ARGS[@]}" below instead of $@
# NOTE: RUN_NONCE will be added in below workflow and gets appended in create-or-get-snapshot.yaml
# to the snapshotName as inputs.parameters.sourceLabel + '_' + inputs.parameters.snapshotPrefix + '_' + inputs.parameters.uniqueRunNonce
# which for Solr versions <= 7 we can not have generated randomly since solr cluster needs to know the snapshot name
# upfront to avoid no-dir error
# NOTE that unique-run-nonce is not part of the parameters to be passed below to INITIALIZE_CMD, thus we filter it out
# here
RUN_NONCE="$(date +%s)"
ALL_ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --unique-run-nonce)
            RUN_NONCE="$2"
            shift 2
            ;;
        *)
            new_args+=("$1")
            shift
            ;;
    esac
done
echo "Used runNonce: $RUN_NONCE"


# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${NODEJS:=node}"

# Default command, can be overridden by setting INITIALIZE_CMD environment variable
: ${INITIALIZE_CMD:="$NODEJS $SCRIPT_DIR/index.js initialize"}

# Create a temporary directory for output files
TEMP_DIR=$(mktemp -d)

# Ensure cleanup on exit
trap "rm -rf $TEMP_DIR" EXIT


WORKFLOW_NAME="migration-workflow"

echo "Running configuration conversion..."
$INITIALIZE_CMD --user-config $CONFIG_FILENAME --output-dir $TEMP_DIR --workflow-name "$WORKFLOW_NAME" "${ALL_ARGS[@]}"

echo "Applying Kubernetes resources..."
if [ -x "$TEMP_DIR/handleK8sResources.sh" ]; then
    "$TEMP_DIR/handleK8sResources.sh"
fi

if [ -x "$TEMP_DIR/enrichWorkflowConfigWithUids.sh" ]; then
    echo "Enriching workflow config with CR UIDs..."
    "$TEMP_DIR/enrichWorkflowConfigWithUids.sh" "$TEMP_DIR/workflowMigration.config.yaml"
fi

echo "Applying workflow to Kubernetes..."

# Display any initialization warnings
if [ -f "$TEMP_DIR/warnings.json" ]; then
    "$NODEJS" -e "JSON.parse(require('fs').readFileSync('$TEMP_DIR/warnings.json','utf8')).forEach(w=>console.log('INIT_WARNING: '+w))" >&2
fi

cat <<EOF | kubectl create -f -
apiVersion: argoproj.io/v1alpha1
kind: Workflow
metadata:
  name: $WORKFLOW_NAME
spec:
  workflowTemplateRef:
    name: full-migration
  entrypoint: main
  arguments:
    parameters:
      - name: uniqueRunNonce
        value: "$RUN_NONCE"
      - name: approval-config
        value: "approval-config-0"
      - name: config
        value: |
$(sed 's/^/          /' "$TEMP_DIR/workflowMigration.config.yaml")
EOF

echo "Done! Workflow submitted successfully."
