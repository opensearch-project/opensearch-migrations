#!/bin/bash

# WIP add relevant security group and switchover to Capture Proxy on port 9200 for source nodes
prepare_source_nodes_for_capture () {
  instance_ids=($(aws ec2 describe-instances --filters 'Name=tag:Name,Values=opensearch-infra-stack*' --query 'Reservations[*].Instances[*].InstanceId' --output text))
  kafka_sg_id=$(aws ssm get-parameter --name '/migration/int/default/mskAccessSecurityGroupId' --query 'Parameter.Value' --output text)
  for id in "${instance_ids[@]}"
  do
    echo "Key for array is: $id"
    group_ids=($(aws ec2 describe-instance-attribute --instance-id $id --attribute groupSet  --query 'Groups[*].GroupId' --output text))
    echo "${group_ids[*]}"

    match=false
    for group_id in "${group_ids[@]}"; do
        if [[ $group_id = "$kafka_sg_id" ]]; then
            match=true
            break
        fi
    done
    if [[ $match = false ]]; then
        group_ids+=("$kafka_sg_id")
    fi
    printf -v groupd_ids_string '%s ' "${group_ids[@]}"
    aws ec2 modify-instance-attribute --instance-id $id --groups $groupd_ids_string
    #aws ssm send-command --instance-ids "i-0e34183fd9ca06830" --document-name "AWS-RunShellScript" --parameters commands="cd /home/ec2-user/capture-proxy && ./startCaptureProxy.sh --help" --output text
    #aws ssm get-command-invocation --command-id 40d53994-48ae-48a3-873e-46c5407d0d14 --instance-id i-0e34183fd9ca06830 --output text
  done
}

# One-time account setup
#aws iam create-service-linked-role --aws-service-name opensearchservice.amazonaws.com
#aws iam create-service-linked-role --aws-service-name ecs.amazonaws.com

# One-time region setup
#cdk bootstrap

stage='aws-integ'
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
    "sourceClusterEndpoint": "<SOURCE_CLUSTER_ENDPOINT>"
}
EOM

git clone https://github.com/lewijacn/opensearch-cluster-cdk.git || echo "Repo already exists, skipping.."
cd opensearch-cluster-cdk && git checkout migration-es
git pull
npm install
# Deploy source cluster on EC2 instances
cdk deploy "*" --require-approval never --c suffix="Migration-Source" --c distVersion="7.10.2" --c distributionUrl="https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-oss-7.10.2-linux-x86_64.tar.gz" --c captureProxyEnabled=true --c captureProxyTarUrl="https://github.com/opensearch-project/opensearch-migrations/releases/download/1.0.0/trafficCaptureProxyServer.x86_64.tar" --c securityDisabled=true --c minDistribution=false --c cpuArch="x64" --c isInternal=true --c singleNodeCluster=false --c networkAvailabilityZones=2 --c dataNodeCount=2 --c managerNodeCount=0 --c serverAccessType="ipv4" --c restrictServerAccessTo="0.0.0.0/0"
source_endpoint=$(aws cloudformation describe-stacks --stack-name opensearch-infra-stack-Migration-Source --query "Stacks[0].Outputs[?OutputKey==\`loadbalancerurl\`].OutputValue" --output text)
echo $source_endpoint
vpc_id=$(aws cloudformation describe-stacks --stack-name opensearch-network-stack --query "Stacks[0].Outputs[?contains(OutputValue, 'vpc')].OutputValue" --output text)
echo $vpc_id

cdk_context=$(echo "${cdk_context/<VPC_ID>/$vpc_id}")
cdk_context=$(echo "${cdk_context/<SOURCE_CLUSTER_ENDPOINT>/$source_endpoint}")
cdk_context=$(echo $cdk_context | jq '@json')

cd ../../deployment/cdk/opensearch-service-migration
./buildDockerImages.sh
cdk deploy "*" --c aws-existing-source=$cdk_context --c contextId=aws-existing-source --require-approval never --concurrency 3
kafka_brokers=$(aws ssm get-parameter --name '/migration/int/default/mskBrokers' --query 'Parameter.Value' --output text)
kafka_sg_id=$(aws ssm get-parameter --name '/migration/int/default/mskAccessSecurityGroupId' --query 'Parameter.Value' --output text)

# Kickoff integration tests
#task_arn=$(aws ecs list-tasks --cluster migration-${stage}-ecs-cluster --family "migration-${stage}-migration-console" | jq --raw-output '.taskArns[0]')
#echo "aws ecs execute-command --cluster 'migration-${stage}-ecs-cluster' --task '${task_arn}' --container 'migration-console' --interactive --command '/bin/bash'"
#aws ecs execute-command --cluster "migration-${stage}-ecs-cluster" --task "${task_arn}" --container "migration-console" --interactive --command "./setupIntegTests.sh https://github.com/opensearch-project/opensearch-migrations.git main"