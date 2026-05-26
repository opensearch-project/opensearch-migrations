#!/usr/bin/env bash
#
# Build all Docker images into the local Docker daemon.
# Used by ECS/CDK paths that need images locally for DockerImageAsset.
#
# Usage: run from the repo root or any directory with git access.
#   ./deployment/cdk/opensearch-service-migration/buildDockerImages.sh
#

set -euo pipefail

run_with_retries() {
  local attempts="$1"
  shift
  local delay_seconds="${DOCKER_BUILD_RETRY_DELAY_SECONDS:-30}"
  local attempt=1

  while true; do
    echo "Running Docker image build attempt ${attempt}/${attempts}: $*"
    if "$@"; then
      return 0
    fi

    local status=$?
    if (( attempt >= attempts )); then
      echo "Docker image build failed after ${attempts} attempts." >&2
      return "$status"
    fi

    echo "Docker image build failed with exit code ${status}; retrying in ${delay_seconds}s..." >&2
    sleep "$delay_seconds"
    attempt=$((attempt + 1))
  done
}

# Resolve the repo root from this script's own location so the script works
# regardless of caller cwd (e.g. test/awsE2ESolutionSetup.sh cd's into
# test/opensearch-cluster-cdk/ before invoking this script, which would make
# `git rev-parse --show-toplevel` return the nested sub-repo).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRATIONS_REPO_ROOT_DIR="${MIGRATIONS_REPO_ROOT_DIR:-$(cd "$SCRIPT_DIR/../../.." && pwd)}"
export MIGRATIONS_REPO_ROOT_DIR
cd "$MIGRATIONS_REPO_ROOT_DIR"

export KUBE_CONTEXT="${KUBE_CONTEXT:-local-image-build}"
source buildImages/backends/dockerHostedBuildkit.sh
setup_build_backend

ARCH=$(uname -m)
case "$ARCH" in
  x86_64)  PLATFORM="amd64" ;;
  arm64|aarch64) PLATFORM="arm64" ;;
  *) echo "Unsupported architecture: $ARCH" >&2; exit 1 ;;
esac

BUILDER_NAME="builder-${KUBE_CONTEXT}"
DOCKER_BUILD_RETRIES="${DOCKER_BUILD_RETRIES:-1}"
case "$DOCKER_BUILD_RETRIES" in
  ''|*[!0-9]*) echo "DOCKER_BUILD_RETRIES must be a positive integer, got: ${DOCKER_BUILD_RETRIES}" >&2; exit 2 ;;
  0) echo "DOCKER_BUILD_RETRIES must be greater than zero" >&2; exit 2 ;;
esac

run_with_retries "$DOCKER_BUILD_RETRIES" ./gradlew ":buildImages:buildImagesToRegistry_${PLATFORM}" \
  -Pbuilder="$BUILDER_NAME" \
  -PregistryEndpoint="localhost:${EXTERNAL_REGISTRY_PORT}" \
  -PlocalContainerRegistryEndpoint="${EXTERNAL_REGISTRY_NAME}:5000" \
  -x test --no-daemon

for image in capture_proxy migration_console traffic_replayer elasticsearch_searchguard reindex_from_snapshot elasticsearch_test_console migration_console_base; do
  docker pull "localhost:${EXTERNAL_REGISTRY_PORT}/migrations/${image}:latest" || true
  docker tag "localhost:${EXTERNAL_REGISTRY_PORT}/migrations/${image}:latest" "migrations/${image}:latest" || true
done

# otel_collector is not in buildImages — only needed for ECS/CDK path
run_with_retries "$DOCKER_BUILD_RETRIES" docker build -t "migrations/otel_collector:latest" \
  "$MIGRATIONS_REPO_ROOT_DIR/TrafficCapture/dockerSolution/src/main/docker/otelCollector"
