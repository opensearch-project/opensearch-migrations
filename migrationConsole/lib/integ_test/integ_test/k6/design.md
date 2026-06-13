# k6 Load Testing for CDC Pipeline — Design Doc

**Author:** Siqi Ding  
**Date:** 2026-04-24  
**Status:** In Progress (Phase 1 complete, Phase 2 in design)

---

## Background

The CDC (Change Data Capture) pipeline captures live traffic from a source cluster via a capture proxy, writes it to Kafka, and replays it to a target cluster via a traffic replayer. All operations (index, update, delete, search) are standard HTTP requests against the Elasticsearch/OpenSearch REST API.

Current integration tests validate functional correctness — they send a handful of documents and verify they appear on the target. The existing `metric_operations.py` checks that CloudWatch metrics emit data points but does not assert on latency, throughput, or error rates. No test generates sustained traffic at a controlled rate, which means we cannot detect performance regressions in the capture proxy until customers run real workloads during migration.

## What Is k6

[k6](https://k6.io/) is an open-source load testing tool by Grafana Labs.

- **Runtime:** Single Go binary (~30MB), no JVM or external dependencies
- **Scripts:** Written in JavaScript, with SigV4 and basic auth support via built-in libraries
- **License:** Open source (AGPL-3.0), free to use — no account or paid service required
- **Install:** `brew install k6` (Mac) or download binary (Linux)
- **K8s native:** [k6 Operator](https://grafana.com/docs/k6/latest/set-up/set-up-distributed-k6/) provides a Kubernetes CRD (`TestRun`) for running and managing tests as K8s resources
- **Outputs:** Built-in support for Prometheus Remote Write, JSON, CSV, InfluxDB, Grafana Cloud

k6 sends HTTP requests at a controlled rate and reports built-in metrics: request latency (p50, p90, p95, p99), error rate, throughput (requests/sec), and active virtual users. It exits non-zero if configurable thresholds are breached, making it CI-friendly.

## Where k6 Fits in the CDC Pipeline

During a CDC migration, client traffic is redirected to the capture proxy (via DNS/LB update). The proxy forwards requests to the source cluster and simultaneously captures them to Kafka. The replayer reads from Kafka and replays to the target.

k6 simulates the customer's application — it sends HTTP requests to the capture proxy, the same way a real application would talk to the proxy during migration. In CI, the k6 Operator manages k6 as a Kubernetes-native `TestRun` resource within the Argo workflow.

```
                                              ┌─────────────────────┐
                                              │  Prometheus + Grafana│
                                              │  (metrics storage)   │
                                              └──────▲──────────────┘
                                                     │ remote write
k6 TestRun (via k6 Operator)                         │
    │                                                │
    │  HTTP requests (PUT, POST, DELETE)              │
    ▼                                                │
Capture Proxy (:9201) ──── metrics ──────────────────┘
    │
    ├──► Source Cluster      (request forwarded)
    │
    └──► Kafka               (request captured)
            │
            ▼
      Traffic Replayer       (reads from Kafka)
            │
            ▼
      Target Cluster         (replayed)
```

### What k6 measures and stores

| Metric | Asserted by k6? | Stored in Prometheus? |
|--------|-----------------|----------------------|
| Capture proxy error rate = 0% | ✅ Yes — exits non-zero on breach | ✅ Yes |
| Capture proxy p95 latency < threshold | ✅ Yes — exits non-zero on breach | ✅ Yes |
| Throughput (req/s) | Reported | ✅ Yes |
| Request latency distribution (p50/p90/p95/p99) | Reported | ✅ Yes |

### What k6 does NOT measure

These are measured by the existing Python test framework, Argo workflow, or monitoring tools:

| Metric | Asserted by | When |
|--------|-------------|------|
| Source/target document count match | Python test framework | After replayer catches up |
| Replayer catch-up time | Kafka consumer lag / CloudWatch | During/after traffic |
| Kafka metrics (e.g. `kafkaCommitCount_total`) | Existing `metric_operations.py` | During/after traffic |

### Results Storage and Regression Detection

k6 outputs metrics to **Prometheus via Remote Write**, which is already deployed in the Migration Assistant stack. This enables:

- **Historical trend tracking** — Grafana dashboards show latency and throughput across runs
- **Regression detection** — Compare p95 latency across releases; alert on degradation
- **CI visibility** — Test results are queryable via PromQL, not just console output

```bash
# k6 outputs to Prometheus (already running in the MA stack)
k6 run --out experimental-prometheus-rw \
  -e K6_PROMETHEUS_RW_SERVER_URL=http://prometheus:9090/api/v1/write \
  cdc-load-test.js
```

For local/ad-hoc runs, JSON output provides machine-parseable results:

```bash
k6 run --out json=results.json cdc-load-test.js
```

## Phases

### Phase 1 — k6 Script + Validation ✅ (Complete)

Delivered a k6 script validated against three auth modes and two environments.

**Deliverables:**
- `cdc-load-test.js` — Single k6 script supporting no-auth, basic auth, and SigV4
- `README.md` — Installation, usage, and output interpretation
- This design doc

**Validation results:**

| Auth Mode | Environment | k6 Result | Pipeline E2E |
|-----------|-------------|-----------|--------------|
| SigV4 | EKS + AWS-managed OpenSearch domains | 151/151 ✅ | Source 301 = Target 301 ✅ |
| Basic auth | Minikube + self-managed OpenSearch | 151/151 ✅ | Source 151 = Target 151 ✅ |
| No auth | Local Docker (security disabled) | Supported | — |

### Phase 2 — Pipeline Integration via k6 Operator + Rate Profiles

Integrate k6 into the CI pipeline using the [k6 Operator](https://grafana.com/docs/k6/latest/set-up/set-up-distributed-k6/) and define standard rate profiles for regression testing.

**Why the k6 Operator:**
- **Kubernetes-native** — works on EKS, minikube, GKE, or any K8s cluster
- **Declarative** — test runs defined as YAML `TestRun` resources
- **Lifecycle management** — operator creates pods, runs tests, collects results, cleans up
- **Distributed testing** — scales to multiple k6 pods for high-volume tests
- **CI-friendly** — integrates with Argo Workflows as a workflow step

**Integration with the existing test framework:**

```
Argo Workflow: cdc-full-e2e-imported-clusters
│
├── Deploy capture proxy + replayer
│
├── Create k6 TestRun (via k6 Operator)
│   │
│   │  apiVersion: k6.io/v1alpha1
│   │  kind: TestRun
│   │  spec:
│   │    parallelism: 1
│   │    script:
│   │      configMap:
│   │        name: cdc-load-test-script
│   │    arguments:
│   │      PROXY_ENDPOINT: https://capture-proxy:9201
│   │      WRITE_RATE: 50
│   │      DURATION: 5m
│   │
│   └── k6 pod runs cdc-load-test.js against capture proxy
│       ├── Asserts: proxy error rate = 0%
│       ├── Asserts: proxy p95 latency < threshold
│       └── Writes metrics to Prometheus
│
├── Wait for replayer to catch up
│
└── Python test framework (MATestBase) verifies:
    ├── Source/target document count match
    ├── k6 TestRun completed successfully
    └── Argo workflow succeeds
```

**k6 Operator deployment:**

```bash
# Install k6 Operator (works on any K8s cluster)
helm repo add grafana https://grafana.github.io/helm-charts
helm install k6-operator grafana/k6-operator -n ma
```

**Rate profiles:**

| Profile | Write Rate | Duration | Purpose | CI? |
|---------|-----------|----------|---------|-----|
| Minimal | 10 req/s | 1m | Quick sanity check | Pre-merge |
| Standard | 50 req/s | 5m | Default CI test | Post-merge |
| High volume | 200 req/s | 10m | Validate sustained throughput | Nightly |
| Stress | 500 req/s | 15m | Find breaking points | On-demand |
| Soak | 50 req/s | 60m | Check for degradation over time | Weekly |

## Authentication

The k6 script supports three authentication modes in a single script, selected via environment variables:

| Mode | Env vars needed | How it works |
|------|----------------|--------------|
| No auth | None | Plain HTTP requests |
| Basic auth | `USERNAME`, `PASSWORD` | `Authorization: Basic <base64>` header |
| SigV4 | `AWS_CREDS_FILE`, `SOURCE_HOST` | Sign for source hostname via jslib/aws SignatureV4, send to proxy |

### SigV4 Signing with the Capture Proxy

When the source cluster uses SigV4 authentication, the capture proxy passes requests through as-is — it does not add authentication. The k6 script signs every request using the [k6 AWS jslib](https://grafana.com/docs/k6/latest/javascript-api/jslib/aws/) (v0.13.0).

The key challenge: the SigV4 signature includes the `host` header. Since we send requests to the proxy but the source cluster expects its own hostname in the signature, we must:

1. **Sign** the request for the **source cluster hostname**
2. **Send** the request to the **proxy endpoint**

```javascript
import { Endpoint, SignatureV4 } from 'https://jslib.k6.io/aws/0.13.0/aws.js';

const creds = JSON.parse(open(__ENV.AWS_CREDS_FILE || '/tmp/aws-creds.json'));

const signer = new SignatureV4({
  service: 'es',
  region: __ENV.AWS_REGION || 'us-east-1',
  credentials: {
    accessKeyId: creds.accessKeyId,
    secretAccessKey: creds.secretAccessKey,
    sessionToken: creds.sessionToken,
  },
});

// Sign for SOURCE host, send to PROXY
const signed = signer.sign({
  method: 'PUT',
  endpoint: new Endpoint(`https://${SOURCE_HOST}`),
  path: path,
  headers: { 'Content-Type': 'application/json', host: SOURCE_HOST },
  body: doc,
});

http.put(`${PROXY}${path}`, doc, { headers: signed.headers });
```

**Credential handling on EKS:** The migration console pod uses IRSA (IAM Roles for Service Accounts). Credentials are extracted via Python botocore and written to a JSON file:

```bash
python3 -c "
from botocore.session import Session
import json
creds = Session().get_credentials().get_frozen_credentials()
json.dump({'accessKeyId': creds.access_key, 'secretAccessKey': creds.secret_key, 'sessionToken': creds.token}, open('/tmp/aws-creds.json','w'))
"
```

For CI (Phase 2), the k6 Operator pod uses the same IRSA role as the migration console — credentials are auto-discovered.

### Basic Auth

```javascript
import encoding from 'k6/encoding';

const headers = { 'Content-Type': 'application/json' };
if (__ENV.USERNAME) {
  headers['Authorization'] = `Basic ${encoding.b64encode(`${__ENV.USERNAME}:${__ENV.PASSWORD}`)}`;
}

http.put(url, doc, { headers });
```

## Where k6 Runs

| Environment | How k6 runs | Auth supported | Use case |
|-------------|-------------|----------------|----------|
| Developer's Mac | k6 CLI | No auth, basic auth | Local development |
| Migration console pod | k6 CLI | All (SigV4, basic, none) | Ad-hoc testing on EKS |
| k6 Operator pod (any K8s) | TestRun CRD | All (SigV4, basic, none) | CI pipeline integration |

## Known Issues

### SigV4 + k6 jslib

- Use jslib version `0.13.0` — version 0.14.0 does not exist, 0.12.3 has a different API
- Pass credentials directly to `SignatureV4` constructor — `AWSConfig.credentials` returns `undefined`
- Session tokens break shell `-e` flags — use JSON file with `open()` instead
- IRSA credentials must be extracted via Python botocore (k6 cannot auto-discover them)

### Replayer Auth Conflict (Migration Assistant 3.0.1)

When both `authHeaderValue` and `TARGET_USERNAME`/`TARGET_PASSWORD` are set, the replayer rejects conflicting auth options. Workaround: disable security on the target cluster or omit target `authConfig`.

### Network Access

- The capture proxy's K8s service is only reachable from inside the cluster
- On EKS, k6 must run from inside the cluster (migration console pod or k6 Operator pod)

## Why k6 Over Alternatives

| Aspect | k6 | OpenSearch Benchmark | Locust | curl loops |
|--------|----|--------------------|--------|------------|
| Controlled request rate | Yes (constant-arrival-rate) | No | No native support | No |
| Pass/fail thresholds | Built-in, exits non-zero | No | No built-in | No |
| AWS SigV4 support | Yes (jslib) | Yes (native) | Manual | Manual |
| K8s native (Operator/CRD) | Yes (k6 Operator) | No | No | No |
| Distributed testing | Yes (via Operator) | No | No | No |
| Metrics output | Prometheus, JSON, CSV, Grafana Cloud | Custom | Custom | None |
| Runtime | Go binary, ~30MB | JVM, heavy | Python + deps | bash |
| Script language | JavaScript | YAML configs | Python | bash |
| Resource overhead | Low (goroutines) | High (JVM threads) | Moderate (GIL) | Minimal |
| Cost | Free, open source | Free, open source | Free, open source | Free |
| Portable across K8s | Yes (EKS, minikube, GKE) | No | No | No |
