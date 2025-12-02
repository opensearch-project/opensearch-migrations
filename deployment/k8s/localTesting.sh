#!/usr/bin/env bash
set -euo pipefail

# ./minikubeLocal.sh --start

## One time things - will require a restart to minikube
minikube config set cpus 8
minikube config set memory 18000

INSECURE_REGISTRY_CIDR="${INSECURE_REGISTRY_CIDR:-0.0.0.0/0}"

minikube start \
  --extra-config=kubelet.authentication-token-webhook=true \
  --extra-config=kubelet.authorization-mode=Webhook \
  --extra-config=scheduler.bind-address=0.0.0.0 \
  --extra-config=controller-manager.bind-address=0.0.0.0 \
  --insecure-registry="${INSECURE_REGISTRY_CIDR}"

# Point docker CLI at minikube's Docker daemon (if you want to build images inside minikube)
eval "$(minikube docker-env)"

kubectl config set-context --current --namespace=ma

# nice to haves
minikube addons enable metrics-server
minikube dashboard &

###############################################################################
# Local registry wiring
###############################################################################

# Dynamically resolve the minikube IP and build a registry host:port string
LOCAL_REGISTRY_PORT="${LOCAL_REGISTRY_PORT:-30500}"
MINIKUBE_IP="$(minikube ip)"
LOCAL_REGISTRY="${MINIKUBE_IP}:${LOCAL_REGISTRY_PORT}"
export LOCAL_REGISTRY
echo "Using local registry at: ${LOCAL_REGISTRY}"

# Optional toggle (you already had this) – default is false
USE_LOCAL_REGISTRY="${USE_LOCAL_REGISTRY:-false}"

###############################################################################
# Helm installs
###############################################################################

helm dependency build charts/aggregates/testClusters
helm install --create-namespace -n ma tc charts/aggregates/testClusters

helm dependency build charts/aggregates/migrationAssistantWithArgo

if [ "${USE_LOCAL_REGISTRY}" = "true" ]; then
  echo "Using LOCAL_REGISTRY for images: ${LOCAL_REGISTRY}"

  helm install --create-namespace -n ma ma charts/aggregates/migrationAssistantWithArgo \
    -f charts/aggregates/migrationAssistantWithArgo/valuesDev.yaml \
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
else
  echo "Using non-local registry (USE_LOCAL_REGISTRY=false). Adjust repositories as needed."

  # Fallback – keep your original repos or whatever your "remote" is:
  helm install --create-namespace -n ma ma charts/aggregates/migrationAssistantWithArgo \
    -f charts/aggregates/migrationAssistantWithArgo/valuesDev.yaml
fi

###############################################################################
# Port forwards
###############################################################################

# Notice that this doesn't include the capture proxy yet
kubectl port-forward services/elasticsearch-master 19200:9200 &
kubectl port-forward services/opensearch-cluster-master 29200:9200 &

kubectl port-forward svc/argo-server 2746:2746 &
kubectl port-forward svc/etcd 2379:2379 &
kubectl port-forward svc/localstack 4566:4566 &
kubectl port-forward svc/kube-prometheus-stack-prometheus 9090:9090 &
kubectl port-forward svc/jaeger-query 16686:16686 &
kubectl port-forward svc/kube-prometheus-stack-grafana 9000:80 &

# Grafana password...
# kubectl --namespace ma get secrets kube-prometheus-stack-grafana -o jsonpath="{.data.admin-password}" | base64 -d ; echo

kubectl -n ma exec --stdin --tty migration-console-0 -- /bin/bash

## To test a new migration console container with kubectl wired up to your minikube instance
kubectl config view --minify --flatten | sed "s/127.0.0.1:.*/$(minikube ip):8443/g" > /tmp/kubeconfig-docker
docker run -v /tmp/kubeconfig-docker:/root/.kube/config:ro --network container:minikube \
  -e KUBECONFIG=/root/.kube/config \
  -it \
  migrations/migration_console:latest \
  /bin/bash
