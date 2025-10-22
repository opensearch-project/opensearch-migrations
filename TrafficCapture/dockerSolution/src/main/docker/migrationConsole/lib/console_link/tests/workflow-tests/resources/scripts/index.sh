#!/bin/bash
# Mock config processor for testing
# This simulates the behavior of the real config processor

set -e

# Parse command line arguments
USER_CONFIG_SOURCE=""
PREFIX=""
SKIP_INITIALIZE=false
SILENT=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --user-config)
            USER_CONFIG_SOURCE="$2"
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

# Read user config from stdin if source is '-'
if [ "$USER_CONFIG_SOURCE" = "-" ]; then
    USER_CONFIG=$(cat)
elif [ -n "$USER_CONFIG_SOURCE" ]; then
    USER_CONFIG=$(cat "$USER_CONFIG_SOURCE")
else
    echo "Error: --user-config is required" >&2
    exit 1
fi

if [ "$SKIP_INITIALIZE" = true ]; then
    # Transform mode: output transformed config as JSON
    cat <<'EOF'
{
  "apiVersion": "argoproj.io/v1alpha1",
  "kind": "Workflow",
  "metadata": {
    "generateName": "test-workflow-"
  },
  "spec": {
    "entrypoint": "main",
    "templates": [
      {
        "name": "main",
        "container": {
          "image": "alpine:latest",
          "command": ["echo"],
          "args": ["Hello from transformed config"]
        }
      }
    ]
  }
}
EOF
else
    # Initialize mode: output prefix
    if [ -n "$PREFIX" ]; then
        OUTPUT_PREFIX="$PREFIX"
    else
        OUTPUT_PREFIX="test-$(date +%s)"
    fi
    
    # In real implementation, this would initialize etcd
    # For testing, we just output the prefix
    if [ "$SILENT" != true ]; then
        echo "Initializing workflow with prefix: $OUTPUT_PREFIX" >&2
    fi
    echo "$OUTPUT_PREFIX"
fi
