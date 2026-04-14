# Solr to OpenSearch Cluster Sizing

Sizing estimates must always be labeled as estimates with stated assumptions. Never present a sizing recommendation as exact. When in doubt, size up — under-provisioning is a harder problem to fix than over-provisioning.

## Rules

- **Shard size target:** Keep each shard between 10 GB and 50 GB. Shards outside this range should be split or merged before migration.
- **Shard count formula:** `primary_shards = ceil(expected_index_size_GB / target_shard_size_GB)`. Use the Solr shard count as a baseline, but recalculate — Solr collections are often over-sharded.
- **Replica default:** Use `number_of_replicas: 1` (2 total copies) for production. Set replicas to 0 during bulk load, then restore after indexing completes.
- **JVM heap:** Set `-Xms` equal to `-Xmx`. Never exceed 50% of available RAM or 32 GB (above 32 GB, JVM compressed OOPs are disabled). Recommended range: 16–31 GB for data nodes.
- **Node resource baseline:** Start with a 1:1 CPU/RAM mapping from Solr nodes to OpenSearch data nodes as a minimum. Adjust based on measured workload.
- **Storage formula:** `total_storage = raw_data_size × (1 + replicas) × 1.3 overhead × 1.25 headroom`. Use NVMe SSD for data nodes.
- **Cluster manager nodes:** Deploy 3 dedicated cluster manager nodes (odd number for quorum). These replace the SolrCloud ZooKeeper ensemble — no external ZooKeeper is needed.
- **Coordinating nodes:** Add ≥ 2 coordinating-only nodes behind a load balancer for production search workloads to offload scatter-gather from data nodes.
- **Hot-warm tiering:** Tag nodes with `node.attr.temp: hot/warm` and use Index State Management (ISM) to move older indices to warm nodes — equivalent to Solr PULL replicas on slower hardware.
- **Disk watermarks:** Alert at 75% disk usage; OpenSearch stops allocating shards at 85% and blocks writes at 90%. Size storage to stay below 75% under normal load.

## What Counts as a Sizing Error

- Recommending a shard size outside the 10–50 GB range without explicit justification.
- Setting JVM heap above 32 GB or above 50% of available RAM.
- Proposing fewer than 3 cluster manager nodes in a production cluster.
- Presenting a storage or shard estimate without stating the assumptions behind it.
- Ignoring replica count when calculating total storage requirements.

Flag any of the above before presenting a sizing recommendation to the user.
