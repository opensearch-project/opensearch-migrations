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

STATUS_OUTPUT=$(workflow status migration-workflow 2>&1 || true)
echo "Status output:"
echo "$STATUS_OUTPUT"

RESULT="ERROR"
EXIT_CODE=2

# 1) Terminal success: stop monitoring, proceed, mark success
if echo "$STATUS_OUTPUT" | grep -q "Phase: Succeeded"; then
    echo "Workflow completed successfully"
    RESULT="SUCCEEDED"
    EXIT_CODE=0

# 2) Terminal failure: stop monitoring, proceed, mark failure
elif echo "$STATUS_OUTPUT" | grep -q "Phase: Failed"; then
    echo "Workflow failed permanently"
    RESULT="FAILED"
    EXIT_CODE=0

# 3) Suspended / waiting for approval: try to approve once, then retry
elif echo "$STATUS_OUTPUT" | grep -q "Suspended\|Waiting"; then
    echo "Workflow is suspended or waiting for approval, attempting to approve..."
    APPROVE_OUTPUT=$(workflow approve 2>&1 || true)
    echo "Approve output:"
    echo "$APPROVE_OUTPUT"
    # Regardless of approve result, tell Argo to check again
    RESULT="RETRY"
    EXIT_CODE=1

# 4) Still running: keep checking
elif echo "$STATUS_OUTPUT" | grep -q "Phase: Running\|Phase: Pending"; then
    echo "⏳ Workflow still running, will retry..."
    RESULT="RETRY"
    EXIT_CODE=1

# 5) Anything else is treated as an error (e.g. CLI failure, unknown output)
else
    echo "Unknown or error status from 'workflow status'"
    RESULT="ERROR"
    EXIT_CODE=2
fi

mkdir -p /tmp/outputs
echo "$RESULT" > /tmp/outputs/monitorResult
sync

echo "Monitor result: $RESULT (exit code: $EXIT_CODE)"
exit $EXIT_CODE
