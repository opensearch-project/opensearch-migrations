#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SOURCE_BOOTSTRAP="${REPO_ROOT}/deployment/k8s/aws/aws-bootstrap.sh"
ASSEMBLER="${REPO_ROOT}/deployment/k8s/aws/assemble-bootstrap.sh"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

assert_contains() {
  local needle="$1"
  local file="$2"
  grep -Fq -- "$needle" "$file" || fail "Expected '${needle}' in ${file}"
}

assert_not_contains() {
  local needle="$1"
  local file="$2"
  if grep -Fq -- "$needle" "$file"; then
    fail "Did not expect '${needle}' in ${file}"
  fi
}

run_expect_failure() {
  local output_file="$1"
  shift

  set +e
  "$@" >"${output_file}" 2>&1
  local rc=$?
  set -e

  if [[ ${rc} -eq 0 ]]; then
    fail "Expected failure but command succeeded: $*"
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
  printf 'MigrationsExportString-eks-dev-us-east-1\texport AWS_ACCOUNT=123456789012; export AWS_CFN_REGION=us-east-1; export STAGE=dev; export MIGRATIONS_EKS_CLUSTER_NAME=migration-eks-cluster-dev-us-east-1; export MIGRATIONS_ECR_REGISTRY=123456789012.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-dev-us-east-1; export SNAPSHOT_ROLE=arn:aws:iam::123456789012:role/SnapshotRole;\n'
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
set -euo pipefail

: "${MOCK_LOG:?}"
printf '%s %s\n' "$(basename "$0")" "$*" >> "${MOCK_LOG}"
echo "unexpected $(basename "$0") call: $*" >&2
exit 42
EOF
  done

  chmod +x "${mock_dir}/aws" "${mock_dir}/curl" "${mock_dir}/jq" "${mock_dir}/kubectl" "${mock_dir}/helm"
}

run_guard_case() {
  local script_under_test="$1"
  local label="$2"
  local args="$3"
  local expected_message="$4"
  local case_name="$5"
  local case_dir="${TMP_ROOT}/${label}-${case_name}"
  local mock_dir="${case_dir}/mock-bin"
  local log_file="${case_dir}/commands.log"
  local output_file="${case_dir}/output.txt"

  mkdir -p "${case_dir}"
  : >"${log_file}"
  setup_mocks "${mock_dir}"

  # shellcheck disable=SC2206
  local extra_args=( ${args} )

  run_expect_failure "${output_file}" env PATH="${mock_dir}:$PATH" MOCK_LOG="${log_file}" \
    bash "${script_under_test}" "${extra_args[@]}"

  assert_contains "${expected_message}" "${output_file}"
  if [[ -s "${log_file}" ]]; then
    fail "${label}/${case_name} should fail before invoking external tools"
  fi
}

run_success_case() {
  local script_under_test="$1"
  local label="$2"
  local case_dir="${TMP_ROOT}/${label}-success"
  local mock_dir="${case_dir}/mock-bin"
  local log_file="${case_dir}/commands.log"
  local output_file="${case_dir}/output.txt"

  mkdir -p "${case_dir}"
  : >"${log_file}"
  setup_mocks "${mock_dir}"

  env PATH="${mock_dir}:$PATH" MOCK_LOG="${log_file}" \
    bash "${script_under_test}" \
      --grant-eks-access-only \
      --stage dev \
      --region us-east-1 \
      --eks-access-principal-arn arn:aws:iam::123456789012:role/TestAdmin \
      >"${output_file}" 2>&1

  assert_contains "Grant-only mode: skipping release version resolution." "${output_file}"
  assert_contains "Mode                   = Grant EKS access only (--grant-eks-access-only)" "${output_file}"
  assert_contains "Done -- EKS access entry applied. Skipped image mirroring and Helm install (--grant-eks-access-only)." "${output_file}"

  assert_contains "aws cloudformation list-exports" "${log_file}"
  assert_contains "aws eks describe-access-entry" "${log_file}"
  assert_contains "aws eks create-access-entry" "${log_file}"
  assert_contains "aws eks associate-access-policy" "${log_file}"

  assert_not_contains "curl " "${log_file}"
  assert_not_contains "jq " "${log_file}"
  assert_not_contains "kubectl " "${log_file}"
  assert_not_contains "helm " "${log_file}"
  assert_not_contains "update-kubeconfig" "${log_file}"
}

ASSEMBLED_BOOTSTRAP="${TMP_ROOT}/aws-bootstrap-assembled.sh"
bash "${ASSEMBLER}" --output "${ASSEMBLED_BOOTSTRAP}" >/dev/null

for script_under_test in "${SOURCE_BOOTSTRAP}" "${ASSEMBLED_BOOTSTRAP}"; do
  label="$(basename "${script_under_test}")"
  run_guard_case \
    "${script_under_test}" \
    "${label}" \
    "--grant-eks-access-only --stage dev --region us-east-1" \
    "Error: --grant-eks-access-only requires --eks-access-principal-arn <arn>." \
    "missing-principal"
  run_guard_case \
    "${script_under_test}" \
    "${label}" \
    "--grant-eks-access-only --skip-cfn-deploy --stage dev --region us-east-1 --eks-access-principal-arn arn:aws:iam::123456789012:role/TestAdmin" \
    "Error: --grant-eks-access-only cannot be combined with --deploy-*-cfn, --skip-cfn-deploy, or --build." \
    "invalid-combination"
  run_success_case "${script_under_test}" "${label}"
done

echo "PASS: grant-only mode works without artifact/tool dependencies and still enforces its argument guards."
