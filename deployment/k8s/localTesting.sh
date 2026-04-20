#!/usr/bin/env bash

set -xeuo pipefail

MIGRATIONS_REPO_ROOT_DIR=$(git rev-parse --show-toplevel)

wait_for_cluster_dns() {
  echo "Waiting for CoreDNS to become ready..."
  kubectl wait --namespace kube-system \
    --for=condition=ready pod \
    --selector k8s-app=kube-dns \
    --timeout=180s

  echo "Verifying external DNS resolution through the cluster DNS service..."
  local attempt
  for attempt in $(seq 1 30); do
    if minikube ssh -- nslookup registry-1.docker.io 10.96.0.10 >/dev/null 2>&1; then
      echo "Cluster DNS can resolve registry-1.docker.io"
      return 0
    fi
    sleep 2
  done

  echo "Cluster DNS did not resolve registry-1.docker.io within the timeout" >&2
  return 1
}

print_step() {
  echo
  echo "==> $1"
}

wait_for_ma_runtime() {
  print_step "Waiting for core Migration Assistant workloads"
  kubectl --context "${KUBE_CONTEXT}" -n ma rollout status statefulset/migration-console --timeout=10m
  kubectl --context "${KUBE_CONTEXT}" -n ma wait --for=condition=ready pod -l app.kubernetes.io/name=argo-workflows-server --timeout=10m
  kubectl --context "${KUBE_CONTEXT}" -n ma wait --for=condition=ready pod -l app.kubernetes.io/name=strimzi-cluster-operator --timeout=10m
}

wait_for_test_clusters() {
  print_step "Waiting for local source and target test clusters"
  kubectl --context "${KUBE_CONTEXT}" -n ma rollout status statefulset/elasticsearch-master --timeout=10m
  kubectl --context "${KUBE_CONTEXT}" -n ma rollout status statefulset/opensearch-cluster-master --timeout=10m
}

print_next_steps() {
  print_step "Local environment is ready"
  echo "Migration console:"
  echo "  kubectl -n ma exec --stdin --tty migration-console-0 -- /bin/bash"
  echo
  echo "Current pods:"
  kubectl --context "${KUBE_CONTEXT}" -n ma get pods
  echo
  echo "Helm releases:"
  helm --kube-context "${KUBE_CONTEXT}" -n ma list
}

## One time things - will require a restart to minikube if it was already running
MINIKUBE_CPU_COUNT="${MINIKUBE_CPU_COUNT:-8}"
MINIKUBE_MEMORY_SIZE="${MINIKUBE_MEMORY_SIZE:-18000}"
print_step "Configuring minikube resources"
echo "Setting minikube config to use ${MINIKUBE_CPU_COUNT} CPUs and ${MINIKUBE_MEMORY_SIZE} MB memory."
minikube config set cpus $MINIKUBE_CPU_COUNT
minikube config set memory $MINIKUBE_MEMORY_SIZE

INSECURE_REGISTRY_CIDR="${INSECURE_REGISTRY_CIDR:-0.0.0.0/0}"
if minikube status --format='{{.Host}}' 2>/dev/null | grep -q Running; then
  echo "minikube is already running, skipping start"
else
  print_step "Starting minikube"
  minikube start \
    --extra-config=kubelet.authentication-token-webhook=true \
    --extra-config=kubelet.authorization-mode=Webhook \
    --extra-config=scheduler.bind-address=0.0.0.0 \
    --extra-config=controller-manager.bind-address=0.0.0.0 \
    --insecure-registry="${INSECURE_REGISTRY_CIDR}"
fi

wait_for_cluster_dns

cd "${MIGRATIONS_REPO_ROOT_DIR}"
gradlew() {
    "${MIGRATIONS_REPO_ROOT_DIR}/gradlew" "$@"
}

export KUBE_CONTEXT="${KUBE_CONTEXT:-minikube}"

export USE_LOCAL_REGISTRY="${USE_LOCAL_REGISTRY:-true}"
export BUILDKIT_HELM_ARGS="--set buildkitd.resources.requests.cpu=0 --set buildkitd.resources.requests.memory=0 --set buildkitd.resources.limits.cpu=0 --set buildkitd.resources.limits.memory=0"
print_step "Preparing BuildKit and local registry services"
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

print_step "Building container images for ${PLATFORM}"
gradlew :buildImages:buildImagesToRegistry_$PLATFORM -Pbuilder="$BUILDER_NAME"

kubectl config set-context "${KUBE_CONTEXT}" --namespace=ma

# Nice to have additions to minikube
print_step "Enabling metrics-server addon"
minikube addons enable metrics-server

cd "${MIGRATIONS_REPO_ROOT_DIR}"/deployment/k8s/

# Helm installs
print_step "Updating Helm dependencies"
helm dependency update charts/aggregates/testClusters
helm dependency update charts/aggregates/migrationAssistantWithArgo

if [ "${USE_LOCAL_REGISTRY:-false}" = "true" ]; then
  echo "Using LOCAL_REGISTRY for images: ${LOCAL_REGISTRY}"
  print_step "Installing Migration Assistant chart"
  helm --kube-context "${KUBE_CONTEXT}" upgrade --install --create-namespace -n ma ma charts/aggregates/migrationAssistantWithArgo \
    --wait --timeout 10m \
    -f charts/aggregates/migrationAssistantWithArgo/valuesForLocalK8s.yaml \
    --set "images.captureProxy.repository=${LOCAL_REGISTRY}/migrations/capture_proxy" \
    --set "images.captureProxy.tag=latest" \
    --set "images.captureProxy.pullPolicy=Always" \
    --set "images.installer.repository=${LOCAL_REGISTRY}/migrations/migration_console" \
    --set "images.installer.tag=latest" \
    --set "images.installer.pullPolicy=Always" \
    --set "images.migrationConsole.repository=${LOCAL_REGISTRY}/migrations/migration_console" \
    --set "images.migrationConsole.tag=latest" \
    --set "images.migrationConsole.pullPolicy=Always" \
    --set "images.trafficReplayer.repository=${LOCAL_REGISTRY}/migrations/traffic_replayer" \
    --set "images.trafficReplayer.tag=latest" \
    --set "images.trafficReplayer.pullPolicy=Always" \
    --set "images.reindexFromSnapshot.repository=${LOCAL_REGISTRY}/migrations/reindex_from_snapshot" \
    --set "images.reindexFromSnapshot.tag=latest" \
    --set "images.reindexFromSnapshot.pullPolicy=Always"

  wait_for_ma_runtime

  print_step "Installing local source and target test clusters"
  helm --kube-context "${KUBE_CONTEXT}" upgrade --install --create-namespace -n ma tc charts/aggregates/testClusters \
      --wait --timeout 10m \
      --set "source.image=${LOCAL_REGISTRY}/migrations/elasticsearch_searchguard"
else
  echo "Using non-local registry (USE_LOCAL_REGISTRY=false). Adjust repositories as needed."
  print_step "Installing Migration Assistant chart"
  helm --kube-context "${KUBE_CONTEXT}" upgrade --install --create-namespace -n ma ma charts/aggregates/migrationAssistantWithArgo \
    --wait --timeout 10m \
    -f charts/aggregates/migrationAssistantWithArgo/valuesForLocalK8s.yaml

  wait_for_ma_runtime

  print_step "Installing local source and target test clusters"
  helm --kube-context "${KUBE_CONTEXT}" upgrade --install --create-namespace -n ma tc charts/aggregates/testClusters \
    --wait --timeout 10m
fi

wait_for_test_clusters
print_next_steps


# Other useful stuff...

# minikube dashboard &

# Port forwards
# ./devScripts/forwardAllServicePorts.sh

# Grafana password...
# kubectl --namespace ma get secrets kube-prometheus-stack-grafana -o jsonpath="{.data.admin-password}" | base64 -d ; echo

# Open a migration console terminal on the stateful set that was deployed
# kubectl -n ma exec --stdin --tty migration-console-0 -- /bin/bash
