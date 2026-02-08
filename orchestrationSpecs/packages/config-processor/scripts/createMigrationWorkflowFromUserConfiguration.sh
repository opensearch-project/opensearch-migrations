#!/bin/bash

set -e  # Exit on any error

# Check if config filename argument is provided
if [ $# -eq 0 ]; then
    echo "Error: CONFIG_FILENAME argument is required"
    echo "Usage: $0 <config-filename> [additional-args...]"
    exit 1
fi

CONFIG_FILENAME=$1
shift  # Remove first argument, leaving any additional args in $@

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${NODEJS:=node}"

# Default command, can be overridden by setting INITIALIZE_CMD environment variable
: ${INITIALIZE_CMD:="$NODEJS $SCRIPT_DIR/index.js initialize"}

# Create a temporary directory for output files
TEMP_DIR=$(mktemp -d)

# Ensure cleanup on exit
trap "rm -rf $TEMP_DIR" EXIT

UUID=$(printf '%x' $(date +%s))$(LC_ALL=C tr -dc 'a-z0-9' < /dev/urandom | head -c 4)
echo "Generated unique uniqueRunNonce: $UUID"

echo "Running configuration conversion..."
$INITIALIZE_CMD --user-config $CONFIG_FILENAME --unique-run-nonce $UUID --output-dir $TEMP_DIR $@

echo "Applying Kubernetes resources..."

# Apply approval config maps
if [ -f "$TEMP_DIR/approvalConfigMaps.yaml" ]; then
    echo "Applying approval config maps..."
    kubectl apply -f "$TEMP_DIR/approvalConfigMaps.yaml"
fi

# Apply concurrency config maps  
if [ -f "$TEMP_DIR/concurrencyConfigMaps.yaml" ]; then
    echo "Applying concurrency config maps..."
    kubectl apply -f "$TEMP_DIR/concurrencyConfigMaps.yaml"
fi

# Set the name field based on environment variable
if [ -n "$USE_GENERATE_NAME" ] && [ "$USE_GENERATE_NAME" != "false" ] && [ "$USE_GENERATE_NAME" != "0" ]; then
  # Keeping this as 'full-migration' so that it's intentionally different than the
  # one-single default migration that we will normally be using
  NAME_FIELD="generateName: full-migration-${UUID}-"
else
  NAME_FIELD="name: migration-workflow"
fi

echo "Applying workflow to Kubernetes..."
cat <<EOF | kubectl create -f -
apiVersion: argoproj.io/v1alpha1
kind: Workflow
metadata:
  $NAME_FIELD
spec:
  workflowTemplateRef:
    name: full-migration
  entrypoint: main
  arguments:
    parameters:
      - name: uniqueRunNonce
        value: "$UUID"
      - name: approval-config
        value: "approval-config-0"
      - name: config
        value: |
$(sed 's/^/          /' "$TEMP_DIR/workflowMigration.config.yaml")
EOF

echo "Done! Workflow submitted successfully."