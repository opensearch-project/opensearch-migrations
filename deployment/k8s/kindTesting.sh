#!/usr/bin/env bash

set -xeuo pipefail

MIGRATIONS_REPO_ROOT_DIR=$(git rev-parse --show-toplevel)
source "${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/localTestingCommon.sh"
source "${MIGRATIONS_REPO_ROOT_DIR}/buildImages/backends/dockerHostedBuildkit.sh"

KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-ma}"
KIND_CONTEXT="${KIND_CONTEXT:-kind-${KIND_CLUSTER_NAME}}"
KIND_CONFIG_FILE="${KIND_CONFIG_FILE:-${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/kindClusterConfig.yaml}"
KIND_REGISTRY_NAME="${KIND_REGISTRY_NAME:-docker-registry}"
KIND_REGISTRY_PORT="${KIND_REGISTRY_PORT:-5002}"
LOCAL_REGISTRY="${LOCAL_REGISTRY:-localhost:${KIND_REGISTRY_PORT}}"
BUILD_REGISTRY_ENDPOINT="${BUILD_REGISTRY_ENDPOINT:-${LOCAL_REGISTRY}}"
EXTERNAL_REGISTRY_NAME="${EXTERNAL_REGISTRY_NAME:-${KIND_REGISTRY_NAME}}"
EXTERNAL_REGISTRY_PORT="${KIND_REGISTRY_PORT}"
export KUBE_CONTEXT="${KUBE_CONTEXT:-${KIND_CONTEXT}}"

require_command kind
require_command docker
require_command kubectl
require_command helm

DOCKER_CONTEXT_NAME="$(docker context show 2>/dev/null || true)"
echo "Using docker context: ${DOCKER_CONTEXT_NAME:-default}"
echo "kind will use the docker-compatible runtime exposed by that context."

if [[ "${USE_LOCAL_REGISTRY:-true}" == "true" && "${LOCAL_REGISTRY}" != "localhost:${KIND_REGISTRY_PORT}" ]]; then
  echo "This script currently expects LOCAL_REGISTRY=localhost:${KIND_REGISTRY_PORT} so kind's containerd mirror matches ${KIND_CONFIG_FILE}." >&2
  exit 1
fi

if kind get clusters | grep -qx "${KIND_CLUSTER_NAME}"; then
  echo "kind cluster ${KIND_CLUSTER_NAME} already exists, skipping create"
else
  kind create cluster --name "${KIND_CLUSTER_NAME}" --config "${KIND_CONFIG_FILE}"
fi

if [[ "${USE_LOCAL_REGISTRY:-true}" == "true" ]]; then
  setup_build_backend

  if ! docker network inspect kind >/dev/null 2>&1; then
    echo "Expected kind docker network to exist after cluster creation" >&2
    exit 1
  fi

  if [[ "$(docker inspect -f '{{json .NetworkSettings.Networks.kind}}' "${KIND_REGISTRY_NAME}")" == "null" ]]; then
    docker network connect kind "${KIND_REGISTRY_NAME}"
  else
    echo "Local registry container already connected to kind network"
  fi

  if ! docker exec "${KIND_CLUSTER_NAME}-control-plane" grep -q 'config_path = "/etc/containerd/certs.d"' /etc/containerd/config.toml; then
    echo "The existing kind cluster is missing the containerd registry config_path setting." >&2
    echo "Delete and recreate it with ${KIND_CONFIG_FILE}: kind delete cluster --name ${KIND_CLUSTER_NAME}" >&2
    exit 1
  fi

  REGISTRY_DIR="/etc/containerd/certs.d/localhost:${KIND_REGISTRY_PORT}"
  for node in $(kind get nodes --name "${KIND_CLUSTER_NAME}"); do
    docker exec "${node}" mkdir -p "${REGISTRY_DIR}"
    cat <<EOF | docker exec -i "${node}" cp /dev/stdin "${REGISTRY_DIR}/hosts.toml"
[host."http://${KIND_REGISTRY_NAME}:5000"]
EOF
  done

  cat <<EOF | kubectl --context "${KIND_CONTEXT}" apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: local-registry-hosting
  namespace: kube-public
data:
  localRegistryHosting.v1: |
    host: "localhost:${KIND_REGISTRY_PORT}"
    help: "https://kind.sigs.k8s.io/docs/user/local-registry/"
EOF
fi

POST_MA_INSTALL_HOOK="${POST_MA_INSTALL_HOOK:-wait_for_ma_runtime}"
POST_TC_INSTALL_HOOK="${POST_TC_INSTALL_HOOK:-wait_for_test_clusters}"
run_local_test_deploy

# Open a migration console terminal on the stateful set that was deployed
# kubectl -n ma exec --stdin --tty migration-console-0 -- /bin/bash
