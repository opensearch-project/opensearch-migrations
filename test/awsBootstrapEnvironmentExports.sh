#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SOURCE_BOOTSTRAP="${REPO_ROOT}/deployment/k8s/aws/aws-bootstrap.sh"
ASSEMBLER="${REPO_ROOT}/deployment/k8s/aws/assemble-bootstrap.sh"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

PRINCIPAL_ARN="arn:aws:iam::123456789012:role/TestAdmin"
EXPORT_VARS=(MIGRATIONS_EKS_CLUSTER_NAME MIGRATIONS_ECR_REGISTRY AWS_ACCOUNT AWS_CFN_REGION VPC_ID
  EKS_CLUSTER_SECURITY_GROUP SNAPSHOT_ROLE STAGE)

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

assert_contains() {
  grep -Fq -- "$1" "$2" || fail "Expected '$1' in $2"
}

assert_not_contains() {
  if grep -Fq -- "$1" "$2"; then
    fail "Did not expect '$1' in $2"
  fi
}

setup_mocks() {
  local mock_dir="$1"
  mkdir -p "${mock_dir}"

  cat >"${mock_dir}/aws" <<'EOF'
#!/bin/bash
set -euo pipefail

: "${MOCK_LOG:?}"
printf 'aws %s\n' "$*" >> "${MOCK_LOG}"

if [[ "${1:-}" == "cloudformation" && "${2:-}" == "list-exports" ]]; then
  printf 'MigrationsExportString-eks-dev-us-east-1\texport AWS_ACCOUNT=123456789012; export AWS_CFN_REGION=us-east-1; export STAGE=dev; export MIGRATIONS_EKS_CLUSTER_NAME=migration-eks-cluster-dev-us-east-1; export MIGRATIONS_ECR_REGISTRY=123456789012.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-dev-us-east-1; export SNAPSHOT_ROLE=%s;\n' \
    "${MOCK_CFN_SNAPSHOT_ROLE-arn:aws:iam::123456789012:role/SnapshotRole}"
  exit 0
fi

if [[ "${1:-}" == "eks" && "${2:-}" == "describe-access-entry" ]]; then
  exit 254
fi

if [[ "${1:-}" == "eks" && ( "${2:-}" == "create-access-entry" || "${2:-}" == "associate-access-policy" ) ]]; then
  exit 0
fi

echo "unexpected aws call: $*" >&2
exit 99
EOF

  for cmd in curl jq kubectl helm; do
    cat >"${mock_dir}/${cmd}" <<'EOF'
#!/bin/bash
: "${MOCK_LOG:?}"
printf '%s %s\n' "$(basename "$0")" "$*" >> "${MOCK_LOG}"
exit 42
EOF
  done
  chmod +x "${mock_dir}"/*
}

# Runs the grant-only bootstrap with the Terraform-style exports given as NAME=value arguments.
# Prints the case directory; the caller reads output.txt and commands.log from it.
run_case() {
  local script_under_test="$1" case_name="$2" stage="$3" region="$4"
  shift 4
  local case_dir="${TMP_ROOT}/$(basename "${script_under_test}")-${case_name}"
  mkdir -p "${case_dir}"
  : >"${case_dir}/commands.log"
  setup_mocks "${case_dir}/mock-bin"

  local unset_args=()
  for var in "${EXPORT_VARS[@]}"; do
    unset_args+=(-u "${var}")
  done

  set +e
  env "${unset_args[@]}" PATH="${case_dir}/mock-bin:$PATH" MOCK_LOG="${case_dir}/commands.log" "$@" \
    bash "${script_under_test}" --grant-eks-access-only --stage "${stage}" --region "${region}" \
      --eks-access-principal-arn "${PRINCIPAL_ARN}" >"${case_dir}/output.txt" 2>&1
  echo $? >"${case_dir}/rc"
  set -e
  echo "${case_dir}"
}

terraform_exports() {
  local stage="$1" region="$2" snapshot_role="$3"
  echo "MIGRATIONS_EKS_CLUSTER_NAME=migration-eks-cluster-${stage}-${region}" \
    "MIGRATIONS_ECR_REGISTRY=123456789012.dkr.ecr.${region}.amazonaws.com/migration-ecr-${stage}-${region}" \
    "AWS_ACCOUNT=123456789012" "AWS_CFN_REGION=${region}" "VPC_ID=vpc-0123456789abcdef0" \
    "EKS_CLUSTER_SECURITY_GROUP=sg-0123456789abcdef0" "SNAPSHOT_ROLE=${snapshot_role}" "STAGE=${stage}"
}

expect_rc() {
  local case_dir="$1" expected="$2"
  local rc
  rc="$(cat "${case_dir}/rc")"
  if [[ "${expected}" == "0" && "${rc}" != "0" ]] || [[ "${expected}" != "0" && "${rc}" == "0" ]]; then
    cat "${case_dir}/output.txt" >&2
    fail "$(basename "${case_dir}") exited ${rc}; expected ${expected}"
  fi
}

ASSEMBLED_BOOTSTRAP="${TMP_ROOT}/aws-bootstrap-assembled.sh"
bash "${ASSEMBLER}" --output "${ASSEMBLED_BOOTSTRAP}" >/dev/null

for script_under_test in "${SOURCE_BOOTSTRAP}" "${ASSEMBLED_BOOTSTRAP}"; do
  # shellcheck disable=SC2046
  dir="$(run_case "${script_under_test}" terraform dev us-east-1 $(terraform_exports dev us-east-1 ""))"
  expect_rc "${dir}" 0
  assert_contains "Using Migration Assistant exports from the environment (cluster migration-eks-cluster-dev-us-east-1)" \
    "${dir}/output.txt"
  assert_contains "Snapshot Role            = " "${dir}/output.txt"
  assert_not_contains "cloudformation" "${dir}/commands.log"
  assert_contains "aws eks create-access-entry --cluster-name migration-eks-cluster-dev-us-east-1" "${dir}/commands.log"

  dir="$(run_case "${script_under_test}" cloudformation dev us-east-1)"
  expect_rc "${dir}" 0
  assert_contains "aws cloudformation list-exports" "${dir}/commands.log"
  assert_not_contains "from the environment" "${dir}/output.txt"

  # shellcheck disable=SC2046
  dir="$(run_case "${script_under_test}" stage-mismatch dev us-east-1 $(terraform_exports prod us-east-1 ""))"
  expect_rc "${dir}" 1
  assert_contains "Error: --stage 'dev' does not match STAGE='prod' from the environment." "${dir}/output.txt"
  assert_not_contains "aws " "${dir}/commands.log"

  # shellcheck disable=SC2046
  dir="$(run_case "${script_under_test}" region-mismatch dev us-east-1 $(terraform_exports dev us-west-2 ""))"
  expect_rc "${dir}" 1
  assert_contains "Error: --region 'us-east-1' does not match AWS_CFN_REGION='us-west-2' from the environment." \
    "${dir}/output.txt"
  assert_not_contains "aws " "${dir}/commands.log"

  # CloudFormation exports must still carry a snapshot role.
  dir="$(run_case "${script_under_test}" cloudformation-no-snapshot-role dev us-east-1 MOCK_CFN_SNAPSHOT_ROLE=)"
  expect_rc "${dir}" 1
  assert_contains "Error: The following required variables were not set by CloudFormation exports:" "${dir}/output.txt"
  assert_contains "  SNAPSHOT_ROLE" "${dir}/output.txt"
done

echo "PASS: bootstrap uses Terraform migration_environment exports from the environment and keeps the CloudFormation path."
