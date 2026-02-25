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

echo "Checking workflow status"
. /etc/profile.d/venv.sh
source /.venv/bin/activate

STATUS_OUTPUT=$(workflow status --workflow-name migration-workflow 2>&1)
STATUS_EXIT_CODE=$?
echo "Status output:"
echo "$STATUS_OUTPUT"

# If the command failed (e.g., connection error), retry
if [ $STATUS_EXIT_CODE -ne 0 ]; then
    echo "Failed to get workflow status (exit code: $STATUS_EXIT_CODE), will retry..."
    exit 1
fi

# Check if workflow is running or pending - retry
if echo "$STATUS_OUTPUT" | grep -q "Phase: Running\|Phase: Pending"; then
    echo "Workflow still running or pending, will retry..."
    exit 1
fi

# All other states are terminal (Succeeded, Failed, Suspended, Error, etc.)
echo "Workflow is in terminal state"
mkdir -p /tmp/outputs
echo "$STATUS_OUTPUT" > /tmp/outputs/monitorResult
exit 0
