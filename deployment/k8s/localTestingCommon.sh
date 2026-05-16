#!/usr/bin/env bash

set -euo pipefail

MIGRATIONS_REPO_ROOT_DIR=$(git rev-parse --show-toplevel)

gradlew() {
  "${MIGRATIONS_REPO_ROOT_DIR}/gradlew" "$@"
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

print_step() {
  echo
  echo "==> $1"
}

wait_for_ma_runtime() {
  print_step "Waiting for core Migration Assistant workloads"
  kubectl --context "${KUBE_CONTEXT}" -n ma rollout status statefulset/migration-console --timeout=10m
  kubectl --context "${KUBE_CONTEXT}" -n ma wait --for=condition=ready pod -l app.kubernetes.io/name=argo-workflows-server --timeout=10m
  kubectl --context "${KUBE_CONTEXT}" -n ma rollout status deployment/strimzi-cluster-operator --timeout=10m
}

wait_for_test_clusters() {
  print_step "Waiting for local source and target test clusters"
  kubectl --context "${KUBE_CONTEXT}" -n ma rollout status statefulset/elasticsearch-master --timeout=10m
  kubectl --context "${KUBE_CONTEXT}" -n ma rollout status statefulset/opensearch-cluster-master --timeout=10m
}

print_next_steps() {
  print_step "Local environment is ready"
  echo "Migration console:"
  echo "  kubectl -n ma exec --stdin --tty migration-console-0 -- /bin/bash"
  echo
  echo "Current pods:"
  kubectl --context "${KUBE_CONTEXT}" -n ma get pods
  echo
  echo "Helm releases:"
  helm --kube-context "${KUBE_CONTEXT}" -n ma list
}

detect_platform() {
  local arch
  arch=$(uname -m)
  case "$arch" in
    x86_64)
      echo "amd64"
      ;;
    arm64|aarch64)
      echo "arm64"
      ;;
    *)
      echo "Unsupported architecture: $arch" >&2
      exit 1
      ;;
  esac
}

set_up_local_build_services() {
  print_step "Preparing BuildKit and local registry services"
  if declare -F setup_build_backend >/dev/null 2>&1; then
    setup_build_backend
  else
    echo "Missing setup_build_backend function" >&2
    exit 1
  fi
}

build_local_images() {
  local builder_name platform image_tag
  builder_name="builder-${KUBE_CONTEXT//[^a-zA-Z0-9_-]/-}"
  platform=$(detect_platform)
  image_tag="${LOCAL_IMAGE_TAG:-latest}"
  local gradle_args=()

  export LOCAL_REGISTRY
  echo "Using local registry for cluster image pulls: ${LOCAL_REGISTRY}"
  echo "Using local image tag: ${image_tag}"
  echo "Using build registry endpoint: ${BUILD_REGISTRY_ENDPOINT}"
  if [[ "${image_tag}" != "latest" ]]; then
    gradle_args+=("-PimageVersion=${image_tag}")
  fi
  if [[ -n "${BUILD_CONTAINER_REGISTRY_ENDPOINT:-}" ]]; then
    echo "Using container-visible registry endpoint for builds: ${BUILD_CONTAINER_REGISTRY_ENDPOINT}"
    gradle_args+=("-PlocalContainerRegistryEndpoint=${BUILD_CONTAINER_REGISTRY_ENDPOINT}")
  fi

  print_step "Building container images for ${platform}"
  gradlew ":buildImages:buildImagesToRegistry_${platform}" \
    -Pbuilder="${builder_name}" \
    -PregistryEndpoint="${BUILD_REGISTRY_ENDPOINT}" \
    "${gradle_args[@]}"
}

run_named_hook() {
  local hook_name
  hook_name="${1:-}"
  if [[ -n "${hook_name}" ]] && declare -F "${hook_name}" >/dev/null 2>&1; then
    "${hook_name}"
  fi
}

deploy_local_charts() {
  cd "${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/"
  local image_tag="${LOCAL_IMAGE_TAG:-latest}"

  print_step "Updating Helm dependencies"
  helm dependency update charts/aggregates/testClusters
  helm dependency update charts/aggregates/migrationAssistantWithArgo

  if [[ "${USE_LOCAL_REGISTRY:-false}" == "true" ]]; then
    echo "Using LOCAL_REGISTRY for images: ${LOCAL_REGISTRY}"
    echo "Using local image tag: ${image_tag}"
    print_step "Installing Migration Assistant chart"
    helm --kube-context "${KUBE_CONTEXT}" upgrade --install --create-namespace -n ma ma charts/aggregates/migrationAssistantWithArgo \
      --wait --timeout 10m \
      -f charts/aggregates/migrationAssistantWithArgo/valuesForLocalK8s.yaml \
      --set "images.captureProxy.repository=${LOCAL_REGISTRY}/migrations/capture_proxy" \
      --set "images.captureProxy.tag=${image_tag}" \
      --set "images.captureProxy.pullPolicy=Always" \
      --set "images.installer.repository=${LOCAL_REGISTRY}/migrations/migration_console" \
      --set "images.installer.tag=${image_tag}" \
      --set "images.installer.pullPolicy=Always" \
      --set "images.migrationConsole.repository=${LOCAL_REGISTRY}/migrations/migration_console" \
      --set "images.migrationConsole.tag=${image_tag}" \
      --set "images.migrationConsole.pullPolicy=Always" \
      --set "images.trafficReplayer.repository=${LOCAL_REGISTRY}/migrations/traffic_replayer" \
      --set "images.trafficReplayer.tag=${image_tag}" \
      --set "images.trafficReplayer.pullPolicy=Always" \
      --set "images.reindexFromSnapshot.repository=${LOCAL_REGISTRY}/migrations/reindex_from_snapshot" \
      --set "images.reindexFromSnapshot.tag=${image_tag}" \
      --set "images.reindexFromSnapshot.pullPolicy=Always" \
      --set "charts.kyverno.values.webhooksCleanup.image.tag=${image_tag}"

    run_named_hook "${POST_MA_INSTALL_HOOK:-}"

    print_step "Installing local source and target test clusters"
    helm --kube-context "${KUBE_CONTEXT}" upgrade --install --create-namespace -n ma tc charts/aggregates/testClusters \
      --wait --timeout 10m \
      --set "source.image=${LOCAL_REGISTRY}/migrations/elasticsearch_searchguard" \
      --set "source.imageTag=${image_tag}"
  else
    echo "Using non-local registry (USE_LOCAL_REGISTRY=false). Adjust repositories as needed."
    print_step "Installing Migration Assistant chart"
    helm --kube-context "${KUBE_CONTEXT}" upgrade --install --create-namespace -n ma ma charts/aggregates/migrationAssistantWithArgo \
      --wait --timeout 10m \
      -f charts/aggregates/migrationAssistantWithArgo/valuesForLocalK8s.yaml

    run_named_hook "${POST_MA_INSTALL_HOOK:-}"

    print_step "Installing local source and target test clusters"
    helm --kube-context "${KUBE_CONTEXT}" upgrade --install --create-namespace -n ma tc charts/aggregates/testClusters \
      --wait --timeout 10m
  fi

  run_named_hook "${POST_TC_INSTALL_HOOK:-}"
}

run_local_test_deploy() {
  cd "${MIGRATIONS_REPO_ROOT_DIR}"
  set_up_local_build_services
  build_local_images
  kubectl config set-context "${KUBE_CONTEXT}" --namespace=ma
  deploy_local_charts
  print_next_steps
}
