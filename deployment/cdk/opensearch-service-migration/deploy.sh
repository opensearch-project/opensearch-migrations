#!/bin/bash

set -e

if [ -z "$1" ]; then
    echo "Error: contextId is required. Please pass it as the first argument to the script."
    echo "Usage: $0 <contextId>"
    exit 1
fi

contextId="$1"

export CDK_CLI_COMMAND=deploy
cdk deploy "*" --c contextId=$contextId --require-approval never --concurrency 51

StartPortForwardingSessionToRemoteHost 

aws ssm start-session \
  --region us-east-1 \
  --target i-029db10a2a80b8486 \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["10.212.227.96"],"portNumber":["80"],"localPortNumber":["8080"]}'



  aws cloudformation describe-stack-resources \
    --stack-name "$STACK_NAME" \
    --region "$REGION" \
    --output text

export REGION=us-east-1
## Stack name -> bootstrap box

  aws cloudformation describe-stack-resources \
    --stack-name "$STACK_NAME" \
    --region "$REGION" \
    --output json | jq '.StackResources[] | select(.ResourceType=="AWS::EC2::Instance").PhysicalResourceId' -r

## Stack name -> ecs cluster
export STACK_NAME=OSMigrations-dev2-us-east-1-MigrationConsole
  aws cloudformation describe-stack-resources \
    --stack-name "$STACK_NAME" \
    --region "$REGION" \
    --output json | jq '.StackResources[] | select(.ResourceType=="AWS::ECS::Service")' -r

## Find Tasks
aws ecs list-tasks \
    --cluster "arn:aws:ecs:us-east-1:336984078605:cluster/migration-dev2-ecs-cluster" \
    --region "$REGION" \
    --desired-status RUNNING \
    --output json

## Find private ip to forward
aws ecs describe-tasks \
   --cluster "arn:aws:ecs:us-east-1:336984078605:cluster/migration-dev2-ecs-cluster" \
   --tasks "arn:aws:ecs:us-east-1:336984078605:task/migration-dev2-ecs-cluster/aa6a6415880642519057ca7d1bb8ca2b" \
    --region "$REGION" \
    --output json | jq '.tasks[].attachments[].details[] | select(.name=="privateIPv4Address").value' -r