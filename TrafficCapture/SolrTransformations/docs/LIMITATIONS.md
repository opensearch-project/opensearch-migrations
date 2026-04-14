# Solr → OpenSearch Transformation Limitations

This document catalogues known limitations that arise from feature differences
between Apache Solr and OpenSearch. Each entry describes the gap, explains
the root cause, and provides a workaround where one exists.

---

## Limitation Index

| Shortcode | Summary |
|-----------|---------|
| [TERMS-OFFSET](#terms-offset) | Terms facet `offset` not natively supported in OpenSearch |
| [DATE-RANGE-GAP](#date-range-gap) | Multi-unit date range gaps approximated as fixed intervals |
| [CURSOR-UNIQUEKEY](#cursor-uniquekey) | Cursor pagination assumes `id` as Solr's uniqueKey field |
| [CURSOR-REPLAY](#cursor-replay) | Traffic replay with cursorMark not supported |
| [SOLRCONFIG-REPLAYER](#solrconfig-replayer) | Traffic replayer requires manual solrConfig in bindingsObject |

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
