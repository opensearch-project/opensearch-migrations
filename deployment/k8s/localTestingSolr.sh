#!/usr/bin/env bash

# Similar to localTesting.sh but installs testSolrOSClusters (Solr + OpenSearch + shimProxy)
# instead of testClusters + migrationAssistantWithArgo.

set -xeuo pipefail

MIGRATIONS_REPO_ROOT_DIR=$(git rev-parse --show-toplevel)

## One time things - will require a restart to minikube if it was already running
MINIKUBE_CPU_COUNT="${MINIKUBE_CPU_COUNT:-8}"
MINIKUBE_MEMORY_SIZE="${MINIKUBE_MEMORY_SIZE:-18000}"
echo "Setting minikube config to use ${MINIKUBE_CPU_COUNT} CPUs and ${MINIKUBE_MEMORY_SIZE} MB memory."
minikube config set cpus $MINIKUBE_CPU_COUNT
minikube config set memory $MINIKUBE_MEMORY_SIZE

INSECURE_REGISTRY_CIDR="${INSECURE_REGISTRY_CIDR:-0.0.0.0/0}"
if minikube status --format='{{.Host}}' 2>/dev/null | grep -q Running; then
  echo "minikube is already running, skipping start"
else
  minikube start \
    --extra-config=kubelet.authentication-token-webhook=true \
    --extra-config=kubelet.authorization-mode=Webhook \
    --extra-config=scheduler.bind-address=0.0.0.0 \
    --extra-config=controller-manager.bind-address=0.0.0.0 \
    --insecure-registry="${INSECURE_REGISTRY_CIDR}"
fi

cd "${MIGRATIONS_REPO_ROOT_DIR}"
gradlew() {
    "${MIGRATIONS_REPO_ROOT_DIR}/gradlew" "$@"
}

export KUBE_CONTEXT="${KUBE_CONTEXT:-minikube}"

export USE_LOCAL_REGISTRY="${USE_LOCAL_REGISTRY:-true}"
export BUILDKIT_HELM_ARGS="--set buildkitd.resources.requests.cpu=0 --set buildkitd.resources.requests.memory=0 --set buildkitd.resources.limits.cpu=0 --set buildkitd.resources.limits.memory=0"
"${MIGRATIONS_REPO_ROOT_DIR}"/buildImages/setUpK8sImageBuildServices.sh

BUILDER_NAME="builder-${KUBE_CONTEXT//[^a-zA-Z0-9_-]/-}"

LOCAL_REGISTRY_PORT="${LOCAL_REGISTRY_PORT:-30500}"
MINIKUBE_IP="$(minikube ip)"
LOCAL_REGISTRY="${MINIKUBE_IP}:${LOCAL_REGISTRY_PORT}"
export LOCAL_REGISTRY
echo "Using local registry at: ${LOCAL_REGISTRY}"

ARCH=$(uname -m)
case "$ARCH" in
  x86_64)
    PLATFORM="amd64"
    ;;
  arm64|aarch64)
    PLATFORM="arm64"
    ;;
  *)
    echo "Unsupported architecture: $ARCH"
    exit 1
    ;;
esac

gradlew :buildImages:buildImagesToRegistry_$PLATFORM -Pbuilder="$BUILDER_NAME"

# Build and push the data loader image to the local registry
# Use docker-registry:5000 (in-cluster name) so buildkitd's insecure registry config applies
echo "Building dataloader image..."
docker buildx build --builder "${BUILDER_NAME}" \
  --platform "linux/${PLATFORM}" \
  -f deployment/k8s/charts/aggregates/testSolrOSClusters/Dockerfile.dataloader \
  -t "docker-registry:5000/migrations/dataloader:latest" \
  --push \
  "${MIGRATIONS_REPO_ROOT_DIR}"

NAMESPACE="${NAMESPACE:-solr-test}"
kubectl config set-context --current --namespace="${NAMESPACE}"

# Nice to have additions to minikube
minikube addons enable metrics-server

cd "${MIGRATIONS_REPO_ROOT_DIR}"/deployment/k8s/

# Helm install
helm dependency update charts/aggregates/testSolrOSClusters

if [ "${USE_LOCAL_REGISTRY:-false}" = "true" ]; then
  echo "Using LOCAL_REGISTRY for images: ${LOCAL_REGISTRY}"
  helm upgrade --install --create-namespace -n "${NAMESPACE}" tc charts/aggregates/testSolrOSClusters \
    --wait --timeout 10m \
    --set "shimProxy.image.repository=${LOCAL_REGISTRY}/migrations/transformation_shim" \
    --set "shimProxy.image.tag=latest" \
    --set "shimProxy.image.pullPolicy=Always" \
    --set "shimProxy.targets.solr=http://tc-solr:8983" \
    --set "dataLoader.image.repository=${LOCAL_REGISTRY}/migrations/dataloader" \
    --set "dataLoader.image.tag=latest" \
    --set "dataLoader.image.pullPolicy=Always" \
    --set-file "shimProxy.transformFiles.solr-to-opensearch-request\.js=${MIGRATIONS_REPO_ROOT_DIR}/TrafficCapture/SolrTransformations/transforms/dist/solr-to-opensearch-request.js" \
    --set-file "shimProxy.transformFiles.solr-to-opensearch-response\.js=${MIGRATIONS_REPO_ROOT_DIR}/TrafficCapture/SolrTransformations/transforms/dist/solr-to-opensearch-response.js"
else
  echo "Using non-local registry (USE_LOCAL_REGISTRY=false). Adjust repositories as needed."
  helm upgrade --install --create-namespace -n "${NAMESPACE}" tc charts/aggregates/testSolrOSClusters \
    --wait --timeout 10m \
    --set-file "shimProxy.transformFiles.solr-to-opensearch-request\.js=${MIGRATIONS_REPO_ROOT_DIR}/TrafficCapture/SolrTransformations/transforms/dist/solr-to-opensearch-request.js" \
    --set-file "shimProxy.transformFiles.solr-to-opensearch-response\.js=${MIGRATIONS_REPO_ROOT_DIR}/TrafficCapture/SolrTransformations/transforms/dist/solr-to-opensearch-response.js"
fi

echo ""
echo "✅  testSolrOSClusters deployed to namespace '${NAMESPACE}'"
echo ""
echo "To check pod status:"
echo "  kubectl -n ${NAMESPACE} get pods"
echo ""
echo "To access services via NodePort:"
echo "  minikube service list -n ${NAMESPACE}"
echo " -- Starting Port Forwards --"

kubectl -n "${NAMESPACE}" port-forward svc/opensearch-cluster-master 9200:9200 &
PF_OS_PID=$!
kubectl -n "${NAMESPACE}" port-forward svc/tc-solr 8983:8983 &
PF_SOLR_PID=$!
kubectl -n "${NAMESPACE}" port-forward svc/tc 8080:8080 &
PF_SHIM_PID=$!

# Wait for port-forwards to be ready
sleep 5

echo ""
echo "✅  Data loading runs as a Kubernetes Job (tc-dataloader)."
echo "    Monitor with: kubectl -n ${NAMESPACE} logs -f job/tc-dataloader"
echo ""
echo "Port forwards running in background (PIDs: ${PF_OS_PID}, ${PF_SOLR_PID}, ${PF_SHIM_PID})"
echo "To stop them: kill ${PF_OS_PID} ${PF_SOLR_PID} ${PF_SHIM_PID}"

# Keep port-forwards alive
wait


