#!/usr/bin/env bats
# test_helm_failure_path.bats — regression guards for the three bugs that
# made the operator hit "Error: failed pre-install … context deadline
# exceeded" with no diagnostics:
#
#   1. helm_dump_diagnostics MUST run when helm exits non-zero (set +e
#      around log_stream; otherwise set -e aborts the function before the
#      diagnostic dump).
#   2. helm_recover_orphan_jobs MUST be called BEFORE helm install, even
#      when no helm release exists yet (the Jobs are independent of the
#      release).
#   3. The retry hint must mention the orphan-job recovery path so the
#      operator knows what to do next.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  PROJECT_ROOT="$(cd "$BATS_TEST_DIRNAME/.." && pwd)"
  export PROJECT_ROOT
  load_libs _common.sh ui.sh log.sh state.sh version.sh discover.sh \
            install_tools.sh artifacts.sh wizard.sh cfn.sh crane.sh helm.sh
  log_init
}

teardown() {
  teardown_isolated_home
}

run_split() {
  local out_file err_file
  out_file=$(mktemp); err_file=$(mktemp)
  ( "$@" ) >"$out_file" 2>"$err_file"
  local rc=$?
  STDOUT=$(cat "$out_file")
  STDERR=$(cat "$err_file")
  rm -f "$out_file" "$err_file"
  return $rc
}

# ---------- (1) set +e wiring ----------

@test "helm.sh wraps the helm upgrade call with 'set +e' so diagnostics run on failure" {
  # Static check: the very specific failure mode we hit was set -e aborting
  # before the diagnostics dump. The fix MUST keep the set +e/set -e pair
  # around the log_stream "helm" call.
  awk '/log_stream "helm" helm upgrade/ {found=1; getline; while($0 !~ /helm_rc=\$\?/) {getline; if (NR > 200) exit}; print prev}
       {prev=$0}' "$PROJECT_ROOT/lib/helm.sh" >/dev/null
  # Stronger: the file must have `set +e` somewhere immediately before
  # `log_stream "helm" helm upgrade`.
  grep -B1 -F 'log_stream "helm" helm upgrade' "$PROJECT_ROOT/lib/helm.sh" \
    | grep -q 'set +e'
}

@test "helm.sh wraps the kubectl wait call with 'set +e' too" {
  grep -B1 -F 'log_stream "kubectl-wait" kubectl wait' "$PROJECT_ROOT/lib/helm.sh" \
    | grep -q 'set +e'
}

@test "helm.sh calls helm_dump_diagnostics when helm fails (and on kubectl-wait failure)" {
  # Two separate failure paths must both invoke the diagnostics dump.
  count=$(grep -c 'helm_dump_diagnostics ' "$PROJECT_ROOT/lib/helm.sh" || echo 0)
  [ "$count" -ge 2 ]
}

# ---------- (2) orphan-job recovery is invoked unconditionally ----------

@test "helm_recover_if_stuck calls helm_recover_orphan_jobs first" {
  # The fix is order-sensitive: orphan jobs are checked even when no
  # helm release exists. Static check: helm_recover_orphan_jobs appears
  # in helm_recover_if_stuck BEFORE the case-on-release-status block.
  awk '/^helm_recover_if_stuck\(\)/        {body=1}
       body && /helm_recover_orphan_jobs/  {found_jobs=NR}
       body && /^  case "\$status" in/     {found_case=NR; exit}
       END { if (found_jobs && found_case && found_jobs < found_case) print "OK"; else print "BAD" }' \
       "$PROJECT_ROOT/lib/helm.sh" >/tmp/order.$$
  [[ "$(cat /tmp/order.$$)" == "OK" ]]
  rm -f /tmp/order.$$
}

@test "helm_recover_orphan_jobs deletes stuck pre-install Jobs" {
  # Stub kubectl: get jobs returns one job that hasn't completed.
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get jobs"*"--no-headers"*)
    printf 'default-helm-installer    \nfinished-job 1\n' ;;
  *"delete job default-helm-installer"*)
    echo "job.batch/default-helm-installer deleted"
    exit 0 ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  # Auto-confirm the deletion prompt.
  ui_prompt() { printf -v "${3:-_}" '%s' 'Y/n'; }
  export -f ui_prompt

  run helm_recover_orphan_jobs default ma
  [ "$status" -eq 0 ]
  grep -q 'STREAM\[kubectl-del-job-default-helm-installer\]' "$LOG_FILE"
}

@test "helm_recover_orphan_jobs leaves completed Jobs alone" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get jobs"*"--no-headers"*)
    printf 'default-helm-installer 1\n' ;;
  *"delete job"*) echo "should not run"; exit 0 ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  run helm_recover_orphan_jobs default ma
  [ "$status" -eq 0 ]
  ! grep -q 'STREAM\[kubectl-del-job-' "$LOG_FILE"
}

@test "helm_recover_orphan_jobs ignores Jobs that don't match the release prefix" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get jobs"*"--no-headers"*)
    # Job belongs to a DIFFERENT release; not a helm-hook orphan for ours.
    printf 'other-release-thing    \n' ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  ui_prompt() { printf -v "${3:-_}" '%s' 'Y/n'; }
  export -f ui_prompt

  run helm_recover_orphan_jobs default ma
  [ "$status" -eq 0 ]
  ! grep -q 'STREAM\[kubectl-del-job-' "$LOG_FILE"
}

@test "helm_recover_orphan_jobs declined deletion leaves Jobs in place" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get jobs"*"--no-headers"*)
    printf 'default-helm-installer    \n' ;;
  *"delete job"*)
    echo "should not have been called"
    exit 1 ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  ui_prompt() { printf -v "${3:-_}" '%s' 'n'; }
  export -f ui_prompt

  run helm_recover_orphan_jobs default ma
  [ "$status" -eq 0 ]
  ! grep -q 'STREAM\[kubectl-del-job-' "$LOG_FILE"
}

# ---------- (3) retry hint mentions the recovery path ----------

@test "retry hint message names the orphan-job recovery" {
  # Static check: when helm fails, the user-facing hint must point at
  # helm_recover_if_stuck OR mention orphan helm-hook Jobs explicitly.
  grep -E 'orphan helm-hook Jobs|recovery' "$PROJECT_ROOT/lib/helm.sh" >/dev/null
}