# Solr to OpenSearch Incompatibilities Steering

Every incompatibility found must be recorded in `SessionState.incompatibilities` before proceeding. Never silently skip a known issue. When in doubt, flag it — a false positive is less harmful than a missed breaking change.

## Incompatibilities

- **Custom plugins** (**Breaking**): Solr custom `RequestHandler` and `SearchComponent` Java plugins have no direct equivalent. Rebuild logic using OpenSearch Search Pipelines (2.9+), Ingest Pipelines, or client-side code.
- **Cross-collection joins** (**Breaking**): Solr `{!join fromIndex=...}` cross-collection joins are not supported. Denormalize at index time or use application-side two-query joins.
- **Trie field types** (**Breaking**): `TrieIntField`, `TrieLongField`, `TrieFloatField`, `TrieDoubleField` have no OpenSearch equivalent. Map to `integer`, `long`, `float`, `double` and cast values to native JSON numbers.
- **Function queries** (**Warning**): Solr function queries (`recip`, `log`, `product`, `bf`) map to OpenSearch `function_score` with `script_score` or built-in functions, but syntax differs significantly.
- **eDisMax `pf`/`pf2`/`pf3`/`mm`/`tie`** (**Warning**): These eDisMax parameters have no direct OpenSearch equivalents. Approximate with `multi_match` (`cross_fields`, `best_fields`) and `bool` query combinations; validate result parity.
- **Dynamic fields** (**Warning**): Solr `dynamicField` patterns map to OpenSearch `dynamic_templates`. Behavior is similar but rule syntax differs — review every pattern.
- **Nested / block join docs** (**Warning**): Solr `_childDocuments_` (block join) maps to OpenSearch `nested` type or `join` field type. Query syntax is completely different; parent-child queries must be rewritten.
- **Spatial fields** (**Warning**): Solr `LatLonPointSpatialField` and `SpatialRecursivePrefixTreeFieldType` map to `geo_point` and `geo_shape`. Convert `"lat,lon"` strings to `{"lat": ..., "lon": ...}` objects.
- **Date math syntax** (**Warning**): Solr date math (`NOW-1DAY/DAY`) differs from OpenSearch (`now-1d/d`). Translate all date math expressions in queries and range filters.
- **Default query operator** (**Warning**): Solr defaults to `OR`; OpenSearch `query_string` defaults to `OR` but `match` uses `OR` — verify `minimum_should_match` and `operator` settings match intended behavior.
- **Similarity / scoring** (**Info**): Both default to BM25 since Solr 7 / OpenSearch 1.0, but parameter defaults differ. If custom similarity was configured in `schema.xml`, replicate it via `similarity` settings in the OpenSearch mapping.
- **ZooKeeper removed** (**Info**): SolrCloud requires an external ZooKeeper ensemble. OpenSearch uses a built-in cluster manager — decommission ZooKeeper after migration.

## What Counts as a Breaking Incompatibility

- A Solr feature used in production that has no functional OpenSearch equivalent.
- A query that cannot be translated without changing result semantics.
- A field type that requires data transformation before indexing.
- Any plugin or custom handler that must be rebuilt before the application can go live.
