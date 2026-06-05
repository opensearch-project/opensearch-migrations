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

# capture_stderr — like run_split but WITHOUT a subshell, so the callee's
# variable mutations (dashboard arrays, __DASH_*_LAST_HEIGHT) persist into
# the test shell. Needed for the sticky-redraw tests that init the panel
# and then render against the same in-memory state.
capture_stderr() {
  local tf; tf=$(mktemp)
  "$@" 2>"$tf"
  STDERR=$(<"$tf")
  rm -f "$tf"
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
#
# ABI v2: classifiers set __DASH_CLS via _dash_set_cls instead of
# printing to stdout. The `_dash_classify <fn> <status>` wrapper is
# what dashboard.sh calls; tests use it the same way.

@test "_cfn_status_class classifies CFN states" {
  _dash_classify _cfn_status_class CREATE_IN_PROGRESS;        [ "$__DASH_CLS" = "prog"  ]
  _dash_classify _cfn_status_class UPDATE_IN_PROGRESS;        [ "$__DASH_CLS" = "prog"  ]
  _dash_classify _cfn_status_class CREATE_COMPLETE;           [ "$__DASH_CLS" = "done"  ]
  _dash_classify _cfn_status_class UPDATE_COMPLETE;           [ "$__DASH_CLS" = "done"  ]
  _dash_classify _cfn_status_class CREATE_FAILED;             [ "$__DASH_CLS" = "fail"  ]
  _dash_classify _cfn_status_class ROLLBACK_IN_PROGRESS;      [ "$__DASH_CLS" = "fail"  ]
  _dash_classify _cfn_status_class UPDATE_ROLLBACK_COMPLETE;  [ "$__DASH_CLS" = "fail"  ]
  _dash_classify _cfn_status_class SOMETHING_NEW;             [ "$__DASH_CLS" = "other" ]
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
  term_show_cursor
}

@test "dash_render percentage = completed/total" {
  _cfn_dashboard_init "S" "r"
  dash_upsert cfn "A" "CREATE_COMPLETE"    "" "t"
  dash_upsert cfn "B" "CREATE_COMPLETE"    "" "t"
  dash_upsert cfn "C" "CREATE_COMPLETE"    "" "t"
  dash_upsert cfn "D" "CREATE_IN_PROGRESS" "" "t"

  run_split dash_render cfn "$(date +%s)"
  [[ "$STDERR" == *"75%"* ]]
  term_show_cursor
}

@test "dash_render handles empty resource set without crashing" {
  _cfn_dashboard_init "S" "r"
  run_split dash_render cfn "$(date +%s)"
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"S"* ]]
  [[ "$STDERR" == *"total 0"* ]]
  term_show_cursor
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
  term_show_cursor
}

@test "_dash_emit_class returns 0 when cls_count is 0" {
  _cfn_dashboard_init "S" "r"
  run_split _dash_emit_class cfn fail 0 5 _cfn_status_class
  [ "$STDOUT" = "0" ]
  [ -z "$STDERR" ]
  term_show_cursor
}

# ---------- (8) sticky-redraw correctness: no wrapped lines ----------
#
# The dashboard redraws in place by moving the cursor up by the LOGICAL
# line count it last printed. If any emitted line is wider than the
# terminal it wraps onto extra PHYSICAL rows, the cursor-up under-counts,
# the erase clears only part of the old frame, and every poll leaves a
# ghost copy behind (the "repeating block" bug). The fix: dash_init
# disables autowrap, and dash_render clips every line to the terminal
# width so each logical line occupies exactly one physical row.

@test "dash_init disables autowrap; dash_finish restores it" {
  __TERM_INTERACTIVE=1
  capture_stderr _cfn_dashboard_init "MyStack" "us-east-1"
  [[ "$STDERR" == *$'\e[?7l'* ]]      # DECAWM reset emitted on init
  [ "$__TERM_WRAP_DISABLED" -eq 1 ]

  capture_stderr dash_finish cfn "$(date +%s)"
  [[ "$STDERR" == *$'\e[?7h'* ]]      # autowrap restored on finish
  [ "$__TERM_WRAP_DISABLED" -eq 0 ]
  __TERM_INTERACTIVE=0
}

@test "dash_render clips every emitted line to the terminal width" {
  __TERM_INTERACTIVE=1
  __TERM_COLUMNS=80
  _cfn_dashboard_init "MyStack" "us-east-1"
  # A failed resource whose reason is the giant multi-resource rollback
  # string from a real deploy — ~300 chars, far wider than 80 columns.
  local huge="The following resource(s) failed to create: [EKSInfraMigrationsPodIdentityPolicy66E9CD0D, VpcPublicSubnet2Subnet691E08A3, VpcPublicSubnet2EIP3C605A87, SnapshotRoleDefaultPolicyBDD7C46A, VpcPublicSubnet2RouteTable94F7E489]. Rollback requested by user."
  dash_upsert cfn "MigrationAssistant-repro" "ROLLBACK_IN_PROGRESS" "$huge" "ts1"
  dash_upsert cfn "Vpc8378EB38" "CREATE_IN_PROGRESS" "" "ts2"

  run_split dash_render cfn "$(date +%s)"
  [ -z "$STDOUT" ]
  # Every physical line (split on newline, with SGR escapes stripped) must
  # fit the terminal width. If any exceeds it, autowrap would wrap it and
  # break the redraw count.
  local line stripped
  while IFS= read -r line; do
    stripped=$(printf '%s' "$line" | sed $'s/\x1b\\[[0-9;?]*[a-zA-Z]//g')
    [ "${#stripped}" -le "$__TERM_COLUMNS" ] || {
      echo "line exceeds ${__TERM_COLUMNS} cols (${#stripped}): $stripped" >&2
      return 1
    }
  done <<<"$STDERR"
  # The clipped failed-row still shows the logical id + an ellipsis.
  [[ "$STDERR" == *"MigrationAssistant-repro"* ]]
  [[ "$STDERR" == *"…"* ]]
  term_show_cursor; term_wrap_on
  __TERM_INTERACTIVE=0
}

@test "dash_render redraw count == physical lines emitted (LAST_HEIGHT)" {
  # The redraw moves the cursor up __DASH_cfn_LAST_HEIGHT lines. With every
  # line clipped to one physical row, that count must equal the number of
  # newline-terminated lines dash_render actually wrote to stderr — proven
  # by counting them and comparing.
  __TERM_INTERACTIVE=1
  __TERM_COLUMNS=80
  _cfn_dashboard_init "S" "us-east-1"
  dash_upsert cfn "A" "CREATE_FAILED" "$(printf 'x%.0s' $(seq 1 250))" "t1"
  dash_upsert cfn "B" "CREATE_IN_PROGRESS" "" "t2"
  dash_upsert cfn "C" "CREATE_COMPLETE" "" "t3"

  # capture_stderr (no subshell) so __DASH_cfn_LAST_HEIGHT survives the call.
  capture_stderr dash_render cfn "$(date +%s)"
  local emitted
  emitted=$(printf '%s\n' "$STDERR" | grep -c '')   # count physical lines
  [ "$__DASH_cfn_LAST_HEIGHT" -eq "$emitted" ]
  term_show_cursor; term_wrap_on
  __TERM_INTERACTIVE=0
}

# ---------- (9) _dash_clip ----------
#
# Fork-free ABI: _dash_clip writes its result into the shared __DASH_CLIP
# variable (like _dash_set_cls), so the render path never subshells. Tests
# read __DASH_CLIP rather than command-substituting.

@test "_dash_clip truncates with an ellipsis past the column budget" {
  __TERM_INTERACTIVE=1
  _dash_clip "abcdefghij" 5
  [ "$__DASH_CLIP" = "abcd…" ]
  [ "${#__DASH_CLIP}" -eq 5 ]
}

@test "_dash_clip leaves short strings untouched" {
  __TERM_INTERACTIVE=1
  _dash_clip "abc" 80
  [ "$__DASH_CLIP" = "abc" ]
}

@test "_dash_clip is a no-op when not interactive (CI keeps full text)" {
  __TERM_INTERACTIVE=0
  _dash_clip "this is a very long line that exceeds the tiny budget" 5
  [ "$__DASH_CLIP" = "this is a very long line that exceeds the tiny budget" ]
}

@test "_dash_clip is a no-op when cols is zero or empty" {
  __TERM_INTERACTIVE=1
  _dash_clip "untouched" 0
  [ "$__DASH_CLIP" = "untouched" ]
  _dash_clip "untouched" ""
  [ "$__DASH_CLIP" = "untouched" ]
}
