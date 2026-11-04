#!/usr/bin/env bash
# AOSS collection test (search, time-series and vector): bootstraps Migration Assistant on EKS, deploys SEARCH,
# TIMESERIES and VECTORSEARCH collections in the MA VPC, then runs test 0021, which migrates a snapshot from the
# snapshot library into each collection. Run ci/aws/shared/eks-cleanup.sh afterwards.
#
# Snapshots come from this account's copy of the snapshot library (see snapshot_library in lib.sh).
#
# Usage: eks-aoss-test.sh [--stage-base aosss|aosst|aossv] [--s3-repo-uri URI] [--snapshot-name NAME]
#                         [--source-version OS_1.3] [--monitor-retry-limit 33]
set -euo pipefail
source "$(dirname "$0")/../shared/lib.sh"

stage_base=aosss source_version=OS_1.3 monitor_retry_limit=33
s3_repo_uri="$(snapshot_library)/aoss-osb-data/os1x-aoss-osb-data/"
snapshot_name=os1x-aoss-osb-data
while [[ $# -gt 0 ]]; do
    case "$1" in
        --stage-base) stage_base="$2"; shift 2 ;;
        --s3-repo-uri) s3_repo_uri="$2"; shift 2 ;;
        --snapshot-name) snapshot_name="$2"; shift 2 ;;
        --source-version) source_version="$2"; shift 2 ;;
        --monitor-retry-limit) monitor_retry_limit="$2"; shift 2 ;;
        *) die "Unknown argument: $1" ;;
    esac
done
[[ "$s3_repo_uri" == */ ]] || s3_repo_uri="$s3_repo_uri/"

stage=$(stage_name "$stage_base")
state_set STAGE "$stage"
state_set KUBE_CONTEXT "migration-eks-$stage"
state_set MA_STACK_NAME "MA-Serverless-$stage-$REGION"
state_set CLUSTER_STACK_NAME "OpenSearch-$stage-$REGION"
state_set CLUSTER_INSIDE_MA_VPC true

cat <<INFO
================================================================
AOSS Collection Integration Test
Stage:            $stage
Region:           $REGION
Collection types: SEARCH, TIMESERIES, VECTORSEARCH
Snapshot:         $snapshot_name at $s3_repo_uri ($source_version)
================================================================
INFO
aws sts get-caller-identity --query Arn --output text

"$CI_SHARED_DIR/bootstrap-ma.sh" --stack-name "$MA_STACK_NAME" --stage "$stage" --kube-context "$KUBE_CONTEXT" \
    --use-general-node-pool
state_load
[[ -n "${MA_VPC_ID:-}" && -n "${EKS_CLUSTER_NAME:-}" ]] || die "Missing VPC_ID / EKS cluster name exports on $MA_STACK_NAME"

log "Deploying the AOSS collections in $MA_VPC_ID"
job_role=$(caller_role_arn)
pod_role="arn:aws:iam::$(account_id):role/$EKS_CLUSTER_NAME-migrations-role"
context_file="tmp/cluster-context-aoss-$stage.json"
mkdir -p "$REPO_ROOT/test/tmp"
jq -n --arg stage "$stage" --arg vpc "$MA_VPC_ID" --arg job_role "$job_role" --arg pod_role "$pod_role" '
    {stage: $stage, vpcId: $vpc, "@aws-cdk/core:bootstrapQualifier": "osmtest",
     clusters: ([["search", "SEARCH"], ["timeseries", "TIMESERIES"], ["vector", "VECTORSEARCH"]] | map(
       {clusterId: .[0], clusterName: "\($stage)-\(.[0])", clusterType: "OPENSEARCH_SERVERLESS", collectionType: .[1],
        standbyReplicas: "DISABLED", domainRemovalPolicy: "DESTROY", dataAccessPrincipals: [$job_role, $pod_role]}))}' \
    > "$REPO_ROOT/test/$context_file"
cat "$REPO_ROOT/test/$context_file"
(cd "$REPO_ROOT/test" && ./awsDeployCluster.sh --stage "$stage" --context-file "$context_file")
details="$REPO_ROOT/test/tmp/cluster-details-$stage.json"

log "Pointing the migration console at the collections and the snapshot"
kubectl --context="$KUBE_CONTEXT" set env statefulset/migration-console -n ma \
    "AOSS_SEARCH_ENDPOINT=$(jq -r .search.endpoint "$details")" \
    "AOSS_TIMESERIES_ENDPOINT=$(jq -r .timeseries.endpoint "$details")" \
    "AOSS_VECTOR_ENDPOINT=$(jq -r .vector.endpoint "$details")" \
    "AOSS_SNAPSHOT_NAME=$snapshot_name" "AOSS_S3_REPO_URI=$s3_repo_uri" "AOSS_S3_REGION=$REGION" \
    "AOSS_MONITOR_RETRY_LIMIT=$monitor_retry_limit"
kubectl --context="$KUBE_CONTEXT" rollout status statefulset/migration-console -n ma --timeout=120s

log "Running test 0021"
cd "$REPO_ROOT/libraries/testAutomation"
pipenv install --deploy
# JSON test reports; collect-logs.sh prints them.
mkdir -p reports
pipenv run app --test-reports-dir=./reports --source-version="$source_version" --target-type=AOSS --test-ids=0021 \
    --reuse-clusters --skip-delete --skip-install --kube-context="$KUBE_CONTEXT"
