#!/usr/bin/env bash
# Deploys the source/target OpenSearch Service domains through test/awsDeployCluster.sh (the aws-samples CDK app).
#
# Usage: deploy-clusters.sh --stage S --context-file F [--source-version V --source-type T]
#                           [--target-version V --target-type T] [--public-access]
#                           [--vpc-id VPC --subnet-ids SUBNET1,SUBNET2]  (default: a new 2-AZ VPC)
set -euo pipefail
source "$(dirname "$0")/lib.sh"

stage="" context_file="" source_version="" source_type="" target_version="" target_type="" public_access=false
vpc_id="" subnet_ids=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --stage) stage="$2"; shift 2 ;;
        --context-file) context_file="$2"; shift 2 ;;
        --source-version) source_version="$2"; shift 2 ;;
        --source-type) source_type="$2"; shift 2 ;;
        --target-version) target_version="$2"; shift 2 ;;
        --target-type) target_type="$2"; shift 2 ;;
        --public-access) public_access=true; shift ;;
        --vpc-id) vpc_id="$2"; shift 2 ;;
        --subnet-ids) subnet_ids="$2"; shift 2 ;;
        *) die "Unknown argument: $1" ;;
    esac
done
[[ -n "$stage" && -n "$context_file" ]] || die "--stage and --context-file are required"

account=$(account_id)
cluster_json() {
    local id="$1" version="$2" type="$3"
    jq -n --arg id "$id" --arg name "$stage-$id" --arg version "$version" --arg type "$type" \
          --argjson public "$public_access" --arg account "$account" '
        {clusterId: $id, clusterName: $name, clusterVersion: $version, clusterType: $type,
         domainRemovalPolicy: "DESTROY", publicAccess: $public}
        + (if $public
           then {accessPolicies: {Version: "2012-10-17", Statement: [{Effect: "Allow",
                 Principal: {AWS: "arn:aws:iam::\($account):root"}, Action: "es:*", Resource: "*"}]}}
           else {openAccessPolicyEnabled: true} end)'
}

clusters="[]"
[[ -n "$source_version" ]] && clusters=$(jq --argjson c "$(cluster_json source "$source_version" "$source_type")" '. + [$c]' <<< "$clusters")
[[ -n "$target_version" ]] && clusters=$(jq --argjson c "$(cluster_json target "$target_version" "$target_type")" '. + [$c]' <<< "$clusters")
[[ "$(jq length <<< "$clusters")" -gt 0 ]] || die "No clusters defined; pass --source-version and/or --target-version"

mkdir -p "$REPO_ROOT/test/$(dirname "$context_file")"
jq -n --arg stage "$stage" --argjson clusters "$clusters" --arg vpc "$vpc_id" --arg subnets "$subnet_ids" '
    {stage: $stage, clusters: $clusters, "@aws-cdk/core:bootstrapQualifier": "osmtest"}
    + (if $vpc != "" then {vpcId: $vpc, vpcSubnetIds: ($subnets | split(","))} else {vpcAZCount: 2} end)' \
    > "$REPO_ROOT/test/$context_file"
log "Cluster context file test/$context_file:"
cat "$REPO_ROOT/test/$context_file"

cd "$REPO_ROOT/test"
./awsDeployCluster.sh --stage "$stage" --context-file "$context_file"
log "Cluster details:"
cat "tmp/cluster-details-$stage.json"
