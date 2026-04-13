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

## 9. Summary: Feature Gap Reference Table

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
