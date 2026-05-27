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

  # No real AWS; stub the external commands the manual_path will reach.
  mkstub aws ''
  mkstub kubectl ''
  mkstub helm ''
  mkstub crane ''
  mkstub jq '{}'
  mkstub curl ''

  # Disable any TTY-based behavior so the run is deterministic.
  export MIGRATE_VERBOSE=0
}

teardown() {
  teardown_isolated_home
}

# Stage 1 — choose Manual via piped stdin. If _select_mode pollutes its
# stdout, the controller fails with "error: unknown mode" and rc != 0.
@test "interactive Mode=Manual progresses past mode-select" {
  cd "$PROJECT_ROOT"

  # Send "1" (Manual). Subsequent prompts read empty (no TTY) and use
  # defaults until the flow reaches a real-AWS / real-helm call. We cap
  # the run to 8 seconds — long enough to exit the wizard, short enough
  # to never hang the suite.
  run bash -c '
    HOME="$HOME"
    MIGRATE_HOME="$MIGRATE_HOME"
    STAGE="$STAGE"
    timeout 8 bash -c "printf \"1\n\" | \"$PROJECT_ROOT/bin/migration-assistant\" resume" 2>&1
  '

  [[ "$output" != *"unknown mode"* ]]
  [[ "$output" == *"OpenSearch Migration Assistant"* ]]
}

@test "interactive Mode=Agent progresses past mode-select" {
  cd "$PROJECT_ROOT"

  run bash -c '
    HOME="$HOME"
    MIGRATE_HOME="$MIGRATE_HOME"
    STAGE="$STAGE"
    timeout 8 bash -c "printf \"2\n\" | \"$PROJECT_ROOT/bin/migration-assistant\" resume" 2>&1
  '

  [[ "$output" != *"unknown mode"* ]]
  [[ "$output" == *"OpenSearch Migration Assistant"* ]]
}

@test "migration-assistant version prints clean stdout" {
  cd "$PROJECT_ROOT"
  run bash -c '"$PROJECT_ROOT/bin/migration-assistant" version'
  [ "$status" -eq 0 ]
  [ "$output" = "0.1.0" ]
}

@test "migration-assistant help prints help to stdout (cmd_help is intentionally stdout)" {
  cd "$PROJECT_ROOT"
  run bash -c '"$PROJECT_ROOT/bin/migration-assistant" help'
  [ "$status" -eq 0 ]
  [[ "$output" == *"OpenSearch Migration Assistant CLI"* ]]
  [[ "$output" == *"--switch"* ]]
}

@test "migration-assistant unknown-command exits 64" {
  cd "$PROJECT_ROOT"
  run bash -c '"$PROJECT_ROOT/bin/migration-assistant" notacommand 2>&1'
  [ "$status" -eq 64 ]
}

@test "migration-assistant cleanup with empty state exits 0 with friendly warning" {
  cd "$PROJECT_ROOT"
  run bash -c '
    "$PROJECT_ROOT/bin/migration-assistant" cleanup 2>&1
  '
  [ "$status" -eq 0 ]
  [[ "$output" == *"nothing to clean up"* ]]
}
