#!/bin/bash

# Parse Argo Workflow parameters and set as environment variables
# Usage: ./parse-workflow.sh <workflow-file.yaml>

set -e -x

cd "$(dirname "$0")"

WORKFLOW_FILE="${1:-../../createMigration.yaml}"

if [[ ! -f "$WORKFLOW_FILE" ]]; then
    echo "Error: Workflow file '$WORKFLOW_FILE' not found"
    exit 1
fi

# Function to extract parameter value from YAML
# This handles multi-line values properly
extract_parameter() {
    local param_name="$1"
    local file="$2"

    # Use awk to extract multi-line parameter values
    awk -v param="$param_name" '
        /- name:/ {
            current_name = $0
            sub(/.*- name: */, "", current_name)
            sub(/ *$/, "", current_name)
        }
        current_name == param && /value: \|/ {
            capture = 1
            next
        }
        capture && /^      - name:/ {
            capture = 0
        }
        capture && /^ {10,}/ {
            sub(/^ {10}/, "")
            print
        }
    ' "$file"
}

# Extract parameters
SOURCE_CLUSTERS=$(extract_parameter "sourceClusters" "$WORKFLOW_FILE")
TARGET_CLUSTERS=$(extract_parameter "targetClusters" "$WORKFLOW_FILE")
SOURCE_MIGRATION_CONFIGS=$(extract_parameter "sourceMigrationConfigs" "$WORKFLOW_FILE")
SNAPSHOT_REPO_CONFIGS=$(extract_parameter "snapshotRepoConfigs" "$WORKFLOW_FILE")

# Export as environment variables with WF_SETUP prefix
export WF_SETUP_SOURCE_CLUSTERS="$SOURCE_CLUSTERS"
export WF_SETUP_TARGET_CLUSTERS="$TARGET_CLUSTERS"
export WF_SETUP_SOURCE_MIGRATION_CONFIGS="$SOURCE_MIGRATION_CONFIGS"
export WF_SETUP_SNAPSHOT_REPO_CONFIGS="$SNAPSHOT_REPO_CONFIGS"

# Print the variables (your test script output)
echo "source clusters = $WF_SETUP_SOURCE_CLUSTERS"
echo "target clusters = $WF_SETUP_TARGET_CLUSTERS"
echo "sourceMigrationConfigs = $WF_SETUP_SOURCE_MIGRATION_CONFIGS"
echo "snapshot repo configs = $WF_SETUP_SNAPSHOT_REPO_CONFIGS"

SCRIPT_TO_RUN="./init.sh"
# Optional: Execute another script with these environment variables
if [[ -n "$SCRIPT_TO_RUN" ]]; then
    echo ""
    echo "Executing script: $SCRIPT_TO_RUN"
    exec "$SCRIPT_TO_RUN"
fi