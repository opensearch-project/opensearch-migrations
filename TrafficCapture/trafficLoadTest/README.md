# Load Test Traffic Generator

Sends controlled HTTP traffic at the **Capture Proxy** to load-test the capture-and-replay
pipeline. The scenarios run on Kubernetes as **k6-operator `TestRun`** CRs, driven from the
migration console (`workflow k6 …`).

- **How to run it:** [`k8s/README.md`](k8s/README.md) — install the standalone `k6LoadTest` chart,
  then `workflow k6 run …`.
- **This document:** the scenarios, their configuration knobs, and the key implementation
  decisions. Configuration is transport-agnostic: each variable below is a preset value you
  override per run with `-o KEY=VALUE` (or the named `--rate`/`--duration`/`--vus` options).

> The load-test setup is deployed **separately** from any migration (an opt-in chart), so a normal
> migration deployment contains no k6 and cannot trigger a load test. See
> [`k8s/README.md`](k8s/README.md).

---

## Scenarios

Three scenario scripts, selected with `--scenario`:

| `--scenario` | Script | What it does |
|---|---|---|
| `ingest` | `scenarios/ingest.js` | `_bulk` + single-doc writes at a constant/ramping rate; optional stateful create→update→query→delete sequences |
| `search` | `scenarios/search.js` | flat `_search`, aggregations, partial updates, optional deep paging (scroll / search_after) |
| `mixed` | `scenarios/mixed.js` | ingest + search streams concurrently, with a write-then-read consistency check via a Redis ring buffer (Webdis) |

Each scenario reads its load shape from a `k6-config/*.env` **preset** (selected with `--config`,
default `<scenario>-steady`). Presets describe load shape only — no document-schema settings — so
any preset works with any scenario. Available presets: `ingest-steady`, `ingest-ramp`,
`ingest-burst`, `search-steady`, `search-deep-paging`, `search-ramp`, `search-burst`,
`mixed-steady`, `mixed-ramp`, `mixed-burst`.

### Load shapes (ramp / burst)

| Preset | Executor | Description |
|---|---|---|
| `*-steady` | `constant-arrival-rate` | hold a fixed rate for `DURATION` |
| `*-ramp` | `ramping-arrival-rate` | 0→peak over minutes, hold, ramp down |
| `*-burst` | `ramping-arrival-rate` | warm-up → short spike (designed to saturate the proxy) → recover |

> **Burst saturates the proxy on purpose.** k6 exits non-zero when latency/error thresholds breach
> during the spike — that's the *finding* (the saturation point), not a broken test. Add
> `--extra-args --no-thresholds` to keep the run from being marked failed.

---

## Document scenarios

All scripts select a document schema via the `SCENARIO` env var — a **separate axis** from
`--scenario` (which picks the script). Override it with `-o SCENARIO=<type>`:

| `SCENARIO` | Index (default) | Document type |
|---|---|---|
| `nyc_taxis` (default) | `nyc_taxis` | NYC taxi trip records — geo_point, scaled_float, date |
| `logs_data` | `logs_data` | Structured log events — keyword, integer, text, date |

`INDEX_NAME` defaults to the `SCENARIO` value; override with `-o INDEX_NAME=my-index`. The
NYC Taxis schema mirrors `DataGenerator/NycTaxis.java` exactly (same date format, constants and
geo-point array format); the index is `dynamic: strict`, so any mismatch rejects documents.

---

## Configuration reference

Set via preset (default) or per-run override. `-o KEY=VALUE` is applied last and wins.

### Common

| Variable | Default | Meaning |
|---|---|---|
| `SCENARIO` | `nyc_taxis` | document schema (`nyc_taxis` or `logs_data`) |
| `CAPTURE_PROXY_URL` | preset | proxy endpoint (also set by `--target`) |
| `INDEX_NAME` | value of `SCENARIO` | target index |
| `DURATION` | `5m` | scenario run time (`--duration`) |
| `EXECUTOR` | `constant-arrival-rate` | set to `ramping-arrival-rate` for ramp/burst |
| `RAMP_STAGES` | single hold stage | JSON array of k6 stages, e.g. `[{"duration":"2m","target":150}]` |

### Ingest

| Variable | Default | Meaning |
|---|---|---|
| `INGEST_RATE` | `50` | target requests/second (`--rate`) |
| `INGEST_VUS` | `20` | pre-allocated VUs (`--vus`) |
| `INGEST_MAX_VUS` | `100` | max VUs k6 may spin up |
| `BULK_BATCH_SIZE` | `20` | documents per `_bulk` call |
| `SEQUENCE_FRACTION` | `0.15` | share of iterations run as create→update→query→delete |
| `BULK_FRACTION` | `0.70` | share of non-sequence iterations sent as `_bulk` |
| `CONNECTION_MODE` | `pinned` | `pinned` = keep-alive; `spread` = `Connection: close` per request |
| `NO_CONNECTION_REUSE` | _(unset)_ | `true` forces a new TCP connection per request client-side |
| `SEED_DOC_COUNT` | `100000` | expected seed doc count (informational; set `0` to skip the wait) |

### Search

| Variable | Default | Meaning |
|---|---|---|
| `SEARCH_RATE` | `50` | target requests/second |
| `SEARCH_VUS` | `30` | pre-allocated VUs |
| `SEARCH_MAX_VUS` | `150` | max VUs |
| `DEEP_PAGING_ENABLED` | `false` | `true` to activate scroll / search_after |
| `PAGING_MODE` | `scroll` | `scroll` or `search_after` |
| `SEARCH_FLAT_FRACTION` | `0.60` | fraction for flat `_search` |
| `SEARCH_AGG_FRACTION` | `0.20` | fraction for aggregation queries |
| `SEARCH_UPDATE_FRACTION` | `0.10` | fraction for partial updates |

### Mixed (needs Redis+Webdis → chart `registry.enabled=true`)

| Variable | Default | Meaning |
|---|---|---|
| `INGEST_RATE` / `SEARCH_RATE` | `30` / `20` | per-stream target rates |
| `CONSISTENCY_FRACTION` | `0.10` | fraction of search iterations that query a recently-ingested doc |
| `WEBDIS_URL` | `http://webdis:7379` | Webdis HTTP-to-Redis proxy URL |
| `REGISTRY_ENABLED` | `false` | `true` activates the ID ring buffer (`--registry-enabled`); off = consistency reads fall back to flat searches |

### Chaos control (opt-in via `CONTROL_ENABLED=true` / `--control-enabled`)

Control commands are written to a Redis key (via Webdis) and polled by VUs mid-run:

| Command (written to `control_cmd`) | Effect |
|---|---|
| `pause` | all VUs halt within ~50 ms |
| `resume` (or delete the key) | VUs proceed |
| `set-rate:N` | probabilistic skip → effective throughput ≈ N |

---

## Thresholds vs Checks

- **`check()`** (k6 built-in) — observational assertions listed in the run summary. Failed checks do
  **not** cancel the run.
- **thresholds** — pass/fail gates on metrics. A breach fails the run (non-zero exit) unless
  `--extra-args --no-thresholds` is set. On a resource-constrained cluster, latency thresholds may
  breach even when every request succeeds — use `--no-thresholds` there.

---

## Key implementation decisions

These are scenario/tool-level decisions. For the **deployment-architecture** decisions — why k6 is
a separate opt-in chart, why the k6-operator instead of Argo, ConfigMap-vs-image scenario storage,
console-CLI-vs-kubectl submission, etc. — see **[k8s/README.md → Design decisions](k8s/README.md#design-decisions)**.

- **Tool: k6.** Each Virtual User holds one persistent TCP connection — mapping directly to
  `connectionId` in the Capture Proxy, the foundation for connection-pinning in the sequences path.
- **Distributed runs.** The k6-operator splits a run across `--parallelism` runner pods via k6
  execution segments, so `--rate`/`--vus` are global totals divided among pods.
- **Proxy TLS.** The proxy listens HTTPS with a self-signed cert; k6 uses
  `insecureSkipTLSVerify: true`. The source cluster behind the proxy runs plain HTTP.
- **Metrics.** k6 pushes OTLP gRPC to the otel-collector (`K6_OUT=opentelemetry`,
  `K6_OTEL_GRPC_EXPORTER_ENDPOINT=otel-collector:4317`). Output is set via the `K6_OUT` **env var**,
  not `--out`, because the operator also feeds run arguments to the initializer's `k6 archive`,
  which rejects run-only flags. The collector exposes a Prometheus scrape endpoint; the
  `k6-load-test` Grafana dashboard reads it.
- **Document scenarios.** Scripts select a generator via `SCENARIO`; each provides its own index
  mapping (`data/<scenario>/mapping.json`), query samples and update-body generator. Adding a type
  needs only new `lib/data/<name>/{documents,queries}.js` + `data/<name>/mapping.json`.
- **ID registry (mixed).** Cross-VU write-then-read state uses a Redis list via a Webdis HTTP proxy
  — k6's built-in `http` module calls Webdis (`GET /LPUSH/key/val`), so no xk6/native Redis build.
- **Scenario storage.** Scenarios ship as a ConfigMap (`k6-scenarios`) mounted into the runner via
  an `items` projection, not baked into a custom image — so editing a scenario is a `helm upgrade`,
  not an image rebuild. See [`k8s/README.md`](k8s/README.md).
