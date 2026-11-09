#!/usr/bin/env bash
# Isolated-network EKS test:
#   1. a build stack (create-VPC) builds the MA images into its ECR;
#   2. a VPC with no internet route gets MA through the import-VPC template with VPC endpoints, pulling images from the
#      build ECR; the script checks that pods can't reach the internet;
#   3. source/target domains go into NAT'd private subnets of that VPC, reachable from EKS on 443;
#   4. the tests run, and CloudWatch log delivery through the VPC endpoint is checked.
# Run ci/aws/shared/eks-cleanup.sh afterwards, whatever the outcome.
#
# Usage: eks-isolated-test.sh [--source-version ES_7.10] [--target-version OS_2.19] [--test-ids 0040]
set -euo pipefail
source "$(dirname "$0")/../shared/lib.sh"

source_version=ES_7.10 target_version=OS_2.19 test_ids=0040
cluster_type=OPENSEARCH_MANAGED_SERVICE
while [[ $# -gt 0 ]]; do
    case "$1" in
        --source-version) source_version="$2"; shift 2 ;;
        --target-version) target_version="$2"; shift 2 ;;
        --test-ids) test_ids="$2"; shift 2 ;;
        *) die "Unknown argument: $1" ;;
    esac
done

stage=$(stage_name isoint)
build_stage=$(stage_name isob)
build_stack="MA-ISO-BUILD-$stage"
state_set STAGE "$stage"
state_set KUBE_CONTEXT "migration-eks-$stage"
state_set MA_STACK_NAME "MA-ISO-$stage"
state_set CLUSTER_STACK_NAME "OpenSearch-$stage-$REGION"
state_set CDK_CONTEXT_FILE "tmp/cluster-context-isolated-$stage.json"
state_set CDK_STAGE "$stage"
state_set STACKS_AFTER_MA "$build_stack"
state_set EXTRA_LOG_STAGES "$build_stage"

cat <<INFO
================================================================
EKS Isolated Integration Test
Stage:     $stage (build stage $build_stage)
Region:    $REGION
Source:    $source_version
Target:    $target_version
Test IDs:  $test_ids
================================================================
INFO
aws sts get-caller-identity --query Arn --output text

log "Phase 1: build stack"
CI_STATE_FILE="$REPO_ROOT/tmp/ci-aws-build-state.env" "$CI_SHARED_DIR/bootstrap-ma.sh" --stack-name "$build_stack" \
    --stage "$build_stage" --kube-context "migration-eks-build-$build_stage"
build_ecr=$(cfn_exports "$build_stack" | sed -n 's/^MIGRATIONS_ECR_REGISTRY=//p')
[[ -n "$build_ecr" ]] || die "No MIGRATIONS_ECR_REGISTRY export on $build_stack"
log "Build ECR registry: $build_ecr"

log "Phase 2: isolated VPC and deploy"
tag_spec() { printf 'ResourceType=%s,Tags=[{Key=Name,Value=ma-isolated-%s%s},{Key=ma-stage,Value=%s}]' "$1" "$stage" "$2" "$stage"; }
vpc_id=$(aws ec2 create-vpc --cidr-block 10.213.0.0/16 --tag-specifications "$(tag_spec vpc '')" --query Vpc.VpcId --output text)
state_set ISOLATED_VPC_ID "$vpc_id"
aws ec2 modify-vpc-attribute --vpc-id "$vpc_id" --enable-dns-hostnames '{"Value":true}'
aws ec2 modify-vpc-attribute --vpc-id "$vpc_id" --enable-dns-support '{"Value":true}'
new_subnet() {
    aws ec2 create-subnet --vpc-id "$vpc_id" --cidr-block "$1" --availability-zone "$REGION$2" \
        --tag-specifications "$(tag_spec subnet "-$3")" --query Subnet.SubnetId --output text
}
iso_subnet1=$(new_subnet 10.213.0.0/24 a 1)
iso_subnet2=$(new_subnet 10.213.1.0/24 b 2)
log "Isolated VPC $vpc_id, subnets $iso_subnet1 $iso_subnet2 (no internet routes)"
aws ec2 create-tags --resources "$iso_subnet1" "$iso_subnet2" --tags Key=kubernetes.io/role/internal-elb,Value=1

"$CI_SHARED_DIR/bootstrap-ma.sh" --stack-name "$MA_STACK_NAME" --stage "$stage" --kube-context "$KUBE_CONTEXT" \
    --deploy-import-vpc-cfn --vpc-id "$vpc_id" --subnet-ids "$iso_subnet1,$iso_subnet2" --create-vpc-endpoints \
    --ma-images-source "$build_ecr"
state_load
aws eks update-kubeconfig --name "$EKS_CLUSTER_NAME" --alias "$KUBE_CONTEXT" >/dev/null

log "Validating network isolation"
if kubectl --context="$KUBE_CONTEXT" exec migration-console-0 -n ma -- timeout 10 curl -sfI https://aws.amazon.com >/dev/null 2>&1; then
    die "Pods can reach the public internet"
fi
log "Isolation validated: pods cannot reach the public internet"
log "Starting early deletion of the build stack $build_stack"
aws cloudformation delete-stack --stack-name "$build_stack" || true

log "Phase 3: AOS networking and clusters"
pub1=$(new_subnet 10.213.10.0/24 a pub-1)
pub2=$(new_subnet 10.213.11.0/24 b pub-2)
igw=$(aws ec2 create-internet-gateway --tag-specifications "$(tag_spec internet-gateway -igw)" \
        --query InternetGateway.InternetGatewayId --output text)
aws ec2 attach-internet-gateway --internet-gateway-id "$igw" --vpc-id "$vpc_id"
pub_rt=$(aws ec2 create-route-table --vpc-id "$vpc_id" --tag-specifications "$(tag_spec route-table -pub-rt)" \
           --query RouteTable.RouteTableId --output text)
aws ec2 create-route --route-table-id "$pub_rt" --destination-cidr-block 0.0.0.0/0 --gateway-id "$igw" >/dev/null
aws ec2 associate-route-table --route-table-id "$pub_rt" --subnet-id "$pub1" >/dev/null
aws ec2 associate-route-table --route-table-id "$pub_rt" --subnet-id "$pub2" >/dev/null
eip=$(aws ec2 allocate-address --domain vpc --tag-specifications "$(tag_spec elastic-ip -eip)" --query AllocationId --output text)
nat=$(aws ec2 create-nat-gateway --subnet-id "$pub1" --allocation-id "$eip" --tag-specifications "$(tag_spec natgateway -nat)" \
        --query NatGateway.NatGatewayId --output text)
aws ec2 wait nat-gateway-available --nat-gateway-ids "$nat"
priv1=$(new_subnet 10.213.20.0/24 a priv-1)
priv2=$(new_subnet 10.213.21.0/24 b priv-2)
priv_rt=$(aws ec2 create-route-table --vpc-id "$vpc_id" --tag-specifications "$(tag_spec route-table -priv-rt)" \
            --query RouteTable.RouteTableId --output text)
aws ec2 create-route --route-table-id "$priv_rt" --destination-cidr-block 0.0.0.0/0 --nat-gateway-id "$nat" >/dev/null
aws ec2 associate-route-table --route-table-id "$priv_rt" --subnet-id "$priv1" >/dev/null
aws ec2 associate-route-table --route-table-id "$priv_rt" --subnet-id "$priv2" >/dev/null
log "AOS networking: public $pub1 $pub2, NAT $nat, private $priv1 $priv2"

"$CI_SHARED_DIR/deploy-clusters.sh" --stage "$stage" --context-file "$CDK_CONTEXT_FILE" \
    --source-version "$source_version" --source-type "$cluster_type" \
    --target-version "$target_version" --target-type "$cluster_type" \
    --vpc-id "$vpc_id" --subnet-ids "$priv1,$priv2"

eks_sg=$(aws eks describe-cluster --name "$EKS_CLUSTER_NAME" --query 'cluster.resourcesVpcConfig.clusterSecurityGroupId' --output text)
for id in source target; do
    sg=$(aws opensearch describe-domain --domain-name "$stage-$id" --query 'DomainStatus.VPCOptions.SecurityGroupIds[0]' \
           --output text 2>/dev/null || true)
    if [[ -n "$sg" && "$sg" != None ]]; then
        log "Allowing $eks_sg (EKS) to reach $sg ($stage-$id) on 443"
        aws ec2 authorize-security-group-ingress --group-id "$sg" --protocol tcp --port 443 --source-group "$eks_sg" >/dev/null || true
    else
        log "WARNING: no security group found for domain $stage-$id"
    fi
done

log "Creating source/target cluster configmaps"
"$CI_SHARED_DIR/cluster-configmaps.sh" --kube-context "$KUBE_CONTEXT" --stage "$stage" \
    --source-version "$source_version" --target-version "$target_version"

log "Phase 4: tests"
cd "$REPO_ROOT/libraries/testAutomation"
pipenv install --deploy
# JSON test reports; collect-logs.sh prints them.
mkdir -p reports
pipenv run app --test-reports-dir=./reports --source-version="$source_version" --target-version="$target_version" --test-ids="$test_ids" \
    --reuse-clusters --skip-delete --skip-install --kube-context="$KUBE_CONTEXT"

log "Validating CloudWatch log delivery"
count=$(aws logs filter-log-events --log-group-name "/migration-assistant-$stage-$REGION/logs" --limit 1 \
          --query 'length(events)' --output text 2>/dev/null || echo 0)
if [[ "$count" == 0 ]]; then
    echo "::warning::No CloudWatch logs found; fluent-bit may not be shipping logs through the VPC endpoint"
else
    log "CloudWatch logs validated: logs are reaching CloudWatch through the VPC endpoint"
fi
