#!/bin/bash

# Note: As this script will deploy an E2E solution in AWS, it assumes all the dependencies of the migration solution (e.g. aws cli, cdk cli,
# configured aws credentials, git, java, docker) as well as 'jq'


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
  for id in "${instance_ids[@]}"
  do
    echo "Performing capture proxy source node setup for: $id"
    group_ids=($(aws ec2 describe-instance-attribute --instance-id $id --attribute groupSet  --query 'Groups[*].GroupId' --output text))
    match=false
    for group_id in "${group_ids[@]}"; do
        if [[ $group_id = "$kafka_sg_id" ]]; then
            match=true
            break
        fi
    done
    if [[ $match = false ]]; then
      echo "Adding security group: $kafka_sg_id to node: $id"
      group_ids+=("$kafka_sg_id")
      printf -v group_ids_string '%s ' "${group_ids[@]}"
      aws ec2 modify-instance-attribute --instance-id $id --groups $group_ids_string
    fi
    echo "Executing ./startCaptureProxy.sh --kafka-endpoints $kafka_brokers on node: $id. Attempting to append output of command: "
    command_id=$(aws ssm send-command --instance-ids "$id" --document-name "AWS-RunShellScript" --parameters commands="cd /home/ec2-user/capture-proxy && ./startCaptureProxy.sh --kafka-endpoints $kafka_brokers" --output text --query 'Command.CommandId')
    sleep 5
    echo "Standard Output:"
    aws ssm get-command-invocation --command-id "$command_id" --instance-id "$id" --output text --query 'StandardOutputContent'
    echo "Standard Error:"
    aws ssm get-command-invocation --command-id "$command_id" --instance-id "$id" --output text --query 'StandardErrorContent'
  done
}

# One-time required service-linked-role creation for AWS accounts which do not have these roles
create_service_linked_roles () {
  aws iam create-service-linked-role --aws-service-name opensearchservice.amazonaws.com
  aws iam create-service-linked-role --aws-service-name ecs.amazonaws.com
}

# One-time required CDK bootstrap setup for a given region. Only required if the 'CDKToolkit' CFN stack does not exist
bootstrap_region () {
  # Picking arbitrary context values to satisfy required values for CDK synthesis. These should not need to be kept in sync with the actual deployment context values
  cdk bootstrap "*" --require-approval never --c suffix="Migration-Source" --c distVersion="7.10.2" --c distributionUrl="https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-oss-7.10.2-linux-x86_64.tar.gz" --c securityDisabled=true --c minDistribution=false --c cpuArch="x64" --c isInternal=true --c singleNodeCluster=false --c networkAvailabilityZones=2 --c dataNodeCount=2 --c managerNodeCount=0 --c serverAccessType="ipv4" --c restrictServerAccessTo="0.0.0.0/0" --c captureProxyEnabled=false
}

usage() {
  echo ""
  echo "Script to setup E2E AWS infrastructure for an ES 7.10.2 source cluster running on EC2, as well as "
  echo "an OpenSearch Service Domain as the target cluster, and the Migration infrastructure for simulating a migration from source to target."
  echo ""
  echo "Usage: "
  echo "  ./awsE2ESolutionSetup.sh [--create-service-linked-roles] [--bootstrap-region] [--enable-capture-proxy]"
  echo ""
  echo "Options:"
  echo "  --create-service-linked-roles                    If included, will attempt to create required service linked roles for the AWS account"
  echo "  --bootstrap-region                               If included, will attempt to CDK bootstrap the region to allow CDK deployments"
  echo "  --enable-capture-proxy                           If included, will attempt to enable the capture proxy on all source nodes"
  echo ""
  exit 1
}

stage='aws-integ'
CREATE_SLR=false
BOOTSTRAP_REGION=false
ENABLE_CAPTURE_PROXY=false
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
    --enable-capture-proxy)
      ENABLE_CAPTURE_PROXY=true
      shift # past argument
      ;;
    -h|--help)
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

if [ "$CREATE_SLR" = true ] ; then
  create_service_linked_roles
fi

# TODO allow spaces in CDK context block when passing as CLI param
# Store CDK context for migration solution deployment in variable
read -r -d '' cdk_context << EOM
{
    "stage": "$stage",
    "vpcId": "<VPC_ID>",
    "engineVersion": "OS_2.9",
    "domainName": "opensearch-cluster-aws-integ",
    "dataNodeCount": 2,
    "availabilityZoneCount": 2,
    "mskBrokerNodeCount": 2,
    "openAccessPolicyEnabled": true,
    "domainRemovalPolicy": "DESTROY",
    "migrationAnalyticsServiceEnabled": false,
    "fetchMigrationEnabled": true,
    "sourceClusterEndpoint": "<SOURCE_CLUSTER_ENDPOINT>",
    "dpPipelineTemplatePath": "../../../test/dp_pipeline_aws_integ.yaml"
}
EOM

git clone https://github.com/lewijacn/opensearch-cluster-cdk.git || echo "Repo already exists, skipping.."
cd opensearch-cluster-cdk && git checkout migration-es
git pull
npm install
if [ "$BOOTSTRAP_REGION" = true ] ; then
  bootstrap_region
fi
# Deploy source cluster on EC2 instances
cdk deploy "*" --require-approval never --c suffix="Migration-Source" --c distVersion="7.10.2" --c distributionUrl="https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-oss-7.10.2-linux-x86_64.tar.gz" --c captureProxyEnabled=true --c captureProxyTarUrl="https://github.com/opensearch-project/opensearch-migrations/releases/download/1.0.0/trafficCaptureProxyServer.x86_64.tar" --c securityDisabled=true --c minDistribution=false --c cpuArch="x64" --c isInternal=true --c singleNodeCluster=true --c networkAvailabilityZones=2 --c dataNodeCount=2 --c managerNodeCount=0 --c serverAccessType="ipv4" --c restrictServerAccessTo="0.0.0.0/0"
source_endpoint=$(aws cloudformation describe-stacks --stack-name opensearch-infra-stack-Migration-Source --query "Stacks[0].Outputs[?OutputKey==\`loadbalancerurl\`].OutputValue" --output text)
echo $source_endpoint
vpc_id=$(aws cloudformation describe-stacks --stack-name opensearch-network-stack --query "Stacks[0].Outputs[?contains(OutputValue, 'vpc')].OutputValue" --output text)
echo $vpc_id

cdk_context=$(echo "${cdk_context/<VPC_ID>/$vpc_id}")
cdk_context=$(echo "${cdk_context/<SOURCE_CLUSTER_ENDPOINT>/http://${source_endpoint}}")
cdk_context=$(echo $cdk_context | jq '@json')

cd ../../deployment/cdk/opensearch-service-migration
./buildDockerImages.sh
npm install
cdk deploy "*" --c aws-existing-source=$cdk_context --c contextId=aws-existing-source --require-approval never --concurrency 3
if [ "$ENABLE_CAPTURE_PROXY" = true ] ; then
  prepare_source_nodes_for_capture $stage
fi

# Kickoff integration tests
#task_arn=$(aws ecs list-tasks --cluster migration-${stage}-ecs-cluster --family "migration-${stage}-migration-console" | jq --raw-output '.taskArns[0]')
#echo "aws ecs execute-command --cluster 'migration-${stage}-ecs-cluster' --task '${task_arn}' --container 'migration-console' --interactive --command '/bin/bash'"
#aws ecs execute-command --cluster "migration-${stage}-ecs-cluster" --task "${task_arn}" --container "migration-console" --interactive --command "./setupIntegTests.sh https://github.com/opensearch-project/opensearch-migrations.git main"