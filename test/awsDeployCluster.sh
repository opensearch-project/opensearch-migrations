#!/bin/bash

# This script will deploy the https://github.com/aws-samples/amazon-opensearch-service-sample-cdk CDK package.
# It requires the aws cli, cdk cli, git and jq to be already installed.

set -euo pipefail

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

  # v0.3.x: single stack named OpenSearch-{stage}-{region}
  stack_name=$(echo "$stacks" | grep "^OpenSearch-${stage}-" | head -n 1)
  if [[ -z "$stack_name" ]]; then
    echo "No OpenSearch stack found for stage: $stage"
    return 1
  fi
  echo "Found stack: $stack_name"

  outputs=$(aws cloudformation describe-stacks \
    --stack-name "$stack_name" \
    --query "Stacks[0].Outputs" \
    --output json)

  # VPC ID (inline in same stack, may not exist for serverless-only)
  # CDK strips hyphens from stage name in CFN OutputKeys
  stage_nohyphen=$(echo "$stage" | tr -d '-')
  vpc_id=$(echo "$outputs" | jq -r --arg key "VpcIdExport${stage_nohyphen}" \
    '.[] | select(.OutputKey == $key) | .OutputValue // empty')

  tmpfile=$(mktemp)
  echo "{}" > "$tmpfile"

  # Process ClusterEndpoint outputs (managed domains)
  # CFN OutputKey strips hyphens from CDK logical IDs, so we match with regex
  while IFS= read -r output; do
    output_key=$(echo "$output" | jq -r '.OutputKey')
    # Extract cluster_id: strip "ClusterEndpointExport" prefix and stage name (no hyphens)
    cluster_id=$(echo "$output_key" | sed "s/^ClusterEndpointExport${stage_nohyphen}//")
    endpoint=$(echo "$output" | jq -r '.OutputValue')
    [[ ! "$endpoint" =~ ^https?:// ]] && endpoint="https://$endpoint"

    # Look up SG and subnets using same no-hyphen pattern
    sg=$(echo "$outputs" | jq -r --arg id "ClusterAccessSecurityGroupIdExport${stage_nohyphen}${cluster_id}" \
      '.[] | select(.OutputKey == $id) | .OutputValue // empty')
    subnets=$(echo "$outputs" | jq -r --arg id "ClusterSubnets${stage_nohyphen}${cluster_id}" \
      '.[] | select(.OutputKey == $id) | .OutputValue // empty')

    jq --arg id "$cluster_id" --arg vpc "${vpc_id:-}" --arg endpoint "$endpoint" \
       --arg sg "${sg:-}" --arg subnets "${subnets:-}" \
       '. + {($id): {vpcId: $vpc, endpoint: $endpoint, securityGroupId: $sg, subnetIds: $subnets}}' \
       "$tmpfile" > "$tmpfile.new"
    mv "$tmpfile.new" "$tmpfile"
  done < <(echo "$outputs" | jq -c '.[] | select(.OutputKey | test("^ClusterEndpointExport"))')

  # Process CollectionEndpoint outputs (serverless)
  while IFS= read -r output; do
    output_key=$(echo "$output" | jq -r '.OutputKey')
    cluster_id=$(echo "$output_key" | sed "s/^CollectionEndpointExport${stage_nohyphen}//")
    endpoint=$(echo "$output" | jq -r '.OutputValue')
    [[ ! "$endpoint" =~ ^https?:// ]] && endpoint="https://$endpoint"

    jq --arg id "$cluster_id" --arg endpoint "$endpoint" \
       '. + {($id): {vpcId: "", endpoint: $endpoint, securityGroupId: "", subnetIds: ""}}' \
       "$tmpfile" > "$tmpfile.new"
    mv "$tmpfile.new" "$tmpfile"
  done < <(echo "$outputs" | jq -c '.[] | select(.OutputKey | test("^CollectionEndpointExport"))')

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

SAMPLE_CDK_REPO="https://github.com/aws-samples/amazon-opensearch-service-sample-cdk.git"
SAMPLE_CDK_VERSION="v0.3.7"
echo "Using sample CDK version: $SAMPLE_CDK_VERSION"
if [ ! -d "amazon-opensearch-service-sample-cdk" ]; then
  git clone "$SAMPLE_CDK_REPO"
else
  echo "Repo already exists, skipping clone."
fi
cd amazon-opensearch-service-sample-cdk && git fetch --tags && git checkout "$SAMPLE_CDK_VERSION"
npm install

cd ..
cp -f "$PROVIDED_CONTEXT_FILE_PATH" "$CLUSTER_CDK_CONTEXT_FILE_PATH"

cd amazon-opensearch-service-sample-cdk
cdk deploy "*" --require-approval never --concurrency 3

CLUSTER_DETAILS_OUTPUT_FILE_PATH="$ROOT_REPO_PATH/test/tmp/cluster-details-${STAGE}.json"
write_cluster_outputs "$STAGE" "$CLUSTER_DETAILS_OUTPUT_FILE_PATH"
