# Solr legacy features — migration strategies

Solr features that **cannot be directly migrated** to OpenSearch and require a strategy change. The skill owns the **decision table per feature** — draft the strategy and the stable-core tool recommendations (Logstash / OSI / Spark / nested-or-join) directly from it. Only the current tool-version-specific configuration (Logstash JDBC connector settings, OSI blueprint syntax, exact nested/join field syntax) is version-volatile: tag those `[verify]` and confirm in the Step 8 batch rather than blocking the draft.

## Data Import Handler (DIH)

DIH was made an independent project as of Solr 9.0 (per the [Solr 9 upgrade notes](https://solr.apache.org/guide/solr/latest/upgrade-notes/major-changes-in-solr-9.html): "The Data Import Handler (DIH) is an independent project now; it is no longer a part of Solr"). OpenSearch has no built-in DIH equivalent — all data is pushed via the REST API (Bulk API). You MUST pick a replacement:

| Approach | When |
|---|---|
| **Custom script** (Python / Java / Go) calling Bulk API | Small / one-time pulls; full control |
| **Logstash JDBC input + OpenSearch output** | Existing Logstash footprint; periodic JDBC polls |
| **Apache Kafka + Kafka Connect** (JDBC Source + OpenSearch Sink) | Streaming / CDC at scale |
| **AWS Glue / Apache Spark** | Large-scale batch backfill |
| **OpenSearch Ingestion (OSI)** | AWS-managed Data Prepper pipeline; preferred on AOS |

Delta-import equivalents: CDC (Debezium), `last_modified` polling in the ETL, or DB triggers.

For Bulk API authentication on Amazon OpenSearch Service, you MUST use **IAM SigV4** with short-lived credentials from an IAM role (managed: service `es`; Serverless NextGen: service `aoss`). You MUST NOT hardcode credentials because hardcoded keys leak into source control and bypass rotation. The `opensearch-py` + `requests-aws4auth` pattern is the canonical client. Retrieve the current request-signing example via [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section).

## BlockJoin (nested / parent-child)

Solr's `BlockJoin` indexes parent and child docs as a single block (`_childDocuments_`). You MUST choose from three migration options in OpenSearch:

| Option | Use when | Trade-off |
|---|---|---|
| **`nested` type** | Children change rarely; queried together | Updating one child re-indexes the parent |
| **`join` field (parent/child)** | Children change frequently; high cardinality | Slower queries; routing required; no cross-shard join |
| **Denormalize** (flatten into one doc) | Child data is read-only or rarely changes | Storage / write amplification |

For exact mapping syntax (`nested`, `join` with `relations`, `has_child`, `has_parent`), retrieve the OpenSearch nested / parent-join field reference per [`knowledge-retrieval.md`](knowledge-retrieval.md) (OpenSearch Project (engine docs) section).

## Function queries → `function_score`

Solr `bf`, `_val_`, `{!func}`, `boost` map to OpenSearch's `function_score` query:

```json
{ "query": { "function_score": {
  "query": {"match_all": {}},
  "functions": [],
  "score_mode": "sum",
  "boost_mode": "multiply"
} } }
```

Function mapping highlights (full table on the OpenSearch `function_score` page — see [`knowledge-retrieval.md`](knowledge-retrieval.md) (OpenSearch Project (engine docs) section)):

| Solr | OpenSearch |
|---|---|
| `recip(ms(NOW,date),...)` | `gauss` (or `exp` / `linear`) decay on the date field — preferred |
| `log(field)` / `sqrt(field)` / `pow(field,exp)` | `field_value_factor` with `modifier`: `log`, `log1p`, `sqrt`, `square`, … |
| `recip(x,m,a,b)` | `script_score` painless: `params.a / (params.m * x + params.b)` |
| `ord(field)` / `rord(field)` | No direct equivalent — approximate with `rank_feature` |

You SHOULD prefer decay (`gauss`/`exp`/`linear`) and `field_value_factor` over Painless `script_score` whenever possible because they are faster and serializable for OpenSearch Dashboards.

## Response writers: Velocity and XSLT

Per the [Solr 9 upgrade notes](https://solr.apache.org/guide/solr/latest/upgrade-notes/major-changes-in-solr-9.html):

- **Velocity (`VelocityResponseWriter`)** — "`VelocityResponseWriter` is an independent project now; it is no longer a part of Solr." All previously included `/browse` and `wt=velocity` examples were removed.
- **XSLT (`XSLTResponseWriter`)** — moved to the optional `scripting` module in Solr 9: "`XSLTResponseWriter` … moved to the `scripting` Module instead of shipping as part of Solr core. This module needs to be enabled explicitly."

OpenSearch has no equivalent server-side templating response writer. You MUST move presentation logic out of the search engine and into the application layer (or OpenSearch Dashboards). Typical migration patterns: render JSON in the client; move HTML rendering to a service tier; replace XSLT-based feeds with application code that consumes the OpenSearch JSON response.
