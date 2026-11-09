#!/usr/bin/env bash
# Solutions CloudFormation test (ECS/EC2 bootstrap box): deploys the Migration-Assistant-Infra-Create-VPC CDK stack,
# runs the INIT_BOOTSTRAP workflow on its bootstrap EC2 instance over SSM, then verifies it. Run
# ci/aws/shared/eks-cleanup.sh afterwards (it deletes the stack and the run's log groups).
set -euo pipefail
source "$(dirname "$0")/../shared/lib.sh"

stage=$(stage_name sol)
state_set STAGE "$stage"
state_set MA_STACK_NAME "Migration-Assistant-Infra-Create-VPC-$stage-$REGION"

cat <<INFO
================================================================
Solutions CFN Test
Stage:   $stage
Region:  $REGION
================================================================
INFO
aws sts get-caller-identity --query Arn --output text

log "Deploying $MA_STACK_NAME"
cd "$REPO_ROOT/deployment/migration-assistant-solution"
npm install
STACK_NAME_SUFFIX="$stage-$REGION" npx cdk deploy "$MA_STACK_NAME" --parameters "Stage=$stage" \
    --require-approval never --concurrency 3
sleep 15

cd "$REPO_ROOT/test"
log "Running the INIT_BOOTSTRAP workflow"
./awsRunInitBootstrap.sh --stage "$stage" --log-group-name "solutions-deployment-ci-$stage" --workflow INIT_BOOTSTRAP
log "Verifying the bootstrap instance"
./awsRunInitBootstrap.sh --stage "$stage" --log-group-name "solutions-deployment-ci-$stage" --workflow VERIFY_INIT_BOOTSTRAP
