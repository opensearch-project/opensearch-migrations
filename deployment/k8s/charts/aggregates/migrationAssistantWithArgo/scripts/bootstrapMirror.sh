#!/bin/bash
# Orchestrates the private-ECR work used by aws-bootstrap.sh.
#
# The released bootstrap inlines this file during assembly. Keeping the
# concurrency and retry policy here makes the main deployment flow easier to
# follow and lets the mirroring behavior be tested without an AWS deployment.
set -euo pipefail

bootstrap_ma_image_tasks() {
  cat <<'EOF'
ma|capture_proxy|traffic-capture-proxy
ma|traffic_replayer|traffic-replayer
ma|reindex_from_snapshot|reindex-from-snapshot
ma|migration_console|console
EOF
}

# Interleave Migration Assistant and dependency images so neither source class
# monopolizes the first worker wave. Once the four MA images are scheduled, the
# remaining dependency images continue through the same bounded queue.
write_bootstrap_image_tasks() {
  local images="$1" include_ma_images="$2" output_file="$3"
  local dependency_file ma_file dependency_task ma_task emitted
  dependency_file=$(mktemp)
  ma_file=$(mktemp)

  printf '%s\n' "$images" \
    | sed 's/^[[:space:]]*//' \
    | sed 's/[[:space:]]*$//' \
    | grep -v '^#' \
    | grep -v '^$' > "$dependency_file"
  if [[ "$include_ma_images" == "true" ]]; then
    bootstrap_ma_image_tasks > "$ma_file"
  else
    : > "$ma_file"
  fi

  : > "$output_file"
  exec 3< "$dependency_file"
  exec 4< "$ma_file"
  while true; do
    emitted=false
    if IFS= read -r ma_task <&4; then
      printf '%s\n' "$ma_task" >> "$output_file"
      emitted=true
    fi
    if IFS= read -r dependency_task <&3; then
      printf 'dependency|%s\n' "$dependency_task" >> "$output_file"
      emitted=true
    fi
    [[ "$emitted" == "true" ]] || break
  done
  exec 3<&-
  exec 4<&-
  rm -f "$dependency_file" "$ma_file"
}

mirror_crane_copy_retry() {
  local src="$1" dst="$2" attempt
  if mirror_image_is_current "$src" "$dst"; then
    echo "  ✅ already mirrored: $dst"
    return 0
  fi
  for attempt in 1 2 3 4 5; do
    if crane copy "$src" "$dst" 2>&1 | tail -1; then
      return 0
    fi
    sleep $((5 * 2**(attempt-1)))
  done
  echo "  ❌ FAILED: $src → $dst" >&2
  return 1
}

mirror_ma_image_task() {
  local build_name="$1" public_suffix="$2" registry_uri="$3"
  local artifact_version="$4" image_tag="$5" ma_images_source="$6"
  local latest_dst="${registry_uri}:migrations_${build_name}_latest"
  local immutable_dst="${registry_uri}:migrations_${build_name}_${image_tag}"
  local src

  echo "  $public_suffix → $latest_dst"
  if [[ -n "$ma_images_source" ]]; then
    src="${ma_images_source}:migrations_${build_name}_latest"
    mirror_crane_copy_retry "$src" "$latest_dst" || return 1
  else
    if [[ -n "${ECR_PULL_THROUGH_ENDPOINT:-}" ]]; then
      src="${ECR_PULL_THROUGH_ENDPOINT}/ecr-public/opensearchproject/opensearch-migrations-${public_suffix}:${artifact_version}"
      if mirror_crane_copy_retry "$src" "$latest_dst" 2>/dev/null; then
        mirror_crane_copy_retry "$latest_dst" "$immutable_dst"
        return
      fi
    fi
    src="public.ecr.aws/opensearchproject/opensearch-migrations-${public_suffix}:${artifact_version}"
    mirror_crane_copy_retry "$src" "$latest_dst" || return 1
  fi
  mirror_crane_copy_retry "$latest_dst" "$immutable_dst"
}

mirror_bootstrap_image_task() {
  local task="$1" ecr_host="$2" region="$3" registry_uri="$4"
  local artifact_version="$5" image_tag="$6" ma_images_source="$7"
  local kind first second
  IFS='|' read -r kind first second <<< "$task"

  case "$kind" in
    dependency)
      copy_image "$ecr_host" "$region" \
        "${DOCKERHUB_MIRRORS:-mirror.gcr.io docker.io public.ecr.aws}" \
        "${ECR_PULL_THROUGH_ENDPOINT:-}" "$first"
      ;;
    ma)
      mirror_ma_image_task "$first" "$second" "$registry_uri" \
        "$artifact_version" "$image_tag" "$ma_images_source"
      ;;
    *)
      echo "Unknown mirror task: $task" >&2
      return 1
      ;;
  esac
}

cancel_bootstrap_mirror_jobs() {
  local pid
  for pid in $(jobs -pr); do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}

# Mirror dependency images, MA images, and charts using one total concurrency
# budget. Charts reserve one slot; every image type shares the remaining
# work-conserving queue.
mirror_bootstrap_artifacts() (
  local registry_uri="$1" region="$2" images="$3" charts="$4"
  local include_ma_images="$5" artifact_version="$6" image_tag="$7"
  local ma_images_source="$8" values_file="$9"
  local ecr_host="${registry_uri%%/*}"
  local task_file failure_file task_count chart_lane total_jobs image_jobs
  local image_rc=0 chart_rc=0 chart_pid=""

  task_file=$(mktemp)
  failure_file=$(mktemp)
  write_bootstrap_image_tasks "$images" "$include_ma_images" "$task_file"
  task_count=$(wc -l < "$task_file" | tr -d ' ')
  chart_lane=0
  [[ -n "$charts" ]] && chart_lane=1
  total_jobs=$(mirror_copy_concurrency "$((task_count + chart_lane))")
  image_jobs="$total_jobs"
  if (( chart_lane == 1 && total_jobs > 1 )); then
    image_jobs=$((total_jobs - 1))
  fi

  trap 'cancel_bootstrap_mirror_jobs; exit 129' HUP
  trap 'cancel_bootstrap_mirror_jobs; exit 130' INT
  trap 'cancel_bootstrap_mirror_jobs; exit 143' TERM
  trap 'cancel_bootstrap_mirror_jobs' EXIT

  prepare_image_mirror_auth "$ecr_host" "$region"
  ensure_mirror_ecr_repositories "$region" "$images"
  generate_private_ecr_values "$ecr_host" > "$values_file"
  echo "Private ECR values written to $values_file"
  echo "Using $total_jobs total mirror worker(s): $image_jobs for images$(
    (( chart_lane == 1 && total_jobs > 1 )) && printf ', 1 for charts'
  )."

  if (( chart_lane == 1 && total_jobs > 1 )); then
    mirror_charts_to_ecr "$ecr_host" "$region" "$charts" &
    chart_pid=$!
  fi

  if (( task_count > 0 )); then
    mirror_run_task_queue "$task_file" "$image_jobs" "$failure_file" \
      mirror_bootstrap_image_task "$ecr_host" "$region" "$registry_uri" \
      "$artifact_version" "$image_tag" "$ma_images_source" || image_rc=$?
  fi

  if [[ -n "$chart_pid" ]]; then
    wait "$chart_pid" || chart_rc=$?
  elif (( chart_lane == 1 )); then
    mirror_charts_to_ecr "$ecr_host" "$region" "$charts" || chart_rc=$?
  fi

  if [[ -s "$failure_file" ]]; then
    echo "" >&2
    echo "❌ The following image mirror tasks failed:" >&2
    sed 's/^/  - /' "$failure_file" >&2
    image_rc=1
  fi
  rm -f "$task_file" "$failure_file"

  trap - EXIT HUP INT TERM
  (( image_rc == 0 && chart_rc == 0 ))
)

# The ECR repository is created well before EKS finishes. Polling its live AWS
# state lets mirroring begin during CloudFormation without introducing a local
# checkpoint file.
wait_for_ecr_repository_uri() {
  local region="$1" repository_name="$2"
  local interval="${MIRROR_ECR_POLL_INTERVAL:-5}"
  local timeout="${MIRROR_ECR_WAIT_TIMEOUT:-900}"
  local aws_output last_error="" start_seconds elapsed remaining sleep_seconds

  case "$interval" in
    ''|0|*[!0-9]*)
      echo "Invalid MIRROR_ECR_POLL_INTERVAL='$interval'; expected a positive integer." >&2
      return 1
      ;;
  esac
  case "$timeout" in
    ''|0|*[!0-9]*)
      echo "Invalid MIRROR_ECR_WAIT_TIMEOUT='$timeout'; expected a positive integer." >&2
      return 1
      ;;
  esac
  interval=$((10#$interval))
  timeout=$((10#$timeout))
  start_seconds=$SECONDS

  echo "Waiting for ECR repository $repository_name before starting mirrors..." >&2
  while true; do
    if aws_output=$(aws ecr describe-repositories \
        --region "$region" \
        --repository-names "$repository_name" \
        --query 'repositories[0].repositoryUri' \
        --output text 2>&1); then
      if [[ -n "$aws_output" && "$aws_output" != "None" ]]; then
        printf '%s\n' "$aws_output"
        return 0
      fi
      last_error=""
    else
      case "$aws_output" in
        *ExpiredToken*|*RequestExpired*|*InvalidClientTokenId*|*UnrecognizedClientException*|\
        *"Unable to locate credentials"*|*AccessDenied*|*UnauthorizedOperation*|\
        *SignatureDoesNotMatch*)
          echo "Unable to query ECR repository $repository_name:" >&2
          echo "$aws_output" >&2
          echo "Refresh or correct the AWS credentials, then rerun the same bootstrap command." >&2
          return 1
          ;;
        *)
          last_error="$aws_output"
          ;;
      esac
    fi

    elapsed=$((SECONDS - start_seconds))
    if (( elapsed >= timeout )); then
      break
    fi
    remaining=$((timeout - elapsed))
    sleep_seconds="$interval"
    (( sleep_seconds <= remaining )) || sleep_seconds="$remaining"
    sleep "$sleep_seconds"
  done

  echo "Timed out after ${timeout} seconds waiting for ECR repository $repository_name." >&2
  [[ -z "$last_error" ]] || echo "Last AWS error: $last_error" >&2
  echo "CloudFormation may still be running. Rerun the same bootstrap command;" >&2
  echo "it will attach to the stack operation, reuse completed work, and retry safely." >&2
  return 1
}
