#!/usr/bin/env bash
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

WORKFLOW_NAMESPACE="${WORKFLOW_NAMESPACE:-ma}"
MIGRATION_CONSOLE_POD="${MIGRATION_CONSOLE_POD:-migration-console-0}"

wait_for_console_pod() {
  kubectl wait \
    --namespace "$WORKFLOW_NAMESPACE" \
    --for=condition=ready "pod/$MIGRATION_CONSOLE_POD" \
    --timeout=300s >/dev/null
}

echo "Checking workflow status"
wait_for_console_pod

set +e
STATUS_OUTPUT=$(
  kubectl exec --namespace "$WORKFLOW_NAMESPACE" "$MIGRATION_CONSOLE_POD" -- \
    /bin/bash -lc 'workflow status --workflow-name migration-workflow' 2>&1
)
STATUS_EXIT_CODE=$?
set -e
echo "Status output:"
echo "$STATUS_OUTPUT"

if [ $STATUS_EXIT_CODE -ne 0 ]; then
    echo "Failed to get workflow status (exit code: $STATUS_EXIT_CODE), will retry..."
    exit 1
fi

if echo "$STATUS_OUTPUT" | grep -q "Phase: Running\|Phase: Pending"; then
    echo "Workflow still running or pending, will retry..."
    exit 1
fi

echo "Workflow is in terminal state"
mkdir -p /tmp/outputs
echo "$STATUS_OUTPUT" > /tmp/outputs/monitorResult
exit 0
