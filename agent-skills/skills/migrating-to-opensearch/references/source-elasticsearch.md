# Source: Elasticsearch

You MUST read this when the source is Elasticsearch (any version 1.x–8.x). Most real-world OpenSearch migrations come from ES.

ES → OpenSearch fork point: **Elasticsearch 7.10.2 / Kibana 7.10.2**. The OpenSearch fork was announced on 12 April 2021 (AWS blog "Stepping up for a truly open source Elasticsearch") and OpenSearch 1.0 reached GA in July 2021. Citations: <https://opensearch.org/faq/> and <https://aws.amazon.com/blogs/opensource/introducing-opensearch/> — both confirm "OpenSearch derived from Elasticsearch 7.10.2".

## ES stable-core — draft directly, no retrieval

These facts do **not** drift per OpenSearch minor release. Draft the ES report straight from the tables below the same way the `solr-*` references let you draft a Solr report — they are the deterministic core that makes the ES fast path possible. Only the parity rows in Step 4 (which OpenSearch *minor* you land on) and the supported-plugin list are version-volatile and tagged `[verify]`.

### ES version family → recommended path (stable-core)

| ES major | Snapshot/Restore to AOS? | Recommended path | Why |
|---|---|---|---|
| 1.x / 2.x | No (too old) | **MA RFS** | Multi-major hop; `fielddata:true` OOM risk (nugget #8); mapping `_type`s |
| 5.x | No | **MA RFS** (C&R for zero-downtime) | Multi-major; `_parent` → `join` reindex (HIGH) |
| 6.x | No | **MA RFS** | Single-type transition; X-Pack/OSS split; multi-major to OS |
| 7.x ≤ 7.10.2 | **Yes** (pre-fork, bit-compatible) | **Snapshot/Restore** (cheapest) or MA RFS | Pre-fork; snapshots restore directly into OS 1.x/2.x |
| 7.11 – 7.17 | **No — ELv2/SSPL** | **MA RFS** (any volume) or `_reindex` from remote (small) | Post-fork; Snapshot/Restore NOT supported (nugget #21); flag legal review |
| 8.x | **No** | **MA RFS only** (into OS 2.x/3.x) | OS 1.x does not accept ES 8 sources |

### ES → OpenSearch always-flag table (stable-core; severity per [`compatibility-rubric.md`](compatibility-rubric.md))

| ES feature | Severity | OpenSearch situation / action |
|---|---|---|
| Fleet / Elastic Agent / Endpoint Security | BLOCKING | No equivalent; re-architect ingest on Data Prepper / OSI / Fluent Bit / OTel |
| X-Pack Graph / Canvas / Lens | BLOCKING | No equivalent; rebuild in Dashboards or drop |
| Runtime fields | HIGH | No equivalent; pre-compute via ingest pipeline or `scripted_field` |
| ILM | HIGH | → **ISM**; policy JSON does not import — rewrite (treat ISM ≠ ILM) |
| Watcher | HIGH | → **Alerting**; rebuild monitors |
| ELSER `text_expansion` | HIGH | → `neural_sparse`; rewrite queries + re-host model |
| ES 8 `retriever` / `rrf` | HIGH | → hybrid query + normalization-processor |
| `_parent` (5.x) | HIGH | → `join` field type; reindex required |
| `fielddata: true` (1.x/2.x text) | HIGH | Strip + add `.keyword` subfield or OOM on first agg (nugget #8) |
| ES `_type` (multi-type 6.x; `_doc` 7.x) | MEDIUM | Types removed in OS 1.0; flatten templates (nugget #9) |
| `dense_vector` | MEDIUM | → `knn_vector`; verify recall; pick engine per [`sizing-formulas.md`](sizing-formulas.md) |
| `flattened` | MEDIUM | → `flat_object` (MA auto-transforms) |
| ILM-driven indexes for OS 3.x from ES ≤ 7.10 | HIGH | Lucene 10 wall — reindex before reaching OS 3.x (nugget #7) |
| TransportClient | HIGH | Removed; migrate to REST client (`opensearch-java`) |
| SAML / OIDC / Kerberos / LDAP auth | MEDIUM | Supported via OpenSearch Security; re-map roles + role-mappings |

### ES field/mapping → OpenSearch (stable-core)

Most ES field types map 1:1 (`text`, `keyword`, `integer`, `long`, `float`, `double`, `boolean`, `date`, `ip`, `geo_point`, `geo_shape`, `object`, `nested` all carry over). The ones that need action:

| ES mapping construct | OpenSearch | Action |
|---|---|---|
| `dense_vector` | `knn_vector` | Set `method`/`engine`; verify recall (MEDIUM) |
| `flattened` | `flat_object` | MA auto-transforms (MEDIUM) |
| Runtime fields (`runtime` block) | — | Pre-compute at ingest; no runtime-mapping equivalent (HIGH) |
| `_type` / multi-type mappings | single mapping | Flatten; types removed OS 1.0 (nugget #9) |
| `fielddata: true` on text | `.keyword` subfield + `doc_values` | Strip `fielddata` (nugget #8) |
| Field aliases (`alias` type) | `alias` type | Carries over 1:1 |
| `_source: {enabled: false}` | — | Forces **MA RFS only** (nugget #22); re-enable `_source` on target |
| Painless scripts (stored/inline) | Painless | Mostly portable; re-test; inline scripts block Serverless NextGen |
| Index/component templates | Index/component templates | Carry over; strip `_type` and X-Pack-only settings |

## Step 1 — What needs live verification (the `[verify]` set)

Three things genuinely drift per OpenSearch minor release and MUST be tagged `[verify]` and confirmed in the Step 8 batch (not drafted from memory): the **X-Pack ↔ OpenSearch feature-parity *minor-version* rows**, the **supported-plugin list on managed AOS**, and the **current instance families + regional availability**. Everything in the stable-core tables above is drafted directly. Canonical entry points (upgrade-path doc, support schedule, snapshot tutorial, alarm-rename doc, supported-plugins) — see [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section). For Elastic's type-removal explanation, see [`knowledge-retrieval.md`](knowledge-retrieval.md) (Elasticsearch (Elastic) section).

## Step 2 — Discovery checklist (skill IP)

The standard intake (cluster facts, index inventory, workload, operations, auth, dashboards) lives in [`intake.md`](intake.md). ES-specific add-ons:

- **Distribution**: Default Elastic vs OSS, license (`GET _xpack/license`)
- **Operations**: ILM (`_ilm/policy`), Watcher (`_watcher/_query/watches`), CCR (`_remote/info`), SLM (`_slm/policy`), `_autoscaling/policy`
- **File-level**: `elasticsearch.yml`, `jvm.options`, `log4j2.properties`, `role_mappings.yml`, `roles.yml`
- **Workload extras**: search templates, runtime fields, scripted fields, ingest pipelines (`_ingest/pipeline?human`)

You SHOULD normalize the inputs into the fingerprint JSON shape documented in [`SKILL.md`](../SKILL.md) Step 2 so downstream steps reason against a single canonical artifact.

## Step 3 — Always-true ES invariants (stable-core — draft directly; confirm currency only if challenged)

- **ES 7.10.2** is the OpenSearch fork point (April 2021); OpenSearch 1.0 GA July 2021. ES ≤ 7.10.2 snapshots are bit-compatible with OpenSearch 1.x / 2.x — snapshot/restore is the cheapest path.
- **ES 7.11+** (including 7.17 and all 8.x) is post-fork (ELv2/SSPL). You MUST flag legal review. Snapshot/Restore is NOT safe — use MA RFS or `_reindex` from remote. See [`nuggets.md`](nuggets.md) #21.
- **ES 8.x sources** require Migration Assistant (OS 1.x does not accept ES 8 sources; OS 2.x/3.x via MA).

These match the version-family table above and are safe to state without retrieval; they are foundational fork facts, not per-release parity rows.

## Step 4 — X-Pack feature parity (draft from the always-flag table; `[verify]` only the minor-version rows)

The **always-flag set** (Fleet/Graph/Canvas/Lens = BLOCKING; ILM→ISM, Watcher→Alerting, runtime fields, ELSER→neural_sparse, ES 8 retriever/rrf, `_parent`→join = HIGH) is stable — it is embedded in the *ES → OpenSearch always-flag table* above and you draft the gap register straight from it. What is version-volatile is **which exact OpenSearch minor first reaches parity** for a given feature and the precise replacement-feature names per release; tag those `[verify]` and confirm in the Step 8 batch. You MUST NOT embed the full minor-by-minor parity matrix because that specific table drifts per release and stale minor numbers mislead the customer.

For specifics, retrieve the ISM and cross-cluster replication docs per [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section), plus the OpenSearch Project anomaly-detection and alerting landing pages — see [`knowledge-retrieval.md`](knowledge-retrieval.md) (OpenSearch Project (engine docs) section).

## Step 5 — Plugin renames (Open Distro → OpenSearch)

When the user pastes `_cat/plugins`, you MUST expect Open-Distro names: prefix `opendistro-` ↔ `opensearch-` for `alerting`, `anomaly-detection`, `knn`, `index-management` (ISM), `job-scheduler`, `performance-analyzer` (3.x: Telemetry plugin), `reports-scheduler`, `security`, `sql`. For the supported-plugin list on managed AOS, you MUST retrieve via [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) section).

## Step 6 — Cite (in the Step 8 batch)

In the batched verification pass, the ES report's Citations section MUST include (URLs catalogued in [`knowledge-retrieval.md`](knowledge-retrieval.md)): the AWS upgrade-path doc and snapshot-based migration tutorial (Amazon OpenSearch Service (managed) section), the specific feature page for any `[verify]`-tagged X-Pack-parity claim, Elastic's removal-of-types page if multi-type schema is involved (Elasticsearch (Elastic) section), and the Migration Assistant doc if MA is the recommendation (Migration Assistant section). Stable-core facts (fork point, version-family path table, always-flag severities) cite this reference file inline; you do not need a live fetch for them.

## Read next

- [`decision-trees.md`](decision-trees.md) — source detection, six-family migration-tool matrix, duration heuristics, go/no-go gates
- [`compatibility-rubric.md`](compatibility-rubric.md) — severity rubric + always-flag list
- [`nuggets.md`](nuggets.md) — production gotchas (Logstash OSS license, Curator, soft_deletes for CCR, alarm renames)
- [`knowledge-retrieval.md`](knowledge-retrieval.md) — retrieval recipes
