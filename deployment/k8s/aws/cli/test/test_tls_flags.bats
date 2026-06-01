#!/usr/bin/env bats
# test_tls_flags.bats — _helm_tls_flags translates --tls-mode / --pca-arn
# into helm --set arguments matching the legacy aws-bootstrap.sh contract.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh ui.sh log.sh state.sh version.sh discover.sh \
            install_tools.sh artifacts.sh wizard.sh dashboard.sh cfn.sh \
            crane.sh helm.sh
  log_init
  state_set AWS_REGION us-east-1
}

teardown() { teardown_isolated_home; }

@test "tls mode unset → no flags" {
  local out=()
  _helm_tls_flags out
  [ "${#out[@]}" -eq 0 ]
}

@test "tls mode 'none' → no flags" {
  state_set TLS_MODE none
  local out=()
  _helm_tls_flags out
  [ "${#out[@]}" -eq 0 ]
}

@test "tls mode 'self-signed' → no flags (chart default)" {
  state_set TLS_MODE self-signed
  local out=()
  _helm_tls_flags out
  [ "${#out[@]}" -eq 0 ]
}

@test "tls mode 'pca-existing' requires --pca-arn (dies without)" {
  state_set TLS_MODE pca-existing
  local out=()
  run _helm_tls_flags out
  [ "$status" -ne 0 ]
  [[ "$output" == *"requires --pca-arn"* ]]
}

@test "tls mode 'pca-existing' with --pca-arn emits 3 --set flags" {
  state_set TLS_MODE pca-existing
  state_set PCA_ARN "arn:aws:acm-pca:us-east-1:111122223333:certificate-authority/abcd"
  local out=()
  _helm_tls_flags out
  # Expect: aws-privateca-issuer=true, awsPrivateCA.arn=ARN, awsPrivateCA.region=REGION
  [ "${#out[@]}" -eq 6 ]   # 3 pairs of --set + KEY=VAL
  printf '%s\n' "${out[@]}" | grep -q 'aws-privateca-issuer=true'
  printf '%s\n' "${out[@]}" | grep -q 'awsPrivateCA.arn=arn:aws:acm-pca'
  printf '%s\n' "${out[@]}" | grep -q 'awsPrivateCA.region=us-east-1'
}

@test "tls mode 'pca-create' enables both controllers + create=true" {
  state_set TLS_MODE pca-create
  local out=()
  _helm_tls_flags out
  printf '%s\n' "${out[@]}" | grep -q 'aws-privateca-issuer=true'
  printf '%s\n' "${out[@]}" | grep -q 'ack-acmpca-controller=true'
  printf '%s\n' "${out[@]}" | grep -q 'awsPrivateCA.create=true'
  printf '%s\n' "${out[@]}" | grep -q 'awsPrivateCA.region=us-east-1'
}

@test "unknown tls mode dies with actionable message" {
  state_set TLS_MODE bogus
  local out=()
  run _helm_tls_flags out
  [ "$status" -ne 0 ]
  [[ "$output" == *"unknown --tls-mode"* ]]
  [[ "$output" == *"none, self-signed, pca-existing, pca-create"* ]]
}
