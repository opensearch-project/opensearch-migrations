#!/bin/sh
# =============================================================================
# test-mirrorToEcr.sh — Run mirrorToEcr.sh against localhost:5000 (zot)
#
# Stubs out aws CLI and auth, then runs the real script end-to-end.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MIRROR_SCRIPT="${SCRIPT_DIR}/deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts/mirrorToEcr.sh"

LOCAL_REGISTRY="localhost:5000"

# Put fake aws on PATH (before real aws)
export PATH="/tmp/test-mirror/bin:$PATH"

echo "=== Testing mirrorToEcr.sh against $LOCAL_REGISTRY ==="
echo "Using fake aws at: $(which aws)"
echo "Script: $MIRROR_SCRIPT"
echo ""

exec "$MIRROR_SCRIPT" "$LOCAL_REGISTRY" --region us-east-1
