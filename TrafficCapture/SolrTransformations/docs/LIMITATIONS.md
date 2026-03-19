# Solr → OpenSearch Transformation Limitations

This document catalogues known limitations that arise from feature differences
between Apache Solr and OpenSearch. Each entry describes the gap, explains
the root cause, and provides a workaround where one exists.

---

## Limitation Index

| Shortcode | Summary |
|-----------|---------|
| [TERMS-OFFSET](#terms-offset) | Terms facet `offset` not natively supported in OpenSearch |

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
