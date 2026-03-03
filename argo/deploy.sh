#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

usage() {
  cat <<EOF
Usage: $0 [OPTIONS]

Deploy Argo CI/CD components on minikube, iteratively or all together.

Options:
  --all           Run all priorities P0-P5 (default if no options given)
  --p0            P0: Infrastructure (minikube, Argo Workflows, Events, MinIO)
  --p1            P1: Apply CI WorkflowTemplates
  --p2            P2: Apply integration test workflow
  --p3            P3: Apply matrix test + cron + stage locks
  --p4            P4: Apply Argo Events (EventSource + Sensors)
  --p5            P5: Install ReportPortal + upload templates
  --skip-infra    Skip P0 infrastructure (assume already running)
  --test          Run a quick smoke test after deployment
  -h, --help      Show this help

Examples:
  $0 --all                    # Full setup from scratch
  $0 --p0                     # Just infrastructure
  $0 --skip-infra --p1 --p2   # Apply P1+P2 templates (infra already up)
  $0 --all --test             # Full setup + smoke test
EOF
  exit 0
}

# Parse args
RUN_P0=false RUN_P1=false RUN_P2=false RUN_P3=false RUN_P4=false RUN_P5=false
RUN_ALL=false SKIP_INFRA=false RUN_TEST=false

if [ $# -eq 0 ]; then
  RUN_ALL=true
fi

while [ $# -gt 0 ]; do
  case "$1" in
    --all)        RUN_ALL=true ;;
    --p0)         RUN_P0=true ;;
    --p1)         RUN_P1=true ;;
    --p2)         RUN_P2=true ;;
    --p3)         RUN_P3=true ;;
    --p4)         RUN_P4=true ;;
    --p5)         RUN_P5=true ;;
    --skip-infra) SKIP_INFRA=true ;;
    --test)       RUN_TEST=true ;;
    -h|--help)    usage ;;
    *)            echo "Unknown option: $1"; usage ;;
  esac
  shift
done

if $RUN_ALL; then
  RUN_P0=true RUN_P1=true RUN_P2=true RUN_P3=true RUN_P4=true RUN_P5=true
fi

echo "=========================================="
echo "  Argo CI/CD Deployment"
echo "=========================================="

# --- P0: Infrastructure ---
if $RUN_P0 && ! $SKIP_INFRA; then
  echo ""
  echo ">>> P0: Infrastructure Setup"
  "${SCRIPT_DIR}/setup-minikube-ci.sh"

  # Validate P0
  echo "  Validating P0..."
  kubectl get pods -n ma -l app.kubernetes.io/name=argo-workflows --no-headers | head -1 || \
    { echo "ERROR: Argo Workflows not running"; exit 1; }
  kubectl get pods -n argo-events --no-headers | head -1 || \
    { echo "ERROR: Argo Events not running"; exit 1; }
  kubectl get pods -n minio --no-headers | head -1 || \
    { echo "ERROR: MinIO not running"; exit 1; }
  echo ">>> P0: Complete (validated)"
fi

# --- P1: CI Templates ---
if $RUN_P1; then
  echo ""
  echo ">>> P1: Applying CI WorkflowTemplates"
  kubectl apply -n ma -f "${SCRIPT_DIR}/workflows/common-ci-steps.yaml"
  kubectl apply -n ma -f "${SCRIPT_DIR}/workflows/build-images.yaml"
  kubectl apply -n ma -f "${SCRIPT_DIR}/workflows/argo-ci-pipeline.yaml"

  # Validate P1
  echo "  Validating P1..."
  for tmpl in common-ci-steps build-images argo-ci-pipeline; do
    kubectl get workflowtemplate -n ma "${tmpl}" --no-headers || \
      { echo "ERROR: WorkflowTemplate ${tmpl} not found"; exit 1; }
  done
  echo ">>> P1: Complete"
  echo "  Templates: common-ci-steps, build-images, argo-ci-pipeline"
fi

# --- P2: Integration Test ---
if $RUN_P2; then
  echo ""
  echo ">>> P2: Applying Integration Test Workflow"
  kubectl apply -n ma -f "${SCRIPT_DIR}/workflows/k8s-local-test.yaml"
  echo ">>> P2: Complete"
  echo "  Template: k8s-local-test"
  echo "  Test: argo submit -n ma ${SCRIPT_DIR}/examples/run-single-test.yaml --watch"
fi

# --- P3: Matrix + Cron + Stage Locks ---
if $RUN_P3; then
  echo ""
  echo ">>> P3: Applying Matrix Test + Cron + Stage Locks + AWS Integ Templates"
  kubectl apply -n ma -f "${SCRIPT_DIR}/ci-stage-locks-configmap.yaml"
  kubectl apply -n ma -f "${SCRIPT_DIR}/workflows/k8s-matrix-test.yaml"
  kubectl apply -n ma -f "${SCRIPT_DIR}/cron/nightly-matrix.yaml"
  # AWS integration test templates (for production use)
  kubectl apply -n ma -f "${SCRIPT_DIR}/workflows/aws-integ/" 2>/dev/null || \
    echo "  (some aws-integ templates skipped — may reference missing CRDs on minikube)"

  # Validate P3
  echo "  Validating P3..."
  kubectl get workflowtemplate -n ma k8s-matrix-test --no-headers || true
  kubectl get cronworkflow -n ma nightly-k8s-matrix --no-headers || true
  kubectl get configmap -n ma ci-stage-locks --no-headers || true
  echo ">>> P3: Complete"
  echo "  Templates: k8s-matrix-test + AWS integ pipelines"
  echo "  CronWorkflow: nightly-k8s-matrix (0 22 * * * UTC)"
  echo "  Test: argo submit -n ma ${SCRIPT_DIR}/examples/run-matrix.yaml --watch"
fi

# --- P4: Argo Events ---
if $RUN_P4; then
  echo ""
  echo ">>> P4: Applying Argo Events"

  # Create secrets for local testing
  kubectl create secret generic github-webhook-secret \
    -n argo-events --from-literal=secret=my-test-secret \
    --dry-run=client -o yaml | kubectl apply -f -
  kubectl create secret generic github-token \
    -n argo-events --from-literal=token=ghp_placeholder \
    --dry-run=client -o yaml | kubectl apply -f -

  kubectl apply -f "${SCRIPT_DIR}/events/eventsource-github.yaml"
  kubectl apply -f "${SCRIPT_DIR}/events/sensor-ci-pipeline.yaml"
  kubectl apply -f "${SCRIPT_DIR}/events/sensor-integ-tests.yaml"
  kubectl apply -f "${SCRIPT_DIR}/events/sensor-release.yaml"
  echo ">>> P4: Complete"
  echo "  EventSource: github-migrations"
  echo "  Sensors: ci-pipeline-sensor, integ-test-sensor, release-sensor"
  echo "  Test webhook:"
  echo "    kubectl port-forward -n argo-events svc/github-migrations-eventsrc-svc 12000:12000 &"
  echo '    PAYLOAD='"'"'{"action":"opened","pull_request":{"number":123,"head":{"ref":"test","sha":"abc123","repo":{"clone_url":"https://github.com/opensearch-project/opensearch-migrations.git"}}}}'"'"''
  echo '    SIGNATURE=$(echo -n "$PAYLOAD" | openssl dgst -sha256 -hmac "my-test-secret" | awk '"'"'{print $2}'"'"')'
  echo '    curl -X POST http://localhost:12000/github/pr -H "Content-Type: application/json" -H "X-GitHub-Event: pull_request" -H "X-Hub-Signature-256: sha256=${SIGNATURE}" -d "$PAYLOAD"'
fi

# --- P5: ReportPortal ---
if $RUN_P5; then
  echo ""
  echo ">>> P5: Installing ReportPortal"
  "${SCRIPT_DIR}/reportportal/install.sh"
  kubectl apply -n ma -f "${SCRIPT_DIR}/workflows/upload-results.yaml"
  echo ">>> P5: Complete"
  echo "  Template: reportportal-upload"
  echo "  UI: kubectl -n reportportal port-forward svc/reportportal-ui 8080:8080"
fi

# --- Smoke Test ---
if $RUN_TEST; then
  echo ""
  echo ">>> Running Smoke Test"
  echo "Verifying all components..."

  echo "  Checking Argo Workflows..."
  kubectl get pods -n ma -l app.kubernetes.io/name=argo-workflows --no-headers | head -3

  echo "  Checking WorkflowTemplates..."
  kubectl get workflowtemplates -n ma --no-headers

  echo "  Checking CronWorkflows..."
  kubectl get cronworkflows -n ma --no-headers 2>/dev/null || echo "  (none)"

  echo "  Checking Argo Events..."
  kubectl get eventsources -n argo-events --no-headers 2>/dev/null || echo "  (none)"
  kubectl get sensors -n argo-events --no-headers 2>/dev/null || echo "  (none)"

  echo "  Checking MinIO..."
  kubectl get pods -n minio --no-headers | head -3

  echo "  Checking ReportPortal..."
  kubectl get pods -n reportportal --no-headers 2>/dev/null | head -3 || echo "  (not installed)"

  echo ""
  echo "  Submitting test git-checkout workflow..."
  argo submit -n ma --from workflowtemplate/common-ci-steps \
    --entrypoint git-checkout \
    -p repo-url=https://github.com/opensearch-project/opensearch-migrations.git \
    -p branch=main \
    --wait --log 2>&1 | tail -20 || echo "  (checkout test completed or failed)"

  echo ""
  echo ">>> Smoke Test Complete"
fi

echo ""
echo "=========================================="
echo "  Deployment Summary"
echo "=========================================="
echo ""
echo "Directory structure:"
find "${SCRIPT_DIR}" -type f | sort | sed "s|${SCRIPT_DIR}/|  argo/|"
echo ""
echo "Quick commands:"
echo "  Argo UI:        kubectl -n ma port-forward svc/argo-workflows-server 2746:2746"
echo "  MinIO UI:       kubectl -n minio port-forward svc/minio-console 9001:9001"
echo "  ReportPortal:   kubectl -n reportportal port-forward svc/reportportal-ui 8080:8080"
echo "  Run single:     argo submit -n ma argo/examples/run-single-test.yaml --watch"
echo "  Run matrix:     argo submit -n ma argo/examples/run-matrix.yaml --watch"
echo "  List workflows: argo list -n ma"
echo "  Trigger cron:   argo cron trigger -n ma nightly-k8s-matrix"
echo ""
