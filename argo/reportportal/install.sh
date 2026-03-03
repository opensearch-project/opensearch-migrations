#!/usr/bin/env bash
set -euo pipefail

echo "Installing ReportPortal on minikube..."

helm repo add reportportal https://reportportal.io/kubernetes 2>/dev/null || true
helm repo update reportportal

kubectl create namespace reportportal 2>/dev/null || true

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

helm upgrade --install reportportal reportportal/reportportal \
  -n reportportal \
  --wait --timeout 10m \
  -f "${SCRIPT_DIR}/values-minikube.yaml"

# Create API token secret in ma namespace for workflow access
RP_TOKEN="superadmin_personal"  # default token for local dev
kubectl -n ma create secret generic reportportal-token \
  --from-literal=token="${RP_TOKEN}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo ""
echo "ReportPortal installed!"
echo "UI: kubectl -n reportportal port-forward svc/reportportal-ui 8080:8080"
echo "Then open: http://localhost:8080"
echo "Login: superadmin / erebus"
echo ""
echo "Create project 'opensearch_migrations' in the UI before running tests."
