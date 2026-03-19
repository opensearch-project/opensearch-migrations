#!/bin/bash

# This script will deploy the https://github.com/aws-samples/amazon-opensearch-service-sample-cdk CDK package.
# It requires the aws cli, cdk cli, git and jq to be already installed.

set -euo pipefail

# Parse CDK --outputs-file into cluster-details JSON.
# CDK outputs use export names: ClusterEndpoint-{stage}-{clusterId}, ClusterSubnets-{stage}-{clusterId},
# ClusterAccessSecurityGroupId-{stage}-{clusterId}. The outputs file keys have dashes stripped by CFN.
write_cluster_outputs() {
  local stage="$1"
  local cdk_outputs_file="$2"
  local outfile="$3"
  local provided_vpc_id="${4:-}"

  # Flatten all stack outputs into a single object {key: value, ...}
  local flat_outputs
  flat_outputs=$(jq '[to_entries[].value | to_entries[]] | from_entries' "$cdk_outputs_file")

  # Build dash-stripped stage prefix for key matching (CDK strips dashes from construct IDs)
  local stage_stripped="${stage//-/}"

  # Extract cluster IDs from endpoint output keys: ClusterEndpointExport{stage}{clusterId} → {clusterId}
  local cluster_ids
  cluster_ids=$(echo "$flat_outputs" | jq -r "keys[] | select(startswith(\"ClusterEndpointExport${stage_stripped}\")) | ltrimstr(\"ClusterEndpointExport${stage_stripped}\")")

  if [[ -z "$cluster_ids" ]]; then
    echo "No cluster outputs found in CDK outputs file for stage: $stage"
    return 1
  fi

  local vpc_id="${provided_vpc_id:-}"
  local result="{}"

  for cluster_id_stripped in $cluster_ids; do
    local endpoint sg subnets
    endpoint=$(echo "$flat_outputs" | jq -r ".\"ClusterEndpointExport${stage_stripped}${cluster_id_stripped}\" // empty")
    sg=$(echo "$flat_outputs" | jq -r ".\"ClusterAccessSecurityGroupIdExport${stage_stripped}${cluster_id_stripped}\" // empty")
    subnets=$(echo "$flat_outputs" | jq -r ".\"ClusterSubnets${stage_stripped}${cluster_id_stripped}\" // empty")

    # Recover original clusterId from context file (match by stripped version)
    local cluster_id="$cluster_id_stripped"
    local context_ids
    context_ids=$(jq -r '.clusters[].clusterId // empty' "$CLUSTER_CDK_CONTEXT_FILE_PATH" 2>/dev/null || true)
    for cid in $context_ids; do
      if [[ "${cid//-/}" == "$cluster_id_stripped" ]]; then
        cluster_id="$cid"
        break
      fi
    done

    echo "Found cluster: $cluster_id (endpoint=$endpoint)"
    result=$(echo "$result" | jq \
      --arg id "$cluster_id" \
      --arg vpc "$vpc_id" \
      --arg endpoint "https://$endpoint" \
      --arg sg "$sg" \
      --arg subnets "$subnets" \
      '. + {($id): {vpcId: $vpc, endpoint: $endpoint, securityGroupId: $sg, subnetIds: $subnets}}')
  done

  echo "$result" > "$outfile"
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
      echo "  --vpc-id <id>                  VPC ID to use in cluster outputs (skips NetworkInfra stack lookup)"
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
SAMPLE_CDK_VERSION="v0.3.7"
echo "Using sample CDK version: $SAMPLE_CDK_VERSION"
if [ ! -d "amazon-opensearch-service-sample-cdk" ]; then
  git clone "$SAMPLE_CDK_REPO"
else
  echo "Repo already exists, skipping clone."
fi
cd amazon-opensearch-service-sample-cdk && git checkout -- . && git fetch --tags && git checkout "$SAMPLE_CDK_VERSION"
npm install
npm run build

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
  write_cluster_outputs "$STAGE" "$CDK_OUTPUTS_FILE" "$CLUSTER_DETAILS_OUTPUT_FILE_PATH" "$VPC_ID"
  rm -f "$CDK_OUTPUTS_FILE"
fi
