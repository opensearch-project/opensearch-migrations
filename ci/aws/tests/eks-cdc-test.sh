#!/usr/bin/env bash
# EKS capture-and-replay (CDC) test: deploys managed source/target domains and Migration Assistant on EKS (with deployer
# resource tags, tag enforcement and a workload NodePool override), builds the transform test images, runs the CDC E2E
# tests, then checks the workload NodePools. Run ci/aws/shared/eks-cleanup.sh afterwards, whatever the outcome.
#
# Usage: eks-cdc-test.sh [--stage-base esoscdc] [--test-ids 0031,...] [--source-version ES_7.10]
#                        [--target-version OS_1.3] [--speedup-factor 20]
set -euo pipefail
source "$(dirname "$0")/../shared/lib.sh"

stage_base=esoscdc test_ids=0031 source_version=ES_7.10 target_version=OS_1.3 speedup_factor=20
cluster_type=OPENSEARCH_MANAGED_SERVICE
while [[ $# -gt 0 ]]; do
    case "$1" in
        --stage-base) stage_base="$2"; shift 2 ;;
        --test-ids) test_ids="$2"; shift 2 ;;
        --source-version) source_version="$2"; shift 2 ;;
        --target-version) target_version="$2"; shift 2 ;;
        --speedup-factor) speedup_factor="$2"; shift 2 ;;
        *) die "Unknown argument: $1" ;;
    esac
done

stage=$(stage_name "$stage_base")
state_set STAGE "$stage"
state_set KUBE_CONTEXT "migration-eks-$stage"
state_set MA_STACK_NAME "Migration-Assistant-Infra-Create-VPC-eks-$stage-$REGION"
state_set CLUSTER_STACK_NAME "OpenSearch-$stage-$REGION"
state_set CDK_CONTEXT_FILE "tmp/cluster-context-cdc-integ-$stage.json"
state_set CDK_STAGE "$stage"

# Load-test cases (008x) need the k6 runner image, which is only built from source.
load_test_flag=()
[[ ",$test_ids," =~ ,[[:space:]]*008 ]] && load_test_flag=(--with-load-test-images)

# Workload NodePool overrides. JSON is valid YAML, so helm reads the file directly.
nodepool_overrides="$REPO_ROOT/tmp/workloads-nodepool-overrides.yaml"
mkdir -p "$(dirname "$nodepool_overrides")"
cat > "$nodepool_overrides" <<'JSON'
{
  "workloadsNodePool": {
    "architectures": ["amd64"],
    "capacityTypes": ["on-demand"],
    "instanceCategories": ["m", "r"],
    "minInstanceGeneration": 6,
    "instanceSizes": ["large", "xlarge", "2xlarge", "4xlarge"],
    "limits": {"cpu": "96000m", "memory": "192Gi"},
    "disruption": {"consolidationPolicy": "WhenEmpty", "consolidateAfter": "20m"}
  }
}
JSON

cat <<INFO
================================================================
EKS CDC Integration Test
Stage:     $stage
Region:    $REGION
Test IDs:  $test_ids
Source:    $source_version
Target:    $target_version
Load-test images: ${load_test_flag[*]:-no}
================================================================
INFO
aws sts get-caller-identity --query Arn --output text

log "Deploying clusters and bootstrapping Migration Assistant in parallel"
"$CI_SHARED_DIR/deploy-clusters.sh" --stage "$stage" --context-file "$CDK_CONTEXT_FILE" \
    --source-version "$source_version" --source-type "$cluster_type" \
    --target-version "$target_version" --target-type "$cluster_type" \
    --public-access 2>&1 | prefix_lines clusters &
clusters_pid=$!
# Deployer tags turn on a tagged Auto Mode NodeClass, so this test doesn't use --use-general-node-pool.
"$CI_SHARED_DIR/bootstrap-ma.sh" --stack-name "$MA_STACK_NAME" --stage "$stage" --kube-context "$KUBE_CONTEXT" \
    --tags "MATestOwner=migrations-ci,MATestStage=$stage" --enforce-tags-on-create-for-tests \
    --helm-values "$nodepool_overrides" "${load_test_flag[@]}" 2>&1 | prefix_lines ma &
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

log "Building transform test images"
registry=$(cfn_exports "$MA_STACK_NAME" | sed -n 's/^MIGRATIONS_ECR_REGISTRY=//p')
[[ -n "$registry" ]] || die "No MIGRATIONS_ECR_REGISTRY export on $MA_STACK_NAME"
aws ecr get-login-password | docker login --username AWS --password-stdin "${registry%%/*}"
build_transform_image() {
    local bank="$1" dir hash output ref
    dir="migrationConsole/lib/integ_test/integ_test/transform_assets/mountable/$bank"
    hash=$(cd "$REPO_ROOT/$dir" && find . -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | awk '{print substr($1,1,20)}')
    output=$(cd "$REPO_ROOT" && deployment/k8s/package-transforms.sh "$dir" "$registry" "transforms-$bank-$hash" --output json)
    printf '%s\n' "$output" >&2
    ref=$(jq -r '.image // empty' <<< "$output")
    [[ -n "$ref" ]] || die "package-transforms.sh returned no image for $bank"
    printf '%s' "$ref"
}
transform_basic=$(build_transform_image basic)
transform_sequence=$(build_transform_image sequence)
transform_context=$(build_transform_image context)
log "Transform images: $transform_basic $transform_sequence $transform_context"

log "Running CDC E2E tests"
cd "$REPO_ROOT/libraries/testAutomation"
pipenv install --deploy
# JSON test reports; collect-logs.sh prints them.
mkdir -p reports
pipenv run app --test-reports-dir=./reports --source-version="$source_version" --target-version="$target_version" --test-ids="$test_ids" \
    --speedup-factor="$speedup_factor" --reuse-clusters --skip-delete --skip-install --kube-context="$KUBE_CONTEXT" \
    --k6-runner-image="$registry:migrations_k6_runner_latest" \
    --transform-image-basic="$transform_basic" --transform-image-sequence="$transform_sequence" \
    --transform-image-context="$transform_context" \
    --verify-resource-tags --ma-stack-name="$MA_STACK_NAME" --aws-region="$REGION" --eks-cluster-name="$EKS_CLUSTER_NAME"

log "Verifying the workload NodePools"
python3 "$CI_SHARED_DIR/verify-workload-nodepool.py" --kube-context "$KUBE_CONTEXT" --overrides "$nodepool_overrides" \
    --chart-values "$REPO_ROOT/deployment/k8s/charts/aggregates/migrationAssistantWithArgo/values.yaml"
