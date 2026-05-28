#!/usr/bin/env bats
# test_helm_installer_logs.bats — verify the helm-installer Job pod's
# logs are streamed live to the operator + log file via log_stream.
#
# The chart's pre-install Job does the real work; the outer helm cmd is
# silent during that window. Streaming the Job's pod logs is the only way
# the operator sees per-sub-chart progress without manual `kubectl logs`.

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

# ---------- (1) _helm_inst_log discipline ----------

@test "_helm_inst_log writes only to stderr (UI discipline)" {
  out_file=$(mktemp); err_file=$(mktemp)
  ( _helm_inst_log "msg1" ) >"$out_file" 2>"$err_file"
  [ -z "$(cat "$out_file")" ]
  grep -q "installer│ msg1" "$err_file"
  rm -f "$out_file" "$err_file"
}

@test "_helm_inst_log appends STREAM[installer] to the log file" {
  _helm_inst_log "hello world"
  grep -q 'STREAM\[installer\] hello world' "$LOG_FILE"
}

# ---------- (2) parent_pid self-exit ----------

@test "helm_watch_installer_logs exits immediately when parent_pid is dead" {
  ( sleep 30 ) &
  local fake_parent=$!
  kill -KILL "$fake_parent" 2>/dev/null
  wait "$fake_parent" 2>/dev/null || true
  ! kill -0 "$fake_parent" 2>/dev/null

  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "$STUB_DIR/kubectl"

  INSTALLER_LOG_DETECT_TIMEOUT_S=10 INSTALLER_LOG_POLL_S=0.2 \
    helm_watch_installer_logs default ma "$fake_parent" &
  local watch_pid=$!

  local i=0 alive=1
  while (( i < 15 )); do
    sleep 0.2
    if ! kill -0 "$watch_pid" 2>/dev/null; then
      alive=0; break
    fi
    i=$(( i + 1 ))
  done
  [ "$alive" -eq 0 ]
  wait "$watch_pid" 2>/dev/null || true
}

# ---------- (3) detection timeout when no pod appears ----------

@test "helm_watch_installer_logs gives up after detect timeout" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
# get pods returns nothing — simulating a Job whose Pod never schedules.
exit 0
EOF
  chmod +x "$STUB_DIR/kubectl"

  ( sleep 30 ) &
  local fake_parent=$!

  INSTALLER_LOG_DETECT_TIMEOUT_S=1 INSTALLER_LOG_POLL_S=0.2 \
    helm_watch_installer_logs default ma "$fake_parent" &
  local watch_pid=$!

  # Wait up to 3s for the watcher to give up.
  local i=0 alive=1
  while (( i < 30 )); do
    sleep 0.1
    if ! kill -0 "$watch_pid" 2>/dev/null; then
      alive=0; break
    fi
    i=$(( i + 1 ))
  done
  [ "$alive" -eq 0 ]

  kill -KILL "$fake_parent" 2>/dev/null || true
  wait "$fake_parent" 2>/dev/null || true

  grep -q 'no default-helm-installer pod appeared within' "$LOG_FILE"
  wait "$watch_pid" 2>/dev/null || true
}

# ---------- (4) detection happy path → starts following logs ----------

@test "helm_watch_installer_logs tails pod logs once the pod is Running" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get pods"*"selector"*"job-name=default-helm-installer"*)
    printf 'default-helm-installer-abcde Running\n' ;;
  *"logs -f"*)
    # Emit a few lines, then exit (simulate the pod completing).
    printf 'Adding chart repository...\n'
    printf 'Preparing chart: argo-workflows\n'
    printf 'Preparing chart: cert-manager\n'
    sleep 0.1
    exit 0 ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  ( sleep 5 ) &
  local fake_parent=$!

  INSTALLER_LOG_DETECT_TIMEOUT_S=5 INSTALLER_LOG_POLL_S=0.1 \
    helm_watch_installer_logs default ma "$fake_parent" &
  local watch_pid=$!

  # Wait up to 3s for the log following to complete.
  local i=0
  while (( i < 30 )); do
    sleep 0.1
    if ! kill -0 "$watch_pid" 2>/dev/null; then
      break
    fi
    i=$(( i + 1 ))
  done

  kill -KILL "$fake_parent" 2>/dev/null || true
  wait "$fake_parent" 2>/dev/null || true
  wait "$watch_pid" 2>/dev/null || true

  grep -q 'STREAM\[installer\] Adding chart repository' "$LOG_FILE"
  grep -q 'STREAM\[installer\] Preparing chart: argo-workflows' "$LOG_FILE"
  grep -q 'STREAM\[installer\] Preparing chart: cert-manager' "$LOG_FILE"
}

# ---------- (5) static checks: helm.sh wires it up ----------

@test "helm.sh calls helm_watch_installer_logs alongside helm_watch_pods" {
  grep -q 'helm_watch_installer_logs "\$release" "\$HELM_NS" "\$our_pid"' "$PROJECT_ROOT/lib/helm.sh"
}

@test "helm.sh tracks installer_pid for SIGINT cleanup" {
  grep -q 'on_signal_track_pid "\$installer_pid"' "$PROJECT_ROOT/lib/helm.sh"
  grep -q 'on_signal_untrack_pid "\$installer_pid"' "$PROJECT_ROOT/lib/helm.sh"
}

@test "helm.sh kills installer_pid after helm completes" {
  grep -q 'kill "\$installer_pid" 2>/dev/null' "$PROJECT_ROOT/lib/helm.sh"
}
