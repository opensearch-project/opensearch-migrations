# Solr `solrconfig.xml` Migration Reference

This document maps common `solrconfig.xml` settings and constructs to their OpenSearch equivalents, covering request handlers, caches, update settings, and other core configuration elements.

## 1. Overview

In SolrCloud, `solrconfig.xml` is the primary configuration file for a collection, stored in ZooKeeper as part of a configset. It controls request handlers, search components, caches, update processing, replication, and more.

In OpenSearch, there is no equivalent single configuration file. Configuration is distributed across:

- **Index settings** (`PUT /<index>/_settings` or at creation time) — controls shards, replicas, analyzers, refresh interval, merge policy, etc.
- **Index mappings** (`PUT /<index>/_mapping`) — controls field types and analysis chains.
- **Index templates** — reusable settings/mappings applied at index creation.
- **Cluster settings** (`PUT /_cluster/settings`) — node-level and cluster-wide defaults.
- **Search/Ingest Pipelines** — replaces custom request handlers and search components.

## 2. Request Handlers (`<requestHandler>`)

### 2.1 `/select` Handler

**Solr `solrconfig.xml`:**
```xml
<requestHandler name="/select" class="solr.SearchHandler">
  <lst name="defaults">
    <str name="echoParams">explicit</str>
    <int name="rows">10</int>
    <str name="df">text</str>
    <str name="defType">edismax</str>
    <str name="qf">title^2.0 body^1.0</str>
  </lst>
</requestHandler>
```

**OpenSearch equivalent:**

Default query parameters are not configured server-side. Set them in the application layer or use a Search Pipeline for request-time defaults:

```json
PUT /_search/pipeline/default-search-pipeline
{
  "request_processors": [
    {
      "script": {
        "lang": "painless",
        "source": "if (ctx.size == null) { ctx.size = 10; }"
      }
    }
  ]
}
```

For `df` (default field), use `default_field` in `query_string` queries. For `qf` (query fields), use `fields` in `multi_match`.

### 2.2 `/update` Handler

**Solr `solrconfig.xml`:**
```xml
<requestHandler name="/update" class="solr.UpdateRequestHandler">
  <lst name="defaults">
    <str name="update.chain">dedupe</str>
  </lst>
</requestHandler>
```

**OpenSearch equivalent:**

Use an **Ingest Pipeline** to pre-process documents before indexing:

```json
PUT /_ingest/pipeline/dedupe-pipeline
{
  "description": "Deduplicate documents by fingerprint",
  "processors": [
    { "fingerprint": { "fields": ["id", "title"], "target_field": "_id" } }
  ]
}
```

Apply the pipeline at index time: `PUT /my-index/_doc/1?pipeline=dedupe-pipeline`

### 2.3 Custom Request Handlers

Custom Java `RequestHandler` classes have no direct equivalent. Migrate logic to:
- **Search Pipelines** (OpenSearch 2.9+) for query rewriting and result post-processing.
- **Ingest Pipelines** for document transformation at index time.
- **Application layer** for business logic that cannot be expressed in pipelines.

## 3. Caches (`<query>`, `<filterCache>`, `<queryResultCache>`, `<documentCache>`)

### 3.1 Solr Cache Configuration

```xml
<query>
  <filterCache class="solr.FastLRUCache" size="512" initialSize="512" autowarmCount="0"/>
  <queryResultCache class="solr.LRUCache" size="512" initialSize="512" autowarmCount="0"/>
  <documentCache class="solr.LRUCache" size="512" initialSize="512" autowarmCount="0"/>
  <fieldValueCache class="solr.FastLRUCache" size="512" autowarmCount="128" showItems="32"/>
</query>
```

### 3.2 OpenSearch Equivalent

OpenSearch manages caches internally. The relevant index and node settings are:

| Solr Cache | OpenSearch Setting | Default |
| :--- | :--- | :--- |
| `filterCache` | `indices.queries.cache.size` (node) | `10%` of heap |
| `queryResultCache` | `index.queries.cache.enabled` | `true` |
| `documentCache` | Field data cache (`indices.fielddata.cache.size`) | unbounded |
| `fieldValueCache` | Field data cache | unbounded |

**Key settings:**
```json
PUT /_cluster/settings
{
  "persistent": {
    "indices.queries.cache.size": "15%",
    "indices.fielddata.cache.size": "20%"
  }
}
```

```json
PUT /my-index/_settings
{
  "index.queries.cache.enabled": true
}
```

There is no manual cache warming (`autowarmCount`) in OpenSearch; the OS page cache and JVM heap serve this role.

## 4. Update Settings (`<updateHandler>`)

### 4.1 Solr Commit Configuration

```xml
<updateHandler class="solr.DirectUpdateHandler2">
  <autoCommit>
    <maxTime>${solr.autoCommit.maxTime:15000}</maxTime>
    <openSearcher>false</openSearcher>
  </autoCommit>
  <autoSoftCommit>
    <maxTime>${solr.autoSoftCommit.maxTime:1000}</maxTime>
  </autoSoftCommit>
</updateHandler>
```

### 4.2 OpenSearch Equivalent

OpenSearch uses a **refresh interval** instead of Solr's soft commit, and **flush** instead of hard commit:

| Solr Setting | OpenSearch Equivalent | Notes |
| :--- | :--- | :--- |
| `autoSoftCommit.maxTime` | `index.refresh_interval` | Default `1s`; controls near-real-time visibility |
| `autoCommit.maxTime` | Translog flush (`index.translog.flush_threshold_size`) | Default `512mb` |
| `commitWithin` | `?refresh=wait_for` or `?refresh=true` | Per-request control |
| `openSearcher=false` | N/A | OpenSearch always opens a new searcher on refresh |

```json
PUT /my-index/_settings
{
  "index.refresh_interval": "5s",
  "index.translog.durability": "async",
  "index.translog.sync_interval": "10s"
}
```

For bulk indexing, disable refresh temporarily:
```json
PUT /my-index/_settings
{ "index.refresh_interval": "-1" }
```
Re-enable after bulk load:
```json
PUT /my-index/_settings
{ "index.refresh_interval": "1s" }
```

## 5. Merge Policy (`<mergePolicyFactory>`)

### 5.1 Solr

```xml
<mergePolicyFactory class="org.apache.solr.index.TieredMergePolicyFactory">
  <int name="maxMergeAtOnce">10</int>
  <int name="segmentsPerTier">10</int>
  <double name="noCFSRatio">0.1</double>
</mergePolicyFactory>
```

### 5.2 OpenSearch Equivalent

```json
PUT /my-index/_settings
{
  "index.merge.policy.max_merge_at_once": 10,
  "index.merge.policy.segments_per_tier": 10,
  "index.merge.policy.expunge_deletes_allowed": 10
}
```

Force merge (equivalent to Solr's `optimize`):
```
POST /my-index/_forcemerge?max_num_segments=1
```

## 6. Similarity (`<similarity>`)

### 6.1 Solr

```xml
<similarity class="solr.SchemaSimilarityFactory">
  <str name="defaultSimFromFieldType">text_general</str>
</similarity>
```

Or per-field:
```xml
<fieldType name="text_bm25" class="solr.TextField">
  <similarity class="solr.BM25SimilarityFactory">
    <float name="k1">1.2</float>
    <float name="b">0.75</float>
  </similarity>
</fieldType>
```

### 6.2 OpenSearch Equivalent

```json
PUT /my-index
{
  "settings": {
    "similarity": {
      "my_bm25": {
        "type": "BM25",
        "k1": 1.2,
        "b": 0.75
      }
    }
  },
  "mappings": {
    "properties": {
      "title": {
        "type": "text",
        "similarity": "my_bm25"
      }
    }
  }
}
```

## 7. Search Components (`<searchComponent>`)

### 7.1 Common Solr Search Components

| Solr Component | Class | OpenSearch Equivalent |
| :--- | :--- | :--- |
| QueryComponent | `solr.QueryComponent` | Core `/_search` |
| FacetComponent | `solr.FacetComponent` | Aggregations |
| HighlightComponent | `solr.HighlightComponent` | `highlight` in request body |
| StatsComponent | `solr.StatsComponent` | `stats` / `extended_stats` aggregation |
| DebugComponent | `solr.DebugComponent` | `"explain": true` |
| SpellCheckComponent | `solr.SpellCheckComponent` | Suggesters API |
| SuggestComponent | `solr.SuggestComponent` | `completion` / `search_as_you_type` |
| MoreLikeThisComponent | `solr.MoreLikeThisComponent` | `more_like_this` query |
| TermsComponent | `solr.TermsComponent` | `terms` aggregation |
| QueryElevationComponent | `solr.QueryElevationComponent` | `pinned` query |

### 7.2 Custom Search Components

Custom `SearchComponent` subclasses must be reimplemented as:
- **Search Pipeline processors** for query/result manipulation.
- **Ingest Pipeline processors** for document-time transformations.
- **Application-layer logic** for anything else.

## 8. `solrconfig.xml` to OpenSearch Settings Quick Reference

| `solrconfig.xml` Element | OpenSearch Equivalent Location |
| :--- | :--- |
| `<requestHandler>` defaults | Search Pipeline / application layer |
| `<searchComponent>` | Built-in query features / Search Pipeline |
| `<updateHandler>` commit settings | `index.refresh_interval`, translog settings |
| `<filterCache>` / `<queryResultCache>` | `indices.queries.cache.size` (cluster setting) |
| `<documentCache>` | Field data cache settings |
| `<mergePolicyFactory>` | `index.merge.policy.*` settings |
| `<similarity>` | `settings.similarity` in index settings |
| `<indexConfig>` lock type, RAM buffer | `index.store.type`, `index.indexing.slowlog.*` |
| `<jmx>` | OpenSearch monitoring via `/_nodes/stats` |
| `<metrics>` | OpenSearch metrics API |
| `<replication>` (standalone Solr) | Built-in replica shards (no config needed) |
| `<lib>` (plugin JARs) | OpenSearch plugin installation (`bin/opensearch-plugin install`) |
