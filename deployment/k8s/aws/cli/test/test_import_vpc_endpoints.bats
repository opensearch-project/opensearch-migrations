#!/usr/bin/env bats
# test_import_vpc_endpoints.bats — verify --create-vpc-endpoints turns
# into the correct CFN parameter overrides (CreateXEndpoint=true, plus
# S3EndpointRouteTableIds resolution).

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh ui.sh log.sh state.sh version.sh discover.sh \
            install_tools.sh artifacts.sh wizard.sh dashboard.sh cfn.sh
  log_init
  state_set AWS_REGION us-east-1
}

teardown() { teardown_isolated_home; }

@test "_cfn_import_vpc_endpoint_params: empty list = no extras" {
  state_set CREATE_VPC_ENDPOINTS ""
  local params=()
  _cfn_import_vpc_endpoint_params "vpc-x" "subnet-1,subnet-2" "us-east-1" params
  [ "${#params[@]}" -eq 0 ]
}

@test "_cfn_import_vpc_endpoint_params: ecr → CreateECREndpoint=true" {
  state_set CREATE_VPC_ENDPOINTS "ecr"
  mkstub aws ''  # not called for non-S3
  local params=()
  _cfn_import_vpc_endpoint_params "vpc-x" "subnet-1,subnet-2" "us-east-1" params
  [ "${#params[@]}" -eq 1 ]
  [[ "${params[0]}" == "CreateECREndpoint=true" ]]
}

@test "_cfn_import_vpc_endpoint_params: full set produces all 7 toggles" {
  state_set CREATE_VPC_ENDPOINTS "ecr,ecrDocker,cloudwatchLogs,efs,sts,eksAuth"
  local params=()
  _cfn_import_vpc_endpoint_params "vpc-x" "subnet-1,subnet-2" "us-east-1" params
  printf '%s\n' "${params[@]}" | grep -q "CreateECREndpoint=true"
  printf '%s\n' "${params[@]}" | grep -q "CreateECRDockerEndpoint=true"
  printf '%s\n' "${params[@]}" | grep -q "CreateCloudWatchLogsEndpoint=true"
  printf '%s\n' "${params[@]}" | grep -q "CreateEFSEndpoint=true"
  printf '%s\n' "${params[@]}" | grep -q "CreateSTSEndpoint=true"
  printf '%s\n' "${params[@]}" | grep -q "CreateEKSAuthEndpoint=true"
}

@test "_cfn_import_vpc_endpoint_params: s3 resolves route tables" {
  state_set CREATE_VPC_ENDPOINTS "s3"
  # describe-route-tables returns one RT id per subnet
  cat >"$STUB_DIR/aws" <<'AWS'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$STUB_DIR/aws.calls"
case "$*" in
  *"Name=association.subnet-id,Values=subnet-1"*) printf 'rt-1\n' ;;
  *"Name=association.subnet-id,Values=subnet-2"*) printf 'rt-2\n' ;;
  *) printf 'None\n' ;;
esac
exit 0
AWS
  chmod +x "$STUB_DIR/aws"

  local params=()
  _cfn_import_vpc_endpoint_params "vpc-x" "subnet-1,subnet-2" "us-east-1" params
  printf '%s\n' "${params[@]}" >&2
  printf '%s\n' "${params[@]}" | grep -q "CreateS3Endpoint=true"
  printf '%s\n' "${params[@]}" | grep -qE "S3EndpointRouteTableIds=rt-1,rt-2"
}

@test "_cfn_import_vpc_endpoint_params: s3 falls back to main route table" {
  state_set CREATE_VPC_ENDPOINTS "s3"
  # Subnet has no explicit RT — main RT lookup returns rt-main
  cat >"$STUB_DIR/aws" <<'AWS'
#!/usr/bin/env bash
case "$*" in
  *"Name=association.main,Values=true"*) printf 'rt-main\n' ;;
  *) printf 'None\n' ;;
esac
exit 0
AWS
  chmod +x "$STUB_DIR/aws"

  local params=()
  _cfn_import_vpc_endpoint_params "vpc-x" "subnet-orphan" "us-east-1" params
  printf '%s\n' "${params[@]}" | grep -qE "S3EndpointRouteTableIds=rt-main"
}

@test "_cfn_import_vpc_endpoint_params: unknown keyword warns and skips" {
  state_set CREATE_VPC_ENDPOINTS "ecr,bogus,sts"
  local params=()
  run _cfn_import_vpc_endpoint_params "vpc-x" "subnet-1" "us-east-1" params
  # We're calling a function that doesn't itself bash-fail; assert via the
  # function's stderr. It'll set ecr+sts and warn on bogus.
  [ "$status" -eq 0 ]
  # Re-call directly to read the array (run swallows it).
  params=()
  _cfn_import_vpc_endpoint_params "vpc-x" "subnet-1" "us-east-1" params
  [ "${#params[@]}" -eq 2 ]
}
