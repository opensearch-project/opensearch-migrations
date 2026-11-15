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

setup_mocks() {
  local mock_dir="$1"
  mkdir -p "$mock_dir"

  cat > "${mock_dir}/aws" <<'EOF'
#!/bin/bash
set -euo pipefail

: "${MOCK_LOG:?}"
: "${STATUS_COUNT:?}"
printf 'aws %s\n' "$*" >> "$MOCK_LOG"

if [[ "${1:-} ${2:-}" == "cloudformation describe-stacks" ]]; then
  if [[ "$*" != *"--query Stacks[0].StackStatus"* ]]; then
    exit 0
  fi
  count=$(cat "$STATUS_COUNT")
  count=$((count + 1))
  printf '%s\n' "$count" > "$STATUS_COUNT"
  if [[ "$count" -le 2 ]]; then
    printf 'CREATE_IN_PROGRESS\n'
  else
    printf 'CREATE_COMPLETE\n'
  fi
  exit 0
fi

if [[ "${1:-} ${2:-}" == "cloudformation describe-stack-events" ]]; then
  exit 0
fi

if [[ "${1:-} ${2:-}" == "cloudformation list-exports" ]]; then
  printf 'MigrationsExportString-dev-us-east-1\texport AWS_ACCOUNT=123456789012; export AWS_CFN_REGION=us-east-1; export STAGE=dev; export MIGRATIONS_EKS_CLUSTER_NAME=migration-eks-cluster-dev-us-east-1; export MIGRATIONS_ECR_REGISTRY=123456789012.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-dev-us-east-1; export SNAPSHOT_ROLE=arn:aws:iam::123456789012:role/SnapshotRole;\n'
  exit 0
fi

if [[ "${1:-} ${2:-}" == "eks update-kubeconfig" ]]; then
  exit 73
fi

echo "unexpected aws call: $*" >&2
exit 99
EOF

  cat > "${mock_dir}/curl" <<'EOF'
#!/bin/bash
set -euo pipefail

output=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) output="$2"; shift 2 ;;
    *) shift ;;
  esac
done
[[ -n "$output" ]] || exit 2
: > "$output"
EOF

  for cmd in jq kubectl helm; do
    cat > "${mock_dir}/${cmd}" <<'EOF'
#!/bin/bash
exit 0
EOF
  done
  chmod +x "${mock_dir}/aws" "${mock_dir}/curl" \
    "${mock_dir}/jq" "${mock_dir}/kubectl" "${mock_dir}/helm"
}

run_case() {
  local script_under_test="$1" label="$2"
  local case_dir="${TMP_ROOT}/${label}"
  local mock_dir="${case_dir}/mock-bin"
  local log_file="${case_dir}/commands.log"
  local status_count="${case_dir}/status-count"
  local output_file="${case_dir}/output"
  local rc=0

  mkdir -p "$case_dir"
  : > "$log_file"
  printf '0\n' > "$status_count"
  setup_mocks "$mock_dir"

  env PATH="${mock_dir}:$PATH" MOCK_LOG="$log_file" STATUS_COUNT="$status_count" \
      CFN_POLL_INTERVAL=0 \
    bash "$script_under_test" \
      --deploy-create-vpc-cfn \
      --stack-name MA-Resume-Test \
      --stage dev \
      --region us-east-1 \
      --version v1.2.3 \
      --use-public-images \
      > "$output_file" 2>&1 || rc=$?

  [[ "$rc" -eq 73 ]] || {
    cat "$output_file" >&2
    fail "${label}: expected controlled kubeconfig failure, got $rc"
  }
  grep -q 'Attaching to the in-progress operation' "$output_file" \
    || fail "${label}: did not report attaching to CloudFormation"
  if grep -q '^aws cloudformation deploy ' "$log_file"; then
    fail "${label}: attempted a second CloudFormation deployment"
  fi
  [[ "$(cat "$status_count")" == "3" ]] \
    || fail "${label}: did not wait for CREATE_COMPLETE"
}

ASSEMBLED_BOOTSTRAP="${TMP_ROOT}/aws-bootstrap-assembled.sh"
bash "$ASSEMBLER" --output "$ASSEMBLED_BOOTSTRAP" >/dev/null

run_case "$SOURCE_BOOTSTRAP" source
run_case "$ASSEMBLED_BOOTSTRAP" assembled

echo "PASS: source and assembled bootstrap attach to an in-progress CloudFormation deployment."
