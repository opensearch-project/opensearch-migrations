# Query Behavior and Parser Edge-Case Guide

This document covers known behavioral differences and edge cases between Solr query parsers and OpenSearch query DSL, helping teams identify and resolve subtle result-set discrepancies during migration.

## 1. Default Field and Default Operator

### 1.1 Solr Behavior

Solr's standard and eDisMax parsers use `df` (default field) and `q.op` (default operator):

```
q=hello world&df=text&q.op=AND
```

- `df` sets the field searched when no field is specified in the query string.
- `q.op=OR` (default) means terms are combined with OR.
- `q.op=AND` means all terms must match.

### 1.2 OpenSearch Behavior

In `query_string` and `simple_query_string`:

```json
{
  "query": {
    "query_string": {
      "query": "hello world",
      "default_field": "text",
      "default_operator": "AND"
    }
  }
}
```

**Edge cases:**
- OpenSearch `query_string` defaults to `OR`; forgetting `default_operator: AND` is a common source of result-set divergence.
- If `default_field` is omitted, OpenSearch searches `index.query.default_field` (default: `*`, all fields). Solr searches only `df`.
- Wildcard expansion of `*` in OpenSearch can be expensive and produce different scoring than Solr's single-field default.

## 2. Boolean Query Syntax

### 2.1 Solr Syntax

Solr supports both Lucene boolean syntax (`+`, `-`, `AND`, `OR`, `NOT`) and field-qualified terms:

```
+title:opensearch -status:deprecated body:(migration guide)
```

### 2.2 OpenSearch `query_string` Syntax

OpenSearch `query_string` accepts the same Lucene syntax. However:

| Behavior | Solr | OpenSearch |
| :--- | :--- | :--- |
| `NOT` without preceding term | Allowed (treated as `*:* NOT ...`) | May throw parse error or return unexpected results |
| Leading wildcard (`*term`) | Disabled by default (`allowLeadingWildcard=false`) | Disabled by default (`allow_leading_wildcard: false`) |
| Fuzzy on phrases (`"hello world"~2`) | Proximity (slop) | Proximity (slop) — same behavior |
| Fuzzy on terms (`hello~0.8`) | Similarity 0–1 scale | Edit distance 0–2 scale — **different semantics** |

**Fuzzy similarity scale difference:**
- Solr: `hello~0.8` means 80% similarity (Jaro-Winkler or Levenshtein ratio).
- OpenSearch: `hello~2` means edit distance ≤ 2. Use `fuzziness: AUTO` for automatic distance selection.

## 3. eDisMax vs. `multi_match` Behavioral Differences

### 3.1 Phrase Boost Fields (`pf`, `pf2`, `pf3`)

Solr eDisMax supports phrase boost fields that reward documents where query terms appear as a phrase:

```
q=quick brown fox&qf=title^2 body^1&pf=title^10 body^5&pf2=title^3&pf3=title^2
```

OpenSearch has no direct equivalent. Workaround:

```json
{
  "query": {
    "bool": {
      "should": [
        { "multi_match": { "query": "quick brown fox", "fields": ["title^2", "body^1"], "type": "best_fields" } },
        { "multi_match": { "query": "quick brown fox", "fields": ["title^10", "body^5"], "type": "phrase" } }
      ]
    }
  }
}
```

This approximates `pf` but does not replicate `pf2`/`pf3` (bigram/trigram phrase boosts).

### 3.2 Minimum Should Match (`mm`)

Solr eDisMax `mm` parameter controls the minimum number of optional clauses that must match:

```
q=a b c d&mm=75%
```

OpenSearch equivalent:

```json
{
  "query": {
    "multi_match": {
      "query": "a b c d",
      "fields": ["title", "body"],
      "minimum_should_match": "75%"
    }
  }
}
```

**Edge cases:**
- Solr `mm` supports complex expressions like `2<75%` (if more than 2 terms, require 75%). OpenSearch `minimum_should_match` supports a subset of these expressions — verify complex `mm` values individually.
- When `mm` is applied to a `bool` query with explicit `must`/`should` clauses, the semantics differ from eDisMax's unified handling.

### 3.3 Tie-Breaker (`tie`)

Solr eDisMax `tie` parameter controls how scores from multiple fields are combined:

```
q=hello&qf=title body&tie=0.1
```

OpenSearch `multi_match` with `type: best_fields` uses `tie_breaker`:

```json
{
  "query": {
    "multi_match": {
      "query": "hello",
      "fields": ["title", "body"],
      "type": "best_fields",
      "tie_breaker": 0.1
    }
  }
}
```

This is a direct equivalent.

## 4. Range Queries

### 4.1 Date Math

Solr date math:
```
date:[NOW-7DAYS TO NOW]
date:[2024-01-01T00:00:00Z TO 2024-12-31T23:59:59Z]
```

OpenSearch date math:
```json
{ "range": { "date": { "gte": "now-7d/d", "lte": "now/d" } } }
```

**Differences:**
| Aspect | Solr | OpenSearch |
| :--- | :--- | :--- |
| Keyword | `NOW` | `now` (lowercase) |
| Unit syntax | `NOW-7DAYS` | `now-7d` |
| Rounding | `NOW/DAY` | `now/d` |
| Timezone | `TZ=America/New_York` param | `time_zone: "America/New_York"` in range |

### 4.2 Exclusive vs. Inclusive Bounds

Both Solr and OpenSearch support `[` (inclusive) and `{` (exclusive) in range syntax, but the query DSL uses `gt`/`gte`/`lt`/`lte` — ensure range boundary semantics are preserved when translating.

## 5. Wildcard and Prefix Queries

### 5.1 Wildcard Behavior

| Behavior | Solr | OpenSearch |
| :--- | :--- | :--- |
| `*` matches zero or more chars | ✅ | ✅ |
| `?` matches exactly one char | ✅ | ✅ |
| Leading wildcard (`*term`) | Disabled by default | Disabled by default |
| Wildcard on analyzed fields | Operates on indexed tokens | Operates on indexed tokens |
| Case sensitivity | Depends on analyzer | Depends on analyzer |

**Edge case:** Wildcard queries bypass analysis. If a field uses a lowercasing analyzer, `Title:Hello*` in Solr will not match because the indexed token is `hello`. The same applies in OpenSearch. Always use `keyword` sub-fields for wildcard queries on analyzed text.

### 5.2 Prefix Queries

Solr `title:open*` and OpenSearch `prefix` query behave identically on `keyword` fields. On `text` fields, both operate on analyzed tokens — ensure the analyzer produces the expected tokens.

## 6. Scoring and Relevance Differences

### 6.1 TF-IDF vs. BM25

- Solr prior to version 6 used **TF-IDF** (Classic Similarity) by default.
- Solr 6+ and OpenSearch both default to **BM25**.
- If migrating from a Solr instance using Classic Similarity, scores will differ. Configure OpenSearch to use `classic` similarity if score parity is required during transition.

### 6.2 Field Norms

Solr allows disabling norms per field:
```xml
<field name="title" type="text_general" omitNorms="true"/>
```

OpenSearch equivalent:
```json
{ "title": { "type": "text", "norms": false } }
```

Disabling norms affects scoring — documents in fields with `norms: false` are scored purely on TF and IDF without length normalization.

### 6.3 Coord Factor

Solr's Classic Similarity includes a **coord factor** (rewards documents matching more query terms). BM25 does not use a coord factor. This can cause ranking differences when migrating from Solr TF-IDF to OpenSearch BM25.

## 7. Filter Queries and Caching

### 7.1 Solr `fq` (Filter Query)

Solr filter queries are cached separately from the main query and do not affect scoring:

```
q=opensearch&fq=status:active&fq=category:tech
```

### 7.2 OpenSearch `filter` Context

```json
{
  "query": {
    "bool": {
      "must": { "match": { "body": "opensearch" } },
      "filter": [
        { "term": { "status": "active" } },
        { "term": { "category": "tech" } }
      ]
    }
  }
}
```

**Edge cases:**
- Queries in `filter` context are cached by OpenSearch automatically (equivalent to Solr's `fq` cache).
- Queries in `must` context are scored and not cached.
- Placing scoring queries in `filter` context (or vice versa) is a common migration mistake that changes both performance and results.

## 8. Highlighting Edge Cases

### 8.1 Offset Source

Solr supports `hl.method=unified` (postings), `hl.method=fastVector` (term vectors), and `hl.method=original` (re-analysis).

OpenSearch highlighter types:

| Solr `hl.method` | OpenSearch `type` | Notes |
| :--- | :--- | :--- |
| `unified` | `unified` | Default; uses postings or term vectors |
| `fastVector` | `fvh` (Fast Vector Highlighter) | Requires `term_vector: with_positions_offsets` |
| `original` | `plain` | Re-analyzes at query time; slowest |

**Edge case:** If Solr used `fastVector` highlighting, the migrated OpenSearch index must have `term_vector: with_positions_offsets` set on the field mapping. Existing indices without this setting cannot use `fvh` without re-indexing.

### 8.2 Fragmentation

Solr `hl.fragsize` controls fragment size in characters. OpenSearch `fragment_size` is equivalent. However, Solr's `hl.bs.type=SENTENCE` (sentence boundary) maps to OpenSearch `boundary_scanner: sentence` — verify boundary behavior matches expectations.

## 9. Sorting Edge Cases

### 9.1 Sorting on Multi-Valued Fields

Solr raises an error when sorting on a multi-valued field unless a sort missing value is configured. OpenSearch sorts on multi-valued fields using the first (or last) value by default — specify `mode: min/max/avg/sum` explicitly:

```json
{ "sort": [{ "tags": { "order": "asc", "mode": "min" } }] }
```

### 9.2 Sort Missing Values

Solr `sortMissingLast=true` / `sortMissingFirst=true` on field type:

OpenSearch equivalent per sort clause:
```json
{ "sort": [{ "price": { "order": "asc", "missing": "_last" } }] }
```

### 9.3 Case-Insensitive Sorting

Solr uses `ICUCollationField` for locale-aware, case-insensitive sorting. OpenSearch uses `icu_collation_keyword` (requires the analysis-icu plugin). Without this, sorting on `keyword` fields is byte-order (case-sensitive). Verify sort order matches expectations after migration.

## 10. Cursor / Deep Pagination

### 10.1 Solr Cursor Mark

Solr uses `cursorMark` for efficient deep pagination:
```
q=*:*&sort=id asc&rows=100&cursorMark=*
```

### 10.2 OpenSearch Point-in-Time (PIT) + `search_after`

```json
POST /my-index/_pit?keep_alive=1m
```
```json
{
  "size": 100,
  "query": { "match_all": {} },
  "sort": [{ "id": "asc" }],
  "pit": { "id": "<pit_id>", "keep_alive": "1m" },
  "search_after": ["last-seen-id"]
}
```

**Edge case:** Solr's `cursorMark` requires a sort that includes the `uniqueKey` field. OpenSearch's `search_after` requires a tiebreaker sort (typically `_id` or a unique field). Ensure the sort includes a unique field to guarantee stable pagination.
