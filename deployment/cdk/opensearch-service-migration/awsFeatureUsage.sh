#!/bin/bash

set -e

if [ -z "$1" ]; then
    echo "Error: contextId is required. Please pass it as the first argument to the script."
    echo "Usage: $0 <contextId>"
    exit 1
fi

contextId="$1"

output_dir="cdk-synth-output"
rm -rf "$output_dir"
mkdir -p "$output_dir"

echo "Synthesizing all stacks..."
raw_stacks=$(cdk list --ci --context contextId=$contextId)

echo "$raw_stacks" | sed -E 's/ *\(.*\)//' | while read -r stack; do
    echo "Synthesizing stack: $stack"
    cdk synth $stack --ci --context contextId=$contextId > "$output_dir/$stack.yaml"
done

echo "Finding resource usage from synthesized stacks..."
echo "-----------------------------------"
echo "IAM Policy Actions:"

grep -h -A 1000 "PolicyDocument:" "$output_dir"/*.yaml | \
grep -E "Action:" -A 50 | \
grep -E "^[ \t-]+[a-zA-Z0-9]+:[a-zA-Z0-9\*]+" | \
grep -vE "^[ \t-]+(aws:|arn:)" | \
sed -E 's/^[ \t-]+//' | \
sort -u

echo "-----------------------------------"
echo "Resources Types:"

grep -h -E " Type: AWS" "$output_dir"/*.yaml | \
sed -E 's/^[ \t]*Type: //' | \
sort -u
