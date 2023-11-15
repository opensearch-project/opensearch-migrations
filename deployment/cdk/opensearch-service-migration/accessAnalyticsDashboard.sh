#!/bin/bash

# Example usage: ./accessAnalyticsDashboard.sh dev us-east-1

stage=$1
region=$2

export AWS_DEFAULT_REGION=$region

bastion_id=$(aws ec2 describe-instances --filters Name=instance-state-name,Values=running Name=tag-key,Values=migration_deployment Name=tag:Name,Values=BastionHost Name=tag:aws:cloudformation:stack-name,Values=OSMigrations-${stage}-${region}-MigrationAnalytics | jq --raw-output '.Reservations[0].Instances[0].InstanceId')

domain_endpoint=$(aws opensearch describe-domains --domain-names migration-analytics-domain | jq --raw-output '.DomainStatusList[0].Endpoints.vpc')

JSON_STRING=$( jq -n -c\
                  --arg port "443" \
                  --arg localPort "8157" \
                  --arg host "$domain_endpoint" \
                  '{portNumber: [$port], localPortNumber: [$localPort], host: [$host]}' )

echo "Access the Analytics Dashboard at https://localhost:8157/_dashboards"

aws ssm start-session --target $bastion_id --document-name AWS-StartPortForwardingSessionToRemoteHost --parameters "${JSON_STRING}"