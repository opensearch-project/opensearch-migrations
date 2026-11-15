#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
MIRROR_SCRIPTS="${REPO_ROOT}/deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts"

# shellcheck source=../deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts/mirrorToEcrUtils.sh
. "${MIRROR_SCRIPTS}/mirrorToEcrUtils.sh"
# shellcheck source=../deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts/mirrorToEcr.sh
. "${MIRROR_SCRIPTS}/mirrorToEcr.sh"
# shellcheck source=../deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts/generatePrivateEcrValues.sh
. "${MIRROR_SCRIPTS}/generatePrivateEcrValues.sh"
# shellcheck source=../deployment/k8s/charts/aggregates/migrationAssistantWithArgo/scripts/bootstrapMirror.sh
. "${MIRROR_SCRIPTS}/bootstrapMirror.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

tmp_root="$(mktemp -d)"
trap 'rm -rf "$tmp_root"' EXIT

task_file="${tmp_root}/tasks"
write_bootstrap_image_tasks $'dependency-one:v1\ndependency-two:v2\ndependency-three:v3' \
  true "$task_file"
cat > "${tmp_root}/expected-tasks" <<'EOF'
ma|capture_proxy|traffic-capture-proxy
dependency|dependency-one:v1
ma|traffic_replayer|traffic-replayer
dependency|dependency-two:v2
ma|reindex_from_snapshot|reindex-from-snapshot
dependency|dependency-three:v3
ma|migration_console|console
EOF
cmp -s "${tmp_root}/expected-tasks" "$task_file" \
  || fail "MA and dependency images were not evenly interleaved"

orchestration_log="${tmp_root}/orchestration"
worker_limit_file="${tmp_root}/worker-limit"
captured_tasks="${tmp_root}/captured-tasks"
values_file="${tmp_root}/private-values"
: > "$orchestration_log"

prepare_image_mirror_auth() {
  printf 'auth %s %s\n' "$1" "$2" >> "$orchestration_log"
}
ensure_mirror_ecr_repositories() {
  printf 'repos %s\n' "$1" >> "$orchestration_log"
}
generate_private_ecr_values() {
  printf 'generated-for: %s\n' "$1"
}
mirror_run_task_queue() {
  cp "$1" "$captured_tasks"
  printf '%s\n' "$2" > "$worker_limit_file"
  : > "$3"
}
mirror_charts_to_ecr() {
  printf 'charts %s %s\n' "$1" "$2" >> "$orchestration_log"
}

MIRROR_CONCURRENCY=4 mirror_bootstrap_artifacts \
  "123456789012.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-dev-us-east-1" \
  us-east-1 $'dependency-one:v1\ndependency-two:v2' \
  'chart|1.0.0|https://example.invalid/charts' true v1.2.3 immutable "" "$values_file"

[[ "$(cat "$worker_limit_file")" == "3" ]] \
  || fail "chart mirroring did not reserve one slot from the global concurrency budget"
grep -q '^ma|' "$captured_tasks" || fail "MA images were not submitted to the shared queue"
grep -q '^dependency|' "$captured_tasks" || fail "dependency images were not submitted to the shared queue"
grep -q '^charts ' "$orchestration_log" || fail "chart lane did not run"
grep -q '^generated-for: 123456789012.dkr.ecr.us-east-1.amazonaws.com$' "$values_file" \
  || fail "private ECR values used the wrong registry host"

mirror_charts_to_ecr() {
  return 1
}
if MIRROR_CONCURRENCY=2 mirror_bootstrap_artifacts \
    "123456789012.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-dev-us-east-1" \
    us-east-1 'dependency-one:v1' \
    'chart|1.0.0|https://example.invalid/charts' false v1.2.3 immutable "" "$values_file"; then
  fail "chart mirroring failure was not propagated"
fi

poll_count="${tmp_root}/poll-count"
printf '0\n' > "$poll_count"
aws() {
  local count
  count=$(cat "$poll_count")
  count=$((count + 1))
  printf '%s\n' "$count" > "$poll_count"
  if [[ "$count" -lt 3 ]]; then
    return 1
  fi
  printf '123456789012.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-dev-us-east-1\n'
}

registry_uri=$(MIRROR_ECR_POLL_INTERVAL=0 \
  wait_for_ecr_repository_uri us-east-1 migration-ecr-dev-us-east-1)
[[ "$registry_uri" == \
    "123456789012.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-dev-us-east-1" ]] \
  || fail "ECR repository polling returned the wrong URI"
[[ "$(cat "$poll_count")" == "3" ]] || fail "ECR repository polling did not retry"

echo "bootstrapMirror tests passed"
