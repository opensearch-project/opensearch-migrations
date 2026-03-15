#!/usr/bin/env bash

set -xeuo pipefail

MIGRATIONS_REPO_ROOT_DIR=$(git rev-parse --show-toplevel)

## One time things - will require a restart to minikube if it was already running
MINIKUBE_CPU_COUNT="${MINIKUBE_CPU_COUNT:-8}"
MINIKUBE_MEMORY_SIZE="${MINIKUBE_MEMORY_SIZE:-18000}"
echo "Setting minikube config to use ${MINIKUBE_CPU_COUNT} CPUs and ${MINIKUBE_MEMORY_SIZE} MB memory."
minikube config set cpus $MINIKUBE_CPU_COUNT
minikube config set memory $MINIKUBE_MEMORY_SIZE

INSECURE_REGISTRY_CIDR="${INSECURE_REGISTRY_CIDR:-0.0.0.0/0}"
minikube start \
  --extra-config=kubelet.authentication-token-webhook=true \
  --extra-config=kubelet.authorization-mode=Webhook \
  --extra-config=scheduler.bind-address=0.0.0.0 \
  --extra-config=controller-manager.bind-address=0.0.0.0 \
  --insecure-registry="${INSECURE_REGISTRY_CIDR}"

cd "${MIGRATIONS_REPO_ROOT_DIR}"
gradlew() {
    "${MIGRATIONS_REPO_ROOT_DIR}/gradlew" "$@"
}

export KUBE_CONTEXT="${KUBE_CONTEXT:-minikube}"

export USE_LOCAL_REGISTRY="${USE_LOCAL_REGISTRY:-true}"
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
    JIB_LOCAL_TAG="latest_amd64"
    ;;
  arm64|aarch64)
    PLATFORM="arm64"
    JIB_LOCAL_TAG="latest_arm64"
    ;;
  *)
    echo "Unsupported architecture: $ARCH"
    exit 1
    ;;
esac

# Host-side registry publishing is already configured by the build image tooling
# (see buildImages defaults / registry port-forward setup). Keep localTesting.sh
# focused on the in-cluster registry endpoint used for minikube preloading.
gradlew :buildImages:buildImagesToRegistry_$PLATFORM \
  -Pbuilder="$BUILDER_NAME"

# Preload exact image refs into minikube to avoid local-registry HTTPS/insecure-registry drift.
# BuildKit-built images publish as :latest, Jib-built images publish as :latest_<arch>.
PRELOAD_SPECS=(
  "migrations/elasticsearch_searchguard:latest"
  "migrations/migration_console:latest"
  "migrations/reindex_from_snapshot:latest"
  "migrations/capture_proxy:${JIB_LOCAL_TAG}"
  "migrations/traffic_replayer:${JIB_LOCAL_TAG}"
)

for spec in "${PRELOAD_SPECS[@]}"; do
  cluster_ref="${LOCAL_REGISTRY}/${spec}"
  echo "Preloading ${cluster_ref} directly into minikube containerd"
  minikube ssh -- "sudo ctr -n k8s.io images pull --plain-http ${cluster_ref}"
  echo "Tagging in-cluster alias ${spec}"
  minikube ssh -- "sudo ctr -n k8s.io images tag ${cluster_ref} ${spec} || true"
done

kubectl config set-context --current --namespace=ma

# Nice to have additions to minikube
minikube addons enable metrics-server

cd "${MIGRATIONS_REPO_ROOT_DIR}"/deployment/k8s/

# Helm installs
helm dependency update charts/aggregates/testClusters
helm dependency update charts/aggregates/migrationAssistantWithArgo

if [ "${USE_LOCAL_REGISTRY:-false}" = "true" ]; then
  echo "Using LOCAL_REGISTRY for images: ${LOCAL_REGISTRY}"
  helm upgrade --install --create-namespace -n ma tc charts/aggregates/testClusters \
      --wait --timeout 10m \
      --set "source.image=migrations/elasticsearch_searchguard" \
      --set "source.imageTag=latest"

  helm upgrade --install --create-namespace -n ma ma charts/aggregates/migrationAssistantWithArgo \
    --wait --timeout 10m \
    -f charts/aggregates/migrationAssistantWithArgo/valuesForLocalK8s.yaml \
    --set "images.captureProxy.repository=${LOCAL_REGISTRY}/migrations/capture_proxy" \
    --set "images.captureProxy.tag=${JIB_LOCAL_TAG}" \
    --set "images.captureProxy.pullPolicy=IfNotPresent" \
    --set "images.installer.repository=${LOCAL_REGISTRY}/migrations/migration_console" \
    --set "images.installer.tag=latest" \
    --set "images.installer.pullPolicy=IfNotPresent" \
    --set "images.migrationConsole.repository=${LOCAL_REGISTRY}/migrations/migration_console" \
    --set "images.migrationConsole.tag=latest" \
    --set "images.migrationConsole.pullPolicy=IfNotPresent" \
    --set "images.trafficReplayer.repository=${LOCAL_REGISTRY}/migrations/traffic_replayer" \
    --set "images.trafficReplayer.tag=${JIB_LOCAL_TAG}" \
    --set "images.trafficReplayer.pullPolicy=IfNotPresent" \
    --set "images.reindexFromSnapshot.repository=${LOCAL_REGISTRY}/migrations/reindex_from_snapshot" \
    --set "images.reindexFromSnapshot.tag=latest" \
    --set "images.reindexFromSnapshot.pullPolicy=IfNotPresent"
else
  echo "Using non-local registry (USE_LOCAL_REGISTRY=false). Adjust repositories as needed."
  helm install --create-namespace -n ma tc charts/aggregates/testClusters \
    --wait --timeout 10m

  helm install --create-namespace -n ma ma charts/aggregates/migrationAssistantWithArgo \
    --wait --timeout 10m \
    -f charts/aggregates/migrationAssistantWithArgo/valuesForLocalK8s.yaml
fi


# Other useful stuff...

# minikube dashboard &

# Port forwards
# ./devScripts/forwardAllServicePorts.sh

# Grafana password...
# kubectl --namespace ma get secrets kube-prometheus-stack-grafana -o jsonpath="{.data.admin-password}" | base64 -d ; echo

# Open a migration console terminal on the stateful set that was deployed
# kubectl -n ma exec --stdin --tty migration-console-0 -- /bin/bash
