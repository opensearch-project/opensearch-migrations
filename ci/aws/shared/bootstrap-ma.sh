#!/usr/bin/env bash
# Builds Migration Assistant from source and deploys it to a new EKS cluster with aws-bootstrap.sh. Records the stack
# exports in the CI state file for cleanup.
#
# Usage: bootstrap-ma.sh --stack-name N --stage S --kube-context C [--deploy-import-vpc-cfn] [extra aws-bootstrap args]
# Deploys the create-VPC template unless --deploy-import-vpc-cfn is given.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

stack_name="" stage="" kube_context="" deploy_flag=--deploy-create-vpc-cfn extra=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --stack-name) stack_name="$2"; shift 2 ;;
        --stage) stage="$2"; shift 2 ;;
        --kube-context) kube_context="$2"; shift 2 ;;
        --deploy-create-vpc-cfn|--deploy-import-vpc-cfn) deploy_flag="$1"; shift ;;
        *) extra+=("$1"); shift ;;
    esac
done
[[ -n "$stack_name" && -n "$stage" && -n "$kube_context" ]] || die "--stack-name, --stage and --kube-context are required"

record_exports() {
    local exports
    exports=$(cfn_exports "$stack_name") || return 1
    state_set EKS_CLUSTER_NAME "$(sed -n 's/^MIGRATIONS_EKS_CLUSTER_NAME=//p' <<< "$exports")"
    state_set MA_VPC_ID "$(sed -n 's/^VPC_ID=//p' <<< "$exports")"
    state_set EKS_CLUSTER_SG "$(sed -n 's/^EKS_CLUSTER_SECURITY_GROUP=//p' <<< "$exports")"
}

# The EKS access entry goes to the role this job assumed (osm-ci-main or osm-ci-pr).
access_principal=$(caller_role_arn)
log "EKS access principal: $access_principal"

cd "$REPO_ROOT"
./deployment/k8s/aws/assemble-bootstrap.sh

status=0
./deployment/k8s/aws/dist/aws-bootstrap.sh \
    "$deploy_flag" \
    --stack-name "$stack_name" \
    --stage "$stage" \
    --eks-access-principal-arn "$access_principal" \
    --build \
    --base-dir "$REPO_ROOT" \
    "${extra[@]}" \
    --skip-console-exec \
    --skip-setting-k8s-context \
    --kubectl-context "$kube_context" \
    --region "$REGION" || status=$?

# CFN/EKS may exist even when the image build or Helm install failed; keep the exports so cleanup can tear EKS down.
record_exports || log "Could not read exports from $stack_name"
exit "$status"
