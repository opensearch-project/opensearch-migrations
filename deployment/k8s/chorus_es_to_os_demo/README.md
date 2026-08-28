# Eric's CDC Migration Demo

Full end-to-end migration from a local Elasticsearch 8.5 instance to a remote OpenSearch 3.7
cluster, using the Migration Assistant's Traffic Capture/Replay (CDC) approach, with a live
replay-quality dashboard. Takes about 20-30 minutes end-to-end, most of it Step 3's workflow.

**Source**: `localhost:9200` — Elasticsearch 8.5.3 (Docker), `elastic:ElasticRocks`
**Target**: `https://chorus-opensearch-edition.dev.o19s.com:9200` — OpenSearch 3.7.0, `admin:MyStr0ng!P@ssw0rd2024`
**Index**: `ecommerce`

First time through? Skim [Known Gotchas](#known-gotchas) before you start — several of them
(especially the Docker/Mac sleep-wake DNS issue) are common enough on a fresh run that it's
worth recognizing them on sight rather than debugging from scratch.

---

## Prerequisites

Also needed on your machine: `docker`, `kind`, `kubectl`, `python3` + `pip` (used by the
patch command below and the TUI).

1. **kind cluster** running — start it with:
   ```bash
   cd deployment/k8s
   ./kindTesting.sh
   ```
   This creates a 3-node kind cluster named `ma`, builds all images from the current source,
   and installs Argo Workflows, LocalStack S3, Strimzi Kafka, and the Migration Assistant
   components.

2. **Elasticsearch** running locally on Docker, port 9200, Web UI proxies to ES via port 9201 — the Chorus Elasticsearch edition
   from https://github.com/querqy/chorus-elasticsearch-edition. Clone it, then from inside
   that repo:
   ```bash
   ./quickstart.sh --es-proxy https://localhost:9201
   ``` 

3. **kubectl context** pointing to the kind cluster:
   ```bash
   kubectl config use-context kind-ma
   ```

4. **OpenSearch** with no existing `ecommerce` index:
   ```bash
   curl -X DELETE "https://chorus-opensearch-edition.dev.o19s.com:9200/ecommerce" \
     -u 'admin:MyStr0ng!P@ssw0rd2024' -k
   ```

---

## Architecture

```
curl/browser → CaptureProxy (kind, port-forwarded to localhost:9201)
                   ├─→ Source ES (host.docker.internal:9200)    [traffic proxied]
                   └─→ Kafka (Strimzi, in-cluster)               [traffic captured]
                                └─→ TrafficReplayer → Target OpenSearch
                                                    └─→ tuple-output Kafka topic (live scoring)

In parallel:
   S3 Snapshot (LocalStack) → RFS Workers → Target OpenSearch  [historical docs]
```

The CaptureProxy listens on port 9201 (HTTPS, self-signed cert — use `-k`/`-sk` with curl),
forwards every request to the real ES, and writes a copy of each request+response to Kafka.
TrafficReplayer reads that captured stream, replays it to the target, and — alongside the
normal S3 tuple archive — writes each request/response comparison to a `tuple-output` Kafka
topic for the live dashboard (see [Live Monitoring](#live-monitoring) below).

---

## Step-by-Step Setup

### Step 1 — Take an ES snapshot into LocalStack S3

```bash
bash 01-setup-es-snapshot.sh
```
One-time setup: connects the ES Docker container to the kind network so it can reach
LocalStack, installs S3 credentials into the ES keystore, and snapshots `ecommerce` to
`migrations_repo`. Safe to re-run — re-run it whenever the ES container or kind cluster
restarts (both wipe this state).

### Step 2 — Create Kubernetes secrets

```bash
bash 02-setup-k8s-secrets.sh
```
Creates `source-es-creds` and `target-os-creds`, both labeled `use-case=http-basic-credentials`
— required for the migration console's `workflow configure edit` to find them.

### Step 3 — Submit the CDC migration workflow

```bash
kubectl apply -f cdc-ecommerce-workflow.yaml
```
Submits `cdc-ecommerce-migration`, which configures and runs the inner `migration-workflow`:
Kafka cluster, CaptureProxy, metadata migration, RFS document backfill, and the
TrafficReplayer — in parallel. Takes ~15-20 minutes end to end.

Monitor with:
```bash
bash 03-monitor.sh
# or watch the inner workflow directly:
kubectl exec -n ma migration-console-0 -- /bin/bash -lc 'workflow status'
```

### Step 4 — Set up  `tuple-output` Kafka Topic

The TrafficReplayer CLI supports `--tuple-kafka-topic`, but nothing in the orchestration
layer (the `TrafficReplay` CRD schema, the workflow template) threads it through yet, so
`tupleKafkaTopic` still needs a direct patch once the `capture-proxy-target1-replay1`
deployment exists.

You don't need to wait for the full workflow — this deployment shows up well before the
15-20 minutes are done. Wait for it first:
```bash
kubectl get deployment -n ma capture-proxy-target1-replay1 -w
# Ctrl-C once it appears
```
Then patch it:

```bash
kubectl patch deployment -n ma capture-proxy-target1-replay1 --type=json -p='[
  {"op":"replace","path":"/spec/template/spec/containers/0/args/1","value":"'"$(
    kubectl get deployment -n ma capture-proxy-target1-replay1 \
      -o jsonpath='{.spec.template.spec.containers[0].args[1]}' \
      | base64 -d | python3 -c '
import json, sys, base64
cfg = json.load(sys.stdin)
cfg.update({"tupleKafkaTopic": "tuple-output"})
print(base64.b64encode(json.dumps(cfg, indent=2).encode()).decode())
'
  )"'"}
]'
```
This decodes the deployment's current base64 JSON config, adds `tupleKafkaTopic`, and patches
it back in one step. Re-apply after any Argo re-run — the workflow resets the Deployment to
its own defaults each time (no operator reconciles it directly).

### Step 5 — Create the `tuple-output` Kafka topic

This Strimzi cluster doesn't auto-create topics on first produce, so create it once, up front
— the TUI does this automatically now too, but it's worth doing explicitly if you're not
starting there. Unlike Step 4, this can be applied any time after Step 3 — the Strimzi topic
operator picks it up as soon as the Kafka cluster is ready, no need to wait or check first:

```bash
cat <<'EOF' | kubectl apply -f -
apiVersion: kafka.strimzi.io/v1
kind: KafkaTopic
metadata:
  name: tuple-output
  namespace: ma
  labels:
    strimzi.io/cluster: default
spec:
  partitions: 1
  replicas: 3
  config:
    retention.ms: 600000
    segment.bytes: 1073741824
EOF
```

### Step 6 — Port-forward the CaptureProxy

In a **separate terminal** (this dies whenever the `capture-proxy` pods restart — see
[Known Gotchas](#known-gotchas)):
```bash
kubectl port-forward -n ma svc/capture-proxy 9201:9201
```
All traffic you want captured must go to **port 9201**, not 9200. It's HTTPS with a
self-signed cert — use `https://localhost:9201` and `-sk` with curl.

---

## Live Monitoring

**`deployment/k8s/tui`** is the primary tool — a Textual TUI that consumes `tuple-output` and
shows each replayed request (and, for `_msearch`, each of its sub-queries and aggregations)
scored independently for source/target agreement, with a detail pane for hit-level diffs and
raw request copy/paste. See `../tui/README.md` for setup and usage.

```bash
cd deployment/k8s
pip install -r tui/requirements.txt
python3 -m tui --namespace ma
```

Older, simpler alternatives, kept for when a full TUI isn't wanted:
- `bash 06-live-jaccard.sh` — polls S3 tuples (~10s lag)
- `bash 07-live-jaccard-kafka.sh` — reads Kafka directly (near real-time, single blended score
  per request — the TUI's per-sub-query breakdown supersedes this)

### Scripted request/replay demo

```bash
bash 04-cdc-demo.sh
```
Sends search/aggregate/delete requests through the CaptureProxy and directly to ES, confirming
the delete propagates to the target — a quick way to see the whole pipeline work without the
UI.

### Manual testing

```bash
# Pick a doc ID
curl -s -u elastic:ElasticRocks "http://localhost:9200/ecommerce/_search?size=1&_source=false" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['hits']['hits'][0]['_id'])"

# DELETE via proxy (captured + replayed)
curl -sk -u elastic:ElasticRocks -X DELETE "https://localhost:9201/ecommerce/_doc/<ID>"

# Confirm it disappeared on target after ~30s
curl -sk -u admin:'MyStr0ng!P@ssw0rd2024' \
  "https://chorus-opensearch-edition.dev.o19s.com:9200/ecommerce/_doc/<ID>?_source=false"
```

### Target doc count
```bash
curl -sk -u admin:'MyStr0ng!P@ssw0rd2024' \
  "https://chorus-opensearch-edition.dev.o19s.com:9200/ecommerce/_count"
```

---

## Cleanup / Reset

```bash
kubectl delete workflow cdc-ecommerce-migration -n ma
kubectl delete workflow migration-workflow -n ma 2>/dev/null || true
kubectl delete capturedtraffic,captureproxy,trafficreplay,snapshotmigration,kafkacluster \
  -n ma --all 2>/dev/null || true
kubectl exec -n ma migration-console-0 -- /bin/bash -lc 'workflow reset --all 2>&1' || true
```
Then re-submit from Step 3. Steps 1 and 2 only need re-running if:
- The kind cluster was recreated (re-run both)
- The ES Docker container was restarted (re-run Step 1 only — keystore is lost on restart)
- The `docker-registry` container was restarted (see below — run the DNS fix first)

### After a Docker restart or Mac sleep/wake

Kind nodes lose their `/etc/hosts` entry for `docker-registry` when the daemon restarts. If
`migration-console-0` gets stuck in `Init:ErrImagePull`:
```bash
for node in ma-control-plane ma-worker ma-worker2; do
  GW=$(docker exec "$node" sh -c "ip route show default | awk '/default/ {print \$3}'")
  docker exec "$node" sh -c "echo '${GW} docker-registry' >> /etc/hosts"
done
kubectl delete pod migration-console-0 -n ma
kubectl wait pod/migration-console-0 -n ma --for=condition=Ready --timeout=120s
```

LocalStack's S3 data is ephemeral too — if it restarted, recreate the bucket, then re-run
`01-setup-es-snapshot.sh`:
```bash
kubectl exec -n ma migration-console-0 -- \
  env AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
  aws --endpoint-url http://localstack:4566 --region us-east-2 \
  s3 mb s3://migrations-default-123456789012-dev-us-east-2
```

---

## Known Gotchas

| Issue | Cause | Fix |
|-------|-------|-----|
| `kubectl port-forward` to `capture-proxy` dies | It binds to one specific pod when started and never reconnects — any restart of that pod (rollout, OOM, reschedule) kills it | Just restart it; or wrap it in `while true; do kubectl port-forward -n ma svc/capture-proxy 9201:9201; sleep 1; done` |
| TUI/`07-live-jaccard-kafka.sh` says "consumer.properties not found" | `migration-console-0` restarted (commonly an OOM kill after heavy `kubectl exec` use) and its `/tmp` was wiped | Re-run the TUI (it regenerates its own config) or `01-setup-es-snapshot.sh` isn't related — just retry; the config gets rebuilt automatically |
| `_msearch` shows `unknown query [querqy]` (400) from target | Source ES (Chorus/Querqy edition) supports a `querqy` query clause that has no equivalent plugin on target OpenSearch | Expected, real migration finding — not a bug in this pipeline. Chorus's relevance-tuned "results" sub-query depends on Querqy specifically |
| A subquery shows "no source data" instead of a score | Source or target response was never captured for that request (e.g. the browser aborted an in-flight search before it resolved) | Expected on live browser traffic sometimes; not a scoring bug — see `TrafficCapture/nettyWireLogging` if it becomes frequent, that path is what makes response capture reliable |
| `configureAndSubmitWorkflow` RBAC error | Wrong service account | Workflow uses `argo-test-workflow-executor` (already correct in the YAML) |
| Secrets reported "missing" by workflow CLI | Missing `use-case=http-basic-credentials` label | Run `02-setup-k8s-secrets.sh` |
| Metadata fails: "Cannot find snapshot repository root" | Workflow appends UID prefix to S3 path | The `snapshotRepo.repoPathUri` override in `source-configs` fixes this |
| RFS bulk timeout (`ReadTimeoutException`) | Remote target slow on large bulks | `documentsPerBulkRequest: 200` in the workflow YAML |
| CaptureProxy SSL error on curl | Sent `http://` to a TLS proxy | Use `https://localhost:9201 -sk` |
| ES can't reach LocalStack | ES container not on kind network | Run `01-setup-es-snapshot.sh` (runs `docker network connect kind`) |
| `migration-console-0` stuck in `Init:ErrImagePull` | `docker-registry` DNS lost after a Docker/Mac restart | See [Cleanup / Reset](#after-a-docker-restart-or-mac-sleepwake) |
| S3 bucket missing after LocalStack restart | LocalStack data is ephemeral | Recreate the bucket (see above), then re-run `01-setup-es-snapshot.sh` |
