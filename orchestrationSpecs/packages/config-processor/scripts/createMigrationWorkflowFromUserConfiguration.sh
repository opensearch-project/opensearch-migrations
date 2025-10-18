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

UUID=$(uuidgen)
echo "Generated unique prefix: $UUID"

echo "Running configuration conversion..."
$INITIALIZE_CMD --user-config $CONFIG_FILENAME --prefix $UUID "$@" > "$TEMPORARY_FILE"

echo "Applying workflow to Kubernetes..."
cat <<EOF | kubectl create -f -
apiVersion: argoproj.io/v1alpha1
kind: Workflow
metadata:
  generateName: full-migration-
spec:
  workflowTemplateRef:
    name: full-migration
  entrypoint: main
  arguments:
    parameters:
      - name: latchCoordinationPrefix
        value: "$UUID"
      - name: migrationConfigs
        value: |
$(sed 's/^/          /' "$TEMPORARY_FILE")
EOF

echo "Done! Workflow submitted successfully."