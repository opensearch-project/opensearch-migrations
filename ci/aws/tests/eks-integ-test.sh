#!/usr/bin/env bash
# EKS integration test: deploys managed source/target domains and Migration Assistant on EKS in parallel, wires the
# cluster configmaps, checks the CloudWatch dashboards, then runs the Python E2E tests. Run ci/aws/shared/eks-cleanup.sh
# afterwards, whatever the outcome.
#
# Usage: eks-integ-test.sh [--source-version ES_7.10] [--target-version OS_1.3] [--test-ids 0001,0002]
set -euo pipefail
source "$(dirname "$0")/../shared/lib.sh"

source_version=ES_7.10 target_version=OS_1.3 test_ids=0001,0002
cluster_type=OPENSEARCH_MANAGED_SERVICE
while [[ $# -gt 0 ]]; do
    case "$1" in
        --source-version) source_version="$2"; shift 2 ;;
        --target-version) target_version="$2"; shift 2 ;;
        --test-ids) test_ids="$2"; shift 2 ;;
        *) die "Unknown argument: $1" ;;
    esac
done

stage=$(stage_name eksint)
# Record everything cleanup needs before creating anything.
state_set STAGE "$stage"
state_set KUBE_CONTEXT "migration-eks-$stage"
state_set MA_STACK_NAME "Migration-Assistant-Infra-Create-VPC-eks-$stage-$REGION"
state_set CLUSTER_STACK_NAME "OpenSearch-$stage-$REGION"
state_set CDK_CONTEXT_FILE "tmp/cluster-context-integ-$stage.json"
state_set CDK_STAGE "$stage"

cat <<INFO
================================================================
EKS Integration Test
Stage:   $stage
Region:  $REGION
Source:  $source_version
Target:  $target_version
Tests:   $test_ids
================================================================
INFO
aws sts get-caller-identity --query Arn --output text

log "Deploying clusters and bootstrapping Migration Assistant in parallel"
"$CI_SHARED_DIR/deploy-clusters.sh" --stage "$stage" --context-file "$CDK_CONTEXT_FILE" \
    --source-version "$source_version" --source-type "$cluster_type" \
    --target-version "$target_version" --target-type "$cluster_type" \
    --public-access 2>&1 | prefix_lines clusters &
clusters_pid=$!
"$CI_SHARED_DIR/bootstrap-ma.sh" --stack-name "$MA_STACK_NAME" --stage "$stage" --kube-context "$KUBE_CONTEXT" \
    --use-general-node-pool 2>&1 | prefix_lines ma &
ma_pid=$!
clusters_rc=0 ma_rc=0
wait "$clusters_pid" || clusters_rc=$?
wait "$ma_pid" || ma_rc=$?
state_load
[[ $clusters_rc -eq 0 ]] || die "Cluster deploy failed (rc=$clusters_rc)"
[[ $ma_rc -eq 0 ]] || die "Migration Assistant bootstrap failed (rc=$ma_rc)"

log "Creating source/target cluster configmaps"
"$CI_SHARED_DIR/cluster-configmaps.sh" --kube-context "$KUBE_CONTEXT" --stage "$stage" \
    --source-version "$source_version" --target-version "$target_version"

log "Validating CloudWatch dashboards"
"$CI_SHARED_DIR/verify-eks-dashboards.sh" create --kube-context "$KUBE_CONTEXT" --stage "$stage"
state_set DASHBOARDS_VERIFIED true

log "Running Python E2E tests"
cd "$REPO_ROOT/libraries/testAutomation"
pipenv install --deploy
test_args=()
[[ -n "$test_ids" && "$test_ids" != all ]] && test_args+=("--test-ids=$test_ids")
# JSON test reports; collect-logs.sh prints them.
mkdir -p reports
pipenv run app --test-reports-dir=./reports --source-version="$source_version" --target-version="$target_version" "${test_args[@]}" \
    --reuse-clusters --skip-delete --skip-install --kube-context="$KUBE_CONTEXT"
