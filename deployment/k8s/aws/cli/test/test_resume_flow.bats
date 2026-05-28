#!/usr/bin/env bats
# test_resume_flow.bats — end-to-end smoke for the resume controller.
#
# This is the test that would have caught the original bug. We invoke
# the real bin/migration-assistant binary with stubbed external commands
# and a piped sequence of answers, and assert the run progresses past
# the "Choose driver" prompt without hitting the "unknown mode" path.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  PROJECT_ROOT="$(cd "$BATS_TEST_DIRNAME/.." && pwd)"
  export PROJECT_ROOT
  export CLI_BIN="$PROJECT_ROOT/bin/migration-assistant"

  # If bin/migration-assistant isn't there, every test in this file would
  # otherwise fail with rc=127 ("command not found") and zero output —
  # impossible to diagnose from CI logs. Surface the actual problem.
  if [[ ! -x "$CLI_BIN" ]]; then
    if [[ -f "$CLI_BIN" ]]; then
      printf 'CLI exists but is not executable: %s (mode=%s)\n' \
        "$CLI_BIN" "$(stat -c '%a' "$CLI_BIN" 2>/dev/null || stat -f '%Lp' "$CLI_BIN")" >&2
      printf 'fix: chmod +x %s\n' "$CLI_BIN" >&2
    else
      printf 'CLI not found at %s\n' "$CLI_BIN" >&2
      printf 'is the file tracked by git? (\`%s/.gitignore\` has \`**/bin/\` at repo root)\n' \
        "$PROJECT_ROOT" >&2
    fi
    return 127
  fi

  # No real AWS; stub the external commands the manual_path will reach.
  # NOTE: jq is required by the CLI's state layer. Don't stub jq here —
  # the test environment must have a real jq on PATH.
  mkstub aws ''
  mkstub kubectl ''
  mkstub helm ''
  mkstub crane ''
  mkstub curl ''

  if ! command -v jq >/dev/null 2>&1; then
    printf 'jq not on PATH; bats CI must install jq before running these tests\n' >&2
    return 127
  fi

  # Disable any TTY-based behavior so the run is deterministic.
  export MIGRATE_VERBOSE=0
}

teardown() {
  teardown_isolated_home
}

# Stage 1 — choose Manual via piped stdin. If _select_mode pollutes its
# stdout, the controller fails with "error: unknown mode" and rc != 0.
# _diag <message> — emit a labelled block of $output + $status to bats's
# fd-3 (visible in CI logs) so a failing assertion explains itself.
_diag() {
  printf '\n--- %s ---\n' "$1" >&3
  printf '  status=%s\n' "${status:-(unset)}" >&3
  printf '  output:\n%s\n' "${output:-(empty)}" | sed 's/^/    /' >&3
  printf '--- end ---\n' >&3
}

@test "default run forces Manual mode (no driver prompt)" {
  # The Agent path is gated behind MIGRATE_ENABLE_AGENT=1 until the
  # agent UX is production-ready. With the gate off (default), the
  # CLI must NOT show the "Choose driver" prompt at all.
  run bash -c "timeout 8 '$CLI_BIN' resume 2>&1"

  if [[ "$output" == *"unknown mode"* ]]; then
    _diag "default run: 'unknown mode' bug regression"
    return 1
  fi
  if [[ "$output" != *"OpenSearch Migration Assistant"* ]]; then
    _diag "default run: banner missing — CLI likely failed to boot"
    return 1
  fi
  if [[ "$output" == *"Choose driver"* ]]; then
    _diag "default run: 'Choose driver' prompt should be suppressed when MIGRATE_ENABLE_AGENT is unset"
    return 1
  fi
}

@test "MIGRATE_ENABLE_AGENT=1 + Mode=Manual progresses past mode-select" {
  # Send "1" (Manual). Subsequent prompts read empty (no TTY) and use
  # defaults until the flow reaches a real-AWS / real-helm call.
  run bash -c "printf '1\n' | MIGRATE_ENABLE_AGENT=1 timeout 8 '$CLI_BIN' resume 2>&1"

  if [[ "$output" == *"unknown mode"* ]]; then
    _diag "Mode=Manual: 'unknown mode' bug regression"
    return 1
  fi
  if [[ "$output" != *"Choose driver"* ]]; then
    _diag "Mode=Manual: 'Choose driver' prompt missing when gate is on"
    return 1
  fi
}

@test "MIGRATE_ENABLE_AGENT=1 + Mode=Agent progresses past mode-select" {
  run bash -c "printf '2\n' | MIGRATE_ENABLE_AGENT=1 timeout 8 '$CLI_BIN' resume 2>&1"

  if [[ "$output" == *"unknown mode"* ]]; then
    _diag "Mode=Agent: 'unknown mode' bug regression"
    return 1
  fi
  if [[ "$output" != *"OpenSearch Migration Assistant"* ]]; then
    _diag "Mode=Agent: banner missing"
    return 1
  fi
}

@test "--mode Agent without gate is rejected" {
  run bash -c "timeout 4 '$CLI_BIN' resume --mode Agent 2>&1"
  if [[ "$output" != *"requires MIGRATE_ENABLE_AGENT=1"* ]]; then
    _diag "--mode Agent: gate-required message missing"
    return 1
  fi
}

@test "migration-assistant version prints clean stdout" {
  run "$CLI_BIN" version
  if [[ "$status" -ne 0 ]]; then _diag "version: non-zero exit"; return 1; fi
  if [[ -z "$output" ]]; then _diag "version: empty output"; return 1; fi
  # Match the project's CLI_VERSION defined in lib/version.sh.
  if [[ ! "$output" =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]]; then
    _diag "version: expected x.y.z, got"
    return 1
  fi
}

@test "migration-assistant help prints help to stdout (cmd_help is intentionally stdout)" {
  run "$CLI_BIN" help
  if [[ "$status" -ne 0 ]]; then _diag "help: non-zero exit"; return 1; fi
  if [[ "$output" != *"OpenSearch Migration Assistant CLI"* ]]; then
    _diag "help: banner missing"
    return 1
  fi
  if [[ "$output" != *"--switch"* ]]; then
    _diag "help: --switch flag missing from help text"
    return 1
  fi
}

@test "migration-assistant unknown-command exits 64" {
  run "$CLI_BIN" notacommand
  if [[ "$status" -ne 64 ]]; then
    _diag "unknown-command: expected exit 64"
    return 1
  fi
}

@test "migration-assistant cleanup with empty state exits 0 with friendly warning" {
  run "$CLI_BIN" cleanup
  if [[ "$status" -ne 0 ]]; then _diag "cleanup: non-zero exit"; return 1; fi
  if [[ "$output" != *"nothing to clean up"* ]]; then
    _diag "cleanup: friendly-warning text missing"
    return 1
  fi
}

# Regression for: cmd_resume was state_save'ing BEFORE state_load,
# wiping last_step from state.env every run. Result: the "Resume from
# this point?" prompt never appeared and the wizard re-asked every
# question even though state.env had everything pre-populated.
@test "stale state with last_step shows resume prompt on rerun" {
  mkdir -p "$STAGE_DIR"
  cat >"$STAGE_DIR/state.env" <<EOF
MODE="Manual"
STAGE_NAME="ma"
MIRROR_IMAGES="Y"
MA_VERSION="3.2.1"
last_step="wizard_done"
EOF
  printf '{
    "MODE":"Manual","STAGE_NAME":"ma","MIRROR_IMAGES":"Y",
    "MA_VERSION":"3.2.1","last_step":"wizard_done"
  }' >"$STAGE_DIR/state.json"

  # Reply "n" to the resume prompt so the run terminates quickly without
  # diving into discovery. We just need to see the prompt.
  run bash -c "printf 'n\n' | timeout 8 '$CLI_BIN' resume 2>&1"

  if [[ "$output" != *"previous run progressed to: wizard_done"* ]]; then
    _diag "resume: did not show 'previous run progressed to' prompt — state was wiped"
    return 1
  fi
  if [[ "$output" != *"Resume from this point?"* ]]; then
    _diag "resume: did not show resume confirmation prompt"
    return 1
  fi
}
