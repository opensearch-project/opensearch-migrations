#!/usr/bin/env bash

set -euo pipefail

MIGRATIONS_REPO_ROOT_DIR=$(git rev-parse --show-toplevel)
source "${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/localTestingCommon.sh"

KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-ma}"
KIND_CONTEXT="${KIND_CONTEXT:-kind-${KIND_CLUSTER_NAME}}"
KIND_REGISTRY_NAME="${KIND_REGISTRY_NAME:-docker-registry}"
EXTERNAL_REGISTRY_VOLUME="${EXTERNAL_REGISTRY_VOLUME:-registry-data}"
EXTERNAL_BUILDKIT_NAME="${EXTERNAL_BUILDKIT_NAME:-buildkitd}"
EXTERNAL_BUILDKIT_VOLUME="${EXTERNAL_BUILDKIT_VOLUME:-buildkitd-state}"
BUILDER_NAME="builder-${KIND_CONTEXT//[^a-zA-Z0-9_-]/-}"

require_command kind
require_command docker

if kind get clusters | grep -qx "${KIND_CLUSTER_NAME}"; then
  kubectl delete namespace buildkit --ignore-not-found=true --context "${KIND_CONTEXT}" || true
  kind delete cluster --name "${KIND_CLUSTER_NAME}"
else
  echo "kind cluster ${KIND_CLUSTER_NAME} does not exist, skipping cluster delete"
fi

docker buildx rm "${BUILDER_NAME}" 2>/dev/null || true
docker rm -f "${KIND_REGISTRY_NAME}" 2>/dev/null || true
docker rm -f kind-registry 2>/dev/null || true
docker rm -f "${EXTERNAL_BUILDKIT_NAME}" 2>/dev/null || true

pkill -f 'kubectl.*buildkitd.*1234:1234' || true
pkill -f 'kubectl.*docker-registry.*5001:5000' || true

if [[ "${KIND_PRUNE_VOLUMES:-false}" == "true" ]]; then
  docker volume rm "${EXTERNAL_REGISTRY_VOLUME}" 2>/dev/null || true
  docker volume rm "${EXTERNAL_BUILDKIT_VOLUME}" 2>/dev/null || true
fi
