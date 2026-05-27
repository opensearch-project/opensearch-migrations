#!/usr/bin/env bats
# test_helm_streaming.bats — verify helm install + pod-watch route through
# log_stream / _helm_pods_log so the operator sees output during the
# otherwise-silent `helm --wait` window.

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

# ---------- _helm_pods_log: file + stderr, never stdout ----------

@test "_helm_pods_log writes to stderr only" {
  run_split _helm_pods_log "pods total=3 running=2 pending=1"
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"pods│"* ]]
  [[ "$STDERR" == *"total=3"* ]]
}

@test "_helm_pods_log appends to LOG_FILE with the STREAM[pods] prefix" {
  _helm_pods_log "snapshot at t1"
  grep -q 'STREAM\[pods\] snapshot at t1' "$LOG_FILE"
}

# ---------- helm_install routes through log_stream ----------

@test "helm.sh source contains log_stream invocations for helm + kubectl" {
  # Static check: helm.sh must use log_stream for the long-running ops so
  # operators always get tee'd output. If a future edit reverts to
  # `tee -a` or silent invocation, this test will fail loudly.
  grep -q 'log_stream "helm" helm upgrade' "$PROJECT_ROOT/lib/helm.sh"
  grep -q 'log_stream "kubectl-wait" kubectl wait' "$PROJECT_ROOT/lib/helm.sh"
}

@test "helm.sh tracks the pod-watcher PID for SIGINT cleanup" {
  # The pod watcher is a long-running background; without on_signal_track_pid
  # Ctrl-C wouldn't kill it. Static check the wiring.
  grep -q 'helm_watch_pods "\$HELM_NS"' "$PROJECT_ROOT/lib/helm.sh"
  grep -q 'on_signal_track_pid "\$watch_pid"' "$PROJECT_ROOT/lib/helm.sh"
  grep -q 'on_signal_untrack_pid "\$watch_pid"' "$PROJECT_ROOT/lib/helm.sh"
}

# ---------- helm_watch_pods: integration with stubbed kubectl ----------

@test "helm_watch_pods emits a summary on the first cycle when pods are Running" {
  # Stub kubectl to print 3 pods all running, all ready=true.
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get pods"*)
    cat <<POD
migration-console-0    Running    true
strimzi-operator-1     Running    true
fluent-bit-xyz         Running    true
POD
    ;;
  *) printf '' ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  WATCH_INTERVAL=0.2 helm_watch_pods ma &
  local pid=$!
  sleep 0.5
  kill "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null || true

  grep -qE 'STREAM\[pods\] pods total=3 running=3' "$LOG_FILE"
}

@test "helm_watch_pods reports not_ready when a Running pod has unready container" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get pods"*)
    cat <<POD
migration-console-0    Running    false
strimzi-operator-1     Running    true
POD
    ;;
  *) printf '' ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  WATCH_INTERVAL=0.2 helm_watch_pods ma &
  local pid=$!
  sleep 0.5
  kill "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null || true

  grep -qE 'STREAM\[pods\] .*not_ready=migration-console-0' "$LOG_FILE"
}

@test "helm_watch_pods stays quiet on unchanged snapshots" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get pods"*)
    cat <<POD
foo    Running    true
POD
    ;;
  *) printf '' ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  WATCH_INTERVAL=0.1 helm_watch_pods ma &
  local pid=$!
  sleep 0.6
  kill "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null || true

  # Many sleep cycles, but snapshot is identical. Expect ONE pods log line.
  count=$(grep -c 'STREAM\[pods\] pods total=' "$LOG_FILE" || echo 0)
  [ "$count" = "1" ]
}

@test "helm_watch_pods handles 'no pods yet' state gracefully" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$STUB_DIR/kubectl"

  WATCH_INTERVAL=0.2 helm_watch_pods ma &
  local pid=$!
  sleep 0.5
  kill "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null || true

  grep -q 'waiting for pods in namespace ma to appear' "$LOG_FILE"
}
