#!/bin/bash
# Install WorkflowTemplate to Kubernetes cluster
# For testing, this just outputs the template that would be installed

set -e

TEMPLATE_NAME="${1:-hello-world-template}"
NAMESPACE="${2:-ma}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE_FILE="$SCRIPT_DIR/${TEMPLATE_NAME}.yaml"

# Check if template file exists
if [ ! -f "$TEMPLATE_FILE" ]; then
    echo "Error: Template file not found: $TEMPLATE_FILE" >&2
    exit 1
fi

kubectl apply -f "$TEMPLATE_FILE" -n "$NAMESPACE"
