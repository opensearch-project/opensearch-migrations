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

@test "interactive Mode=Manual progresses past mode-select" {
  # Send "1" (Manual). Subsequent prompts read empty (no TTY) and use
  # defaults until the flow reaches a real-AWS / real-helm call. We cap
  # the run to 8 seconds — long enough to exit the wizard, short enough
  # to never hang the suite.
  run bash -c "printf '1\n' | timeout 8 '$CLI_BIN' resume 2>&1"

  if [[ "$output" == *"unknown mode"* ]]; then
    _diag "Mode=Manual: 'unknown mode' bug regression"
    return 1
  fi
  if [[ "$output" != *"OpenSearch Migration Assistant"* ]]; then
    _diag "Mode=Manual: banner missing — CLI likely failed to boot"
    return 1
  fi
}

@test "interactive Mode=Agent progresses past mode-select" {
  run bash -c "printf '2\n' | timeout 8 '$CLI_BIN' resume 2>&1"

  if [[ "$output" == *"unknown mode"* ]]; then
    _diag "Mode=Agent: 'unknown mode' bug regression"
    return 1
  fi
  if [[ "$output" != *"OpenSearch Migration Assistant"* ]]; then
    _diag "Mode=Agent: banner missing"
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
