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

get_docker_network_dns_servers() {
  local resolv_conf dns_line
  resolv_conf="$(docker run --rm --network "${EXTERNAL_DOCKER_NETWORK}" registry:2 cat /etc/resolv.conf 2>/dev/null || true)"
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
    docker run -d \
      --name "${EXTERNAL_REGISTRY_NAME}" \
      --network "${EXTERNAL_DOCKER_NETWORK}" \
      -p "127.0.0.1:${EXTERNAL_REGISTRY_PORT}:5000" \
      -v "${EXTERNAL_REGISTRY_VOLUME}:/var/lib/registry" \
      --restart=always \
      registry:2 >/dev/null
  else
    if [[ "$(docker inspect -f '{{.State.Running}}' "${EXTERNAL_REGISTRY_NAME}")" != "true" ]]; then
      docker start "${EXTERNAL_REGISTRY_NAME}" >/dev/null
    fi
    if ! docker inspect -f '{{json .NetworkSettings.Networks}}' "${EXTERNAL_REGISTRY_NAME}" | grep -q "\"${EXTERNAL_DOCKER_NETWORK}\":"; then
      docker network connect "${EXTERNAL_DOCKER_NETWORK}" "${EXTERNAL_REGISTRY_NAME}"
    fi
  fi
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
