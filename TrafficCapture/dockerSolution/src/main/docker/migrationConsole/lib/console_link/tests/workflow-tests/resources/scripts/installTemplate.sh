#!/bin/bash
# Install WorkflowTemplate to Kubernetes cluster
# For testing, this just outputs the template that would be installed

set -e

TEMPLATE_NAME="${1:-hello-world-template}"
NAMESPACE="${2:-ma}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE_FILE="$SCRIPT_DIR/../${TEMPLATE_NAME}.yaml"

# Check if template file exists
if [ ! -f "$TEMPLATE_FILE" ]; then
    echo "Error: Template file not found: $TEMPLATE_FILE" >&2
    exit 1
fi

# For testing, just output success message
# In production, this would run: kubectl apply -f "$TEMPLATE_FILE" -n "$NAMESPACE"
cat <<EOF
{
  "success": true,
  "template_name": "$TEMPLATE_NAME",
  "namespace": "$NAMESPACE",
  "message": "WorkflowTemplate $TEMPLATE_NAME would be installed to namespace $NAMESPACE",
  "template_file": "$TEMPLATE_FILE"
}
EOF
