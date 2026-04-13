# Solr to OpenSearch Query Translation

This document provides guidance on converting Apache Solr queries to OpenSearch Query DSL. It covers standard Solr query syntax, eDisMax parameters, and common behavioral differences between the two search engines.

## 1. Standard Query Parameter Mapping

The most common Solr query parameters map directly to OpenSearch query structures:

| Solr Parameter | OpenSearch Mapping | Notes |
| :--- | :--- | :--- |
| `q` | `query` | The main query expression. |
| `fq` (Filter Query) | `filter` in a `bool` query | Used for non-scoring filtering. |
| `sort` | `sort` | Mappings for field names and order (asc/desc). |
| `start`, `rows` | `from`, `size` | For pagination. |
| `fl` (Field List) | `_source` or `fields` | Specifies which fields to return in results. |
| `wt` (Writer Type) | N/A | OpenSearch typically returns JSON. |

## 2. Standard Solr Query Syntax to OpenSearch DSL

Solr's standard query parser uses Lucene syntax. Below are the mappings to OpenSearch Query DSL:

| Solr Syntax | OpenSearch DSL Mapping | Example |
| :--- | :--- | :--- |
| `field:value` | `match` | `{"match": {"field": "value"}}` |
| `field:"phrase"` | `match_phrase` | `{"match_phrase": {"field": "phrase"}}` |
| `field:val*` / `field:*val` | `wildcard` | `{"wildcard": {"field": "val*"}}` |
| `field:[low TO high]` | `range` (inclusive) | `{"range": {"field": {"gte": low, "lte": high}}}` |
| `field:{low TO high}` | `range` (exclusive) | `{"range": {"field": {"gt": low, "lt": high}}}` |
| `+term` / `term1 AND term2` | `bool` -> `must` | `{"bool": {"must": [{"match": {"f": "t1"}}, {"match": {"f": "t2"}}]}}` |
| `term1 OR term2` | `bool` -> `should` | `{"bool": {"should": [...], "minimum_should_match": 1}}` |
| `-term` / `NOT term` | `bool` -> `must_not` | `{"bool": {"must_not": [{"match": {"field": "term"}}]}}` |
| `*:*` | `match_all` | `{"match_all": {}}` |
| Plain `term` | `query_string` | `{"query_string": {"query": "term"}}` |

## 3. eDisMax Parameter Translation

The Extended DisMax (eDisMax) parser is widely used in Solr. Its parameters map to OpenSearch's `multi_match` and `bool` queries:

| eDisMax Parameter | OpenSearch Mapping | Logic |
| :--- | :--- | :--- |
| `qf` (Query Fields) | `multi_match` -> `fields` | Uses `type: "best_fields"`. Supports `field^boost`. |
| `pf` (Phrase Fields) | `multi_match` -> `type: "phrase"` | Added as a `should` clause for phrase boosting. |
| `pf2`, `pf3` | `multi_match` -> `type: "phrase"` | Bigram/trigram phrase boosts. |
| `mm` (Min Should Match) | `minimum_should_match` | Applied to the `bool` or `multi_match` query. |
| `tie` (Tiebreaker) | `tie_breaker` | For `multi_match` scoring. |
| `ps` (Phrase Slop) | `slop` | Applied to `pf` phrase clauses. |
| `qs` (Query Slop) | `slop` | Applied to `qf` match clauses. |
| `bq` (Boost Query) | `bool` -> `should` | Additive boost queries. |
| `bf` (Boost Function) | `script_score` | Approximated via Painless scripting. |

## 4. Key Behavioral Differences and Limitations

When migrating queries, keep the following differences in mind:

- **Additive vs. Multiplicative Boosts**: Solr's `bq` (Boost Query) is typically additive to the score. In OpenSearch, adding a query to a `should` clause also has an additive effect on the total score, but the combined coordination factor may result in different final rankings compared to Solr.
- **Function Queries (`bf`)**: Solr has a rich set of built-in functions for `bf`. OpenSearch uses `script_score` with the Painless scripting language. Complex Solr functions may require manual translation into Painless.
- **Default Operator**: Solr's default operator (AND/OR) is configured in `solrconfig.xml`. OpenSearch's `query_string` defaults to `OR`.
- **Nested Grouping**: Very complex nested Lucene queries in Solr might not have a 1:1 mapping in structured DSL and may require using the `query_string` query in OpenSearch to preserve original logic.
- **Fuzzy/Proximity**: Solr's `~` operator for fuzziness or proximity is best handled by OpenSearch's `match` (with `fuzziness`) or `match_phrase` (with `slop`).

## 5. Solr 6 Query Features and Legacy Considerations

Solr 6 introduced and stabilized several query features. When migrating from Solr 6, be aware of the following:

### 5.1 DisMax vs. eDisMax

Solr 6 supports both the older `DisMax` parser and the newer `eDisMax` parser. The eDisMax parser is a superset of DisMax and is preferred. When migrating:

- If `defType=dismax` is set in `solrconfig.xml` or per-request, treat it the same as eDisMax for migration purposes. The core parameters (`qf`, `pf`, `mm`, `tie`, `bq`, `bf`) are identical.
- If `defType=edismax` (or unset, as eDisMax is often the default), use the full eDisMax translation in section 3.

### 5.2 Standard Query Parser (`lucene` defType)

In Solr 6, the default query parser is often the standard Lucene parser (`defType=lucene` or no `defType`). Queries submitted without a `defType` use Lucene syntax directly. Use the mappings in section 2 for these queries.

### 5.3 Local Params Syntax

Solr 6 queries frequently use the local params syntax to switch parsers inline:

| Solr Local Params Syntax | Meaning | OpenSearch Approach |
| :--- | :--- | :--- |
| `{!lucene}field:value` | Use standard Lucene parser | Translate using section 2 rules |
| `{!dismax qf="title body"}search term` | Use DisMax parser | Translate using section 3 (eDisMax) rules |
| `{!edismax qf="title^2 body" mm=75%}search term` | Use eDisMax parser | Translate using section 3 rules |
| `{!term f=status}active` | Exact term query | `{"term": {"status": "active"}}` |
| `{!prefix f=title}open` | Prefix query | `{"prefix": {"title": "open"}}` |
| `{!frange l=0 u=10}price` | Function range filter | `{"range": {"price": {"gte": 0, "lte": 10}}}` |

Strip the `{!...}` local params block before translating the remaining query string.

### 5.4 Function Queries (`_val_` and `{!func}`)

Solr 6 supports function queries in several forms:

| Solr Form | Example | OpenSearch Equivalent |
| :--- | :--- | :--- |
| `_val_:"recip(rord(price),1,1000,1000)"` | Reciprocal rank boost | `function_score` with `script_score` |
| `{!func}log(popularity)` | Log of a field value | `script_score` with Painless: `Math.log10(doc['popularity'].value)` |
| `bf=recip(ms(NOW,last_modified),3.16e-11,1,1)` | Date recency boost | `script_score` with date arithmetic in Painless |

Common Solr 6 function query translations to Painless:

- `recip(x, m, a, b)` → `a / (m * x + b)` — implement as a Painless expression in `script_score`
- `log(x)` → `Math.log10(doc['field'].value)`
- `sqrt(x)` → `Math.sqrt(doc['field'].value)`
- `ms(NOW, field)` → `(System.currentTimeMillis() - doc['field'].value.millis)`
- `ord(field)` / `rord(field)` → no direct equivalent; approximate with field value or use `rank_feature`

### 5.5 Spatial / Geo Queries

Solr 6 spatial queries must be translated to OpenSearch geo queries:

| Solr 6 Spatial Syntax | OpenSearch Equivalent |
| :--- | :--- |
| `{!geofilt sfield=location pt=37.77,-122.41 d=10}` | `{"geo_distance": {"distance": "10km", "location": {"lat": 37.77, "lon": -122.41}}}` |
| `{!bbox sfield=location pt=37.77,-122.41 d=10}` | `{"geo_bounding_box": {...}}` (compute bbox from center+distance) |
| `location:[37,-122 TO 38,-121]` (lat/lon range) | `{"geo_bounding_box": {"location": {"top_left": {...}, "bottom_right": {...}}}}` |

### 5.6 Collapse and Expand (Field Collapsing)

Solr 6's `CollapsingQParserPlugin` (`{!collapse field=group_id}`) and `expand` component have no direct single-query equivalent in OpenSearch. Approximate with:

- Use `collapse` → OpenSearch `collapse` parameter (available in OpenSearch for field collapsing by a keyword field).
- For more complex group-based deduplication, use aggregations (`terms` agg + `top_hits`).

### 5.7 Join Queries

Solr 6 supports `{!join from=id to=product_id}category:electronics`. OpenSearch does not support cross-document joins in the same way. Options:

- Denormalize data at index time (preferred).
- Use nested objects or parent-child (`join` field type) for related documents.
- Perform application-side joins with two separate queries.

### 5.8 `q.op` and `df` Parameters

Solr 6 queries often rely on `q.op` (default operator) and `df` (default field) set in `solrconfig.xml` or passed per-request:

- `q.op=AND` → set `"default_operator": "AND"` in the OpenSearch `query_string` query.
- `q.op=OR` → `"default_operator": "OR"` (OpenSearch default).
- `df=title` → set `"default_field": "title"` in the OpenSearch `query_string` query.

Example:

**Solr:** `q=opensearch&q.op=AND&df=title`

**OpenSearch:**
```json
{
  "query": {
    "query_string": {
      "query": "opensearch",
      "default_operator": "AND",
      "default_field": "title"
    }
  }
}
```

## 6. Solr 4 and 5 Query Features and Legacy Considerations

### 6.1 Query Parser Defaults

In Solr 4/5, always check `solrconfig.xml` for the `defType` in `<requestHandler>` defaults (eDisMax was less commonly the default than in Solr 6):

```xml
<requestHandler name="/select" class="solr.SearchHandler">
  <lst name="defaults">
    <str name="defType">dismax</str>
    <str name="q.op">OR</str>
    <str name="df">text</str>
  </lst>
</requestHandler>
```

Translate these defaults into `default_operator` and `default_field` in the OpenSearch `query_string` query (see section 5.8).

### 6.2 Trie-Based Numeric and Date Fields

Solr 4/5 used Trie-based numeric/date types (replaced by Point types in Solr 6.3+). Map them identically to OpenSearch numeric types:

| Solr 4/5 Field Type | OpenSearch Type |
| :--- | :--- |
| `solr.TrieIntField` | `integer` |
| `solr.TrieLongField` | `long` |
| `solr.TrieFloatField` | `float` |
| `solr.TrieDoubleField` | `double` |
| `solr.TrieDateField` | `date` |

`precisionStep` (indexing granularity) has no equivalent in OpenSearch; omit it.

### 6.3 Solr 4 DisMax (Original)

Solr 4 commonly used the original `DisMax` parser (`defType=dismax`). It does not support `pf2`/`pf3`, the `boost` parameter, or nested local params. Translate using the eDisMax mapping in section 3, ignoring those unsupported parameters.

### 6.4 Solr 4/5 Faceting as Aggregations

Solr 4/5 faceting parameters (`facet=true`, `facet.field`, `facet.query`, `facet.range`) have no direct query-level equivalent in OpenSearch. Translate them to OpenSearch aggregations:

| Solr 4/5 Facet Parameter | OpenSearch Aggregation |
| :--- | :--- |
| `facet.field=category` | `terms` aggregation on `category` |
| `facet.query=price:[0 TO 50]` | `filter` aggregation with a `range` query |
| `facet.range` on a numeric field | `histogram` or `range` aggregation |
| `facet.range` on a date field | `date_histogram` aggregation |
| `facet.pivot=category,brand` | Nested `terms` aggregations |

Example:

**Solr 4/5:** `facet=true&facet.field=category&facet.mincount=1`

**OpenSearch:**
```json
{
  "aggs": {
    "category_facet": {
      "terms": {
        "field": "category",
        "min_doc_count": 1
      }
    }
  }
}
```

### 6.5 Solr 4/5 Highlighting

Solr 4/5 highlighting parameters map to OpenSearch `highlight`: `hl.fl` → `fields`, `hl.snippets` → `number_of_fragments`, `hl.fragsize` → `fragment_size`, `hl.simple.pre/post` → `pre_tags`/`post_tags`.

### 6.6 Solr 4/5 Grouping (`group=true`)

Solr 4/5 result grouping maps to OpenSearch `collapse` or aggregations: `group.field=category` → `collapse: {"field": "category"}`; `group.ngroups=true` → `cardinality` aggregation; full group results → `terms` agg with `top_hits`.

### 6.7 Solr 4 Spatial Queries (Legacy `LatLonType`)

Solr 4 used `solr.LatLonType` (a two-field composite) before `LatLonPointSpatialField` was introduced in Solr 5/6. Translate `{!geofilt}` and `{!bbox}` the same way as Solr 6 spatial queries (section 5.5). Note that Solr 4 stored lat/lon as two sub-fields; consolidate into a single `geo_point` field in OpenSearch.

### 6.8 Solr 4/5 `MoreLikeThis` (MLT)

Solr 4/5 `MoreLikeThis` queries (`mlt=true`, `mlt.fl`, `mlt.mindf`) translate to OpenSearch's `more_like_this` query:

**Solr 4/5:** `mlt=true&mlt.fl=title,body&mlt.mindf=2&mlt.mintf=1`

**OpenSearch:**
```json
{
  "query": {
    "more_like_this": {
      "fields": ["title", "body"],
      "like": [{"_id": "<doc_id>"}],
      "min_doc_freq": 2,
      "min_term_freq": 1
    }
  }
}
```

## 7. Example Translation

**Solr eDisMax Query:**
`q=opensearch&qf=title^2 body&mm=2&pf=title&ps=1&bq=category:featured`

**OpenSearch Query DSL:**
```json
{
  "query": {
    "bool": {
      "must": [
        {
          "multi_match": {
            "query": "opensearch",
            "fields": ["title^2", "body"],
            "type": "best_fields"
          }
        }
      ],
      "should": [
        {
          "multi_match": {
            "query": "opensearch",
            "fields": ["title"],
            "type": "phrase",
            "slop": 1
          }
        },
        {
          "match": {
            "category": "featured"
          }
        }
      ],
      "minimum_should_match": "2"
    }
  }
}
```
