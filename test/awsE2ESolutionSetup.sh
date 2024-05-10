#!/bin/bash

# Note: As this script will deploy an E2E solution in AWS, it assumes all the dependencies of the migration solution (e.g. aws cli, cdk cli,
# configured aws credentials, git, java, docker) as well as 'jq'

script_abs_path=$(readlink -f "$0")
TEST_DIR_PATH=$(dirname "$script_abs_path")
ROOT_REPO_PATH=$(dirname "$TEST_DIR_PATH")
TMP_DIR_PATH="$TEST_DIR_PATH/tmp"
EC2_SOURCE_CDK_PATH="$ROOT_REPO_PATH/test/opensearch-cluster-cdk"
MIGRATION_CDK_PATH="$ROOT_REPO_PATH/deployment/cdk/opensearch-service-migration"

# Note: This function is still in an experimental state
# Modify EC2 nodes to add required Kafka security group if it doesn't exist, as well as call the ./startCaptureProxy.sh
# script on each node which will detect if ES and the Capture Proxy are running and on the correct port, and attempt
# to mediate if this is not the case.
prepare_source_nodes_for_capture () {
  deploy_stage=$1
  instance_ids=($(aws ec2 describe-instances --filters 'Name=tag:Name,Values=opensearch-infra-stack*' 'Name=instance-state-name,Values=running' --query 'Reservations[*].Instances[*].InstanceId' --output text))
  kafka_brokers=$(aws ssm get-parameter --name "/migration/$deploy_stage/default/kafkaBrokers" --query 'Parameter.Value' --output text)
  # Substitute @ to be used instead of ',' for cases where ',' would disrupt formatting of arguments, i.e. AWS SSM commands
  kafka_brokers=$(echo "$kafka_brokers" | tr ',' '@')
  kafka_sg_id=$(aws ssm get-parameter --name "/migration/$deploy_stage/default/trafficStreamSourceAccessSecurityGroupId" --query 'Parameter.Value' --output text)
  otel_sg_id=$(aws ssm get-parameter --name "/migration/$deploy_stage/default/otelCollectorSGId" --query 'Parameter.Value' --output text)
  for id in "${instance_ids[@]}"
  do
    echo "Performing capture proxy source node setup for: $id"
    group_ids=($(aws ec2 describe-instance-attribute --instance-id $id --attribute groupSet  --query 'Groups[*].GroupId' --output text))
    kafka_sg_match=false
    otel_sg_match=false
    for group_id in "${group_ids[@]}"; do
        if [[ $group_id = "$kafka_sg_id" ]]; then
            kafka_sg_match=true
        fi
        if [[ $group_id = "$otel_sg_id" ]]; then
            otel_sg_match=true
        fi
    done
    if [ $kafka_sg_match = false ] || [ $otel_sg_match = false ]; then
      if [[ $kafka_sg_match = false ]]; then
          echo "Adding security group: $kafka_sg_id to node: $id"
          group_ids+=("$kafka_sg_id")
      fi
      if [[ $otel_sg_match = false ]]; then
          echo "Adding security group: $otel_sg_id to node: $id"
          group_ids+=("$otel_sg_id")
      fi
      printf -v group_ids_string '%s ' "${group_ids[@]}"
      aws ec2 modify-instance-attribute --instance-id $id --groups $group_ids_string
    fi
    start_proxy_command="./startCaptureProxy.sh --stage $deploy_stage --kafka-endpoints $kafka_brokers --git-url $MIGRATIONS_GIT_URL --git-branch $MIGRATIONS_GIT_BRANCH"
    echo "Executing $start_proxy_command on node: $id"
    command_id=$(aws ssm send-command --instance-ids "$id" --document-name "AWS-RunShellScript" --parameters commands="cd /home/ec2-user/capture-proxy && $start_proxy_command" --output text --query 'Command.CommandId')
    sleep 10
    command_status=$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$id" --output text --query 'Status')
    # TODO for multi-node setups, we should collect all command ids and allow to run in parallel
    while [ "$command_status" != "Success" ] && [ "$command_status" != "Failed" ] && [ "$command_status" != "TimedOut" ]
    do
      echo "Waiting for command to complete, current status is $command_status"
      sleep 60
      command_status=$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$id" --output text --query 'Status')
    done
    echo "Command has completed with status: $command_status, appending output"
    echo "Standard Output:"
    aws --no-cli-pager ssm get-command-invocation --command-id "$command_id" --instance-id "$id" --output text --query 'StandardOutputContent'
    echo "Standard Error:"
    aws --no-cli-pager ssm get-command-invocation --command-id "$command_id" --instance-id "$id" --output text --query 'StandardErrorContent'
  done
}

restore_and_record () {
  deploy_stage=$1
  source_lb_endpoint=$(aws cloudformation describe-stacks --stack-name opensearch-infra-stack-Migration-Source --query "Stacks[0].Outputs[?OutputKey==\`loadbalancerurl\`].OutputValue" --output text)
  source_endpoint="http://${source_lb_endpoint}:19200"
  kafka_brokers=$(aws ssm get-parameter --name "/migration/$deploy_stage/default/kafkaBrokers" --query 'Parameter.Value' --output text)
  console_task_arn=$(aws ecs list-tasks --cluster migration-${deploy_stage}-ecs-cluster --family "migration-${deploy_stage}-migration-console" | jq --raw-output '.taskArns[0]')

  # Print final doc counts and Kafka topic status
  unbuffer aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${console_task_arn}" --container "migration-console" --interactive --command "./catIndices.sh --source-endpoint $source_endpoint --source-no-auth --target-no-auth"
  unbuffer aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${console_task_arn}" --container "migration-console" --interactive --command "./kafka-tools/kafka/bin/kafka-consumer-groups.sh --bootstrap-server ${kafka_brokers} --timeout 100000 --describe --group logging-group-default --command-config kafka-tools/aws/msk-iam-auth.properties"

  # Turn off Replayer
  aws ecs update-service --cluster "migration-${deploy_stage}-ecs-cluster" --service "migration-${deploy_stage}-traffic-replayer-default" --desired-count 0 > /dev/null 2>&1
}

# One-time required service-linked-role creation for AWS accounts which do not have these roles
create_service_linked_roles () {
  aws iam create-service-linked-role --aws-service-name opensearchservice.amazonaws.com
  aws iam create-service-linked-role --aws-service-name ecs.amazonaws.com
}

clean_up_all () {
  cdk_context_var=$1
  vpc_id=$2
  default_sg=$(aws ec2 describe-security-groups --filters "Name=vpc-id,Values=$vpc_id" "Name=group-name,Values=default" --query "SecurityGroups[*].GroupId" --output text)
  instance_ids=($(aws ec2 describe-instances --filters 'Name=tag:Name,Values=opensearch-infra-stack*' 'Name=instance-state-name,Values=running' --query 'Reservations[*].Instances[*].InstanceId' --output text))
  # Revert source cluster back to default SG to remove added SGs
  for id in "${instance_ids[@]}"
  do
    echo "Removing security groups from node: $id"
    aws ec2 modify-instance-attribute --instance-id $id --groups $default_sg
  done

  cd "$MIGRATION_CDK_PATH" || exit
  cdk destroy "*" --force --c aws-existing-source=$cdk_context_var --c contextId=aws-existing-source
  cd "$EC2_SOURCE_CDK_PATH" || exit
  cdk destroy "*" --force --c suffix="Migration-Source" --c distVersion="7.10.2" --c distributionUrl="https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-oss-7.10.2-linux-x86_64.tar.gz" --c securityDisabled=true --c minDistribution=false --c cpuArch="x64" --c isInternal=true --c singleNodeCluster=false --c networkAvailabilityZones=2 --c dataNodeCount=2 --c managerNodeCount=0 --c serverAccessType="ipv4" --c restrictServerAccessTo="0.0.0.0/0" --c captureProxyEnabled=false
}

# One-time required CDK bootstrap setup for a given region. Only required if the 'CDKToolkit' CFN stack does not exist
bootstrap_region () {
  # Picking arbitrary context values to satisfy required values for CDK synthesis. These should not need to be kept in sync with the actual deployment context values
  cdk bootstrap --require-approval never --c suffix="Migration-Source" --c distVersion="7.10.2" --c distributionUrl="https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-oss-7.10.2-linux-x86_64.tar.gz" --c securityDisabled=true --c minDistribution=false --c cpuArch="x64" --c isInternal=true --c singleNodeCluster=false --c networkAvailabilityZones=2 --c dataNodeCount=2 --c managerNodeCount=0 --c serverAccessType="ipv4" --c restrictServerAccessTo="0.0.0.0/0" --c captureProxyEnabled=false
}

usage() {
  echo ""
  echo "Script to setup E2E AWS infrastructure for an ES 7.10.2 source cluster running on EC2, as well as "
  echo "an OpenSearch Service Domain as the target cluster, and the Migration infrastructure for simulating a migration from source to target."
  echo ""
  echo "Usage: "
  echo "  ./awsE2ESolutionSetup.sh <>"
  echo ""
  echo "Options:"
  echo "  --context-file                                   A file path for a given context file from which source and target context options will be used, default is './defaultCDKContext.json'."
  echo "  --source-context-id                              The CDK context block identifier within the context-file to use, default is 'source-single-node-ec2'."
  echo "  --migration-context-id                           The CDK context block identifier within the context-file to use, default is 'migration-default'."

  echo "  --migrations-git-url                             The Github http url used for building the capture proxy on setups with a dedicated source cluster, default is 'https://github.com/opensearch-project/opensearch-migrations.git'."
  echo "  --migrations-git-branch                          The Github branch associated with the 'git-url' to pull from, default is 'main'."
  echo "  --stage                                          The stage name to use for naming/grouping of AWS deployment resources, default is 'aws-integ'."
  echo "  --run-post-actions                               Flag to enable only running post test actions for cleaning up and recording a test run."
  echo "  --create-service-linked-roles                    Flag to create required service linked roles for the AWS account"
  echo "  --bootstrap-region                               Flag to CDK bootstrap the region to allow CDK deployments"
  echo "  --skip-capture-proxy                             Flag to skip setting up the Capture Proxy on source cluster nodes"
  echo "  --skip-source-deploy                             Flag to skip deploying the EC2 source cluster"
  echo "  --skip-migration-deploy                          Flag to skip deploying the Migration solution"
  echo "  --clean-up-all                                   Flag to remove all deployed CloudFormation resources"
  echo ""
  exit 1
}

STAGE='script-test'
RUN_POST_ACTIONS=false
CREATE_SLR=false
BOOTSTRAP_REGION=false
SKIP_CAPTURE_PROXY=false
SKIP_SOURCE_DEPLOY=false
SKIP_MIGRATION_DEPLOY=false
CONTEXT_FILE='./defaultCDKContext.json'
SOURCE_CONTEXT_ID='source-single-node-ec2'
MIGRATION_CONTEXT_ID='migration-default'
MIGRATIONS_GIT_URL='https://github.com/opensearch-project/opensearch-migrations.git'
MIGRATIONS_GIT_BRANCH='main'
CLEAN_UP_ALL=false

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
    --run-post-actions)
      RUN_POST_ACTIONS=true
      shift # past argument
      ;;
    --skip-capture-proxy)
      SKIP_CAPTURE_PROXY=true
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
    --context-file)
      MIGRATIONS_GIT_URL="$2"
      shift # past argument
      shift # past value
      ;;
    --source-context-id)
      MIGRATIONS_GIT_URL="$2"
      shift # past argument
      shift # past value
      ;;
    --migration-context-id)
      MIGRATIONS_GIT_URL="$2"
      shift # past argument
      shift # past value
      ;;
    --migrations-git-url)
      MIGRATIONS_GIT_URL="$2"
      shift # past argument
      shift # past value
      ;;
    --migrations-git-branch)
      MIGRATIONS_GIT_BRANCH="$2"
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

if [ "$RUN_POST_ACTIONS" = true ] ; then
  restore_and_record "$STAGE"
  exit 0
fi

if [ "$CREATE_SLR" = true ] ; then
  create_service_linked_roles
fi

mkdir -p "$TMP_DIR_PATH"
cp $CONTEXT_FILE "$TMP_DIR_PATH/generatedCDKContext.json"
sed -i -e "s/<STAGE>/$STAGE/g" "$TMP_DIR_PATH/generatedCDKContext.json"

if [ ! -d "opensearch-cluster-cdk" ]; then
  git clone https://github.com/lewijacn/opensearch-cluster-cdk.git
else
  echo "Repo already exists, skipping clone."
fi
cd opensearch-cluster-cdk && git pull && git checkout tanner-migration-testing
npm install
if [ "$BOOTSTRAP_REGION" = true ] ; then
  bootstrap_region
fi

if [ "$SKIP_SOURCE_DEPLOY" = false ] && [ "$CLEAN_UP_ALL" = false ] ; then
  # Deploy source cluster on EC2 instances
  cdk deploy "*" --c contextFile="$TMP_DIR_PATH/generatedCDKContext.json" --c contextId="$SOURCE_CONTEXT_ID" --require-approval never
  if [ $? -ne 0 ]; then
    echo "Error: deploy source cluster failed, exiting."
    exit 1
  fi
fi

source_endpoint=$(aws cloudformation describe-stacks --stack-name "opensearch-infra-stack-ec2-source-$STAGE" --query "Stacks[0].Outputs[?OutputKey==\`loadbalancerurl\`].OutputValue" --output text)
echo "Source endpoint: $source_endpoint"
vpc_id=$(aws cloudformation describe-stacks --stack-name "opensearch-network-stack-ec2-source-$STAGE" --query "Stacks[0].Outputs[?contains(OutputValue, 'vpc')].OutputValue" --output text)
echo "VPC ID: $vpc_id"

# Replace source specific placeholders
sed -i -e "s/<VPC_ID>/$vpc_id/g" "$TMP_DIR_PATH/generatedCDKContext.json"
sed -i -e "s/<SOURCE_CLUSTER_ENDPOINT>/$source_endpoint/g" "$TMP_DIR_PATH/generatedCDKContext.json"

if [ "$CLEAN_UP_ALL" = true ] ; then
  #TODO adjust for file
  clean_up_all "$cdk_context" "$vpc_id"
  exit 0
fi

if [ "$SKIP_MIGRATION_DEPLOY" = false ] ; then
  cd "$MIGRATION_CDK_PATH" || exit
  ./buildDockerImages.sh
  if [ $? -ne 0 ]; then
    echo "Error: building docker images failed, exiting."
    exit 1
  fi
  npm install
  cdk deploy "*" --c contextFile="$TMP_DIR_PATH/generatedCDKContext.json" --c contextId="$MIGRATION_CONTEXT_ID" --require-approval never --concurrency 3
  if [ $? -ne 0 ]; then
    echo "Error: deploying migration stacks failed, exiting."
    exit 1
  fi
fi

if [ "$SKIP_CAPTURE_PROXY" = false ] ; then
  prepare_source_nodes_for_capture "$STAGE"
  if [ $? -ne 0 ]; then
    echo "Error: enabling capture proxy on source cluster, exiting."
    exit 1
  fi
fi