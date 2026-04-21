#!/usr/bin/env bash

set -euo pipefail

set_docker_hosted_defaults() {
  : "${EXTERNAL_DOCKER_NETWORK:=local-migrations-network}"
  : "${EXTERNAL_REGISTRY_NAME:=docker-registry}"
  : "${EXTERNAL_REGISTRY_PORT:=5001}"
  : "${EXTERNAL_REGISTRY_VOLUME:=registry-data}"
  : "${EXTERNAL_BUILDKIT_NAME:=buildkitd}"
  : "${EXTERNAL_BUILDKIT_PORT:=1234}"
  : "${EXTERNAL_BUILDKIT_VOLUME:=buildkitd-state}"
  : "${BUILD_CONTAINER_REGISTRY_ENDPOINT:=${EXTERNAL_REGISTRY_NAME}:5000}"
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

ensure_buildkit_container() {
  local buildkit_config
  buildkit_config="${MIGRATIONS_REPO_ROOT_DIR}/buildImages/buildkitd.toml"
  if ! docker inspect "${EXTERNAL_BUILDKIT_NAME}" >/dev/null 2>&1; then
    docker run -d \
      --privileged \
      --name "${EXTERNAL_BUILDKIT_NAME}" \
      --network "${EXTERNAL_DOCKER_NETWORK}" \
      -p "127.0.0.1:${EXTERNAL_BUILDKIT_PORT}:1234" \
      -v "${buildkit_config}:/etc/buildkit/buildkitd.toml" \
      -v "${EXTERNAL_BUILDKIT_VOLUME}:/var/lib/buildkit" \
      --restart=always \
      moby/buildkit:latest \
      --addr "tcp://0.0.0.0:1234" \
      --config /etc/buildkit/buildkitd.toml >/dev/null
  else
    if [[ "$(docker inspect -f '{{.State.Running}}' "${EXTERNAL_BUILDKIT_NAME}")" != "true" ]]; then
      docker start "${EXTERNAL_BUILDKIT_NAME}" >/dev/null
    fi
    if ! docker inspect -f '{{json .NetworkSettings.Networks}}' "${EXTERNAL_BUILDKIT_NAME}" | grep -q "\"${EXTERNAL_DOCKER_NETWORK}\":"; then
      docker network connect "${EXTERNAL_DOCKER_NETWORK}" "${EXTERNAL_BUILDKIT_NAME}"
    fi
  fi
}

ensure_buildx_builder() {
  local builder_name
  builder_name="builder-${KUBE_CONTEXT//[^a-zA-Z0-9_-]/-}"
  if ! docker buildx inspect "${builder_name}" --bootstrap >/dev/null 2>&1; then
    docker buildx rm "${builder_name}" >/dev/null 2>&1 || true
    docker buildx create --name "${builder_name}" --driver remote "tcp://localhost:${EXTERNAL_BUILDKIT_PORT}" >/dev/null
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
  ensure_buildkit_container
  ensure_buildx_builder
}
