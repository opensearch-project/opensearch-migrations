# Test Automation Layer

This library provides integration testing for Kubernetes-based migrations between Elasticsearch and OpenSearch clusters.

## Prerequisites

- Kubernetes cluster (for example: kind) with a local registry
- Python 3.11+ with pipenv installed

## Setup

```bash
cd libraries/testAutomation
pipenv install --deploy
mkdir -p ./reports
```

## Usage

**Basic test with defaults (ES_5.6 → OS_2.19):**
```bash
pipenv run app
```

**Test specific version combinations:**
```bash
pipenv run app --source-version=ES_7.10 --target-version=OS_2.19 \
  --test-reports-dir='./reports' --copy-logs

# Test all source versions against a target
pipenv run app --source-version=all --target-version=OS_3.1 \
  --test-reports-dir='./reports' --copy-logs
```

Supported versions:
- **Sources:** `ES_1.5`, `ES_2.4`, `ES_5.6`, `ES_6.8`, `ES_7.10`, `OS_1.3`, `OS_2.19`, `SOLR_6.6`, `SOLR_7.7`, `SOLR_8.11`, `SOLR_9.8`
- **Targets:** `OS_1.3`, `OS_2.19`, `OS_3.1`

**Run specific tests:**
```bash
pipenv run app --test-ids=0001 --source-version=ES_7.10 --target-version=OS_2.19
pipenv run app --test-ids=0001,0004 --source-version=ES_7.10 --target-version=OS_2.19
```

For local kind CDC tests that deploy a capture proxy, disable external load balancer provisioning:
```bash
pipenv run app --test-ids=0031 --source-version=ES_8.19 --target-version=OS_3.1 \
  --capture-proxy-service-type=ClusterIP
```

**Run a two-phase trace test:**
```bash
pipenv run app --test-ids=0001,0040 --trace-test-ids=0051,0053 \
  --trace-values-file=../../deployment/k8s/charts/aggregates/migrationAssistantWithArgo/valuesTraceJaeger.yaml \
  --trace-backend=jaeger \
  --source-version=ES_8.19 --target-version=OS_3.1 \
  --capture-proxy-service-type=ClusterIP
```

The trace phase runs after the default phase. It resets migration workflow resources, upgrades the same Helm release with `--reuse-values`, waits for `otel-trace-collector`, and then runs only the trace IDs. Use `--trace-backend=xray` with `valuesTraceXray.yaml` for EKS.

## Development Mode

The `--dev` flag combines options for fast iteration: `--skip-delete`, `--reuse-clusters`, `--keep-workflows`.

```bash
# First run - deploys Helm chart and creates clusters
pipenv run app --dev --source-version=ES_7.10 --target-version=OS_2.19 --test-ids=0001

# Subsequent runs - reuses existing deployment and clusters
pipenv run app --dev --source-version=ES_7.10 --target-version=OS_2.19 --test-ids=0001

# Cleanup when done
pipenv run app --delete-only
```

**Individual flags (can be used without `--dev`):**

- `--skip-delete`: Keeps the Migration Assistant Helm deployment and namespace after tests complete
- `--reuse-clusters`: Reuses existing source/target clusters matching the naming pattern (e.g., `target-opensearch-2-19-*`). On first run, creates clusters and leaves them running; subsequent runs reuse them.
- `--keep-workflows`: Preserves Argo workflows for debugging instead of deleting them

Note: `--skip-delete` and `--reuse-clusters` are disabled when testing multiple version combinations (`--source-version=all`).

## Output Options

```bash
# Copy container logs from all pods in the namespace to ./logs directory
# (collected via FluentBit from /shared-logs-output in the migration console)
pipenv run app --copy-logs --source-version=ES_7.10 --target-version=OS_2.19

# Save test reports (JSON with pass/fail, duration, errors) to a directory
pipenv run app --test-reports-dir='./reports' --source-version=ES_7.10 --target-version=OS_2.19

# View summary table of existing reports without running tests
pipenv run app --output-reports-summary-only --test-reports-dir='./reports'
```

## Cleanup

```bash
# Delete entire deployment (namespace, Helm releases, clusters, all resources)
pipenv run app --delete-only

# Delete only source/target clusters (keeps Migration Assistant deployment)
pipenv run app --delete-clusters-only
```

## Troubleshooting

1. Check cluster status: `kubectl cluster-info` and `kubectl get nodes`
2. Review container logs in `./logs` (requires `--copy-logs`)
3. Inspect test reports in `./reports`
4. Check pod status: `kubectl get pods -n ma`
5. Clean up and retry: `pipenv run app --delete-only`

## Verifying AWS resource tags (EKS only)

`aws-bootstrap.sh --tags` is supposed to reach every AWS resource the deployment
creates, including the EC2 instances, EBS volumes and load balancers that EKS
Auto Mode provisions at runtime — which CloudFormation stack tags never touch.
This verifies that it actually happened:

```bash
pipenv run verify-tags \
  --region us-east-1 \
  --kube-context migration-eks-esoscdc-p42 \
  --stack-name Migration-Assistant-Infra-Create-VPC-eks-esoscdc-p42-us-east-1
```

The expected tags are read from the stack's own tags, so there is nothing to
configure — whoever deployed chose them, and this asserts they propagated. Pass
`--expect-tags 'K=V,K2=V2'` to override.

It never searches by tag: an untagged resource is exactly the one a tag search
cannot find. Instead it reaches every resource from an oracle that knows it
exists independently — `ListStackResources` for the CloudFormation half, and the
Kubernetes API for everything Auto Mode made (`Node.spec.providerID` → instances
→ their volumes/ENIs/launch template; `PersistentVolume.spec.csi.volumeHandle` →
PVC volumes; `Service.status.loadBalancer` → load balancer → target groups,
listeners, security groups). Because the cluster is the index, it is exact
regardless of what else lives in the region, and needs no clean account.

Pass `--cluster-name` to add a third, catch-all oracle: a CloudTrail sweep of
every resource-creating call made by the **cluster IAM role** (the principal Auto
Mode assumes). That checks *calls* rather than resources, so it catches a create
whose resource type nobody enumerated. Findings are split by whether AWS lets us
tag the action — `AutoModeTagPropagationPolicy`'s action set — so "our bug" is
distinguishable from "no AWS mechanism exists, the deployer must exempt it".
CloudTrail lags 5–15 minutes, hence `--cloudtrail-wait-seconds` (default 300).

The EKS CDC pipelines run all of this automatically via `--verify-resource-tags`
on the normal test invocation, after the tests so the load balancer and PVC
volumes exist. They also bootstrap with `--enforce-tags-on-create`, which adds an
IAM **Deny** on the cluster role for those same create actions when a required
tag is absent — reproducing a deployer SCP that requires tags on create, so an
untagged create fails at the moment it happens instead of being found later.

Use at least two tags: a single tag cannot catch a bug that keeps only the first
entry of a comma-separated list.
