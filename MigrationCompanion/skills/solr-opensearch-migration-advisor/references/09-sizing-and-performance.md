# Operational Sizing and Performance Guidance

This document provides guidance for sizing OpenSearch clusters when migrating from Solr, covering hardware recommendations, shard strategy, JVM tuning, indexing throughput, and query performance.

## 1. Cluster Sizing Fundamentals

### 1.1 Key Differences from Solr

| Aspect | Solr | OpenSearch |
| :--- | :--- | :--- |
| Coordination | External ZooKeeper ensemble | Built-in cluster manager nodes |
| Node roles | Overseer, data, coordinator | Cluster manager, data, ingest, coordinating-only |
| Heap recommendation | 16–32 GB | 16–32 GB (never exceed 50% of RAM) |
| ZooKeeper nodes | 3–5 dedicated | 0 (eliminated) |
| Coordinating node | Optional (Solr client node) | Recommended (offloads scatter-gather) |
| Storage overhead | 1.1–1.5× | 1.1–1.5× (same Lucene internals) |

### 1.2 Node Role Separation

For production clusters, separate node roles:

```yaml
node.roles: [ cluster_manager ]   # cluster manager node
node.roles: [ data ]              # data node
node.roles: []                    # coordinating-only (load balancer)
node.roles: [ ingest ]            # ingest node
```

**Minimum recommended topology:** 3 dedicated cluster manager nodes (odd number for quorum), ≥ 3 data nodes, ≥ 2 coordinating-only nodes behind a load balancer. This replaces the SolrCloud pattern of 3 ZooKeeper nodes + Solr nodes with overseer election.

---

## 2. Shard Sizing Strategy

| Guideline | Recommendation |
| :--- | :--- |
| Target shard size | 10–50 GB per shard |
| Maximum shards per node | ~20 shards per GB of heap (e.g., 30 GB heap → ~600 shards) |
| Primary shards | Set at index creation; cannot be changed without re-indexing |
| Replica shards | Can be changed at any time |

**Formula:** `primary_shards = ceil(expected_index_size_GB / target_shard_size_GB)`

Example: 500 GB index with 25 GB target → 20 primary shards.

**Migrating from Solr shard counts:** Solr collections are often over-sharded. Check current shard sizes via `GET /solr/<collection>/admin/segments?wt=json`. Consolidate shards < 5 GB; split shards > 50 GB.

**Replicas:** 1 replica (default) tolerates loss of 1 node and doubles storage. During bulk indexing, set replicas to 0 and restore after load completes.

---

## 3. JVM and Heap Configuration

```
# config/jvm.options
-Xms16g
-Xmx16g
```

- Set `-Xms` equal to `-Xmx` to avoid heap resizing pauses.
- Do not exceed 50% of available RAM (leave the rest for OS page cache).
- Do not exceed 32 GB (above this, JVM compressed OOPs are disabled).
- Recommended range: 16–31 GB for data nodes.

OpenSearch defaults to G1GC. For large heaps (> 16 GB), consider ZGC (`-XX:+UseZGC`, JDK 15+). Avoid swap: set `bootstrap.memory_lock: true` and use SSDs/NVMe for data directories.

---

## 4. Index Settings for Performance

### 4.1 Bulk Indexing

Before bulk loading:
```json
PUT /my-index/_settings
{ "index.refresh_interval": "-1", "index.number_of_replicas": 0,
  "index.translog.durability": "async", "index.translog.sync_interval": "60s" }
```

After bulk load, restore settings and force-merge:
```json
PUT /my-index/_settings
{ "index.refresh_interval": "1s", "index.number_of_replicas": 1,
  "index.translog.durability": "request" }
```
```
POST /my-index/_forcemerge?max_num_segments=5
```

Set `indices.memory.index_buffer_size: 20%` in `opensearch.yml` for write-heavy workloads (equivalent to Solr's `ramBufferSizeMB`).

### 4.2 Caches

| Cache | Setting | Notes |
| :--- | :--- | :--- |
| Query cache (filter context) | `indices.queries.cache.size: 15%` | Disable for high-cardinality or time-series indices |
| Field data cache | `indices.fielddata.cache.size: 20%` | Avoid — use `keyword` fields with doc values instead |
| Request cache (agg-only) | `index.requests.cache.enable: true` | Equivalent to Solr's `queryResultCache` for facet queries |

**Best practice:** Use `keyword` sub-fields (not `text`) for aggregations and sorting to avoid field data loading entirely.

### 4.3 Slow Query Logging

```json
PUT /my-index/_settings
{ "index.search.slowlog.threshold.query.warn": "10s",
  "index.search.slowlog.threshold.query.info": "5s",
  "index.search.slowlog.threshold.fetch.warn": "1s" }
```

## 5. Hardware Recommendations

| Workload | CPU | RAM | Storage |
| :--- | :--- | :--- | :--- |
| Search-heavy | 16+ cores | 64 GB | SSD, 2–4 TB |
| Ingest-heavy | 32+ cores | 64 GB | NVMe SSD, 4–8 TB |
| Mixed | 16–32 cores | 64–128 GB | NVMe SSD, 4 TB |

**Storage formula:** `total_storage = raw_data_size × (1 + replicas) × overhead_factor`

- `overhead_factor`: 1.1–1.5× (Lucene structures, stored fields, doc values). Add 20–30% headroom for merges.
- Example: 1 TB raw, 1 replica, 1.3× overhead → 1 TB × 2 × 1.3 × 1.25 headroom ≈ **3.25 TB minimum**.
- Use 10 GbE minimum between nodes; 25 GbE for high-throughput ingest.

---

## 6. Allocation and Routing

**Shard allocation awareness** (equivalent to SolrCloud rack-aware placement):
```yaml
# opensearch.yml on each node
node.attr.zone: us-east-1a
```
```json
PUT /my-index/_settings
{ "index.routing.allocation.awareness.attributes": "zone" }
```

**Forced awareness** (prevent all replicas in same zone):
```yaml
cluster.routing.allocation.awareness.force.zone.values: us-east-1a,us-east-1b,us-east-1c
```

**Hot-warm architecture** (equivalent to Solr's PULL replicas on slower nodes): Tag nodes with `node.attr.temp: hot/warm` and use Index State Management (ISM) to automate moving indices between tiers.

---

## 7. Monitoring Key Metrics

| Metric | API | Alert Threshold |
| :--- | :--- | :--- |
| JVM heap usage | `/_nodes/stats/jvm` | > 85% |
| GC time | `/_nodes/stats/jvm` | > 10% of wall time |
| Search latency (p99) | `/_nodes/stats/indices/search` | Baseline + 2× |
| Segment count | `/_cat/segments` | > 200 per shard (trigger forcemerge) |
| Shard size | `/_cat/shards?v` | < 5 GB or > 50 GB (rebalance) |
| Unassigned shards | `/_cluster/health` | > 0 (investigate immediately) |
| Disk usage | `/_cat/allocation?v` | > 75% (watermark default: 85%) |

**Disk watermarks:**
```json
PUT /_cluster/settings
{ "persistent": {
    "cluster.routing.allocation.disk.watermark.low": "75%",
    "cluster.routing.allocation.disk.watermark.high": "85%",
    "cluster.routing.allocation.disk.watermark.flood_stage": "90%" } }
```
