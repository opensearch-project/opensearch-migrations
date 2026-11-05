#!/usr/bin/env bash
# Always-run teardown for the EKS tests. Reads what to delete from the CI state file the test script wrote, so it also
# cleans up after a test that failed or timed out part-way. Each step runs even if an earlier one failed; the script
# fails if any stack is left behind.
set -uo pipefail
source "$(dirname "$0")/lib.sh"
state_load

if [[ -z "${MA_STACK_NAME:-}" ]]; then
    log "Nothing to clean up: no MA stack recorded in $CI_STATE_FILE"
    exit 0
fi

failed_steps=()
run_step() {
    local label="$1"; shift
    log "cleanup: $label"
    if ! "$@"; then
        log "cleanup: step '$label' failed; continuing"
        failed_steps+=("$label")
    fi
}

verify_dashboards_deleted() {
    "$CI_SHARED_DIR/verify-eks-dashboards.sh" delete --kube-context "$KUBE_CONTEXT" --stage "${STAGE:?}"
}

teardown_ma_app() {
    cd "$REPO_ROOT/libraries/testAutomation" || return 1
    pipenv install --deploy || return 1
    kubectl --context="$KUBE_CONTEXT" -n ma get pods || true
    local rc=0
    pipenv run app --delete-only --kube-context="$KUBE_CONTEXT" || rc=$?
    if [[ $rc -eq 2 ]]; then
        log "teardownMaApp: residual PVCs and/or 'ma' namespace (rc=2); infra teardown removes the rest"
    elif [[ $rc -ne 0 ]]; then
        log "teardownMaApp: 'pipenv run app --delete-only' exited $rc; continuing"
    fi
    kubectl --context="$KUBE_CONTEXT" get all,pvc,cm,secret -n ma 2>/dev/null || true
    return 0
}

cleanup_eks_infra() {
    if kubectl --context="$KUBE_CONTEXT" get namespace ma >/dev/null 2>&1; then
        kubectl --context="$KUBE_CONTEXT" delete namespace ma --ignore-not-found --timeout=60s || true
        kubectl --context="$KUBE_CONTEXT" wait --for=delete namespace/ma --timeout=300s || true
    fi

    # EKS-managed instance profiles keep the stack's roles from deleting.
    local role ip
    for role in $(aws cloudformation describe-stack-resources --stack-name "$MA_STACK_NAME" \
                    --query "StackResources[?ResourceType=='AWS::IAM::Role'].PhysicalResourceId" \
                    --output text 2>/dev/null); do
        for ip in $(aws iam list-instance-profiles-for-role --role-name "$role" \
                      --query 'InstanceProfiles[*].InstanceProfileName' --output text 2>/dev/null); do
            log "Removing role $role from instance profile $ip and deleting it"
            aws iam remove-role-from-instance-profile --instance-profile-name "$ip" --role-name "$role" 2>/dev/null || true
            aws iam delete-instance-profile --instance-profile-name "$ip" 2>/dev/null || true
        done
    done

    # Orphaned EKS security groups hold ENIs that block the VPC delete. Skip the cluster's own security group: it stays
    # attached to the control plane until the cluster is deleted, so deleting it now always fails. The MA stack delete
    # removes it with the cluster.
    local sgs sg sg_json ingress egress deleted cluster_sg
    cluster_sg=$(aws eks describe-cluster --name "$EKS_CLUSTER_NAME" \
            --query 'cluster.resourcesVpcConfig.clusterSecurityGroupId' --output text 2>/dev/null || true)
    sgs=$(aws ec2 describe-security-groups --filters "Name=tag:aws:eks:cluster-name,Values=$EKS_CLUSTER_NAME" \
            --query 'SecurityGroups[*].GroupId' --output text 2>/dev/null || true)
    if [[ -n "$cluster_sg" && "$cluster_sg" != None ]]; then
        log "Skipping the cluster security group $cluster_sg; it is deleted with the cluster"
        sgs=$(tr -s '[:space:]' '\n' <<< "$sgs" | grep -vx "$cluster_sg" || true)
    fi
    [[ -z "$sgs" ]] && log "No orphaned EKS security groups"
    for sg in $sgs; do
        sg_json=$(aws ec2 describe-security-groups --group-ids "$sg" --query 'SecurityGroups[0]' --output json 2>/dev/null || echo '{}')
        ingress=$(jq -c '.IpPermissions // []' <<< "$sg_json")
        egress=$(jq -c '.IpPermissionsEgress // []' <<< "$sg_json")
        [[ "$ingress" != '[]' ]] && aws ec2 revoke-security-group-ingress --group-id "$sg" --ip-permissions "$ingress" >/dev/null 2>&1
        [[ "$egress" != '[]' ]] && aws ec2 revoke-security-group-egress --group-id "$sg" --ip-permissions "$egress" >/dev/null 2>&1
    done
    for sg in $sgs; do
        deleted=false
        for i in $(seq 1 10); do
            if aws ec2 delete-security-group --group-id "$sg" >/dev/null 2>&1; then deleted=true; break; fi
            log "SG $sg delete failed ($i/10); waiting 60s for ENIs to drain"
            sleep 60
        done
        if [[ "$deleted" == false ]]; then
            aws ec2 describe-network-interfaces --filters "Name=group-id,Values=$sg" \
                --query 'NetworkInterfaces[].[NetworkInterfaceId,Status,Description]' --output table 2>/dev/null || true
        fi
    done
    return 0
}

# The isolated-VPC test builds its VPC with plain EC2 calls, so it is deleted piece by piece.
cleanup_isolated_vpc() {
    local vpc="$ISOLATED_VPC_ID" nats nat main_rt rt assoc id
    nats=$(aws ec2 describe-nat-gateways --filter "Name=vpc-id,Values=$vpc" \
             --query "NatGateways[?State!='deleted'].NatGatewayId" --output text 2>/dev/null || true)
    for nat in $nats; do aws ec2 delete-nat-gateway --nat-gateway-id "$nat" >/dev/null || true; done
    for nat in $nats; do aws ec2 wait nat-gateway-deleted --nat-gateway-ids "$nat" || true; done
    for id in $(aws ec2 describe-addresses --filters "Name=tag:ma-stage,Values=$STAGE" \
                  --query 'Addresses[*].AllocationId' --output text 2>/dev/null); do
        aws ec2 release-address --allocation-id "$id" || true
    done
    for id in $(aws ec2 describe-internet-gateways --filters "Name=attachment.vpc-id,Values=$vpc" \
                  --query 'InternetGateways[*].InternetGatewayId' --output text 2>/dev/null); do
        aws ec2 detach-internet-gateway --internet-gateway-id "$id" --vpc-id "$vpc" || true
        aws ec2 delete-internet-gateway --internet-gateway-id "$id" || true
    done
    main_rt=$(aws ec2 describe-route-tables --filters "Name=vpc-id,Values=$vpc" Name=association.main,Values=true \
                --query 'RouteTables[0].RouteTableId' --output text 2>/dev/null || true)
    for rt in $(aws ec2 describe-route-tables --filters "Name=vpc-id,Values=$vpc" \
                  --query 'RouteTables[*].RouteTableId' --output text 2>/dev/null); do
        [[ "$rt" == "$main_rt" ]] && continue
        for assoc in $(aws ec2 describe-route-tables --route-table-ids "$rt" \
                         --query 'RouteTables[0].Associations[?!Main].RouteTableAssociationId' --output text 2>/dev/null); do
            aws ec2 disassociate-route-table --association-id "$assoc" || true
        done
        aws ec2 delete-route-table --route-table-id "$rt" || true
    done
    for id in $(aws ec2 describe-subnets --filters "Name=vpc-id,Values=$vpc" --query 'Subnets[*].SubnetId' --output text 2>/dev/null); do
        aws ec2 delete-subnet --subnet-id "$id" || true
    done
    for id in $(aws ec2 describe-vpc-endpoints --filters "Name=vpc-id,Values=$vpc" \
                  --query 'VpcEndpoints[*].VpcEndpointId' --output text 2>/dev/null); do
        aws ec2 delete-vpc-endpoints --vpc-endpoint-ids "$id" >/dev/null || true
    done
    for id in $(aws ec2 describe-security-groups --filters "Name=vpc-id,Values=$vpc" \
                  --query "SecurityGroups[?GroupName!='default'].GroupId" --output text 2>/dev/null); do
        aws ec2 delete-security-group --group-id "$id" || true
    done
    aws ec2 delete-vpc --vpc-id "$vpc"
}

destroy_clusters() {
    if [[ -n "${CDK_CONTEXT_FILE:-}" ]]; then
        if [[ ! -f "$REPO_ROOT/test/$CDK_CONTEXT_FILE" ]]; then
            log "cdk-destroy skipped: test/$CDK_CONTEXT_FILE not present (the run stopped before the cluster deploy)"
            return 0
        fi
        (cd "$REPO_ROOT/test" && ./awsDeployCluster.sh --stage "$CDK_STAGE" --context-file "$CDK_CONTEXT_FILE" --destroy)
    else
        cfn_destroy "$CLUSTER_STACK_NAME"
    fi
}

if [[ "${DASHBOARDS_VERIFIED:-}" == true ]]; then
    run_step "verify CloudWatch dashboards deleted" verify_dashboards_deleted
fi
if [[ -n "${KUBE_CONTEXT:-}" && -n "${EKS_CLUSTER_NAME:-}" ]]; then
    run_step "MA app teardown" teardown_ma_app
    run_step "EKS infra cleanup" cleanup_eks_infra
fi
# Ingress rule a test added from the EKS cluster security group to a cluster in the MA VPC (BYOS).
if [[ -n "${REVOKE_SG_ID:-}" && -n "${REVOKE_SG_SOURCE:-}" ]]; then
    run_step "revoke EKS ingress on $REVOKE_SG_ID" aws ec2 revoke-security-group-ingress --group-id "$REVOKE_SG_ID" \
        --protocol -1 --port -1 --source-group "$REVOKE_SG_SOURCE"
fi
if [[ -n "${CLUSTER_STACK_NAME:-}" && "${CLUSTER_INSIDE_MA_VPC:-false}" == true ]]; then
    run_step "cluster stack" destroy_clusters
fi
run_step "MA stack" cfn_destroy "$MA_STACK_NAME"
if [[ -n "${CLUSTER_STACK_NAME:-}" && "${CLUSTER_INSIDE_MA_VPC:-false}" != true ]]; then
    run_step "cluster stack" destroy_clusters
fi
[[ -n "${ISOLATED_VPC_ID:-}" ]] && run_step "isolated VPC $ISOLATED_VPC_ID" cleanup_isolated_vpc
# Stacks the MA stack depends on, such as the import-VPC test's VPC, or the isolated test's build stack.
for stack in ${STACKS_AFTER_MA:-}; do
    run_step "stack $stack" cfn_destroy "$stack"
done
[[ -n "${KUBE_CONTEXT:-}" ]] && kubectl config delete-context "$KUBE_CONTEXT" >/dev/null 2>&1

# shellcheck disable=SC2086 # EXTRA_LOG_STAGES is a space-separated list
[[ -n "${STAGE:-}" ]] && run_step "log groups" cleanup_log_groups "$STAGE" ${EXTRA_LOG_STAGES:-}

leftover=()
if [[ -n "${ISOLATED_VPC_ID:-}" ]] && aws ec2 describe-vpcs --vpc-ids "$ISOLATED_VPC_ID" >/dev/null 2>&1; then
    leftover+=("VPC $ISOLATED_VPC_ID")
fi
# shellcheck disable=SC2086 # STACKS_AFTER_MA is a space-separated list
for stack in "$MA_STACK_NAME" ${CLUSTER_STACK_NAME:+"$CLUSTER_STACK_NAME"} ${STACKS_AFTER_MA:-}; do
    status=$(stack_status "$stack")
    [[ "$status" == GONE || "$status" == DELETE_COMPLETE ]] || leftover+=("$stack: $status")
done

[[ ${#failed_steps[@]} -gt 0 ]] && echo "::warning::Cleanup steps failed: ${failed_steps[*]}"
if [[ ${#leftover[@]} -gt 0 ]]; then
    echo "::error::Resources still present after cleanup: ${leftover[*]}"
    exit 1
fi
log "Cleanup complete"
