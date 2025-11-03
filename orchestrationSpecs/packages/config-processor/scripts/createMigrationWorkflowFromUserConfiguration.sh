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

# Default command, can be overridden by setting INITIALIZE_CMD environment variable
: ${INITIALIZE_CMD:="node $SCRIPT_DIR/index.js"}

# Create a temporary file
TEMPORARY_FILE=$(mktemp)

# Ensure cleanup on exit
trap "rm -f $TEMPORARY_FILE" EXIT

UUID=$(uuidgen | tr '[:upper:]' '[:lower:]')
echo "Generated unique uniqueRunNonce: $UUID"

echo "Running configuration conversion..."
$INITIALIZE_CMD --user-config $CONFIG_FILENAME --unique-run-nonce $UUID "$@" > "$TEMPORARY_FILE"

# Set the name field based on environment variable
if [ -n "$USE_GENERATE_NAME" ]; then
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
      - name: migrationConfigs
        value: |
$(sed 's/^/          /' "$TEMPORARY_FILE")
EOF

echo "Done! Workflow submitted successfully."