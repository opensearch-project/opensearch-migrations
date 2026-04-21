#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"

CLUSTER_NAME="${WORKFLOW_TEST_KIND_CLUSTER_NAME:-console-link-test}"
EXPECTED_CONTEXT="${WORKFLOW_TEST_KUBE_CONTEXT:-kind-${CLUSTER_NAME}}"
ARGO_VERSION="${WORKFLOW_TEST_ARGO_VERSION:-v3.7.3}"
ARGO_NAMESPACE="${WORKFLOW_TEST_ARGO_NAMESPACE:-argo}"
ARGO_MANIFEST_URL="https://github.com/argoproj/argo-workflows/releases/download/${ARGO_VERSION}/quick-start-minimal.yaml"

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

need_cmd kind
need_cmd kubectl
need_cmd curl

cluster_exists() {
  kind get clusters | grep -Fxq "${CLUSTER_NAME}"
}

wait_for_deployment() {
  local namespace="$1"
  local deployment="$2"
  echo "Waiting for deployment ${deployment} in namespace ${namespace}..."
  kubectl -n "${namespace}" rollout status "deployment/${deployment}" --timeout=300s
}

echo "Repo root: ${REPO_ROOT}"
echo "Kind cluster name: ${CLUSTER_NAME}"
echo "Expected kube context: ${EXPECTED_CONTEXT}"

if cluster_exists; then
  echo "Kind cluster ${CLUSTER_NAME} already exists; reusing it."
else
  echo "Creating kind cluster ${CLUSTER_NAME}..."
  kind create cluster --name "${CLUSTER_NAME}"
fi

ACTIVE_CONTEXT="$(kubectl config current-context || true)"
if [[ "${ACTIVE_CONTEXT}" != "${EXPECTED_CONTEXT}" ]]; then
  echo "Switching kubectl context to ${EXPECTED_CONTEXT}..."
  kubectl config use-context "${EXPECTED_CONTEXT}" >/dev/null
fi

echo "Verifying cluster connectivity..."
kubectl cluster-info
kubectl get nodes -o wide
kubectl get namespaces

echo "Ensuring namespace ${ARGO_NAMESPACE} exists..."
kubectl get namespace "${ARGO_NAMESPACE}" >/dev/null 2>&1 || kubectl create namespace "${ARGO_NAMESPACE}"

echo "Installing Argo Workflows ${ARGO_VERSION} from ${ARGO_MANIFEST_URL}..."
curl -fsSL "${ARGO_MANIFEST_URL}" | kubectl apply -n "${ARGO_NAMESPACE}" -f -

wait_for_deployment "${ARGO_NAMESPACE}" "workflow-controller"
wait_for_deployment "${ARGO_NAMESPACE}" "argo-server"

echo
echo "Workflow test cluster is ready."
echo "Current context: $(kubectl config current-context)"
echo "Suggested test command:"
echo "  cd ${REPO_ROOT}/migrationConsole/lib/console_link"
echo "  pipenv run pytest -vv -s tests/workflow-tests/test_workflow_integration.py -k submit_hello_world"
