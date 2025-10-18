#!/bin/bash

# This script will deploy the https://github.com/aws-samples/amazon-opensearch-service-sample-cdk CDK package.
# It requires the aws cli, cdk cli, git and jq to be already installed.

set -euo pipefail

CLUSTER_STACK_TYPE_REGEX="(OpenSearchDomain|OpenSearchServerless|SelfManagedEC2)"

write_cluster_outputs() {
  local stage="$1"
  local outfile="$2"

  stacks=$(aws cloudformation list-stacks \
    --query "StackSummaries[?StackStatus!='DELETE_COMPLETE' && StackStatus!='DELETE_IN_PROGRESS'].StackName" \
    --output text | tr '\t' '\n')

  if [[ -z "$stacks" ]]; then
    echo "No stacks found when listing stacks."
    return 1
  fi

  network_stack_name=$(echo "$stacks" | grep "NetworkInfra-${stage}" | head -n 1)
  vpc_id=$(aws cloudformation describe-stacks \
    --stack-name "$network_stack_name" \
    --query "Stacks[0].Outputs[?contains(OutputValue, 'vpc')].OutputValue" \
    --output text)

  cluster_stack_names=$(echo "$stacks" | grep -E "^$CLUSTER_STACK_TYPE_REGEX-.*-${stage}-")
  if [[ -z "$cluster_stack_names" ]]; then
    echo "No cluster stacks found for stage: $stage"
    return 1
  fi

  tmpfile=$(mktemp)
  echo "{}" > "$tmpfile"

  for stack in $cluster_stack_names; do
    echo "Found cluster stack: $stack"

    outputs=$(aws cloudformation describe-stacks \
      --stack-name "$stack" \
      --query "Stacks[0].Outputs" \
      --output json)

    cluster_id=$(echo "$stack" \
      | sed -E "s/^($CLUSTER_STACK_TYPE_REGEX)-//" \
      | sed -E "s/-${stage}-.*$//")
    cluster_endpoint=$(echo "$outputs" | jq -r '.[] | select(.OutputKey | test("^ClusterEndpoint")) | .OutputValue')
    cluster_endpoint="https://$cluster_endpoint"
    cluster_sg=$(echo "$outputs" | jq -r '.[] | select(.OutputKey | test("^ClusterAccessSecurityGroupId")) | .OutputValue')
    cluster_subnets=$(echo "$outputs" | jq -r '.[] | select(.OutputKey | test("^ClusterSubnets")) | .OutputValue')

    jq --arg id "$cluster_id" \
       --arg vpc "$vpc_id" \
       --arg endpoint "$cluster_endpoint" \
       --arg security_group "$cluster_sg" \
       --arg subnets "$cluster_subnets" \
       '. + {($id): {vpcId: $vpc, endpoint: $endpoint, securityGroupId: $security_group, subnetIds: $subnets}}' \
       "$tmpfile" > "$tmpfile.new"

    mv "$tmpfile.new" "$tmpfile"
  done

  mv "$tmpfile" "$outfile"
  echo "Wrote outputs to $outfile"
}

script_abs_path=$(readlink -f "$0")
TEST_DIR_PATH=$(dirname "$script_abs_path")
ROOT_REPO_PATH=$(dirname "$TEST_DIR_PATH")
CLUSTER_CDK_PATH="$ROOT_REPO_PATH/test/amazon-opensearch-service-sample-cdk"
CLUSTER_CDK_CONTEXT_FILE_PATH="$CLUSTER_CDK_PATH/cdk.context.json"

# Defaults
STAGE="aws-integ"
PROVIDED_CONTEXT_FILE_PATH=""

# Argument parser
while [[ $# -gt 0 ]]; do
  case "$1" in
    --stage|-s)
      STAGE="$2"
      shift 2
      ;;
    --context-file|-c)
      PROVIDED_CONTEXT_FILE_PATH="$2"
      shift 2
      ;;
    -h|--help)
      echo "Usage: $0 [options]"
      echo "Options:"
      echo "  -s, --stage <val>              Stage name (default: $STAGE)"
      echo "  -c, --context-file <path>      Path to context file (REQUIRED)"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1"
      exit 1
      ;;
  esac
done

# Require context file
if [[ -z "$PROVIDED_CONTEXT_FILE_PATH" ]]; then
  echo "Error: --context-file is required"
  echo "Run with --help for usage"
  exit 1
fi

if [ ! -d "amazon-opensearch-service-sample-cdk" ]; then
  git clone https://github.com/aws-samples/amazon-opensearch-service-sample-cdk.git
else
  echo "Repo already exists, skipping clone."
fi
cd amazon-opensearch-service-sample-cdk && git pull
npm install

cd ..
cp -f "$PROVIDED_CONTEXT_FILE_PATH" "$CLUSTER_CDK_CONTEXT_FILE_PATH"

cd amazon-opensearch-service-sample-cdk
cdk deploy "*" --require-approval never --concurrency 3

CLUSTER_DETAILS_OUTPUT_FILE_PATH="$ROOT_REPO_PATH/test/tmp/cluster-details-${STAGE}.json"
write_cluster_outputs "$STAGE" "$CLUSTER_DETAILS_OUTPUT_FILE_PATH"
