#!/bin/bash
# Returns sample workflow configuration for testing
# This is completely self-contained and doesn't depend on any external tools

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SAMPLE_CONFIG="$SCRIPT_DIR/../sample-config.yaml"

# Check if sample config exists
if [ ! -f "$SAMPLE_CONFIG" ]; then
    echo "Error: Sample config not found at $SAMPLE_CONFIG" >&2
    exit 1
fi

# Output the sample configuration
cat "$SAMPLE_CONFIG"
