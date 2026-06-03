#!/usr/bin/env bats
# test_stdout_discipline.bats — enforce the rule:
#
#     UI output (banners, prompts, info/ok/warn/err/step/dim/table) goes to
#     STDERR. Stdout is reserved for return values that callers capture
#     with $(…).
#
# This test class prevents the bug:
#     mode=$(_select_mode "$mode")  # captured "▶ Choose driver\nManual"
# where ui_step leaked to stdout, polluted the captured value, broke the
# downstream `case`, and triggered "error: unknown mode:" with a multiline
# value that confused users.
#
# Strategy:
#   1. Run each ui_* helper and assert: stdout empty, stderr non-empty.
#   2. Run each value-returning function (_select_mode, ui_select,
#      ui_spinner_start, state_get, arch_os, _sha256_of_string, etc.)
#      with all UI text stubbed in, and assert stdout == single clean
#      return value with no embedded escape codes or banner glyphs.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh ui.sh log.sh state.sh
}

teardown() {
  teardown_isolated_home
}

# Helper: run "$@" in a subshell, capture stdout & stderr separately.
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

# ---------- ui helpers must NEVER write to stdout ----------

@test "ui_info writes to stderr only" {
  run_split ui_info "hello world"
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"hello world"* ]]
}

@test "ui_ok writes to stderr only" {
  run_split ui_ok "deployed"
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"deployed"* ]]
}

@test "ui_warn writes to stderr only" {
  run_split ui_warn "watch out"
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"watch out"* ]]
}

@test "ui_err writes to stderr only" {
  run_split ui_err "kaboom"
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"kaboom"* ]]
}

@test "ui_step writes to stderr only" {
  run_split ui_step "Choose driver"
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"Choose driver"* ]]
}

@test "ui_dim writes to stderr only" {
  run_split ui_dim "fine print"
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"fine print"* ]]
}

@test "ui_banner writes to stderr only" {
  run_split ui_banner "OpenSearch Migration Assistant"
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"OpenSearch Migration Assistant"* ]]
}

@test "ui_table writes to stderr only" {
  run_split ui_table "HEAD" "row1" "row2"
  [ -z "$STDOUT" ]
  [[ "$STDERR" == *"HEAD"*"row1"*"row2"* ]]
}

# ---------- value-returning functions return a single clean line ----------

@test "state_get returns only the value (no UI text on stdout)" {
  state_load
  state_set FOO bar
  state_save
  state_load
  run_split state_get FOO
  [ "$STDOUT" = "bar" ]
}

@test "state_get default returns only the default" {
  state_load
  run_split state_get MISSING xyz
  [ "$STDOUT" = "xyz" ]
}

@test "arch_os returns os/arch with no UI text" {
  run_split arch_os
  [[ "$STDOUT" =~ ^(linux|darwin|windows)/(amd64|arm64)$ ]]
}

# ---------- regression: _select_mode returns ONLY Manual or Agent ----------

@test "_select_mode returns just 'Manual' (not '▶ Choose driver\\nManual')" {
  load_libs version.sh discover.sh install_tools.sh artifacts.sh wizard.sh \
            cfn.sh crane.sh helm.sh console.sh agent.sh cleanup.sh resume.sh

  # Bypass interactive ui_prompt by stubbing it. ui_prompt sets the named
  # variable directly, so we wrap it.
  ui_prompt() { printf -v "${3:-_DUMP}" '%s' '1'; }
  export -f ui_prompt

  run_split _select_mode Manual
  [ "$STDOUT" = "Manual" ]

  # Agent option only surfaces when MIGRATE_ENABLE_AGENT=1 (preview gate).
  # Enable for the second half of this test so picking "2" → Agent works.
  ui_prompt() { printf -v "${3:-_DUMP}" '%s' '2'; }
  export -f ui_prompt

  MIGRATE_ENABLE_AGENT=1 run_split _select_mode Manual
  [ "$STDOUT" = "Agent" ]
}

@test "_select_mode does not leak ui_step text to stdout" {
  load_libs version.sh discover.sh install_tools.sh artifacts.sh wizard.sh \
            cfn.sh crane.sh helm.sh console.sh agent.sh cleanup.sh resume.sh
  ui_prompt() { printf -v "${3:-_DUMP}" '%s' '1'; }
  export -f ui_prompt

  run_split _select_mode Manual
  # The exact regression: stdout must NOT contain "Choose driver" or "▶".
  [[ "$STDOUT" != *"Choose driver"* ]]
  [[ "$STDOUT" != *"▶"* ]]
  [[ "$STDOUT" != *$'\n'*$'\n'* ]]   # no embedded blank lines either
}

# ---------- regression: case statement matches captured value ----------

@test "captured _select_mode value matches case Manual without falling through" {
  load_libs version.sh discover.sh install_tools.sh artifacts.sh wizard.sh \
            cfn.sh crane.sh helm.sh console.sh agent.sh cleanup.sh resume.sh
  ui_prompt() { printf -v "${3:-_DUMP}" '%s' '1'; }
  export -f ui_prompt

  mode=$(_select_mode Manual 2>/dev/null)
  case "$mode" in
    Manual) result=ok ;;
    Agent)  result=agent ;;
    *)      result="fallthrough:[$mode]" ;;
  esac
  [ "$result" = "ok" ]
}

# ---------- ui_spinner_start: returns PID on stdout, animation on stderr ----------

@test "ui_spinner_start returns numeric PID on stdout" {
  # In non-tty mode (which BATS provides) the spinner skips the background
  # loop and just prints "msg" + PID 0. Verify stdout is just the PID.
  run_split ui_spinner_start "Working"
  # When stdout isn't a tty, the helper short-circuits and emits "Working "
  # (msg + space) then "0\n". After our move, msg goes to stderr. Stdout is
  # the PID line.
  [[ "$STDOUT" =~ ^[0-9]+$ ]]
}

# ---------- ui_prompt: prompt text NEVER pollutes stdout ----------
#
# Regression test for the bug where ui_prompt fell back to writing the
# prompt text on stdout when /dev/tty was unwritable, causing the captured
# value of `mode=$(_select_mode)` to be "Select [1]: Manual" instead of
# just "Manual".

@test "ui_prompt with VAR_NAME does not leak prompt text on stdout" {
  # Pipe the answer in via stdin; /dev/tty is unwritable in this context
  # because of bats input redirection — exercises the fallback path.
  run_split bash -c '
    source "'"$BATS_TEST_DIRNAME"'/../lib/_common.sh"
    source "'"$BATS_TEST_DIRNAME"'/../lib/ui.sh"
    out=""
    ui_prompt "Pick a number" "1" out <<<"7"
    printf "%s\n" "$out"
  '
  # The test asserts: stdout contains EXACTLY the captured value (7),
  # not the prompt text "Pick a number [1]:".
  [ "$STDOUT" = "7" ]
  [[ "$STDERR" == *"Pick a number"* ]]
}

@test "ui_prompt without VAR_NAME returns just the answer on stdout" {
  run_split bash -c '
    source "'"$BATS_TEST_DIRNAME"'/../lib/_common.sh"
    source "'"$BATS_TEST_DIRNAME"'/../lib/ui.sh"
    ui_prompt "Stage name" "default" <<<"staging"
  '
  [ "$STDOUT" = "staging" ]
  [[ "$STDOUT" != *"Stage name"* ]]
  [[ "$STDOUT" != *"["* ]]
  [[ "$STDOUT" != *"]"* ]]
}

@test "ui_prompt empty input returns the default cleanly" {
  run_split bash -c '
    source "'"$BATS_TEST_DIRNAME"'/../lib/_common.sh"
    source "'"$BATS_TEST_DIRNAME"'/../lib/ui.sh"
    ui_prompt "Stage name" "default" <<<""
  '
  [ "$STDOUT" = "default" ]
}

# ---------- end-to-end: piped "1" yields exactly "Manual" inside resume ----------

@test "piped Mode=1 captured value is exactly 'Manual'" {
  # This is the proximate test for the original bug. We piped "1" into
  # the resume controller and the captured _select_mode return value was
  # "▶ Choose driver\nManual" (or worse, "Select [1]: Manual"), causing
  # the case statement to fall through.

  # Simulate the same pipe behavior: feed "1" on stdin, run _select_mode,
  # capture stdout into a variable, then verify the variable matches
  # exactly "Manual" (no leading newlines, no prompt text).
  load_libs version.sh discover.sh install_tools.sh artifacts.sh wizard.sh \
            cfn.sh crane.sh helm.sh console.sh agent.sh cleanup.sh resume.sh

  result=$(printf '1\n' | _select_mode "" 2>/dev/null)
  [ "$result" = "Manual" ]

  # Agent option is preview-gated; need MIGRATE_ENABLE_AGENT=1 for "2" to
  # mean Agent. Without it, picking "2" is invalid (only Manual visible).
  export MIGRATE_ENABLE_AGENT=1
  result=$(printf '2\n' | _select_mode "" 2>/dev/null)
  [ "$result" = "Agent" ]
  unset MIGRATE_ENABLE_AGENT

  # Empty input → default of 1 → Manual.
  result=$(printf '\n' | _select_mode "" 2>/dev/null)
  [ "$result" = "Manual" ]
}
