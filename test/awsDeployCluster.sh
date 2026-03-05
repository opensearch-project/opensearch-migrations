#!/bin/bash

# This script will deploy the https://github.com/aws-samples/amazon-opensearch-service-sample-cdk CDK package.
# It requires the aws cli, cdk cli, git and jq to be already installed.

set -euo pipefail

# CDK stack naming convention: OpenSearch-{stage}-{region}
write_cluster_outputs() {
  local stage="$1"
  local outfile="$2"
  local provided_vpc_id="${3:-}"

  stacks=$(aws cloudformation list-stacks \
    --query "StackSummaries[?StackStatus!='DELETE_COMPLETE' && StackStatus!='DELETE_IN_PROGRESS'].StackName" \
    --output text | tr '\t' '\n')

  if [[ -z "$stacks" ]]; then
    echo "No stacks found when listing stacks."
    return 1
  fi

  local vpc_id
  if [[ -n "$provided_vpc_id" ]]; then
    vpc_id="$provided_vpc_id"
    echo "Using provided VPC ID: $vpc_id"
  else
    network_stack_name=$(echo "$stacks" | grep "NetworkInfra-${stage}" | head -n 1)
    vpc_id=$(aws cloudformation describe-stacks \
      --stack-name "$network_stack_name" \
      --query "Stacks[0].Outputs[?contains(OutputValue, 'vpc')].OutputValue" \
      --output text)
  fi

  # Match CDK naming: OpenSearch-{stage}-{region}
  cluster_stack_names=$(echo "$stacks" | grep -E "^OpenSearch-${stage}-" || true)
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

    # Extract cluster IDs from output keys.
    # CDK construct ID "ClusterEndpointExport-{stage}-{clusterId}" becomes
    # CFN OutputKey "ClusterEndpointExport{stageNoDashes}{clusterId}" (dashes stripped)
    local stage_nodash="${stage//-/}"
    cluster_ids=$(echo "$outputs" | jq -r '.[].OutputKey' | grep -oP "^ClusterEndpointExport${stage_nodash}\K.+" || true)

    for cluster_id in $cluster_ids; do
      cluster_endpoint=$(echo "$outputs" | jq -r --arg key "ClusterEndpointExport${stage_nodash}${cluster_id}" \
        '.[] | select(.OutputKey == $key) | .OutputValue')
      cluster_endpoint="https://$cluster_endpoint"
      cluster_sg=$(echo "$outputs" | jq -r --arg key "ClusterAccessSecurityGroupIdExport${stage_nodash}${cluster_id}" \
        '.[] | select(.OutputKey == $key) | .OutputValue')
      # Fallback: look up security group from the OpenSearch domain's VPC config
      if [[ -z "$cluster_sg" || "$cluster_sg" == "null" ]]; then
        domain_name=$(aws cloudformation list-stack-resources --stack-name "$stack" \
          --query "StackResourceSummaries[?ResourceType=='AWS::OpenSearchService::Domain'].PhysicalResourceId | [0]" \
          --output text 2>/dev/null || true)
        if [[ -n "$domain_name" && "$domain_name" != "None" ]]; then
          cluster_sg=$(aws opensearch describe-domain --domain-name "$domain_name" \
            --query 'DomainStatus.VPCOptions.SecurityGroupIds[0]' --output text 2>/dev/null || true)
          if [[ -n "$cluster_sg" && "$cluster_sg" != "None" ]]; then
            echo "Looked up security group from domain $domain_name: $cluster_sg"
          else
            cluster_sg=""
          fi
        fi
      fi
      cluster_subnets=$(echo "$outputs" | jq -r --arg key "ClusterSubnets${stage_nodash}${cluster_id}" \
        '.[] | select(.OutputKey == $key) | .OutputValue')

      echo "  cluster_id=$cluster_id endpoint=$cluster_endpoint sg=$cluster_sg"

      jq --arg id "$cluster_id" \
         --arg vpc "$vpc_id" \
         --arg endpoint "$cluster_endpoint" \
         --arg security_group "$cluster_sg" \
         --arg subnets "$cluster_subnets" \
         '. + {($id): {vpcId: $vpc, endpoint: $endpoint, securityGroupId: $security_group, subnetIds: $subnets}}' \
         "$tmpfile" > "$tmpfile.new"

      mv "$tmpfile.new" "$tmpfile"
    done
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

# Pin to v0.3.1 — v0.3.2+ has a bug: cfnDomain.getAtt('DomainEndpoints.vpc') causes
# ROLLBACK with "Requested attribute DomainEndpoints.vpc must be a readonly property"
CDK_REPO_TAG="v0.3.1"

if [ ! -d "amazon-opensearch-service-sample-cdk" ]; then
  git clone https://github.com/aws-samples/amazon-opensearch-service-sample-cdk.git
else
  echo "Repo already exists, skipping clone."
fi
cd amazon-opensearch-service-sample-cdk && git fetch --tags && git checkout "$CDK_REPO_TAG"
npm install

cd ..
cp -f "$PROVIDED_CONTEXT_FILE_PATH" "$CLUSTER_CDK_CONTEXT_FILE_PATH"

cd amazon-opensearch-service-sample-cdk

if [[ "$DESTROY" == true ]]; then
  cdk destroy "*" --force
else
  cdk deploy "*" --require-approval never --concurrency 3

  CLUSTER_DETAILS_OUTPUT_FILE_PATH="$ROOT_REPO_PATH/test/tmp/cluster-details-${STAGE}.json"
  cd ..
  write_cluster_outputs "$STAGE" "$CLUSTER_DETAILS_OUTPUT_FILE_PATH" "$VPC_ID"
fi
