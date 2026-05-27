#!/usr/bin/env bats
# test_install_notes_and_pending.bats
#
# Two operator-visibility regressions:
#   1. After the helm-installer Job pod is GC'd, `kubectl logs` 404s.
#      The chart writes a <release>-installation-notes ConfigMap that
#      survives — _helm_dump_install_notes streams it to the operator.
#   2. helm_watch_pods showed `pending=N` without naming the pending
#      pods. Now both `pending=[…]` and `failed=[…]` print the names.

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

# ---------- (1) install-notes streaming ----------

@test "_helm_dump_install_notes streams the ConfigMap's all-notes.txt to log" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get configmap default-installation-notes"*"-o jsonpath"*)
    cat <<NOTES
==== NOTES for argo-workflows ====
Installing chart from Helm repo: argo-workflows
Release "argo-workflows" does not exist. Installing it now.
NAME: argo-workflows
STATUS: deployed
Finished installing chart: argo-workflows
NOTES
    ;;
  *"get configmap default-installation-notes"*) exit 0 ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  ( sleep 5 ) &
  local fake_parent=$!
  INSTALLER_NOTES_TIMEOUT_S=2 INSTALLER_NOTES_POLL_S=0.2 \
    _helm_dump_install_notes default ma "$fake_parent"
  kill -KILL "$fake_parent" 2>/dev/null || true
  wait "$fake_parent" 2>/dev/null || true

  grep -q 'STREAM\[install-notes\] ==== NOTES for argo-workflows' "$LOG_FILE"
  grep -q 'STREAM\[install-notes\] STATUS: deployed' "$LOG_FILE"
  grep -q 'STREAM\[install-notes\] Finished installing chart: argo-workflows' "$LOG_FILE"
}

@test "_helm_dump_install_notes gives up after timeout when ConfigMap never appears" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
  chmod +x "$STUB_DIR/kubectl"

  ( sleep 5 ) &
  local fake_parent=$!
  INSTALLER_NOTES_TIMEOUT_S=1 INSTALLER_NOTES_POLL_S=0.2 \
    _helm_dump_install_notes default ma "$fake_parent"
  kill -KILL "$fake_parent" 2>/dev/null || true
  wait "$fake_parent" 2>/dev/null || true

  grep -q 'no ConfigMap/default-installation-notes appeared within 1s' "$LOG_FILE"
}

@test "_helm_dump_install_notes self-exits when parent dies" {
  ( sleep 30 ) &
  local fake_parent=$!
  kill -KILL "$fake_parent" 2>/dev/null
  wait "$fake_parent" 2>/dev/null || true

  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
  chmod +x "$STUB_DIR/kubectl"

  local started; started=$(date +%s)
  INSTALLER_NOTES_TIMEOUT_S=10 INSTALLER_NOTES_POLL_S=0.5 \
    _helm_dump_install_notes default ma "$fake_parent"
  local ended; ended=$(date +%s)
  local elapsed=$((ended - started))
  # Should have returned essentially immediately, not waited 10s.
  [ "$elapsed" -lt 2 ]
}

@test "_helm_dump_install_notes falls back to describe when all-notes.txt is absent" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get configmap default-installation-notes"*"-o jsonpath"*) printf '' ;;   # empty key
  *"get configmap default-installation-notes"*) exit 0 ;;
  *"describe configmap"*) printf 'CM_DESCRIBE_OUTPUT\n' ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  ( sleep 5 ) &
  local fake_parent=$!
  INSTALLER_NOTES_TIMEOUT_S=2 INSTALLER_NOTES_POLL_S=0.2 \
    _helm_dump_install_notes default ma "$fake_parent"
  kill -KILL "$fake_parent" 2>/dev/null || true
  wait "$fake_parent" 2>/dev/null || true

  grep -q 'has no all-notes.txt key; falling back to describe' "$LOG_FILE"
  grep -q 'STREAM\[install-notes\] CM_DESCRIBE_OUTPUT' "$LOG_FILE"
}

# ---------- (2) Pending pod names ----------

@test "helm_watch_pods reports pending=[names] when pods are Pending" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get pods"*)
    cat <<POD
running-pod    Running    true
pending-pod-1  Pending
pending-pod-2  Pending
POD
    ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  WATCH_INTERVAL=0.2 helm_watch_pods ma &
  local pid=$!
  sleep 1.2
  kill "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null || true

  # Both names must appear, in the same line.
  grep -qE 'STREAM\[pods\] .*pending=\[pending-pod-1,pending-pod-2\]' "$LOG_FILE"
}

@test "helm_watch_pods reports failed=[names] when pods are Failed" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get pods"*)
    cat <<POD
ok-pod         Running   true
broken-pod     Failed
POD
    ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  WATCH_INTERVAL=0.2 helm_watch_pods ma &
  local pid=$!
  sleep 1.2
  kill "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null || true

  grep -qE 'STREAM\[pods\] .*failed=\[broken-pod\]' "$LOG_FILE"
}

@test "helm_watch_pods omits pending=[] when there are no Pending pods" {
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *"get pods"*) printf 'a Running true\n' ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$STUB_DIR/kubectl"

  WATCH_INTERVAL=0.2 helm_watch_pods ma &
  local pid=$!
  sleep 0.5
  kill "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null || true

  ! grep -q 'pending=\[' "$LOG_FILE"
  ! grep -q 'failed=\[' "$LOG_FILE"
}

# ---------- (3) helm.sh wires _helm_dump_install_notes after log follow ----------

@test "helm_watch_installer_logs calls _helm_dump_install_notes after log follow ends" {
  grep -E '_helm_dump_install_notes "\$release" "\$ns" "\$parent_pid"' "$PROJECT_ROOT/lib/helm.sh"
}
