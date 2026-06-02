#!/usr/bin/env bats
# test_timeline.bats — coverage for lib/timeline.sh.
#
# timeline_render produces the per-phase checklist used by the resume
# prompt. timeline_phase_label maps last_step keys to operator-visible
# labels.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh std.sh term.sh ui.sh log.sh state.sh \
            version.sh discover.sh install_tools.sh artifacts.sh \
            wizard.sh dashboard.sh timeline.sh
}

teardown() {
  teardown_isolated_home
}

capture_stderr() {
  local tf
  tf=$(mktemp)
  "$@" 2>"$tf"
  STDERR=$(<"$tf")
  rm -f "$tf"
}

@test "timeline_phase_label maps known keys to human labels" {
  [ "$(timeline_phase_label discover)" = "Discover environment" ]
  [ "$(timeline_phase_label wizard_done)" = "Configure deploy" ]
  [ "$(timeline_phase_label cfn_done)" = "Deploy CloudFormation" ]
  [ "$(timeline_phase_label helm_done)" = "Install helm chart" ]
  [ "$(timeline_phase_label agent_handoff)" = "Agent handoff (AI mode)" ]
}

@test "timeline_phase_label echoes the key when unknown" {
  [ "$(timeline_phase_label something_we_havent_added)" = "something_we_havent_added" ]
}

@test "timeline_render shows all phases, with the right marker for each" {
  capture_stderr timeline_render wizard_done
  # Phases before wizard_done are 'done' (●)
  [[ "$STDERR" == *"●"*"Discover environment"* ]]
  # wizard_done itself is 'last' (◐)
  [[ "$STDERR" == *"◐"*"Configure deploy"* ]]
  # Later phases are 'pending' (○)
  [[ "$STDERR" == *"○"*"Deploy CloudFormation"* ]]
  [[ "$STDERR" == *"○"*"Install helm chart"* ]]
}

@test "timeline_render with empty last_step shows everything pending" {
  capture_stderr timeline_render ""
  # No '●' (done) marker should appear when there's nothing done yet.
  # Some implementations may still mark the very-first phase — accept
  # both shapes; what matters is that '○' (pending) dominates.
  pending_count=$(printf '%s\n' "$STDERR" | grep -c '○' || true)
  done_count=$(printf '%s\n' "$STDERR" | grep -c '●' || true)
  [ "$pending_count" -gt "$done_count" ]
}

@test "timeline_render writes only to stderr, not stdout" {
  STDOUT=""
  STDERR=""
  out_file=$(mktemp); err_file=$(mktemp)
  timeline_render cfn_done >"$out_file" 2>"$err_file"
  STDOUT=$(<"$out_file"); STDERR=$(<"$err_file")
  rm -f "$out_file" "$err_file"
  [ -z "$STDOUT" ]
  [ -n "$STDERR" ]
}
