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
| [UPDATE-COMMANDS](#update-commands)             | Only `add`, `delete-by-id`, and `delete-by-query` commands supported; JSON only |
| [DELETE-BY-QUERY](#delete-by-query)             | Delete-by-query always synchronous; version conflicts treated as partial failure |
| [BULK-PARTIAL-FAILURE](#bulk-partial-failure)   | `_bulk` partial failures surface as `errors[]`; Solr's default strict mode has no OpenSearch equivalent |
| [NDJSON-INGEST-PARITY](#ndjson-ingest-parity)   | Shim does not parse NDJSON request bodies on ingress (not exercised by Solr clients, but diverges from replayer) |
| [MM-AUTORELAX](#mm-autorelax)                   | eDisMax `mm.autoRelax` parameter not supported |
| [MM-EDISMAX-OPERATOR-DEFAULT](#mm-edismax-operator-default) | eDisMax `mm` default with explicit operators not fully replicated |
| [MM-EDISMAX-MIXED-OPERATORS](#mm-edismax-mixed-operators) | eDisMax `mm` with mixed explicit and implicit operators applies to wrong scope |
| [MM-STOPWORD-MULTIFIELD](#mm-stopword-multifield) | `mm` with per-field stopword removal differs from Solr |
| [DISMAX](#dismax)                                | `defType=dismax` not supported — use `defType=edismax` instead |
| [BQ-NEG-BOOST](#bq-neg-boost)                   | Negative boost in `bq` (e.g., `^-10`) not supported |
| [BF](#bf)                                       | `bf` (boost functions) parameter not supported |
| [BOOST-MULTIPLICATIVE](#boost-multiplicative)   | eDisMax multiplicative `boost` parameter not supported |

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
| `delete` by query | ✅ Supported — `{"delete":{"query":"..."}}` → `_delete_by_query` (see [DELETE-BY-QUERY](#delete-by-query)) |
| `commit` | ❌ Not supported — fails fast |
| `optimize` / `rollback` | ❌ Not supported — fails fast |
| Mixed commands | ❌ Not supported — fails fast |
| Array/bulk operations | ❌ Not supported — fails fast |
| XML content type | ❌ Not supported — fails fast (JSON only) |

**Content type:**
Only `application/json` request bodies are supported. XML bodies
(`text/xml`, `application/xml`) cannot be parsed by the shim and will
fail with an error. Clients using XML format must switch to JSON.

---

## DELETE-BY-QUERY

**Feature:** `{"delete":{"query":"<solr-query>"}}` → `POST /_delete_by_query`

**Solr behaviour:**
Delete-by-query is synchronous — the response is returned after all matching
documents are deleted. The response is simply `{responseHeader:{status:0,QTime:N}}`
with no metadata about how many documents were deleted.

**OpenSearch behaviour:**
`_delete_by_query` is asynchronous by default. With `wait_for_completion=true`,
it behaves synchronously. Version conflicts during deletion are reported in the
response as `version_conflicts` count rather than aborting the operation.

**Current behaviour (applied automatically by the transformer):**

* `wait_for_completion=true` is always set for synchronous response.
* `commit=true` / `commitWithin` → `?refresh=true` (immediate visibility).
* Query translated via `translateQ` in **fail-fast mode** — no passthrough.
* Version conflicts or `deleted < total` → `responseHeader.status = 1`.

**Residual impact:**

* **No passthrough mode** — queries that the read path would pass through
  as `query_string` will fail on delete-by-query (intentional for safety).
* **Version conflicts** — concurrent updates can cause `version_conflicts`.
  OpenSearch continues deleting remaining documents (no abort). Reported as
  `status: 1`.

---

## BULK-PARTIAL-FAILURE

**Feature:** Batch `add` / `delete` request where some documents succeed and
others fail.

**Solr behaviour:**
Solr's default `/update` semantics are strict: if any document in a batch
fails validation or indexing, Solr aborts the whole request with an HTTP 4xx
error and none of the batch's documents become visible. Successfully-indexed
docs from earlier in the batch are rolled back.

An opt-in `TolerantUpdateProcessorFactory` (configured per-core in
`solrconfig.xml`) changes this: Solr continues past individual failures and
returns HTTP 200 with an `errors[]` array describing the per-document
failures. This opt-in mode is the closest match to OpenSearch's default
behaviour.

**OpenSearch behaviour:**
The `_bulk` API processes actions independently. A single failing item does
**not** abort the rest. The response is always HTTP 200 and each entry in
`items[]` carries its own `status` and optional `error` block. There is no
transactional rollback: successfully-indexed documents from earlier items
remain in the index even if later items fail.

**Cause:**
OpenSearch does not support ACID transactions across multiple documents.
Per-document writes are atomic, but the underlying Lucene segment model has
no concept of a multi-document rollback. There is no header, setting, or
API flag that enables all-or-nothing batch semantics — not in OpenSearch,
not in Elasticsearch, not in any fork of either.

**Current workaround (applied automatically by the transformer):**
The shim's `_bulk` response aggregator (`features/bulk/bulk-response.ts`)
walks `items[]` and produces a response shape that matches Solr's
**tolerant** update-processor output:

* All items succeeded → `{responseHeader:{status:0, QTime:<took>}}`, HTTP 200.
* Any item failed → `{responseHeader:{status:1, QTime:<took>}, errors:[{index,id,status,type,reason},…]}`,
  HTTP 200.

HTTP 200 is preserved even on partial failure because returning a 4xx would
imply *no* documents were indexed, causing well-behaved clients to retry the
entire batch and create duplicates of the already-indexed subset. The
tolerant-mode emulation lets clients inspect `errors[]` to retry only the
failed subset.

**Residual impact:**

* **SolrJ clients expecting strict semantics** — Applications that treat any
  response with `responseHeader.status != 0` as fatal, without inspecting
  `errors[]`, will behave differently against the shim than against a
  strict-mode Solr. Migration checklist for these clients: either inspect
  the `errors[]` array, or deploy Solr's `TolerantUpdateProcessorFactory`
  in the source Solr for an apples-to-apples comparison.
* **No rollback of partial successes** — Documents indexed before a
  failure remain in OpenSearch. This is inherent to OpenSearch's
  architecture and cannot be fixed by the translation layer. Applications
  that previously relied on Solr's "entire batch aborted, nothing visible"
  guarantee should treat batch requests as idempotent (e.g. by using the
  `_id` of each doc, which is how Solr batches behave anyway when IDs
  collide).

---

## NDJSON-INGEST-PARITY

**Feature:** Ingesting a request body that is already NDJSON (multiple
newline-delimited JSON objects).

**Solr behaviour:**
Not applicable — Solr clients do not send NDJSON to `/update`. Every Solr
update format (single-doc `add`, command-object with arrays, mixed commands,
delete-by-id, delete-by-query, commit) uses a single top-level JSON object
or a top-level JSON array. There is no Solr client that sends a body like
`{"a":1}\n{"b":2}\n{"c":3}\n`.

**OpenSearch behaviour:**
OpenSearch's `_bulk`, `_msearch`, and a few other endpoints accept NDJSON.
The traffic replayer supports this on both ingress (via
`NettyJsonBodyAccumulateHandler`, which stores the parsed list under
`payload.inlinedJsonSequenceBodies`) and egress (via
`NettyJsonBodySerializeHandler`).

**Cause:**
The shim's `HttpMessageUtil.parseJsonOrText` currently only parses the first
top-level JSON value it sees. An NDJSON body beginning with `{...}\n{...}`
would be partially parsed: the first object becomes `inlinedJsonBody` and
the remaining lines are silently discarded.

**Current workaround:**
Egress (emitting NDJSON from the shim to OpenSearch) **is** supported as of
PR 1 — transforms that produce `payload.inlinedJsonSequenceBodies` are
materialized as NDJSON on the wire with correct trailing-newline semantics.
Ingress (parsing NDJSON from a client) is not needed for the Solr→OpenSearch
translation use case.

**Residual impact:**
Only relevant if the shim is repurposed as a generic proxy that must accept
arbitrary OpenSearch `_bulk` traffic from clients. No Solr client exercises
this path, so the gap is invisible to the intended customer. A follow-up
change to the ingest side (mirroring `JsonAccumulator` semantics from the
replayer) would close this gap if needed.

---

## MM-AUTORELAX

**Feature:** eDisMax `mm.autoRelax` parameter

**Solr behaviour:**
When `mm.autoRelax=true` in eDisMax, Solr automatically relaxes the
`minimum_should_match` requirement if a query clause is removed by stopword
filtering in some but not all `qf` fields. This prevents zero-hit results
caused by uneven stopword removal across fields with different analyzers.

**OpenSearch behaviour:**
OpenSearch has no equivalent parameter. The `minimum_should_match` value on
a `bool` query is static — it cannot adapt based on per-field analysis
differences at query time.

**Cause:**
This is a Solr query-parser-level optimization that inspects per-field
analysis results during query construction. The shim operates at the HTTP
transform layer and has no visibility into field-level analyzer behaviour.

**Current status:**
Not supported. The `mm.autoRelax` parameter is rejected by validation with
a clear error message. Users relying on this behaviour should remove the
parameter and consider aligning stopword lists across `qf` fields, or
lowering the `mm` value to avoid zero-hit results from uneven stopword
removal.

**Reference:** https://solr.apache.org/guide/solr/latest/query-guide/edismax-query-parser.html

---

## MM-EDISMAX-OPERATOR-DEFAULT

**Feature:** eDisMax `mm` default when explicit operators are present

**Solr behaviour:**
In eDisMax, the default `mm` value is `0%` if the query contains any explicit
operator other than `AND` (such as `+`, `-`, `OR`, `NOT`), even when
`q.op=AND`. Only when `q.op=AND` and no explicit operators are present does
`mm` default to `100%`.

**OpenSearch behaviour:**
The shim's query parser handles `+`/`-`/`AND`/`OR`/`NOT` by placing terms
into `must`/`must_not`/`should` clauses. The explicit `mm` value is applied
to the `should` clauses via `minimum_should_match`. However, the nuanced
default logic — where the *presence* of explicit operators changes the `mm`
default from `100%` to `0%` — is not replicated.

**Cause:**
The shim does not track whether the original query string contained explicit
operators to conditionally adjust the default `mm`. The parser structurally
handles operators correctly, but the default `mm` inference differs.

**Current status:**
Partially supported. When `mm` is explicitly set, behaviour matches Solr.
When `mm` is omitted and the query mixes explicit operators with bare terms
under `q.op=AND`, the effective default may differ. Users relying on this
nuance should set `mm` explicitly.

**Reference:** https://solr.apache.org/guide/solr/latest/query-guide/edismax-query-parser.html

---

## MM-EDISMAX-MIXED-OPERATORS

**Feature:** eDisMax `mm` with mixed explicit and implicit operators

**Solr behaviour:**
In eDisMax, a query like `q=A AND B C D` treats `A` and `B` as mandatory
(from the explicit `AND`) and `C`, `D` as optional. Solr applies `mm` only
to the optional clauses (`C`, `D`). Internally, Solr produces a flat boolean
query: `must: [A, B], should: [C, D], minimum_should_match: <mm>`.

**OpenSearch behaviour:**
The shim's parser produces a different AST structure for this query. The
`A AND B` sub-expression becomes a nested `bool.must` inside the top-level
`should` array:
```json
{
  "bool": {
    "should": [
      { "bool": { "must": [A, B] } },
      C,
      D
    ],
    "minimum_should_match": "<mm>"
  }
}
```
This means `mm` applies to all 3 top-level `should` entries (the nested
`A AND B` group, `C`, and `D`) rather than just the optional `C` and `D`.

**Cause:**
The PEG grammar treats `A AND B` as a sub-expression at the same precedence
level as the implicit-OR siblings `C D`. The parser wraps `A AND B` into a
nested bool rather than hoisting the `and` clauses to the parent level. A
`boolRule.ts` TODO exists for AST flattening which would resolve this.

**Current status:**
Not supported. This only affects eDisMax queries that mix explicit operators
(`AND`, `OR`, `+`, `-`) with bare terms and use `mm`. Pure DisMax queries
(no explicit operators) are unaffected since all terms are flat `should`
clauses. Users experiencing incorrect results should restructure queries to
avoid mixing explicit operators with `mm`, or use `+`/`-` prefixes on all
terms to make intent explicit.

**Reference:** https://solr.apache.org/guide/solr/latest/query-guide/edismax-query-parser.html

---

## MM-STOPWORD-MULTIFIELD

**Feature:** `mm` interaction with per-field stopword removal in multi-field queries

**Solr behaviour:**
When searching across multiple `qf` fields with different query analyzers,
the number of optional clauses may differ between fields (e.g., a term is a
stopword in one field but not another). Solr applies `mm` to the **maximum**
number of optional clauses across all fields. A query clause removed as a
stopword in one field does not count as matched for that field, potentially
causing zero results if `mm=100%`.

**OpenSearch behaviour:**
OpenSearch applies `minimum_should_match` at the `bool` query level, not
per-field. When `qf` multi-field expansion produces `dis_max` queries per
term, the `minimum_should_match` operates on the outer bool across terms,
not on the inner per-field disjunctions. Stopword handling is delegated to
each field's analyzer independently.

**Cause:**
This is a fundamental architectural difference. Solr's DisMax/eDisMax parser
has visibility into per-field analysis results and adjusts clause counts
accordingly. OpenSearch's `bool` query treats `minimum_should_match` as a
flat count over its `should` array without per-field clause awareness.

**Current status:**
Not supported. In most practical cases the behaviour is equivalent. Edge
cases arise when fields have significantly different stopword lists and `mm`
is set to a high value (e.g., `100%`). Users experiencing zero-hit queries
due to this difference should consider aligning stopword lists across fields
or lowering the `mm` value.

**Reference:** https://solr.apache.org/guide/solr/latest/query-guide/dismax-query-parser.html#mm-minimum-should-match-parameter

---

## DISMAX

**Feature:** `defType=dismax` (DisMax query parser)

**Solr behaviour:**
The DisMax query parser provides a simplified query syntax with multi-field
search via `qf`, boost queries (`bq`), boost functions (`bf`), and minimum
match (`mm`). eDisMax (Extended DisMax) is a superset that adds support for
field:value syntax, boolean operators, and additional parameters.

**Current status:**
Not supported. Requests with `defType=dismax` are rejected by validation
with a clear error message. Use `defType=edismax` instead — eDisMax supports
all DisMax features plus additional capabilities.

**Workaround:**
Replace `defType=dismax` with `defType=edismax` in all queries. eDisMax is
backwards-compatible with DisMax for the supported parameter set (`q`, `qf`,
`bq`, `mm`, `pf`, `ps`, `tie`). The only behavioral difference is that
eDisMax supports explicit field:value syntax and boolean operators in `q`,
which DisMax treats as literal text.

**Reference:** https://solr.apache.org/guide/solr/latest/query-guide/dismax-query-parser.html

---

## BQ-NEG-BOOST

**Feature:** Negative boost values in `bq` (e.g., `bq=category:spam^-10`)

**Solr behaviour:**
Solr accepts negative boost values via Java's `Float.parseFloat()`. A negative
boost reduces the score contribution of matching documents additively — the
document is still returned, just ranked lower.

**Current status:**
Not supported. The shim rejects `bq` values containing `^-` with a fail-fast
error. OpenSearch does not support negative boost values — they produce
undefined scoring behavior. Without fail-fast, the PEG grammar would
reinterpret `category:spam^-10` as `-(category:spam^10)` (negation with
positive boost), which would **exclude** matching documents instead of
demoting them — a silent correctness bug.

**Workaround:**
Remove negative boosts from `bq` params. If score demotion is needed,
consider restructuring the query to use positive boosts on the preferred
documents instead.

---

## BF

**Feature:** `bf` (boost functions) parameter

**Solr behaviour:**
The `bf` parameter in DisMax/eDisMax specifies function expressions with
optional boosts that are added as scoring clauses. For example:
`bf=div(1,sum(1,price))^1.5` is shorthand for
`bq={!func}div(1,sum(1,price))^1.5`. Multiple `bf` values are supported.

**Current status:**
Not supported. The `bf` parameter is entirely function-based and requires
a dedicated transform to parse the function shorthand syntax and delegate
to the query engine. Sending `bf` will be rejected by validation as an
unsupported parameter.

**Reference:** https://solr.apache.org/guide/solr/latest/query-guide/dismax-query-parser.html#bf-boost-functions-parameter

---

## BOOST-MULTIPLICATIVE

**Feature:** eDisMax multiplicative `boost` parameter

**Solr behaviour:**
The eDisMax `boost` parameter applies a multiplicative boost to the entire
query score. Unlike `bq` (additive), `boost` acts as a scaling factor.
For example: `boost=popularity` multiplies each document's score by its
`popularity` field value. This is shorthand for wrapping the query in a
`{!boost}` QParser.

**Current status:**
Not supported. Multiplicative boosting requires OpenSearch's `function_score`
query with `script_score` or `field_value_factor`, which is architecturally
different from additive `bq`/`bf`. Sending `boost` will be rejected by
validation as an unsupported parameter.

**Reference:** https://solr.apache.org/guide/solr/latest/query-guide/edismax-query-parser.html#extended-dismax-parameters
