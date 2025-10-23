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
: ${INITIALIZE_CMD:="$SCRIPT_DIR/index.sh"}

# Create a temporary file
TEMPORARY_FILE=$(mktemp)

# Ensure cleanup on exit
trap "rm -f $TEMPORARY_FILE" EXIT

# Generate UUID - use uuidgen if available, otherwise use a timestamp-based approach
if command -v uuidgen &> /dev/null; then
    UUID=$(uuidgen)
else
    UUID="test-$(date +%s)-$$"
fi
echo "Generated unique prefix: $UUID"

echo "Running configuration conversion..."
$INITIALIZE_CMD --user-config $CONFIG_FILENAME --prefix $UUID "$@" > "$TEMPORARY_FILE"

# Parse user configuration to extract parameters using yq
echo "Parsing user configuration..."

# Extract parameters using yq (handles both JSON and YAML)
# Try nested path first, if null/empty try direct path
MESSAGE=$(yq eval '.parameters.message' "$CONFIG_FILENAME")
if [ "$MESSAGE" = "null" ] || [ -z "$MESSAGE" ]; then
    MESSAGE=$(yq eval '.message' "$CONFIG_FILENAME")
fi

REQUIRES_APPROVAL=$(yq eval '.parameters.requiresApproval' "$CONFIG_FILENAME")
if [ "$REQUIRES_APPROVAL" = "null" ] || [ -z "$REQUIRES_APPROVAL" ]; then
    REQUIRES_APPROVAL=$(yq eval '.requiresApproval' "$CONFIG_FILENAME")
fi

APPROVER=$(yq eval '.parameters.approver' "$CONFIG_FILENAME")
if [ "$APPROVER" = "null" ] || [ -z "$APPROVER" ]; then
    APPROVER=$(yq eval '.approver' "$CONFIG_FILENAME")
fi

# Validate required parameters
if [ -z "$MESSAGE" ] || [ "$MESSAGE" = "null" ]; then
    echo "Error: 'message' parameter is required in configuration file" >&2
    exit 1
fi

if [ -z "$REQUIRES_APPROVAL" ] || [ "$REQUIRES_APPROVAL" = "null" ]; then
    echo "Error: 'requiresApproval' parameter is required in configuration file" >&2
    exit 1
fi

# Default approver to empty string if not provided or null
if [ "$APPROVER" = "null" ]; then
    APPROVER=""
fi

echo "Configuration parameters:"
echo "  message: $MESSAGE"
echo "  requiresApproval: $REQUIRES_APPROVAL"
echo "  approver: $APPROVER"

echo "Applying workflow to Kubernetes..."
cat <<EOF | kubectl create -f -
apiVersion: argoproj.io/v1alpha1
kind: Workflow
metadata:
  generateName: hello-world-
spec:
  workflowTemplateRef:
    name: hello-world-template
  entrypoint: main
  arguments:
    parameters:
      - name: message
        value: "$MESSAGE"
      - name: requiresApproval
        value: "$REQUIRES_APPROVAL"
      - name: approver
        value: "$APPROVER"
      - name: configData
        value: |
$(sed 's/^/          /' "$TEMPORARY_FILE")
EOF

echo "Done! Workflow submitted successfully."
