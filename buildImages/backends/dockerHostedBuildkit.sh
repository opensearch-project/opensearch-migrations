#!/usr/bin/env bash

set -euo pipefail

if [[ -z "${MIGRATIONS_REPO_ROOT_DIR:-}" ]]; then
  MIGRATIONS_REPO_ROOT_DIR="$(git rev-parse --show-toplevel)"
fi

set_docker_hosted_defaults() {
  : "${EXTERNAL_DOCKER_NETWORK:=local-migrations-network}"
  : "${EXTERNAL_REGISTRY_NAME:=docker-registry}"
  : "${EXTERNAL_REGISTRY_PORT:=5001}"
  : "${EXTERNAL_REGISTRY_VOLUME:=registry-data}"
  : "${BUILD_CONTAINER_REGISTRY_ENDPOINT:=${EXTERNAL_REGISTRY_NAME}:5000}"
}

# Rewrite an image reference through the ECR pull-through cache when
# ECR_PULL_THROUGH_ENDPOINT is set (e.g. on Jenkins hosts via
# /etc/profile.d/ecr-pull-through.sh). Mirrors PullThroughCacheHelper.groovy
# and the ptc_rewrite in deployment/k8s/.../mirrorToEcr.sh. Falls back to the
# original reference when the endpoint is unset or the registry isn't mapped.
ptc_rewrite_image() {
  local image="$1"
  local ptc="${ECR_PULL_THROUGH_ENDPOINT:-}"
  [[ -z "$ptc" ]] && { echo "$image"; return; }
  local prefix path
  case "$image" in
    docker.io/*)            prefix="docker-hub";               path="${image#docker.io/}" ;;
    registry-1.docker.io/*) prefix="docker-hub";               path="${image#registry-1.docker.io/}" ;;
    public.ecr.aws/*)       prefix="ecr-public";               path="${image#public.ecr.aws/}" ;;
    ghcr.io/*)              prefix="github-container-registry"; path="${image#ghcr.io/}" ;;
    registry.k8s.io/*)      prefix="k8s";                      path="${image#registry.k8s.io/}" ;;
    quay.io/*)              prefix="quay";                     path="${image#quay.io/}" ;;
    *)
      # Bare reference (e.g. "registry:2") — implicit Docker Hub library/
      if [[ "$image" != */* ]]; then
        prefix="docker-hub"; path="library/${image}"
      else
        echo "$image"; return
      fi
      ;;
  esac
  echo "${ptc}/${prefix}/${path}"
}

get_docker_network_dns_servers() {
  local resolv_conf dns_line registry_image
  registry_image="$(ptc_rewrite_image registry:2)"
  resolv_conf="$(docker run --rm --network "${EXTERNAL_DOCKER_NETWORK}" "${registry_image}" cat /etc/resolv.conf 2>/dev/null || true)"
  dns_line="$(printf '%s\n' "${resolv_conf}" | sed -n 's/^# ExtServers: \[\(.*\)\]$/\1/p' | head -n1)"

  if [[ -z "${dns_line}" ]]; then
    return 0
  fi

  printf '%s\n' "${dns_line}" | grep -oE '([0-9]{1,3}\.){3}[0-9]{1,3}'
}

write_buildkit_config() {
  local source_config target_config
  source_config="${MIGRATIONS_REPO_ROOT_DIR}/buildImages/buildkitd.toml"
  target_config="/tmp/buildkitd-${KUBE_CONTEXT//[^a-zA-Z0-9_-]/-}.toml"

  cp "${source_config}" "${target_config}"

  local docker_dns_servers=()
  local server
  while IFS= read -r server; do
    [[ -n "${server}" ]] && docker_dns_servers+=("${server}")
  done < <(get_docker_network_dns_servers)

  if [[ "${#docker_dns_servers[@]}" -gt 0 ]]; then
    {
      printf '\n[dns]\n'
      printf '  nameservers = ['
      local first=true
      for server in "${docker_dns_servers[@]}"; do
        if [[ "${first}" == true ]]; then
          first=false
        else
          printf ', '
        fi
        printf '"%s"' "${server}"
      done
      printf ']\n'
    } >> "${target_config}"
  fi

  printf '%s\n' "${target_config}"
}

ensure_docker_network() {
  if ! docker network inspect "${EXTERNAL_DOCKER_NETWORK}" >/dev/null 2>&1; then
    docker network create "${EXTERNAL_DOCKER_NETWORK}" >/dev/null
  fi
}

ensure_registry_container() {
  if ! docker inspect "${EXTERNAL_REGISTRY_NAME}" >/dev/null 2>&1; then
    local registry_image
    registry_image="$(ptc_rewrite_image registry:2)"
    docker run -d \
      --name "${EXTERNAL_REGISTRY_NAME}" \
      --network "${EXTERNAL_DOCKER_NETWORK}" \
      -p "127.0.0.1:${EXTERNAL_REGISTRY_PORT}:5000" \
      -v "${EXTERNAL_REGISTRY_VOLUME}:/var/lib/registry" \
      --restart=always \
      "${registry_image}" >/dev/null
  else
    if [[ "$(docker inspect -f '{{.State.Running}}' "${EXTERNAL_REGISTRY_NAME}")" != "true" ]]; then
      docker start "${EXTERNAL_REGISTRY_NAME}" >/dev/null
    fi
    if ! docker inspect -f '{{json .NetworkSettings.Networks}}' "${EXTERNAL_REGISTRY_NAME}" | grep -q "\"${EXTERNAL_DOCKER_NETWORK}\":"; then
      docker network connect "${EXTERNAL_DOCKER_NETWORK}" "${EXTERNAL_REGISTRY_NAME}"
    fi
  fi
}

# Connect a Kubernetes cluster's docker network to the registry's network and
# install a containerd hosts.toml on every node so localhost:${EXTERNAL_REGISTRY_PORT}
# routes to http://${EXTERNAL_REGISTRY_NAME}:5000. Lets kind and minikube share the
# same docker-hosted registry container without insecure-registry flags or NodePorts.
# Args: <cluster-docker-network> <node-container-name>...
connect_cluster_to_registry_network() {
  local cluster_network="$1"
  shift
  local nodes=("$@")

  set_docker_hosted_defaults

  if ! docker network inspect "${cluster_network}" >/dev/null 2>&1; then
    echo "Cluster docker network '${cluster_network}' does not exist" >&2
    return 1
  fi

  if [[ "$(docker inspect -f "{{json .NetworkSettings.Networks}}" "${EXTERNAL_REGISTRY_NAME}" \
        | grep -c "\"${cluster_network}\":")" -eq 0 ]]; then
    docker network connect "${cluster_network}" "${EXTERNAL_REGISTRY_NAME}"
  fi

  local registry_dir="/etc/containerd/certs.d/localhost:${EXTERNAL_REGISTRY_PORT}"
  local node
  for node in "${nodes[@]}"; do
    docker exec "${node}" mkdir -p "${registry_dir}"
    cat <<EOF | docker exec -i "${node}" cp /dev/stdin "${registry_dir}/hosts.toml"
[host."http://${EXTERNAL_REGISTRY_NAME}:5000"]
EOF
  done
}

ensure_buildx_builder() {
  local builder_name
  builder_name="builder-${KUBE_CONTEXT//[^a-zA-Z0-9_-]/-}"
  local buildkit_config
  buildkit_config="$(write_buildkit_config)"

  if ! docker buildx inspect "${builder_name}" --bootstrap >/dev/null 2>&1; then
    docker buildx rm "${builder_name}" >/dev/null 2>&1 || true
    docker buildx create --name "${builder_name}" \
      --driver docker-container \
      --driver-opt network="${EXTERNAL_DOCKER_NETWORK}" \
      --config "${buildkit_config}" >/dev/null
    docker buildx inspect "${builder_name}" --bootstrap >/dev/null
  fi
}

setup_build_backend() {
  set_docker_hosted_defaults
  export USE_LOCAL_REGISTRY="${USE_LOCAL_REGISTRY:-true}"
  export BUILD_CONTAINER_REGISTRY_ENDPOINT
  if [[ "${BUILD_CONTAINER_REGISTRY_ENDPOINT}" != "${EXTERNAL_REGISTRY_NAME}:5000" ]]; then
    echo "BUILD_CONTAINER_REGISTRY_ENDPOINT must match ${EXTERNAL_REGISTRY_NAME}:5000 for the dockerHosted buildkit backend." >&2
    exit 1
  fi
  ensure_docker_network
  ensure_registry_container
  ensure_buildx_builder
}
