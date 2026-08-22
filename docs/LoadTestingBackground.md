# Load Testing the Traffic Capture & Replay Pipeline: Background and Problem Statement

## Overview

This document captures the background, architecture, and problem statement for load testing the
Traffic Capture and Replay pipeline in this repository (`opensearch-migrations`). It is a reference
for the traffic generation design work that follows. Implementation and tool selection are covered
separately.

The pipeline is the "live capture and replay" (change-data-capture) path used to mirror traffic
from a source cluster onto a target cluster during a migration. For the component-level design, see
[`TrafficCaptureAndReplayDesign.md`](./TrafficCaptureAndReplayDesign.md),
[`replayerArchitecture.md`](./replayerArchitecture.md), and
[`ScalingTrafficCaptureAndReplayer.md`](./ScalingTrafficCaptureAndReplayer.md). This document is
concerned specifically with how to load-test that pipeline.

---

## Scope and Applicability (Source Engines)

The migration assistant supports OpenSearch/Elasticsearch and Solr sources, but they take different
migration paths and relate differently to the capture/replay pipeline.

| Source engine | Capture & replay | Supported migration path | In scope here? |
|---|---|---|---|
| **OpenSearch / Elasticsearch** | Supported | Capture Proxy → Kafka → Traffic Replayer; or snapshot backfill | **Yes** — primary focus |
| **Solr** | Not officially supported (emerging; see below) | Snapshot **backfill** — `CreateSnapshot` → snapshot (FS/S3) → metadata conversion → Reindex-From-Snapshot (`DocumentsFromSnapshotMigration`) | **Partially** |

The root [`README.md`](../README.md) states the supported position: *"Backfill only — Capture and
Replay is not supported for Solr sources."* Load testing the supported Solr path is therefore a
Reindex-From-Snapshot problem (snapshot read throughput, bulk-load rate, Lucene shard reading) — a
separate exercise with no Capture Proxy, Kafka, or Replayer, and out of scope here.

This document therefore targets **OpenSearch/Elasticsearch**. Solr is not entirely outside the
pipeline, though — an emerging Solr-via-replayer path exists with caveats that matter if it is ever
load-tested (notably for deep paging). Those details are in
[Appendix B](#appendix-b--solrs-nuanced-relationship-to-capturereplay).

---

## System Architecture

The pipeline under test has five actors. Three are components in this repository (the **Capture
Proxy**, **Kafka**, and the **Traffic Replayer**); two are the **source** and **target**
OpenSearch/Elasticsearch clusters that represent the real-world environment (the primary supported
case — see [Scope](#scope-and-applicability-source-engines) for Solr's more nuanced relationship to
this pipeline).

```
                  ┌──────────────────────────────────────────────────────────────────┐
                  │                    CAPTURE PATH (live traffic)                   │
                  │                                                                  │
  ┌──────────┐    │  ┌─────────────────────────────┐      ┌───────────────────────┐  │
  │          │HTTP│  │       CAPTURE PROXY         │ HTTP │     SOURCE CLUSTER    │  │
  │  Traffic │───►│  │ trafficCaptureProxyServer   │─────►│   (OpenSearch /       │  │
  │Generator │    │  │ Netty; NettyScanningHttp-   │◄─────│    Elasticsearch)     │  │
  │          │    │  │ Proxy                       │      │                       │  │
  └──────────┘    │  │ • mutating reqs HELD until  │      └───────────────────────┘  │
                  │  │   Kafka commit, THEN sent   │                                 │
                  │  │   to source                 │                                 │
                  │  │ • GETs forwarded now,       │                                 │
                  │  │   offloaded async           │                                 │
                  │  │ memory: LOW while Kafka     │                                 │
                  │  │ keeps up                    │                                 │
                  │  └─────────────┬───────────────┘                                 │
                  │                │ TrafficStream protobufs (req + response bytes)  │
                  └────────────────┼─────────────────────────────────────────────────┘
                                   │  KafkaCaptureFactory (captureKafkaOffloader)
                                   ▼
                  ┌────────────────────────────────────┐
                  │                KAFKA               │
                  │  topic: logging-traffic-topic      │
                  │  partition key: connectionId       │
                  │  (durable buffer; hours–days at    │
                  │   normal rates)                    │
                  │                                    │
                  │  failure modes:                    │
                  │  • slow write path                 │
                  │  • partition / broker outage       │
                  │  • consumer lag accumulation       │
                  └────────────────┬───────────────────┘
                                   │  BlockingTrafficSource (backpressure, at-least-once)
                  ┌────────────────┼───────────────────────────────────────────────────┐
                  │                ▼            REPLAY PATH (shadow traffic)           │
                  │  ┌─────────────────────────────┐      ┌────────────────────────┐   │
                  │  │      TRAFFIC REPLAYER       │ HTTP │     TARGET CLUSTER     │   │
                  │  │ trafficReplayer; Netty      │─────►│   (OpenSearch /        │   │
                  │  │                             │◄─────│    Elasticsearch)      │   │
                  │  │ • reconstructs by           │      │                        │   │
                  │  │   connectionId (accumulator)│      └────────────────────────┘   │
                  │  │ • IJsonTransformer rewrites │                                   │
                  │  │ • holds full req+resp tuple │                                   │
                  │  │   for comparison            │                                   │
                  │  │ memory: HIGH                │                                   │
                  │  └─────────────┬───────────────┘                                   │
                  │                │ JSON tuples (src vs target req/resp)              │
                  │                ▼                                                   │
                  │     ResultsToLogsConsumer → output_tuples.log   +   OTEL metrics   │
                  └────────────────────────────────────────────────────────────────────┘
```

### Memory asymmetry

The Capture Proxy and Traffic Replayer have fundamentally different memory profiles at the same
request rate. The asymmetry is a direct consequence of their designs:

```
Capture Proxy memory ≈ (mutating_req/s × kafka_commit_latency × avg_request_body_size)
                          ── mutating requests are HELD until committed to Kafka
                       + (req/s × offload_lag × avg_message_size)
                          ── async offload backlog of GETs + captured responses
                       (bounded while Kafka keeps up; Kafka write/commit throughput
                        is the primary pressure variable — not source latency)

Replayer memory      ≈ messages_in_flight_to_target
                       × full_reconstituted_(request + response)_tuple_size
                       + IJsonTransformer working buffers
                       + comparison/tuple accumulation awaiting write-out
                       (NOT streaming — each connection's messages are reconstructed
                        and held whole)

Kafka lag rate       = proxy_write_rate − replayer_consume_rate
                       (compounds over time; a small sustained gap becomes a large backlog)
```

The replayer's memory burden is meaningfully larger at equivalent throughput because it holds whole
tuples for comparison while the proxy mostly streams bytes through. The proxy's one *non-streaming*
behaviour — committing mutating requests (PUT/POST/DELETE/PATCH) to Kafka **before** releasing them
to the source — is its main backpressure mechanism: a slow Kafka write path directly raises
client-visible write latency.

---

## What We Are Testing

The goal is not to find the maximum throughput of the source or target clusters. The goal is to
understand how the pipeline components behave across a matrix of **load level × downstream health**.

Specifically:

1. **Capture Proxy correctness under load** — does the proxy handle high connection rates, large
   bodies, and stateful request sequences (create → update → query → delete) without dropping,
   reordering, or corrupting captured `TrafficStream` data?

2. **Capture Proxy memory and backpressure** — when Kafka write throughput degrades, mutating
   requests are held until commit (coupling write correctness and latency). Does that backpressure
   surface cleanly as added client latency, or does in-flight state grow unbounded? How much does the
   async offload backlog (GETs + responses) add under sustained load?

3. **Kafka as a buffer** — does the pipeline behave correctly when Kafka is underscaled or
   temporarily unavailable? Does the proxy handle a slow-write path and a broker outage gracefully,
   and does the replayer recover (at-least-once, via `BlockingTrafficSource`) without data loss when
   Kafka returns?

4. **Traffic Replayer memory under realistic load** — given that the replayer reconstructs and holds
   full request+response tuples per `connectionId` during transformation and comparison, what is the
   practical memory ceiling at various request rates and body sizes? What happens when the target
   cluster is slower than the source?

5. **Kafka consumer lag and catch-up** — when the replayer falls behind (e.g. because the target
   cluster is slower than the source), lag compounds over time. When pressure is relieved, does the
   replayer catch up smoothly or burst requests at the target in a way that causes secondary
   failures?

6. **Horizontal scaling** — we intend to support scaling the Capture Proxy and Traffic Replayer
   horizontally to meet demand. This apparatus is designed with TDD in mind: it will validate
   scaling behaviour before and as that feature is built out. Because Kafka is partitioned by
   `connectionId`, key questions are whether adding proxy or replayer instances preserves
   per-connection ordering and whether consumer-group rebalancing is handled without gaps or
   duplicate replays. See [`ScalingTrafficCaptureAndReplayer.md`](./ScalingTrafficCaptureAndReplayer.md)
   for the current scaling design.

---

## Failure Modes Under Test

### Capture Proxy ↔ Source cluster

| Failure | How to induce | What to assert |
|---|---|---|
| Slow source responses | Inject latency on the source cluster | Proxy holds in-flight responses for async offload without OOM; client latency rises proportionally |
| Source at capacity | Inject latency + reduce connection cap | Proxy sheds load cleanly; returns appropriate errors; does not fall over |
| Source unreachable | Drop connections to the source | Proxy fails fast; recovers without a reconnect storm |

### Capture Proxy ↔ Kafka

| Failure | How to induce | What to assert |
|---|---|---|
| Slow Kafka writes | Underscale Kafka brokers / throttle broker network | Mutating-request latency rises (they block on commit); in-flight state stays bounded; no unbounded buffering |
| Kafka outage | Stop / partition brokers mid-test | Proxy behaviour during outage (block vs. fail mutating requests); recovery after brokers return; no lost `TrafficStream` data |

### Traffic Replayer ↔ Target cluster

| Failure | How to induce | What to assert |
|---|---|---|
| Slow target responses | Inject latency on the target cluster | Replayer memory growth rate; Kafka consumer-lag accumulation rate |
| Target at capacity | Inject latency + reduce connection cap | Replayer backs off cleanly; lag grows predictably; no OOM |
| Target unreachable | Drop connections to the target | Replayer halts or retries safely (at-least-once); lag grows; no data loss on recovery |
| Lag catch-up burst | Relieve target pressure after lag has built | Replayer resumes at a controlled rate; no secondary failure on the target |

### Sequence ordering and `connectionId` partitioning

The Capture Proxy keys Kafka records by **`connectionId`** — every `TrafficStream` for a single TCP
connection lands on the same partition and is therefore consumed in order, and the replayer
reconstructs each connection's requests via the `CapturedTrafficToHttpTransactionAccumulator`. The
direct consequence for testing:

- **Ordering is guaranteed *within* a connection, not *across* connections.** A stateful sequence
  (create → update → query → delete) replays in the captured order **only if those requests share a
  single connection** (HTTP keep-alive on one TCP socket). If the client spreads the sequence across
  several connections, the requests land on different partitions and there is no cross-partition
  ordering guarantee on replay.
- This makes connection reuse a **first-class dimension of the traffic generator**, not an
  incidental detail: the generator must be able to pin a sequence to one connection (to test
  coherent replay) and to deliberately spread a sequence across connections (to exercise the
  ordering edge cases and confirm how the pipeline behaves when ordering is *not* guaranteed).
- A violated sequence (e.g. update replayed before its create) produces a different failure
  signature than a slow response — it surfaces as a target-side error or a silent comparison
  mismatch in the `output_tuples.log` output, not as a latency anomaly.

---

## Test Infrastructure Components

A complete pipeline load test involves the components below. They fall into two categories, and it
is important not to confuse them:

- **System under test (already exists).** The Capture Proxy, Kafka, and the Traffic Replayer are the
  existing pipeline. We do **not** build these — the load test *exercises* them. The harness does
  need to deploy, scale, and chaos-test them (e.g. underscale Kafka brokers), but the components
  themselves are already in the repo (`trafficCaptureProxyServer`, Apache Kafka in the
  `dockerSolution` compose, `trafficReplayer`). These are listed below only where the test harness
  must *control* them; their internal behaviour is covered in the architecture sections above.
- **Test scaffolding (new, or assembled from existing parts).** The Traffic Generator, the
  simulated Source/Target clusters, and the Orchestration Layer are what this effort builds. The
  Traffic Generator and Orchestration Layer are largely new; the simulated clusters are assembled
  from off-the-shelf mock/chaos tooling.

Each component below is tagged accordingly.

### 1. Traffic Generator  *(new — primary focus of this effort)*

**Role:** generates a controlled, realistic HTTP request stream directed at the **Capture Proxy**
(not directly at the source cluster — the proxy is the entry point of the pipeline under test).

**Configuration dimensions:**
- Connection rate (connections/s) and connection lifetime / keep-alive reuse
- Request rate (req/s), with steady, ramping, and bursty profiles
- Request body size distribution (small, medium, large; `_bulk` batch sizes)
- Operation-type mix (write / update / query / paging — see Workload Profiles below)
- Stateful sequences (create → update → query → aggregate → delete) with referential integrity,
  and explicit control over whether a sequence is pinned to one connection (`connectionId`) or
  spread across several

**Existing building blocks in this repo:** the [`DataGenerator`](../DataGenerator) module already
produces OpenSearch workloads (`HTTP_LOGS`, `GEONAMES`, `NESTED`, `NYC_TAXIS`) via
`./gradlew DataGenerator:run`, and the Migration Console wraps **OpenSearch Benchmark** (e.g.
`runTestBenchmarks.sh`) to drive traffic through the Capture Proxy. These cover bulk data
generation but not the connection-control, latency-blending, and per-connection sequence pinning
this effort needs — that gap is what the traffic generator design addresses.

**Stress scenarios:** ramp to and past Capture Proxy capacity; sustained load at varying rates;
burst after a quiet period.

### 2. Source Cluster  *(test scaffolding — real cluster and/or simulated downstream)*

**Role:** the OpenSearch/Elasticsearch cluster that the Capture Proxy forwards live traffic to. It
can be either of two things, used for different purposes:

- **Real** — an actual running cluster. Gives realistic responses and a faithful baseline, but its
  latency, error rate, and failure timing are not directly controllable.
- **Simulated** — a programmable stand-in (e.g. a mock HTTP responder such as WireMock, or a
  network-chaos layer such as Toxiproxy, optionally fronting a small real cluster) whose responses
  *imitate* a cluster. The point is controllability: latency, error rate, bandwidth, and connection
  behaviour can be dialed on demand.

Because the system under test is the **pipeline, not the cluster**, the simulated form is what makes
the configuration dimensions and stress scenarios below achievable — a real cluster will not produce
a 503 storm or become unreachable on command. Use a real cluster for realistic baselines; use a
simulated one to induce the failure modes.

**Configuration dimensions:**
- Response latency (nominal, degraded, bimodal fast/slow, outlier injection)
- Error rate (4xx / 5xx injection at configurable percentages)
- Bandwidth cap (to simulate a saturated upstream network)
- Connection limit (to simulate a cluster at capacity)

**Stress scenarios:** healthy baseline; slow responses; partial failure (percentage of requests
failing); complete unavailability; recovery after outage.

### 3. Target Cluster  *(test scaffolding — real cluster and/or simulated downstream)*

**Role:** the OpenSearch/Elasticsearch cluster that the Traffic Replayer sends replayed traffic to —
**real or simulated** in the same sense as the source cluster above. Its behaviour independently
controls replayer memory pressure and Kafka consumer lag, so a simulated target is especially useful
here for holding the target slower than the source on demand.

**Configuration dimensions:** same as the source cluster, but set independently. The migration-
relevant case is a target slower than the source.

**Stress scenarios:** target slower than source (common during migration); target at capacity;
target unavailable; target recovering while a lag backlog is present.

### 4. Kafka  *(existing — part of the system under test; harness deploys/scales/chaos-tests it)*

**Role:** the durable buffer between capture and replay (topic `logging-traffic-topic`, partitioned
by `connectionId`), already part of the pipeline (Apache Kafka in the `dockerSolution` compose). It
is not built for the test; the harness only controls its deployment to stress the pipeline. Its
throughput and availability directly drive proxy backpressure and replayer lag.

**Configuration dimensions:**
- Broker count and partition count (throughput ceiling; partition count also bounds replay
  parallelism, since ordering is per-partition)
- Network bandwidth limits between the proxy and brokers
- Retention size/time (disk overflow is **not** a primary concern — capacity is hours–days at
  normal rates)

**Stress scenarios:** underscaled brokers (slow write path); broker outage mid-test; broker
recovery while the proxy has traffic to flush.

### 5. Orchestration Layer  *(new)*

**Role:** coordinates all components across a test run — starts load, injects faults at defined
points, collects metrics from all five actors, and produces a unified report.

**Responsibilities:**
- Start / stop traffic generator scenarios
- Apply and remove chaos conditions on the source cluster, target cluster, and Kafka at defined
  times within a run
- Collect and correlate metrics. The Capture Proxy and Traffic Replayer already emit
  OpenTelemetry metrics and traces to a sidecar **OTEL collector** (→ CloudWatch/X-Ray on AWS,
  → Prometheus/Jaeger in Docker): proxy latency/memory and Kafka offload success rate; replayer
  memory, throughput, Kafka consumer lag, and comparison-result counts. The orchestration layer
  consumes these plus the replayer's `output_tuples.log` comparison output.
- Assert pass/fail thresholds across the full pipeline, not just individual components

---

## Workload Profiles

Because the Capture Proxy and Traffic Replayer carry real OpenSearch/Elasticsearch client traffic,
the generator produces traffic shaped like it — `_bulk` indexing, document updates, `_search`
queries, aggregations, and scroll/`search_after` paging. The pipeline must hold up across very
different real-world workloads, so we define three profiles. Each profile is a different mix of
operation types and body sizes, and each one stresses a different part of the pipeline.

The [`DataGenerator`](../DataGenerator) module supplies document corpora and index mappings for four
workloads (`HTTP_LOGS`, `GEONAMES`, `NESTED`, `NYC_TAXIS`), but its `Workload` interface generates
**documents only — no queries**; producing query traffic is the new traffic generator's job. All
four corpora are queryable. `NYC_TAXIS` is in fact one of the richest for search and aggregation —
its mappings include geo-point, date, scaled-float numeric, and keyword fields, which support geo,
range, date-histogram, sorting, and terms-aggregation queries — so it suits the search profile as
well as the ingest one. `GEONAMES` adds text + geo search, `NESTED` exercises nested/Q&A-shaped
documents, and `HTTP_LOGS` is the canonical time-series/log shape.

The relevant operation classes (OpenSearch API shapes):

- **Write** — `POST /_bulk`, `POST /{index}/_doc`, `PUT /{index}/_doc/{id}`
- **Update** — `POST /{index}/_update/{id}` (partial document updates)
- **Query** — `GET|POST /{index}/_search`, `POST /_msearch`, aggregations
- **Deep paging / scroll** — `from`/`size`, `search_after`, and the scroll sequence
  (`POST /{index}/_search?scroll=...` → `POST /_search/scroll` → `DELETE /_search/scroll`)

### Profile 1 — Ingest-heavy

A logging / bulk-load shape: a high volume of writes, very few updates, and a small number of
queries that may individually be complex (aggregations, complex filters).

- **Operation mix:** writes dominant; updates negligible; queries few but heavy.
- **Body sizes:** large request bodies (bulk batches of documents); small responses (index acks).
- **Distinguishing characteristics:** high request bandwidth on the capture path; writes often
  arrive in large `_bulk` batches rather than single-document requests.
- **What it stresses:** Capture Proxy write throughput and request-body bandwidth; **Kafka write
  rate** (this is the most likely place to see backpressure); Traffic Replayer replay throughput
  against the target. Memory pressure is driven by request body size, not response size.

### Profile 2 — Search

A catalog / storefront shape: few writes, a moderate update rate (e.g. availability and price
changes), and a heavy query load — including deep paging and scroll queries.

- **Operation mix:** writes low; updates moderate (partial `_update` on existing docs); queries
  dominant.
- **Body sizes:** small-to-moderate requests; **potentially very large responses** — deep paging
  and scroll queries return large result sets.
- **Distinguishing characteristics:** queries dominate, and **deep paging** is an optional add-on
  (see below).
- **What it stresses:** query path latency on both clusters; **Traffic Replayer memory** — large
  query responses inflate the replayer's footprint far more than the proxy's, making this the profile
  where the memory asymmetry is most visible.

**Optional capability — deep paging.** Deep paging (scroll and `search_after`) is an
**activatable/deactivatable** capability of this profile rather than always-on. With it off, the
profile runs flat `_search` requests; with it on, the generator emits multi-page paging sequences,
which add large response bodies, longer-lived replayer state, and per-sequence ordering through
Kafka. The two paging mechanisms behave differently under capture and replay and are detailed in
[Appendix A](#appendix-a--deep-paging-sub-cases). Enable it to test deep-paging behaviour; leave it
off for a simpler, faster search workload.

### Profile 3 — Mixed (ingest + search)

A realistic steady-state shape: concurrent heavy writes and heavy queries on the same pipeline.

- **Operation mix:** a blend of Profiles 1 and 2, running simultaneously.
- **Body sizes:** the full range — large write bodies *and* large query responses concurrently.
- **Distinguishing characteristics:** write and read pressure compete for the same proxy, Kafka,
  and replayer resources at once. Consistency matters more here: a query may target a document a
  recent write created or updated, so replay ordering through Kafka has correctness implications,
  not just performance ones.
- **What it stresses:** every component simultaneously; surfaces interference effects that the
  isolated profiles miss (e.g. large-response replay memory pressure occurring at the same time as
  high Kafka write rate). This is the profile that most closely reflects production behaviour.

**Optional capability — deep paging.** As in Profile 2, deep paging (scroll and `search_after`) is
an **activatable/deactivatable** capability of the search portion of this profile — detailed in
[Appendix A](#appendix-a--deep-paging-sub-cases). With it on, the large-response and longer-lived
replayer state from paging coincides with heavy write pressure, which is the most demanding
combination for replayer memory and Kafka lag.

**Concurrency model.** The Mixed profile runs as **independent concurrent generators** — one ingest
stream and one search stream — rather than a single generator interleaving operation types. This
keeps attribution clean (each stream can be dialed, ramped, and chaos-tested independently and its
pressure traced back to it), mirrors the reality of separate indexing and search client populations
(separate connection pools and pacing on the proxy), and keeps each stateful sequence coherent
within its own stream. The exception is **write-then-read consistency**: to exercise a query that
targets a just-written document, a thin **shared ID registry** lets writers publish recently-written
document IDs that a fraction of search operations draw from. Streams are independent by default; the
shared registry is used only for the consistency-sensitive fraction of traffic, where replay
ordering through Kafka becomes a correctness concern rather than a performance one.

### Summary

| Dimension | Ingest-heavy | Search | Mixed |
|---|---|---|---|
| Writes | high | low | high |
| Updates | very low | moderate | moderate |
| Queries | low (but complex) | high (deep paging / scroll) | high |
| Request body size | large (bulk) | small–moderate | large |
| Response body size | small | large | large |
| Primary pressure point | Kafka write rate, proxy bandwidth | Replayer memory, query path | All components concurrently |
| Stateful sequence focus | bulk batching | deep paging, optional ([Appendix A](#appendix-a--deep-paging-sub-cases)) | write-then-read consistency; deep paging optional ([Appendix A](#appendix-a--deep-paging-sub-cases)) |
| Concurrency model | single stream | single stream | independent streams + shared ID registry |

---

## Next: Traffic Generation Design

The remainder of this design work focuses on the traffic generator: what request shapes to model,
how to represent stateful sequences, how to control connection reuse (`connectionId` pinning vs.
spreading), how to blend operation mixes and latency profiles, and which tool and configuration best
fit the Capture-Proxy-centric test context described above — building on the existing `DataGenerator`
and OpenSearch Benchmark tooling where it fits.

---

## Related Documents

- [`TrafficCaptureAndReplayDesign.md`](./TrafficCaptureAndReplayDesign.md) — overall capture/replay
  design and the `TrafficStream`/`TrafficObservation` protocol.
- [`replayerArchitecture.md`](./replayerArchitecture.md) — Traffic Replayer internals: connection
  accumulation, Kafka commit tracking, partition revocation, backpressure.
- [`ScalingTrafficCaptureAndReplayer.md`](./ScalingTrafficCaptureAndReplayer.md) — scaling and
  backpressure design for the proxy and replayer.
- [`Architecture.md`](./Architecture.md) — end-to-end migration architecture and where this pipeline
  fits.
- [`../TrafficCapture/README.md`](../TrafficCapture/README.md) — module overview for the
  TrafficCapture tools (`trafficCaptureProxyServer`, `trafficReplayer`, `captureKafkaOffloader`, …).
- [`../DataGenerator`](../DataGenerator) — existing OpenSearch workload data generation.

---

## Appendix A — Deep-paging sub-cases

Detail for the optional deep-paging capability of [Profile 2](#profile-2--search) and the search
portion of [Profile 3](#profile-3--mixed-ingest--search). Scroll and `search_after` both walk large
result sets but behave very differently under capture and replay, so they are modeled as distinct
sub-cases.

### A.1 — Scroll

The scroll sequence holds **server-side state on the cluster**: `POST /{index}/_search?scroll=...`
opens a scroll context that returns a `scroll_id`, subsequent `POST /_search/scroll` calls page
through it, and `DELETE /_search/scroll` releases it.

- **Stateful sequence:** open → fetch page → fetch page → … → close, threading the `scroll_id`
  forward. The sequence must replay in order and the context must be released.
- **Distinct stresses:** the cluster carries open scroll contexts (memory and segment-pin cost) for
  the lifetime of the scroll. Under replay, an out-of-order or dropped close leaks scroll contexts
  on the target cluster — a failure mode that does not exist for stateless paging. Long-lived
  scrolls also keep the corresponding request/response pairs resident in the replayer longer.

### A.2 — `search_after`

`search_after` is **stateless on the cluster**: each page is an independent `_search` request that
carries the sort key of the last hit from the previous page. There is no server-side context to
open or release.

- **Stateful sequence:** still a sequence from the generator's perspective (each page depends on the
  previous page's last sort value), but each request is independently valid and replayable.
- **Distinct stresses:** no scroll-context leak risk, but typically more total requests and larger
  cumulative response volume through Kafka and the replayer, since deep `search_after` walks can run
  for many pages. Ordering still matters for the generator's own correctness checks, but a
  reordered/dropped page does not leak cluster-side state.

---

## Appendix B — Solr's nuanced relationship to capture/replay

Background for the [Scope](#scope-and-applicability-source-engines) summary. The supported Solr
migration path is snapshot backfill (Reindex-From-Snapshot), which is out of scope for this document.
But Solr is not *wholly* outside the capture/replay pipeline — two distinct pieces exist, and only
one is genuinely out:

- **The `transformationShim` binary is *not* part of capture/replay.** It is a standalone live Netty
  reverse proxy ([`transformationShim`](../TrafficCapture/transformationShim),
  `MultiTargetRoutingHandler`) for synchronous Solr→OpenSearch shadow/cutover validation; the Traffic
  Replayer neither depends on nor invokes it. (It warrants its own load-testing document.)
- **The Solr *transforms* are usable by the Traffic Replayer.** The JS transforms in
  [`SolrTransformations`](../TrafficCapture/SolrTransformations) are shared: the shim loads them via
  `SolrTransformerProvider`, and the replayer can load the same files via the generic
  `JsonJSTransformerProvider` (`--transformerConfig`) to replay captured Solr traffic. This is an
  emerging capability (cf. the in-progress `solrMigrationDevSandbox` and Solr-advisor work) with two
  documented limits ([`LIMITATIONS.md`](../TrafficCapture/SolrTransformations/docs/LIMITATIONS.md)):
  **`CURSOR-REPLAY`** — opaque `cursorMark` tokens replay only the first page (the live shim handles
  pagination; offline replay cannot) — and **`SOLRCONFIG-REPLAYER`** — solrConfig must be supplied as
  pre-built JSON, no XML auto-parsing.

So if Solr-via-replayer is ever load-tested, the [Profile 2](#profile-2--search) deep-paging workload
must account for the `cursorMark` first-page-only limit, and the transformer is loaded via
`JsonJSTransformerProvider` rather than the shim's `SolrTransformerProvider`.
