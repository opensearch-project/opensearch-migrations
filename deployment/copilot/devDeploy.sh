#!/bin/sh

# Stop script on command failures
set -e

skip_init=false
if [[ $* == *--skip-init* ]]
then
  echo "Skipping initializations"
  skip_init=true
fi

# === CDK Deployment ===
export CDK_DEPLOYMENT_STAGE=dev

# Pipe output to some place and grab the export commands and execute them :)
cd ../opensearch-service-migration
cdk deploy "*" --c domainName="aos-domain" --c engineVersion="OS_1.3" --c --c dataNodeCount=2 --c vpcEnabled=true --c availabilityZoneCount=2 --c openAccessPolicyEnabled=true --c domainRemovalPolicy="DESTROY" --c migrationAssistanceEnabled=true --c mskEnablePublicEndpoints=true --require-approval never

# === Copilot Deployment ===

cd ../copilot
# Init app
copilot app init

# Init env
copilot env init --name dev --profile default --default-config

# Init services
copilot svc init --name traffic-comparator-jupyter
copilot svc init --name traffic-comparator
copilot svc init --name traffic-replayer
copilot svc init --name kafka-puller

# Deploy env
copilot env deploy --name dev

# Deploy services
copilot svc deploy --name traffic-comparator-jupyter --env dev
copilot svc deploy --name traffic-comparator --env dev
copilot svc deploy --name traffic-replayer --env dev
copilot svc deploy --name kafka-puller --env dev