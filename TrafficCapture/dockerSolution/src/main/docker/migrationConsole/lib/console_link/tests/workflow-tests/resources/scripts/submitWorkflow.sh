#!/bin/bash
# Mock workflow submitter that creates a Workflow from WorkflowTemplate
# Demonstrates how config parameters are injected when invoking a template

set -e

CONFIG_FILE="$1"
PREFIX="$2"
NAMESPACE="${3:-ma}"

if [ -z "$CONFIG_FILE" ] || [ -z "$PREFIX" ]; then
    echo "Error: Missing required arguments" >&2
    echo "Usage: submitWorkflow.sh <config-file> <prefix> [namespace]" >&2
    exit 1
fi

# Read config from stdin or file
if [ "$CONFIG_FILE" = "-" ]; then
    CONFIG=$(cat)
elif [ ! -f "$CONFIG_FILE" ]; then
    echo "Error: Config file not found: $CONFIG_FILE" >&2
    exit 1
else
    CONFIG=$(cat "$CONFIG_FILE")
fi

# Parse config to extract parameters
# Look for lines with proper indentation under parameters section
MESSAGE=$(echo "$CONFIG" | awk '/^  message:/ {gsub(/^  message: *"?|"$/, ""); print; exit}' || echo "Hello World")
REQUIRES_APPROVAL=$(echo "$CONFIG" | awk '/^  requiresApproval:/ {print $2; exit}' || echo "false")
APPROVER=$(echo "$CONFIG" | awk '/^  approver:/ {gsub(/^  approver: *"?|"$/, ""); print; exit}' || echo "")

# Generate workflow name from prefix
WORKFLOW_NAME="test-workflow-$(echo $PREFIX | cut -d'-' -f2)"

# Build Workflow that invokes the WorkflowTemplate with parameters
WORKFLOW_SPEC=$(cat <<EOF
{
  "apiVersion": "argoproj.io/v1alpha1",
  "kind": "Workflow",
  "metadata": {
    "generateName": "hello-world-",
    "namespace": "$NAMESPACE"
  },
  "spec": {
    "workflowTemplateRef": {
      "name": "hello-world-template"
    },
    "arguments": {
      "parameters": [
        {
          "name": "message",
          "value": "$MESSAGE"
        },
        {
          "name": "requiresApproval",
          "value": "$REQUIRES_APPROVAL"
        },
        {
          "name": "approver",
          "value": "$APPROVER"
        }
      ]
    }
  },
  "status": {
    "phase": "Pending",
    "startedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  },
  "_metadata": {
    "workflow_name": "$WORKFLOW_NAME",
    "prefix": "$PREFIX",
    "template": "hello-world-template"
  }
}
EOF
)

# Output workflow spec as JSON
echo "$WORKFLOW_SPEC"
