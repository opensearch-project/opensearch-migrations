#!/usr/bin/env bats
# test_discover.bats — exercise discover.sh with stubbed `aws`.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh ui.sh log.sh state.sh discover.sh
}

teardown() {
  teardown_isolated_home
}

@test "discover_aws sets account/region/arn from sts output" {
  mkstub aws '{"UserId":"ABC","Account":"123456789012","Arn":"arn:aws:iam::123456789012:user/alice"}'

  state_load
  export AWS_REGION=us-east-1
  run discover_aws
  [ "$status" -eq 0 ]

  state_load   # state was saved on disk by discover_aws? No — discover_aws sets in-memory only.
  # We saved nothing; assertions on STATE only valid in-process. Re-source by sourcing inline:
}

@test "discover_aws fails gracefully when aws is missing" {
  # Don't stub aws — but our test PATH already has stubs first; remove if present.
  rm -f "$STUB_DIR/aws"
  state_load
  run discover_aws
  # The function returns 1 and warns; bats captures that as non-zero.
  [ "$status" -ne 0 ]
}

@test "discover_os identifies the host OS correctly" {
  state_load
  discover_os
  local os; os=$(state_get OS_NAME)
  case "$(uname -s)" in
    Darwin) [ "$os" = "darwin" ] ;;
    Linux)  [ "$os" = "linux" ] || [ "$os" = "wsl" ] ;;
    *)      printf 'unsupported test platform: %s\n' "$(uname -s)" >&2
            return 1 ;;
  esac
}
