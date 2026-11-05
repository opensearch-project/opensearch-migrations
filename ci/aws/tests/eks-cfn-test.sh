#!/usr/bin/env bash
# EKS Solutions CloudFormation test: deploys Migration Assistant through the production create-VPC or import-VPC
# template with aws-bootstrap.sh, then checks that the migration console comes up. In import mode it first deploys a
# test VPC (templates/test-vpc.json). Run ci/aws/shared/eks-cleanup.sh afterwards, whatever the outcome.
#
# Usage: eks-cfn-test.sh [--vpc-mode create|import]
set -euo pipefail
source "$(dirname "$0")/../shared/lib.sh"

vpc_mode=create
while [[ $# -gt 0 ]]; do
    case "$1" in
        --vpc-mode) vpc_mode="$2"; shift 2 ;;
        *) die "Unknown argument: $1" ;;
    esac
done
case "$vpc_mode" in
    create) stage=$(stage_name ekscvpc); template=Migration-Assistant-Infra-Create-VPC-eks ;;
    import) stage=$(stage_name eksivpc); template=Migration-Assistant-Infra-Import-VPC-eks ;;
    *) die "--vpc-mode must be 'create' or 'import'" ;;
esac
state_set STAGE "$stage"
state_set MA_STACK_NAME "$template-$stage-$REGION"
state_set KUBE_CONTEXT "migration-eks-cluster-$stage-$REGION"

cat <<INFO
================================================================
EKS Solutions CFN Test ($vpc_mode VPC)
Stage:   $stage
Region:  $REGION
================================================================
INFO
aws sts get-caller-identity --query Arn --output text

bootstrap_args=(--deploy-create-vpc-cfn)
if [[ "$vpc_mode" == import ]]; then
    test_vpc_stack="test-vpc-$stage-$REGION"
    state_set STACKS_AFTER_MA "$test_vpc_stack"
    log "Deploying the test VPC $test_vpc_stack"
    aws cloudformation create-stack --stack-name "$test_vpc_stack" \
        --template-body "file://$CI_TESTS_DIR/templates/test-vpc.json" \
        --parameters "ParameterKey=Stage,ParameterValue=$stage"
    aws cloudformation wait stack-create-complete --stack-name "$test_vpc_stack"
    vpc_id=$(aws cloudformation describe-stacks --stack-name "$test_vpc_stack" \
        --query "Stacks[0].Outputs[?OutputKey=='VpcId'].OutputValue" --output text)
    subnet_ids=$(aws cloudformation describe-stacks --stack-name "$test_vpc_stack" \
        --query "Stacks[0].Outputs[?OutputKey=='SubnetIds'].OutputValue" --output text)
    log "Test VPC: $vpc_id, subnets $subnet_ids"
    bootstrap_args=(--deploy-import-vpc-cfn --vpc-id "$vpc_id" --subnet-ids "$subnet_ids")
fi

"$CI_SHARED_DIR/bootstrap-ma.sh" --stack-name "$MA_STACK_NAME" --stage "$stage" --kube-context "$KUBE_CONTEXT" \
    "${bootstrap_args[@]}" --use-general-node-pool
state_load

log "Validating the EKS deployment"
kubectl --context="$KUBE_CONTEXT" wait --namespace ma --for=condition=ready pod/migration-console-0 --timeout=600s
kubectl --context="$KUBE_CONTEXT" exec -n ma migration-console-0 -- /bin/bash -lc 'console --version'
log "EKS deployment validation completed successfully"
