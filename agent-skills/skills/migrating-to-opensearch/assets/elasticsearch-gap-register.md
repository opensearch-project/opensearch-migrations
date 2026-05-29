# Elasticsearch Gap Register Skeleton

Use this table verbatim in section **6. Feature Gap Register** of [report-template](report-template.md) (or the ES rendering in [elasticsearch-report-template](elasticsearch-report-template.md)) for Elasticsearch **and** OpenSearch-upgrade sources. Add one row per incompatibility surfaced by Steps 3, 4, and 6 of the workflow. Severity values come from the canonical rubric in [compatibility-rubric](../references/compatibility-rubric.md) — **BLOCKING / HIGH / MEDIUM / LOW**.

Draft the rows directly from the embedded *ES → OpenSearch always-flag table* in [source-elasticsearch.md](../references/source-elasticsearch.md) (stable-core, no retrieval). Tag only the version-volatile "which OpenSearch minor reaches parity" detail `[verify]` and resolve it in the Step 8 batch.

| # | Feature | Elasticsearch behavior | OpenSearch alternative | Severity | Effort | Owner action |
|---|---------|------------------------|------------------------|----------|--------|--------------|
| 1 | _e.g. ILM_ | Index Lifecycle Management policies (`_ilm/policy`) | **ISM** (`_plugins/_ism/policies`) — policy JSON does NOT import | HIGH | M | Rewrite policies as ISM; re-attach to indexes per [source-elasticsearch](../references/source-elasticsearch.md). |
| 2 | _e.g. Watcher_ | X-Pack Watcher rules | OpenSearch **Alerting** monitors | HIGH | M | Rebuild monitors + destinations; smoke-test triggers. |
| 3 | _e.g. Runtime fields_ | Schema-on-read `runtime` mappings | No equivalent | HIGH | M | Pre-compute via ingest pipeline or `scripted_field`; reindex. |
| 4 | _e.g. Fleet / Elastic Agent_ | X-Pack ingest + endpoint management | No equivalent | BLOCKING | L | Re-architect ingest on Data Prepper / OSI / Fluent Bit / OTel. |
| 5 | _e.g. ELSER `text_expansion`_ | Elastic learned sparse retrieval | `neural_sparse` query | HIGH | L | Re-host a sparse model; rewrite queries; validate relevance. |
| 6 | _e.g. `dense_vector`_ | Dense vector field + kNN | `knn_vector` (engine per sizing-formulas) | MEDIUM | M | Pick engine; reindex; verify recall vs source. |
| 7 | _e.g. `_type` / multi-type mappings_ | ES 6.x multi-type or 7.x `_doc` placeholder | Types removed in OS 1.0 | MEDIUM | S | Flatten templates (nugget #9); MA metadata transformer handles it. |
| 8 | _e.g. `fielddata: true` (ES 1.x/2.x text)_ | In-memory fielddata for sort/agg | `.keyword` subfield + `doc_values` | HIGH | S | Strip `fielddata`; add subfield (nugget #8) or OOM on first agg. |
| 9 | _e.g. `_source: {enabled:false}`_ | `_source` not stored on the index | Forces **MA RFS only** | HIGH | S | Use MA RFS (nugget #22); re-enable `_source` on target. |
| 10 | _e.g. ES 8 `retriever` / `rrf`_ | Native reciprocal-rank fusion | Hybrid query + normalization-processor | HIGH | M | Rebuild as hybrid search pipeline; benchmark ranking. |

## Severity vocabulary

Severities MUST come from the canonical rubric in [compatibility-rubric.md](../references/compatibility-rubric.md) §1 — BLOCKING / HIGH / MEDIUM / LOW only.

## Effort tiers

- **S** — under 1 engineer-week.
- **M** — 1–4 engineer-weeks.
- **L** — over 4 engineer-weeks; usually requires design review.

## Constraints

- You MUST keep the column order exactly as shown because downstream tooling parses the table by column position. (Same locked shape as [solr-gap-register.md](solr-gap-register.md) — only the "behavior" column label changes from Solr to Elasticsearch.)
- You MUST NOT remove a row to "simplify" the report because every flagged incompatibility belongs in the register, even LOW-level, and removed rows hide risk.
- You MUST use the BLOCKING / HIGH / MEDIUM / LOW vocabulary in the Severity column. You MUST NOT use the legacy Breaking / Warning / Info labels.
- You MUST link every row's "OpenSearch alternative" cell to the relevant reference file when one exists.
- For OpenSearch-upgrade sources, draw the rows from [source-opensearch.md](../references/source-opensearch.md) breaking-changes (e.g. JDK 21 minimum, NMSLIB deprecation, removed k-NN index settings, WLM rename) instead of the X-Pack rows.
