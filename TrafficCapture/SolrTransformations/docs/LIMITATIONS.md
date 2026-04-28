# Solr → OpenSearch Transformation Limitations

This document catalogues known limitations that arise from feature differences
between Apache Solr and OpenSearch. Each entry describes the gap, explains
the root cause, and provides a workaround where one exists.

---

## Limitation Index

| Shortcode                                       | Summary |
|-------------------------------------------------|---------|
| [TERMS-OFFSET](#terms-offset)                   | Terms facet `offset` not natively supported in OpenSearch |
| [DATE-RANGE-GAP](#date-range-gap)               | Multi-unit date range gaps approximated as fixed intervals |
| [RANGE-BOUNDARY](#range-boundary)               | Range facet boundary types not fully expressible in OpenSearch |
| [CURSOR-UNIQUEKEY](#cursor-uniquekey)           | Cursor pagination assumes `id` as Solr's uniqueKey field |
| [CURSOR-REPLAY](#cursor-replay)                 | Traffic replay with cursorMark not supported |
| [SOLRCONFIG-REPLAYER](#solrconfig-replayer)     | Traffic replayer requires manual solrConfig in bindingsObject |
| [COMMITWITHIN](#commitwithin)                   | `commitWithin=N` translated to immediate refresh |
| [FQ-LOCAL-PARAMS](#filter-queries-local-params) | Filter query local params (`cache`, `cost`) and post filters not supported |
| [FQ-CACHING](#fq-caching)                       | Filter query caching granularity differs between Solr and OpenSearch |
| [JSON-QUERIES](#json-queries)                   | JSON Request API `queries` key not supported |
| [JSON-PARAM-PREFIX](#json-param-prefix)         | JSON Request API `json.<param>` prefix passthrough not supported |
| [UPDATE-COMMANDS](#update-commands)             | Only `add` and `delete-by-id` commands supported; JSON only |

---

## TERMS-OFFSET

**Feature:** Terms facet pagination via `offset`

**Solr behaviour:**
Solr's JSON Facet API accepts `offset` and `limit` on a `terms` facet,
enabling cursor-style pagination through facet buckets. For example,
`{ type: "terms", field: "color", offset: 10, limit: 5 }` returns buckets
11–15 (zero-indexed).

**OpenSearch behaviour:**
The OpenSearch `terms` aggregation exposes a `size` parameter to control how
many buckets are returned, but it has **no `offset` equivalent**. Every
response always begins from the top-ranked bucket.

**Cause:**
OpenSearch (and the underlying `terms` aggregation) computes
approximate top-N buckets using a distributed coordination algorithm that
does not support skipping to an arbitrary position. An `offset` would
require the engine to still compute and rank all preceding buckets internally,
so the parameter was never exposed.

**Current workaround (applied automatically by the transformer):**
The transformer sets `size = offset + limit` (both default to `0` and `10`
respectively when absent) so that the returned bucket list is large enough
to contain the desired page. Consumers that relied on Solr's `offset`
pagination must **discard the first `offset` buckets** from the OpenSearch
response on the client side.

**Residual impact:**

* **Over-fetching** – More buckets than strictly necessary are returned over
  the wire, increasing response size.
* **Approximation shift** – The `terms` aggregation is approximate. Asking for
  a larger `size` may change which buckets appear and in what order compared
  to a smaller `size`, meaning results can differ slightly from what Solr
  returned for the same logical page.
* **Client changes required** – Any application code that previously consumed
  the Solr response directly must be updated to trim the leading buckets,
  since the transformer can only adjust the *request*, not the response.

---

## DATE-RANGE-GAP

**Feature:** Date range facet with multi-unit calendar gaps (e.g. `+2MONTHS`, `+3YEARS`)

**Solr behaviour:**
Solr's range facet accepts date-math gap strings such as `+1MONTH`, `+2MONTHS`,
or `+3YEARS`. These are calendar-aware — `+1MONTH` starting from January 31
lands on February 28/29, and `+1YEAR` correctly handles leap years.

**OpenSearch behaviour:**
OpenSearch's `date_histogram` aggregation offers two interval parameters:

* **`calendar_interval`** — calendar-aware, but only accepts a **single unit**
  (e.g. `1M`, `1y`, `1d`). Multi-unit values like `2M` or `3y` are rejected.
* **`fixed_interval`** — accepts any multiple (e.g. `5m`, `3h`, `48h`), but
  uses **fixed durations** that do not account for variable-length calendar
  periods.

**Cause:**
There is no OpenSearch interval mode that combines calendar-awareness with
arbitrary multiples. A gap like `+2MONTHS` cannot be directly expressed
because `calendar_interval` rejects `2M` and `fixed_interval` cannot
represent the variable length of months.

**Current workaround (applied automatically by the transformer):**

| Solr gap pattern | OpenSearch mapping | Notes |
|------------------|--------------------|-------|
| Single-unit, any (`+1MONTH`, `+1DAY`, `+1HOUR`, …) | `calendar_interval` (`1M`, `1d`, `1h`, …) | Exact match — no approximation |
| Multi-unit, fixed-duration (`+5MINUTES`, `+3HOURS`, `+90SECONDS`) | `fixed_interval` (`5m`, `3h`, `90s`) | Exact match — these units have constant length |
| Multi-unit, calendar (`+2MONTHS`, `+3YEARS`, `+2DAYS`) | `fixed_interval` with approximation | Approximated using 30 days/month, 365 days/year, 24 hours/day |

For the approximated cases, the transformer converts the gap to a
`fixed_interval` using the largest whole unit (hours, minutes, or seconds)
that divides evenly. For example, `+2MONTHS` becomes `1440h`
(2 × 30 × 24). A console warning is emitted when this approximation is
applied.

**Compound gaps** (e.g. `+1MONTH+2DAYS`, `+1YEAR+6MONTHS`) are also
supported. All components are summed into a total number of seconds using the
same approximation constants, then expressed in the largest whole unit.
For example, `+1MONTH+2DAYS` becomes `768h` (720 + 48). Compound gaps
always use `fixed_interval` since they cannot be expressed as a single
`calendar_interval`.

**Residual impact:**

* **Bucket boundary drift** — Approximated intervals do not align with
  calendar boundaries. A "2-month" bucket starting February 1 would end on
  April 2 (60 days later) instead of April 1. The drift accumulates over
  longer date ranges.
* **Leap year / DST mismatch** — Years are approximated as 365 days (missing
  leap days) and days as 24 hours (missing DST transitions of ±1 hour).
* **Different bucket counts** — The total number of buckets may differ
  slightly from Solr's output because fixed-duration buckets divide the
  date range differently than calendar-aware ones.
* **Compound gap drift** — Compound gaps combining calendar and fixed units
  (e.g. `+1MONTH+2DAYS`) accumulate approximation error from each calendar
  component.

---

## RANGE-BOUNDARY

**Feature:** Range facet with non-default boundary inclusivity

**Solr behaviour:**
Solr's range facet syntax supports four bracket combinations to control
boundary inclusivity on each range: `[from, to)` (default), `(from, to)`,
`[from, to]`, and `(from, to]`. The JSON Facets API `range` type also
accepts explicit `include` options (`lower`, `upper`, `edge`, `outer`, `all`)
that fine-tune which boundaries are inclusive.

**OpenSearch behaviour:**
OpenSearch's `range` aggregation defines each bucket with `from` (inclusive)
and `to` (exclusive). There is no per-bucket option to flip a boundary
between inclusive and exclusive.

**Cause:**
The `range` aggregation hardcodes `[from, to)` semantics. A Solr range
expressed as `(10, 20]` (exclusive start, inclusive end) cannot be
represented exactly — the transformer would need to adjust the numeric
boundary values, which requires knowledge of the field's precision and
data type.

**Current workaround (applied automatically by the transformer):**
The transformer parses the bracket notation and emits a console warning
when the boundaries differ from OpenSearch's default `[from, to)`. The
`from` and `to` values are passed through as-is, so the resulting bucket
boundaries are approximate.

**Residual impact:**

* **Off-by-one at boundaries** — Documents exactly at a boundary value may
  be included or excluded differently than in Solr. For example, a Solr
  range `(10, 20]` excludes 10 and includes 20, but OpenSearch's
  `[10, 20)` includes 10 and excludes 20.
* **Floating-point edge cases** — For decimal fields the mismatch is
  typically negligible, but for integer fields a single-value difference
  at the boundary can change bucket counts.

---

## CURSOR-UNIQUEKEY

**Feature:** Cursor pagination with custom `uniqueKey` field

**Solr behaviour:**
Solr requires `cursorMark` queries to include the `uniqueKey` field
(configured in `schema.xml`) in the sort clause. The default is `id`, but
deployments can configure any field. Solr rejects the query if the
`uniqueKey` is missing from the sort.

**OpenSearch behaviour:**
OpenSearch uses `_id` as the document identifier. The `search_after`
pagination mechanism requires the sort to include a tiebreaker field.

**Cause:**
The cursor pagination transform hardcodes `id` as the Solr `uniqueKey`
and maps it to OpenSearch's `_id`. Deployments using a custom `uniqueKey`
(e.g., `doc_id`) may see a redundant `id asc` tiebreaker added, and the
custom field will not be mapped to `_id`.

**Current workaround:**
None. The vast majority of Solr deployments use the default `id`. A
configurable `uniqueKey` option can be added in a future iteration.

---

## CURSOR-REPLAY

**Feature:** Replaying captured Solr traffic containing `cursorMark`

**Solr behaviour:**
Solr's `cursorMark` tokens are opaque, binary-encoded values derived from
sort values. They are only meaningful to the Solr instance that generated them.

**Cause:**
The traffic replayer cannot decode Solr's native cursor tokens into
OpenSearch `search_after` values. Only the initial page (`cursorMark=*`)
works during replay.

**Current workaround:**
This applies only to offline traffic replay. The live shim proxy handles
cursor pagination correctly using its own base64-encoded tokens.


---

## SOLRCONFIG-REPLAYER

**Feature:** Solrconfig defaults in traffic replayer mode

**Limitation:**
The traffic replayer uses `JsonJSTransformerProvider` which does not have
access to `SolrConfigProvider` for XML parsing. The solrConfig must be
provided as pre-built JSON in the `bindingsObject` of `--transformerConfig`.

Both the shim proxy and the traffic replayer use `--transformerConfig`
with the same provider-based JSON array format. The shim uses
`SolrTransformerProvider` which auto-prepends the GraalVM polyfill and
supports `solrConfigXmlFile` for automatic XML parsing.

**Example (shim proxy — with solrconfig.xml auto-parsing):**
```
--transformerConfig '[{"SolrTransformerProvider": {
  "initializationScriptFile": "/path/to/request.js",
  "bindingsObject": "{}",
  "solrConfigXmlFile": "/path/to/solrconfig.xml"
}}]'
```

**Example (shim proxy — with inline solrConfig JSON in bindings):**
```
--transformerConfig '[{"SolrTransformerProvider": {
  "initializationScriptFile": "/path/to/request.js",
  "bindingsObject": "{\"solrConfig\": {\"/select\": {\"defaults\": {\"df\": \"title\"}}}}"
}}]'
```

**Example (traffic replayer — uses JsonJSTransformerProvider):**
```
--transformerConfig '[{"JsonJSTransformerProvider": {
  "initializationScriptFile": "/path/to/request.js",
  "bindingsObject": "{\"solrConfig\": {\"/select\": {\"defaults\": {\"df\": \"title\"}}}}"
}}]'
```

**Note:** The traffic replayer cannot use `SolrTransformerProvider` directly
because `SolrConfigProvider` lives in the `transformationShim` module.
A future PR can move it to a shared module.


---

## COMMITWITHIN

**Feature:** Timed commit via `commitWithin=N` parameter

**Solr behaviour:**
`commitWithin=N` tells Solr to make the document searchable within `N`
milliseconds. Solr batches multiple updates and performs a single commit
when the timer expires, which is more efficient than per-request commits.

**OpenSearch behaviour:**
OpenSearch has no equivalent timed refresh. The `refresh` parameter only
supports `true` (immediate), `false` (no refresh), or `wait_for` (wait
for next scheduled refresh).

**Current translation:**
`commitWithin=N` is translated to `?refresh=true`, which makes the document
immediately searchable. This satisfies the "searchable within N ms" contract
but is more aggressive than Solr's batched approach.

**Residual impact:**

* **Performance** — Every request with `commitWithin` triggers an immediate
  refresh in OpenSearch, which is more expensive than Solr's batched commit.
  Under high write throughput, this can degrade indexing performance.
* **Behavioral difference** — Solr batches commits for efficiency; OpenSearch
  refreshes per-request. Applications that relied on `commitWithin` for
  batching will see higher refresh overhead.


---

## Filter Queries Local Params

**Feature:** Filter query local parameters (`cache`, `cost`) and post filters

**Solr behaviour:**
Solr's `fq` parameter supports local parameters that control caching and
evaluation order:

* **`cache`** — Boolean that controls whether filter results are cached
  (default: `true`). Example: `fq={!geofilt cache=false}...`
* **`cost`** — Numeric hint for evaluation order of non-cached filters.
  Lower cost filters run first. Example: `fq={!frange cost=200 l=0}...`
* **Post filters** — When `cache=false` and `cost>=100`, filters implementing
  the `PostFilter` interface run as collectors after the main query and other
  filters have matched. This is efficient for expensive filters.

Example from Solr docs:
```
fq={!cache=false}quantity_in_stock:[5 TO *]
fq={!frange cache=false l=10 u=100}mul(popularity,price)
fq={!frange cache=false cost=200 l=0}pow(mul(sum(1, query('tag:smartphone')), div(1,avg_rating)), 2.3)
```

**OpenSearch behaviour:**
OpenSearch's `bool.filter` clause does not expose caching controls or
evaluation order hints. All filters are evaluated by the query engine
with its own internal optimization strategies.

**Cause:**
OpenSearch manages filter caching internally and does not provide
user-facing parameters to control it. There is no equivalent to Solr's
`cost` parameter or post-filter mechanism.

**Current workaround:**
The transformer ignores `cache` and `cost` local parameters. The filter
query content is still translated and added to `bool.filter`, but the
caching and ordering hints are silently dropped.

**Residual impact:**

* **No caching control** — Filters that were intentionally uncached in Solr
  (e.g., highly variable geospatial queries) will use OpenSearch's default
  caching behavior.
* **No evaluation ordering** — Expensive filters that relied on high `cost`
  values to run last will execute in OpenSearch's default order, potentially
  impacting query performance.
* **No post-filter optimization** — Filters designed as post-filters in Solr
  will run as regular filters in OpenSearch, which may be less efficient for
  certain query patterns.

---

## FQ-CACHING

**Feature:** Independent caching of multiple filter queries

**Solr behaviour:**
In Apache Solr, each `fq` parameter is cached independently in the filter
cache. The document set (bitset) for each filter is stored as a separate
cache entry. This allows frequently-used filters to be reused across
different queries.

From the Solr documentation:
> The document sets from each filter query are cached independently. Thus,
> use a single fq containing two mandatory clauses if those clauses appear
> together often, and use two separate fq parameters if they are relatively
> independent.

Example optimization in Solr:
```
# Two separate fq params — each cached independently for reuse
fq=category:books&fq=inStock:true

# Single fq with combined clauses — cached as one unit
fq=category:books AND inStock:true
```

**OpenSearch behaviour:**
In OpenSearch, filters in the `bool.filter` clause are executed in filter
context (no scoring) and may be cached automatically by the engine.

From the OpenSearch documentation:
> To improve performance, OpenSearch caches frequently used filters.

When using a `bool` query with multiple filters in the `filter` clause,
OpenSearch may cache individual filter clauses, but this is heuristic-driven
and depends on factors such as query frequency, selectivity, and segment
lifecycle.

**Cause:**
Both systems use Lucene-level bitsets for filter execution. However:

- Solr provides explicit, per-`fq` cache entries
- OpenSearch uses adaptive caching, where filters are cached only when
  deemed beneficial

As a result, while the query structure maps cleanly (`fq` → `bool.filter`),
caching behavior is not strictly equivalent.

**Current behaviour:**
The transformer maps multiple `fq` parameters to multiple entries in
`bool.filter`, preserving the logical separation of filters.

However, unlike Solr, OpenSearch does not guarantee that each filter will
be cached independently. Cache reuse depends on runtime behavior and
internal heuristics.

**Residual impact:**

* **Less predictable cache reuse** — Frequently-used filters in Solr may not
  consistently benefit from caching in OpenSearch.
* **Heuristic-based caching** — Filter caching depends on usage patterns,
  cardinality, and segment changes rather than explicit configuration.
* **No explicit cache control** — Solr's `{!cache=false}` local param is not
  supported; caching decisions are automatic.
* **Different warm-up characteristics** — Solr supports cache warming;
  OpenSearch caches warm up through live traffic.
* **Potential performance variance** — Workloads relying heavily on repeated
  `fq` reuse may observe different latency characteristics after migration.

---

---

## JSON-QUERIES

**Feature:** Named sub-queries via `queries` key in JSON Request API

**Solr behaviour:**
The JSON Request API supports a `queries` key for defining named sub-queries
that can be referenced elsewhere in the request using local params syntax
(`{!v=$query_name}`):
```json
{
  "query": "*:*",
  "queries": {
    "electronics": "category:electronics"
  },
  "filter": ["{!v=$electronics}"]
}
```

**Current status:**
Not supported. The `queries` key depends on local params (`{!...}`) syntax
which is also not supported. The `queries` key in the JSON body is silently
ignored.

**Reference:** https://solr.apache.org/guide/solr/latest/query-guide/json-query-dsl.html#additional-queries

---

## JSON-PARAM-PREFIX

**Feature:** `json.<param_name>` URL parameter prefix

**Solr behaviour:**
Solr allows JSON body properties to be specified as URL query parameters
using the `json.` prefix. For example, `json.limit=5` is equivalent to
`{"limit": 5}` in the JSON body. This enables overriding JSON body values
from the URL.

**Current status:**
Not supported. The `json.` prefix passthrough is a URL-to-JSON-body bridge
(opposite direction from what the `json-request` transform handles). URL
parameters with the `json.` prefix are treated as unsupported params and
rejected by validation.

**Reference:** https://solr.apache.org/guide/solr/latest/query-guide/json-request-api.html#json-parameter-merging

---

## UPDATE-COMMANDS

**Feature:** Solr `/update` endpoint command support

**Solr behaviour:**
The `/update` endpoint accepts JSON or XML bodies with multiple command types:
`add`, `delete` (by id or query), `commit`, `optimize`, `rollback`. Commands
can be mixed in a single request and can use arrays for bulk operations.
Both JSON and XML content types are supported.

**Current support:**

| Command | Status |
|---|---|
| `add` (single doc) | ✅ Supported — `{"add":{"doc":{...}}}` |
| `add` with `boost` | ❌ Not supported — fails fast (OpenSearch has no document-level boost) |
| `add` with `overwrite: false` | ❌ Not supported — fails fast (OpenSearch always overwrites) |
| `delete` by id | ✅ Supported — `{"delete":{"id":"..."}}` |
| `delete` by query | ❌ Not supported — fails fast |
| `commit` | ❌ Not supported — fails fast |
| `optimize` / `rollback` | ❌ Not supported — fails fast |
| Mixed commands | ❌ Not supported — fails fast |
| Array/bulk operations | ❌ Not supported — fails fast |
| XML content type | ❌ Not supported — fails fast (JSON only) |

**Content type:**
Only `application/json` request bodies are supported. XML bodies
(`text/xml`, `application/xml`) cannot be parsed by the shim and will
fail with an error. Clients using XML format must switch to JSON.
