#!/bin/bash
# Mock workflow initializer for testing
# Generates a test prefix and validates config

set -e

# Read config from file or stdin
CONFIG_FILE="${1:--}"
PREFIX="${2:-test-$(date +%s)}"

# Validate config exists
if [ "$CONFIG_FILE" = "-" ]; then
    # Read from stdin and validate it's not empty
    CONFIG=$(cat)
    if [ -z "$CONFIG" ]; then
        echo "Error: Configuration is empty" >&2
        exit 1
    fi
elif [ ! -f "$CONFIG_FILE" ]; then
    echo "Error: Config file not found: $CONFIG_FILE" >&2
    exit 1
fi

# Output the prefix (this would normally initialize state in etcd)
echo "$PREFIX"
