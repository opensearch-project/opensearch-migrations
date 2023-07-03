#!/bin/sh

# Automation script to deploy the migration solution pipline to AWS for development use case. The current requirement
# for use is having valid AWS credentials available to the environment

# Stop script on command failures
set -e

# Allow executing this script from any dir
script_abs_path=$(readlink -f "$0")
script_dir_abs_path=$(dirname "$script_abs_path")
cd $script_dir_abs_path

SECONDS=0

# Allow --skip-init flag to avoid one-time setups
skip_boot=false
if [[ $* == *--skip-bootstrap* ]]
then
  skip_boot=true
fi

# === CDK Deployment ===
export CDK_DEPLOYMENT_STAGE=dev
# Will be used for CDK and Copilot
export AWS_DEFAULT_REGION=us-east-1
export COPILOT_DEPLOYMENT_NAME=migration-copilot

# Used to overcome error: "failed to solve with frontend dockerfile.v0: failed to create LLB definition: unexpected
# status code [manifests latest]: 400 Bad Request" but may not be practical
export DOCKER_BUILDKIT=0
export COMPOSE_DOCKER_CLI_BUILD=0

cd ../cdk/opensearch-service-migration
if [ "$skip_boot" = false ] ; then
  cd ../../../TrafficCapture
  ./gradlew :dockerSolution:buildDockerImages
  cd ../deployment/cdk/opensearch-service-migration
  npm install
  cdk bootstrap
fi

cdk deploy "*" --c domainName="aos-domain" --c engineVersion="OS_1.3" --c  dataNodeCount=2 --c vpcEnabled=true --c availabilityZoneCount=2 --c openAccessPolicyEnabled=true --c domainRemovalPolicy="DESTROY" --c migrationAssistanceEnabled=true --c mskEnablePublicEndpoints=true --c enableDemoManager=true -O cdkOutput.json --require-approval never

# Gather CDK output which includes export commands needed by Copilot, and make them available to the environment
found_exports=$(grep -o "export [a-zA-Z0-9_]*=[^\\;\"]*" cdkOutput.json)
eval "$(grep -o "export [a-zA-Z0-9_]*=[^\\;\"]*" cdkOutput.json)"
printf "The following exports were added from CDK:\n%s\n" "$found_exports"

# Future enhancement needed here to make our Copilot deployment able to be reran without error even if no changes are deployed
# === Copilot Deployment ===

cd ../../copilot

# Allow script to continue on error for copilot services, as copilot will error when no changes are needed
set +e

# Init app
copilot app init $COPILOT_DEPLOYMENT_NAME

# Init env
copilot env init -a $COPILOT_DEPLOYMENT_NAME --name dev --default-config --aws-access-key-id $AWS_ACCESS_KEY_ID --aws-secret-access-key $AWS_SECRET_ACCESS_KEY --aws-session-token $AWS_SESSION_TOKEN --region $AWS_DEFAULT_REGION

# Init services
copilot svc init -a $COPILOT_DEPLOYMENT_NAME --name traffic-comparator-jupyter
copilot svc init -a $COPILOT_DEPLOYMENT_NAME --name traffic-comparator
copilot svc init -a $COPILOT_DEPLOYMENT_NAME --name traffic-replayer
copilot svc init -a $COPILOT_DEPLOYMENT_NAME --name kafka-puller

copilot svc init -a $COPILOT_DEPLOYMENT_NAME --name elasticsearch
copilot svc init -a $COPILOT_DEPLOYMENT_NAME --name capture-proxy
copilot svc init -a $COPILOT_DEPLOYMENT_NAME --name opensearch-benchmark

# Deploy env
copilot env deploy -a $COPILOT_DEPLOYMENT_NAME --name dev

# Deploy services
copilot svc deploy -a $COPILOT_DEPLOYMENT_NAME --name traffic-comparator-jupyter --env dev
copilot svc deploy -a $COPILOT_DEPLOYMENT_NAME --name traffic-comparator --env dev
copilot svc deploy -a $COPILOT_DEPLOYMENT_NAME --name traffic-replayer --env dev
copilot svc deploy -a $COPILOT_DEPLOYMENT_NAME --name kafka-puller --env dev

copilot svc deploy -a $COPILOT_DEPLOYMENT_NAME --name elasticsearch --env dev
copilot svc deploy -a $COPILOT_DEPLOYMENT_NAME --name capture-proxy --env dev
copilot svc deploy -a $COPILOT_DEPLOYMENT_NAME --name opensearch-benchmark --env dev


# Output deployment time
duration=$SECONDS
echo "Total deployment time: $((duration / 3600)) hour(s), $(((duration / 60) % 60)) minute(s) and $((duration % 60)) second(s) elapsed."