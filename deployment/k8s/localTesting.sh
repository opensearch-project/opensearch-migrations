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

export USE_LOCAL_REGISTRY="${USE_LOCAL_REGISTRY:-true}"
"${MIGRATIONS_REPO_ROOT_DIR}"/buildImages/setUpK8sImageBuildServices.sh

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

gradlew :buildImages:buildImagesToRegistry_$PLATFORM

kubectl config set-context --current --namespace=ma

# Nice to have additions to minikube
minikube addons enable metrics-server

# Install Mountpoint S3 CSI Driver v2 (controller + node)
# Create dummy AWS credentials secret for localstack (CSI driver references this with optional: true)
kubectl create secret generic aws-secret \
  --namespace kube-system \
  --from-literal=key_id=test \
  --from-literal=access_key=test \
  --from-literal=session_token=test \
  --dry-run=client -o yaml | kubectl apply -f -
helm repo add aws-mountpoint-s3-csi-driver https://awslabs.github.io/mountpoint-s3-csi-driver
helm repo update aws-mountpoint-s3-csi-driver
helm upgrade --install aws-mountpoint-s3-csi-driver aws-mountpoint-s3-csi-driver/aws-mountpoint-s3-csi-driver \
  --namespace kube-system \
  --set node.tolerateAllTaints=true \
  --wait --timeout 5m
echo "✅  S3 CSI Driver v2 installed"

cd "${MIGRATIONS_REPO_ROOT_DIR}"/deployment/k8s/

# Helm installs
helm dependency update charts/aggregates/testClusters
helm dependency update charts/aggregates/migrationAssistantWithArgo

if [ "${USE_LOCAL_REGISTRY:-false}" = "true" ]; then
  echo "Using LOCAL_REGISTRY for images: ${LOCAL_REGISTRY}"
  helm install --create-namespace -n ma tc charts/aggregates/testClusters \
      --wait --timeout 10m \
      --set "source.image=${LOCAL_REGISTRY}/migrations/elasticsearch_searchguard"

  helm install --create-namespace -n ma ma charts/aggregates/migrationAssistantWithArgo \
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
    --set "images.reindexFromSnapshot.pullPolicy=Always" \
    --set "images.snapshotFuse.repository=${LOCAL_REGISTRY}/migrations/snapshot_fuse" \
    --set "images.snapshotFuse.tag=latest" \
    --set "images.snapshotFuse.pullPolicy=Always"
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
