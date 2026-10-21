#!/usr/bin/env bash

set -euo pipefail

# Usage: kindCleanup.sh [--delete-logs]
#   Deletes the kind cluster and the local build services. The cluster's logs on this machine
#   (KIND_LOGS_DIR, see kindTesting.sh) are kept unless --delete-logs is given.
DELETE_LOGS=false
for arg in "$@"; do
  case "$arg" in
    --delete-logs) DELETE_LOGS=true ;;
    -h|--help) sed -n '5,7p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

MIGRATIONS_REPO_ROOT_DIR=$(git rev-parse --show-toplevel)
source "${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/localTestingCommon.sh"

KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-ma}"
KIND_CONTEXT="${KIND_CONTEXT:-kind-${KIND_CLUSTER_NAME}}"
EXTERNAL_REGISTRY_NAME="${EXTERNAL_REGISTRY_NAME:-docker-registry}"
EXTERNAL_REGISTRY_VOLUME="${EXTERNAL_REGISTRY_VOLUME:-registry-data}"
EXTERNAL_BUILDKIT_NAME="${EXTERNAL_BUILDKIT_NAME:-buildkitd}"
EXTERNAL_BUILDKIT_VOLUME="${EXTERNAL_BUILDKIT_VOLUME:-buildkitd-state}"
BUILDER_NAME="builder-${KIND_CONTEXT//[^a-zA-Z0-9_-]/-}"
KIND_LOGS_DIR="${KIND_LOGS_DIR:-${HOME}/.opensearch-migrations/kind/${KIND_CLUSTER_NAME}/logs}"

require_command kind
require_command docker

if kind get clusters | grep -qx "${KIND_CLUSTER_NAME}"; then
  kubectl delete namespace buildkit --ignore-not-found=true --context "${KIND_CONTEXT}" || true
  kind delete cluster --name "${KIND_CLUSTER_NAME}"
else
  echo "kind cluster ${KIND_CLUSTER_NAME} does not exist, skipping cluster delete"
fi

docker buildx rm "${BUILDER_NAME}" 2>/dev/null || true
docker rm -f "${EXTERNAL_REGISTRY_NAME}" 2>/dev/null || true
docker rm -f kind-registry 2>/dev/null || true
docker rm -f "${EXTERNAL_BUILDKIT_NAME}" 2>/dev/null || true

if [[ "${KIND_PRUNE_VOLUMES:-false}" == "true" ]]; then
  docker volume rm "${EXTERNAL_REGISTRY_VOLUME}" 2>/dev/null || true
  docker volume rm "${EXTERNAL_BUILDKIT_VOLUME}" 2>/dev/null || true
fi

if [[ "$DELETE_LOGS" == "true" ]]; then
  echo "Deleting logs in ${KIND_LOGS_DIR}"
  rm -rf -- "${KIND_LOGS_DIR:?}"
elif [[ -d "${KIND_LOGS_DIR}" ]]; then
  echo "Kept the cluster's logs in ${KIND_LOGS_DIR} (--delete-logs removes them)"
fi
