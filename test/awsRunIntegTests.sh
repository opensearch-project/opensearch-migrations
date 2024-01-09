#!/bin/bash

usage() {
  echo ""
  echo "Script to run integrations tests on AWS Migration Console"
  echo ""
  echo "Usage: "
  echo "  ./awsRunIntegTests.sh [--migrations-git-url] [--migrations-git-branch] [--stage]"
  echo ""
  echo "Options:"
  echo "  --migrations-git-url                             The Github http url used for pulling the integration tests onto the migration console, default is 'https://github.com/opensearch-project/opensearch-migrations.git'."
  echo "  --migrations-git-branch                          The Github branch associated with the 'git-url' to pull from, default is 'main'."
  echo "  --stage                                          The stage used for CDK deployment, default is 'aws-integ'."
  echo ""
  exit 1
}

STAGE='aws-integ'
MIGRATIONS_GIT_URL='https://github.com/opensearch-project/opensearch-migrations.git'
MIGRATIONS_GIT_BRANCH='main'

while [[ $# -gt 0 ]]; do
  case $1 in
    --stage)
      STAGE="$2"
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

task_arn=$(aws ecs list-tasks --cluster migration-${STAGE}-ecs-cluster --family "migration-${STAGE}-migration-console" | jq --raw-output '.taskArns[0]')
# Delete and re-create topic
kafka_brokers=$(aws ssm get-parameter --name "/migration/${STAGE}/default/kafkaBrokers" --query 'Parameter.Value' --output text)
unbuffer aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${task_arn}" --container "migration-console" --interactive --command "./kafka-tools/kafka/bin/kafka-topics.sh --bootstrap-server ${kafka_brokers} --delete --topic logging-traffic-topic --command-config kafka-tools/aws/msk-iam-auth.properties"
echo "Done deleting 'logging-traffic-topic'"
unbuffer aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${task_arn}" --container "migration-console" --interactive --command "./kafka-tools/kafka/bin/kafka-topics.sh --bootstrap-server ${kafka_brokers} --create --topic logging-traffic-topic --command-config kafka-tools/aws/msk-iam-auth.properties"
echo "Done creating 'logging-traffic-topic'"

# Remove all non-system indices
source_lb_endpoint=$(aws cloudformation describe-stacks --stack-name opensearch-infra-stack-Migration-Source --query "Stacks[0].Outputs[?OutputKey==\`loadbalancerurl\`].OutputValue" --output text)
source_endpoint="http://${source_lb_endpoint}:19200"
proxy_endpoint="http://${source_lb_endpoint}:9200"
target_endpoint=$(aws ssm get-parameter --name "/migration/${STAGE}/default/osClusterEndpoint" --query 'Parameter.Value' --output text)
echo "Clearing non-system source indices"
unbuffer aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${task_arn}" --container "migration-console" --interactive --command "curl -XDELETE ${source_endpoint}/*,-.*,-searchguard*,-sg7*"
echo "Clearing non-system target indices"
unbuffer aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${task_arn}" --container "migration-console" --interactive --command "curl -XDELETE ${target_endpoint}/*,-.*"

# Spin up Replayer container and wait set time for service to get started
aws ecs update-service --cluster "migration-${STAGE}-ecs-cluster" --service "migration-${STAGE}-traffic-replayer-default" --desired-count 1
# TODO: Poll with AWS CLI call till service is running
sleep 60

# Kickoff integration tests
echo "aws ecs execute-command --cluster 'migration-${STAGE}-ecs-cluster' --task '${task_arn}' --container 'migration-console' --interactive --command '/bin/bash'"
unbuffer aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${task_arn}" --container "migration-console" --interactive --command "./setupIntegTests.sh ${MIGRATIONS_GIT_URL} ${MIGRATIONS_GIT_BRANCH} ${source_endpoint} ${proxy_endpoint}"