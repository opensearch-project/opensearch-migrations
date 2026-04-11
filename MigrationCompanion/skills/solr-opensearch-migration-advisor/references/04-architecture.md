# Solr to OpenSearch Architecture Migration

This document provides guidance on migrating from SolrCloud architecture to OpenSearch, covering cluster coordination, document identity, data distribution, and operational differences.

## 1. SolrCloud vs. OpenSearch Cluster Architecture

### 1.1 Overview

| Concept | SolrCloud | OpenSearch |
| :--- | :--- | :--- |
| Cluster coordination | Apache ZooKeeper (external) | Built-in cluster manager (Raft-based) |
| Configuration storage | ZooKeeper znodes | Internal cluster state / config API |
| Node discovery | ZooKeeper | Seed hosts / discovery plugins |
| Shard routing | ZooKeeper-managed | Internal routing table |
| Leader election | ZooKeeper | Internal election |
| Collection/index metadata | ZooKeeper | Cluster state API |

### 1.2 ZooKeeper in SolrCloud

SolrCloud relies on Apache ZooKeeper for:

- **Cluster state management**: ZooKeeper stores the live nodes list, collection state (shard leaders, replicas, their states), and configuration files.
- **Configuration distribution**: `solrconfig.xml`, `schema.xml`, and other config files are uploaded to ZooKeeper and distributed to all nodes automatically.
- **Leader election**: When a shard leader goes down, ZooKeeper coordinates the election of a new leader among the replicas.
- **Distributed locking**: ZooKeeper provides distributed locks for operations like index commits and collection management.

**ZooKeeper paths commonly used by SolrCloud:**

```
/solr/clusterstate.json       — collection/shard/replica state
/solr/live_nodes/             — currently active Solr nodes
/solr/aliases.json            — collection aliases
/solr/configs/<configset>/    — uploaded configsets (schema, solrconfig, etc.)
/solr/overseer/               — overseer election and queue
```

### 1.3 OpenSearch: No ZooKeeper Required

OpenSearch does **not** use ZooKeeper. All cluster coordination is handled internally:

- **Cluster Manager**: One node is elected cluster manager (formerly "master") using an internal Raft-like consensus mechanism. No external coordination service is needed.
- **Cluster State**: Index mappings, settings, shard routing, and node membership are stored in the internal cluster state, replicated across cluster manager-eligible nodes.
- **Configuration**: Index settings and mappings are managed via the REST API or index templates. There is no equivalent to uploading a configset — configuration is applied at index creation time or updated via the API.

**Migration implication**: When migrating from SolrCloud, you can decommission ZooKeeper entirely. All configuration that was stored in ZooKeeper (schema, solrconfig defaults) must be translated into OpenSearch index mappings, settings, and index templates before or during index creation.

## 2. Document Identity: `uniqueKey` vs. `_id`

### 2.1 Solr `uniqueKey`

Every Solr collection has a `uniqueKey` field defined in `schema.xml`. This field uniquely identifies each document within the collection:

```xml
<schema name="example" version="1.6">
  <uniqueKey>id</uniqueKey>
  ...
</schema>
```

- The `uniqueKey` field is a regular Solr field (typically `solr.StrField` or `solr.UUIDField`) that is indexed and stored.
- Solr uses this field to deduplicate documents on update (upsert behavior).
- The field name can be anything (e.g., `id`, `doc_id`, `product_id`).

### 2.2 OpenSearch `_id`

OpenSearch uses a reserved metadata field `_id` to uniquely identify every document:

- `_id` is always present and is not part of the `_source` document body.
- If you do not supply an `_id` at index time, OpenSearch generates a random UUID.
- To achieve the same upsert behavior as Solr's `uniqueKey`, you must explicitly set the `_id` when indexing documents.

### 2.3 Migration Strategy for `uniqueKey`

When migrating documents from Solr to OpenSearch:

1. **Identify the `uniqueKey` field** in `schema.xml` (e.g., `id`).
2. **Use the `uniqueKey` value as the OpenSearch `_id`** when indexing each document.
3. **Decide whether to keep the field in `_source`**: You may keep the original field (e.g., `id`) in the document body for application compatibility, or rely solely on `_id`. Keeping it in `_source` is recommended for transparency.

**Example — Solr document:**
```json
{
  "id": "product-123",
  "title": "OpenSearch Guide",
  "price": 29.99
}
```

**Example — OpenSearch index request:**
```
PUT /products/_doc/product-123
{
  "id": "product-123",
  "title": "OpenSearch Guide",
  "price": 29.99
}
```

The `id` field value `product-123` becomes the OpenSearch `_id`. Subsequent indexing of a document with the same `_id` will overwrite (upsert) the existing document, matching Solr's behavior.

### 2.4 Auto-Generated IDs

If the Solr `uniqueKey` field was populated by `solr.UUIDField` (auto-generated UUIDs), you can let OpenSearch auto-generate `_id` values using a `POST` request instead of `PUT`. However, this breaks the ability to do deterministic upserts, so it is generally better to carry over the existing UUID values explicitly.

## 3. Sharding and Replication

### 3.1 SolrCloud Sharding

- Collections are divided into **shards** (logical partitions).
- Each shard has one **leader** replica and zero or more **follower** replicas.
- The number of shards is set at collection creation time and cannot be changed without re-indexing (split shard is possible but complex).
- Routing is based on a hash of the `uniqueKey` field by default, or can be configured with `router.name=compositeId`.

### 3.2 OpenSearch Sharding

- Indices are divided into **primary shards** and **replica shards**.
- The number of primary shards is set at index creation time and is fixed (requires reindex to change).
- Replica shards are copies of primary shards and can be added or removed dynamically.
- Routing is based on a hash of `_id` by default, or can be customized with `routing` parameter or `_routing` field.

### 3.3 Shard Count Guidance

| Factor | Recommendation |
| :--- | :--- |
| Target shard size | 10–50 GB per shard (OpenSearch recommendation) |
| Number of primary shards | Match or approximate Solr shard count |
| Number of replicas | At least 1 replica for production (equivalent to Solr's replication factor) |
| Shard count change | Requires reindex in both Solr and OpenSearch |

**Migration implication**: If your SolrCloud collection has `numShards=4` and `replicationFactor=2`, create an OpenSearch index with `number_of_shards: 4` and `number_of_replicas: 1` (replicas are in addition to the primary, so total copies = 2).

## 4. Configsets vs. Index Templates

### 4.1 Solr Configsets

SolrCloud uses **configsets** — bundles of configuration files stored in ZooKeeper — to define the schema and request handler behavior for a collection:

```
/solr/configs/my_configset/
  schema.xml
  solrconfig.xml
  synonyms.txt
  stopwords.txt
  ...
```

Multiple collections can share a configset. When a collection is created, it references a configset by name.

### 4.2 OpenSearch Index Templates

OpenSearch uses **index templates** to apply settings and mappings automatically to new indices matching a pattern:

```json
PUT /_index_template/my_template
{
  "index_patterns": ["products-*"],
  "template": {
    "settings": {
      "number_of_shards": 4,
      "number_of_replicas": 1,
      "analysis": {
        "analyzer": { ... }
      }
    },
    "mappings": {
      "properties": { ... }
    }
  }
}
```

**Migration steps:**
1. Translate `schema.xml` → OpenSearch `mappings` (see `01-schema-migration.md`).
2. Translate `schema.xml` analyzer definitions → OpenSearch `settings.analysis` (see `03-analysis-pipelines.md`).
3. Package both into an index template or apply directly at index creation.
4. Auxiliary files (`synonyms.txt`, `stopwords.txt`) must be placed on each OpenSearch node's filesystem or inlined into the settings (see `03-analysis-pipelines.md` Section 5).

## 5. Solr Overseer vs. OpenSearch Cluster Manager

### 5.1 Solr Overseer

SolrCloud uses an **Overseer** — a single node elected via ZooKeeper — to process all cluster-level state changes (collection creation, shard splitting, replica assignment, etc.). The Overseer is a potential bottleneck in very large clusters.

### 5.2 OpenSearch Cluster Manager

OpenSearch uses a **cluster manager** node (previously called "master node") that handles:

- Index creation, deletion, and mapping updates.
- Shard allocation and rebalancing.
- Node join/leave events.

Unlike the Solr Overseer, the cluster manager role can be held by any cluster-manager-eligible node and is re-elected automatically on failure without ZooKeeper.

**Dedicated cluster manager nodes** are recommended for large clusters (set `node.roles: [ cluster_manager ]` and exclude `data` role).

## 6. Collection Aliases vs. Index Aliases

### 6.1 Solr Collection Aliases

SolrCloud supports **collection aliases** stored in ZooKeeper (`/solr/aliases.json`). An alias can point to one or more collections, enabling:

- Zero-downtime re-indexing (swap alias from old to new collection).
- Routing aliases (time-based or category-based routing to different collections).

### 6.2 OpenSearch Index Aliases

OpenSearch supports **index aliases** with equivalent functionality. Aliases can point to multiple indices (fan-out search), a single write index, or include a filter query. Translate Solr collection aliases to OpenSearch index aliases; time-based routing aliases map to aliases pointing to multiple time-partitioned indices.

```json
POST /_aliases
{ "actions": [
    { "add": { "index": "products_v2", "alias": "products" } },
    { "remove": { "index": "products_v1", "alias": "products" } }
] }
```

## 7. SolrCloud-Specific Features Without Direct Equivalents

| SolrCloud Feature | OpenSearch Approach |
| :--- | :--- |
| **Overseer-based collection management** | Cluster Manager API (`/_cluster/settings`, index API) |
| **ZooKeeper-stored configsets** | Index templates + node-local analysis files |
| **Shard splitting** | Reindex into a new index with more shards |
| **CDCR (Cross-Data Center Replication)** | OpenSearch Cross-Cluster Replication (CCR) plugin |
| **SolrCloud Collections API** | OpenSearch Index API + `_cat/indices` |
| **Solr Admin UI** | OpenSearch Dashboards |
| **Solr Metrics API** | OpenSearch `_nodes/stats`, `_cluster/stats` |
| **Solr streaming expressions** | OpenSearch aggregations + scripting |

## 8. Legacy SolrCloud Versions (Solr 4 and 5)

### 8.1 Solr 4 SolrCloud

Solr 4 introduced SolrCloud as a production feature. Key differences from later versions:

- **Cluster state**: All collection state in a single `/solr/clusterstate.json` (bottleneck for large clusters); per-collection state files were introduced in Solr 5.4+.
- **`schema.xml` only**: No Managed Schema support. All schema changes require editing `schema.xml` and reloading the core.

### 8.2 Solr 5 SolrCloud

- **Per-collection state**: Solr 5.4+ moved collection state to per-collection znodes (`/solr/collections/<name>/state.json`), reducing ZooKeeper load.
- **Managed Schema**: Solr 5 introduced the Schema API and managed schema (mutable schema via API). If the Solr 5 instance used managed schema, export the schema via the Schema API (`/solr/<collection>/schema`) before migration.
- **Trie numeric fields**: Solr 5 still uses Trie-based numeric fields by default (see `01-schema-migration.md` Section 7).

### 8.3 Exporting Configuration from ZooKeeper

To extract configsets from a running SolrCloud ZooKeeper for migration:

```bash
# Using the Solr CLI (zkcli.sh or bin/solr zk)
# Solr 5+
bin/solr zk downconfig -z <zk_host>:2181 -n <configset_name> -d /local/path/to/configset

# Solr 4 (using zkcli.sh)
server/scripts/cloud-scripts/zkcli.sh -zkhost <zk_host>:2181 \
  -cmd downconfig -confname <configset_name> -confdir /local/path/to/configset
```

Once downloaded, translate `schema.xml` using the guidance in `01-schema-migration.md` and `03-analysis-pipelines.md`.

## 9. Data Migration Approach

### 9.1 Re-indexing from Source

The recommended approach is to re-index from the original data source (database, data lake, etc.) rather than exporting from Solr, ensuring data consistency and avoiding Solr-specific artifacts.

### 9.2 Exporting from Solr

If re-indexing from source is not feasible, export using:

- **Solr Export Handler** (`/export`) — streams all documents.
- **CursorMark pagination** — use `cursorMark=*` with `sort=id asc` for large collections.

**Example — Cursor-based export:**
```
GET /solr/<collection>/select?q=*:*&rows=1000&sort=id+asc&cursorMark=*&wt=json&fl=id,title,price
```

Repeat with the `nextCursorMark` value from each response until `nextCursorMark == cursorMark`.

### 9.3 Indexing into OpenSearch

1. Map the Solr `uniqueKey` value to OpenSearch `_id` (see Section 2).
2. Remove Solr internal fields (`_version_`, `_root_`, `score`) from the document body.
3. Use the Bulk API for high-throughput indexing:

```
POST /_bulk
{ "index": { "_index": "products", "_id": "product-123" } }
{ "title": "OpenSearch Guide", "price": 29.99 }
{ "index": { "_index": "products", "_id": "product-456" } }
{ "title": "Solr in Action", "price": 39.99 }
```

4. Monitor indexing progress with `GET /_cat/indices?v` and `GET /_nodes/stats`.
