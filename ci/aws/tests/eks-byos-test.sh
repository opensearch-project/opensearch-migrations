#!/usr/bin/env bash
# Bring-your-own-snapshot test: bootstraps Migration Assistant on EKS, deploys only a target domain in the MA VPC, and
# migrates a snapshot from the snapshot library (no source cluster). Run ci/aws/shared/eks-cleanup.sh afterwards,
# whatever the outcome.
#
# Snapshots come from this account's copy of the snapshot library (see snapshot_library in lib.sh).
# The large presets (5-6 TB, 90 workers) run up to 18h, past the 6h limit of a GitHub-hosted job.
#
# Usage: eks-byos-test.sh [--preset custom|small-es5x-300k|small-es7x-osb|large-es7x-24B|large-es6x-20B|large-es5x]
#                         [--s3-repo-uri URI --snapshot-name NAME --source-version V --rfs-workers N --target-size default|large]
#                         [--target-version OS_2.19] [--test-ids 0010] [--monitor-retry-limit 1000000]
set -euo pipefail
source "$(dirname "$0")/../shared/lib.sh"

library=$(snapshot_library)
preset=custom target_version=OS_2.19 test_ids=0010 monitor_retry_limit=1000000
s3_repo_uri="$library/ma_osb_data/es7x-osb-data/" snapshot_name=es7x-osb-data source_version=ES_7.10
rfs_workers=1 target_size=default
while [[ $# -gt 0 ]]; do
    case "$1" in
        --preset) preset="$2"; shift 2 ;;
        --s3-repo-uri) s3_repo_uri="$2"; shift 2 ;;
        --snapshot-name) snapshot_name="$2"; shift 2 ;;
        --source-version) source_version="$2"; shift 2 ;;
        --rfs-workers) rfs_workers="$2"; shift 2 ;;
        --target-size) target_size="$2"; shift 2 ;;
        --target-version) target_version="$2"; shift 2 ;;
        --test-ids) test_ids="$2"; shift 2 ;;
        --monitor-retry-limit) monitor_retry_limit="$2"; shift 2 ;;
        *) die "Unknown argument: $1" ;;
    esac
done
case "$preset" in
    custom) ;;
    large-es7x-24B) s3_repo_uri="$library/large-snapshot-es7x/" snapshot_name=large-snapshot source_version=ES_7.10 rfs_workers=90 target_size=large ;;
    large-es6x-20B) s3_repo_uri="$library/large-snapshot-es6x/" snapshot_name=large-snapshot source_version=ES_6.8 rfs_workers=90 target_size=large ;;
    large-es5x) s3_repo_uri="$library/large-snapshot-es5x/" snapshot_name=large-snapshot source_version=ES_5.6 rfs_workers=90 target_size=large ;;
    small-es5x-300k) s3_repo_uri="$library/another_set/large-snapshot-es5x/" snapshot_name=large-snapshot source_version=ES_5.6 rfs_workers=1 target_size=default ;;
    small-es7x-osb) s3_repo_uri="$library/ma_osb_data/es7x-osb-data/" snapshot_name=es7x-osb-data source_version=ES_7.10 rfs_workers=1 target_size=default ;;
    *) die "Unknown preset: $preset" ;;
esac
[[ "$s3_repo_uri" == */ ]] || s3_repo_uri="$s3_repo_uri/"
case "$target_size" in
    default) size='{"dataNodeType": "r8g.large.search", "dedicatedManagerNodeType": "m6g.large.search", "dataNodeCount": 2,
                    "dedicatedManagerNodeCount": 0, "ebsVolumeSize": 100}' ;;
    large) size='{"dataNodeType": "r8g.8xlarge.search", "dedicatedManagerNodeType": "m6g.2xlarge.search", "dataNodeCount": 24,
                  "dedicatedManagerNodeCount": 3, "ebsVolumeSize": 2048, "ebsThroughput": 1000, "ebsIops": 10000}' ;;
    *) die "--target-size must be default or large" ;;
esac

stage=$(stage_name eksbyos)
state_set STAGE "$stage"
state_set KUBE_CONTEXT "migration-eks-$stage"
state_set MA_STACK_NAME "Migration-Assistant-Infra-Create-VPC-eks-$stage-$REGION"
state_set CLUSTER_STACK_NAME "OpenSearch-$stage-$REGION"
state_set CLUSTER_INSIDE_MA_VPC true
state_set CDK_CONTEXT_FILE "tmp/cluster-context-byos-$stage.json"
state_set CDK_STAGE "$stage"

cat <<INFO
================================================================
EKS BYOS Integration Test
Stage:     $stage
Region:    $REGION
Preset:    $preset
Snapshot:  $snapshot_name at $s3_repo_uri ($source_version)
Target:    $target_version ($target_size), RFS workers: $rfs_workers
Test IDs:  $test_ids
================================================================
INFO
aws sts get-caller-identity --query Arn --output text

"$CI_SHARED_DIR/bootstrap-ma.sh" --stack-name "$MA_STACK_NAME" --stage "$stage" --kube-context "$KUBE_CONTEXT" \
    --use-general-node-pool
state_load
[[ -n "${MA_VPC_ID:-}" ]] || die "Missing VPC_ID export on $MA_STACK_NAME"

log "Deploying the target domain in $MA_VPC_ID"
mkdir -p "$REPO_ROOT/test/tmp"
jq -n --arg stage "$stage" --arg vpc "$MA_VPC_ID" --arg version "$target_version" --argjson size "$size" '
    {stage: $stage, vpcId: $vpc, "@aws-cdk/core:bootstrapQualifier": "osmtest",
     clusters: [({clusterId: "target", clusterName: "\($stage)-target", clusterVersion: $version,
                  clusterType: "OPENSEARCH_MANAGED_SERVICE", openAccessPolicyEnabled: true, domainRemovalPolicy: "DESTROY",
                  ebsEnabled: true, nodeToNodeEncryption: true, allowAllVpcTraffic: true} + $size)]}' \
    > "$REPO_ROOT/test/$CDK_CONTEXT_FILE"
cat "$REPO_ROOT/test/$CDK_CONTEXT_FILE"
(cd "$REPO_ROOT/test" && ./awsDeployCluster.sh --stage "$stage" --context-file "$CDK_CONTEXT_FILE" --vpc-id "$MA_VPC_ID")
details="$REPO_ROOT/test/tmp/cluster-details-$stage.json"
cat "$details"

log "Creating the target configmap"
jq -n --arg e "$(jq -r .target.endpoint "$details")" --arg region "$REGION" \
    '{endpoint: $e, allow_insecure: true, sigv4: {region: $region, service: "es"}}' > /tmp/target-cluster-config.json
expanded=$(expand_version "$target_version")
kubectl --context="$KUBE_CONTEXT" create configmap "target-$expanded-migration-config" \
    --from-file=cluster-config=/tmp/target-cluster-config.json --namespace ma --dry-run=client -o yaml \
    | kubectl --context="$KUBE_CONTEXT" apply -f -

target_sg=$(jq -r '.target.securityGroupId // empty' "$details")
if [[ -n "$target_sg" && -n "${EKS_CLUSTER_SG:-}" ]]; then
    if [[ -z "$(aws ec2 describe-security-groups --group-ids "$target_sg" \
                  --query "SecurityGroups[0].IpPermissions[?UserIdGroupPairs[?GroupId=='$EKS_CLUSTER_SG']]" --output text)" ]]; then
        log "Allowing the EKS cluster security group $EKS_CLUSTER_SG into $target_sg"
        aws ec2 authorize-security-group-ingress --group-id "$target_sg" --protocol -1 --port -1 --source-group "$EKS_CLUSTER_SG" >/dev/null
        state_set REVOKE_SG_ID "$target_sg"
        state_set REVOKE_SG_SOURCE "$EKS_CLUSTER_SG"
    fi
fi

log "Pointing the migration console at the snapshot"
kubectl --context="$KUBE_CONTEXT" -n ma set env statefulset/migration-console \
    "BYOS_SNAPSHOT_NAME=$snapshot_name" "BYOS_S3_REPO_URI=$s3_repo_uri" "BYOS_S3_REGION=$REGION" \
    "BYOS_POD_REPLICAS=$rfs_workers" "BYOS_MONITOR_RETRY_LIMIT=$monitor_retry_limit"
kubectl --context="$KUBE_CONTEXT" -n ma rollout status statefulset/migration-console --timeout=120s

log "Running BYOS migration test"
cd "$REPO_ROOT/libraries/testAutomation"
pipenv install --deploy
test_args=()
[[ -n "$test_ids" && "$test_ids" != all ]] && test_args+=("--test-ids=$test_ids")
# JSON test reports; collect-logs.sh prints them.
mkdir -p reports
pipenv run app --test-reports-dir=./reports --source-version="$source_version" --target-version="$target_version" "${test_args[@]}" \
    --reuse-clusters --skip-delete --skip-install --kube-context="$KUBE_CONTEXT"
