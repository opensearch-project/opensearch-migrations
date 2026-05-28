# Query Behavior and Parser Edge-Case Guide

This document covers behavioral differences and edge cases between Solr query parsers and OpenSearch query DSL that cause subtle result-set discrepancies during migration.

## 1. Default Field and Default Operator

Solr uses `df` and `q.op` to set the default field and operator. OpenSearch equivalents in `query_string`:

```json
{ "query_string": { "query": "hello world", "default_field": "text", "default_operator": "AND" } }
```

**Edge cases:**
- OpenSearch `query_string` defaults to `OR` (per [Elasticsearch query_string docs](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-query-string-query.html)); you MUST NOT omit `default_operator: AND` when migrating from a Solr `q.op=AND` setting because that omission is the most common source of result divergence.
- If `default_field` is omitted, OpenSearch falls back to the `index.query.default_field` index setting, which itself defaults to `*` (all eligible fields, excluding metadata) — so a missing `default_field` searches all fields, while Solr's `df` searches only the named field. You MUST set `default_field` explicitly to match Solr `df` behavior; otherwise scoring and recall will differ.
- You SHOULD avoid wildcard expansion of `*` across all fields because it is expensive and produces different scoring than Solr's single-field default.

## 2. Boolean Query Syntax

OpenSearch `query_string` accepts Lucene syntax, but with differences:

| Behavior | Solr | OpenSearch |
| :--- | :--- | :--- |
| `NOT` without preceding term | Allowed (`*:* NOT ...`) | May throw parse error |
| Leading wildcard (`*term`) | Disabled by default | Disabled by default |
| Fuzzy on terms (`hello~`, `hello~2`) | Edit distance 0–2 (Damerau-Levenshtein) since Solr 4 | Edit distance 0–2 |
| Fuzzy on phrases (`"hello world"~2`) | Proximity slop | Proximity slop — same |

Per the [Solr standard query parser docs](https://solr.apache.org/guide/solr/latest/query-guide/standard-query-parser.html), `~N` "specifies the maximum number of edits allowed, between 0 and 2, defaulting to 2." The legacy 0–1 similarity-fraction syntax (`hello~0.8`) from Solr 3 was removed; only integer edit distances are accepted today. You SHOULD use `fuzziness: AUTO` in OpenSearch for sensible per-term-length defaults. **Skill IP** (operational observation): if the source system is genuinely on Solr 3 or older, the 0–1 fraction MUST be re-expressed as an integer edit distance during query translation.

## 3. eDisMax vs. `multi_match` Behavioral Differences

### 3.1 Phrase Boost Fields (`pf`, `pf2`, `pf3`)

No direct OpenSearch equivalent. You SHOULD approximate `pf` with a `should` phrase clause. You MUST NOT promise `pf2`/`pf3` parity because OpenSearch has no native bigram/trigram phrase-boost equivalent:

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

Solr `mm` and OpenSearch `minimum_should_match` accept the **same** conditional syntax (e.g. `"2<-25%"`, `"3<90%"`, percentages, fixed values, multi-clause expressions). You MUST pass the source Solr `mm` value unchanged on `multi_match` or `bool.should` because no translation is required.

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

Wildcard queries bypass analysis in both systems. `Title:Hello*` will not match if the indexed token is `hello`. You MUST use `keyword` sub-fields for wildcard queries on analyzed text because analyzed `text` fields lower-case at index time and the wildcard term won't match the analyzed token.

## 6. Scoring and Relevance Differences

- **TF-IDF vs. BM25**: Solr ≤ 5.x defaulted to ClassicSimilarity (TF-IDF); Solr 6.0+ defaulted to BM25 via `SchemaSimilarityFactory` (per [Solr 6.6 Other Schema Elements](https://solr.apache.org/guide/6_6/other-schema-elements.html)); OpenSearch defaults to BM25. You MUST set `"similarity": { "type": "classic" }` in OpenSearch index settings if score parity is required during transition from a TF-IDF source.
- **Coord factor**: ClassicSimilarity rewards documents matching more query terms via a coord factor; BM25 has no coord factor. You MUST expect ranking to differ when migrating from Solr ClassicSimilarity.
- **Field norms**: Solr `omitNorms="true"` → OpenSearch `"norms": false`. Affects length normalization in scoring.

## 7. Filter Queries and Caching

Solr `fq` → OpenSearch `filter` context in a `bool` query. Queries in `filter` context are cached; queries in `must` context are scored and not cached. You MUST NOT place scoring queries in `filter` (or vice versa) because doing so changes both performance and results — it's a common migration mistake.

## 8. Highlighting Edge Cases

| Solr `hl.method` | OpenSearch `type` | Notes |
| :--- | :--- | :--- |
| `unified` | `unified` | Default |
| `fastVector` | `fvh` | Requires `term_vector: with_positions_offsets` on the field — you MUST re-index if missing |
| `original` | `plain` | Re-analyzes at query time |

Solr `hl.bs.type=SENTENCE` → OpenSearch `boundary_scanner: sentence`.

## 9. Sorting Edge Cases

- **Multi-valued fields**: Solr errors on sort; OpenSearch uses first/last value by default. You MUST specify `mode: min/max/avg/sum` explicitly.
- **Sort missing**: Solr `sortMissingLast=true` → OpenSearch `"missing": "_last"` per sort clause.
- **Case-insensitive sort**: Solr `ICUCollationField` → OpenSearch `icu_collation_keyword` (requires the `analysis-icu` plugin; included on all Amazon OpenSearch Service domains per [AWS supported plugins](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/supported-plugins.html)). Without it, `keyword` sort is byte-order (case-sensitive).

## 10. Cursor / Deep Pagination

Solr `cursorMark` → OpenSearch Point-in-Time (PIT) + `search_after`. Both require a sort including a unique field as tiebreaker. OpenSearch PIT IDs expire; you MUST set `keep_alive` appropriately.

## 11. Solr-only query parsers with no OpenSearch equivalent

| Solr | Migration |
|---|---|
| `{!complexphrase}` (wildcards/fuzzy inside phrase) | `span_near` + `span_multi` for ordered proximity with wildcards |
| `{!surround}` (`3W(a,b)`, `5N(a,b)` proximity) | `span_near` with `slop` + `in_order` |
| `{!graph}` (graph traversal) | No equivalent — pre-compute reachable IDs at index time, iterative app-side traversal, or dedicated graph DB feeding `terms` filter |
| `{!switch}` (conditional routing) | App-layer routing |
| `{!rerank}` (secondary-Lucene two-phase rerank) | OpenSearch 2.12+ `rerank` uses ML models; approximate with `function_score` + `filter`/`weight` (note: all docs scored, not just top-N) |

For broader Solr legacy-feature gaps (DIH, BlockJoin, function queries, query elevation, external file fields), see [`solr-legacy-features.md`](solr-legacy-features.md).
