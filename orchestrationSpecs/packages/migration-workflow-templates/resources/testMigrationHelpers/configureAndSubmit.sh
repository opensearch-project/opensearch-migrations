#!/bin/bash
#
# ============================================================================
# WARNING: TEST HELPER SCRIPT - SHOULD BE MOVED TO SEPARATE PACKAGE
# ============================================================================
# This script is part of the test workflow infrastructure and creates a
# logical dependency cycle:
# - migration-workflow-templates package provides workflow templates
# - workflow CLI (in migration console) depends on migration-workflow-templates
# - This test script depends on workflow CLI
#
# TODO: Move test workflows and helpers to a separate package (e.g.,
# migration-workflow-templates-test) to break this dependency cycle.
# ============================================================================

set -x

# Write error details to /dev/termination-log so Argo surfaces the reason, not just the exit code
fail() { echo "$1" | tee /dev/termination-log >&2; exit 1; }

# Activate the Python virtual environment to get access to workflow CLI
. /etc/profile.d/venv.sh
source /.venv/bin/activate

echo "Building and submitting migration workflow..."

# Decode base64 migration config from environment variable and write to file
echo "$MIGRATION_CONFIG_BASE64" | base64 -d > /tmp/migration_config.json

echo "Migration config contents:"
cat /tmp/migration_config.json

echo "Loading configuration from JSON..."
CONFIG_OUTPUT=$(cat /tmp/migration_config.json | workflow configure edit --stdin 2>&1) || fail "Configure failed: $CONFIG_OUTPUT"
echo "$CONFIG_OUTPUT"

# Submit workflow
echo "Submitting workflow..."
WORKFLOW_OUTPUT=$(workflow submit 2>&1) || fail "Submit failed: $WORKFLOW_OUTPUT"
echo "Workflow submit output: $WORKFLOW_OUTPUT"
