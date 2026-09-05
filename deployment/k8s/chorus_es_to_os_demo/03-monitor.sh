#!/usr/bin/env bash
# =============================================================================
# 03-monitor.sh
#
# Shows the status of the inner migration-workflow (the one submitted by the
# Argo CDC workflow), RFS document progress, and a live target doc count.
# Run at any time after submitting the workflow to check progress.
# =============================================================================
set -euo pipefail

NAMESPACE="ma"
TARGET_URL="https://chorus-opensearch-edition.dev.o19s.com:9200"
TARGET_USER="admin"
TARGET_PASS='MyStr0ng!P@ssw0rd2024'

echo "=== Outer Argo workflow ==="
kubectl get workflow cdc-ecommerce-migration -n "$NAMESPACE" \
  -o custom-columns="NAME:.metadata.name,PHASE:.status.phase,MESSAGE:.status.message" 2>/dev/null \
  || echo "(not found)"

echo ""
echo "=== Inner migration-workflow ==="
kubectl exec -n "$NAMESPACE" migration-console-0 -- \
  /bin/bash -lc 'workflow status 2>&1' 2>/dev/null || echo "(not available)"

echo ""
echo "=== RFS worker ==="
RFS_POD=$(kubectl get pods -n "$NAMESPACE" -l "app.kubernetes.io/component=rfs" \
  --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
if [ -n "$RFS_POD" ]; then
  echo "Pod: $RFS_POD"
  kubectl logs "$RFS_POD" -n "$NAMESPACE" --tail=5 2>/dev/null | \
    grep -E "heartbeat|docs=" || echo "(no heartbeat lines yet)"
else
  echo "(no running RFS pod — may be complete)"
fi

echo ""
echo "=== Document count on target ==="
kubectl exec -n "$NAMESPACE" migration-console-0 -- /bin/bash -lc \
  "curl -sk -u ${TARGET_USER}:'${TARGET_PASS}' '${TARGET_URL}/ecommerce/_count'" 2>/dev/null \
  | python3 -m json.tool 2>/dev/null || echo "(could not reach target)"

echo ""
echo "=== Traffic replayer ==="
REPLAY_POD=$(kubectl get pods -n "$NAMESPACE" -l "app.kubernetes.io/component=traffic-replayer" \
  --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
if [ -n "$REPLAY_POD" ]; then
  echo "Pod: $REPLAY_POD"
  kubectl logs "$REPLAY_POD" -n "$NAMESPACE" --tail=3 2>/dev/null | \
    grep -E "ReplayHeartbeat|AccumulatorH" || echo "(no heartbeat lines yet)"
else
  echo "(no running replayer pod)"
fi
