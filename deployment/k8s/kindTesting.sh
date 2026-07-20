#!/usr/bin/env bash

set -xeuo pipefail

MIGRATIONS_REPO_ROOT_DIR=$(git rev-parse --show-toplevel)
source "${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/localTestingCommon.sh"
source "${MIGRATIONS_REPO_ROOT_DIR}/buildImages/backends/dockerHostedBuildkit.sh"

# These fall back to any existing environment variable, and finally to
# elasticsearch/true, matching the defaults baked into localTestingCommon.sh.
TEST_CLUSTERS_SOURCE="${TEST_CLUSTERS_SOURCE:-elasticsearch}"
INSTALL_TEST_CLUSTERS="${INSTALL_TEST_CLUSTERS:-true}"

usage() {
  cat <<EOF
Usage: $(basename "${BASH_SOURCE[0]}") [options]

Options:
  --source=<elasticsearch|solr>   Source cluster type for the local test clusters
                                   (default: elasticsearch)
  --no-test-clusters              Skip installing the local test clusters chart entirely
  -h, --help                      Show this help message
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source=*)
      TEST_CLUSTERS_SOURCE="${1#*=}"
      shift
      ;;
    --source)
      TEST_CLUSTERS_SOURCE="${2:-}"
      shift 2
      ;;
    --no-test-clusters)
      INSTALL_TEST_CLUSTERS="false"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

case "${TEST_CLUSTERS_SOURCE}" in
  elasticsearch|solr) ;;
  *)
    echo "Invalid --source value: '${TEST_CLUSTERS_SOURCE}' (expected 'elasticsearch' or 'solr')" >&2
    exit 1
    ;;
esac

export TEST_CLUSTERS_SOURCE
export INSTALL_TEST_CLUSTERS

KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-ma}"
KIND_CONTEXT="${KIND_CONTEXT:-kind-${KIND_CLUSTER_NAME}}"
KIND_CONFIG_FILE="${KIND_CONFIG_FILE:-${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/kindClusterConfig.yaml}"
set_docker_hosted_defaults
# In-cluster pulls use the docker-network DNS name; the host's `docker buildx`
# push goes to the bind-mounted localhost port. Same registry, two URLs.
LOCAL_REGISTRY="${LOCAL_REGISTRY:-${EXTERNAL_REGISTRY_NAME}:${EXTERNAL_REGISTRY_PORT}}"
BUILD_REGISTRY_ENDPOINT="${BUILD_REGISTRY_ENDPOINT:-localhost:${EXTERNAL_REGISTRY_PORT}}"
export KUBE_CONTEXT="${KUBE_CONTEXT:-${KIND_CONTEXT}}"

require_command kind
require_command docker
require_command kubectl
require_command helm

DOCKER_CONTEXT_NAME="$(docker context show 2>/dev/null || true)"
echo "Using docker context: ${DOCKER_CONTEXT_NAME:-default}"
echo "kind will use the docker-compatible runtime exposed by that context."

if kind get clusters | grep -qx "${KIND_CLUSTER_NAME}"; then
  echo "kind cluster ${KIND_CLUSTER_NAME} already exists, skipping create"
else
  kind create cluster --name "${KIND_CLUSTER_NAME}" --config "${KIND_CONFIG_FILE}"
fi

if [[ "${USE_LOCAL_REGISTRY:-true}" == "true" ]]; then
  setup_build_backend

  if ! docker exec "${KIND_CLUSTER_NAME}-control-plane" grep -q 'config_path = "/etc/containerd/certs.d"' /etc/containerd/config.toml; then
    echo "The existing kind cluster is missing the containerd registry config_path setting." >&2
    echo "Delete and recreate it with ${KIND_CONFIG_FILE}: kind delete cluster --name ${KIND_CLUSTER_NAME}" >&2
    exit 1
  fi

  kind_nodes=()
  while IFS= read -r node; do
    [[ -n "${node}" ]] && kind_nodes+=("${node}")
  done < <(kind get nodes --name "${KIND_CLUSTER_NAME}")
  connect_cluster_to_registry_network kind "${kind_nodes[@]}"

  cat <<EOF | kubectl --context "${KIND_CONTEXT}" apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: local-registry-hosting
  namespace: kube-public
data:
  localRegistryHosting.v1: |
    host: "${EXTERNAL_REGISTRY_NAME}:${EXTERNAL_REGISTRY_PORT}"
    help: "https://kind.sigs.k8s.io/docs/user/local-registry/"
EOF
fi

POST_MA_INSTALL_HOOK="${POST_MA_INSTALL_HOOK:-wait_for_ma_runtime}"
POST_TC_INSTALL_HOOK="${POST_TC_INSTALL_HOOK:-wait_for_test_clusters}"
run_local_test_deploy

# Open a migration console terminal on the stateful set that was deployed
# kubectl -n ma exec --stdin --tty migration-console-0 -- /bin/bash
