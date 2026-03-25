#!/bin/bash

# Example usage: ./accessContainer.sh migration-console dev us-east-1

export AWS_RETRY_MODE="${AWS_RETRY_MODE:-adaptive}"
export AWS_MAX_ATTEMPTS="${AWS_MAX_ATTEMPTS:-10}"

service_name=$1
stage=$2
region=$3

export AWS_DEFAULT_REGION=$region

task_arn=$(aws ecs list-tasks --cluster migration-${stage}-ecs-cluster --family "migration-${stage}-${service_name}" | jq --raw-output '.taskArns[0]')

aws ecs execute-command --cluster "migration-${stage}-ecs-cluster" --task "${task_arn}" --container "${service_name}" --interactive --command "/bin/bash"