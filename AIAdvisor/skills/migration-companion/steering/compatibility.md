# Version Compatibility and Known Issues

## Elasticsearch to OpenSearch

### Version Compatibility
- ES 1.x–2.x → OS 1.x–3.x: Backfill only (no Capture & Replay)
- ES 5.x–8.x → OS 1.x–3.x: Backfill + Capture & Replay supported
- ES 8.x → OS 1.x: Not supported (version gap too large)

### Known Issues
- **Type mappings:** ES 5.x/6.x indices may use multiple types per index. These must
  be flattened to single-type indices for OpenSearch. The Type Mapping Sanitization
  Transformer handles this automatically during RFS backfill.
- **Field type changes:** `sparse_vector` → `rank_features`, `dense_vector` → `knn_vector`,
  ELSER-specific types may need removal.
- **Security plugin:** Elasticsearch X-Pack security does not migrate. OpenSearch uses
  its own Security plugin. Roles, users, and permissions must be recreated.
- **Kibana objects:** Kibana saved objects (dashboards, visualizations) need the
  Dashboards Sanitizer tool for conversion to OpenSearch Dashboards format.
- **Ingest pipelines:** Not automatically migrated. Must be recreated manually.
- **ILM → ISM:** Elasticsearch Index Lifecycle Management policies must be converted
  to OpenSearch Index State Management policies.

## OpenSearch to OpenSearch

### Version Compatibility
- OS 1.x → OS 1.x–3.x: Supported
- OS 2.x → OS 2.x–3.x: Supported
- OS 3.x → OS 3.x: Planned

### Known Issues
- Generally the smoothest migration path. Most settings and mappings transfer directly.
- Check for deprecated settings between major versions.
- Plugin compatibility may vary between versions.

## Solr to OpenSearch

### Version Compatibility
- Solr 8.x–9.x → OS 3.x: Backfill via SolrReader

### Architectural Differences
- **No `_source` field:** Solr does not store the original document by default.
  SolrReader reconstructs documents from stored fields. Fields marked `stored="false"`
  in the Solr schema cannot be recovered.
- **Schema model:** Solr uses `schema.xml` or Managed Schema with explicit field type
  definitions. OpenSearch uses JSON mappings. The conversion is not always 1:1.
- **Query model:** Solr query syntax (standard, DisMax, eDisMax) differs significantly
  from OpenSearch Query DSL. Query translation is an assessment aid, not a migration
  mechanism.
- **No live capture:** Capture & Replay is not supported for Solr sources.
- **ZooKeeper → Cluster Manager:** SolrCloud's ZooKeeper ensemble is replaced by
  OpenSearch's built-in cluster manager nodes.

### Field Type Mapping
| Solr Type | OpenSearch Type | Notes |
|---|---|---|
| `solr.StrField` | `keyword` | Direct mapping |
| `solr.TextField` | `text` | Analyzer chain must be converted |
| `solr.IntPointField` | `integer` | Direct mapping |
| `solr.LongPointField` | `long` | Direct mapping |
| `solr.FloatPointField` | `float` | Direct mapping |
| `solr.DoublePointField` | `double` | Direct mapping |
| `solr.BoolField` | `boolean` | Direct mapping |
| `solr.DatePointField` | `date` | Direct mapping |
| `solr.TrieIntField` | `integer` | Deprecated in Solr 7+; map to Point equivalent |
| `solr.TrieLongField` | `long` | Deprecated in Solr 7+; map to Point equivalent |
| `solr.TrieFloatField` | `float` | Deprecated in Solr 7+; map to Point equivalent |
| `solr.TrieDoubleField` | `double` | Deprecated in Solr 7+; map to Point equivalent |
| `solr.TrieDateField` | `date` | Deprecated in Solr 7+; map to Point equivalent |
| `solr.SpatialRecursivePrefixTreeFieldType` | `geo_shape` | Approximate mapping |
| `solr.LatLonPointSpatialField` | `geo_point` | Direct mapping |
