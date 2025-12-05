#!/bin/bash

set -euo pipefail

usage() {
  echo ""
  echo "Script to validate EKS Migration Assistant deployment"
  echo ""
  echo "Usage: "
  echo "  ./awsRunEksValidation.sh [--stage] [--region] [--stack-name] [--build-images] [--branch]"
  echo ""
  echo "Options:"
  echo "  --stage                                     Deployment stage name, e.g. eks-integ"
  echo "  --region                                    AWS region, e.g. us-east-1"
  echo "  --stack-name                                CloudFormation stack name"
  echo "  --build-images                              Whether to build images from source (true/false, default: false)"
  echo "  --org-name                                  Org name when build-images=true (default: opensearch-project)"
  echo "  --branch                                    Git branch to use when build-images=true (default: main)"
  echo ""
  exit 1
}

# Default values
STAGE="dev"
REGION="us-east-1"
STACK_NAME=""
BUILD_IMAGES="false"
BRANCH="main"
ORG_NAME="opensearch-project"

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
    --build-images)
      BUILD_IMAGES="$2"
      shift 2
      ;;
    --branch)
      BRANCH="$2"
      shift 2
      ;;
    --org-name)
      ORG_NAME="$2"
      shift 2
      ;;
    -h|--help)
      usage
      ;;
    -*)
      echo "Unknown option $1"
      usage
      ;;
    *)
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
echo "Boolean Build Images: ${BUILD_IMAGES}"
echo "Org Name: ${ORG_NAME}"
echo "Branch: ${BRANCH}"
echo ""

# Helper Function for failures
fail() {
  echo "ERROR: $*" >&2
  exit 1
}

# Normalize BUILD_IMAGES for safety
BUILD_IMAGES_NORMALIZED="$(echo "${BUILD_IMAGES}" | tr '[:upper:]' '[:lower:]')"

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

  if [[ "${STACK_STATUS}" != "CREATE_COMPLETE" ]]; then
    fail "Expected stack status CREATE_COMPLETE, got '${STACK_STATUS}'."
  fi

  echo "CloudFormation stack is ready."
  echo
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

  # Ensure kubectl, git, jq are available; aws-bootstrap will check as well.
  if [[ "${BUILD_IMAGES}" == "true" ]]; then
    echo "Invoking aws-bootstrap.sh with image build enabled..."
    ./aws-bootstrap.sh \
      --skip-console-exec \
      --build-images true \
      --org-name "${ORG_NAME}" \
      --branch "${BRANCH}"
  else
    echo "Invoking aws-bootstrap.sh with public images..."
    ./aws-bootstrap.sh \
      --skip-console-exec
  fi

  echo "aws-bootstrap.sh completed successfully."
  popd >/dev/null

  echo "Current kubectl context:"
  kubectl config current-context || echo "Warning: Unable to get current context"
  echo
}

# Function to wait for migration-console-0 pod to be ready
wait_for_migration_console_pod() {
  echo "Waiting for migration-console-0 pod in namespace 'ma' to become Ready."

  if ! kubectl wait \
    --namespace ma \
    --for=condition=ready pod/migration-console-0 \
    --timeout=600s; then
    fail "migration-console-0 pod did not become Ready within timeout."
  fi

  echo "migration-console-0 pod is Ready."
  echo

  echo "Validating migration console by running 'console --version' inside the pod..."

  if ! kubectl exec -n ma migration-console-0 -- /bin/bash -lc 'console --version'; then
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
