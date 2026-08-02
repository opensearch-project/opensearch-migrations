#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <image-manifest> <cache-group>" >&2
  exit 2
fi

manifest=$1
cache_group=$2
if [[ ! -f "$manifest" ]]; then
  echo "Image manifest does not exist: $manifest" >&2
  exit 2
fi

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
# shellcheck source=../../deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts/mirrorToEcr.sh
source "$repo_root/deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts/mirrorToEcr.sh"

pull_with_retries() {
  local image=$1
  local attempts=$2
  local attempt

  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if docker pull "$image"; then
      return 0
    fi

    if ((attempt < attempts)); then
      echo "Pull failed for $image (attempt $attempt/$attempts); retrying..." >&2
      sleep $((attempt * 10))
    fi
  done

  return 1
}

entries=$(manifest_section "$manifest" gradleTestImages)
if [[ -z "$entries" ]]; then
  echo "No gradleTestImages found in manifest: $manifest" >&2
  exit 2
fi

matched=0
while IFS='|' read -r entry_group canonical preferred extra; do
  if [[ "$entry_group" != "$cache_group" ]]; then
    continue
  fi
  matched=$((matched + 1))
  if [[ -z "${canonical:-}" ]]; then
    echo "Invalid gradleTestImages entry: missing canonical image" >&2
    exit 2
  fi
  if [[ -n "${extra:-}" ]]; then
    echo "Invalid gradleTestImages entry for $canonical" >&2
    exit 2
  fi
  if docker image inspect "$canonical" >/dev/null 2>&1; then
    echo "Image already available: $canonical"
    continue
  fi

  if [[ -n "${preferred:-}" ]] && pull_with_retries "$preferred" 2; then
    docker tag "$preferred" "$canonical"
    continue
  fi

  if [[ -n "${preferred:-}" ]]; then
    echo "Preferred source unavailable for $canonical; trying the canonical registry." >&2
  fi
  pull_with_retries "$canonical" 4
done <<< "$entries"

if [[ "$matched" -eq 0 ]]; then
  echo "No gradleTestImages found for cache group: $cache_group" >&2
  exit 2
fi

docker image list
