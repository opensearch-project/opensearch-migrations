#!/usr/bin/env bash
#
# test_workflow_run.sh — smoke-test the k6 load-test Argo integration.
#
# Assumes the surrounding infra is ALREADY running in the cluster (Argo Workflows, the
# otel-collector, and kube-prometheus-stack. See buildImages/scripts folder for the startup scripts)
# — e.g. a migration-assistant deployment on a
# kind (or other local k8s setup) cluster. It does NOT deploy that stack. It:
#   1. builds the migrations/k6 image and loads it into the (kind) cluster,
#   2. wires k6Image into the migration-image-config ConfigMap,
#   3. applies the k6-load-test WorkflowTemplate,
#   4. deploys a throwaway single-node OpenSearch as the target (unless TARGET_URL is given),
#   5. submits one ingest run and waits for it,
#   6. verifies: workflow Succeeded, docs written, and k6 metrics reached Prometheus.
#
# Usage:
#   ./test_workflow_run.sh [--no-build] [--keep] [--cleanup-only]
#
# Env overrides (defaults match the dev kind cluster used during development):
#   CLUSTER=ma NAMESPACE=ma CONTEXT=kind-ma
#   SCENARIO=ingest CONFIG_NAME=ingest-steady DURATION=30s RATE=10
#   TARGET_URL=            # if set, skips deploying OpenSearch and points k6 there
#   K6_IMAGE=migrations/k6:latest
#   PROM_SVC=kube-prometheus-stack-prometheus
set -euo pipefail

# ── config ────────────────────────────────────────────────────────────────────
CLUSTER="${CLUSTER:-ma}"
NAMESPACE="${NAMESPACE:-ma}"
CONTEXT="${CONTEXT:-kind-${CLUSTER}}"
SCENARIO="${SCENARIO:-ingest}"
CONFIG_NAME="${CONFIG_NAME:-ingest-steady}"
DURATION="${DURATION:-30s}"
RATE="${RATE:-10}"
K6_IMAGE="${K6_IMAGE:-migrations/k6:latest}"
PROM_SVC="${PROM_SVC:-kube-prometheus-stack-prometheus}"
TARGET_URL="${TARGET_URL:-}"
INDEX="${INDEX:-nyc_taxis}"          # ingest default doc-type → index name

NO_BUILD=0; KEEP=0; CLEANUP_ONLY=0
for a in "$@"; do
  case "$a" in
    --no-build)     NO_BUILD=1 ;;
    --keep)         KEEP=1 ;;
    --cleanup-only) CLEANUP_ONLY=1 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "unknown arg: $a" >&2; exit 2 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null)" || REPO_ROOT=""
[ -n "$REPO_ROOT" ] || REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
K6_CONTEXT_DIR="$REPO_ROOT/TrafficCapture/trafficLoadTest"
WF_TEMPLATE="$REPO_ROOT/deployment/k8s/charts/aggregates/migrationAssistantWithArgo/files/k6/workflowTemplate.yaml"
OS_APP="k6-smoke-opensearch"

K() { kubectl --context "$CONTEXT" -n "$NAMESPACE" "$@"; }
say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
die() { printf '\033[1;31mFAIL: %s\033[0m\n' "$*" >&2; exit 1; }

cleanup() {
  say "Cleanup"
  K delete workflow -l app=k6-load-test --ignore-not-found >/dev/null 2>&1 || true
  K delete deploy,svc -l app="$OS_APP" --ignore-not-found >/dev/null 2>&1 || true
  echo "removed smoke workflows + throwaway OpenSearch (WorkflowTemplate + k6 image left in place)"
}

if [ "$CLEANUP_ONLY" = 1 ]; then cleanup; exit 0; fi

# ── preflight ─────────────────────────────────────────────────────────────────
say "Preflight (context=$CONTEXT ns=$NAMESPACE)"
kubectl --context "$CONTEXT" get ns "$NAMESPACE" >/dev/null 2>&1 || die "namespace $NAMESPACE not found on $CONTEXT"
K get crd workflowtemplates.argoproj.io >/dev/null 2>&1 || die "Argo Workflows not installed (no WorkflowTemplate CRD)"
K get svc otel-collector >/dev/null 2>&1 || echo "  warn: otel-collector Service not found — metrics push may fail"
K get svc "$PROM_SVC" >/dev/null 2>&1 || echo "  warn: Prometheus Service '$PROM_SVC' not found — metrics check will be skipped"
echo "  ok"

# ── 1. build + load image ─────────────────────────────────────────────────────
if [ "$NO_BUILD" = 1 ]; then
  say "Skipping image build (--no-build)"
else
  say "Build k6 image ($K6_IMAGE)"
  docker build -t "$K6_IMAGE" "$K6_CONTEXT_DIR"
  say "Load image into kind cluster '$CLUSTER'"
  kind load docker-image "$K6_IMAGE" --name "$CLUSTER"
fi

# ── 2. image config + 3. WorkflowTemplate ─────────────────────────────────────
say "Wire k6Image into migration-image-config"
K patch cm migration-image-config --type merge \
  -p "{\"data\":{\"k6Image\":\"${K6_IMAGE}\",\"k6PullPolicy\":\"IfNotPresent\"}}"

say "Apply k6-load-test WorkflowTemplate"
[ -f "$WF_TEMPLATE" ] || die "WorkflowTemplate not found: $WF_TEMPLATE"
K apply -f "$WF_TEMPLATE"

# ── 4. target ─────────────────────────────────────────────────────────────────
CURL_POD=""   # a pod with curl, used for verification
if [ -n "$TARGET_URL" ]; then
  say "Using provided TARGET_URL=$TARGET_URL (not deploying OpenSearch)"
  CURL_POD="$(K get pod -l app=migration-console -o name 2>/dev/null | head -1)"
else
  TARGET_URL="http://${OS_APP}:9200"
  say "Deploy throwaway OpenSearch target ($OS_APP)"
  K apply -f - <<YAML
apiVersion: apps/v1
kind: Deployment
metadata: { name: $OS_APP, labels: { app: $OS_APP } }
spec:
  replicas: 1
  selector: { matchLabels: { app: $OS_APP } }
  template:
    metadata: { labels: { app: $OS_APP } }
    spec:
      containers:
        - name: opensearch
          image: mirror.gcr.io/opensearchproject/opensearch:2.15.0
          env:
            - { name: discovery.type,               value: single-node }
            - { name: DISABLE_INSTALL_DEMO_CONFIG,  value: "true" }
            - { name: plugins.security.disabled,    value: "true" }
            - { name: bootstrap.memory_lock,        value: "false" }
            - { name: OPENSEARCH_JAVA_OPTS,         value: "-Xms512m -Xmx512m" }
          ports: [ { containerPort: 9200 } ]
          readinessProbe:
            httpGet: { path: /_cluster/health, port: 9200 }
            initialDelaySeconds: 20
            periodSeconds: 5
          resources:
            requests: { cpu: 250m, memory: 1Gi }
            limits:   { cpu: "1",  memory: 2Gi }
---
apiVersion: v1
kind: Service
metadata: { name: $OS_APP, labels: { app: $OS_APP } }
spec:
  selector: { app: $OS_APP }
  ports: [ { port: 9200, targetPort: 9200 } ]
YAML
  K rollout status deploy/$OS_APP --timeout=240s
  CURL_POD="$(K get pod -l app=$OS_APP -o name | head -1)"
fi

# ── 5. submit run ─────────────────────────────────────────────────────────────
say "Submit k6 run (scenario=$SCENARIO config=$CONFIG_NAME rate=$RATE duration=$DURATION)"
WF="$(K create -o name -f - <<YAML | sed 's|.*/||'
apiVersion: argoproj.io/v1alpha1
kind: Workflow
metadata:
  generateName: k6-smoke-
  labels: { app: k6-load-test, k6-scenario: "$SCENARIO" }
spec:
  serviceAccountName: argo-workflow-executor
  workflowTemplateRef: { name: k6-load-test }
  arguments:
    parameters:
      - { name: scenario,   value: "$SCENARIO" }
      - { name: configName, value: "$CONFIG_NAME" }
      - { name: targetUrl,  value: "$TARGET_URL" }
      - { name: duration,   value: "$DURATION" }
      - { name: rate,       value: "$RATE" }
      - { name: overrides,  value: "SEED_DOC_COUNT=0" }
YAML
)"
echo "  workflow: $WF"

say "Wait for completion"
PHASE=""
for i in $(seq 1 30); do
  PHASE="$(K get workflow "$WF" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
  printf '  [%3ds] phase=%s\n' "$((i*10))" "${PHASE:-Pending}"
  case "$PHASE" in Succeeded|Failed|Error) break ;; esac
  sleep 10
done

POD="$(K get pods -l workflows.argoproj.io/workflow="$WF" -o name 2>/dev/null | head -1)"

# ── 6. verify ─────────────────────────────────────────────────────────────────
say "Verify"
RC=0

# 6a. workflow phase
if [ "$PHASE" = "Succeeded" ]; then echo "  ✓ workflow Succeeded"; else
  echo "  ✗ workflow phase=$PHASE"; RC=1
  [ -n "$POD" ] && K logs "$POD" -c main 2>/dev/null | tail -25
fi

# 6b. docs written to the target index (only checkable when we control a curl pod)
if [ -n "$CURL_POD" ]; then
  CNT="$(K exec "$CURL_POD" -- sh -c "curl -sk \"${TARGET_URL}/${INDEX}/_count\"" 2>/dev/null \
         | sed -n 's/.*\"count\":\([0-9]*\).*/\1/p')"
  if [ -n "${CNT:-}" ] && [ "$CNT" -gt 0 ] 2>/dev/null; then
    echo "  ✓ docs in '$INDEX': $CNT"
  else
    echo "  ✗ no docs found in '$INDEX' (count='${CNT:-?}')"; RC=1
  fi
else
  echo "  ~ doc check skipped (no reachable curl pod)"
fi

# 6c. k6 metrics reached Prometheus via the otel-collector
if K get svc "$PROM_SVC" >/dev/null 2>&1 && [ -n "$CURL_POD" ]; then
  Q='http_reqs_total{exported_job="k6"}'
  RESULT="$(K exec "$CURL_POD" -- sh -c \
    "curl -s --data-urlencode 'query=${Q}' http://${PROM_SVC}:9090/api/v1/query" 2>/dev/null || true)"
  if printf '%s' "$RESULT" | grep -q '"result":\[{'; then
    echo "  ✓ k6 metrics present in Prometheus (http_reqs_total{exported_job=\"k6\"})"
  else
    echo "  ✗ k6 metrics not found in Prometheus (OTLP path?)"; RC=1
  fi
else
  echo "  ~ metrics check skipped"
fi

# ── result + cleanup ──────────────────────────────────────────────────────────
if [ "$KEEP" = 1 ]; then
  say "Left running (--keep). Clean up later with: $0 --cleanup-only"
else
  cleanup
fi

if [ "$RC" = 0 ]; then
  printf '\n\033[1;32m==== SMOKE TEST PASSED ====\033[0m\n'
else
  printf '\n\033[1;31m==== SMOKE TEST FAILED ====\033[0m\n'
fi
exit "$RC"
