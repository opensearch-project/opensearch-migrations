#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"

CLUSTER_NAME="${WORKFLOW_TEST_KIND_CLUSTER_NAME:-console-link-test}"
EXPECTED_CONTEXT="${WORKFLOW_TEST_KUBE_CONTEXT:-kind-${CLUSTER_NAME}}"
ARGO_VERSION="${WORKFLOW_TEST_ARGO_VERSION:-v3.7.3}"
ARGO_NAMESPACE="${WORKFLOW_TEST_ARGO_NAMESPACE:-argo}"
ARGO_MANIFEST_URL="https://github.com/argoproj/argo-workflows/releases/download/${ARGO_VERSION}/quick-start-minimal.yaml"

# Argo's quick-start manifest bundles MinIO as the S3 artifact store. MinIO revoked
# anonymous image pulls (quay.io/Docker Hub now return HTTP 401), so that Deployment
# can no longer start. We replace it with LocalStack -- the same S3-compatible backend
# the rest of this project already uses for Argo artifacts -- from an image the project
# already mirrors. The artifact repository config in the manifest is rewritten to point
# at localstack:4566 (see the sed/awk transform below).
LOCALSTACK_IMAGE="${WORKFLOW_TEST_LOCALSTACK_IMAGE:-mirror.gcr.io/localstack/localstack:4.3.0}"
LOCALSTACK_PORT=4566

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
# Rewrite the artifact repository config on the way in:
#   1. Point every `endpoint: minio:9000` at LocalStack (`localstack:${LOCALSTACK_PORT}`).
#   2. Add `createBucketIfNotPresent: {}` after each `bucket: my-bucket` so Argo creates
#      the bucket on first use (LocalStack, unlike the bundled MinIO, starts empty).
# The dummy `my-minio-cred` credentials are reused as-is; LocalStack does not validate them.
curl -fsSL "${ARGO_MANIFEST_URL}" \
  | awk -v ep="localstack:${LOCALSTACK_PORT}" '
      { if ($0 ~ /endpoint: minio:9000/) sub(/endpoint: minio:9000/, "endpoint: " ep) }
      { print }
      /^[[:space:]]*bucket: my-bucket[[:space:]]*$/ {
        match($0, /^[[:space:]]*/)
        print substr($0, 1, RLENGTH) "createBucketIfNotPresent: {}"
      }' \
  | kubectl apply -n "${ARGO_NAMESPACE}" -f -

# The bundled MinIO Deployment/Service are unusable (image no longer pullable) and now
# unreferenced; remove them and stand up LocalStack in their place.
echo "Removing bundled MinIO artifact store..."
kubectl -n "${ARGO_NAMESPACE}" delete deployment minio --ignore-not-found
kubectl -n "${ARGO_NAMESPACE}" delete service minio --ignore-not-found

echo "Deploying LocalStack S3 artifact store (${LOCALSTACK_IMAGE})..."
kubectl -n "${ARGO_NAMESPACE}" apply -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: localstack
  labels:
    app: localstack
spec:
  replicas: 1
  selector:
    matchLabels:
      app: localstack
  template:
    metadata:
      labels:
        app: localstack
    spec:
      automountServiceAccountToken: false
      containers:
      - name: localstack
        image: ${LOCALSTACK_IMAGE}
        env:
        - name: SERVICES
          value: s3
        - name: EAGER_SERVICE_LOADING
          value: "1"
        ports:
        - containerPort: ${LOCALSTACK_PORT}
          name: edge
        readinessProbe:
          httpGet:
            path: /_localstack/health
            port: ${LOCALSTACK_PORT}
          initialDelaySeconds: 5
          periodSeconds: 5
        livenessProbe:
          httpGet:
            path: /_localstack/health
            port: ${LOCALSTACK_PORT}
          initialDelaySeconds: 20
          periodSeconds: 15
---
apiVersion: v1
kind: Service
metadata:
  name: localstack
  labels:
    app: localstack
spec:
  selector:
    app: localstack
  ports:
  - name: edge
    port: ${LOCALSTACK_PORT}
    targetPort: ${LOCALSTACK_PORT}
    protocol: TCP
EOF

wait_for_deployment "${ARGO_NAMESPACE}" "workflow-controller"
wait_for_deployment "${ARGO_NAMESPACE}" "argo-server"
wait_for_deployment "${ARGO_NAMESPACE}" "localstack"

echo
echo "Workflow test cluster is ready."
echo "Current context: $(kubectl config current-context)"
echo "Suggested test command:"
echo "  cd ${REPO_ROOT}/migrationConsole/lib/console_link"
echo "  pipenv run pytest -vv -s tests/workflow-tests/test_workflow_integration.py -k submit_hello_world"
