# Cluster Sizing Rules

Sizing estimates must always be labeled as estimates with stated assumptions.
Never present a sizing recommendation as exact.

## Universal Rules (All Source Types)

- **Shard size target:** 10–50 GB per shard. Flag shards outside this range.
- **Shard count formula:** `primary_shards = ceil(index_size_GB / target_shard_size_GB)`
- **Replica default:** `number_of_replicas: 1` for production. Set to 0 during bulk load.
- **JVM heap:** `-Xms` = `-Xmx`. Never exceed 50% of RAM or 32 GB.
- **Storage formula:** `raw_data × (1 + replicas) × 1.3 overhead × 1.25 headroom`
- **Cluster manager nodes:** Always 3 dedicated (odd number for quorum).
- **Disk watermarks:** Alert at 75%. OpenSearch stops allocating at 85%, blocks writes at 90%.

## Instance Type Selection (Amazon OpenSearch Service)

Prefer NVMe-backed instances to eliminate EBS costs:

1. Calculate storage needed per node: `total_storage / data_node_count`
2. If it fits on r8gd NVMe → use r8gd (default choice)
3. If not → fall back to r7g + gp3 EBS

| Source Profile | Recommended Instance | Notes |
|---|---|---|
| ~2 vCPU, ~16 GB | r8gd.large.search | NVMe preferred |
| ~4 vCPU, ~32 GB | r8gd.xlarge.search | NVMe preferred |
| ~8 vCPU, ~64 GB | r8gd.2xlarge.search | NVMe preferred |
| ~16 vCPU, ~128 GB | r8gd.4xlarge.search | NVMe preferred |

## Elasticsearch / OpenSearch Source Sizing

- Match source data node count exactly as starting point.
- Map source node vCPU and memory to equivalent or better OpenSearch instance type.
- Prefer latest generation (r8gd > r7g > r6g).
- Data node count must be divisible by AZ count; round up if needed.
- Add dedicated master nodes even if source doesn't have them.

## Solr Source Sizing

- Use Solr shard count as baseline but recalculate — Solr collections are often over-sharded.
- Map SolrCloud nodes to OpenSearch data nodes 1:1 as minimum.
- Solr ZooKeeper ensemble is replaced by OpenSearch cluster manager nodes (no external ZK needed).
- Add coordinating-only nodes for production search workloads.
- Consider hot-warm tiering for time-series data (replaces Solr PULL replicas on slower hardware).

## RFS Worker Calculation

For Reindex-From-Snapshot worker count:

```
total_vcpu = target_data_nodes × vcpu_per_node
S = source_primary_shard_count
upper = total_vcpu / 3
lower = total_vcpu / 6

if S <= lower: workers = S
else: find largest S/N that fits in [lower, upper]
```

Workers should be a clean fraction of shard count to avoid idle workers in the final batch.
