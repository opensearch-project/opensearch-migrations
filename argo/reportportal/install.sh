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

echo "Waiting for ReportPortal API to be ready..."
kubectl wait --for=condition=ready pod -l component=reportportal-api -n reportportal --timeout=120s 2>/dev/null || true
kubectl wait --for=condition=ready pod -l component=reportportal-uat -n reportportal --timeout=120s 2>/dev/null || true
sleep 5

# Get API token via OAuth
echo "Obtaining API token..."
RP_API="http://reportportal-api.reportportal.svc:8585"
RP_UAT="http://reportportal-uat.reportportal.svc:9999"

TOKEN_JSON=$(kubectl run rp-get-token --rm -i --restart=Never -n reportportal \
  --image=curlimages/curl:latest -- \
  curl -sf -X POST "${RP_UAT}/uat/sso/oauth/token" \
  -d "grant_type=password&username=superadmin&password=erebus" \
  -H "Authorization: Basic dWk6dWltYW4=" 2>/dev/null || echo '{}')

ACCESS_TOKEN=$(echo "${TOKEN_JSON}" | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)

if [ -z "${ACCESS_TOKEN}" ]; then
  echo "Warning: Could not obtain API token. Using default."
  ACCESS_TOKEN="superadmin_personal"
fi

# Create project (idempotent — 409 if exists)
kubectl run rp-create-project --rm -i --restart=Never -n reportportal \
  --image=curlimages/curl:latest -- \
  curl -sf -X POST "${RP_API}/api/v1/project" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"projectName":"opensearch_migrations","entryType":"INTERNAL"}' 2>/dev/null || true

# Create API token secret in ma namespace for workflow access
kubectl -n ma create secret generic reportportal-token \
  --from-literal=token="${ACCESS_TOKEN}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo ""
echo "ReportPortal installed!"
echo "UI: kubectl -n reportportal port-forward svc/reportportal-ui 8080:8080"
echo "Then open: http://localhost:8080"
echo "Login: superadmin / erebus"
echo "Project: opensearch_migrations (auto-created)"
