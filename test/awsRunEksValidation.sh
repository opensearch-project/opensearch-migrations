#!/bin/bash

set -euo pipefail

usage() {
  echo ""
  echo "Script to validate EKS Migration Assistant deployment"
  echo ""
  echo "This script:"
  echo "  1. Verifies CloudFormation stack is deployed and healthy"
  echo "  2. Installs Migration Assistant via aws-bootstrap.sh"
  echo "  3. Validates migration console pod is accessible"
  echo ""
  echo "By default, mirrors public container images to private ECR."
  echo "Pass additional aws-bootstrap.sh options after the required arguments."
  echo ""
  echo "Usage:"
  echo "  ./awsRunEksValidation.sh --stage <stage> --region <region> [options]"
  echo ""
  echo "Required options:"
  echo "  --stage <stage>                             Deployment stage name"
  echo "  --region <region>                           AWS region"
  echo ""
  echo "Other options:"
  echo "  --stack-name <name>                         Existing CloudFormation stack name"
  echo "  --build                                     Build all artifacts from source"
  echo "  --version <tag>                             Use a published release"
  echo "  --use-public-images                         Pull directly from public registries"
  echo ""
  echo "Examples:"
  echo "  ./awsRunEksValidation.sh --stage ekscvpc --region us-east-1"
  echo "  ./awsRunEksValidation.sh --stage dev --region us-east-1 --build"
  echo ""
  exit 1
}

STAGE=""
REGION="us-east-1"
STACK_NAME=""
BOOTSTRAP_ARGS=()

while [[ $# -gt 0 ]]; do
  case $1 in
    --stage)
      STAGE="$2"
      shift 2
      ;;
    --region)
      REGION="$2"
      shift 2
      ;;
    --stack-name)
      STACK_NAME="$2"
      shift 2
      ;;
    -h|--help)
      usage
      ;;
    *)
      BOOTSTRAP_ARGS+=("$1")
      shift
      ;;
  esac
done

if [[ -z "${STAGE}" ]]; then
  echo "Error: --stage is a required parameter"
  usage
fi

if [[ -z "${STACK_NAME}" ]]; then
  echo "Info: --stack-name not provided. Using default:"
  STACK_NAME="Migration-Assistant-Infra-Create-VPC-eks-${STAGE}-${REGION}"
fi

echo "Starting EKS Migration Assistant deployment with:"
echo "Stage: ${STAGE}"
echo "Region: ${REGION}"
echo "Stack Name: ${STACK_NAME}"
if [[ ${#BOOTSTRAP_ARGS[@]} -gt 0 ]]; then
  echo "Extra aws-bootstrap.sh args: ${BOOTSTRAP_ARGS[*]}"
else
  echo "Extra aws-bootstrap.sh args: (none)"
fi
echo ""

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

verify_cfn_stack() {
  echo "Verifying CloudFormation stack '${STACK_NAME}' in region '${REGION}'."

  if ! aws cloudformation describe-stacks \
    --stack-name "${STACK_NAME}" \
    --region "${REGION}" >/dev/null 2>&1; then
    fail "CloudFormation stack '${STACK_NAME}' not found in region '${REGION}'."
  fi

  STACK_STATUS=$(aws cloudformation describe-stacks \
    --stack-name "${STACK_NAME}" \
    --region "${REGION}" \
    --query "Stacks[0].StackStatus" \
    --output text)

  echo "Stack status: ${STACK_STATUS}"

  case "${STACK_STATUS}" in
    CREATE_COMPLETE|UPDATE_COMPLETE)
      echo "CloudFormation stack is ready."
      echo
      ;;
    CREATE_IN_PROGRESS)
      echo "Stack is in CREATE_IN_PROGRESS. Waiting for CREATE_COMPLETE."
      aws cloudformation wait stack-create-complete \
        --stack-name "${STACK_NAME}" \
        --region "${REGION}"
      echo "CloudFormation stack ${STACK_NAME} is now CREATE_COMPLETE."
      echo
      ;;
    UPDATE_IN_PROGRESS)
      echo "Stack is in UPDATE_IN_PROGRESS. Waiting for UPDATE_COMPLETE."
      aws cloudformation wait stack-update-complete \
        --stack-name "${STACK_NAME}" \
        --region "${REGION}"
      echo "CloudFormation stack ${STACK_NAME} is now UPDATE_COMPLETE."
      echo
      ;;
    *)
      fail "Expected a complete or in-progress create/update status, got '${STACK_STATUS}'."
      ;;
  esac
}

run_aws_bootstrap() {
  local script_dir repo_root bootstrap_script
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  repo_root="$(cd "${script_dir}/.." && pwd)"
  bootstrap_script="${repo_root}/deployment/k8s/aws/aws-bootstrap.sh"

  if [[ ! -x "${bootstrap_script}" ]]; then
    fail "aws-bootstrap.sh not found or not executable at: ${bootstrap_script}"
  fi

  echo "Running ${bootstrap_script}."

  "${bootstrap_script}" \
    --skip-cfn-deploy \
    --skip-console-exec \
    --skip-setting-k8s-context \
    --stage "${STAGE}" \
    --region "${REGION}" \
    "${BOOTSTRAP_ARGS[@]}"

  echo "aws-bootstrap.sh completed successfully."

  KUBE_CONTEXT="migration-eks-cluster-${STAGE}-${REGION}"
  export KUBE_CONTEXT
  echo "Using kubectl context: ${KUBE_CONTEXT}"
  echo
}

wait_for_migration_console_pod() {
  echo "Waiting for migration-console-0 pod in namespace 'ma' to become Ready."

  if ! kubectl --context="${KUBE_CONTEXT}" wait \
    --namespace ma \
    --for=condition=ready pod/migration-console-0 \
    --timeout=600s; then
    fail "migration-console-0 pod did not become Ready within timeout."
  fi

  echo "migration-console-0 pod is Ready."
  echo
  echo "Validating migration console by running 'console --version' inside the pod."

  if ! kubectl --context="${KUBE_CONTEXT}" exec -n ma migration-console-0 -- \
    /bin/bash -lc 'console --version'; then
    fail "console --version command failed inside migration-console-0."
  fi

  echo
  echo "Migration console validation successful."
  echo "EKS deployment for stage '${STAGE}' in region '${REGION}' is healthy."
}

main() {
  verify_cfn_stack
  run_aws_bootstrap
  wait_for_migration_console_pod
}

main
