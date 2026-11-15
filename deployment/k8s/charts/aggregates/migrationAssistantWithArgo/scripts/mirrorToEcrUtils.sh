#!/bin/bash
# Shared fast-path helpers for ECR mirroring.
#
# This file is sourced by mirrorToEcr.sh in a repository checkout and inlined
# into the released aws-bootstrap.sh by assemble-bootstrap.sh.
set -euo pipefail

# Return success when the destination tag already points at the same manifest
# as the source. Check the destination first so a cold mirror does not pay for
# an extra source lookup when the destination tag does not exist yet.
mirror_image_is_current() {
  local src="$1" dest="$2" src_digest dest_digest

  dest_digest=$(crane digest "$dest" 2>/dev/null) || return 1
  src_digest=$(crane digest "$src" 2>/dev/null) || return 1

  [[ -n "$src_digest" && "$src_digest" == "$dest_digest" ]]
}

# Convert an image reference into the repository used by the private mirror.
mirror_ecr_repo_name() {
  local image="$1" without_digest last_component
  without_digest="${image%%@*}"
  last_component="${without_digest##*/}"
  if [[ "$last_component" == *:* ]]; then
    without_digest="${without_digest%:*}"
  fi
  echo "mirrored/${without_digest}"
}

# Create only repositories that are absent. Listing once makes a warm rerun
# avoid one create-repository request per image.
ensure_mirror_ecr_repositories() {
  local region="$1" images="$2" existing image repo create_output

  if ! existing=$(aws ecr describe-repositories \
      --region "$region" \
      --query 'repositories[].repositoryName' \
      --output text); then
    echo "Unable to list existing ECR repositories in $region." >&2
    return 1
  fi
  existing=$(printf '%s\n' "$existing" | tr '\t' '\n')

  for image in $images; do
    repo=$(mirror_ecr_repo_name "$image")
    if printf '%s\n' "$existing" | grep -qxF "$repo"; then
      continue
    fi

    echo "  Creating ECR repository: $repo"
    if ! create_output=$(aws ecr create-repository \
        --repository-name "$repo" \
        --region "$region" 2>&1); then
      if [[ "$create_output" != *RepositoryAlreadyExistsException* &&
            "$create_output" != *"already exists"* ]]; then
        echo "$create_output" >&2
        return 1
      fi
    fi
    existing="${existing}"$'\n'"${repo}"
  done
}

# Return a safe worker count, defaulting to the GitFarm implementation's six
# workers while allowing operators to tune it for their network or host.
mirror_copy_concurrency() {
  local total="$1" requested="${MIRROR_CONCURRENCY:-6}"

  case "$requested" in
    ''|0|*[!0-9]*)
      echo "Invalid MIRROR_CONCURRENCY='$requested'; expected a positive integer." >&2
      return 1
      ;;
  esac
  requested=$((10#$requested))

  if (( requested > total )); then
    requested="$total"
  fi
  (( requested > 0 )) || requested=1
  echo "$requested"
}

# Run newline-delimited tasks with a bounded, work-conserving worker pool.
# Args: <task-file> <worker-count> <failure-file> <worker-function> [worker-args...]
mirror_run_task_queue() {
  local task_file="$1" max_jobs="$2" failure_file="$3" worker="$4"
  shift 4
  local task pid
  local -a worker_args pids remaining
  worker_args=("$@")
  pids=()
  remaining=()

  : > "$failure_file"
  while IFS= read -r task; do
    [[ -z "$task" ]] && continue
    while (( ${#pids[@]} >= max_jobs )); do
      remaining=()
      for pid in "${pids[@]}"; do
        if kill -0 "$pid" 2>/dev/null; then
          remaining+=("$pid")
        else
          wait "$pid" 2>/dev/null || true
        fi
      done
      if (( ${#remaining[@]} > 0 )); then
        pids=("${remaining[@]}")
      else
        pids=()
      fi
      (( ${#pids[@]} < max_jobs )) || sleep 0.2
    done

    (
      "$worker" "$task" ${worker_args[@]+"${worker_args[@]}"} \
        || printf '%s\n' "$task" >> "$failure_file"
    ) &
    pids+=("$!")
  done < "$task_file"

  if (( ${#pids[@]} > 0 )); then
    for pid in "${pids[@]}"; do
      wait "$pid" 2>/dev/null || true
    done
  fi
  [[ ! -s "$failure_file" ]]
}

# Helm OCI charts appear as tagged images in ECR.
mirror_ecr_tag_exists() {
  local region="$1" repo="$2" tag="$3"
  aws ecr describe-images \
    --region "$region" \
    --repository-name "$repo" \
    --image-ids "imageTag=$tag" >/dev/null 2>&1
}
