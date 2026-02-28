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
  echo "By default, uses public container images."
  echo "Optionally supports building from source for testing unreleased changes."
  echo ""
  echo "Usage: "
  echo "  ./awsRunEksValidation.sh --stage <stage> --region <region> [options]"
  echo ""
  echo "Required Options:"
  echo "  --stage                                     Deployment stage name"
  echo "  --region                                    AWS region"
  echo ""
  echo "Other options:"
  echo "  --build-images true                         Build images from source instead of public registry"
  echo "  --org-name opensearch-project               Org name when building from source"
  echo "  --branch main                               Git branch when building from source"
  echo ""
  echo "Examples:"
  echo "  # Standard validation with public images"
  echo "  ./awsRunEksValidation.sh --stage ekscreatevpc --region us-east-1"
  echo ""
  echo "  # Test from a feature branch"
  echo "  ./awsRunEksValidation.sh --stage dev --region us-east-1 --build-images true --branch feature-xyz"
  echo ""
  exit 1
}

# Default values
STAGE=""
REGION="us-east-1"
STACK_NAME=""

# Any extra args to forward to aws-bootstrap.sh
BOOTSTRAP_ARGS=()

# Parse command line arguments
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

# Validate required parameters
if [ -z "${STAGE}" ]; then
  echo "Error: --stage is a required parameter"
  usage
fi

if [ -z "${STACK_NAME}" ]; then
  echo "Info: --stack-name not provided. Using default:"
  STACK_NAME="Migration-Assistant-Infra-Create-VPC-eks-${STAGE}-${REGION}"
fi

echo "Starting EKS Migration Assistant deployment with :"
echo "Stage: ${STAGE}"
echo "Region: ${REGION}"
echo "Stack Name: ${STACK_NAME}"
if [[ ${#BOOTSTRAP_ARGS[@]} -gt 0 ]]; then
  echo "Extra aws-bootstrap.sh args: ${BOOTSTRAP_ARGS[*]}"
else
  echo "Extra aws-bootstrap.sh args: (none)"
fi
echo ""

# Helper Function for failures
fail() {
  echo "ERROR: $*" >&2
  exit 1
}

# Function to verify CFN stack exists and is CREATE_COMPLETE
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
    CREATE_COMPLETE)
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

    *)
      fail "Expected stack CREATE_COMPLETE or CREATE_IN_PROGRESS, got '${STACK_STATUS}'."
      ;;
  esac
}

# Function to run aws-bootstrap.sh
run_aws_bootstrap() {
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
  BOOTSTRAP_DIR="${REPO_ROOT}/deployment/k8s/aws"
  BOOTSTRAP_SCRIPT="${BOOTSTRAP_DIR}/aws-bootstrap.sh"

  if [[ ! -x "${BOOTSTRAP_SCRIPT}" ]]; then
    fail "aws-bootstrap.sh not found or not executable at: ${BOOTSTRAP_SCRIPT}"
  fi

  echo "Running aws-bootstrap.sh from ${BOOTSTRAP_DIR}."

  pushd "${BOOTSTRAP_DIR}" >/dev/null

  if [[ ${#BOOTSTRAP_ARGS[@]} -gt 0 ]]; then
    echo "Invoking aws-bootstrap.sh with extra args: ${BOOTSTRAP_ARGS[*]}"
    ./aws-bootstrap.sh \
      --skip-console-exec \
      --skip-setting-k8s-context \
      --stage "${STAGE}" \
      "${BOOTSTRAP_ARGS[@]}"
  else
    echo "Invoking aws-bootstrap.sh with default args (public images)."
    ./aws-bootstrap.sh \
      --skip-console-exec \
      --skip-setting-k8s-context \
      --stage "${STAGE}"
  fi

  echo "aws-bootstrap.sh completed successfully."
  popd >/dev/null

  # Derive the kube context name (matches the EKS cluster name / alias set by aws-bootstrap.sh)
  KUBE_CONTEXT="migration-eks-cluster-${STAGE}-${REGION}"
  export KUBE_CONTEXT
  echo "Using kubectl context: ${KUBE_CONTEXT}"
  echo
}

# Function to wait for migration-console-0 pod to be ready
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

  if ! kubectl --context="${KUBE_CONTEXT}" exec -n ma migration-console-0 -- /bin/bash -lc 'console --version'; then
    fail "console --version command failed inside migration-console-0."
  fi

  echo
  echo "Migration console validation successful."
  echo "EKS deployment for stage '${STAGE}' in region '${REGION}' is healthy."
}

# Main execution flow
main() {
  verify_cfn_stack
  run_aws_bootstrap
  wait_for_migration_console_pod
}

# Run main function
main
