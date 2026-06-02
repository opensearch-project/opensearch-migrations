#!/usr/bin/env bats
# test_cleanup_panel.bats — verify cmd_cleanup uses term_panel for
# destructive confirmation instead of the previous flat ui_warn.
#
# The panel surfaces what's about to be deleted (CFN stack, region, helm
# release, local state) inside a UTF-8 box so the operator sees the
# blast radius before they confirm. Regression for "[y/N] autopilot
# accidentally tore down prod".

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh std.sh term.sh ui.sh log.sh state.sh \
            version.sh discover.sh install_tools.sh artifacts.sh \
            wizard.sh dashboard.sh cfn.sh crane.sh build.sh helm.sh \
            console.sh agent.sh cleanup.sh
  # Force interactive so term_panel emits the box rather than plain text.
  __TERM_INTERACTIVE=1
  __TERM_LINES=24
  __TERM_COLUMNS=80
  __TERM_C_RESET=$'\e[0m'
  __TERM_C_BOLD=$'\e[1m'
  __TERM_C_DIM=$'\e[2m'

  # Stub everything cmd_cleanup might invoke.
  mkstub aws ''
  mkstub kubectl ''
  mkstub helm ''
}

teardown() {
  teardown_isolated_home
}

@test "cmd_cleanup --non-interactive shows the panel before deleting" {
  state_load
  state_set CFN_STACK_NAME "MigrationAssistant-prod"
  state_set HELM_RELEASE   "ma"
  state_set AWS_REGION     "us-east-1"
  state_save

  out_file=$(mktemp); err_file=$(mktemp)
  cmd_cleanup --non-interactive >"$out_file" 2>"$err_file" || true
  STDERR=$(<"$err_file")
  rm -f "$out_file" "$err_file"

  # UTF-8 box characters.
  [[ "$STDERR" == *"┏"* ]]
  [[ "$STDERR" == *"┃"* ]]
  [[ "$STDERR" == *"┗"* ]]
  # Panel content reflects state.
  [[ "$STDERR" == *"Cleanup will remove"* ]]
  [[ "$STDERR" == *"MigrationAssistant-prod"* ]]
  [[ "$STDERR" == *"us-east-1"* ]]
  [[ "$STDERR" == *"Helm release"* ]]
  [[ "$STDERR" == *"Local state"* ]]
}

@test "cmd_cleanup with empty state still says 'nothing to clean up'" {
  state_load
  out_file=$(mktemp); err_file=$(mktemp)
  cmd_cleanup --non-interactive >"$out_file" 2>"$err_file"
  STDERR=$(<"$err_file")
  rm -f "$out_file" "$err_file"

  [[ "$STDERR" == *"nothing to clean up"* ]]
}
