#!/usr/bin/env bash

set -xeuo pipefail

MIGRATIONS_REPO_ROOT_DIR=$(git rev-parse --show-toplevel)
source "${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/localTestingCommon.sh"
source "${MIGRATIONS_REPO_ROOT_DIR}/buildImages/backends/dockerHostedBuildkit.sh"

KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-ma}"
KIND_CONTEXT="${KIND_CONTEXT:-kind-${KIND_CLUSTER_NAME}}"
KIND_CONFIG_FILE="${KIND_CONFIG_FILE:-${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/kindClusterConfig.yaml}"
# Logs from every node land on this machine, one directory per node (see valuesForLocalK8s.yaml).
# They outlive the cluster; kindCleanup.sh --delete-logs removes them.
KIND_LOGS_DIR="${KIND_LOGS_DIR:-${HOME}/.opensearch-migrations/kind/${KIND_CLUSTER_NAME}/logs}"
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
  # kind's config can't reference variables, so render a copy that mounts this machine's log
  # directories into each node: the node's own directory at /var/log/migrations (Fluent Bit writes
  # there) and the parent at /var/log/migrations-all (the console reads every node's logs there).
  # Node names follow kind's: <cluster>-control-plane, <cluster>-worker, <cluster>-worker2, ...
  rendered_kind_config="$(mktemp "${TMPDIR:-/tmp}/kind-config.XXXXXX")"
  awk -v cluster="${KIND_CLUSTER_NAME}" -v logs="${KIND_LOGS_DIR}" '
    { print }
    /^[[:space:]]*- role:[[:space:]]*(control-plane|worker)[[:space:]]*$/ {
      role = $NF; count[role]++
      node = cluster "-" role (count[role] > 1 ? count[role] : "")
      indent = $0; sub(/-.*/, "", indent)
      printf "%s  extraMounts:\n", indent
      printf "%s    - hostPath: %s/%s\n%s      containerPath: /var/log/migrations\n", indent, logs, node, indent
      printf "%s    - hostPath: %s\n%s      containerPath: /var/log/migrations-all\n", indent, logs, indent
      print node > "/dev/stderr"
    }' "${KIND_CONFIG_FILE}" > "${rendered_kind_config}" 2> "${rendered_kind_config}.nodes"
  while IFS= read -r node; do mkdir -p "${KIND_LOGS_DIR}/${node}"; done < "${rendered_kind_config}.nodes"
  kind create cluster --name "${KIND_CLUSTER_NAME}" --config "${rendered_kind_config}"
  rm -f "${rendered_kind_config}" "${rendered_kind_config}.nodes"
fi

if ! docker inspect "${KIND_CLUSTER_NAME}-control-plane" --format '{{range .Mounts}}{{.Destination}} {{end}}' \
    | grep -qw /var/log/migrations-all; then
  echo "The existing kind cluster has no host log directories mounted (/var/log/migrations-all)." >&2
  echo "Delete and recreate it so this script can add them: kind delete cluster --name ${KIND_CLUSTER_NAME}" >&2
  exit 1
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
