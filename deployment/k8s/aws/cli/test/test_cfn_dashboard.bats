#!/usr/bin/env bats
# test_cfn_dashboard.bats — unit tests for the dashboard module + the
# CFN-domain classifier glue in cfn.sh.
#
# Generic dashboard primitives live in lib/dashboard.sh:
#   1. dash_upsert + internal index round-trip
#   2. dash_render: stderr-only, counts + bar correct, empty case
#   3. _dash_emit_class respects row cap
# CFN-specific:
#   4. _cfn_status_class classifies CFN statuses correctly
#   5. _cfn_log_event writes to log file only

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh ui.sh log.sh state.sh
  log_init
  load_libs version.sh discover.sh install_tools.sh artifacts.sh wizard.sh \
            dashboard.sh cfn.sh
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

@test "dash_upsert inserts and updates without growing rows on update" {
  dash_init t "Test"
  dash_status_classifier t _cfn_status_class
  dash_upsert t "Vpc8378EB38" "CREATE_IN_PROGRESS" "" "ts1"
  dash_upsert t "AppRegistry" "CREATE_COMPLETE"    "" "ts2"
  [ "${#__DASH_t_KEYS[@]}" -eq 2 ]

  # Update existing — must NOT add a row.
  dash_upsert t "Vpc8378EB38" "CREATE_COMPLETE" "" "ts3"
  [ "${#__DASH_t_KEYS[@]}" -eq 2 ]

  # Look up by inspecting the parallel arrays directly. Indexing
  # internals are not part of the public API, but the test verifies
  # the in-place update.
  local i found=""
  for ((i=0; i < ${#__DASH_t_KEYS[@]}; i++)); do
    if [[ "${__DASH_t_KEYS[$i]}" == "Vpc8378EB38" ]]; then
      found="${__DASH_t_VALS[$i]}"
    fi
  done
  [[ "$found" == CREATE_COMPLETE* ]]
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

@test "_dash_fmt_elapsed renders M m SS s" {
  # _dash_fmt_elapsed re-reads `date +%s`, so the test's `now` and the
  # function's `now` can drift by up to a second under load. Match the
  # rendered output against a small tolerance window rather than an exact
  # string so we don't flake when the CI runner pauses between calls.
  _matches_within() {
    local got="$1" expected_min="$2" expected_max="$3"
    local s
    for s in "$expected_min" "$expected_max"; do
      [[ "$got" == "$s" ]] && return 0
    done
    return 1
  }

  now=$(date +%s)
  out=$(_dash_fmt_elapsed "$(( now - 75 ))")     # 1 minute 15 seconds
  _matches_within "$out" "1m 15s" "1m 16s"

  out=$(_dash_fmt_elapsed "$(( now - 5 ))")
  _matches_within "$out" "0m 05s" "0m 06s"

  out=$(_dash_fmt_elapsed "$(( now - 3661 ))")
  _matches_within "$out" "61m 01s" "61m 02s"
}

# ---------- (4) char repeat including n=0 ----------

@test "_dash_repeat_char repeats N copies of the char" {
  out=$(_dash_repeat_char '#' 5)
  [ "$out" = "#####" ]

  out=$(_dash_repeat_char '█' 3)
  [ "$out" = "███" ]
}

@test "_dash_repeat_char with n=0 emits nothing" {
  out=$(_dash_repeat_char '#' 0)
  [ -z "$out" ]
}

@test "_dash_repeat_char with negative n emits nothing" {
  out=$(_dash_repeat_char '#' -3)
  [ -z "$out" ]
}

# ---------- (5) dashboard render: stderr only, counts right ----------

@test "dash_render writes only to stderr" {
  _cfn_dashboard_init "MyStack" "us-east-1"
  dash_upsert cfn "A" "CREATE_COMPLETE"    "" "ts1"
  dash_upsert cfn "B" "CREATE_IN_PROGRESS" "" "ts2"
  dash_upsert cfn "C" "CREATE_FAILED"      "Insufficient capacity" "ts3"

  run_split dash_render cfn "$(date +%s)"
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"MyStack"* ]]
  [[ "$STDERR" == *"us-east-1"* ]]
  [[ "$STDERR" == *"✓ 1"* ]]
  [[ "$STDERR" == *"↻ 1"* ]]
  [[ "$STDERR" == *"✗ 1"* ]]
  [[ "$STDERR" == *"total 3"* ]]
  [[ "$STDERR" == *"CREATE_FAILED"* ]]
  [[ "$STDERR" == *"Insufficient capacity"* ]]
  _dash_cursor_restore
}

@test "dash_render percentage = completed/total" {
  _cfn_dashboard_init "S" "r"
  dash_upsert cfn "A" "CREATE_COMPLETE"    "" "t"
  dash_upsert cfn "B" "CREATE_COMPLETE"    "" "t"
  dash_upsert cfn "C" "CREATE_COMPLETE"    "" "t"
  dash_upsert cfn "D" "CREATE_IN_PROGRESS" "" "t"

  run_split dash_render cfn "$(date +%s)"
  [[ "$STDERR" == *"75%"* ]]
  _dash_cursor_restore
}

@test "dash_render handles empty resource set without crashing" {
  _cfn_dashboard_init "S" "r"
  run_split dash_render cfn "$(date +%s)"
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"S"* ]]
  [[ "$STDERR" == *"total 0"* ]]
  _dash_cursor_restore
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

@test "_dash_emit_class respects max-rows cap" {
  _cfn_dashboard_init "S" "r"
  local i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    dash_upsert cfn "R$i" "CREATE_IN_PROGRESS" "" "ts$i"
  done

  run_split _dash_emit_class cfn prog 10 3 _cfn_status_class
  [ "$STDOUT" = "3" ]
  rows=$(printf '%s\n' "$STDERR" | grep -c '↻' || true)
  [ "$rows" -eq 3 ]
  _dash_cursor_restore
}

@test "_dash_emit_class returns 0 when cls_count is 0" {
  _cfn_dashboard_init "S" "r"
  run_split _dash_emit_class cfn fail 0 5 _cfn_status_class
  [ "$STDOUT" = "0" ]
  [ -z "$STDERR" ]
  _dash_cursor_restore
}
