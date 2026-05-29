# Solr → OpenSearch sizing — match-source rule + Solr-specific deltas

This file owns the **Solr-specific sizing rules** that the general AWS sizing best-practice docs do not cover. For the universal rules (storage formula, shard size, heap sizing, watermarks, monitoring metrics, instance-family selection), see [`sizing-formulas.md`](sizing-formulas.md) and retrieve the AWS storage / sharding / instance best-practice pages per [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section).

## Match-source rule (you MUST READ FIRST)

When the user gives their current Solr cluster sizing, the OpenSearch recommendation **MUST stay close to the source ballpark** unless workload signals (peak QPS, doc count, growth, retention) clearly demand more.

- Source per-node RAM < 16 GB → you SHOULD recommend an instance class with comparable RAM, or up to 1.5× source RAM. You MUST NOT default to 64 GB because oversizing inflates the cost without workload justification.
- Recommend an **instance class** that matches the source workload. You MUST NOT specify a manual JVM heap value — Amazon OpenSearch Service sets the JVM heap per instance class (`auto-tune.html`: *"OpenSearch Service uses 50% of an instance's RAM for the JVM heap, up to a heap size of 32 GiB"*; verify the per-instance recommendation in the live `bp-instances.html`).
- A small Solr cluster (3 × 8 GB RAM × 4 GB heap × 50 M docs × 10 GB index) maps to a similarly small OpenSearch instance class (e.g. `r6g.large.search`), 2–4 primary shards, 1 replica.
- You MUST justify any uplift above source sizing **explicitly** in the report (e.g., "doubling RAM because peak QPS exceeded 500/s").

## Topology deltas vs SolrCloud

OpenSearch / OpenSearch Service uses internal cluster-manager nodes rather than an external coordination service — confirmed by `managedomains-dedicatedmasternodes.html` (which describes dedicated master nodes performing cluster management without an external ensemble).

| Aspect | SolrCloud | OpenSearch |
|---|---|---|
| Coordination | External ZooKeeper ensemble | Built-in cluster manager nodes |
| Node roles | Overseer, data, coordinator | Cluster manager, data, ingest, coordinating-only |
| Coordinating node | Optional Solr client node | Recommended (offloads scatter-gather) |
| ZooKeeper nodes | 3–5 dedicated | **0** (eliminated) |

For SolrCloud sources, you MUST replace the ZooKeeper ensemble with **3 dedicated cluster manager nodes** (odd quorum) for production — `managedomains-dedicatedmasternodes.html`: *"We recommend that you use Multi-AZ with Standby, which adds three dedicated master nodes to each production OpenSearch Service domain ... Never choose an even number of dedicated master nodes."* For small migrations the cluster-manager-eligible role MAY be combined on data nodes.

Production node-role split:
```yaml
node.roles: [cluster_manager]   # 3 dedicated, odd quorum
node.roles: [data]              # ≥3 data nodes
node.roles: []                  # ≥2 coordinating-only behind LB
node.roles: [ingest]            # if you need ingest pipelines
```

## Shard migration from Solr collections

- Solr collections are **often over-sharded**. You MUST check current shard sizes via `GET /solr/<collection>/admin/segments?wt=json`.
- Target OpenSearch shard size: 10–30 GiB (search) or 30–50 GiB (logs). You MUST consolidate shards < 5 GB and split shards > 50 GB.
- Primary shard count is fixed at index creation; replicas can change at any time. During bulk indexing, you MUST set replicas to 0 and restore after load.

## Bulk-indexing tuning (during migration cutover)

Dynamic per-index settings (you MUST set these via `PUT /<index>/_settings` before bulk load): `refresh_interval: -1`, `number_of_replicas: 0`, `translog.durability: async`. After bulk load you MUST restore (`1s`, `1`, `request`) and run `_forcemerge?max_num_segments=5`.

Node-level tuning (requires `opensearch.yml` + rolling restart — NOT settable via the `_settings` API): `indices.memory.index_buffer_size: 20%`. You MUST NOT recommend setting this via `PUT /_settings` because it is a static node-level setting and the API call will silently no-op.

## Cache rules of thumb

| Cache | Setting | Notes |
|---|---|---|
| Query cache | `indices.queries.cache.size: 15%` | You MUST disable for time-series because cache-hit rates are too low to justify the heap cost. |
| Field data | `indices.fielddata.cache.size: 20%` | You SHOULD prefer `keyword` + doc values because text-fielddata aggregations OOM data nodes. |
| Request cache | `index.requests.cache.enable: true` | For facets/aggs |

Best practice: you SHOULD use `keyword` sub-fields for aggregations / sorting because aggregating on `text` requires fielddata which is memory-expensive.
