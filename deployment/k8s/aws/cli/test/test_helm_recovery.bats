#!/usr/bin/env bats
# test_helm_recovery.bats — namespace ensure, helm recovery, diagnostics.

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

# ---------- helm_ensure_namespace ----------

@test "helm_ensure_namespace is silent when namespace exists" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get namespace"*) exit 0 ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  run_split helm_ensure_namespace ma
  [ -z "$STDOUT" ]
  # No "creating namespace" line on stderr either.
  [[ "$STDERR" != *"creating namespace"* ]]
  grep -q 'namespace ma already exists' "$LOG_FILE"
}

@test "helm_ensure_namespace creates namespace when missing — no YAML on stderr" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get namespace"*) exit 1 ;;     # missing
  *"create namespace"*) echo "namespace/ma created" ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  run_split helm_ensure_namespace ma
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"creating namespace: ma"* ]]
  # Critically: no apiVersion / kind / metadata YAML lines.
  [[ "$STDERR" != *"apiVersion"* ]]
  [[ "$STDERR" != *"kind: Namespace"* ]]
  [[ "$STDERR" != *"spec: {}"* ]]
  grep -q 'namespace ma created' "$LOG_FILE"
}

@test "helm_ensure_namespace dies with a useful message when create fails" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get namespace"*) exit 1 ;;
  *"create namespace"*) echo "boom" >&2; exit 1 ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  run helm_ensure_namespace ma
  [ "$status" -ne 0 ]
  [[ "$output" == *"could not create namespace ma"* ]]
}

# ---------- _helm_release_status ----------

@test "_helm_release_status returns empty when release does not exist" {
  cat >"$STUB_DIR/helm" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
  chmod +x "$STUB_DIR/helm"

  out=$(_helm_release_status default ma)
  [ -z "$out" ]
}

@test "_helm_release_status extracts 'pending-upgrade' from helm json" {
  cat >"$STUB_DIR/helm" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"status"*"-o json"*)
    cat <<JSON
{ "name": "default", "info": { "status": "pending-upgrade", "description": "stuck" } }
JSON
    ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/helm"

  out=$(_helm_release_status default ma)
  [ "$out" = "pending-upgrade" ]
}

@test "_helm_release_status extracts 'deployed' for healthy release" {
  cat >"$STUB_DIR/helm" <<'EOF'
#!/usr/bin/env bash
cat <<'JSON'
{ "name":"default", "info":{ "status":"deployed" } }
JSON
EOF
  chmod +x "$STUB_DIR/helm"

  out=$(_helm_release_status default ma)
  [ "$out" = "deployed" ]
}

# ---------- helm_recover_if_stuck ----------

@test "helm_recover_if_stuck returns 0 immediately when status=deployed" {
  cat >"$STUB_DIR/helm" <<'EOF'
#!/usr/bin/env bash
echo '{ "info":{ "status":"deployed" } }'
EOF
  chmod +x "$STUB_DIR/helm"

  run helm_recover_if_stuck default ma
  [ "$status" -eq 0 ]
}

@test "helm_recover_if_stuck on pending-install offers uninstall" {
  cat >"$STUB_DIR/helm" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"status"*"-o json"*)
    echo '{ "info":{ "status":"pending-install" } }' ;;
  *"uninstall"*) echo "release default uninstalled" ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/helm"

  # Stub ui_prompt to auto-answer "Y" so ui_confirm returns 0.
  ui_prompt() { printf -v "${3:-_}" '%s' 'Y/n'; }
  export -f ui_prompt

  run helm_recover_if_stuck default ma
  [ "$status" -eq 0 ]
  grep -q 'helm-uninstall' "$LOG_FILE"
}

@test "helm_recover_if_stuck on pending-install with declined confirm returns non-zero" {
  cat >"$STUB_DIR/helm" <<'EOF'
#!/usr/bin/env bash
echo '{ "info":{ "status":"pending-install" } }'
EOF
  chmod +x "$STUB_DIR/helm"

  ui_prompt() { printf -v "${3:-_}" '%s' 'n'; }
  export -f ui_prompt

  run helm_recover_if_stuck default ma
  [ "$status" -ne 0 ]
}

@test "helm_recover_if_stuck on pending-upgrade choice 1 = rollback" {
  cat >"$STUB_DIR/helm" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"status"*"-o json"*)
    echo '{ "info":{ "status":"pending-upgrade" } }' ;;
  *"rollback"*) echo "rolled back" ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/helm"

  ui_prompt() { printf -v "${3:-_}" '%s' '1'; }
  export -f ui_prompt

  run helm_recover_if_stuck default ma
  [ "$status" -eq 0 ]
  grep -q 'helm-rollback' "$LOG_FILE"
}

@test "helm_recover_if_stuck on pending-upgrade choice 2 = uninstall" {
  cat >"$STUB_DIR/helm" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"status"*"-o json"*)
    echo '{ "info":{ "status":"pending-upgrade" } }' ;;
  *"uninstall"*) echo "uninstalled" ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/helm"

  ui_prompt() { printf -v "${3:-_}" '%s' '2'; }
  export -f ui_prompt

  run helm_recover_if_stuck default ma
  [ "$status" -eq 0 ]
  grep -q 'helm-uninstall' "$LOG_FILE"
}

@test "helm_recover_if_stuck on pending-upgrade choice 3 = abort, returns non-zero" {
  cat >"$STUB_DIR/helm" <<'EOF'
#!/usr/bin/env bash
echo '{ "info":{ "status":"pending-upgrade" } }'
EOF
  chmod +x "$STUB_DIR/helm"

  ui_prompt() { printf -v "${3:-_}" '%s' '3'; }
  export -f ui_prompt

  run helm_recover_if_stuck default ma
  [ "$status" -ne 0 ]
}

@test "helm_recover_if_stuck on failed offers rollback or uninstall" {
  cat >"$STUB_DIR/helm" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"status"*"-o json"*)
    echo '{ "info":{ "status":"failed" } }' ;;
  *"rollback"*) echo "rolled back" ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/helm"

  ui_prompt() { printf -v "${3:-_}" '%s' '1'; }
  export -f ui_prompt

  run helm_recover_if_stuck default ma
  [ "$status" -eq 0 ]
  grep -q 'helm-rollback' "$LOG_FILE"
}

# ---------- helm_dump_diagnostics ----------

@test "helm_dump_diagnostics writes helm status, pods, and events to log" {
  cat >"$STUB_DIR/helm" <<'EOF'
#!/usr/bin/env bash
echo "FAKE_HELM_STATUS_LINE"
EOF
  chmod +x "$STUB_DIR/helm"
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get pods"*"--no-headers"*) printf 'p1 Running\n' ;;
  *"get pods"*) printf 'NAME READY STATUS\np1 1/1 Running\n' ;;
  *"get events"*) printf 'FAKE_EVENT_LINE\n' ;;
  *"get jobs"*) printf 'j1 1\n' ;;
  *) printf '' ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  run_split helm_dump_diagnostics default ma
  grep -q 'STREAM\[diag-helm\] FAKE_HELM_STATUS_LINE' "$LOG_FILE"
  grep -q 'STREAM\[diag-pods\]' "$LOG_FILE"
  grep -q 'STREAM\[diag-events\]' "$LOG_FILE"
}

@test "helm_dump_diagnostics describes unhealthy pods" {
  cat >"$STUB_DIR/helm" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$STUB_DIR/helm"
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get pods"*"--no-headers"*) printf 'broken-pod Pending\nhealthy-pod Running\n' ;;
  *"describe pod broken-pod"*) printf 'FAKE_DESCRIBE_BROKEN\n' ;;
  *"describe pod healthy-pod"*) printf 'should not see this\n' ;;
  *) printf '' ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  run_split helm_dump_diagnostics default ma
  grep -q 'STREAM\[diag-pod-broken-pod\]' "$LOG_FILE"
  ! grep -q 'STREAM\[diag-pod-healthy-pod\]' "$LOG_FILE"
}

@test "helm_dump_diagnostics describes stuck Jobs (helm hooks)" {
  cat >"$STUB_DIR/helm" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$STUB_DIR/helm"
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get pods"*"--no-headers"*) printf '' ;;
  *"get pods"*)                printf '' ;;
  *"get events"*)              printf '' ;;
  *"get jobs"*)                printf 'default-helm-installer  \nfinished-job 1\n' ;;
  *"describe job default-helm-installer"*) printf 'FAKE_JOB_DESCRIBE\n' ;;
  *) printf '' ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  run_split helm_dump_diagnostics default ma
  grep -q 'STREAM\[diag-job-default-helm-installer\]' "$LOG_FILE"
}
