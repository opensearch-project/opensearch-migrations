#!/bin/bash

set -e

if [ -z "$1" ]; then
    echo "Error: contextId is required. Please pass it as the first argument to the script."
    echo "Usage: $0 <contextId>"
    exit 1
fi

contextId="$1"

export CDK_CLI_COMMAND=deploy
cdk deploy "*" --c contextId=$contextId --require-approval never --concurrency 5