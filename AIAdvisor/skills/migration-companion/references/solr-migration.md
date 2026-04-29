# Solr Migration Reference

## Migration Mechanisms

### SolrReader — Backfill
- Reads Solr backup data: Lucene segments + schema.xml
- Translates Solr field types to OpenSearch mappings
- Bulk-indexes documents into OpenSearch
- Backfill only — no live capture support for Solr sources

### Transformation Shim — Assessment Tool
- HTTP proxy that translates Solr API requests (`/select`) to OpenSearch `_search`
- Four operating modes:
  1. Solr-only passthrough
  2. OpenSearch-only
  3. Dual-target Solr-primary (shadow validation)
  4. Dual-target OpenSearch-primary (cutover with safety net)
- Used for compatibility evaluation, NOT as a migration mechanism
- Helps users validate that their application queries work against OpenSearch

## Schema Conversion

### Process
1. Parse `schema.xml` or Schema API JSON
2. Map Solr field types to OpenSearch types (see compatibility.md)
3. Convert analyzer chains (tokenizers + filters)
4. Handle copy fields (may become multi-match queries)
5. Flag incompatible features

### Common Analyzer Mappings
| Solr Analyzer | OpenSearch Equivalent |
|---|---|
| `solr.StandardTokenizerFactory` | `standard` tokenizer |
| `solr.WhitespaceTokenizerFactory` | `whitespace` tokenizer |
| `solr.LowerCaseFilterFactory` | `lowercase` filter |
| `solr.StopFilterFactory` | `stop` filter |
| `solr.SynonymGraphFilterFactory` | `synonym_graph` filter |
| `solr.SnowballPorterFilterFactory` | `snowball` filter |
| `solr.EdgeNGramFilterFactory` | `edge_ngram` filter |
| `solr.NGramFilterFactory` | `ngram` filter |
| `solr.ASCIIFoldingFilterFactory` | `asciifolding` filter |

### Known Incompatibilities
- Solr `copyField` has no direct OpenSearch equivalent. Use `copy_to` in mappings
  or multi-match queries at search time.
- Solr `dynamicField` maps to OpenSearch `dynamic_templates`.
- Solr `uniqueKey` maps to OpenSearch `_id` (but OpenSearch auto-generates IDs by default).
- Solr `similarity` configuration (TF-IDF vs BM25) — OpenSearch defaults to BM25.

## Query Translation

### Solr Standard Query → OpenSearch Query DSL
| Solr | OpenSearch |
|---|---|
| `field:value` | `{"term": {"field": "value"}}` |
| `field:value*` | `{"prefix": {"field": "value"}}` |
| `field:[min TO max]` | `{"range": {"field": {"gte": min, "lte": max}}}` |
| `field:val1 AND field:val2` | `{"bool": {"must": [...]}}` |
| `field:val1 OR field:val2` | `{"bool": {"should": [...]}}` |
| `NOT field:value` | `{"bool": {"must_not": [...]}}` |

### Solr DisMax/eDisMax → OpenSearch
| Solr Parameter | OpenSearch Equivalent |
|---|---|
| `qf` (query fields) | `multi_match.fields` with boosts |
| `pf` (phrase fields) | `multi_match` with `type: phrase` |
| `bq` (boost query) | `bool.should` with boost |
| `bf` (boost function) | `function_score` |
| `tie` (tie breaker) | `multi_match.tie_breaker` |
| `mm` (minimum match) | `minimum_should_match` |

## Architecture Mapping

| Solr Concept | OpenSearch Equivalent |
|---|---|
| SolrCloud cluster | OpenSearch cluster |
| Collection | Index |
| Shard | Shard |
| Replica | Replica |
| ZooKeeper ensemble | Cluster manager nodes (built-in) |
| Config set | Index template |
| Request handler | Search endpoint + query configuration |
| Update handler | Bulk API / index API |
| PULL replica | Hot-warm tiering with ISM |
