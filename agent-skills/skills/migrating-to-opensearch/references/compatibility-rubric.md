# Compatibility — draft from the embedded tables, verify the volatile rows

The skill owns the stable-core here: the **severity rubric**, the **always-flag list** (Step 3), and the **plugin-rename cheat-sheet** (Step 4) — draft the gap register straight from them, no retrieval. What is version-volatile is the **exact minor-version parity row** (which OpenSearch minor first reaches feature X, k-NN default engine per release, current plugin-support list, Lucene format per major): tag those `[verify]` and confirm them in the single Step 8 batched pass. **You MUST NOT ship a `[verify]`-tagged parity number unverified** — but you draft first and verify in one batch, not one fetch per row.

## Step 1 — Severity rubric (skill IP)

When you find an incompatibility, you MUST tag it:

- **BLOCKING** — cannot migrate without redesign (e.g., Elastic Fleet, X-Pack Graph, an X-Pack feature with no equivalent)
- **HIGH** — works after non-trivial rewrite (e.g., ILM→ISM, Watcher→Alerting, ES runtime fields)
- **MEDIUM** — auto-translatable but tested per-query (e.g., `dense_vector`→`knn_vector`, `flattened`→`flat_object`, BM25 score drift)
- **LOW** — drop-in or trivial change (terminology rename, plugin rename, default-operator difference)

When in doubt, you SHOULD flag at the higher severity because false positives are far less harmful than a missed BLOCKING.

## Step 2 — The canonical tables (draft now, `[verify]` the volatile rows in the batch)

> For Solr → OpenSearch compatibility, the schema, query, and analyzer mappings live in the `solr-*` references ([`solr-schema-migration.md`](solr-schema-migration.md), [`solr-query-behavior-edge-cases.md`](solr-query-behavior-edge-cases.md), [`solr-analysis.md`](solr-analysis.md), [`solr-legacy-features.md`](solr-legacy-features.md), [`solr-transformation-rules.md`](solr-transformation-rules.md)). You MUST tag Solr findings with the same BLOCKING / HIGH / MEDIUM / LOW vocabulary defined in Step 1 above.

### Elasticsearch → OpenSearch

Draft from the embedded *ES → OpenSearch always-flag table* in [`source-elasticsearch.md`](source-elasticsearch.md). Only the exact minor-version parity rows are `[verify]` — confirm these in the Step 8 batch:

- AWS-side managed-AOS plugin support — see [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section).
- OpenSearch-side Alerting / Anomaly Detection / ISM landing pages — see [`knowledge-retrieval.md`](knowledge-retrieval.md) (OpenSearch Project (engine docs) section).
- Vector / k-NN engine support — see [`knowledge-retrieval.md`](knowledge-retrieval.md) (OpenSearch Project (engine docs) section).
- Removal of types (ES history) — see [`knowledge-retrieval.md`](knowledge-retrieval.md) (Elasticsearch (Elastic) section).

### OpenSearch ↔ OpenSearch (upgrade)

Breaking changes per minor live in the documentation-website repo and the 3.0 announcement; the AWS upgrade-path doc is the canonical AOS reference. Retrieve all three via [`knowledge-retrieval.md`](knowledge-retrieval.md) (OpenSearch Project (engine docs) section and Amazon OpenSearch Service (managed) section).

## Step 3 — Standing items you MUST always flag (verify by retrieval)

These are stable enough to mention before retrieval, but you **MUST verify** the live doc says the same thing before quoting because parity claims drift per release.

### Always BLOCKING / HIGH

- ES Fleet / Elastic Agent / Endpoint Security / X-Pack Graph / Canvas / Lens — no OpenSearch equivalents
- ES Runtime fields — no equivalent (pre-compute at ingest or use scripted fields)
- ES 7.10-or-older indexes destined for OS 3.x — MUST reindex (Lucene 10 incompatibility)
- OS 1.3 / 2.x → OS 3.x without first reaching OS 2.19 — invalid upgrade path

### Always HIGH

- ILM → ISM (rewrite policies)
- Watcher → Alerting (rebuild monitors)
- ELSER → Neural Sparse (rewrite queries + retrain models)
- ES `_parent` (5.x) → `join` field (reindex required)
- NMSLIB k-NN indexes — NMSLIB engine deprecated in k-NN 2.19.0.0 `[verify]` / OpenSearch 3.0 `[verify]` (not yet removed). FAISS has been the default since k-NN 2.18.0.0 `[verify]`; migrate to FAISS or Lucene engine. (The deprecation itself is stable; the exact release numbers are version-volatile — confirm in the Step 8 batch.)

### Always MEDIUM

- `dense_vector` → `knn_vector` (MA auto-transforms but verify recall)
- `flattened` → `flat_object` (MA auto-transforms)
- Romanian analyzer change (cedilla→comma — reindex Romanian docs; per OpenSearch 3.0 breaking changes)
- ES SQL → OpenSearch SQL (mostly compatible; endpoint differs)

> **Skill IP**: a `LegacyBM25Similarity` → `BM25Similarity` default change is sometimes cited for OS 3.x. Whether it is listed in the current breaking-changes file is itself version-volatile — tag any BM25-default-change claim `[verify]` and confirm against the live breaking-changes file via [`knowledge-retrieval.md`](knowledge-retrieval.md) (OpenSearch Project (engine docs) section) before flagging it to a customer, especially if the workload uses custom similarity settings.

### Always LOW

- Plugin renames (Open Distro → OpenSearch)
- Terminology renames to inclusive forms (cluster manager, allow list, deny list)
- Endpoint paths (`/_plugin/kibana` → `/_dashboards`)
- Default role names (`kibana_user` → `opensearch_dashboards_user`)

## Step 4 — Plugin renames cheat-sheet

When the user pastes `_cat/plugins` against an Open-Distro cluster:

```
opendistro-alerting → opensearch-alerting
opendistro-anomaly-detection → opensearch-anomaly-detection
opendistro-knn → opensearch-knn
opendistro-index-management → opensearch-index-management (ISM)
opendistro-job-scheduler → opensearch-job-scheduler
opendistro-performance-analyzer → opensearch-performance-analyzer (3.x: Telemetry plugin)
opendistro-reports-scheduler → opensearch-reports-scheduler
opendistro-security → opensearch-security
opendistro-sql → opensearch-sql
```

You MUST confirm with retrieval if the user's plugin list includes anything else.

## Step 5 — Cite

Compatibility claims MUST cite (URLs catalogued in [`knowledge-retrieval.md`](knowledge-retrieval.md)):
- The specific OpenSearch project page for k-NN / engine claims — OpenSearch Project (engine docs) section
- The Elastic doc for type-removal / runtime-field claims — Elasticsearch (Elastic) section
- The AWS upgrade-path doc for upgrade-path claims — Amazon OpenSearch Service (managed) section

## Read next

- [`source-elasticsearch.md`](source-elasticsearch.md) — ES-specific compatibility
- [`source-opensearch.md`](source-opensearch.md) — OS upgrade compatibility
- [`nuggets.md`](nuggets.md) — 20 production gotchas (the skill's own anti-pattern list)
