import os
import time
import boto3
import json
import subprocess

# Get Isengard credentials & set default region
# os.environ["AWS_DEFAULT_REGION"] = "us-east-1"
# Assuming you have set up your credentials elsewhere or using default provider chain

# Create boto3 clients
cloudformation_client = boto3.client("cloudformation")
ecs_client = boto3.client("ecs")
ssm_client = boto3.client("ssm")
logs_client = boto3.client("logs")

stage = "demo"
subprocess.run(["./awsE2ESolutionSetup.sh", "--stage", stage, "--skip-capture-proxy"], check=True)

source_stack_name = "opensearch-infra-stack-Migration-Source"
response = cloudformation_client.describe_stacks(StackName=source_stack_name)
outputs = response["Stacks"][0]["Outputs"]
source_url = next(output["OutputValue"] for output in outputs if output["OutputKey"] == "loadbalancerurl")
source_url = f"http://{source_url}:9200"

console_task_response = ecs_client.list_tasks(
    cluster=f"migration-{stage}-ecs-cluster",
    family=f"migration-{stage}-migration-console"
)
console_task_arn = console_task_response["taskArns"][0]

target_url = ssm_client.get_parameter(Name="/migration/demo/default/osClusterEndpoint")["Parameter"]["Value"]

# Run command to generate benchmark documents on source cluster from the migration console
response = ecs_client.execute_command(
    cluster=f"migration-{stage}-ecs-cluster",
    task=console_task_arn,
    container="migration-console",
    interactive=True,
    command=f"./runTestBenchmarks.sh --no-auth --endpoint {source_url}"
)
# Assuming this command returns immediately and does not require further processing

# Run catIndices to compare indices (should have lots of data on source and nothing on target)
response = ecs_client.execute_command(
    cluster=f"migration-{stage}-ecs-cluster",
    task=console_task_arn,
    container="migration-console",
    interactive=True,
    command=f"./catIndices.sh --source-no-auth --target-no-auth --source-endpoint {source_url}"
)
# Assuming this command returns immediately and does not require further processing

# Start RFS
ecs_client.update_service(
    cluster=f"migration-{stage}-ecs-cluster",
    service=f"migration-{stage}-reindex-from-snapshot",
    desiredCount=1
)
# add
# aws ecs wait services-stable --cluster "migration-${STAGE}-ecs-cluster" --service "migration-${STAGE}-traffic-replayer-default"


# Extract the start time from the response and convert to epoch time
response = ecs_client.describe_services(
    cluster=f"migration-{stage}-ecs-cluster",
    services=[f"migration-{stage}-reindex-from-snapshot"]
)
rfs_start_time = int(response["services"][0]["deployments"][0]["createdAt"].timestamp() * 1000)

# Loop every 15 seconds until we have at least one "Process is in idle mode" message
has_idled = False
while not has_idled:
    response = logs_client.filter_log_events(
        logGroupName=f"/migration/{stage}/default/reindex-from-snapshot",
        startTime=rfs_start_time,
        filterPattern="Process is in idle mode"
    )
    has_idled = len(response["events"]) > 0
    time.sleep(15)

# Run catIndices again (should have the same data on both)
response = ecs_client.execute_command(
    cluster=f"migration-{stage}-ecs-cluster",
    task=console_task_arn,
    container="migration-console",
    interactive=True,
    command=f"./catIndices.sh --source-no-auth --target-no-auth --source-endpoint {source_url}"
)
# Assuming this command returns immediately and does not require further processing

# Start sending continuous data
response = ecs_client.execute_command(
    cluster=f"migration-{stage}-ecs-cluster",
    task=console_task_arn,
    container="migration-console",
    interactive=True,
    command=f"./simpleDocumentGenerator.py --endpoint {source_url}"
)
# Assuming this command returns immediately and does not require further processing

# Start the replayer
ecs_client.update_service(
    cluster=f"migration-{stage}-ecs-cluster",
    service=f"migration-{stage}-traffic-replayer-default",
    desiredCount=1
)

# add
# aws ecs wait services-stable --cluster "migration-${STAGE}-ecs-cluster" --service "migration-${STAGE}-traffic-replayer-default"


# #TODO check existence of metrics

