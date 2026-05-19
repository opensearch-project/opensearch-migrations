#!/usr/bin/env bash

set -xeuo pipefail

MIGRATIONS_REPO_ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && git rev-parse --show-toplevel)
source "${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/localTestingCommon.sh"
source "${MIGRATIONS_REPO_ROOT_DIR}/buildImages/backends/dockerHostedBuildkit.sh"

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
MINIKUBE_PROFILE="${MINIKUBE_PROFILE:-minikube}"
MINIKUBE_CPU_COUNT="${MINIKUBE_CPU_COUNT:-8}"
MINIKUBE_MEMORY_SIZE="${MINIKUBE_MEMORY_SIZE:-18000}"
print_step "Configuring minikube resources"
echo "Setting minikube config to use ${MINIKUBE_CPU_COUNT} CPUs and ${MINIKUBE_MEMORY_SIZE} MB memory."
minikube -p "${MINIKUBE_PROFILE}" config set cpus $MINIKUBE_CPU_COUNT
minikube -p "${MINIKUBE_PROFILE}" config set memory $MINIKUBE_MEMORY_SIZE

# containerd runtime is required so the per-node /etc/containerd/certs.d
# hosts.toml that connect_cluster_to_registry_network writes is honored.
# cri-dockerd (the minikube < 1.39 default) routes pulls through dockerd,
# which would need a separate insecure-registry config and never mirrors
# localhost:5001 to docker-registry:5000. If a pre-existing cluster is on
# cri-dockerd, recreate it.
needs_recreate=false
if minikube -p "${MINIKUBE_PROFILE}" status --format='{{.Host}}' 2>/dev/null | grep -q Running; then
  current_runtime="$(docker exec "${MINIKUBE_PROFILE}" cat /etc/crictl.yaml 2>/dev/null | awk -F': ' '/runtime-endpoint/ {print $2}' || true)"
  if [[ "${current_runtime}" == *containerd* ]]; then
    echo "minikube is already running on containerd, skipping start"
  else
    echo "minikube is running but on '${current_runtime:-unknown}'; recreating on containerd"
    needs_recreate=true
  fi
else
  needs_recreate=true
fi

if [[ "${needs_recreate}" == true ]]; then
  print_step "Starting minikube"
  minikube -p "${MINIKUBE_PROFILE}" delete >/dev/null 2>&1 || true
  minikube -p "${MINIKUBE_PROFILE}" start \
    --driver=docker \
    --container-runtime=containerd \
    --extra-config=kubelet.authentication-token-webhook=true \
    --extra-config=kubelet.authorization-mode=Webhook \
    --extra-config=scheduler.bind-address=0.0.0.0 \
    --extra-config=controller-manager.bind-address=0.0.0.0
fi

# The shared docker-hosted backend joins the registry to the cluster's docker
# network and writes a containerd hosts.toml on each node, so the docker-driver
# is required (other minikube drivers — kvm, hyperkit, virtualbox — don't put
# the node container on a docker network we can attach the registry to).
ACTUAL_DRIVER="$(minikube -p "${MINIKUBE_PROFILE}" profile list -o json 2>/dev/null \
  | python3 -c "import json,sys; data=json.load(sys.stdin); valid=data.get('valid',[]); \
print(next((p['Config']['Driver'] for p in valid if p.get('Name')=='${MINIKUBE_PROFILE}'), ''))" 2>/dev/null || true)"
if [[ -n "${ACTUAL_DRIVER}" && "${ACTUAL_DRIVER}" != "docker" ]]; then
  echo "ERROR: localTesting.sh requires the minikube docker driver, profile '${MINIKUBE_PROFILE}' uses '${ACTUAL_DRIVER}'." >&2
  exit 1
fi

export KUBE_CONTEXT="${KUBE_CONTEXT:-${MINIKUBE_PROFILE}}"
wait_for_cluster_dns

set_docker_hosted_defaults
LOCAL_REGISTRY="localhost:${EXTERNAL_REGISTRY_PORT}"
BUILD_REGISTRY_ENDPOINT="${BUILD_REGISTRY_ENDPOINT:-${LOCAL_REGISTRY}}"
export USE_LOCAL_REGISTRY="${USE_LOCAL_REGISTRY:-true}"
POST_MA_INSTALL_HOOK="${POST_MA_INSTALL_HOOK:-wait_for_ma_runtime}"
POST_TC_INSTALL_HOOK="${POST_TC_INSTALL_HOOK:-wait_for_test_clusters}"

# Bring the registry/buildkit containers up first, then attach the minikube
# node containers to the registry's docker network so containerd can pull
# localhost:${EXTERNAL_REGISTRY_PORT} via the in-network docker-registry alias.
setup_build_backend
mk_nodes=()
while IFS= read -r node; do
  [[ -n "${node}" ]] && mk_nodes+=("${node}")
done < <(minikube -p "${MINIKUBE_PROFILE}" node list 2>/dev/null | awk '{print $1}')
connect_cluster_to_registry_network "${MINIKUBE_PROFILE}" "${mk_nodes[@]}"

run_local_test_deploy

# Nice to have additions to minikube
print_step "Enabling metrics-server addon"
minikube -p "${MINIKUBE_PROFILE}" addons enable metrics-server

# Other useful stuff...

# minikube dashboard &

# Port forwards
# ./devScripts/forwardAllServicePorts.sh

# Grafana password...
# kubectl --namespace ma get secrets kube-prometheus-stack-grafana -o jsonpath="{.data.admin-password}" | base64 -d ; echo

# Open a migration console terminal on the stateful set that was deployed
# kubectl -n ma exec --stdin --tty migration-console-0 -- /bin/bash
