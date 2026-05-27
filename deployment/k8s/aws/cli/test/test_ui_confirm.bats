#!/usr/bin/env bats
# test_ui_confirm.bats — regression tests for ui_confirm.
#
# Bug history:
#   * "Resume? [Y/n]: <Enter>" returned 1 (no), printing "starting over"
#     when the operator clearly wanted to resume. Cause: a clever two-case
#     statement with an `&&` short-circuit inside a `case` arm that never
#     returned 0 for the default-fallback path.
#
# These tests stub ui_prompt so we can exercise ui_confirm's branching
# without depending on /dev/tty or stdin redirection.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh ui.sh
  # IMPORTANT: redefine ui_prompt AFTER load_libs so our mock wins. Earlier
  # versions defined it at file-top-level, but `load_libs ui.sh` redefines
  # ui_prompt to the real implementation, silently overwriting the mock.
  ui_prompt() {
    local varname="${3:-}"
    local ans="$__MOCK_ANSWER"
    if [[ "$ans" == "<DEFAULT>" ]]; then
      ans="$2"
    fi
    if [[ -n "$varname" ]]; then
      printf -v "$varname" '%s' "$ans"
    else
      printf '%s\n' "$ans"
    fi
  }
}

teardown() {
  teardown_isolated_home
}

# Helper: replace ui_prompt with a stub that returns __MOCK_ANSWER for
# every call. This avoids cross-subshell queue-state issues.
__MOCK_ANSWER=""
ui_prompt() {
  local varname="${3:-}"
  local ans="$__MOCK_ANSWER"
  # If the test set MOCK_ANSWER to literal "<DEFAULT>", propagate the
  # caller-specified default (mimicking ui_prompt's empty-input fallback).
  if [[ "$ans" == "<DEFAULT>" ]]; then
    ans="$2"
  fi
  if [[ -n "$varname" ]]; then
    printf -v "$varname" '%s' "$ans"
  else
    printf '%s\n' "$ans"
  fi
}

queue_answer() { __MOCK_ANSWER="$1"; }
reset_queue()  { __MOCK_ANSWER=""; }

# ---------- empty input → use default ----------

@test "default Y + empty input returns 0 (yes)" {
  # Empty input: ui_prompt's real impl falls back to default; we mimic
  # that by setting the mock to return the same "default" value the real
  # implementation would (the hint string).
  queue_answer "<DEFAULT>"
  run ui_confirm "Resume?" "Y"
  [ "$status" -eq 0 ]
}

@test "default N + empty input returns 1 (no)" {
  queue_answer "<DEFAULT>"
  run ui_confirm "Cleanup?" "N"
  [ "$status" -eq 1 ]
}

# ---------- explicit y/n ----------

@test "default Y + 'y' returns 0" {
  reset_queue; queue_answer "y"
  run ui_confirm "Resume?" "Y"
  [ "$status" -eq 0 ]
}

@test "default N + 'y' returns 0" {
  reset_queue; queue_answer "y"
  run ui_confirm "Cleanup?" "N"
  [ "$status" -eq 0 ]
}

@test "default Y + 'n' returns 1" {
  reset_queue; queue_answer "n"
  run ui_confirm "Resume?" "Y"
  [ "$status" -eq 1 ]
}

@test "default N + 'n' returns 1" {
  reset_queue; queue_answer "n"
  run ui_confirm "Cleanup?" "N"
  [ "$status" -eq 1 ]
}

@test "uppercase 'Y' returns 0" {
  reset_queue; queue_answer "Y"
  run ui_confirm "Test?" "N"
  [ "$status" -eq 0 ]
}

@test "'YES' returns 0" {
  reset_queue; queue_answer "YES"
  run ui_confirm "Test?" "N"
  [ "$status" -eq 0 ]
}

@test "'Yes' returns 0" {
  reset_queue; queue_answer "Yes"
  run ui_confirm "Test?" "N"
  [ "$status" -eq 0 ]
}

@test "'NO' returns 1" {
  reset_queue; queue_answer "NO"
  run ui_confirm "Test?" "Y"
  [ "$status" -eq 1 ]
}

# ---------- the hint-string default-collapse path ----------
#
# When ui_prompt's own fallback set the answer to the prompt hint
# ("Y/n" or "y/N"), ui_confirm must treat that as the default.

@test "hint 'Y/n' returned by ui_prompt is treated as default Y" {
  reset_queue; queue_answer "Y/n"
  run ui_confirm "Resume?" "Y"
  [ "$status" -eq 0 ]
}

@test "hint 'y/N' returned by ui_prompt is treated as default N" {
  reset_queue; queue_answer "y/N"
  run ui_confirm "Cleanup?" "N"
  [ "$status" -eq 1 ]
}

# ---------- unknown input → conservative default (no) ----------

@test "garbage 'maybe' returns 1" {
  reset_queue; queue_answer "maybe"
  run ui_confirm "Test?" "Y"
  [ "$status" -eq 1 ]
}

@test "garbage '42' returns 1" {
  reset_queue; queue_answer "42"
  run ui_confirm "Test?" "Y"
  [ "$status" -eq 1 ]
}

# ---------- stdout discipline (regression) ----------

@test "ui_confirm itself emits nothing on stdout" {
  reset_queue; queue_answer "y"
  run_split() {
    local out err
    out=$(mktemp); err=$(mktemp)
    ui_confirm "Test?" "Y" >"$out" 2>"$err"
    STDOUT=$(cat "$out"); STDERR=$(cat "$err")
    rm -f "$out" "$err"
  }
  run_split
  [ -z "$STDOUT" ]
}
