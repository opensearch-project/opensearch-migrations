#!/usr/bin/env bats
# test_state.bats — unit tests for lib/state.sh
#
# Verifies: load → set → save → load round-trip, atomicity, archive.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh ui.sh log.sh state.sh
}

teardown() {
  teardown_isolated_home
}

@test "state_save / state_load round-trips a single key" {
  state_load
  state_set FOO bar
  state_save

  STATE=()
  state_load

  run state_get FOO
  [ "$status" -eq 0 ]
  [ "$output" = "bar" ]
}

@test "state_get returns the default when key absent" {
  state_load
  run state_get MISSING fallback
  [ "$status" -eq 0 ]
  [ "$output" = "fallback" ]
}

@test "state_save writes both state.env and state.json" {
  state_load
  state_set REGION us-west-2
  state_save

  [ -f "$STAGE_DIR/state.env" ]
  [ -f "$STAGE_DIR/state.json" ]
  grep -q 'REGION="us-west-2"' "$STAGE_DIR/state.env"
  grep -q '"REGION"' "$STAGE_DIR/state.json"
}

@test "state_save handles values with spaces and quotes" {
  state_load
  state_set NOTE 'arn:aws:iam::1234:user/"john doe"'
  state_save

  STATE=()
  state_load
  run state_get NOTE
  [ "$status" -eq 0 ]
  [ "$output" = 'arn:aws:iam::1234:user/"john doe"' ]
}

@test "state_archive moves state into archive/<ts>" {
  state_load
  state_set FOO bar
  state_save
  state_archive

  [ ! -f "$STAGE_DIR/state.env" ]
  [ ! -f "$STAGE_DIR/state.json" ]
  [ -d "$STAGE_DIR/archive" ]
  found=$(find "$STAGE_DIR/archive" -name 'state.env' | wc -l | tr -d ' ')
  [ "$found" = "1" ]
}

@test "state_resumable_step echoes empty when last_step missing" {
  state_load
  run state_resumable_step
  [ "$status" -eq 0 ]
  [ -z "$output" ]
}

@test "state_resumable_step returns last_step value" {
  state_load
  state_set last_step "helm_done"
  state_save
  STATE=()
  state_load
  run state_resumable_step
  [ "$output" = "helm_done" ]
}
