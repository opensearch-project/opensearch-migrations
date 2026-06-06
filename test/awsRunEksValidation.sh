#!/bin/bash

set -euo pipefail

usage() {
  echo ""
  echo "Script to validate EKS Migration Assistant deployment"
  echo ""
  echo "This script:"
  echo "  1. Verifies CloudFormation stack is deployed and healthy"
  echo "  2. Installs Migration Assistant via the migration-assistant CLI"
  echo "  3. Validates migration console pod is accessible"
  echo ""
  echo "By default, mirrors public container images to private ECR."
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
  echo "  --build                                     Build all artifacts from source instead of using release"
  echo "  --org-name opensearch-project               Org name when building from source"
  echo "  --branch main                               Git branch when building from source"
  echo ""
  echo "Examples:"
  echo "  # Standard validation with public images"
  echo "  ./awsRunEksValidation.sh --stage ekscvpc --region us-east-1"
  echo ""
  echo "  # Test from a feature branch"
  echo "  ./awsRunEksValidation.sh --stage dev --region us-east-1 --build --branch feature-xyz"
  echo ""
  exit 1
}

# Default values
STAGE=""
REGION="us-east-1"
STACK_NAME=""

# Any extra args to forward to migration-assistant
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
  echo "Extra migration-assistant args: ${BOOTSTRAP_ARGS[*]}"
else
  echo "Extra migration-assistant args: (none)"
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

# Function to run the migration-assistant CLI (replaces aws-bootstrap.sh).
#
# The CLI source is Amber (deployment/k8s/aws/cli/src/*.ab). We compile the
# entrypoint to a single self-contained Bash script via Gradle, which PROVISIONS
# the Amber toolchain itself (downloads + caches the pinned release binary, like
# the Java/Corretto toolchain) — so the agent needs no host `amber`. The compiled
# output is plain Bash; only the compile step (Gradle) touches the toolchain.
run_aws_bootstrap() {
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
  CLI_BIN="${REPO_ROOT}/deployment/k8s/aws/build/migrate-cli-compiled/migration-assistant"

  echo "Compiling migration-assistant via Gradle (provisions the Amber toolchain)."
  ( cd "${REPO_ROOT}" && ./gradlew :deployment:k8s:aws:compileMigrationAssistantCli ) \
    || fail "gradle compile of the migration-assistant CLI failed"
  test -x "${CLI_BIN}" || fail "compiled CLI not found at ${CLI_BIN}"

  echo "Running migration-assistant (compiled from ${CLI_DIR})."

  # Pin MIGRATE_HOME to a per-build workspace dir so each Jenkins run
  # starts from a clean state directory (no leftover state.env / cached
  # artifacts from previous PR builds).
  MIGRATE_HOME="${WORKSPACE:-$PWD}/.migrate-home-${BUILD_NUMBER:-local}"
  export MIGRATE_HOME
  rm -rf "${MIGRATE_HOME}"
  mkdir -p "${MIGRATE_HOME}"
  echo "MIGRATE_HOME=${MIGRATE_HOME}"

  # --verbose so all log-file content also lands on the Jenkins
  # console live (rather than only-on-failure as a tail). Then if the
  # CLI dies before our trap fires we still see what happened.
  set +e
  if [[ ${#BOOTSTRAP_ARGS[@]} -gt 0 ]]; then
    echo "Invoking migration-assistant with extra args: ${BOOTSTRAP_ARGS[*]}"
    "${CLI_BIN}" \
      --non-interactive \
      --verbose \
      --skip-console-exec \
      --skip-setting-k8s-context \
      --stage "${STAGE}" \
      "${BOOTSTRAP_ARGS[@]}"
  else
    echo "Invoking migration-assistant with default args (public images)."
    "${CLI_BIN}" \
      --non-interactive \
      --verbose \
      --skip-console-exec \
      --skip-setting-k8s-context \
      --use-public-images \
      --stage "${STAGE}"
  fi
  cli_rc=$?
  set -e

  if [[ $cli_rc -ne 0 ]]; then
    echo
    echo "=== migration-assistant FAILED (rc=$cli_rc) — dumping migrate.log ==="
    cat "${MIGRATE_HOME}/${STAGE}/log/migrate.log" 2>&1 || \
      echo "(migrate.log not found at ${MIGRATE_HOME}/${STAGE}/log/migrate.log)"
    echo "=== end migrate.log ==="
    fail "migration-assistant exited rc=$cli_rc"
  fi

  echo "migration-assistant completed successfully."

  # Derive the kube context name (matches the EKS cluster name / alias)
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
