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

# unique-run-nonce is only used as the suffix for auto-created snapshot names.
# It is not part of INITIALIZE_CMD input, so filter it out while preserving all
# other arguments for the config processor.
RUN_NONCE=""
ALL_ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --unique-run-nonce)
            if [[ $# -lt 2 ]]; then
                echo "Error: --unique-run-nonce requires a value" >&2
                exit 1
            fi
            RUN_NONCE="$2"
            shift 2
            ;;
        *)
            ALL_ARGS+=("$1")
            shift
            ;;
    esac
done

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${NODEJS:=node}"

# Default command, can be overridden by setting INITIALIZE_CMD environment variable
: ${INITIALIZE_CMD:="$NODEJS $SCRIPT_DIR/index.js initialize"}

# Create a temporary directory for output files
TEMP_DIR=$(mktemp -d)

# Ensure cleanup on exit
trap "rm -rf $TEMP_DIR" EXIT

if [ -n "${EPOCHREALTIME:-}" ]; then
    RUN_SECONDS="${EPOCHREALTIME%.*}"
    RUN_MICROS="${EPOCHREALTIME#*.}"
    RUN_NUMBER="${RUN_SECONDS}${RUN_MICROS:0:3}"
else
    RUN_NUMBER="$(date +%s%3N 2>/dev/null || true)"
    case "$RUN_NUMBER" in
        ''|*[!0-9]*) RUN_NUMBER="$(date +%s)000" ;;
    esac
fi

: "${RUN_NONCE:=$RUN_NUMBER}"

echo "Using migration run number: $RUN_NUMBER"
echo "Using snapshot nonce: $RUN_NONCE"

WORKFLOW_NAME="migration-workflow"

echo "Running configuration conversion..."
$INITIALIZE_CMD --user-config "$CONFIG_FILENAME" --output-dir "$TEMP_DIR" --workflow-name "$WORKFLOW_NAME" --run-number "$RUN_NUMBER" "${ALL_ARGS[@]}"

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

kubectl create -f - <<EOF
apiVersion: argoproj.io/v1alpha1
kind: Workflow
metadata:
  name: $WORKFLOW_NAME
  labels:
    migrations.opensearch.org/workflow-name: "$WORKFLOW_NAME"
    migrations.opensearch.org/run-number: "$RUN_NUMBER"
  annotations:
    migrations.opensearch.org/migration-run: "$WORKFLOW_NAME-run-$RUN_NUMBER"
spec:
  workflowTemplateRef:
    name: full-migration
  entrypoint: main
  arguments:
    parameters:
      - name: uniqueRunNonce
        value: "$RUN_NONCE"
      - name: migrationRunNumber
        value: "$RUN_NUMBER"
      - name: config
        value: |
$(sed 's/^/          /' "$TEMP_DIR/workflowMigration.config.yaml")
EOF

echo "Done! Workflow submitted successfully."
