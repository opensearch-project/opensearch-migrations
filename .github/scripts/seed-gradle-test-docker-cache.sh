#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <image-list>" >&2
  exit 2
fi

image_list=$1
if [[ ! -f "$image_list" ]]; then
  echo "Image list does not exist: $image_list" >&2
  exit 2
fi

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

matched=0
while IFS='|' read -r canonical preferred extra; do
  if [[ -z "$canonical" || "$canonical" == \#* ]]; then
    continue
  fi
  matched=$((matched + 1))
  if [[ -n "${extra:-}" ]]; then
    echo "Invalid image list entry for $canonical" >&2
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
done < "$image_list"

if [[ "$matched" -eq 0 ]]; then
  echo "No images found in image list: $image_list" >&2
  exit 2
fi

docker image list
