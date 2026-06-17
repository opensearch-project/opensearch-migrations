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

set -x

: "${MIGRATION_CONFIG_BASE64:?}"

# Write error details to /dev/termination-log so Argo surfaces the reason, not just the exit code
fail() { echo "$1" | tee /dev/termination-log >&2; exit 1; }

WORKFLOW_NAMESPACE="${WORKFLOW_NAMESPACE:-ma}"
MIGRATION_CONSOLE_POD="${MIGRATION_CONSOLE_POD:-migration-console-0}"

wait_for_console_pod() {
  kubectl wait \
    --namespace "$WORKFLOW_NAMESPACE" \
    --for=condition=ready "pod/$MIGRATION_CONSOLE_POD" \
    --timeout=300s >/dev/null || fail "migration-console pod '$MIGRATION_CONSOLE_POD' was not ready"
}

echo "Building and submitting migration workflow..."
wait_for_console_pod

echo "$MIGRATION_CONFIG_BASE64" | base64 -d > /tmp/migration_config.json

echo "Migration config contents:"
cat /tmp/migration_config.json

echo "Loading configuration from JSON..."
CONFIG_OUTPUT=$(
  kubectl exec -i --namespace "$WORKFLOW_NAMESPACE" "$MIGRATION_CONSOLE_POD" -- \
    /bin/bash -lc 'workflow configure edit --stdin' < /tmp/migration_config.json 2>&1
) || fail "Configure failed: $CONFIG_OUTPUT"
echo "$CONFIG_OUTPUT"

echo "Submitting workflow..."
WORKFLOW_OUTPUT=$(
  kubectl exec --namespace "$WORKFLOW_NAMESPACE" "$MIGRATION_CONSOLE_POD" -- \
    /bin/bash -lc 'workflow submit' 2>&1
) || fail "Submit failed: $WORKFLOW_OUTPUT"
echo "Workflow submit output: $WORKFLOW_OUTPUT"
