#!/usr/bin/env bats
# test_helm_watch_orphan.bats — regression guard for the orphan-watcher
# bug: when the operator hit Ctrl-C, helm_watch_pods kept running and
# emitting `pods│ waiting…` to the terminal even after the prompt returned.
#
# Two layers of defense are tested:
#   1. The watcher accepts a parent_pid argument and self-exits when the
#      parent disappears (kill -0 check).
#   2. The watcher's polling cycle is short enough that the orphan window
#      is bounded by WATCH_INTERVAL, not unbounded.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  PROJECT_ROOT="$(cd "$BATS_TEST_DIRNAME/.." && pwd)"
  export PROJECT_ROOT
  load_libs _common.sh ui.sh log.sh state.sh version.sh discover.sh \
            install_tools.sh artifacts.sh wizard.sh cfn.sh crane.sh helm.sh
  log_init

  # Stub kubectl so the watcher's body runs but doesn't make real calls.
  cat >"$STUB_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
# Always emit the "no pods yet" path so the watcher prints a known line.
exit 0
EOF
  chmod +x "$STUB_DIR/kubectl"
}

teardown() {
  teardown_isolated_home
}

# ---------- (1) parent_pid arg → self-exit when parent goes away ----------

@test "helm_watch_pods exits within 1s when the parent_pid is dead" {
  # Spawn a fake "parent" sleep, take its pid, kill it, start the watcher
  # passing that already-dead pid. The watcher's first kill -0 check fails
  # immediately and it exits.
  ( sleep 60 ) &
  local fake_parent=$!
  kill -KILL "$fake_parent" 2>/dev/null
  wait "$fake_parent" 2>/dev/null || true

  # Confirm the parent is dead before we start.
  ! kill -0 "$fake_parent" 2>/dev/null

  WATCH_INTERVAL=0.5 helm_watch_pods ma "$fake_parent" &
  local watch_pid=$!

  # The watcher should exit on its very first iteration.
  local i=0 alive=1
  while (( i < 10 )); do
    sleep 0.1
    if ! kill -0 "$watch_pid" 2>/dev/null; then
      alive=0
      break
    fi
    i=$(( i + 1 ))
  done

  [ "$alive" -eq 0 ]
  wait "$watch_pid" 2>/dev/null || true
}

@test "helm_watch_pods stays running while parent is alive, dies when parent dies" {
  # Start a real parent sleep that lives long enough.
  ( sleep 5 ) &
  local fake_parent=$!

  WATCH_INTERVAL=0.3 helm_watch_pods ma "$fake_parent" &
  local watch_pid=$!

  # After 0.5s the watcher should still be alive (parent's still up).
  sleep 0.5
  kill -0 "$watch_pid" 2>/dev/null

  # Now kill the parent. Watcher should die within ~1s (one polling cycle).
  kill -KILL "$fake_parent" 2>/dev/null
  wait "$fake_parent" 2>/dev/null || true

  local i=0 alive=1
  while (( i < 30 )); do
    sleep 0.1
    if ! kill -0 "$watch_pid" 2>/dev/null; then
      alive=0; break
    fi
    i=$(( i + 1 ))
  done

  [ "$alive" -eq 0 ]
  wait "$watch_pid" 2>/dev/null || true
}

# ---------- (2) bounded orphan window ----------

@test "WATCH_INTERVAL bounds the worst-case orphan window" {
  # If we set WATCH_INTERVAL=0.2, a watcher whose parent died exactly
  # after the kill -0 check should still terminate within 0.2 + epsilon.
  ( sleep 0.1 ) &
  local fake_parent=$!

  WATCH_INTERVAL=0.2 helm_watch_pods ma "$fake_parent" &
  local watch_pid=$!

  # Wait for the parent to exit naturally.
  wait "$fake_parent" 2>/dev/null

  local started ended
  started=$(date +%s)
  local i=0 alive=1
  while (( i < 30 )); do
    sleep 0.1
    if ! kill -0 "$watch_pid" 2>/dev/null; then
      alive=0; break
    fi
    i=$(( i + 1 ))
  done
  ended=$(date +%s)
  local elapsed=$(( ended - started ))

  [ "$alive" -eq 0 ]
  # Should die within 2 seconds of the parent going away.
  [ "$elapsed" -lt 2 ]
  wait "$watch_pid" 2>/dev/null || true
}

# ---------- (3) helm.sh wiring ----------

@test "helm.sh passes the parent_pid argument to helm_watch_pods" {
  # Static check: the install function must invoke `helm_watch_pods <ns> <pid>`.
  # Without the second arg the watcher falls back to PPID, which under
  # bash subshell scoping isn't always the real grandparent.
  grep -E 'helm_watch_pods "\$HELM_NS" "\$our_pid"' "$PROJECT_ROOT/lib/helm.sh"
}

@test "helm_watch_pods has a TERM/HUP exit trap" {
  # Watcher must respond to SIGTERM and SIGHUP so the trap-based cleanup
  # in __on_signal works on the platforms where it does fire.
  grep -E "trap 'exit 0' TERM HUP" "$PROJECT_ROOT/lib/helm.sh"
}
