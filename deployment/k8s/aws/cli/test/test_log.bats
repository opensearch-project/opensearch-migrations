#!/usr/bin/env bats
# test_log.bats — log_announce, log_stream, and CFN event streaming.
#
# Three concerns:
#   * log_announce prints the path on stderr (never stdout)
#   * log_stream writes child output BOTH to file and stderr
#   * _cfn_print_event respects UI discipline (stderr) AND tees to log file

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh ui.sh log.sh state.sh
  log_init
}

teardown() {
  teardown_isolated_home
}

run_split() {
  local out_file err_file
  out_file=$(mktemp)
  err_file=$(mktemp)
  ( "$@" ) >"$out_file" 2>"$err_file"
  local rc=$?
  STDOUT=$(cat "$out_file")
  STDERR=$(cat "$err_file")
  rm -f "$out_file" "$err_file"
  return $rc
}

# ---------- log_announce ----------

@test "log_announce writes the path to stderr only" {
  run_split log_announce
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"$LOG_FILE"* ]]
  [[ "$STDERR" == *"tail -f"* ]]
}

@test "log_announce --exit prints final path line" {
  run_split log_announce --exit
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"Log: $LOG_FILE"* ]]
}

@test "log_announce_exit wrapper does not error" {
  run log_announce_exit 0
  [ "$status" -eq 0 ]
}

@test "log_announce no-ops when LOG_FILE is /dev/null" {
  LOG_FILE=/dev/null
  run_split log_announce
  [ -z "$STDOUT" ]
  [ -z "$STDERR" ]
}

# ---------- log_stream ----------

@test "log_stream tees stdout into the log file" {
  run_split log_stream "test" sh -c 'printf "hello world\n"'
  [ -z "$STDOUT" ]                          # no UI text on stdout
  [[ "$STDERR" == *"hello world"* ]]        # streamed live to operator
  grep -q 'STREAM\[test\] hello world' "$LOG_FILE"
}

@test "log_stream tees stderr into the log file too" {
  run_split log_stream "errchan" sh -c 'printf "boom\n" >&2'
  [[ "$STDERR" == *"boom"* ]]
  grep -q 'STREAM\[errchan\] boom' "$LOG_FILE"
}

@test "log_stream propagates non-zero exit code" {
  run log_stream "bad" sh -c 'echo nope; exit 7'
  [ "$status" -eq 7 ]
  grep -q 'STREAM\[bad\] nope' "$LOG_FILE"
  grep -q 'stream\[bad\] exit=7' "$LOG_FILE"
}

@test "log_stream prefixes each line with the bucket name" {
  run_split log_stream "myop" sh -c 'for i in 1 2 3; do printf "line-%d\n" $i; done'
  [[ "$STDERR" == *"myop"*"line-1"* ]]
  [[ "$STDERR" == *"myop"*"line-2"* ]]
  [[ "$STDERR" == *"myop"*"line-3"* ]]
}

@test "log_stream return value never lands on stdout" {
  # CRITICAL — log_stream is sometimes used inside other functions whose
  # output may be captured. Stdout from log_stream itself MUST be empty.
  run_split log_stream "x" sh -c 'echo a; echo b'
  [ -z "$STDOUT" ]
}

# ---------- _cfn_print_event (UI discipline) ----------

@test "_cfn_print_event writes the event to stderr + log only" {
  load_libs version.sh discover.sh install_tools.sh artifacts.sh wizard.sh \
            cfn.sh
  run_split _cfn_print_event \
    "2026-05-26T22:00:00Z" "MyResource" "CREATE_COMPLETE" "None"
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"MyResource"* ]]
  [[ "$STDERR" == *"CREATE_COMPLETE"* ]]
  grep -q 'cfn-event:.*MyResource.*CREATE_COMPLETE' "$LOG_FILE"
}

@test "_cfn_print_event includes reason when present" {
  load_libs version.sh discover.sh install_tools.sh artifacts.sh wizard.sh \
            cfn.sh
  run_split _cfn_print_event \
    "2026-05-26T22:00:00Z" "MyResource" "CREATE_FAILED" "Insufficient capacity"
  [[ "$STDERR" == *"Insufficient capacity"* ]]
  grep -q 'cfn-event:.*Insufficient capacity' "$LOG_FILE"
}
