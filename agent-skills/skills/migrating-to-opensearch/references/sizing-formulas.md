# Sizing — draft from the formulas, verify the per-instance numbers

The storage / shard / topology formulas and constants below are stable-core (confirmed against AWS `bp-*` docs at the cited line) — draft the sizing section directly from them. What is version-volatile and MUST be tagged `[verify]` and confirmed in the Step 8 batch: the **per-instance JVM heap recommendation**, the **current instance families** (and their regional availability), and the **live Serverless NextGen OCU caps**. Everything else here is a formula or a stable constant — do not block the draft on a fetch.

This file owns the **operational rules of thumb** that do not appear in any single doc, plus the k-NN engine selection IP.

## Storage formula

Confirmed by `bp-storage.html`:

```
min_storage = source_data * (1 + replicas) * (1 + indexing_overhead) / (1 - linux_reserved) / (1 - aos_overhead)
```

Defaults from the same doc: `linux_reserved = 0.05` (*"Linux reserves 5% of the file system for the root user"*), `aos_overhead = 0.20` capped at 20 GiB/instance (*"OpenSearch Service reserves 20% of the storage space of each instance (up to 20 GiB)"*), `indexing_overhead ≈ 0.10` (*"the index up to 10% of the source data"*). Simplified rule from the same doc: `min_storage ≈ source_data × (1 + replicas) × 1.45`. For >1 PB, retrieve `petabyte-scale.html` (e.g., 100 GiB shards, OR1.16xlarge / i3.16xlarge).

## Shard rules

Confirmed by `bp-sharding.html` and `bp.html`:

- Search workloads: 10–30 GiB / shard. Logs / write-heavy: 30–50 GiB. (`bp-sharding.html`: *"keep shard size between 10–30 GiB ... and 30–50 GiB for write-heavy workloads"*.) Petabyte-scale on i3.16xl / OR1: up to 100 GiB (`petabyte-scale.html`).
- Primary shard count: `(source + room_to_grow) × 1.1 / desired_shard_size`, rounded up to a multiple of the data-node count (`bp.html`: *"try to make the shard count a multiple of the data node count"*).
- Per-node cap: ≤ 25 shards/GiB JVM heap (`bp.html`: *"Aim for 25 shards or fewer per GiB of heap memory"*). Cluster-wide cap (Multi-AZ-with-Standby): 75,000 shards (`managedomains-multiaz.html`: *"the total number of shards on a cluster can't exceed 75000"*).
- Shard-to-CPU ratio: ~1.5 vCPU per shard starting point (`bp.html`: *"use an initial scale point of 1.5 vCPU per shard"*).

## JVM and operational thresholds

- **Heap sizing**: On Amazon OpenSearch Service the JVM heap is set by the service per instance class. `auto-tune.html` states: *"By default, OpenSearch Service uses 50% of an instance's RAM for the JVM heap, up to a heap size of 32 GiB."* `cloudwatch-alarms.html` reinforces this: *"OpenSearch Service uses half of an instance's RAM for the Java heap, up to a heap size of 32 GiB. You can scale instances vertically up to 64 GiB of RAM, at which point you can scale horizontally by adding instances."* You MUST verify the per-instance heap recommendation in the live `bp-instances.html` doc rather than applying the self-managed 32-GiB rule of thumb manually.
- **Pressure thresholds** — confirmed by `handling-errors.html`: *"When the JVMMemoryPressure metric exceeds 92% for 30 minutes, OpenSearch Service triggers a protection mechanism and blocks all write operations. ... When the JVMMemoryPressure metric returns to 88% or lower for five minutes, the protection is disabled."* `bp.html` recommends keeping usage below 80% as a "good rule of thumb".
- **Refresh interval** — confirmed by `bp.html`: *"We recommend setting the `refresh_interval` parameter for all of your indexes to 30 seconds or more."*
- **Bulk request size** — confirmed by `bp.html`: *"a good starting point is 3–5 MiB per bulk request."*
- **UltraWarm cost-effective threshold** — confirmed by `bp.html`: *"UltraWarm becomes cost-effective when you have roughly 2.5 TiB of data to migrate from hot storage."*
- **Disk watermarks**: 75 / 85 / 90 % (low / high / flood) — upstream Elasticsearch defaults; verify in current OpenSearch docs if the customer has overridden them.
- **EBS burst balance** — `handling-errors.html` notifies "when the EBS burst balance on one of your General Purpose (SSD) volumes is below 70%, and a follow-up notification if the balance falls below 20%."

## Topology defaults

- You MUST use exactly 3 dedicated cluster managers. You MUST NOT use 1, 2, 4, or 5 because cluster manager quorum requires odd counts and 3 is the minimum that survives a single-node failure.
- ≥ 2 data nodes (Multi-AZ-with-Standby: data nodes in multiples of 3, 2 replicas, Auto-Tune ON)
- 3 AZs for prod
- You MUST NOT recommend `t2.*` or `t3.small` for prod data nodes because their CPU credits exhaust under sustained load and the cluster will throttle. You SHOULD prefer graviton-based memory-optimized instances (e.g. `r6g`/`r7g`/`r8g` — tag the concrete family `[verify]` for current regional availability) for query latency, and write-optimized instances (e.g. `OR1`/`OR2`/`OM2` `[verify]`) for write-heavy. These names are examples, not an exhaustive or fixed list; confirm the current supported families in the Step 8 batch.
- You SHOULD prefer gp3 EBS over gp2 because gp3 decouples IOPS from volume size and is cheaper at scale.

## k-NN engine selection (skill IP)

| Vector count | Recommendation |
|---|---|
| < 10M, want filtering | Lucene HNSW |
| 10M – tens of billions, RAM abundant | FAISS HNSW |
| 10M – tens of billions, RAM tight | FAISS HNSW + PQ or fp16, or `mode=on_disk` |
| Need fastest indexing, accept lower recall | FAISS IVF (+ optional PQ) |
| Migrating from NMSLIB | FAISS migration (NMSLIB deprecated; default since 2.18 is FAISS) |
| Targeting Serverless NextGen | FAISS HNSW only |

For k-NN memory math, draft directly from the standing rules of thumb (skill IP, no retrieval): float HNSW `bytes_per_vector ≈ 1.1 × (4 × dim + 8 × m)`; multiply by `(1 + replicas)`; default native-index circuit breaker 50 % of non-heap RAM. Tag any engine-specific exact value `[verify]` and confirm in the Step 8 batch against `knn-index/` and the `knn-methods-engines.md` reference per [`knowledge-retrieval.md`](knowledge-retrieval.md) (OpenSearch Project (engine docs) section). Do not block the draft on this fetch.

For UltraWarm k-NN: you MUST NOT use `uw.medium` for in-memory engines because the instance lacks the RAM headroom to hold k-NN graphs. You MUST size so cumulative graph size of actively-searched shards ≤ `knn.memory.circuit_breaker.limit × 61 GiB` per `uw.large` instance.

## Serverless NextGen OCUs

- 1 OCU = 6 GiB RAM + matching vCPU + ~120 GiB ephemeral storage — confirmed by `serverless-scaling.html`: *"Each OCU is a combination of 6 GiB of memory and corresponding virtual CPU (vCPU) ... Each OCU includes enough hot ephemeral storage for 120 GiB of index data."*
- Default minimum (redundancy ON): **1 indexing OCU (0.5 OCU × 2) + 1 search OCU (0.5 OCU × 2)** = 4 × 0.5 OCU billed — confirmed by `serverless-overview.html` (Pricing): *"a minimum of 1 OCU (0.5 OCU × 2) for ingestion ... and 1 OCU (0.5 OCU × 2) for search."*
- Default maximum: 10 OCUs each for indexing and search; up to 1,700 OCUs each on request — confirmed by `serverless-scaling.html`: *"the default maximum OCU capacity is 10 OCUs for indexing and 10 OCUs for search ... the maximum allowed capacity is 1,700 OCUs for indexing and 1,700 OCUs for search."*
- Sustained ingest / QPS rules of thumb (1 indexing OCU ≈ 100–200 MB/s sustained ingest, 1 search OCU ≈ 50–200 simple QPS or 10–50 complex aggregations/sec) are **Skill IP** (not in upstream docs) — verify under representative load with OpenSearch Benchmark before quoting.

Draft the OCU recommendation from the standing defaults above (1 OCU = 6 GiB RAM + vCPU; redundancy-ON floor = 1 indexing + 1 search). Tag any exact cap or scaling-model change `[verify]` and confirm against the Serverless NextGen scaling doc in the Step 8 batch via [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Serverless NextGen section). Do not block the draft on this fetch.

## Validate before cutover

You MUST run OpenSearch Benchmark against the target cluster before cutover. Retrieve the OSB doc and workload catalog via [`knowledge-retrieval.md`](knowledge-retrieval.md) (OpenSearch Project (engine docs) section); the `big5` workload is the standard search benchmark, and `compare` produces a baseline-vs-contender diff.

## Cite

Sizing claims MUST cite the specific best-practice page used (storage / sharding / instances / petabyte-scale), the error-handling doc for circuit breaker / pressure thresholds, and the k-NN methods/engines doc for vector sizing. All catalogued in [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section and OpenSearch Project (engine docs) section).
