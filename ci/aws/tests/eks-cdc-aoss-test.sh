#!/usr/bin/env bash
# EKS capture-and-replay test into an OpenSearch Serverless (AOSS) search collection. Bootstraps Migration Assistant
# first (with deployer tags, tag enforcement and an arm64 workload NodePool), then deploys the ES source domain and the
# AOSS collection inside the MA VPC, runs the CDC AOSS tests and checks the workload NodePools. Run
# ci/aws/shared/eks-cleanup.sh afterwards, whatever the outcome.
#
# Usage: eks-cdc-aoss-test.sh [--test-ids 0034,0041] [--source-version ES_7.10]
set -euo pipefail
source "$(dirname "$0")/../shared/lib.sh"

test_ids=0034,0041 source_version=ES_7.10
while [[ $# -gt 0 ]]; do
    case "$1" in
        --test-ids) test_ids="$2"; shift 2 ;;
        --source-version) source_version="$2"; shift 2 ;;
        *) die "Unknown argument: $1" ;;
    esac
done

stage=$(stage_name aosscdc)
state_set STAGE "$stage"
state_set KUBE_CONTEXT "migration-eks-$stage"
state_set MA_STACK_NAME "MA-CDC-AOSS-$stage-$REGION"
state_set CLUSTER_STACK_NAME "OpenSearch-$stage-$REGION"
# The collection lives in the MA VPC, so cleanup deletes its stack before the MA stack.
state_set CLUSTER_INSIDE_MA_VPC true

nodepool_overrides="$REPO_ROOT/tmp/workloads-nodepool-overrides.yaml"
mkdir -p "$(dirname "$nodepool_overrides")"
cat > "$nodepool_overrides" <<'JSON'
{
  "workloadsNodePool": {
    "architectures": ["arm64"],
    "capacityTypes": ["on-demand"],
    "instanceCategories": ["c", "m", "r"],
    "minInstanceGeneration": 6,
    "instanceSizes": ["large", "xlarge", "2xlarge", "4xlarge", "8xlarge"],
    "limits": {"cpu": "80000m", "memory": "160Gi"},
    "disruption": {"consolidationPolicy": "WhenEmpty", "consolidateAfter": "45m"}
  }
}
JSON

cat <<INFO
================================================================
EKS CDC + AOSS Integration Test
Stage:     $stage
Region:    $REGION
Test IDs:  $test_ids
Source:    $source_version
Target:    AOSS (search collection)
================================================================
INFO
aws sts get-caller-identity --query Arn --output text

"$CI_SHARED_DIR/bootstrap-ma.sh" --stack-name "$MA_STACK_NAME" --stage "$stage" --kube-context "$KUBE_CONTEXT" \
    --tags "MATestOwner=migrations-ci,MATestStage=$stage" --enforce-tags-on-create-for-tests \
    --helm-values "$nodepool_overrides"
state_load
[[ -n "${MA_VPC_ID:-}" && -n "${EKS_CLUSTER_NAME:-}" ]] || die "Missing VPC_ID / EKS cluster name exports on $MA_STACK_NAME"

log "Deploying the source domain and AOSS target collection in $MA_VPC_ID"
account=$(account_id)
job_role=$(caller_role_arn)
pod_role="arn:aws:iam::$account:role/$EKS_CLUSTER_NAME-migrations-role"
context_file="tmp/cluster-context-cdc-aoss-$stage.json"
mkdir -p "$REPO_ROOT/test/tmp"
jq -n --arg stage "$stage" --arg vpc "$MA_VPC_ID" --arg version "$source_version" --arg account "$account" \
      --arg job_role "$job_role" --arg pod_role "$pod_role" '
    {stage: $stage, vpcId: $vpc, "@aws-cdk/core:bootstrapQualifier": "osmtest",
     clusters: [
       {clusterId: "source", clusterName: "\($stage)-source", clusterVersion: $version,
        clusterType: "OPENSEARCH_MANAGED_SERVICE", domainRemovalPolicy: "DESTROY", publicAccess: true,
        accessPolicies: {Version: "2012-10-17", Statement: [{Effect: "Allow",
          Principal: {AWS: "arn:aws:iam::\($account):root"}, Action: "es:*", Resource: "*"}]}},
       {clusterId: "target", clusterName: "\($stage)-target", clusterType: "OPENSEARCH_SERVERLESS",
        collectionType: "SEARCH", standbyReplicas: "DISABLED", domainRemovalPolicy: "DESTROY",
        dataAccessPrincipals: [$job_role, $pod_role]}
     ]}' > "$REPO_ROOT/test/$context_file"
cat "$REPO_ROOT/test/$context_file"
(cd "$REPO_ROOT/test" && ./awsDeployCluster.sh --stage "$stage" --context-file "$context_file")
details="$REPO_ROOT/test/tmp/cluster-details-$stage.json"
aoss_endpoint=$(jq -r '.target.endpoint' "$details")
log "AOSS endpoint: $aoss_endpoint"

log "Creating the source configmap and pointing the console at the collection"
source_endpoint=$(jq -r '.source.endpoint' "$details")
jq -n --arg e "$source_endpoint" --arg region "$REGION" --arg v "$source_version" \
    '{endpoint: $e, allow_insecure: true, sigv4: {region: $region, service: "es"}, version: $v}' \
    > /tmp/source-cluster-config.json
expanded=$(expand_version "$source_version")
kubectl --context="$KUBE_CONTEXT" create configmap "source-$expanded-migration-config" \
    --from-file=cluster-config=/tmp/source-cluster-config.json --namespace ma --dry-run=client -o yaml \
    | kubectl --context="$KUBE_CONTEXT" apply -f -
kubectl --context="$KUBE_CONTEXT" set env statefulset/migration-console -n ma "AOSS_CDC_ENDPOINT=$aoss_endpoint"
kubectl --context="$KUBE_CONTEXT" rollout status statefulset/migration-console -n ma --timeout=120s

log "Running CDC AOSS tests"
cd "$REPO_ROOT/libraries/testAutomation"
pipenv install --deploy
# JSON test reports; collect-logs.sh prints them.
mkdir -p reports
pipenv run app --test-reports-dir=./reports --source-version="$source_version" --target-type=AOSS --test-ids="$test_ids" \
    --reuse-clusters --skip-delete --skip-install --kube-context="$KUBE_CONTEXT" \
    --verify-resource-tags --ma-stack-name="$MA_STACK_NAME" --aws-region="$REGION" --eks-cluster-name="$EKS_CLUSTER_NAME"

log "Verifying the workload NodePools"
python3 "$CI_SHARED_DIR/verify-workload-nodepool.py" --kube-context "$KUBE_CONTEXT" --overrides "$nodepool_overrides" \
    --chart-values "$REPO_ROOT/deployment/k8s/charts/aggregates/migrationAssistantWithArgo/values.yaml"
