#!/bin/bash

# This script will deploy the https://github.com/aws-samples/amazon-opensearch-service-sample-cdk CDK package.
# It requires the aws cli, cdk cli, git and jq to be already installed.

set -euo pipefail

# Parse CDK --outputs-file into cluster-details JSON.
# CDK outputs use export names: ClusterEndpoint-{stage}-{clusterId}, ClusterSubnets-{stage}-{clusterId},
# ClusterAccessSecurityGroupId-{stage}-{clusterId}. The outputs file keys have dashes stripped by CFN.
write_cluster_outputs() {
  local stage="$1"
  local outfile="$2"
  local cdk_outputs_file="$3"
  local provided_vpc_id="${4:-}"

  # cdk --outputs-file produces: {"StackName": {"OutputKey": "OutputValue", ...}}
  # Flatten to a single object of all outputs across stacks
  outputs=$(jq 'to_entries | map(.value) | add // {}' "$cdk_outputs_file")

  # CDK strips hyphens from stage name in CFN OutputKeys
  stage_nohyphen=$(echo "$stage" | tr -d '-')
  vpc_id="${provided_vpc_id:-$(echo "$outputs" | jq -r --arg key "VpcIdExport${stage_nohyphen}" '.[$key] // empty')}"

  tmpfile=$(mktemp)
  echo "{}" > "$tmpfile"

  # Process ClusterEndpoint outputs (managed domains)
  while read -r output_key; do
    cluster_id=$(echo "$output_key" | sed "s/^ClusterEndpointExport${stage_nohyphen}//")
    endpoint=$(echo "$outputs" | jq -r --arg k "$output_key" '.[$k]')
    [[ ! "$endpoint" =~ ^https?:// ]] && endpoint="https://$endpoint"

    sg=$(echo "$outputs" | jq -r --arg k "ClusterAccessSecurityGroupIdExport${stage_nohyphen}${cluster_id}" '.[$k] // empty')
    subnets=$(echo "$outputs" | jq -r --arg k "ClusterSubnets${stage_nohyphen}${cluster_id}" '.[$k] // empty')

    jq --arg id "$cluster_id" --arg vpc "${vpc_id:-}" --arg endpoint "$endpoint" \
       --arg sg "${sg:-}" --arg subnets "${subnets:-}" \
       '. + {($id): {vpcId: $vpc, endpoint: $endpoint, securityGroupId: $sg, subnetIds: $subnets}}' \
       "$tmpfile" > "$tmpfile.new"
    mv "$tmpfile.new" "$tmpfile"
  done < <(echo "$outputs" | jq -r 'keys[] | select(test("^ClusterEndpointExport"))')

  # Process CollectionEndpoint outputs (serverless)
  while read -r output_key; do
    cluster_id=$(echo "$output_key" | sed "s/^CollectionEndpointExport${stage_nohyphen}//")
    endpoint=$(echo "$outputs" | jq -r --arg k "$output_key" '.[$k]')
    [[ ! "$endpoint" =~ ^https?:// ]] && endpoint="https://$endpoint"

    jq --arg id "$cluster_id" --arg endpoint "$endpoint" \
       '. + {($id): {vpcId: "", endpoint: $endpoint, securityGroupId: "", subnetIds: ""}}' \
       "$tmpfile" > "$tmpfile.new"
    mv "$tmpfile.new" "$tmpfile"
  done < <(echo "$outputs" | jq -r 'keys[] | select(test("^CollectionEndpointExport"))')

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
VPC_ID=""
DESTROY=false

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
    --vpc-id)
      VPC_ID="$2"
      shift 2
      ;;
    --destroy)
      DESTROY=true
      shift
      ;;
    -h|--help)
      echo "Usage: $0 [options]"
      echo "Options:"
      echo "  -s, --stage <val>              Stage name (default: $STAGE)"
      echo "  -c, --context-file <path>      Path to context file (REQUIRED)"
      echo "  --vpc-id <id>                  VPC ID to use in cluster outputs (overrides CDK output)"
      echo "  --destroy                      Destroy all stacks instead of deploying"
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
SAMPLE_CDK_VERSION="v0.3.8"
echo "Using sample CDK version: $SAMPLE_CDK_VERSION"
if [ ! -d "amazon-opensearch-service-sample-cdk" ]; then
  git clone "$SAMPLE_CDK_REPO"
else
  echo "Repo already exists, skipping clone."
fi
cd amazon-opensearch-service-sample-cdk && git checkout -- . && git fetch --tags && git checkout "$SAMPLE_CDK_VERSION"
npm ci

cd ..
cp -f "$PROVIDED_CONTEXT_FILE_PATH" "$CLUSTER_CDK_CONTEXT_FILE_PATH"

cd amazon-opensearch-service-sample-cdk

if [[ "$DESTROY" == true ]]; then
  cdk destroy "*" --force
else
  CDK_OUTPUTS_FILE=$(mktemp)
  cdk deploy "*" --require-approval never --concurrency 3 --outputs-file "$CDK_OUTPUTS_FILE"

  CLUSTER_DETAILS_OUTPUT_FILE_PATH="$ROOT_REPO_PATH/test/tmp/cluster-details-${STAGE}.json"
  mkdir -p "$(dirname "$CLUSTER_DETAILS_OUTPUT_FILE_PATH")"
  cd ..
  write_cluster_outputs "$STAGE" "$CLUSTER_DETAILS_OUTPUT_FILE_PATH" "$CDK_OUTPUTS_FILE" "$VPC_ID"
  rm -f "$CDK_OUTPUTS_FILE"
fi
