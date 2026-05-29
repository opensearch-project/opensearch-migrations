# Source: Elasticsearch

You MUST read this when the source is Elasticsearch (any version 1.x–8.x). Most real-world OpenSearch migrations come from ES.

ES → OpenSearch fork point: **Elasticsearch 7.10.2 / Kibana 7.10.2**. The OpenSearch fork was announced on 12 April 2021 (AWS blog "Stepping up for a truly open source Elasticsearch") and OpenSearch 1.0 reached GA in July 2021. Citations: <https://opensearch.org/faq/> and <https://aws.amazon.com/blogs/opensource/introducing-opensearch/> — both confirm "OpenSearch derived from Elasticsearch 7.10.2".

## Step 1 — Retrieve current AWS guidance

The version matrix, plugin list, X-Pack parity table, and EOL schedule all drift. You MUST retrieve each assessment per the recipe in [`knowledge-retrieval.md`](knowledge-retrieval.md). Canonical entry points (upgrade-path doc, support schedule, snapshot tutorial, alarm-rename doc, supported-plugins) — see [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section). For Elastic's type-removal explanation, see [`knowledge-retrieval.md`](knowledge-retrieval.md) (Elasticsearch (Elastic) section).

## Step 2 — Discovery checklist (skill IP)

The standard intake (cluster facts, index inventory, workload, operations, auth, dashboards) lives in [`intake.md`](intake.md). ES-specific add-ons:

- **Distribution**: Default Elastic vs OSS, license (`GET _xpack/license`)
- **Operations**: ILM (`_ilm/policy`), Watcher (`_watcher/_query/watches`), CCR (`_remote/info`), SLM (`_slm/policy`), `_autoscaling/policy`
- **File-level**: `elasticsearch.yml`, `jvm.options`, `log4j2.properties`, `role_mappings.yml`, `roles.yml`
- **Workload extras**: search templates, runtime fields, scripted fields, ingest pipelines (`_ingest/pipeline?human`)

You SHOULD normalize the inputs into the fingerprint JSON shape documented in [`SKILL.md`](../SKILL.md) Step 2 so downstream steps reason against a single canonical artifact.

## Step 3 — Always-true ES invariants (you MUST verify with retrieval each time)

- **ES 7.10.2** is the OpenSearch fork point (April 2021); OpenSearch 1.0 GA July 2021. ES ≤ 7.10.2 snapshots are bit-compatible with OpenSearch 1.x / 2.x — snapshot/restore is the cheapest path.
- **ES 7.11+** (including 7.17 and all 8.x) is post-fork (ELv2/SSPL). You MUST flag legal review. Snapshot/Restore is NOT safe — use MA RFS or `_reindex` from remote. See [`nuggets.md`](nuggets.md) #21.
- **ES 8.x sources** require Migration Assistant (OS 1.x does not accept ES 8 sources; OS 2.x/3.x via MA).

## Step 4 — X-Pack feature parity (you MUST retrieve, MUST NOT embed)

The parity table changes per OpenSearch minor release. You MUST NOT embed it because the table drifts per release and stale numbers mislead the customer. Always-flag items in any ES report:

- ILM → ISM (rewrite policies)
- Watcher → Alerting (rebuild monitors)
- Runtime fields → no direct equivalent (pre-compute via ingest pipeline or scripted_field)
- ELSER `text_expansion` → `neural_sparse`
- ES 8 `retriever` / `rrf` → hybrid query + normalization-processor
- Fleet / Elastic Agent → Data Prepper / OSI / Fluent Bit / OTel

For specifics, retrieve the ISM and cross-cluster replication docs per [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section), plus the OpenSearch Project anomaly-detection and alerting landing pages — see [`knowledge-retrieval.md`](knowledge-retrieval.md) (OpenSearch Project (engine docs) section).

## Step 5 — Plugin renames (Open Distro → OpenSearch)

When the user pastes `_cat/plugins`, you MUST expect Open-Distro names: prefix `opendistro-` ↔ `opensearch-` for `alerting`, `anomaly-detection`, `knn`, `index-management` (ISM), `job-scheduler`, `performance-analyzer` (3.x: Telemetry plugin), `reports-scheduler`, `security`, `sql`. For the supported-plugin list on managed AOS, you MUST retrieve via [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section).

## Step 6 — Cite

Every ES report MUST cite (URLs catalogued in [`knowledge-retrieval.md`](knowledge-retrieval.md)): the AWS upgrade-path doc and snapshot-based migration tutorial (Amazon OpenSearch Service (managed) section), the specific feature page for any X-Pack-equivalent claim, Elastic's removal-of-types page if multi-type schema is involved (Elasticsearch (Elastic) section), and the Migration Assistant doc if MA is the recommendation (Migration Assistant section).

## Read next

- [`decision-trees.md`](decision-trees.md) — source detection, six-family migration-tool matrix, duration heuristics, go/no-go gates
- [`compatibility-rubric.md`](compatibility-rubric.md) — severity rubric + always-flag list
- [`nuggets.md`](nuggets.md) — production gotchas (Logstash OSS license, Curator, soft_deletes for CCR, alarm renames)
- [`knowledge-retrieval.md`](knowledge-retrieval.md) — retrieval recipes
