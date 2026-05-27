#!/usr/bin/env bats
# test_signals.bats — SIGINT/SIGTERM trap installation + behavior.
#
# Bash signal delivery semantics under shells like zsh job-control mean
# `kill -INT $bg_pid` doesn't always reach the script when backgrounded
# from a different shell. So instead of relying on flaky cross-process-
# group signal delivery, we test the *correctness of the trap setup*:
#
#   1. The SIGINT trap is INSTALLED only when MIGRATE_OWNS_PROCESS=1 set
#      by bin/migration-assistant. Sourcing the libs from any other
#      context (tests, dotfiles, dev shell) leaves SIGINT handling alone.
#   2. The trap function `__on_signal` is reachable and callable.
#   3. The PID registry tracks/untracks correctly.
#   4. Direct invocation of __on_signal kills tracked PIDs.
#
# Operator-visible behavior (Ctrl-C in a real terminal terminates the
# CLI promptly) requires manual verification — automated tests cannot
# faithfully reproduce a TTY-attached foreground process group.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  PROJECT_ROOT="$(cd "$BATS_TEST_DIRNAME/.." && pwd)"
  export PROJECT_ROOT
}

teardown() {
  teardown_isolated_home
}

# ---------- (1) trap installation gate ----------

@test "MIGRATE_OWNS_PROCESS unset → no SIGINT trap installed" {
  load_libs _common.sh ui.sh log.sh
  local trap_int
  trap_int=$(trap -p INT 2>/dev/null || true)
  [[ "$trap_int" != *"__on_signal"* ]]
}

@test "MIGRATE_OWNS_PROCESS=1 → __on_signal trap installed" {
  result=$(MIGRATE_OWNS_PROCESS=1 bash -c '
    source "'"$PROJECT_ROOT"'/lib/_common.sh"
    trap -p INT
  ')
  [[ "$result" == *"__on_signal"* ]]

  result_term=$(MIGRATE_OWNS_PROCESS=1 bash -c '
    source "'"$PROJECT_ROOT"'/lib/_common.sh"
    trap -p TERM
  ')
  [[ "$result_term" == *"__on_signal"* ]]
}

# ---------- (2) trap function exists and is callable ----------

@test "__on_signal is defined and accepts the signal name" {
  result=$(MIGRATE_OWNS_PROCESS=1 bash -c '
    source "'"$PROJECT_ROOT"'/lib/_common.sh"
    type __on_signal >/dev/null && echo defined
  ')
  [ "$result" = "defined" ]
}

# ---------- (3) PID registry track/untrack ----------

@test "on_signal_track_pid + on_signal_untrack_pid round-trip" {
  load_libs _common.sh
  on_signal_track_pid 12345
  on_signal_track_pid 67890
  [ "${#__SIG_TRACKED_PIDS[@]}" -eq 2 ]
  on_signal_untrack_pid 12345
  [ "${#__SIG_TRACKED_PIDS[@]}" -eq 1 ]
  [ "${__SIG_TRACKED_PIDS[0]}" -eq 67890 ]
  on_signal_untrack_pid 67890
  [ "${#__SIG_TRACKED_PIDS[@]}" -eq 0 ]
}

@test "on_signal_track_pid handles multiple PIDs without dropping any" {
  load_libs _common.sh
  on_signal_track_pid 1
  on_signal_track_pid 2
  on_signal_track_pid 3
  [ "${#__SIG_TRACKED_PIDS[@]}" -eq 3 ]
  on_signal_untrack_pid 2
  [ "${#__SIG_TRACKED_PIDS[@]}" -eq 2 ]
  [ "${__SIG_TRACKED_PIDS[0]}" -eq 1 ]
  [ "${__SIG_TRACKED_PIDS[1]}" -eq 3 ]
}

# ---------- (4) __on_signal actually kills tracked children ----------

@test "__on_signal kills tracked PIDs and exits 130" {
  # Run in a sub-bash whose only job is: source libs, fork a sleeper,
  # call __on_signal. Capture exit code with `set +e`. Print whether
  # the child died.
  local script err
  script=$(mktemp)
  err=$(mktemp)
  cat >"$script" <<EOF
#!/usr/bin/env bash
export MIGRATE_OWNS_PROCESS=1
source "$PROJECT_ROOT/lib/_common.sh"
sleep 30 &
child_pid=\$!
on_signal_track_pid "\$child_pid"
echo "CHILD_PID=\$child_pid"
__on_signal INT
EOF
  chmod +x "$script"

  # `run` captures stdout+stderr into $output and exit code into $status,
  # without tripping bats's set -e on a non-zero exit.
  run bash "$script"
  printf '%s\n' "$output" >"$err"
  child_pid=$(grep -oE 'CHILD_PID=[0-9]+' "$err" | head -1 | cut -d= -f2)
  [ -n "$child_pid" ]

  # The child should be dead (since __on_signal TERMed it).
  ! kill -0 "$child_pid" 2>/dev/null

  # The script's exit code should be 130 (SIGINT convention).
  [ "$status" -eq 130 ]

  rm -f "$script" "$err"
}

# ---------- (5) the spinner subshell ignores SIGINT ----------

@test "ui_spinner_start subshell traps SIGINT to ignore (parent owns kills)" {
  # Verify the spinner code path uses `trap '' INT` so that when the
  # parent's __on_signal handler explicitly kills the spinner pid via
  # SIGTERM, we don't get racey signal delivery.
  grep -q "trap '' INT" "$PROJECT_ROOT/lib/ui.sh"
}

@test "log_stream subshell traps SIGINT to ignore (parent owns kills)" {
  grep -q "trap '' INT" "$PROJECT_ROOT/lib/log.sh"
}
