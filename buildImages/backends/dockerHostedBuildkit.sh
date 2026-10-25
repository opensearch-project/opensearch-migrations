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

  # buildkitd.toml marks docker-registry:5000 as plain HTTP; point that at this run's registry.
  sed "s/docker-registry:5000/${BUILD_CONTAINER_REGISTRY_ENDPOINT}/" "${source_config}" > "${target_config}"

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

teardown_registry_container() {
  set_docker_hosted_defaults
  # Must be called before any docker network rm that the registry is connected
  # to; docker network rm fails silently when containers are still attached.
  docker rm -f "${EXTERNAL_REGISTRY_NAME}" >/dev/null 2>&1 || true
}

# The registry is shared by every build and cluster on this host, so this never replaces a
# registry that is already there: in-flight pushes and pulls from other runs would fail. It
# creates the registry if it is missing and starts it if it is stopped. To replace it on purpose,
# call teardown_registry_container first.
ensure_registry_container() {
  local registry_image state run_output
  state="$(docker inspect -f '{{.State.Running}}' "${EXTERNAL_REGISTRY_NAME}" 2>/dev/null || true)"
  if [[ -z "${state}" ]]; then
    registry_image="$(ptc_rewrite_image registry:2)"
    # Bind on 0.0.0.0 so the registry is reachable at the host gateway IP from
    # inside any cluster node container, without docker network connect.
    # The registry-data volume persists across runs so image layers are reused.
    if ! run_output="$(docker run -d \
      --name "${EXTERNAL_REGISTRY_NAME}" \
      --network "${EXTERNAL_DOCKER_NETWORK}" \
      -p "${EXTERNAL_REGISTRY_PORT}:5000" \
      -v "${EXTERNAL_REGISTRY_VOLUME}:/var/lib/registry" \
      --restart=always \
      "${registry_image}" 2>&1)"; then
      # A concurrent run may have created it first; anything else is a real failure.
      if ! docker inspect "${EXTERNAL_REGISTRY_NAME}" >/dev/null 2>&1; then
        echo "${run_output}" >&2
        return 1
      fi
    fi
  elif [[ "${state}" != "true" ]]; then
    docker start "${EXTERNAL_REGISTRY_NAME}" >/dev/null
  fi

  # A concurrent run's container can take a moment to start.
  local attempt
  for attempt in $(seq 1 30); do
    [[ "$(docker inspect -f '{{.State.Running}}' "${EXTERNAL_REGISTRY_NAME}" 2>/dev/null)" == "true" ]] && break
    if [[ "${attempt}" == 30 ]]; then
      echo "Registry container ${EXTERNAL_REGISTRY_NAME} isn't running. ${run_output:-}" >&2
      return 1
    fi
    sleep 1
  done

  # An existing registry must match this run's settings. Fail rather than replace it.
  if ! docker port "${EXTERNAL_REGISTRY_NAME}" 5000/tcp 2>/dev/null | grep -q "^0.0.0.0:${EXTERNAL_REGISTRY_PORT}\$"; then
    echo "Registry container ${EXTERNAL_REGISTRY_NAME} doesn't publish port ${EXTERNAL_REGISTRY_PORT} on 0.0.0.0, so cluster nodes can't reach it." >&2
    echo "Set EXTERNAL_REGISTRY_PORT to match it, or replace it with teardown_registry_container (this interrupts other builds and clusters using it)." >&2
    return 1
  fi
  if ! docker inspect -f '{{json .NetworkSettings.Networks}}' "${EXTERNAL_REGISTRY_NAME}" | grep -q "\"${EXTERNAL_DOCKER_NETWORK}\":"; then
    echo "Registry container ${EXTERNAL_REGISTRY_NAME} isn't on docker network ${EXTERNAL_DOCKER_NETWORK}." >&2
    echo "Set EXTERNAL_DOCKER_NETWORK to match it, or replace it with teardown_registry_container (this interrupts other builds and clusters using it)." >&2
    return 1
  fi

  for attempt in $(seq 1 30); do
    curl -sf "http://localhost:${EXTERNAL_REGISTRY_PORT}/v2/" >/dev/null && return 0
    sleep 1
  done
  echo "Registry ${EXTERNAL_REGISTRY_NAME} didn't answer on localhost:${EXTERNAL_REGISTRY_PORT} within 30s" >&2
  return 1
}

# Configure containerd on each cluster node to pull from the host-bound
# registry port. No docker network connect is used: the registry is bound on
# 0.0.0.0 so it is reachable at the node's default gateway IP (the host).
#
# Steps per node:
#  1. Resolve the host gateway IP from inside the node (default route next-hop).
#  2. Write a hosts.toml for the named image registry that points directly at
#     http://<gateway-ip>:EXTERNAL_REGISTRY_PORT.
#
# Using the gateway IP in hosts.toml is deliberate. Entries appended to a
# container's /etc/hosts are regenerated when Docker restarts, which previously
# left long-lived kind clusters unable to resolve the local registry.
#
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

  local registry_dir="/etc/containerd/certs.d/${EXTERNAL_REGISTRY_NAME}:${EXTERNAL_REGISTRY_PORT}"
  local node host_gw
  for node in "${nodes[@]}"; do
    host_gw="$(docker exec "${node}" sh -c "ip route show default | awk '/default/ {print \$3}'")"
    docker exec "${node}" mkdir -p "${registry_dir}"
    cat <<EOF | docker exec -i "${node}" cp /dev/stdin "${registry_dir}/hosts.toml"
server = "http://${host_gw}:${EXTERNAL_REGISTRY_PORT}"
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
