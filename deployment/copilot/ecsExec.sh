#!/bin/bash

# Example usage: ./ecsExec.sh migration-console dev us-east-1

service_name=$1
stage=$2
region=$3

export AWS_DEFAULT_REGION=$region

task_arn=$(aws ecs list-tasks --cluster migration-${stage}-ecs-cluster --family "migration-${stage}-${service_name}" | jq --raw-output '.taskArns[0]')

aws ecs execute-command --cluster "migration-${stage}-ecs-cluster" --task "${task_arn}" --container "${service_name}" --interactive --command "/bin/bash"