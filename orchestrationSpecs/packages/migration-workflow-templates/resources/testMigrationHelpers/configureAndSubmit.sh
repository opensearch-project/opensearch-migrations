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

set -e -x

# Activate the Python virtual environment to get access to workflow CLI
. /etc/profile.d/venv.sh
source /.venv/bin/activate

echo "Building and submitting migration workflow..."

# Decode base64 migration config from environment variable and write to file
echo "$MIGRATION_CONFIG_BASE64" | base64 -d > /tmp/migration_config.json

echo "Migration config contents:"
cat /tmp/migration_config.json

echo "Loading configuration from JSON..."
cat /tmp/migration_config.json | workflow configure edit --stdin

# Submit workflow
echo "Submitting workflow..."
WORKFLOW_OUTPUT=$(workflow submit 2>&1)
EXIT_CODE=$?
echo "Workflow submit output: $WORKFLOW_OUTPUT"
[ $EXIT_CODE -eq 0 ] || exit $EXIT_CODE
