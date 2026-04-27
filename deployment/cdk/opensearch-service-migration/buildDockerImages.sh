#!/usr/bin/env bash
#
# Build all Docker images into the local Docker daemon.
# Used by ECS/CDK paths that need images locally for DockerImageAsset.
#
# Usage: run from the repo root or any directory with git access.
#   ./deployment/cdk/opensearch-service-migration/buildDockerImages.sh
#

set -euo pipefail

MIGRATIONS_REPO_ROOT_DIR="${MIGRATIONS_REPO_ROOT_DIR:-$(git rev-parse --show-toplevel)}"
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
./gradlew ":buildImages:buildImagesToRegistry_${PLATFORM}" \
  -Pbuilder="$BUILDER_NAME" \
  -PregistryEndpoint="localhost:${EXTERNAL_REGISTRY_PORT}" \
  -PlocalContainerRegistryEndpoint="${EXTERNAL_REGISTRY_NAME}:5000" \
  -x test --no-daemon

for image in capture_proxy migration_console traffic_replayer elasticsearch_searchguard reindex_from_snapshot elasticsearch_test_console migration_console_base; do
  docker pull "localhost:${EXTERNAL_REGISTRY_PORT}/migrations/${image}:latest" || true
  docker tag "localhost:${EXTERNAL_REGISTRY_PORT}/migrations/${image}:latest" "migrations/${image}:latest" || true
done

# otel_collector is not in buildImages — only needed for ECS/CDK path
docker build -t "migrations/otel_collector:latest" \
  "$MIGRATIONS_REPO_ROOT_DIR/TrafficCapture/dockerSolution/src/main/docker/otelCollector"
