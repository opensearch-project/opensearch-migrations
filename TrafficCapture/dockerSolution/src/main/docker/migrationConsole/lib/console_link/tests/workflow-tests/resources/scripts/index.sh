#!/bin/bash
# Stub script for workflow config processing in tests
# This is a minimal implementation that generates a basic workflow spec

set -e

# Parse command line arguments
USER_CONFIG=""
PREFIX=""
SKIP_INITIALIZE=false
SILENT=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --user-config)
            USER_CONFIG="$2"
            shift 2
            ;;
        --prefix)
            PREFIX="$2"
            shift 2
            ;;
        --skip-initialize)
            SKIP_INITIALIZE=true
            shift
            ;;
        --silent)
            SILENT=true
            shift
            ;;
        *)
            shift
            ;;
    esac
done

# Read config from stdin if USER_CONFIG is "-"
if [ "$USER_CONFIG" = "-" ]; then
    CONFIG_CONTENT=$(cat)
else
    CONFIG_CONTENT=$(cat "$USER_CONFIG")
fi

# If no prefix provided, generate one
if [ -z "$PREFIX" ]; then
    # Always use test- prefix for consistency with tests
    PREFIX="test-$(date +%s)-$$"
fi

# If skipping initialization (transform mode), output workflow JSON
if [ "$SKIP_INITIALIZE" = true ]; then
    # Generate a basic workflow spec (for testing)
    # This returns a minimal Argo workflow specification
    cat <<EOF
{
  "apiVersion": "argoproj.io/v1alpha1",
  "kind": "Workflow",
  "metadata": {
    "generateName": "test-workflow-",
    "namespace": "ma"
  },
  "spec": {
    "entrypoint": "main",
    "arguments": {
      "parameters": []
    },
    "templates": [
      {
        "name": "main",
        "container": {
          "image": "busybox:latest",
          "command": ["echo"],
          "args": ["Hello from test workflow"]
        }
      }
    ]
  }
}
EOF
else
    # Init mode - only output the prefix
    echo "$PREFIX"
fi
