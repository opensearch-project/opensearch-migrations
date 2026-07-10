#!/bin/bash

set -e

if [ -z "$1" ]; then
    echo "Error: contextId is required. Please pass it as the first argument to the script."
    echo "Usage: $0 <contextId>"
    exit 1
fi

contextId="$1"

# Ensure the CDK CLI and the app's dependencies match. The script invokes
# `npx cdk`, which resolves to the locally-pinned `aws-cdk` in this package's
# node_modules; running the globally-installed `cdk` can cause cloud-assembly
# schema mismatches between CLI and library.
npm ci

export CDK_CLI_COMMAND=deploy
npx cdk deploy "*" --c contextId=$contextId --require-approval never --concurrency 5