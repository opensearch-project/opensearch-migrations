# k6 Load Testing for CDC Pipeline

Load testing scripts for the CDC capture proxy using [k6](https://k6.io/).

## What This Tests

k6 sends HTTP requests to the capture proxy at a controlled rate, simulating customer traffic during a CDC migration. It measures:

- **TPS** — actual requests per second achieved
- **TTFB** — time to first byte (`proxy_ttfb`, proxy processing time)
- **Response time** — time to receive full response (`proxy_receiving`)
- **End-to-end latency** — full round trip per request (`http_req_duration`, p50/p90/p95/p99)
- **Error rate** — percentage of failed requests

k6 does NOT measure Kafka throughput, replayer performance, or data accuracy — those are verified by the existing Python test framework.

## Install k6

**Mac:**

```bash
brew install k6
```

**Linux:**

```bash
wget -O /tmp/k6.tar.gz "https://github.com/grafana/k6/releases/download/v1.7.1/k6-v1.7.1-linux-$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/').tar.gz"
tar xzf /tmp/k6.tar.gz -C /tmp/
sudo mv /tmp/k6-*/k6 /usr/local/bin/
```

## Scripts

### cdc-load-test.js — Basic Auth / No Auth

For local Docker CDC, minikube, or any cluster with basic auth or no auth.

```bash
# No auth (security disabled)
k6 run -e PROXY_ENDPOINT=http://localhost:9201 cdc-load-test.js

# Basic auth
k6 run \
  -e PROXY_ENDPOINT=https://capture-proxy:9201 \
  -e USERNAME=admin \
  -e PASSWORD=admin \
  cdc-load-test.js

# Custom rate and duration
k6 run \
  -e PROXY_ENDPOINT=https://capture-proxy:9201 \
  -e USERNAME=admin \
  -e PASSWORD=admin \
  -e WRITE_RATE=100 \
  -e DURATION=5m \
  cdc-load-test.js

# Large documents (10KB)
k6 run \
  -e PROXY_ENDPOINT=https://capture-proxy:9201 \
  -e USERNAME=admin \
  -e PASSWORD=admin \
  -e DOC_SIZE=large \
  cdc-load-test.js
```

### cdc-load-test-sigv4.js — AWS SigV4 Auth

For AWS-managed OpenSearch domains. Must run from inside the migration console pod (or anywhere with network access to the proxy).

```bash
# Step 1: Generate credentials file (from migration console pod)
python3 -c "
from botocore.session import Session
import json
creds = Session().get_credentials().get_frozen_credentials()
json.dump({
    'accessKeyId': creds.access_key,
    'secretAccessKey': creds.secret_key,
    'sessionToken': creds.token
}, open('/tmp/aws-creds.json', 'w'))
"

# Step 2: Run k6
k6 run \
  -e PROXY_ENDPOINT=https://capture-proxy.ma.svc.cluster.local:9201 \
  -e SOURCE_HOST=<source-cluster-hostname> \
  -e WRITE_RATE=50 \
  -e DURATION=2m \
  cdc-load-test-sigv4.js
```

**Note:** SigV4 credentials expire after ~1 hour. Re-run Step 1 before each test session.

**Key detail:** The script signs requests for the source cluster hostname but sends them to the proxy endpoint. This is required because the proxy forwards requests as-is and the source cluster validates the SigV4 signature against its own hostname.

## Parameters

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `PROXY_ENDPOINT` | Yes | — | Capture proxy URL (e.g. `https://capture-proxy:9201`) |
| `WRITE_RATE` | No | 50 | Requests per second |
| `DURATION` | No | 2m | How long to sustain traffic |
| `INDEX_NAME` | No | cdc-load-test | Target index name |
| `USERNAME` | No | — | Basic auth username (omit for no auth) |
| `PASSWORD` | No | — | Basic auth password |
| `DOC_SIZE` | No | small | Document size: `small` (~100B), `large` (~10KB), `xlarge` (~100KB) |
| `SOURCE_HOST` | SigV4 only | — | Source cluster hostname for SigV4 signing |
| `AWS_CREDS_FILE` | No | /tmp/aws-creds.json | Path to AWS credentials JSON file |
| `AWS_REGION` | No | us-east-1 | AWS region for SigV4 signing |

## Interpreting Output

```
  █ THRESHOLDS

    http_req_duration
    ✓ 'p(95)<2000' p(95)=45ms          ← p95 latency under 2s threshold

    http_req_failed
    ✓ 'rate==0' rate=0.00%             ← 0% error rate

  █ TOTAL RESULTS

    checks_succeeded...: 100.00%       ← all requests returned 200/201
    http_req_duration..: p(50)=16ms p(95)=45ms  ← latency percentiles
    http_reqs..........: 151  5.03/s   ← total requests and throughput
    proxy_ttfb.........: p(50)=15ms    ← time to first byte
    proxy_receiving....: p(50)=0.07ms  ← time to receive response body
```

- **✓** = threshold passed
- **✗** = threshold breached (k6 exits non-zero — fails CI)
- **dropped_iterations** = requests k6 wanted to send but couldn't (system too slow)

## After the Run

Verify the full CDC pipeline delivered all documents:

```bash
# Check source
curl -k -u admin:admin "https://<proxy-or-source>:9200/<index>/_count"

# Check target (after replayer catches up)
curl "http://<target>:9200/<index>/_count"
```

Counts should match.

## Validated Results

| Environment | Auth | Rate | Result | Pipeline |
|-------------|------|------|--------|----------|
| EKS (AWS domains) | SigV4 | 5 req/s, 30s | 151/151, p95=45ms ✅ | Source 301 = Target 301 ✅ |
| Minikube | Basic auth | 5 req/s, 30s | 151/151, p95=501ms ✅ | Source = Target ✅ |
| Minikube | Basic auth | Multi-scenario PoC | 1210/1210, 0% errors ✅ | Source = Target ✅ |

## Limitations

k6 measures the capture proxy HTTP layer only. For full pipeline observability:

| Dimension | k6? | Alternative |
|-----------|-----|-------------|
| Proxy latency, TPS, errors | ✅ | — |
| Document size impact | ✅ | — |
| Connection scaling | ✅ | — |
| Kafka throughput / consumer lag | ❌ | Prometheus (Strimzi metrics) |
| Replayer performance | ❌ | CloudWatch / Prometheus |
| Source/target data accuracy | ❌ | Python test framework |
