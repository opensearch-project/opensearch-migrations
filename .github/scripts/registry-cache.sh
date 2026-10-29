#!/usr/bin/env bash
#
# Pull-through cache registries for the local integ test workflows.
#
# Runs a registry:2 proxy for Docker Hub, which rate-limits anonymous pulls. Its storage
# lives under REGISTRY_CACHE_DIR, which the workflow restores from and saves to the GitHub
# Actions cache. BuildKit and the kind node containerd use the proxy as a mirror and fall
# back to the upstream registry when it can't serve a request, so a cold or broken cache
# only costs speed.
#
# No ports are published: proxies are reached by container name on the Docker
# networks the builders and kind nodes use.
#
# Usage:
#   registry-cache.sh start               Start the proxies and write the BuildKit mirror config.
#   registry-cache.sh configure-kind NAME Point the kind cluster's nodes at the proxies.
#   registry-cache.sh stop                Stop the proxies and print the cache size.

set -euo pipefail

readonly REGISTRY_CACHE_DIR="${REGISTRY_CACHE_DIR:-${RUNNER_TEMP:-/tmp}/registry-cache}"
readonly REGISTRY_CACHE_NETWORK="${EXTERNAL_DOCKER_NETWORK:-local-migrations-network}"
readonly REGISTRY_CACHE_IMAGE="${REGISTRY_CACHE_IMAGE:-registry:2}"
readonly BUILDKIT_MIRROR_CONFIG="${REGISTRY_CACHE_DIR}/buildkitd-mirrors.toml"

# name|upstream registry host|upstream URL for the proxy
# ECR Public can't be proxied: it answers HEAD on blobs with 401 even with a valid token,
# and the registry proxy checks every blob with HEAD before fetching it.
readonly UPSTREAMS=(
  "regcache-docker-io|docker.io|https://registry-1.docker.io"
)

start() {
  docker network inspect "${REGISTRY_CACHE_NETWORK}" >/dev/null 2>&1 ||
    docker network create "${REGISTRY_CACHE_NETWORK}" >/dev/null

  : > "${BUILDKIT_MIRROR_CONFIG}.tmp"
  local entry name host url
  for entry in "${UPSTREAMS[@]}"; do
    IFS='|' read -r name host url <<<"${entry}"
    mkdir -p "${REGISTRY_CACHE_DIR}/${name}"
    # The proxy's scheduler deletes content it has tracked for longer than 7 days. A restored
    # cache would be purged on startup, so drop the schedule; untracked content is kept.
    rm -f "${REGISTRY_CACHE_DIR}/${name}/scheduler-state.json"

    docker rm -f "${name}" >/dev/null 2>&1 || true
    docker run -d --name "${name}" \
      --network "${REGISTRY_CACHE_NETWORK}" \
      -v "${REGISTRY_CACHE_DIR}/${name}:/var/lib/registry" \
      -e REGISTRY_PROXY_REMOTEURL="${url}" \
      "${REGISTRY_CACHE_IMAGE}" >/dev/null

    cat >> "${BUILDKIT_MIRROR_CONFIG}.tmp" <<EOF

[registry."${host}"]
  mirrors = ["${name}:5000"]

[registry."${name}:5000"]
  http = true
EOF
  done
  mv "${BUILDKIT_MIRROR_CONFIG}.tmp" "${BUILDKIT_MIRROR_CONFIG}"

  for entry in "${UPSTREAMS[@]}"; do
    IFS='|' read -r name _ _ <<<"${entry}"
    wait_until_serving "${name}"
  done

  # dockerHostedBuildkit.sh appends this file to the BuildKit config it generates.
  if [[ -n "${GITHUB_ENV:-}" ]]; then
    echo "BUILDKIT_EXTRA_CONFIG=${BUILDKIT_MIRROR_CONFIG}" >> "${GITHUB_ENV}"
  fi
  echo "Registry cache started: $(du -sh "${REGISTRY_CACHE_DIR}" | cut -f1) restored"
}

wait_until_serving() {
  local name="$1"
  for _ in $(seq 1 30); do
    if docker run --rm --network "${REGISTRY_CACHE_NETWORK}" "${REGISTRY_CACHE_IMAGE}" \
        wget -q -O /dev/null "http://${name}:5000/v2/" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "Registry cache ${name} didn't start; pulls will go to the upstream registry" >&2
  docker logs "${name}" >&2 || true
}

configure_kind() {
  local cluster="$1" entry name host url node
  local nodes=()
  while IFS= read -r node; do
    [[ -n "${node}" ]] && nodes+=("${node}")
  done < <(kind get nodes --name "${cluster}")
  if [[ "${#nodes[@]}" -eq 0 ]]; then
    echo "No nodes found for kind cluster ${cluster}" >&2
    return 1
  fi

  for entry in "${UPSTREAMS[@]}"; do
    IFS='|' read -r name host url <<<"${entry}"
    # kind nodes resolve container names on the kind network through Docker's DNS.
    docker network connect kind "${name}" 2>/dev/null || true
    for node in "${nodes[@]}"; do
      docker exec "${node}" mkdir -p "/etc/containerd/certs.d/${host}"
      cat <<EOF | docker exec -i "${node}" cp /dev/stdin "/etc/containerd/certs.d/${host}/hosts.toml"
server = "${url}"

[host."http://${name}:5000"]
  capabilities = ["pull", "resolve"]
EOF
    done
  done
  echo "Configured ${#nodes[@]} kind node(s) to pull through the registry cache"
}

stop() {
  local entry name
  for entry in "${UPSTREAMS[@]}"; do
    IFS='|' read -r name _ _ <<<"${entry}"
    docker stop "${name}" >/dev/null 2>&1 || true
  done
  report
}

report() {
  local entry name size
  echo "Registry cache size:"
  for entry in "${UPSTREAMS[@]}"; do
    IFS='|' read -r name _ _ <<<"${entry}"
    size="$(du -sh "${REGISTRY_CACHE_DIR}/${name}" 2>/dev/null | cut -f1)"
    echo "  ${name}: ${size:-0}"
    if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
      echo "- Registry cache \`${name}\`: ${size:-0}" >> "${GITHUB_STEP_SUMMARY}"
    fi
  done
}

case "${1:-}" in
  start) start ;;
  configure-kind) configure_kind "${2:?kind cluster name required}" ;;
  stop) stop ;;
  report) report ;;
  *)
    echo "Usage: $0 start | configure-kind <cluster> | stop | report" >&2
    exit 2
    ;;
esac
