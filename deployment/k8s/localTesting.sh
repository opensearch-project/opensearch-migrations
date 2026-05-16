#!/usr/bin/env bash

set -xeuo pipefail

MIGRATIONS_REPO_ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && git rev-parse --show-toplevel)
source "${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/localTestingCommon.sh"
source "${MIGRATIONS_REPO_ROOT_DIR}/buildImages/backends/k8sHostedBuildkit.sh"

wait_for_cluster_dns() {
  local kube_context
  local attempt
  kube_context="${KUBE_CONTEXT:-minikube}"
  print_step "Waiting for cluster DNS"
  echo "Waiting for CoreDNS pod to be created..."
  for attempt in $(seq 1 60); do
    if kubectl --context "${kube_context}" get pod -n kube-system -l k8s-app=kube-dns -o name | grep -q .; then
      break
    fi
    if [[ "${attempt}" -eq 60 ]]; then
      echo "CoreDNS pod was not created within the timeout" >&2
      return 1
    fi
    sleep 2
  done

  echo "Waiting for CoreDNS to become ready..."
  kubectl --context "${kube_context}" wait --namespace kube-system \
    --for=condition=ready pod \
    --selector k8s-app=kube-dns \
    --timeout=180s
  echo "CoreDNS is ready."
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

export KUBE_CONTEXT="${KUBE_CONTEXT:-minikube}"
wait_for_cluster_dns
LOCAL_REGISTRY_PORT="${LOCAL_REGISTRY_PORT:-30500}"
MINIKUBE_IP="$(minikube ip)"
LOCAL_REGISTRY="${MINIKUBE_IP}:${LOCAL_REGISTRY_PORT}"
BUILD_REGISTRY_ENDPOINT="${BUILD_REGISTRY_ENDPOINT:-localhost:5001}"
export USE_LOCAL_REGISTRY="${USE_LOCAL_REGISTRY:-true}"
export BUILD_CONTAINER_REGISTRY_ENDPOINT="${BUILD_CONTAINER_REGISTRY_ENDPOINT:-docker-registry:5000}"
export BUILDKIT_HELM_ARGS="${BUILDKIT_HELM_ARGS:-"--set buildkitd.resources.requests.cpu=0 --set buildkitd.resources.requests.memory=0 --set buildkitd.resources.limits.cpu=0 --set buildkitd.resources.limits.memory=0"}"
POST_MA_INSTALL_HOOK="${POST_MA_INSTALL_HOOK:-wait_for_ma_runtime}"
POST_TC_INSTALL_HOOK="${POST_TC_INSTALL_HOOK:-wait_for_test_clusters}"

run_local_test_deploy

# Nice to have additions to minikube
print_step "Enabling metrics-server addon"
minikube addons enable metrics-server

# Other useful stuff...

# minikube dashboard &

# Port forwards
# ./devScripts/forwardAllServicePorts.sh

# Grafana password...
# kubectl --namespace ma get secrets kube-prometheus-stack-grafana -o jsonpath="{.data.admin-password}" | base64 -d ; echo

# Open a migration console terminal on the stateful set that was deployed
# kubectl -n ma exec --stdin --tty migration-console-0 -- /bin/bash
