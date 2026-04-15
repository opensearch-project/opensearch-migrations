# Query Behavior and Parser Edge-Case Guide

This document covers behavioral differences and edge cases between Solr query parsers and OpenSearch query DSL that cause subtle result-set discrepancies during migration.

## 1. Default Field and Default Operator

Solr uses `df` and `q.op` to set the default field and operator. OpenSearch equivalents in `query_string`:

```json
{ "query_string": { "query": "hello world", "default_field": "text", "default_operator": "AND" } }
```

**Edge cases:**
- OpenSearch defaults to `OR`; omitting `default_operator: AND` is the most common source of result divergence.
- If `default_field` is omitted, OpenSearch searches all fields (`*`). Solr searches only `df` — scoring and recall will differ.
- Wildcard expansion of `*` across all fields is expensive and produces different scoring than Solr's single-field default.

## 2. Boolean Query Syntax

OpenSearch `query_string` accepts Lucene syntax, but with differences:

| Behavior | Solr | OpenSearch |
| :--- | :--- | :--- |
| `NOT` without preceding term | Allowed (`*:* NOT ...`) | May throw parse error |
| Leading wildcard (`*term`) | Disabled by default | Disabled by default |
| Fuzzy on terms (`hello~0.8`) | Similarity 0–1 scale | Edit distance 0–2 — **different semantics** |
| Fuzzy on phrases (`"hello world"~2`) | Proximity slop | Proximity slop — same |

Solr `hello~0.8` = 80% similarity; OpenSearch `hello~2` = edit distance ≤ 2. Use `fuzziness: AUTO` in OpenSearch.

## 3. eDisMax vs. `multi_match` Behavioral Differences

### 3.1 Phrase Boost Fields (`pf`, `pf2`, `pf3`)

No direct OpenSearch equivalent. Approximate `pf` with a `should` phrase clause; `pf2`/`pf3` (bigram/trigram boosts) cannot be replicated:

```json
{
  "query": {
    "bool": {
      "should": [
        { "multi_match": { "query": "quick brown fox", "fields": ["title^2", "body"], "type": "best_fields" } },
        { "multi_match": { "query": "quick brown fox", "fields": ["title^10", "body^5"], "type": "phrase" } }
      ]
    }
  }
}
```

### 3.2 Minimum Should Match (`mm`) Complex Expressions

Solr `mm` supports expressions like `2<75%` (if >2 terms, require 75%). OpenSearch `minimum_should_match` supports a subset — verify complex `mm` values individually.

### 3.3 Tie-Breaker (`tie`)

`tie` in Solr eDisMax maps directly to `tie_breaker` in OpenSearch `multi_match` with `type: best_fields`.

## 4. Range Queries — Date Math Differences

| Aspect | Solr | OpenSearch |
| :--- | :--- | :--- |
| Keyword | `NOW` | `now` (lowercase) |
| Unit syntax | `NOW-7DAYS` | `now-7d` |
| Rounding | `NOW/DAY` | `now/d` |
| Timezone | `TZ=America/New_York` param | `time_zone: "America/New_York"` in range query |

## 5. Wildcard Queries

Wildcard queries bypass analysis in both systems. `Title:Hello*` won't match if the indexed token is `hello`. Always use `keyword` sub-fields for wildcard queries on analyzed text.

## 6. Scoring and Relevance Differences

- **TF-IDF vs. BM25**: Solr < 6 defaulted to TF-IDF (Classic Similarity); Solr 6+ and OpenSearch default to BM25. Set `"similarity": { "type": "classic" }` in OpenSearch index settings if score parity is required during transition.
- **Coord factor**: Solr Classic Similarity rewards documents matching more query terms via a coord factor. BM25 has no coord factor — ranking will differ when migrating from Solr TF-IDF.
- **Field norms**: Solr `omitNorms="true"` → OpenSearch `"norms": false`. Affects length normalization in scoring.

## 7. Filter Queries and Caching

Solr `fq` → OpenSearch `filter` context in a `bool` query. Queries in `filter` context are cached; queries in `must` context are scored and not cached. Placing scoring queries in `filter` (or vice versa) is a common migration mistake that changes both performance and results.

## 8. Highlighting Edge Cases

| Solr `hl.method` | OpenSearch `type` | Notes |
| :--- | :--- | :--- |
| `unified` | `unified` | Default |
| `fastVector` | `fvh` | Requires `term_vector: with_positions_offsets` on the field — must re-index if missing |
| `original` | `plain` | Re-analyzes at query time |

Solr `hl.bs.type=SENTENCE` → OpenSearch `boundary_scanner: sentence`.

## 9. Sorting Edge Cases

- **Multi-valued fields**: Solr errors on sort; OpenSearch uses first/last value by default. Specify `mode: min/max/avg/sum` explicitly.
- **Sort missing**: Solr `sortMissingLast=true` → OpenSearch `"missing": "_last"` per sort clause.
- **Case-insensitive sort**: Solr `ICUCollationField` → OpenSearch `icu_collation_keyword` (requires analysis-icu plugin). Without it, `keyword` sort is byte-order (case-sensitive).

## 10. Cursor / Deep Pagination

Solr `cursorMark` → OpenSearch Point-in-Time (PIT) + `search_after`. Both require a sort including a unique field as tiebreaker. OpenSearch PIT IDs expire; set `keep_alive` appropriately.

## 11. Solr-Only Query Parsers With No OpenSearch Equivalent

### 11.1 `{!complexphrase}` — Wildcards Inside Phrases

Allows wildcards and fuzzy terms inside phrase queries: `{!complexphrase}title:"uni* search~1"`. No OpenSearch equivalent. Use `span_near` + `span_multi` for ordered proximity with wildcards:

```json
{
  "query": {
    "span_near": {
      "clauses": [
        { "span_multi": { "match": { "wildcard": { "title": "uni*" } } } },
        { "span_multi": { "match": { "fuzzy": { "title": { "value": "search", "fuzziness": 1 } } } } }
      ],
      "slop": 0, "in_order": true
    }
  }
}
```

### 11.2 `{!surround}` — Proximity Operator Syntax

`3W(a, b)` (within 3 words, ordered), `5N(a, b)` (within 5, any order). Use OpenSearch `span_near` with `slop` and `in_order`.

### 11.3 `{!graph}` — Graph Traversal

Traverses document relationships across fields to find connected subgraphs. **No OpenSearch equivalent.** Options:
- Pre-compute reachable node IDs at index time and store as a multi-valued field.
- Iterative application-side traversal (multiple queries expanding the frontier).
- Dedicated graph database for traversal; feed result IDs as a `terms` filter into OpenSearch.

### 11.4 `{!switch}` — Conditional Query Routing

Routes to different sub-queries based on the query string value. No OpenSearch equivalent — implement in the application layer.

### 11.5 `{!rerank}` — Query-Based Two-Phase Reranking

Rescores top-N results with a secondary Lucene query. OpenSearch 2.12+ `rerank` uses ML models, not a secondary query. Approximate with `function_score` + `filter`/`weight` functions, accepting that all documents are scored (not just top-N).

> For atomic update modifiers, `_version_` concurrency, `QueryElevationComponent`, `ExternalFileField`, and `PreAnalyzedField` gaps, see `05b-legacy-features-continued.md`.
