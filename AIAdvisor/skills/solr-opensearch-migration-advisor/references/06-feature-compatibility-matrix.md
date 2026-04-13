# Solr to OpenSearch Feature Compatibility Matrix

This document provides a comprehensive compatibility matrix covering Solr features and their OpenSearch equivalents, organized by functional area.

## 1. Schema and Field Types

| Solr Feature | OpenSearch Equivalent | Compatibility | Notes |
| :--- | :--- | :--- | :--- |
| `solr.StrField` | `keyword` | Full | Exact match, not analyzed |
| `solr.TextField` | `text` | Full | Analyzed; configure analyzer separately |
| `solr.IntPointField` / `solr.TrieIntField` | `integer` | Full | Use Point fields in Solr 7+ |
| `solr.LongPointField` / `solr.TrieLongField` | `long` | Full | |
| `solr.FloatPointField` / `solr.TrieFloatField` | `float` | Full | |
| `solr.DoublePointField` / `solr.TrieDoubleField` | `double` | Full | |
| `solr.BoolField` | `boolean` | Full | |
| `solr.DatePointField` / `solr.TrieDateField` | `date` | Full | ISO 8601 format compatible |
| `solr.BinaryField` | `binary` | Full | Base64-encoded |
| `solr.UUIDField` | `keyword` with `uuid` format | Full | No auto-generation; use ingest pipeline |
| `solr.LatLonPointSpatialField` | `geo_point` | Full | |
| `solr.SpatialRecursivePrefixTreeFieldType` | `geo_shape` | Partial | WKT/GeoJSON supported; some Solr-specific shapes differ |
| `solr.ICUCollationField` | `icu_collation_keyword` (analysis-icu plugin) | Partial | Requires ICU Analysis plugin |
| `solr.EnumField` | `keyword` | Partial | No built-in enum ordering; use numeric mapping |
| `solr.CurrencyFieldType` | `scaled_float` or `double` | Partial | No multi-currency conversion built in |
| `solr.ExternalFileField` | No direct equivalent | None | Use `function_score` with a lookup or script |
| `solr.PreAnalyzedField` | No direct equivalent | None | Pre-tokenize at ingest via ingest pipeline |
| `solr.NestPath` / `_nest_path_` | `nested` type | Partial | Different internal representation |
| Dynamic fields (`dynamicField`) | Dynamic templates | Partial | Pattern matching is less expressive |
| Copy fields (`copyField`) | `copy_to` parameter | Full | Defined per field in mappings |

## 2. Query Parsers and Query Features

| Solr Feature | OpenSearch Equivalent | Compatibility | Notes |
| :--- | :--- | :--- | :--- |
| Standard query parser (`defType=lucene`) | `query_string` query | Full | Syntax largely compatible |
| DisMax (`defType=dismax`) | `dis_max` + `multi_match` | Full | |
| eDisMax (`defType=edismax`) | `multi_match` (best_fields / most_fields) | Partial | `pf`, `pf2`, `pf3` have no direct equivalent |
| `{!term}` | `term` query | Full | |
| `{!terms}` | `terms` query | Full | |
| `{!prefix}` | `prefix` query | Full | |
| `{!wildcard}` | `wildcard` query | Full | |
| `{!fuzzy}` | `fuzzy` query | Full | |
| `{!range}` | `range` query | Full | |
| `{!join}` cross-collection | No direct equivalent | None | Denormalize or application-side join |
| `{!child}` / `{!parent}` block join | `has_child` / `has_parent` queries | Partial | Requires `join` field type in OpenSearch |
| `{!geofilt}` | `geo_distance` query | Full | |
| `{!bbox}` | `geo_bounding_box` query | Full | |
| `{!collapse}` | `collapse` parameter (OpenSearch 2.x) | Full | |
| `{!expand}` | No direct equivalent | None | Implement via aggregations |
| Function queries (`_val_`, `{!func}`) | `function_score` with `script_score` | Partial | Solr functions must be rewritten as Painless scripts |
| `{!knn}` (Solr 9+) | `knn` query | Full | Vector field type required |
| `{!rerank}` | Rerank query (OpenSearch 2.12+) | Partial | Different reranking model interface |
| `{!mlt}` | `more_like_this` query | Full | |
| `{!graph}` | No direct equivalent | None | Graph traversal not natively supported |

## 3. Search Components and Request Handlers

| Solr Feature | OpenSearch Equivalent | Compatibility | Notes |
| :--- | :--- | :--- | :--- |
| `/select` handler | `/_search` endpoint | Full | |
| `/get` real-time get | `/_doc/{id}` GET | Full | |
| `/update` handler | `/_bulk`, `/_doc` PUT/POST | Full | |
| `/admin/ping` | `/_cluster/health` | Full | |
| FacetComponent (`facet=true`) | `terms` aggregation | Full | |
| Facet ranges (`facet.range`) | `range` / `date_histogram` aggregation | Full | |
| Facet pivots (`facet.pivot`) | Nested aggregations | Full | |
| JSON Facet API | Aggregations API | Full | |
| StatsComponent (`stats=true`) | `stats` / `extended_stats` aggregation | Full | |
| HighlightComponent | Highlight API | Full | |
| SpellCheckComponent | `term_suggester` / `phrase_suggester` | Full | |
| SuggestComponent | `completion` suggester / `search_as_you_type` | Full | |
| MoreLikeThis handler (`/mlt`) | `more_like_this` query | Full | |
| TermsComponent | `terms` aggregation | Full | |
| QueryElevationComponent | Pinned query (OpenSearch 2.x) | Partial | Requires `pinned` query type |
| DebugComponent (`debugQuery=true`) | `"explain": true` in request | Full | |
| Streaming Expressions | Aggregations + SQL plugin | Partial | No 1:1 mapping; rewrite required |
| SQL interface (`/sql`) | OpenSearch SQL plugin (`/_plugins/_sql`) | Full | |
| Export handler (`/export`) | Scroll API / Point-in-Time API | Full | |
| Data Import Handler (DIH) | No direct equivalent | None | Use Logstash, Kafka Connect, or custom ETL |
| Custom RequestHandler | Search Pipelines (OpenSearch 2.9+) | Partial | Complex logic may require a custom plugin |
| Custom SearchComponent | Ingest Pipelines / Search Pipelines | Partial | |

## 4. Analysis and Text Processing

| Solr Feature | OpenSearch Equivalent | Compatibility | Notes |
| :--- | :--- | :--- | :--- |
| `StandardTokenizerFactory` | `standard` tokenizer | Full | |
| `WhitespaceTokenizerFactory` | `whitespace` tokenizer | Full | |
| `KeywordTokenizerFactory` | `keyword` tokenizer | Full | |
| `NGramTokenizerFactory` | `ngram` tokenizer | Full | |
| `EdgeNGramTokenizerFactory` | `edge_ngram` tokenizer | Full | |
| `PatternTokenizerFactory` | `pattern` tokenizer | Full | |
| `LowerCaseFilterFactory` | `lowercase` token filter | Full | |
| `StopFilterFactory` | `stop` token filter | Full | |
| `SynonymGraphFilterFactory` | `synonym_graph` token filter | Full | |
| `PorterStemFilterFactory` | `porter_stem` token filter | Full | |
| `SnowballPorterFilterFactory` | `snowball` token filter | Full | |
| `ICUFoldingFilterFactory` | `icu_folding` (analysis-icu plugin) | Full | Requires ICU Analysis plugin |
| `ICUTokenizerFactory` | `icu_tokenizer` (analysis-icu plugin) | Full | Requires ICU Analysis plugin |
| `ManagedSynonymGraphFilterFactory` | Synonyms via index settings API | Partial | No live reload without index close/open |
| `ClassicTokenizerFactory` | `classic` tokenizer | Full | |
| `HTMLStripCharFilterFactory` | `html_strip` char filter | Full | |
| `MappingCharFilterFactory` | `mapping` char filter | Full | |
| `PatternReplaceCharFilterFactory` | `pattern_replace` char filter | Full | |
| `OpenNLPTokenizerFactory` | NLP via ML inference (OpenSearch ML) | Partial | Requires ML plugin and model deployment |

## 5. Indexing and Document Management

| Solr Feature | OpenSearch Equivalent | Compatibility | Notes |
| :--- | :--- | :--- | :--- |
| Atomic updates (`set`, `add`, `remove`) | Partial update via `_update` with `doc` | Partial | No `add`/`remove` for arrays; use scripts |
| In-place updates | `_update` with `doc` | Partial | Only for fields without `copy_to` or nested |
| Optimistic concurrency (`_version_`) | `if_seq_no` / `if_primary_term` | Partial | Different mechanism; not field-based |
| Soft commits / hard commits | Refresh API (`/_refresh`) | Partial | No equivalent to Solr's commit model |
| `commitWithin` | `refresh=wait_for` | Partial | |
| Document expiry (`TTL`) | Index Lifecycle Management (ILM) | Partial | No per-document TTL; use ILM policies |
| Nested / child documents | `nested` type or `join` field | Partial | Different data model |
| Bulk indexing (`/update` with JSON/CSV/XML) | Bulk API (`/_bulk`) | Full | JSON only in OpenSearch |

## 6. Cluster and Operations

| Solr Feature | OpenSearch Equivalent | Compatibility | Notes |
| :--- | :--- | :--- | :--- |
| Collections API | Index API + Index Templates | Full | |
| Configsets (ZooKeeper) | Index templates / component templates | Full | |
| Aliases | Index aliases | Full | |
| Shard splitting | Split index API | Partial | Different mechanics; requires re-indexing |
| Replica types (NRT, TLOG, PULL) | Replica shards (no type distinction) | Partial | OpenSearch replicas are always NRT-equivalent |
| Overseer | Cluster Manager node | Full | No ZooKeeper dependency |
| Metrics API | `/_nodes/stats`, `/_cluster/stats` | Full | |
| Autoscaling (Solr 8+) | OpenSearch Autoscaling (plugin) | Partial | Different policy model |
| Cross-collection search | Cross-index search (`index1,index2/_search`) | Full | |
| Collection backup/restore | Snapshot/restore API | Full | |
| Security (Basic Auth, Kerberos) | OpenSearch Security plugin | Partial | Kerberos not natively supported |
| TLS/SSL | OpenSearch Security plugin (TLS) | Full | |
| Role-based access control | OpenSearch Security plugin (RBAC) | Full | |

**Legend:**
- Full — Direct equivalent exists with minimal configuration change
- Partial — Equivalent exists but requires rewriting, plugin, or behavioral differences
- None — No direct equivalent; architectural change or workaround required
