#!/bin/bash

# Automation script to deploy the migration solution pipeline to AWS for development use case. The current requirement
# for use is having valid AWS credentials available to the environment

# Stop script on command failures
set -e

# Allow executing this script from any dir
script_abs_path=$(readlink -f "$0")
script_dir_abs_path=$(dirname "$script_abs_path")
cd $script_dir_abs_path
if [ -f ../../VERSION ]; then
    software_version=$(cat ../../VERSION)
else
    software_version=unknown
fi
TAGS=migration_deployment=$software_version

SECONDS=0

usage() {
  echo ""
  echo "Deploy migration solution infrastructure composed of resources deployed by CDK and Copilot"
  echo ""
  echo "Options:"
  echo "  --skip-bootstrap                      Skip one-time setup of installing npm package, bootstrapping CDK, and building Docker images."
  echo "  --skip-copilot-init                   Skip one-time Copilot initialization of app, environments, and services"
  echo "  --copilot-app-name                    [string, default: migration-copilot] Specify the Copilot application name to use for deployment"
  echo "  --destroy-env                         Destroy all CDK and Copilot CloudFormation stacks deployed, excluding the Copilot app level stack, for the given env/stage and return to a clean state."
  echo "  --destroy-all-copilot                 Destroy Copilot app and all Copilot CloudFormation stacks deployed for the given app across all regions."
  echo "  -r, --region                          [string, default: us-east-1] Specify the AWS region to deploy the CloudFormation stacks and resources."
  echo "  -s, --stage                           [string, default: dev] Specify the stage name to associate with the deployed resources"
  exit 1
}

SKIP_BOOTSTRAP=false
SKIP_COPILOT_INIT=false
COPILOT_APP_NAME=migration-copilot
DESTROY_ENV=false
DESTROY_ALL_COPILOT=false
REGION=us-east-1
STAGE=dev
while [[ $# -gt 0 ]]; do
  case $1 in
    --skip-bootstrap)
      SKIP_BOOTSTRAP=true
      shift # past argument
      ;;
    --skip-copilot-init)
      SKIP_COPILOT_INIT=true
      shift # past argument
      ;;
    --copilot-app-name)
      COPILOT_APP_NAME="$2"
      shift # past argument
      shift # past value
      ;;
    --destroy-env)
      DESTROY_ENV=true
      shift # past argument
      ;;
    --destroy-all-copilot)
      DESTROY_ALL_COPILOT=true
      shift # past argument
      ;;
    -r|--region)
      REGION="$2"
      shift # past argument
      shift # past value
      ;;
    -s|--stage)
      STAGE="$2"
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

COPILOT_DEPLOYMENT_STAGE=$STAGE
export AWS_DEFAULT_REGION=$REGION
export CDK_DEPLOYMENT_STAGE=$STAGE
# Used to overcome error: "failed to solve with frontend dockerfile.v0: failed to create LLB definition: unexpected
# status code [manifests latest]: 400 Bad Request" but may not be practical
export DOCKER_BUILDKIT=0
export COMPOSE_DOCKER_CLI_BUILD=0

if [ "$DESTROY_ENV" = true ] ; then
  set +e
  # Reset AWS_DEFAULT_REGION as the SDK used by Copilot will first check here for region to use to locate the Copilot app (https://github.com/aws/copilot-cli/issues/5138)
  export AWS_DEFAULT_REGION=""
  copilot svc delete -a $COPILOT_APP_NAME --name traffic-comparator-jupyter --env $COPILOT_DEPLOYMENT_STAGE --yes
  copilot svc delete -a $COPILOT_APP_NAME --name traffic-comparator --env $COPILOT_DEPLOYMENT_STAGE --yes
  copilot svc delete -a $COPILOT_APP_NAME --name traffic-replayer --env $COPILOT_DEPLOYMENT_STAGE --yes
  copilot svc delete -a $COPILOT_APP_NAME --name capture-proxy-es --env $COPILOT_DEPLOYMENT_STAGE --yes
  copilot svc delete -a $COPILOT_APP_NAME --name migration-console --env $COPILOT_DEPLOYMENT_STAGE --yes
  copilot env delete -a $COPILOT_APP_NAME --name $COPILOT_DEPLOYMENT_STAGE --yes
  rm ./environments/$COPILOT_DEPLOYMENT_STAGE/manifest.yml
  echo "Destroying a region will not remove the Copilot app level stack that gets created in each region. This must be manually deleted from CloudFormation or automatically removed by deleting the Copilot app"

  export AWS_DEFAULT_REGION=$REGION
  cd ../cdk/opensearch-service-migration
  cdk destroy "*" --c domainName="aos-domain" --c engineVersion="OS_2.7" --c  dataNodeCount=2 --c vpcEnabled=true --c availabilityZoneCount=2 --c openAccessPolicyEnabled=true --c domainRemovalPolicy="DESTROY" --c migrationAssistanceEnabled=true --c enableDemoAdmin=true
  exit 1
fi

if [ "$DESTROY_ALL_COPILOT" = true ] ; then
    # Reset AWS_DEFAULT_REGION as the SDK used by Copilot will first check here for region to use to locate the Copilot app (https://github.com/aws/copilot-cli/issues/5138)
    export AWS_DEFAULT_REGION=""
    copilot app delete
    echo "Destroying a Copilot app will not remove generated manifest.yml files in the copilot/environments directory. These should be manually deleted before deploying again. "
    exit 1
fi

# === CDK Deployment ===

cd ../cdk/opensearch-service-migration
if [ "$SKIP_BOOTSTRAP" = false ] ; then
  cd ../../../TrafficCapture
  ./gradlew :dockerSolution:buildDockerImages
  cd ../deployment/cdk/opensearch-service-migration
  npm install
  cdk bootstrap
fi

# This command deploys the required infrastructure for the migration solution with CDK that Copilot requires.
# The options provided to `cdk deploy` here will cause a VPC, Opensearch Domain, and MSK(Kafka) resources to get created in AWS (among other resources)
# More details on the CDK used here can be found at opensearch-migrations/deployment/cdk/opensearch-service-migration/README.md
cdk deploy "*" --tags $TAGS --c domainName="aos-domain" --c engineVersion="OS_2.7" --c  dataNodeCount=2 --c vpcEnabled=true --c availabilityZoneCount=2 --c openAccessPolicyEnabled=true --c domainRemovalPolicy="DESTROY" --c migrationAssistanceEnabled=true --c enableDemoAdmin=true -O cdk.out/cdkOutput.json --require-approval never --concurrency 3

# Collect export commands from CDK output, which are needed by Copilot, wrap the commands in double quotes and store them within the "environment" dir
export_file_path=../../copilot/environments/$COPILOT_DEPLOYMENT_STAGE/envExports.sh
grep -o "export [a-zA-Z0-9_]*=[^\\;\"]*" cdk.out/cdkOutput.json | sed 's/=/="/' | sed 's/.*/&"/' > "${export_file_path}"
source "${export_file_path}"
chmod +x "${export_file_path}"
echo "The following exports were stored from CDK in ${export_file_path}"
cat $export_file_path

# Future enhancement needed here to make our Copilot deployment able to be reran without error even if no changes are deployed
# === Copilot Deployment ===

cd ../../copilot

# Reset AWS_DEFAULT_REGION as the SDK used by Copilot will first check here for region to use to locate the Copilot app (https://github.com/aws/copilot-cli/issues/5138)
export AWS_DEFAULT_REGION=""

# Allow script to continue on error for copilot services, as copilot will error when no changes are needed
set +e

if [ "$SKIP_COPILOT_INIT" = false ] ; then
  # Init app
  copilot app init $COPILOT_APP_NAME

  # Init env, start state does not contain existing manifest but is created on the fly here to accommodate varying numbers of public and private subnets
  copilot env init -a $COPILOT_APP_NAME --name $COPILOT_DEPLOYMENT_STAGE --import-vpc-id $MIGRATION_VPC_ID --import-public-subnets $MIGRATION_PUBLIC_SUBNETS --import-private-subnets $MIGRATION_PRIVATE_SUBNETS --aws-access-key-id $AWS_ACCESS_KEY_ID --aws-secret-access-key $AWS_SECRET_ACCESS_KEY --aws-session-token $AWS_SESSION_TOKEN --region $REGION
  #copilot env init -a $COPILOT_APP_NAME --name $COPILOT_DEPLOYMENT_STAGE --default-config --aws-access-key-id $AWS_ACCESS_KEY_ID --aws-secret-access-key $AWS_SECRET_ACCESS_KEY --aws-session-token $AWS_SESSION_TOKEN --region $REGION

  # Init services
  copilot svc init -a $COPILOT_APP_NAME --name traffic-comparator-jupyter
  copilot svc init -a $COPILOT_APP_NAME --name traffic-comparator
  copilot svc init -a $COPILOT_APP_NAME --name capture-proxy-es
  copilot svc init -a $COPILOT_APP_NAME --name migration-console
  else
  REPLAYER_SKIP_INIT_ARG="--skip-copilot-init"
fi


# Deploy env
copilot env deploy -a $COPILOT_APP_NAME --name $COPILOT_DEPLOYMENT_STAGE

# Deploy services
copilot svc deploy -a $COPILOT_APP_NAME --name traffic-comparator-jupyter --env $COPILOT_DEPLOYMENT_STAGE --resource-tags $TAGS
copilot svc deploy -a $COPILOT_APP_NAME --name traffic-comparator --env $COPILOT_DEPLOYMENT_STAGE --resource-tags $TAGS
copilot svc deploy -a $COPILOT_APP_NAME --name capture-proxy-es --env $COPILOT_DEPLOYMENT_STAGE --resource-tags $TAGS
copilot svc deploy -a $COPILOT_APP_NAME --name migration-console --env $COPILOT_DEPLOYMENT_STAGE --resource-tags $TAGS
./createReplayer.sh --id default --target-uri "https://${MIGRATION_DOMAIN_ENDPOINT}:443" --extra-args "--tags=${TAGS} --auth-header-user-and-secret ${MIGRATION_DOMAIN_USER_AND_SECRET_ARN} | nc traffic-comparator 9220" "${REPLAYER_SKIP_INIT_ARG}"


# Output deployment time
duration=$SECONDS
echo "Total deployment time: $((duration / 3600)) hour(s), $(((duration / 60) % 60)) minute(s) and $((duration % 60)) second(s) elapsed."
