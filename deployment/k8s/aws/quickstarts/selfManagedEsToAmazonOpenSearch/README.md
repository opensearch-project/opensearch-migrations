# Quick start: self-managed Elasticsearch to Amazon OpenSearch with capture and replay

This walkthrough takes a new team member from an empty AWS account to a running live migration:

1. Deploy Migration Assistant (MA) on EKS, built from your checkout.
2. Create a self-managed Elasticsearch 7.10 source.
3. Create a target: an Amazon OpenSearch Service domain, or an OpenSearch Serverless collection.
4. Pre-load the source with OpenSearch Benchmark, then configure and submit a migration: snapshot
   and backfill of the existing data, followed by capture and replay of live traffic.
5. Send traffic through the capture proxy, then watch it flow with the dashboards, logs and
   console commands.
6. Verify the result and tear everything down.

The whole loop takes about 90 minutes, most of it waiting for CloudFormation and the target domain.

```text
 clients / k6 ──► capture proxy (NLB) ──► ES 7.10 source        (self-managed)
                        │
                        ▼
                  Kafka (Strimzi) ──► traffic replayer ──► OpenSearch target  (domain or collection)
                                             │
                                             └──► S3 result tuples (source vs target responses)

 proxy + replayer ──OTLP──► otel-collector ──► CloudWatch (namespace OpenSearchMigrations)
                                           └─► Prometheus ──► Grafana (optional)
 pod stdout ──► Fluent Bit ──► CloudWatch Logs  /migration-assistant-<stage>-<region>/logs
```

> **Cost and safety.** Every step creates billable AWS resources. Use a development account, not a
> production one. Section 9 removes everything this guide creates.

Every command below assumes these variables. Pick your own stage name; it ends up in resource
names, dashboard names and log group names.

```bash
export AWS_REGION=us-east-2 AWS_DEFAULT_REGION=us-east-2
export STAGE=qs                      # short, lowercase
export STACK_NAME=MA-QS
export AWS_ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
cd "$(git rev-parse --show-toplevel)"
```

---

## 1. Prerequisites

- AWS CLI v2. The credentials must be able to create CloudFormation, EKS, ECR, IAM, VPC and
  OpenSearch resources.
- Java 21, Docker with the Buildx plugin, `kubectl`, `helm` and `jq`. These are needed to build the
  images from source.
- Node.js 20+ and the AWS CDK CLI (`npm install -g aws-cdk`). These are only for the target
  cluster script in section 3.
- A shell on macOS or Linux.

For background on what you are deploying, see:

- [`deployment/k8s/aws/README.md`](../../README.md) for the EKS bootstrap.
- [`captureAndReplay/captureAndReplayArchitecture.md`](../../../../../docs/captureAndReplay/captureAndReplayArchitecture.md)
  for how capture and replay work.

---

## 2. Deploy Migration Assistant on EKS

This builds every image, the chart and the CloudWatch dashboards from your checkout, pushes the
images to a new private ECR repository, and installs the chart.

```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --deploy-create-vpc-cfn \
  --stack-name "$STACK_NAME" \
  --stage "$STAGE" \
  --region "$AWS_REGION" \
  --build \
  --base-dir "$(pwd)" \
  --helm-values deployment/k8s/aws/quickstarts/selfManagedEsToAmazonOpenSearch/helm-values-observability.yaml \
  --skip-console-exec
```

- **`--deploy-create-vpc-cfn`** creates a new VPC (`10.212.0.0/16`, three AZs, private subnets
  with NAT). To use an existing VPC instead, pass
  `--deploy-import-vpc-cfn --vpc-id ... --subnet-ids ...`.
- **`--helm-values .../helm-values-observability.yaml`** turns on Grafana, which EKS leaves off by
  default. Omit it if CloudWatch is enough for you.
- **Test images.** Don't pass `--skip-test-images`. Option A in section 3 needs the
  `elasticsearch_searchguard` test image that this build produces.
- **k6 images.** Add `--with-load-test-images` if you want to use k6 in section 5.

The script sets your kubectl context to the new cluster. When it finishes:

```bash
kubectl -n ma get pods                     # everything Running / Completed
kubectl -n ma exec -it migration-console-0 -- bash   # the migration console
```

Load the stack's exports into your shell. Later steps use them.

```bash
eval "$(aws cloudformation list-exports \
  --query "Exports[?starts_with(Name,'MigrationsExportString') && contains(Name,'-${STAGE}-')].Value" \
  --output text)"
echo "$VPC_ID $EKS_CLUSTER_SECURITY_GROUP $MIGRATIONS_ECR_REGISTRY $MIGRATIONS_EKS_CLUSTER_NAME"
export MA_POD_ROLE_ARN="arn:aws:iam::${AWS_ACCOUNT}:role/${MIGRATIONS_EKS_CLUSTER_NAME}-migrations-role"
```

`MA_POD_ROLE_ARN` is the EKS Pod Identity role that MA's pods use, including the replayer. The
target's access policy must allow it.

---

## 3. Create the source and target clusters

### Source: self-managed Elasticsearch 7.10

#### Option A (recommended for a first run): in-cluster Elasticsearch on EKS

The [`testClusters`](../../../charts/aggregates/testClusters) chart runs a single-node
ES 7.10.2 with SearchGuard. It serves HTTPS with demo certificates and basic auth `admin:admin`.
`valuesEks.yaml` places it on its own on-demand NodePool and gives it S3 access, so snapshots work.

Install only the source half of the chart:

```bash
ES_IMAGE="${MIGRATIONS_ECR_REGISTRY}:migrations_elasticsearch_searchguard_latest"
helm dependency update deployment/k8s/charts/aggregates/testClusters
helm upgrade --install tc deployment/k8s/charts/aggregates/testClusters -n ma \
  -f deployment/k8s/charts/aggregates/testClusters/values.yaml \
  -f deployment/k8s/charts/aggregates/testClusters/valuesEks.yaml \
  --set conditionalPackageInstalls.target=false \
  --set source.image="${MIGRATIONS_ECR_REGISTRY}" \
  --set "source.extraInitContainers[0].image=${ES_IMAGE}" \
  --set "source.extraContainers[0].image=${ES_IMAGE}" \
  --wait --timeout 15m
kubectl -n ma get pods -l app=elasticsearch-master
```

MA reaches this source at `https://elasticsearch-master-headless:9200`. That address is already
in the sample configs.

- **Size.** The chart is deliberately small and ephemeral: one node, a 512 MB heap, no durable
  storage. It suits functional runs at tens or hundreds of requests per second. For more load,
  raise `source.esJavaOpts` and `source.resources`, or use option B.
- **Node pool.** `valuesEks.yaml` creates a `dedicated-search-pool` NodePool on the built-in
  `default` NodeClass. If you bootstrapped with `--tags` or `--disable-general-purpose-pool`, add
  `--set nodeClassName=<the MA release's aws.nodeClassName>`.
- **Image tags.** `aws-bootstrap.sh --build` tags images `latest` unless you pass `--image-tag`.
  If you passed it, change the `_latest` suffix above to match.
- **No auth.** For a plain-HTTP source without auth, add
  `-f deployment/k8s/charts/aggregates/testClusters/valuesEksNoAuth.yaml` after `valuesEks.yaml`.
  Then use `http://elasticsearch-master-headless:9200` and delete `authConfig` from the source in
  section 4. Leave the key out entirely: `authConfig: {}` fails validation.

#### Option B: your own Elasticsearch on EC2

There is no script for this path. The replayer and proxy treat the source as an HTTP endpoint, so
any ES cluster you run yourself works if the network allows it:

- **Reachability.** EKS pods must reach the source's HTTP port. Put the cluster in MA's VPC
  (`$VPC_ID`), or peer its VPC with MA's VPC and add routes in both directions. MA's VPC CIDR is
  `10.212.0.0/16`.
- **Security group.** Allow TCP 9200, or your port, from `$EKS_CLUSTER_SECURITY_GROUP` on the
  source's security group or load balancer.
- **Clients.** Your application clients must be able to reach the capture proxy's *internal* NLB
  in MA's VPC. That is how traffic gets captured (section 5).
- **Config.** Set the source `endpoint` in section 4 to the cluster's load balancer or node
  address.
  - Basic auth: keep `authConfig.basic`.
  - No auth: delete `authConfig`.
  - Self-signed TLS: set `allowInsecure: true`.
- **Snapshots.** Needed for the `WithBackfill` configs. The nodes need the `repository-s3` plugin
  and credentials that can write the snapshot bucket.

### Target

Both target options use [`test/awsDeployCluster.sh`](../../../../../test/awsDeployCluster.sh). It deploys
[amazon-opensearch-service-sample-cdk](https://github.com/aws-samples/amazon-opensearch-service-sample-cdk)
from a JSON context file and writes the endpoints to `test/tmp/cluster-details-$STAGE.json`. Run
it from `test/`, because it clones the CDK project into the current directory.

#### Option A: Amazon OpenSearch Service domain

The domain is placed inside MA's VPC, so no peering is needed. Its access policy allows SigV4
callers from your account. The size matches CI's BYOS `default` (2 × `r8g.large.search`).

```bash
mkdir -p test/tmp
cat > test/tmp/quickstart-target-domain.json <<EOF
{
  "stage": "${STAGE}",
  "vpcId": "${VPC_ID}",
  "clusters": [{
    "clusterId": "target",
    "clusterName": "${STAGE}-target",
    "clusterType": "OPENSEARCH_MANAGED_SERVICE",
    "clusterVersion": "OS_2.19",
    "domainRemovalPolicy": "DESTROY",
    "dataNodeType": "r8g.large.search",
    "dataNodeCount": 2,
    "ebsEnabled": true,
    "ebsVolumeSize": 100,
    "nodeToNodeEncryption": true,
    "allowAllVpcTraffic": true,
    "accessPolicies": {
      "Version": "2012-10-17",
      "Statement": [{
        "Effect": "Allow",
        "Principal": {"AWS": "arn:aws:iam::${AWS_ACCOUNT}:root"},
        "Action": "es:*",
        "Resource": "*"
      }]
    }
  }]
}
EOF
(cd test && ./awsDeployCluster.sh --stage "$STAGE" --context-file tmp/quickstart-target-domain.json --vpc-id "$VPC_ID")
jq . test/tmp/cluster-details-$STAGE.json
export TARGET_ENDPOINT="https://$(jq -r '.target.endpoint' test/tmp/cluster-details-$STAGE.json | sed 's|^https://||')"
```

Domain creation takes 20–40 minutes.

- **Security group.** `allowAllVpcTraffic` already admits the EKS pods. If you changed that, allow
  the EKS cluster security group explicitly:

  ```bash
  TARGET_SG=$(jq -r '.target.securityGroupId' test/tmp/cluster-details-$STAGE.json)
  aws ec2 authorize-security-group-ingress --group-id "$TARGET_SG" --protocol tcp --port 443 \
    --source-group "$EKS_CLUSTER_SECURITY_GROUP"
  ```

- **Fine-grained access control.** If you turn on FGAC, map `$MA_POD_ROLE_ARN` as a backend role,
  for example to `all_access` for a test. Nothing in the repo does this for you.

#### Option B: OpenSearch Serverless collection

The collection's data access policy must list MA's pod role. Add your own role too if you want to
query the collection from your workstation. The `sed` below turns an assumed-role session ARN into
its role ARN. If your role has an IAM path, as SSO roles do (`aws-reserved/sso.amazonaws.com/`),
set `MY_ROLE_ARN` by hand instead.

```bash
MY_ROLE_ARN=$(aws sts get-caller-identity --query Arn --output text \
  | sed -E 's#:sts::([0-9]+):assumed-role/([^/]+)/.*#:iam::\1:role/\2#')
mkdir -p test/tmp
cat > test/tmp/quickstart-target-collection.json <<EOF
{
  "stage": "${STAGE}",
  "vpcId": "${VPC_ID}",
  "clusters": [{
    "clusterId": "target",
    "clusterName": "${STAGE}-target",
    "clusterType": "OPENSEARCH_SERVERLESS",
    "collectionType": "SEARCH",
    "standbyReplicas": "DISABLED",
    "domainRemovalPolicy": "DESTROY",
    "dataAccessPrincipals": ["${MA_POD_ROLE_ARN}", "${MY_ROLE_ARN}"]
  }]
}
EOF
(cd test && ./awsDeployCluster.sh --stage "$STAGE" --context-file tmp/quickstart-target-collection.json)
export TARGET_ENDPOINT=$(jq -r '.target.endpoint' test/tmp/cluster-details-$STAGE.json)
```

Use the `SEARCH` collection type. `TIMESERIES` collections reject client-supplied document IDs,
and replayed bulk requests usually carry IDs. The same shape is exercised by `Test0034` in
`migrationConsole/lib/integ_test/integ_test/test_cases/cdc_aoss_tests.py`.

---

## 4. Configure, pre-load and submit the migration

### 4.1 Pick a config and load it

Four ready-to-edit configs sit next to this guide:

| File | Target | What runs |
|---|---|---|
| `esToAosDomainWithBackfill.yaml` | OpenSearch Service domain | Snapshot, metadata, backfill, then capture and replay |
| `esToAossCollectionWithBackfill.yaml` | Serverless collection | Snapshot, metadata, backfill, then capture and replay |
| `esToAosDomain.yaml` | OpenSearch Service domain | Capture and replay only |
| `esToAossCollection.yaml` | Serverless collection | Capture and replay only |

The `WithBackfill` files are the full live-migration shape and the recommended first run. Every
config sets up:

- one proxy fleet;
- an auto-created Kafka cluster;
- one replayer fleet;
- result tuples written to MA's default S3 bucket.

The backfill variants also take a snapshot of the source into the same bucket.

Fill in the placeholders:

```bash
BUCKET=$(kubectl -n ma get cm migrations-default-s3-config -o jsonpath='{.data.BUCKET_NAME}')
CONFIG=deployment/k8s/aws/quickstarts/selfManagedEsToAmazonOpenSearch/esToAosDomainWithBackfill.yaml
sed -e "s|<TARGET_DOMAIN_ENDPOINT>|${TARGET_ENDPOINT}|; s|<COLLECTION_ENDPOINT>|${TARGET_ENDPOINT}|" \
    -e "s|<REGION>|${AWS_REGION}|g; s|<BUCKET>|${BUCKET}|g" \
    "$CONFIG" > /tmp/migration.yaml
```

Load the config into the console and create the source credentials. **Don't submit yet.**

```bash
kubectl -n ma exec -i migration-console-0 -- workflow configure edit --stdin < /tmp/migration.yaml
kubectl -n ma exec -it migration-console-0 -- bash
```

`configure edit` validates the config as it saves it and prints any errors.

Inside the console:

```bash
workflow configure view                                   # check what you loaded
printf 'admin:admin' | workflow configure credentials create --stdin --silent source-creds
workflow configure credentials create --show-missing      # should list nothing
console clusters connection-check --cluster source        # the console can already reach the source
```

The console resolves `source` and `target` from the *configured* migration, so the `console
clusters ...` commands work before anything is submitted.

### 4.2 Pre-load the source with OpenSearch Benchmark

A backfill only has something to do if the source already holds data, so load some now. Do it
before the proxy exists:

- these writes go straight to the source, so they are never captured;
- the only way they can reach the target is the snapshot and backfill.

That makes the backfill easy to see and to verify separately from the replay.

The migration console ships [OpenSearch Benchmark](https://opensearch.org/docs/latest/benchmark/)
(OSB), and the console wraps it:

```bash
console clusters run-test-benchmarks --cluster source
console clusters cat-indices --cluster source
```

**What the command does.** It runs four standard OSB workloads, one after another, against the
source:

| Workload | Data | Indices it creates |
|---|---|---|
| `geonames` | Points of interest from geonames.org | `geonames` |
| `http_logs` | 1998 World Cup web server logs | `logs-181998`, `logs-191998`, ... |
| `nested` | StackOverflow questions with nested answers | `sonested` |
| `nyc_taxis` | 2015 NYC yellow-cab trips | `nyc_taxis` |

Each one is an `opensearch-benchmark run` with these options (see
`execute_benchmark_workload` in
`migrationConsole/lib/console_link/console_link/models/cluster.py`):

- **`--test-mode`.** OSB swaps each corpus for its 1,000-document sample (`documents-1k.json`).
  It also cuts every timed task down to a few iterations or about 10 seconds. The result is about
  1,000 documents per index, roughly 10K across the four workloads, in a few minutes.
- **`--pipeline=benchmark-only`.** OSB doesn't provision a cluster; it targets the source's
  endpoint.
- **`--workload-params=bulk_size:10,bulk_indexing_clients:1`.** Small, gentle bulks.
- **`--client-options`.** Built from the config: `use_ssl` and `verify_certs:false` for the HTTPS
  `allowInsecure` source, plus the `source-creds` basic-auth user. A SigV4 source would get the
  pod's AWS session instead.
- **`--exclude-tasks=check-cluster-health`.** Skipped so that a yellow cluster, such as a single node with replicas,
  doesn't stop the run.

Each workload's test procedure also runs its search tasks briefly. That is harmless and leaves
the indices in place.

OSB downloads the workload definitions from GitHub, and the sample corpora from the OSB data
bucket (`dbyiw3u3rf9yr.cloudfront.net`). The console therefore needs outbound internet access. The
create-VPC deployment has a NAT gateway; an isolated VPC does not.

**Loading more data.** Run OSB yourself without `--test-mode` and limit the ingest with
`ingest_percentage`. `geonames` is a good choice: 11.4M documents (3.5 GB of JSON) in one index
with a simple mapping. 5% loads about 570K documents into `geonames`, enough for the backfill to
run for a while and show real progress:

```bash
opensearch-benchmark run --pipeline=benchmark-only --kill-running-processes \
  --target-host=https://elasticsearch-master-headless:9200 \
  --client-options="use_ssl:true,verify_certs:false,basic_auth_user:admin,basic_auth_password:admin" \
  --workload=geonames \
  --include-tasks="type:delete-index,type:create-index,type:bulk" \
  --workload-params="ingest_percentage:5,bulk_size:1000,bulk_indexing_clients:2,number_of_replicas:0"
```

- **Your own source.** For a no-auth source, drop the `basic_auth_*` options. For plain HTTP, also
  drop `use_ssl` and `verify_certs`. Set `--target-host` to your endpoint.
- **`--include-tasks`** keeps only the delete-index, create-index and bulk-indexing steps. The
  workload's searches and force-merge would only slow the load down.
- **Download size.** OSB downloads and decompresses the whole corpus whatever the percentage: a
  265 MB download that expands to about 3.5 GB under `~/.benchmark` in the console pod.
- **Other workloads.** `opensearch-benchmark list workloads` shows the rest. `nyc_taxis` (165M
  documents, 74 GB) and `http_logs` (247M) are far larger than the test source can hold, so use a
  small `ingest_percentage` with those.
- **Source size.** The testClusters source has a 512 MB heap. Raise `source.esJavaOpts` first if
  you load more than a few million documents.

Check what landed, and note the counts so you can compare them after the backfill:

```bash
console clusters curl source "/_cat/indices?v&s=index"
console clusters curl source /_cat/count
```

### 4.3 Submit

```bash
workflow submit
workflow status                                           # or: workflow manage  (interactive TUI)
```

With a backfill config, the workflow runs these steps in order:

1. **Kafka.** The Strimzi cluster and topic.
2. **Capture proxy.** The Deployment and its NLB.
3. **Snapshot** (`createSnapshot`, `checkSnapshotCompletion`). This waits until the proxy is
   ready. Every write from then on is captured, so nothing falls between the snapshot and the
   capture.
4. **Metadata** (`evaluateMetadata`, `migrateMetadata`). Index settings and mappings are created on
   the target.
5. **Backfill** (`startHistoricalBackfill`, `checkBackfillStatus`, `stopHistoricalBackfill`).
   Reindex-From-Snapshot workers read the snapshot's shards from S3 and bulk-load the documents
   into the target. Progress is on the `MA-<stage>-<region>-ReindexFromSnapshot` CloudWatch
   dashboard.
6. **Replayer.** It starts once the backfill completes (`dependsOnSnapshotMigrations`). It then
   replays everything captured since step 2, starting from the beginning of the topic.

Expect 10–15 minutes before the backfill starts; Kafka and the NLB take most of it. A `test-mode`
pre-load backfills in seconds; the 5% geonames load takes a few minutes. Use
`workflow status --resource-view` to see each resource, and
`kubectl -n ma get kafkaclusters,captureproxies,datasnapshots,snapshotmigrations,trafficreplays` to see the
CRs.

**Timing your traffic.** Traffic you send through the proxy between step 2 and the snapshot is in
the snapshot *and* in Kafka, so the replayer applies it a second time. Requests with explicit
document IDs are idempotent. Requests without IDs, such as `_bulk` index actions, create
duplicates. For exact before/after counts, wait until `checkSnapshotCompletion` has succeeded
before you send section 5 traffic. In a real migration you would redirect clients to the proxy
first and accept that overlap, or use writes that carry IDs.

### What the config controls

- **Partitions.** Kafka topic partitions must be at least proxy `podReplicas + 1`, which leaves
  room for the rolling-update surge pod. Validation rejects anything smaller. For an even load,
  make the count a multiple of both the proxy and replayer counts. The samples use 4, with 2
  proxies and 2 replayers.
- **Proxy TLS.** TLS is on by default. When `tls` is omitted, the proxy gets a cert-manager
  certificate from the `migrations-ca` issuer. Set `tls: {mode: plaintext}` for plain HTTP. For
  PCA and bring-your-own certificates, see [`captureProxyTls.md`](../../../../../docs/captureProxyTls.md).
- **Proxy service.** `serviceType` defaults to `LoadBalancer`, an internal NLB. Set
  `internetFacing: true` only if you understand the exposure.
- **Backfill.** `snapshotMigrationConfigs` lists which snapshot goes to which target.
  `metadataMigrationConfig` and `documentBackfillConfig` take index allowlists, transforms and
  worker counts (`podReplicas`). An empty `{}` migrates every non-system index with defaults.
- **Approvals.** `skipApprovals: true` lets the workflow run without manual gates. Remove it to
  practise `workflow approve`, for example before the backfill starts.

The full schema is in `orchestrationSpecs/packages/schemas/src/userSchemas.ts`, and
`workflow configure sample` prints an annotated example.

---

## 5. Send traffic through the capture proxy

Clients must send requests to the proxy rather than to the source. The proxy forwards each request
to the source unchanged, credentials included, and records it to Kafka.

**From the console (simplest).** The `proxy` cluster resolves to the capture proxy:

```bash
console clusters generate-data --cluster proxy --index-name qs-test --num-docs 5000
console clusters curl proxy /_cat/indices?v
```

**From your own clients.** Point them at the NLB and trust MA's CA:

```bash
kubectl -n ma get captureproxy capture-proxy -o jsonpath='{.status.loadBalancerEndpoint}{"\n"}'
kubectl -n ma get secret migrations-root-ca-secret -o jsonpath='{.data.ca\.crt}' | base64 -d > migrations-ca.crt
curl --cacert migrations-ca.crt -u admin:admin "https://<nlb-host>:9201/_cluster/health"
```

The NLB is internal, so clients must run inside MA's VPC or a network peered with it.

**Sustained load with k6.** This needs `--with-load-test-images` at bootstrap.

```bash
deployment/k8s/installK6Chart.sh install --namespace ma
kubectl -n ma exec -it migration-console-0 -- bash
loadtest run --scenario ingest --config ingest-steady \
  --target https://capture-proxy:9201 -e AUTH_USERNAME=admin -e AUTH_PASSWORD=admin
loadtest list
```

`ingest-steady` holds 50 req/s for 5 minutes. The `ingest` scenario writes NYC-taxi documents to
the `nyc_taxis` index, the same index the benchmark pre-load created. That is fine for watching a
live index change on top of backfilled data. For separate counts, add `-e INDEX_NAME=k6-taxis`. `ingest-ramp` and `ingest-burst` are also defined.
For profiles, rates and SigV4 sources, see
[`TrafficCapture/trafficLoadTest/README.md`](../../../../../TrafficCapture/trafficLoadTest/README.md).

---

## 6. Observe capture and replay

This branch adds a full metric set for the proxy and replayer, matching CloudWatch and Grafana
dashboards, and bounded `event=` log lines. The operator runbook is
[`captureAndReplay/observabilityOperations.md`](../../../../../docs/captureAndReplay/observabilityOperations.md): full
metric inventory, conservation checks, thresholds and decision trees. This section is the tour.

### CloudWatch dashboard

The chart installs `MA-<stage>-<region>-CaptureReplay` through the ACK CloudWatch controller.
There is also a `...-ReindexFromSnapshot` dashboard for backfills.

```bash
echo "https://${AWS_REGION}.console.aws.amazon.com/cloudwatch/home?region=${AWS_REGION}#dashboards/dashboard/MA-${STAGE}-${AWS_REGION}-CaptureReplay"
kubectl -n ma get dashboards.cloudwatch.services.k8s.aws      # confirm it synced
```

The dashboard has these sections:

- **KPI row:**
  - captured req/s
  - replayed tuples/s
  - mean replay lag
  - catch-up ETA
  - actual speedup
  - source/target status-code match %
  - target CPU and JVM
- **One column per stage** (proxy → Kafka → replayer intake → target), with rows for:
  - throughput
  - backlog
  - latency and age
  - skew across pods
  - faults
  - compute
  - fleet size
- **Correctness and result tuples**, then source and replayer internals.

Pick your target domain in the dashboard's *Target domain* variable. It starts blank because
domain names differ per migration. The *Source domain* picker only applies to managed sources.

**How to read CloudWatch statistics.** EKS strips the per-pod instance ID before export, so every
pod writes to one shared series:

| Metric type | Statistic | Means |
|---|---|---|
| Gauge | `Sum / PERIOD` | Fleet total |
| Gauge | `Maximum` | Hottest pod |
| Gauge | `SampleCount / PERIOD` | Number of pods reporting |
| Counter | `Sum / PERIOD` | Fleet rate |
| Histogram | Mean (sum ÷ count) | The only usable value; there are no percentiles |

Metrics are in namespace `OpenSearchMigrations`, with dimension `OTelLib=captureProxy|replayer`.

### Grafana and Prometheus (in-cluster)

Grafana runs only if you deployed with `helm-values-observability.yaml`. It is the Prometheus
version of the same layout, with per-pod series:

```bash
kubectl -n ma port-forward svc/kube-prometheus-stack-grafana 9000:80 &
kubectl -n ma get secret kube-prometheus-stack-grafana -o jsonpath='{.data.admin-password}' | base64 -d; echo
# open http://localhost:9000  (user admin) → dashboard "Capture and Replay Observability"
kubectl -n ma port-forward svc/kube-prometheus-stack-prometheus 9090:9090 &   # raw PromQL
```

Exported names carry unit suffixes, such as `kafkaSourceConsumerLag_records` and
`replayFatalFailures_failures_total`. After pods churn, stale per-pod series linger for up to
5 minutes, so fleet sums can briefly over-count.

### The signals to watch first

| What | Metric(s) | Healthy |
|---|---|---|
| Traffic in vs. out | `fullRequest` (proxy) vs. `tupleComparison` (replayer) | Rates track each other |
| Replay lag | `lagBetweenSourceAndTargetRequests` (mean) | Seconds, not growing |
| Kafka backlog | `kafkaSourceConsumerLag` (fleet sum), `kafkaSourceMaxPartitionLag` | Bounded; no single hot partition |
| Stuck work | `replayOldestKafkaRecordAge`, `kafkaSourceStaleCommitHeadPartitions` | Under 120 s sustained; 0 |
| Capture health | `captureGapActive`, `captureHeartbeatDeadlineViolations`, `capturePublishingRecordsAwaitingAcknowledgement` | 0, 0, bounded |
| Capture faults | `capturePublisherFailures`, `captureWritesRejected` | 0 |
| Correctness | `tupleComparison` with `statusCodesMatch` | Close to 100% match |
| Tuple durability | `tupleDurabilityWritesPending`, `tupleSinkUploadsRetrying`, `tupleDurabilityTerminalFailures` | Bounded, 0, 0 |
| Target health | `targetAttemptsWithoutResponse` by `noResponseKind`, `targetRetriesExhausted` | 0 or transient; 0 |
| Process safety | `replayFatalFailures`, `kafkaSourceCommitRetryDegraded`, spread of `kafkaSourceAssignedPartitions` | 0, 0, even |

This repository installs no alarms. The thresholds are guidance only.

### Kafka from the console

```bash
console kafka describe-consumer-group           # group is replayer-<target label>, e.g. replayer-target
console kafka list-topics
console kafka dump-topic-records capture-proxy --mode dump-http   # decode captured HTTP; no consumer group
```

`describe-consumer-group` prints two parts:

- The broker's native LAG table, which is the authoritative backlog.
- A REPLAY WATERMARK section, which shows how far, in time, replay has durably progressed. It
  reads metadata only and is not a drain-time estimate.

Replay has caught up when LAG is at most 1 per partition.

### Logs

All pod stdout goes to CloudWatch Logs group `/migration-assistant-<stage>-<region>/logs`, with
streams named `from-fluentbit-<pod>`. Lines are structured as `event=<name> key=value ...`.

From your workstation:

```bash
kubectl -n ma logs -l app=replayer -c replayer --since=15m --prefix \
  | grep -E 'event=(replayer_startup_summary|kafka_assignment_changed|kafka_commit_retry_degraded|tuple_sink_upload_(degraded|recovered)|replayer_fatal_failure)'
kubectl -n ma logs -l migrations/proxy=capture-proxy -c proxy --since=15m --prefix \
  | grep -E 'event=capture_(startup_summary|health|write_gate_changed|heartbeat_late|publisher_failed)'
```

The same queries work in CloudWatch Logs Insights:

```text
fields @timestamp, pod, @message
| filter @message like /event=(capture_publisher_failed|capture_heartbeat_late|replayer_fatal_failure|kafka_commit_retry_degraded)/
| sort @timestamp desc
```

From the console: `workflow log resource --list`, then
`workflow log resource captureproxy.capture-proxy -- --tail=100`.

To follow a single record, use the log fields `topic`, `partition`, `generation` and `offset`.
Its descendants carry `writer`, `connection`, `lifetime`, `request` and `attempt`.

Two things to know about the logs:

- **Pod labels.** Replayer pods are labelled `app=replayer`. Proxy pods are labelled
  `migrations/proxy=<proxy name>`. Older examples that use `app=traffic-replayer` or
  `app=capture-proxy` match nothing.
- **Activity-monitor events.** `replay_health`, `replay_oldest_record` and `replay_stall_detected`
  are written only to the replayer's host log files, and those files are off by default
  (`MIGRATIONS_HOST_FILE_LOG_LEVEL=off`). Use the metrics instead: `replayOldestContextAge*` and
  `replayOldestKafkaRecordAge`.

### One-shot snapshot script

[`deployment/k8s/scripts/capture-replay-gameday.sh`](../../../scripts/capture-replay-gameday.sh)
`baseline` prints key Prometheus values and recent lifecycle events. Its defaults predate workflow
naming, so override them:

```bash
NAMESPACE=ma KAFKA_CLUSTER=default CONSUMER_GROUP=replayer-target \
REPLAYER_SELECTOR=app=replayer CAPTURE_SELECTOR=migrations/proxy=capture-proxy \
  deployment/k8s/scripts/capture-replay-gameday.sh events
```

The script's `lag` subcommand calls Kafka's plaintext listener. The auto-created cluster uses
SCRAM over TLS, so use `console kafka describe-consumer-group` for lag instead.

---

## 7. Verify the migration

Inside the console.

**Backfill.** Compare the pre-loaded indices on both sides once `checkBackfillStatus` has
succeeded. The counts should match exactly.

```bash
console clusters cat-indices --refresh                  # source and target, side by side
console clusters curl source /geonames/_count
console clusters curl target /geonames/_count
```

On Serverless, skip `--refresh` and allow up to a minute for new documents to become searchable.

**Replay.** Compare an index you wrote to through the proxy in section 5:

```bash
console clusters curl target /qs-test/_refresh -X POST  # domain only; Serverless refreshes on its own
console clusters curl source /qs-test/_count
console clusters curl target /qs-test/_count
console kafka describe-consumer-group                   # LAG ≈ 0 when replay has caught up
```

- **Result tuples.** One tuple per replayed request, comparing the source and target responses.
  They land in `s3://$BUCKET/tuples/` as gzipped batches, about every 60 s. Copy one locally
  and pretty-print it with `console tuples show --in <file>`.
- **Document counts.** The target can end up with slightly *more* documents than the source. The
  replayer re-sends every captured request, including requests the source rejected.

For reference numbers at scale, see the
[160K req/s benchmark](../../../../../docs/captureAndReplay/loadTestRun-2026-10-13-160k.md). It was managed-to-managed,
but its per-pod sizing figures also apply here.

---

## 8. Capture and replay settings worth knowing

All of these go in the migration config. Defaults are tuned for correctness; change them only for
load. Field definitions are in `orchestrationSpecs/packages/schemas/src/userSchemas.ts`.

**Replayer** (`traffic.replayers.<name>.replayerConfig`):

| Field | Default | What it does |
|---|---|---|
| `podReplicas` / `resources` | 1 / 3500m, 3500Mi | Fleet size and per-pod size. The 160K run used 8 vCPU, 24 GiB. |
| `speedupFactor` | 1.1 | Replay speed relative to the original timing. Must be > 0. |
| `maxConcurrentRequests` | 10000 (process default) | Cap on in-flight target requests. |
| `numClientThreads` | 0 (2 × CPUs) | Target event-loop threads. |
| `readyRequestsBufferPerThread` | 2 | Retry-ready request buffer per event loop. The 160K run used 256. |
| `kafkaQuietWindowMs` | 60000 | New replayers wait until group membership stops growing for this long before taking partitions. Scale up in batches. |
| `cancellationGraceMs` | 1000 | Time allowed for in-flight work when partitions are revoked. Must be less than the quiet window. |
| `catchUpFactor` | 2 | Speed multiplier for overdue work on newly assigned partitions. 0 means send immediately. |
| `targetRetryMaxDelayMs` | 30000 | Cap on jittered exponential retry backoff. |
| `targetRetryBudgetRatio` / `targetRetryBudgetFloorPerSecond` | 0.2 / 50 | Retry budget, so a sick target isn't overwhelmed. |
| `targetConnectionInitialRate` | 200 | Initial new-connection rate for the adaptive limiter. 0 disables it. |
| `tupleS3Bucket` / `tupleS3Region` / `tupleS3Prefix` | — / — / `tuples/` | Where result tuples go. |

Removed: `lookaheadTimeSeconds`, `observedPacketConnectionTimeout` and `quiescentPeriodMs` are now
rejected.

**Proxy** (`traffic.proxies.<name>.proxyConfig`):

| Field | Default | What it does |
|---|---|---|
| `podReplicas` / `resources` | — / 3500m, 3500Mi | Fleet size and per-pod size. Partitions must be at least `podReplicas + 1`. |
| `numThreads` | 0 (processor-aware) | Proxy event loops. Previously fixed at 1. |
| `destinationConnectionPoolSize` | 0 | Warm source connections per worker thread. |
| `tls` | cert-manager (`migrations-ca`) | `plaintext`, `certManager` or `existingSecret`. |

**Both** take `otelMetricsExportEnabled` (default true) and `otelTraceExportEnabled` (default
false).

**Placement** is set through Helm values passed to `aws-bootstrap.sh --helm-values`:

- Kafka, proxies and replayers get dedicated on-demand NodePools (`workloadsNodePool.mode: isolated`).
- EKS spreads them across zones by default (`workloadsNodePool.zoneSpread: required`, with
  `minDomains` set from the subnet count).

See [`deployment/k8s/aws/README.md`](../../README.md#configuring-workload-nodepools).

**Kafka** (`kafkaClusterConfiguration.default.autoCreate`): the defaults are smoke-test size
(3 brokers, 2 Gi storage). For real load, set `clusterSpecOverrides`, `nodePoolSpecOverrides` and
`topicSpecOverrides`. `Test0082` in `migrationConsole/lib/integ_test` is a worked example.

---

## 9. Tear down

Remove things in this order so nothing is left orphaned.

1. **The migration resources.** Inside the console:

   ```bash
   workflow reset --all --cascade --include-proxies --delete-storage
   ```

2. **The k6 chart and the source.** From your workstation; skip the k6 line if you didn't install
   it.

   ```bash
   deployment/k8s/installK6Chart.sh uninstall --namespace ma
   helm uninstall -n ma tc
   ```

3. **The target.** Use the same context file you deployed with.

   ```bash
   (cd test && ./awsDeployCluster.sh --stage "$STAGE" --context-file tmp/quickstart-target-domain.json --destroy)
   ```

4. **MA itself.** The Helm pre-delete hook stops workflows, deletes dashboards and empties the S3
   bucket.

   ```bash
   helm uninstall -n ma ma
   aws cloudformation delete-stack --stack-name "$STACK_NAME" --region "$AWS_REGION"
   ```

Alternatively, `migration-assistant cleanup --stage "$STAGE"` from
`deployment/k8s/aws/cli/bin/` handles step 4.

---

## Troubleshooting first steps

| Symptom | Look at |
|---|---|
| Config rejected on submit | The validation message names the field. Common causes: `authConfig: {}`, too few partitions, the old `fromProxy:` key (now `fromCapturedTraffic`). |
| Proxy never becomes ready | `kubectl -n ma describe captureproxy capture-proxy`, NLB provisioning, the AWS Load Balancer Controller events. |
| 401 through the proxy | The client isn't sending the *source's* credentials. The proxy only forwards them. |
| Replayer 403 from the target | The target's access policy, the FGAC mapping or the AOSS data access policy doesn't include `$MA_POD_ROLE_ARN`. |
| Lag grows | Follow the runbook's decision trees, in [`observabilityOperations.md`](../../../../../docs/captureAndReplay/observabilityOperations.md#decision-trees). |
| Anything else | `migration-assistant diag`, or `kubectl -n ma get events --sort-by=.lastTimestamp`. |
