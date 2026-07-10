#!/bin/bash

# Note: As this script will deploy an E2E solution in AWS, it assumes all the dependencies of the migration solution (e.g. aws cli, cdk cli,
# configured aws credentials, git, java, docker) as well as 'jq'

script_abs_path=$(readlink -f "$0")
TEST_DIR_PATH=$(dirname "$script_abs_path")
ROOT_REPO_PATH=$(dirname "$TEST_DIR_PATH")
TMP_DIR_PATH="$TEST_DIR_PATH/tmp"
EC2_SOURCE_CDK_PATH="$ROOT_REPO_PATH/test/opensearch-cluster-cdk"
MIGRATION_CDK_PATH="$ROOT_REPO_PATH/deployment/cdk/opensearch-service-migration"

validate_required_options () {
  suffix_required_value='ec2-source-<STAGE>'
  source_infra_suffix=$(jq ".[\"$SOURCE_CONTEXT_ID\"].suffix" "$SOURCE_GEN_CONTEXT_FILE" -r)
  source_network_suffix=$(jq ".[\"$SOURCE_CONTEXT_ID\"].networkStackSuffix" "$SOURCE_GEN_CONTEXT_FILE" -r)
  if [ "$source_infra_suffix" != "$suffix_required_value" ]; then
    echo "Error: source CDK context must include the 'suffix' option with a value of '$suffix_required_value', however found a value of '$source_infra_suffix', exiting."
    exit 1
  fi
  if [ "$source_network_suffix" != "$suffix_required_value" ]; then
    echo "Error: source CDK context must include the 'networkStackSuffix' option with a value of '$suffix_required_value', however found a value of '$source_network_suffix', exiting."
    exit 1
  fi
}

# One-time required service-linked-role creation for AWS accounts which do not have these roles, will ignore/fail if
# any of these roles already exist
create_service_linked_roles () {
  aws iam create-service-linked-role --aws-service-name opensearchservice.amazonaws.com
  aws iam create-service-linked-role --aws-service-name ecs.amazonaws.com
  aws iam create-service-linked-role --aws-service-name osis.amazonaws.com
}

clean_up_migration () {
  # Destroy the migration CDK app ("*" in its cdk.json) and block until all of
  # its CloudFormation stacks are fully deleted. `npx cdk destroy` is already
  # synchronous (it polls CFN until DELETE_COMPLETE), but we also explicitly
  # wait afterwards so that any stack cdk couldn't see (e.g. out-of-band
  # resources left behind by a prior partial deploy) still holds up the
  # source destroy that follows.
  cd "$MIGRATION_CDK_PATH" || exit
  # `cdk destroy` goes through the app entrypoint (`npx ts-node bin/app.ts`),
  # which requires the migration CDK's node_modules to be installed so the
  # `source-map-support/register` side-effect import (and its type decls)
  # resolves. In the full deploy flow `npm ci` runs before `cdk deploy`, but
  # the --clean-up-migration-only short-circuit skips that path, so install
  # deps here unconditionally. `npm ci` is cheap relative to the destroy.
  npm ci
  echo "Destroying migration CDK app stacks..."
  CDK_SKIP_LOCAL_IMAGE_HASH=true npx cdk destroy "*" --force --c contextFile="$MIGRATION_GEN_CONTEXT_FILE" --c contextId="$MIGRATION_CONTEXT_ID"
  local cdk_rc=$?
  if [ $cdk_rc -ne 0 ]; then
    echo "Error: cdk destroy for migration stacks exited with code $cdk_rc. Aborting before the subsequent source-CDK destroy (i.e. the EC2 source cluster teardown) so that failure is visible rather than masked by a best-effort source destroy."
    exit $cdk_rc
  fi

  # Defensive: wait for any lingering migration stacks to finish deleting
  # before continuing. The migration app prefixes stacks with "OSMigrations-<stage>".
  local migration_stack_prefix="OSMigrations-${STAGE}"
  local leftover
  leftover=$(aws cloudformation list-stacks \
      --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE UPDATE_ROLLBACK_COMPLETE ROLLBACK_COMPLETE DELETE_FAILED DELETE_IN_PROGRESS CREATE_IN_PROGRESS UPDATE_IN_PROGRESS \
      --query "StackSummaries[?starts_with(StackName, \`${migration_stack_prefix}\`)].StackName" \
      --output text)
  if [ -n "$leftover" ]; then
    echo "Waiting for lingering migration stacks to reach DELETE_COMPLETE: $leftover"
    # `aws cloudformation wait stack-delete-complete` is bounded, not indefinite:
    # it polls every 30s for at most 120 attempts (~1 hour) and then exits
    # non-zero, which we propagate below. Jenkins also wraps this stage in its
    # own 1-hour timeout, so worst case we surface a bounded failure rather
    # than hanging the pipeline.
    for s in $leftover; do
      aws cloudformation wait stack-delete-complete --stack-name "$s" || {
        echo "Error: stack $s did not reach DELETE_COMPLETE."
        exit 1
      }
    done
  fi
  echo "Migration CDK app destroy complete."
}

clean_up_source () {
  vpc_id=$1
  if [ -n "$vpc_id" ] && [ "$vpc_id" != "None" ]; then
    default_sg=$(aws ec2 describe-security-groups --filters "Name=vpc-id,Values=$vpc_id" "Name=group-name,Values=default" --query "SecurityGroups[*].GroupId" --output text)
    instance_ids=($(aws ec2 describe-instances --filters "Name=tag:Name,Values=$SOURCE_INFRA_STACK_NAME/*" 'Name=instance-state-name,Values=running' --query 'Reservations[*].Instances[*].InstanceId' --output text))
    # Revert source cluster back to default SG to remove added SGs
    for id in "${instance_ids[@]}"
    do
      echo "Removing security groups from node: $id"
      aws ec2 modify-instance-attribute --instance-id $id --groups $default_sg
    done
  fi

  cd "$EC2_SOURCE_CDK_PATH" || exit
  echo "Destroying source CDK app stacks..."
  npx cdk destroy "*" --force --c contextFile="$SOURCE_GEN_CONTEXT_FILE" --c contextId="$SOURCE_CONTEXT_ID"
  local cdk_rc=$?
  if [ $cdk_rc -ne 0 ]; then
    echo "Error: cdk destroy for source stacks exited with code $cdk_rc."
    exit $cdk_rc
  fi
  echo "Source CDK app destroy complete."
}

clean_up_all () {
  vpc_id=$1
  clean_up_migration
  clean_up_source "$vpc_id"
}

# One-time required CDK bootstrap setup for a given region. Only required if the 'CDKToolkit' CFN stack does not exist
bootstrap_region () {
  # Picking arbitrary context values to satisfy required values for CDK synthesis. These should not need to be kept in sync with the actual deployment context values
  npx cdk bootstrap --require-approval never --c contextFile="$SOURCE_GEN_CONTEXT_FILE" --c contextId="$SOURCE_CONTEXT_ID"
}

usage() {
  echo ""
  echo "Script to setup E2E AWS infrastructure for simulating a test environment with a source cluster on EC2, the "
  echo "opensearch-migrations tooling, and a target cluster. The source cluster, migration tooling, and target cluster "
  echo "can all be customized by use of CDK context in the provided 'context-file' option"
  echo ""
  echo "The following placeholder values are replaced before a source deployment: <STAGE>, and the following"
  echo "placeholder values are replaced after a source deployment: <SOURCE_CLUSTER_ENDPOINT> <VPC_ID>"
  echo ""
  echo "Usage: "
  echo "  ./awsE2ESolutionSetup.sh <>"
  echo ""
  echo "Options:"
  echo "  --source-context-file                            A file path for a given context file from which source context options will be used, default is './defaultSourceContext.json'."
  echo "  --migration-context-file                         A file path for a given context file from which migration infrastructure and target context options will be used, default is './defaultMigrationContext.json'."
  echo "  --source-context-id                              The CDK context block identifier within the context-file to use, default is 'source-single-node-ec2'."
  echo "  --migration-context-id                           The CDK context block identifier within the context-file to use, default is 'migration-default'."
  echo "  --stage                                          The stage name to use for naming/grouping of AWS deployment resources, default is 'aws-integ'."
  echo "  --create-service-linked-roles                    Flag to create required service linked roles for the AWS account"
  echo "  --bootstrap-region                               Flag to CDK bootstrap the region to allow CDK deployments"
  echo "  --skip-source-deploy                             Flag to skip deploying the EC2 source cluster"
  echo "  --skip-migration-deploy                          Flag to skip deploying the Migration solution"
  echo "  --clean-up-all                                   Flag to remove all deployed CloudFormation resources (migration first, then source)"
  echo "  --clean-up-migration-only                        Flag to destroy ONLY the migration CDK app stacks (waits for DELETE_COMPLETE)"
  echo "  --clean-up-source-only                           Flag to destroy ONLY the source (EC2) CDK app stacks"
  echo ""
  exit 1
}

STAGE='aws-integ'
CREATE_SLR=false
BOOTSTRAP_REGION=false
SKIP_SOURCE_DEPLOY=false
SKIP_MIGRATION_DEPLOY=false
SOURCE_CONTEXT_FILE='./defaultSourceContext.json'
MIGRATION_CONTEXT_FILE='./defaultMigrationContext.json'
SOURCE_CONTEXT_ID='source-single-node-ec2'
MIGRATION_CONTEXT_ID='migration-default'
CLEAN_UP_ALL=false
CLEAN_UP_MIGRATION_ONLY=false
CLEAN_UP_SOURCE_ONLY=false

while [[ $# -gt 0 ]]; do
  case $1 in
    --create-service-linked-roles)
      CREATE_SLR=true
      shift # past argument
      ;;
    --bootstrap-region)
      BOOTSTRAP_REGION=true
      shift # past argument
      ;;
    --skip-source-deploy)
      SKIP_SOURCE_DEPLOY=true
      shift # past argument
      ;;
    --skip-migration-deploy)
      SKIP_MIGRATION_DEPLOY=true
      shift # past argument
      ;;
    --source-context-file)
      SOURCE_CONTEXT_FILE="$2"
      shift # past argument
      shift # past value
      ;;
    --migration-context-file)
      MIGRATION_CONTEXT_FILE="$2"
      shift # past argument
      shift # past value
      ;;
    --source-context-id)
      SOURCE_CONTEXT_ID="$2"
      shift # past argument
      shift # past value
      ;;
    --migration-context-id)
      MIGRATION_CONTEXT_ID="$2"
      shift # past argument
      shift # past value
      ;;
    --stage)
      STAGE="$2"
      shift # past argument
      shift # past value
      ;;
    --clean-up-all)
      CLEAN_UP_ALL=true
      shift # past argument
      ;;
    --clean-up-migration-only)
      CLEAN_UP_MIGRATION_ONLY=true
      shift # past argument
      ;;
    --clean-up-source-only)
      CLEAN_UP_SOURCE_ONLY=true
      shift # past argument
      ;;
    -h|--h|--help)
      usage
      ;;
    -*)
      echo "Unknown option $1"
      usage
      ;;
    *)
      shift # past argument
      ;;
  esac
done

# Mutually exclusive cleanup flags: --clean-up-all is a superset of the other
# two, so combining them is almost certainly a caller mistake. Reject early
# rather than silently letting --clean-up-all win further down.
if [ "$CLEAN_UP_ALL" = true ] && { [ "$CLEAN_UP_MIGRATION_ONLY" = true ] || [ "$CLEAN_UP_SOURCE_ONLY" = true ]; }; then
  echo "Error: --clean-up-all cannot be combined with --clean-up-migration-only or --clean-up-source-only"
  exit 1
fi
if [ "$CLEAN_UP_MIGRATION_ONLY" = true ] && [ "$CLEAN_UP_SOURCE_ONLY" = true ]; then
  echo "Error: --clean-up-migration-only and --clean-up-source-only cannot be combined; omit both or pass --clean-up-all"
  exit 1
fi

SOURCE_NETWORK_STACK_NAME="opensearch-network-stack-ec2-source-$STAGE"
SOURCE_INFRA_STACK_NAME="opensearch-infra-stack-ec2-source-$STAGE"
SOURCE_GEN_CONTEXT_FILE="$TMP_DIR_PATH/generatedSourceContext.json"
MIGRATION_GEN_CONTEXT_FILE="$TMP_DIR_PATH/generatedMigrationContext.json"

if [ "$CREATE_SLR" = true ] ; then
  create_service_linked_roles
fi

# Replace preliminary placeholders in CDK context into a generated context file
mkdir -p "$TMP_DIR_PATH"
cp $SOURCE_CONTEXT_FILE "$SOURCE_GEN_CONTEXT_FILE"
cp $MIGRATION_CONTEXT_FILE "$MIGRATION_GEN_CONTEXT_FILE"
validate_required_options
sed -i -e "s/<STAGE>/$STAGE/g" "$SOURCE_GEN_CONTEXT_FILE"
sed -i -e "s/<STAGE>/$STAGE/g" "$MIGRATION_GEN_CONTEXT_FILE"

# Short-circuit for --clean-up-migration-only: don't touch the source CDK tree at all,
# just destroy the migration app stacks and wait for their CFN delete to complete.
# The migration CDK app still synths on destroy, so resolve <VPC_ID> and
# <SOURCE_CLUSTER_ENDPOINT> the same way the full path does (via CFN outputs of
# the source stacks if they still exist), falling back to dummy values when the
# source side has already been destroyed -- cdk destroy doesn't actually hit the
# endpoint/vpc, it only needs synth to succeed so it can match CFN stack names.
if [ "$CLEAN_UP_MIGRATION_ONLY" = true ] ; then
  source_endpoint=$(aws cloudformation describe-stacks --stack-name "$SOURCE_INFRA_STACK_NAME" --query "Stacks[0].Outputs[?OutputKey==\`loadbalancerurl\`].OutputValue" --output text 2>/dev/null || echo "")
  if [ -z "$source_endpoint" ] || [ "$source_endpoint" = "None" ]; then
    source_endpoint="placeholder-source-endpoint.invalid"
  fi
  vpc_id=$(aws cloudformation describe-stacks --stack-name "$SOURCE_NETWORK_STACK_NAME" --query "Stacks[0].Outputs[?contains(OutputValue, 'vpc')].OutputValue | [0]" --output text 2>/dev/null || echo "")
  if [ -z "$vpc_id" ] || [ "$vpc_id" = "None" ]; then
    # CDK sentinel: Vpc.fromLookup returns a dummy VPC with id "vpc-12345"
    # on cache miss; network-stack.ts explicitly short-circuits validation
    # when it sees that id (see NetworkSetup ctor).
    vpc_id="vpc-12345"
  fi
  sed -i -e "s/<VPC_ID>/$vpc_id/g" "$MIGRATION_GEN_CONTEXT_FILE"
  sed -i -e "s/<SOURCE_CLUSTER_ENDPOINT>/http:\/\/${source_endpoint}:9200/g" "$MIGRATION_GEN_CONTEXT_FILE"
  clean_up_migration
  exit 0
fi

if [ ! -d "opensearch-cluster-cdk" ]; then
  git clone https://github.com/lewijacn/opensearch-cluster-cdk.git
else
  echo "Repo already exists, skipping clone."
fi
cd opensearch-cluster-cdk && git pull && git checkout migration-es && git pull
npm ci
if [ "$BOOTSTRAP_REGION" = true ] ; then
  bootstrap_region
fi

# Short-circuit for --clean-up-source-only: destroy the source CDK app stacks and exit.
# vpc_id is best-effort here; if the source network stack is already gone we pass
# an empty value and clean_up_source skips the SG-revert loop.
if [ "$CLEAN_UP_SOURCE_ONLY" = true ] ; then
  vpc_id=$(aws cloudformation describe-stacks --stack-name "$SOURCE_NETWORK_STACK_NAME" --query "Stacks[0].Outputs[?contains(OutputValue, 'vpc')].OutputValue | [0]" --output text 2>/dev/null || echo "")
  clean_up_source "$vpc_id"
  exit 0
fi

if [ "$SKIP_SOURCE_DEPLOY" = false ] && [ "$CLEAN_UP_ALL" = false ] ; then
  # Deploy source cluster on EC2 instances
  npx cdk deploy "*" --c contextFile="$SOURCE_GEN_CONTEXT_FILE" --c contextId="$SOURCE_CONTEXT_ID" --require-approval never
  if [ $? -ne 0 ]; then
    echo "Error: deploy source cluster failed, exiting."
    exit 1
  fi
fi

source_endpoint=$(aws cloudformation describe-stacks --stack-name "$SOURCE_INFRA_STACK_NAME" --query "Stacks[0].Outputs[?OutputKey==\`loadbalancerurl\`].OutputValue" --output text)
echo "Source endpoint: $source_endpoint"
vpc_id=$(aws cloudformation describe-stacks --stack-name "$SOURCE_NETWORK_STACK_NAME" --query "Stacks[0].Outputs[?contains(OutputValue, 'vpc')].OutputValue | [0]" --output text)
echo "VPC ID: $vpc_id"

# Replace source dependent placeholders in CDK context
sed -i -e "s/<VPC_ID>/$vpc_id/g" "$MIGRATION_GEN_CONTEXT_FILE"
sed -i -e "s/<SOURCE_CLUSTER_ENDPOINT>/http:\/\/${source_endpoint}:9200/g" "$MIGRATION_GEN_CONTEXT_FILE"

if [ "$CLEAN_UP_ALL" = true ] ; then
  clean_up_all "$vpc_id"
  exit 0
fi

if [ "$SKIP_MIGRATION_DEPLOY" = false ] ; then
  # Build Docker images into local daemon for CDK's DockerImageAsset
  "$MIGRATION_CDK_PATH/buildDockerImages.sh"

  cd "$MIGRATION_CDK_PATH" || exit
  npm ci
  npx cdk deploy "*" --c contextFile="$MIGRATION_GEN_CONTEXT_FILE" --c contextId="$MIGRATION_CONTEXT_ID" --require-approval never --concurrency 3
  if [ $? -ne 0 ]; then
    echo "Error: deploying migration stacks failed, exiting."
    exit 1
  fi
fi
