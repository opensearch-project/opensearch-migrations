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
    --query "Stacks[0].Outputs[?contains(OutputValue, 'vpc')].OutputValue | [0]" \
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

SAMPLE_CDK_REPO="https://github.com/aws-samples/amazon-opensearch-service-sample-cdk.git"
SAMPLE_CDK_VERSION=$(git ls-remote --tags --sort=-v:refname "$SAMPLE_CDK_REPO" "v0.1.*" | head -n1 | sed 's/.*refs\/tags\///')
if [[ -z "$SAMPLE_CDK_VERSION" ]]; then
  echo "Error: Could not discover latest v0.1.x tag from $SAMPLE_CDK_REPO"
  exit 1
fi
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

# Wait for any leftover OpenSearch domains from a previous run to finish deleting.
# The CDK VPC-validation Lambda will reject deployment if a domain with the same name
# still exists in a different VPC (e.g. from a prior run whose cleanup is still in progress).
cluster_ids=$(jq -r '.clusters[].clusterId' "$PROVIDED_CONTEXT_FILE_PATH")
for cid in $cluster_ids; do
  domain_name="cluster-${STAGE}-${cid}"
  if aws opensearch describe-domain --domain-name "$domain_name" >/dev/null 2>&1; then
    if aws opensearch describe-domain --domain-name "$domain_name" \
         --query 'DomainStatus.Deleted' --output text 2>/dev/null | grep -qi true; then
      echo "Domain '$domain_name' is still deleting — waiting up to 30 minutes..."
      for i in $(seq 1 60); do
        if ! aws opensearch describe-domain --domain-name "$domain_name" >/dev/null 2>&1; then
          echo "Domain '$domain_name' has been fully deleted."
          break
        fi
        sleep 30
      done
      # Final check
      if aws opensearch describe-domain --domain-name "$domain_name" >/dev/null 2>&1; then
        echo "ERROR: Domain '$domain_name' still exists after waiting. Aborting."
        exit 1
      fi
    else
      echo "WARNING: Domain '$domain_name' exists and is NOT being deleted. CDK deploy may fail if VPC differs."
    fi
  fi
done

# Delete any ROLLBACK_COMPLETE stacks from a prior failed deploy — CDK cannot update these.
rollback_stacks=$(aws cloudformation list-stacks \
  --stack-status-filter ROLLBACK_COMPLETE \
  --query "StackSummaries[?contains(StackName, '${STAGE}')].StackName" \
  --output text 2>/dev/null || true)
for stack in $rollback_stacks; do
  echo "Deleting ROLLBACK_COMPLETE stack: $stack"
  aws cloudformation delete-stack --stack-name "$stack"
  aws cloudformation wait stack-delete-complete --stack-name "$stack"
done

cd amazon-opensearch-service-sample-cdk
cdk deploy "*" --require-approval never --concurrency 3

CLUSTER_DETAILS_OUTPUT_FILE_PATH="$ROOT_REPO_PATH/test/tmp/cluster-details-${STAGE}.json"
write_cluster_outputs "$STAGE" "$CLUSTER_DETAILS_OUTPUT_FILE_PATH"
