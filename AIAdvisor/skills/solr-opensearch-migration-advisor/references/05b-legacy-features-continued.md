# Solr Legacy Features: Migration Strategies (Continued)

This document is a continuation of `05-legacy-features.md`, covering joins, streaming expressions, spell check, MoreLikeThis, custom handlers, and the full feature gap summary table.

## 4. Solr Joins (`{!join}`)

### 4.1 What It Is

Solr supports cross-collection joins using the `{!join}` query parser:

```
{!join from=product_id to=id fromIndex=products}category:electronics
```

This finds documents whose `id` matches a `product_id` in the `products` collection where `category=electronics`.

### 4.2 Migration Strategy

OpenSearch does not support cross-index joins at query time. Options:

- **Denormalize**: Copy joined fields into the main document at index time (most performant).
- **Application-side join**: Execute two queries — fetch IDs from the "foreign" index, then use a `terms` query with those IDs in the main index.
- **`join` field type**: For parent-child within a single index (see `05-legacy-features.md` section 2.3).
- **`terms` lookup**: Use the `terms` query with a lookup to fetch IDs from another index document (limited to a single document's field values).

---

## 5. Solr Streaming Expressions and SQL

### 5.1 What They Are

Solr 6+ supports Streaming Expressions (a functional language for distributed data processing) and a SQL interface (`/sql` endpoint) for analytics, joins, rollups, and data export.

### 5.2 Migration Strategy

| Solr Feature | OpenSearch Equivalent |
| :--- | :--- |
| Streaming Expressions (`stream`, `search`, `facet`, `rollup`) | OpenSearch aggregations (terms, date_histogram, sum, avg, etc.) |
| SQL interface (`/sql`) | OpenSearch SQL plugin (`/_plugins/_sql`) |
| `export` handler (deep cursor export) | Scroll API or Point-in-Time (PIT) API |
| `stats` component | `stats` aggregation |
| `facet` streaming | `terms` aggregation |

---

## 6. Solr Spell Check and Suggest Components

### 6.1 What They Are

Solr has dedicated `SpellCheckComponent` and `SuggestComponent` request handlers for query spell correction and autocomplete suggestions.

### 6.2 Migration Strategy

| Solr Feature | OpenSearch Equivalent |
| :--- | :--- |
| `SpellCheckComponent` | `term_suggester` or `phrase_suggester` in the Suggesters API |
| `SuggestComponent` (prefix) | `completion` suggester with a `completion` field type |
| `SuggestComponent` (infix) | `search_as_you_type` field type |
| `AnalyzingInfixSuggester` | `search_as_you_type` field type |

**`completion` field example:**
```json
{
  "mappings": {
    "properties": {
      "suggest": { "type": "completion" }
    }
  }
}
```

---

## 7. Solr MoreLikeThis (MLT)

### 7.1 What It Is

Solr's `MoreLikeThis` component (`/mlt` handler or `mlt=true` parameter) finds documents similar to a given document based on term frequency.

### 7.2 Migration Strategy

Use OpenSearch's `more_like_this` query:

```json
{
  "query": {
    "more_like_this": {
      "fields": ["title", "body"],
      "like": [{ "_index": "articles", "_id": "doc-1" }],
      "min_term_freq":    1,
      "max_query_terms": 12,
      "min_doc_freq":     5
    }
  }
}
```

| Solr MLT Parameter | OpenSearch Equivalent |
| :--- | :--- |
| `mlt.fl` | `fields` |
| `mlt.mintf` | `min_term_freq` |
| `mlt.mindf` | `min_doc_freq` |
| `mlt.maxqt` | `max_query_terms` |
| `mlt.minwl` | `min_word_length` |
| `mlt.maxwl` | `max_word_length` |

---

## 8. Solr Custom Request Handlers and Search Components

### 8.1 What They Are

Solr allows custom Java `RequestHandler` and `SearchComponent` plugins registered in `solrconfig.xml` to implement custom search pipelines, pre/post-processing, and business logic.

### 8.2 Migration Strategy

- **Move logic to the client**: Implement pre/post-processing in the application layer before sending queries to OpenSearch or after receiving results.
- **OpenSearch Ingest Pipelines**: For document transformation at index time, use ingest pipelines with built-in processors (set, rename, gsub, script, etc.).
- **Search Pipelines** (OpenSearch 2.9+): Use search request/response processors for query rewriting and result post-processing.
- **Custom OpenSearch Plugin**: For complex cases, implement a custom OpenSearch plugin (Java), though this requires significant engineering effort.

---

## 9. Atomic Updates and Optimistic Concurrency

### 9.1 Solr Atomic Update Modifiers

Solr supports field-level updates without re-sending the full document:

```json
{ "id": "product-1", "price": { "set": 29.99 }, "tags": { "add": "sale" }, "views": { "inc": 1 } }
```

Supported modifiers: `set`, `add`, `add-distinct`, `remove`, `removeregex`, `inc`.

OpenSearch has no equivalent modifiers. Use a Painless script via the `_update` API:

```
POST /products/_update/product-1
{
  "script": {
    "source": "ctx._source.views += params.count; ctx._source.tags.add(params.tag)",
    "params": { "count": 1, "tag": "sale" }
  }
}
```

**Critical edge case:** Solr atomic updates work with `stored="false"` if `docValues="true"`. OpenSearch partial updates require `_source` to be enabled (the default). If `_source` is disabled, partial updates are impossible.

### 9.2 `_version_` vs. `if_seq_no`/`if_primary_term`

Solr uses a `_version_` field for optimistic concurrency:
- `_version_ > 0`: must exist at exactly this version
- `_version_ = 1`: must exist (any version)
- `_version_ = -1`: must not exist

OpenSearch uses `if_seq_no` and `if_primary_term` query parameters — not stored in `_source` and not queryable. Application code using `_version_` must be rewritten to read `_seq_no`/`_primary_term` from index/get responses.

---

## 10. `QueryElevationComponent` vs. `pinned` Query

Solr's `QueryElevationComponent` pins documents to the top and excludes others per query, configured in `elevate.xml` with runtime reload support.

OpenSearch `pinned` query pins by ID but has gaps:
- No built-in per-query exclude — wrap in `bool` with `must_not: { ids: [...] }`.
- No file-based config — elevation rules must be injected at query time by the application.
- No runtime reload of rules without application-layer changes.

---

## 11. `ExternalFileField` and `PreAnalyzedField`

### 11.1 `ExternalFileField`

Loads boost values from a disk file without re-indexing — useful for frequently-updated signals like click-through rates:

```xml
<fieldType name="boost_field" class="solr.ExternalFileField" keyField="id" valType="float"/>
```

**No OpenSearch equivalent.** Options:
- Store boost values in the index and use partial updates (`_update` API) when values change.
- Pre-compute and embed boost values at index time.
- Use `function_score` with a `script_score` doing a `terms` lookup (limited to small datasets).

### 11.2 `PreAnalyzedField`

Accepts pre-tokenized input (tokens + positions + offsets), bypassing the analysis chain — useful for custom NLP pipelines:

```xml
<fieldType name="pre_analyzed" class="solr.PreAnalyzedField"/>
```

**No OpenSearch equivalent.** Options:
- Use an Ingest Pipeline with a `script` processor to transform pre-analyzed tokens at index time.
- Index tokens as a `keyword` array (loses position/offset data, breaking phrase queries and highlighting).
- Use the ML Inference ingest processor (OpenSearch 2.x) to integrate external NLP models.

---

## 12. Summary: Feature Gap Reference Table

| Solr Feature | Direct OpenSearch Equivalent? | Recommended Strategy |
| :--- | :--- | :--- |
| Data Import Handler (DIH) | ❌ No | External ETL (Logstash, Kafka Connect, custom script) |
| BlockJoin (`_childDocuments_`) | ⚠️ Partial | `nested` type or `join` field type |
| `{!join}` cross-collection | ❌ No | Denormalize or application-side join |
| Function queries (`recip`, `log`, etc.) | ⚠️ Partial | `function_score` with `script_score` or built-in functions |
| `bf` / `boost` eDisMax params | ⚠️ Partial | `function_score` wrapping the main query |
| Streaming Expressions | ⚠️ Partial | Aggregations + SQL plugin |
| SQL interface | ✅ Yes | OpenSearch SQL plugin |
| SpellCheck component | ✅ Yes | `term_suggester` / `phrase_suggester` |
| Suggest component | ✅ Yes | `completion` / `search_as_you_type` |
| MoreLikeThis | ✅ Yes | `more_like_this` query |
| Custom RequestHandler | ❌ No | Client-side logic or Search Pipelines |
| Custom SearchComponent | ❌ No | Client-side logic or Ingest Pipelines |
| `CollapsingQParserPlugin` | ✅ Yes | `collapse` parameter |
| `{!geofilt}` / `{!bbox}` | ✅ Yes | `geo_distance` / `geo_bounding_box` |
| `{!complexphrase}` | ❌ No | `span_near` + `span_multi` (partial) |
| `{!surround}` proximity | ⚠️ Partial | `span_near` with `slop` and `in_order` |
| `{!graph}` traversal | ❌ No | Pre-compute at index time or app-side traversal |
| `{!switch}` routing | ❌ No | Application-layer conditional logic |
| `{!rerank}` query-based | ⚠️ Partial | `function_score` (scores all docs, not top-N) |
| Atomic update modifiers (`add`, `inc`, etc.) | ❌ No | Painless script via `_update` API |
| `_version_` optimistic concurrency | ⚠️ Partial | `if_seq_no` / `if_primary_term` (different API) |
| `QueryElevationComponent` | ⚠️ Partial | `pinned` query (no file config, no exclude, no reload) |
| `ExternalFileField` | ❌ No | Embed boost at index time or use partial updates |
| `PreAnalyzedField` | ❌ No | Ingest pipeline script or `keyword` array (lossy) |
| `CurrencyFieldType` | ⚠️ Partial | `scaled_float` or `double`; no multi-currency conversion |
| `EnumField` (ordered) | ⚠️ Partial | `keyword` (no ordering) or numeric mapping for sort |
| `{!expand}` | ❌ No | Aggregations (`terms` + `top_hits`) |
