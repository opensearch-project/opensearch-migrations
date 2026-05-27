#!/usr/bin/env bats
# test_cfn_dashboard.bats — unit tests for the live CFN dashboard.
#
# Five concerns:
#   1. _cfn_res_upsert + _cfn_res_idx round-trip (parallel-array store)
#   2. _cfn_status_class classifies CFN statuses correctly
#   3. _cfn_fmt_elapsed formats seconds as "Mm SSs"
#   4. _repeat_char produces N copies, including the n=0 edge case
#   5. _cfn_dashboard_render writes to STDERR only and tallies counts right
#   6. _cfn_log_event writes to log file only, never stdout/stderr terminal
#   7. _cfn_dash_emit_class respects the row cap

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh ui.sh log.sh state.sh
  log_init
  load_libs version.sh discover.sh install_tools.sh artifacts.sh wizard.sh \
            cfn.sh
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

# ---------- (1) parallel-array store round-trip ----------

@test "_cfn_res_upsert + _cfn_res_idx insert and update" {
  _cfn_res_upsert "Vpc8378EB38"     "CREATE_IN_PROGRESS" "" "2026-05-26T22:00Z"
  _cfn_res_upsert "AppRegistry"     "CREATE_COMPLETE"    "" "2026-05-26T22:01Z"
  [ "${#_CFN_RES_KEYS[@]}" -eq 2 ]

  # Update existing — must NOT add a row.
  _cfn_res_upsert "Vpc8378EB38" "CREATE_COMPLETE" "" "2026-05-26T22:02Z"
  [ "${#_CFN_RES_KEYS[@]}" -eq 2 ]

  idx=$(_cfn_res_idx "Vpc8378EB38")
  [ "$idx" = "0" ]
  packed="${_CFN_RES_VALS[$idx]}"
  [[ "$packed" == CREATE_COMPLETE* ]]

  idx=$(_cfn_res_idx "AppRegistry")
  [ "$idx" = "1" ]

  idx=$(_cfn_res_idx "NotPresent")
  [ -z "$idx" ]
}

# ---------- (2) status classification ----------

@test "_cfn_status_class classifies CFN states" {
  [ "$(_cfn_status_class CREATE_IN_PROGRESS)"   = "prog" ]
  [ "$(_cfn_status_class UPDATE_IN_PROGRESS)"   = "prog" ]
  [ "$(_cfn_status_class CREATE_COMPLETE)"      = "done" ]
  [ "$(_cfn_status_class UPDATE_COMPLETE)"      = "done" ]
  [ "$(_cfn_status_class CREATE_FAILED)"        = "fail" ]
  [ "$(_cfn_status_class ROLLBACK_IN_PROGRESS)" = "fail" ]
  [ "$(_cfn_status_class UPDATE_ROLLBACK_COMPLETE)" = "fail" ]
  [ "$(_cfn_status_class SOMETHING_NEW)"        = "other" ]
}

# ---------- (3) elapsed formatter ----------

@test "_cfn_fmt_elapsed renders M m SS s" {
  now=$(date +%s)
  out=$(_cfn_fmt_elapsed "$(( now - 75 ))")     # 1 minute 15 seconds
  [[ "$out" == "1m 15s" ]]

  out=$(_cfn_fmt_elapsed "$(( now - 5 ))")
  [[ "$out" == "0m 05s" ]]

  out=$(_cfn_fmt_elapsed "$(( now - 3661 ))")
  [[ "$out" == "61m 01s" ]]
}

# ---------- (4) char repeat including n=0 ----------

@test "_repeat_char repeats N copies of the char" {
  out=$(_repeat_char '#' 5)
  [ "$out" = "#####" ]

  out=$(_repeat_char '█' 3)
  [ "$out" = "███" ]
}

@test "_repeat_char with n=0 emits nothing" {
  out=$(_repeat_char '#' 0)
  [ -z "$out" ]
}

@test "_repeat_char with negative n emits nothing" {
  out=$(_repeat_char '#' -3)
  [ -z "$out" ]
}

# ---------- (5) dashboard render: stderr only, counts right ----------

@test "_cfn_dashboard_render writes only to stderr" {
  _cfn_dashboard_init "MyStack" "us-east-1"
  _cfn_res_upsert "A" "CREATE_COMPLETE"    "" "ts1"
  _cfn_res_upsert "B" "CREATE_IN_PROGRESS" "" "ts2"
  _cfn_res_upsert "C" "CREATE_FAILED"      "Insufficient capacity" "ts3"

  run_split _cfn_dashboard_render "$(date +%s)"
  [ -z "$STDOUT" ]                       # stdout untouched
  [[ "$STDERR" == *"MyStack"* ]]
  [[ "$STDERR" == *"us-east-1"* ]]
  [[ "$STDERR" == *"✓ 1"* ]]              # 1 completed
  [[ "$STDERR" == *"↻ 1"* ]]              # 1 in progress
  [[ "$STDERR" == *"✗ 1"* ]]              # 1 failed
  [[ "$STDERR" == *"total 3"* ]]
  [[ "$STDERR" == *"CREATE_FAILED"* ]]
  [[ "$STDERR" == *"Insufficient capacity"* ]]
  _cfn_dashboard_cursor_restore
}

@test "_cfn_dashboard_render percentage = completed/total" {
  _cfn_dashboard_init "S" "r"
  _cfn_res_upsert "A" "CREATE_COMPLETE"    "" "t"
  _cfn_res_upsert "B" "CREATE_COMPLETE"    "" "t"
  _cfn_res_upsert "C" "CREATE_COMPLETE"    "" "t"
  _cfn_res_upsert "D" "CREATE_IN_PROGRESS" "" "t"

  run_split _cfn_dashboard_render "$(date +%s)"
  # 3/4 = 75%
  [[ "$STDERR" == *"75%"* ]]
  _cfn_dashboard_cursor_restore
}

@test "_cfn_dashboard_render handles empty resource set without crashing" {
  _cfn_dashboard_init "S" "r"
  run_split _cfn_dashboard_render "$(date +%s)"
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"S"* ]]
  [[ "$STDERR" == *"total 0"* ]]
  _cfn_dashboard_cursor_restore
}

# ---------- (6) log-only event writer ----------

@test "_cfn_log_event writes to file only — never to stdout or stderr" {
  run_split _cfn_log_event "ts" "Vpc8378EB38" "CREATE_IN_PROGRESS" ""
  [ -z "$STDOUT" ]
  [ -z "$STDERR" ]
  grep -q 'cfn-event: Vpc8378EB38 CREATE_IN_PROGRESS' "$LOG_FILE"
}

@test "_cfn_log_event includes reason when present" {
  run_split _cfn_log_event "ts" "Foo" "CREATE_FAILED" "out of memory"
  grep -q 'cfn-event: Foo CREATE_FAILED — out of memory' "$LOG_FILE"
}

# ---------- (7) row cap ----------

@test "_cfn_dash_emit_class respects max-rows cap" {
  _cfn_dashboard_init "S" "r"
  # Create 10 in-progress resources but cap at 3.
  local i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    _cfn_res_upsert "R$i" "CREATE_IN_PROGRESS" "" "ts$i"
  done

  run_split _cfn_dash_emit_class prog 10 3
  # The function returns (echoes) the row count on stdout.
  [ "$STDOUT" = "3" ]
  # And prints exactly 3 row lines on stderr.
  rows=$(printf '%s\n' "$STDERR" | grep -c '↻' || true)
  [ "$rows" -eq 3 ]
  _cfn_dashboard_cursor_restore
}

@test "_cfn_dash_emit_class returns 0 when cls_count is 0" {
  _cfn_dashboard_init "S" "r"
  run_split _cfn_dash_emit_class fail 0 5
  [ "$STDOUT" = "0" ]
  [ -z "$STDERR" ]
  _cfn_dashboard_cursor_restore
}
