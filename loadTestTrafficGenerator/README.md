# Load Test Traffic Generator

Sends controlled HTTP traffic at the **Capture Proxy** to load-test the capture-and-replay
pipeline.

---

## Prerequisites

Build the Capture Proxy Docker image (also generates the self-signed TLS certs).
Run from the **repo root**:

```bash
cd /path/to/opensearch-migrations   # repo root (where settings.gradle lives)
./gradlew :TrafficCapture:trafficCaptureProxyServer:jibDockerBuild -Djib.to.image=migrations/capture_proxy:latest
```

---

## Ingest Baseline

### Start the stack

```bash
cd loadTestTrafficGenerator
docker compose up -d --wait kafka opensearch-source capture-proxy otel-collector prometheus grafana
```

### Run the ingest scenario

The k6 service already has all defaults set in `docker-compose.yml`. Run with the
defaults from `k6-config/ingest-steady.env` by passing each variable explicitly
(`docker compose run` does not support `--env-file`):

```bash
docker compose run --rm \
  $(grep -v '^[[:space:]]*#' k6-config/ingest-steady.env | grep -v '^$' | sed 's/^/-e /') \
  k6 run --out=opentelemetry /scripts/scenarios/ingest.js
```

Or override individual variables inline:

```bash
docker compose run --rm -e INGEST_RATE=100 -e DURATION=10m \
  k6 run --out=opentelemetry /scripts/scenarios/ingest.js
```

### Validate

```bash
# checks only — stack and k6 run must already be complete
./scripts/validate_ingest.sh

# start stack + run k6 + validate, all in one go
./scripts/validate_ingest.sh --with-setup

# add --teardown to bring the stack down after validation
./scripts/validate_ingest.sh --with-setup --teardown
```

### What to check manually

| What | Where |
|---|---|
| k6 live output | terminal (req/s, error rate, p95 latency) |
| Grafana dashboard | http://localhost:3000 → Load Testing → Load Test — Capture Proxy Ingest (admin / admin) |
| Kafka consumer lag | `docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups` |
| Capture Proxy logs | `docker compose logs -f capture-proxy` |
| Prometheus metrics | http://localhost:19090 |

The Grafana datasource (Prometheus) and the dashboard are provisioned automatically on startup — no manual configuration needed.

### Tear down

```bash
docker compose down -v
```

---

## Document scenarios

All three scenario scripts (`ingest.js`, `search.js`, `mixed.js`) support multiple document
schemas. Select one by passing `SCENARIO` on the command line:

| `SCENARIO` value | Index (default) | Document type |
|---|---|---|
| `nyc_taxis` (default) | `nyc_taxis` | NYC taxi trip records — geo_point, scaled_float, date |
| `logs_data` | `logs_data` | Structured log events — keyword, integer, text, date |

```bash
# Run log ingest with the steady profile
docker compose run --rm \
  $(grep -v '^[[:space:]]*#' k6-config/ingest-steady.env | grep -v '^$' | sed 's/^/-e /') \
  -e SCENARIO=logs_data \
  k6 run --out=opentelemetry /scripts/scenarios/ingest.js
```

`INDEX_NAME` defaults to the `SCENARIO` value. Override explicitly if needed:
```bash
-e SCENARIO=logs_data -e INDEX_NAME=my-logs-index
```

Any load-profile env file (`ingest-steady.env`, `ingest-burst.env`, etc.) works with any scenario —
they describe load shape only and contain no document-schema settings.

---

## Configuration

All scenario parameters are set via environment variables. Edit the relevant `k6-config/*.env`
file or override on the command line with `-e KEY=VALUE`.

| Variable | Default | Meaning |
|---|---|---|
| `SCENARIO` | `nyc_taxis` | Document schema: `nyc_taxis` or `logs_data` |
| `CAPTURE_PROXY_URL` | `https://capture-proxy:9200` | Proxy endpoint |
| `INDEX_NAME` | value of `SCENARIO` | Target index (override to use a custom name) |
| `INGEST_RATE` | `50` | Target requests/second |
| `INGEST_VUS` | `20` | Pre-allocated VUs (≈ connections) |
| `INGEST_MAX_VUS` | `100` | Max VUs k6 may spin up |
| `DURATION` | `5m` | Scenario run time |
| `BULK_BATCH_SIZE` | `20` | Documents per `_bulk` call |
| `SEED_DOC_COUNT` | `100000` | Expected seed doc count (informational) |

---

## Stateful Sequences

### Run with sequences (pinned mode)

```bash
docker compose run --rm \
  $(grep -v '^[[:space:]]*#' k6-config/ingest-steady.env | grep -v '^$' | sed 's/^/-e /') \
  -e SEQUENCE_FRACTION=0.15 \
  -e CONNECTION_MODE=pinned \
  k6 run --out=opentelemetry /scripts/scenarios/ingest.js
```

To test spread mode (forces a new TCP connection per request — sequences may replay out of order):

```bash
docker compose run --rm \
  $(grep -v '^[[:space:]]*#' k6-config/ingest-steady.env | grep -v '^$' | sed 's/^/-e /') \
  -e SEQUENCE_FRACTION=0.15 \
  -e CONNECTION_MODE=spread \
  k6 run --out=opentelemetry /scripts/scenarios/ingest.js
```

### Validate

```bash
./scripts/validate_sequences.sh
./scripts/validate_sequences.sh --with-setup
./scripts/validate_sequences.sh --with-setup --teardown
```


### New environment variables

| Variable | Default | Meaning |
|---|---|---|
| `SEQUENCE_FRACTION` | `0.15` | Share of iterations run as a create→update→query→delete sequence |
| `BULK_FRACTION` | `0.70` | Share of non-sequence iterations sent as `_bulk` (remainder → single-doc POSTs) |
| `CONNECTION_MODE` | `pinned` | `pinned` = keep-alive (one stream); `spread` = `Connection: close` (one stream per request) |

---

## Search Profile

### Run the search scenario (steady, no deep paging)

```bash
docker compose run --rm \
  $(grep -v '^[[:space:]]*#' k6-config/search-steady.env | grep -v '^$' | sed 's/^/-e /') \
  k6 run --out=opentelemetry /scripts/scenarios/search.js
```

To enable deep paging (scroll or search_after sequences for 5% of iterations):

```bash
docker compose run --rm \
  $(grep -v '^[[:space:]]*#' k6-config/search-deep-paging.env | grep -v '^$' | sed 's/^/-e /') \
  k6 run --out=opentelemetry /scripts/scenarios/search.js
```

Switch between scroll and search_after by overriding `PAGING_MODE`:

```bash
docker compose run --rm \
  $(grep -v '^[[:space:]]*#' k6-config/search-deep-paging.env | grep -v '^$' | sed 's/^/-e /') \
  -e PAGING_MODE=search_after \
  k6 run --out=opentelemetry /scripts/scenarios/search.js
```

### Validate

```bash
./scripts/validate_search.sh
./scripts/validate_search.sh --with-setup
./scripts/validate_search.sh --with-setup --teardown
./scripts/validate_search.sh --with-setup --deep-paging   # uses search-deep-paging.env
```

Steps 1–8 are automated. Step 10 (Replayer memory growth) requires the Traffic Replayer
to be running separately — see the script output for guidance.

### New environment variables

| Variable | Default | Meaning |
|---|---|---|
| `SEARCH_RATE` | `50` | Target requests/second |
| `SEARCH_VUS` | `30` | Pre-allocated VUs |
| `SEARCH_MAX_VUS` | `150` | Max VUs k6 may spin up |
| `DEEP_PAGING_ENABLED` | `false` | `true` to activate scroll / search_after steps |
| `PAGING_MODE` | `scroll` | `scroll` or `search_after` |
| `SCROLL_PAGES` | `3` | Max pages per scroll sequence |
| `SEARCH_AFTER_PAGES` | `3` | Max pages per search_after sequence |
| `CONNECTION_MODE` | `pinned` | Same as Stateful Sequences |
| `SEARCH_FLAT_FRACTION` | `0.60` | Fraction of iterations for flat `_search` (term / range / bool) |
| `SEARCH_AGG_FRACTION` | `0.20` | Fraction of iterations for aggregation queries |
| `SEARCH_UPDATE_FRACTION` | `0.10` | Fraction of iterations for partial updates |
| `SEARCH_WRITE_FRACTION` | `0.05` | Fraction of iterations for single-doc writes; remainder → deep paging or flat fallback |

The `search` scenario auto-appears in the Grafana Scenario drop-down — no dashboard changes needed.

---

## Mixed Profile

Runs the ingest and search streams concurrently. A Redis ring buffer (accessed via Webdis)
lets ingest VUs register newly-created document IDs so that `CONSISTENCY_FRACTION` of search
VUs can query those exact documents — exercising write-then-read ordering through the pipeline.

### Start the stack

```bash
docker compose up -d --wait kafka opensearch-source capture-proxy otel-collector prometheus grafana redis webdis
```

### Run the mixed scenario

```bash
docker compose run --rm \
  $(grep -v '^[[:space:]]*#' k6-config/mixed-steady.env | grep -v '^$' | sed 's/^/-e /') \
  k6 run --out=opentelemetry /scripts/scenarios/mixed.js
```

To dial the streams independently:

```bash
docker compose run --rm \
  $(grep -v '^[[:space:]]*#' k6-config/mixed-steady.env | grep -v '^$' | sed 's/^/-e /') \
  -e INGEST_RATE=50 -e SEARCH_RATE=10 \
  k6 run --out=opentelemetry /scripts/scenarios/mixed.js
```

### Validate

```bash
./scripts/validate_mixed.sh
./scripts/validate_mixed.sh --with-setup
./scripts/validate_mixed.sh --with-setup --teardown
```

### New environment variables

| Variable | Default | Meaning |
|---|---|---|
| `INGEST_RATE` | `30` | Ingest stream target requests/second |
| `SEARCH_RATE` | `20` | Search stream target requests/second |
| `INGEST_VUS` | `15` | Pre-allocated ingest VUs |
| `INGEST_MAX_VUS` | `75` | Max ingest VUs |
| `SEARCH_VUS` | `15` | Pre-allocated search VUs |
| `SEARCH_MAX_VUS` | `75` | Max search VUs |
| `SEQUENCE_FRACTION` | `0.15` | Fraction of ingest iterations run as create→update→query→delete |
| `BULK_FRACTION` | `0.70` | Fraction of non-sequence ingest iterations sent as `_bulk` (remainder → single-doc with registry write) |
| `CONSISTENCY_FRACTION` | `0.10` | Fraction of search iterations that query a recently-ingested doc |
| `SEARCH_FLAT_FRACTION` | `0.60` | Fraction of non-consistency search iterations for flat `_search` |
| `SEARCH_AGG_FRACTION` | `0.20` | Fraction of non-consistency search iterations for aggregation queries |
| `SEARCH_UPDATE_FRACTION` | `0.10` | Fraction of non-consistency search iterations for partial updates; remainder → single-doc write |
| `WEBDIS_URL` | `http://webdis:7379` | Webdis HTTP-to-Redis proxy URL |
| `REGISTRY_ENABLED` | `false` | `true` to activate the ID ring buffer; when `false` all registry calls are no-ops and the consistency fraction falls back to flat searches (safe to run without Redis/Webdis) |

The `mixed_ingest` and `mixed_search` scenarios auto-appear in the Grafana Scenario drop-down.

---

## Burst and Ramp Profiles

Adds `ramping-arrival-rate` load shapes to find the Capture Proxy saturation point and observe
Kafka lag accumulation under load spikes. Three profiles:

| Profile | Env file | Scenario | Description |
|---|---|---|---|
| Ramp | `ingest-ramp.env` | `ingest.js` | 0→150 req/s over 2m, hold 3m, ramp down 1m |
| Burst | `ingest-burst.env` | `ingest.js` | 20 req/s warm-up → 200 req/s spike (30s) → recover |
| Mixed ramp | `mixed-ramp.env` | `mixed.js` | Ingest 0→80 req/s, search 0→50 req/s, independent ramps |

### Run the ramp profile

```bash
docker compose up -d --wait kafka opensearch-source capture-proxy otel-collector prometheus grafana

docker compose run --rm \
  $(grep -v '^[[:space:]]*#' k6-config/ingest-ramp.env | grep -v '^$' | sed 's/^/-e /') \
  k6 run --out=opentelemetry /scripts/scenarios/ingest.js
```

### Run the burst profile

```bash
docker compose run --rm \
  $(grep -v '^[[:space:]]*#' k6-config/ingest-burst.env | grep -v '^$' | sed 's/^/-e /') \
  k6 run --out=opentelemetry /scripts/scenarios/ingest.js
```

> **Note:** The burst profile is designed to saturate the proxy. k6 will exit non-zero when
> error-rate and latency thresholds breach during the 200 req/s spike — this is the expected
> finding (saturation point), not a broken test.

### Run the mixed-ramp profile

Redis + Webdis are required (same as Mixed Profile):

```bash
docker compose up -d --wait kafka opensearch-source capture-proxy otel-collector prometheus grafana redis webdis

docker compose run --rm \
  $(grep -v '^[[:space:]]*#' k6-config/mixed-ramp.env | grep -v '^$' | sed 's/^/-e /') \
  k6 run --out=opentelemetry /scripts/scenarios/mixed.js
```

### Validate

```bash
# Ramp profile only
./scripts/validate_load_shapes.sh --with-setup

# Ramp + burst
./scripts/validate_load_shapes.sh --with-setup --with-burst

# Ramp + mixed-ramp (starts Redis+Webdis automatically)
./scripts/validate_load_shapes.sh --with-setup --with-mixed-ramp

# All three profiles + teardown
./scripts/validate_load_shapes.sh --with-setup --with-burst --with-mixed-ramp --teardown
```

### New environment variables

For `ingest.js` and `mixed.js`:

| Variable | Default | Meaning |
|---|---|---|
| `EXECUTOR` | `constant-arrival-rate` | Switch to `ramping-arrival-rate` for ramp/burst shapes |
| `RAMP_STAGES` | single hold stage | JSON array of k6 stage objects, e.g. `[{"duration":"2m","target":150},{"duration":"1m","target":0}]` |

Additional variables for `mixed.js` ramping (override `RAMP_STAGES` per stream):

| Variable | Default | Meaning |
|---|---|---|
| `INGEST_RAMP_STAGES` | single hold stage | Stage array for the ingest stream |
| `SEARCH_RAMP_STAGES` | single hold stage | Stage array for the search stream |
| `MIN_RING_FILL` | `0` | Minimum IDs in the Redis ring before search VUs start; converted to a `startTime` delay using `INGEST_RATE × (1−SEQUENCE_FRACTION) × (1−BULK_FRACTION)` IDs/s |

> When `DURATION` is set and `RAMP_STAGES` is not, the scenario falls back to a single
> hold-at-`INGEST_RATE` stage — existing env files work unchanged.

---

## Chaos Integration Hooks

Exposes pause/resume/rate-throttle control points so an orchestration layer can inject faults
at defined moments within a live test run. Uses the existing Webdis sidecar as a shared
signalling bus — no new services required.

Control is **opt-in**: pass `CONTROL_ENABLED=true` when launching k6. All existing env files
work unchanged (default is no-op).

### Run with control enabled

```bash
docker compose up -d --wait kafka opensearch-source capture-proxy otel-collector prometheus grafana redis webdis

docker compose run --rm \
  $(grep -v '^[[:space:]]*#' k6-config/ingest-steady.env | grep -v '^$' | sed 's/^/-e /') \
  -e CONTROL_ENABLED=true \
  k6 run --out=opentelemetry /scripts/scenarios/ingest.js
```

### Send commands while k6 is running (from host)

```bash
# Pause all VUs (traffic to Capture Proxy stops within ~50ms):
curl -s "http://localhost:7379/SET/control_cmd/pause"

# Resume:
curl -s "http://localhost:7379/DEL/control_cmd"

# Throttle to ~10 req/s (80% skip at default 50 req/s baseline):
curl -s "http://localhost:7379/SET/control_cmd/set-rate%3A10"

# Clear throttle:
curl -s "http://localhost:7379/DEL/control_cmd"
```

### Validate

```bash
./scripts/validate_chaos.sh --with-setup
./scripts/validate_chaos.sh --with-setup --teardown
```

The script runs k6 in the background and exercises pause → resume → set-rate automatically,
using Kafka offset snapshots to confirm traffic stopped and restarted.

> **Note on latency thresholds:** while VUs are paused they accumulate sleep time inside
> `checkControl()`, which inflates `http_req_duration`. k6 will exit non-zero if p95 thresholds
> breach during a pause. For chaos runs where latency is not the primary assertion, pass
> `--no-thresholds` to `k6 run` to disable threshold evaluation for the run:
>
> ```bash
> k6 run --out=opentelemetry --no-thresholds /scripts/scenarios/ingest.js
> ```
>
> `validate_chaos.sh` does this automatically — the flag is already wired in.

### New environment variables

| Variable | Default | Meaning |
|---|---|---|
| `CONTROL_ENABLED` | `false` | `true` to enable mid-test control polling via Webdis |
| `CONTROL_CMD_KEY` | `control_cmd` | Redis key name polled for commands |

### Commands reference

| Command string (written to `control_cmd`) | Effect |
|---|---|
| `pause` | All VUs halt within ~50ms; sleep loop until command changes |
| `resume` (or DEL key) | Exits pause; VUs proceed normally |
| `set-rate:N` | Probabilistic skip: effective throughput ≈ N/baseRate × configured rate |


---

## Key implementation decisions

- **Tool: k6.** Each Virtual User (VU) holds one persistent TCP connection — this maps directly to
  `connectionId` in the Capture Proxy and is the foundation for connection-pinning in the Sequences scenario.

- **Proxy TLS.** The Capture Proxy listens on HTTPS with a self-signed cert (generated by
  `generateSelfSignedCerts` Gradle task, baked into the Docker image). k6 uses
  `insecureSkipTLSVerify: true`. The source cluster behind the proxy runs plain HTTP with
  security disabled — the proxy handles all TLS termination.

- **Metrics.** All metrics funnel through the OTEL collector. Capture Proxy pushes OTLP gRPC to
  the collector (`:4317`). Kafka broker/topic/consumer metrics are scraped by the collector's
  `kafkametricsreceiver`. k6 pushes OTLP gRPC (`--out=opentelemetry`, `K6_OTEL_GRPC_EXPORTER_ENDPOINT=otel-collector:4317`)
  to the same OTLP receiver — no custom k6 image needed. The collector exposes a single Prometheus
  scrape endpoint at `:8889`. k6 timing histograms arrive in milliseconds and are exposed as
  `http_req_duration_milliseconds_bucket/sum/count`; query p95 via
  `histogram_quantile(0.95, rate(http_req_duration_milliseconds_bucket{name="..."}[5m]))`.

- **Document scenarios.** All scenario scripts select a document generator via the `SCENARIO` env
  var (`nyc_taxis` default, `logs_data` also available). Each scenario provides its own index
  mapping (`data/<scenario>/mapping.json`), field-value samples for queries, and partial-update
  body generator. The NYC Taxis schema mirrors `DataGenerator/NycTaxis.java` exactly (same date
  format, same constants, same geo-point array format) — the index is `dynamic: strict`, so any
  mismatch causes rejected documents. Adding a new document type requires only a new
  `lib/<name>/documents.js` and `lib/<name>/queries.js` alongside the data files; the scenario
  scripts pick it up via the `SCENARIO` dispatch.

- **Operation mix.** Ingest baseline: 70% `_bulk` writes, 30% single-doc POSTs. Sequences adds
  stateful sequences (create → update → query → delete); the budget is redistributed using
  `SEQUENCE_FRACTION` (default 0.15). `CONNECTION_MODE=pinned` (default) keeps all VU requests
  on one TCP connection; `CONNECTION_MODE=spread` forces `Connection: close` per request.
  Search adds a separate search scenario: 60% flat search, 20% aggregations, 10% partial update,
  5% single-doc write, 5% deep paging (scroll or search_after, off by default).

- **Scroll safety.** Scroll contexts are closed in a `try/finally` block — leaks cannot
  accumulate even if a page fetch fails or k6 is interrupted mid-sequence.

- **ID registry (Mixed Profile).** Cross-VU shared state (ingest → search write-then-read) is
  implemented via a Redis list accessed through a Webdis HTTP proxy (`anapsix/webdis`).
  k6 uses its built-in `http` module to call Webdis over HTTP (`GET /LPUSH/key/val`, etc.) —
  no custom k6 build or xk6 extension required. This avoids the native Redis client modules
  (`k6/experimental/redis` was removed from k6; `k6/x/redis` requires a custom binary build
  and exposes only a subset of Redis commands).

- **Chaos control.** Control commands (`pause`, `resume`, `set-rate:N`) are written
  to a Redis key by the orchestration layer and polled by VUs via the same Webdis sidecar.
  Opt-in via `CONTROL_ENABLED=true`; fail-open (Webdis unreachable → proceed normally).

---

## Validation scripts

| Script | What it validates |
|---|---|
| `validate_ingest.sh` | `_bulk` + single-doc writes at constant rate → Kafka → OpenSearch |
| `validate_sequences.sh` | Stateful create → update → query → delete; connection pinning; doc leak check |
| `validate_search.sh` | Queries, aggregations, deep paging (scroll / search_after); scroll context leak check |
| `validate_mixed.sh` | Concurrent ingest + search; Webdis ID registry; consistency metrics |
| `validate_load_shapes.sh` | `ramping-arrival-rate` profiles: ramp, burst, mixed-ramp |
| `validate_chaos.sh` | Pause/resume/rate-throttle via Webdis; Kafka offset delta assertions |


## Thresholds vs Checks
- `check()` calls (k6 built-in) are used for checks whose outcome is listed in the k6 summary after a run. Failed
  checks do not cancel the run or similar, its solely observational.
- thresholds on metrics configured in the run script. If those checks fail, this can abort the run if the flag to 
  ignore thresholds is not set (`--no-thresholds`).


## Usage Notes

- **CaptureProxy flag rename**: `--otelCollectorEndpoint` was split into `--otelTraceCollectorEndpoint`
  and `--otelMetricsCollectorEndpoint`. If you see a JCommander error about an unknown main parameter
  on proxy startup, your local image is stale — rebuild it:
  ```bash
  ./gradlew :TrafficCapture:trafficCaptureProxyServer:jibDockerBuild -Djib.to.image=migrations/capture_proxy:latest
  ```

- **Jib ENTRYPOINT**: Jib bakes `java -cp @/app/jib-classpath-file CaptureProxy` as the image
  `ENTRYPOINT`. `docker-compose.yml` therefore uses an explicit `entrypoint:` key (not just `command:`)
  to run `/runJavaWithClasspath.sh` — without it, the script name lands as a JCommander argument and
  the proxy exits immediately with code 2.

- **Prometheus config changes**: After editing `prometheus.yaml`, restart Prometheus to pick up the change:
  ```bash
  docker compose restart prometheus
  ```
