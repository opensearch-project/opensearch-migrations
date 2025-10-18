#!/bin/bash
# Transform config by extracting workflow parameters
# This demonstrates how config flows into workflow execution

set -e

# Read input from file or stdin
if [ $# -eq 0 ]; then
    INPUT=$(cat)
else
    INPUT=$(cat "$1")
fi

# Validate it's not empty
if [ -z "$INPUT" ]; then
    echo "Error: Configuration is empty" >&2
    exit 1
fi

# For testing, extract key parameters and create a transformed config
# In production, this would call orchestrationSpecs npm commands

# Use a simple approach - just pass through but could parse YAML here
# In a real implementation, you'd extract parameters and validate them
echo "$INPUT"
