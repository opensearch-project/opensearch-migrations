# Decision trees

Navigational core of the assessment. You MUST read this any time the workflow says "select" or "choose". You MUST default to **MANAGED** for target shape and **MIGRATION ASSISTANT** for path when ambiguous.

Personas (definitions and intake checklists in [`intake.md`](intake.md)): SRE → `TECHNICAL_DEEP_DIVE.md`; DOP → `TECHNICAL_DEEP_DIVE.md`; BSH → `EXECUTIVE_SUMMARY.md`. For mixed personas you MUST pick the most technical voice and add a one-page exec header.

---

## Source-engine detection

| User says… | Route |
|---|---|
| "Solr" / SolrCloud / pastes `<requestHandler>` XML | **SOLR** — use `solr-*` references; schema/query translation in scope; MA handles data movement via Migration Assistant Solr backfill (RFS / Reindex-from-Snapshot). **Backfill only — Migration Assistant does NOT support live traffic capture & replay for Solr sources** (per the AWS Solutions guide supported-paths matrix; verify per [`knowledge-retrieval.md`](knowledge-retrieval.md), Migration Assistant section). |
| "Elasticsearch" / "ELK" / pastes `_cat/indices` | **ELASTICSEARCH** — version-family branches: 1/2/5 (legacy, multi-step), 6 (single-type, X-Pack split), 7 ≤ 7.10.2 (fork point, cleanest path), 7.11+ (ELv2/SSPL — flag legal review), 8 (MA-only into OS 2.x/3.x) |
| "OpenSearch" | **OPENSEARCH** — 1.x and 2.x (< 2.19) must reach 2.19 before 3.x; 2.19 → 3.x direct; 3.x upgrade-only |
| Algolia / Vespa / Typesense / Meilisearch / Sphinx | OpenSearch can replace these but path is bespoke (custom Spark/EMR ETL or 3rd-party tool). **You MUST stop the workflow.** |

---

## Target shape (Managed Domain vs. Serverless NextGen)

You MUST run this after the Phase 1 fingerprint. You MUST default to **MANAGED** when ambiguous.

**Terminology**: "Serverless NextGen" in this SOP refers exclusively to **Amazon OpenSearch Serverless NextGen collections**. The original Serverless NextGen collection model is being superseded; you MUST NOT recommend a non-NextGen collection without an explicit retrieval that confirms it's still the right answer for the customer's workload (NextGen vs Classic signals are covered by the companion `aoss-nextgen` skill — load it whenever target shape lands on Serverless NextGen). Capability + sizing + supported-engines facts for NextGen drift fast — draft the NextGen recommendation from this tree, tag every specific NextGen feature / limit / supported-source row `[verify]`, and confirm them in the Step 8 batched pass via [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Serverless NextGen section) before delivery.

**MANAGED ONLY** if any: SIEM / Security Analytics, custom plugins, Lucene k-NN or FAISS IVF, CCR or CCS required, UltraWarm / Cold tiering required, geospatial beyond OS 2.1 parity, manual snapshots required, inline scripts, T-class burstable instances, user-tunable sharding required, predictable steady-state with RI savings opportunity, very small clusters (≤2 OCU steady — managed is cheaper).

**SERVERLESS NEXTGEN OK** when: workload fits a NextGen-supported collection type (verify the current supported-types matrix per [`knowledge-retrieval.md`](knowledge-retrieval.md) — typical: full-text search / time-series logs / vector); bursty traffic; no custom plugins; no CCR/CCS; no manual snapshots; no inline scripts. Bursty (10×+ swings) or zero-ops preference favors Serverless NextGen. Tiebreaker: workload is a NextGen first-class fit AND has bursty traffic → Serverless NextGen; otherwise you MUST default to MANAGED with a note "re-evaluate Serverless NextGen after stable traffic and after retrieving the latest NextGen capability matrix."

### Standing topology / JVM / OCU defaults

Standing operational defaults (cluster manager quorum, JVM heap caps, pressure thresholds, refresh / watermarks, UltraWarm threshold, Serverless NextGen OCU rule of thumb, and instance-class preferences) live in [`sizing-formulas.md`](sizing-formulas.md) and are stable-core — draft directly from them. Tag only the version-volatile values (current instance families, the live NextGen OCU caps) `[verify]` and confirm them in the Step 8 batch per [`knowledge-retrieval.md`](knowledge-retrieval.md) (Amazon OpenSearch Service (managed) + Amazon OpenSearch Serverless NextGen sections). Do not block the draft on these.

---

## Migration tool decision matrix

The six families:

1. **Migration Assistant (MA)** — RFS for backfill + Capture & Replay for live cutover
2. **Snapshot / Restore** via S3
3. **OpenSearch Ingestion (OSI)** — managed Data Prepper pipelines
4. **`_reindex` from remote** — native API
5. **EMR / Spark / Logstash / custom** — for complex transforms
6. **In-place blue/green upgrade** — for AWS-managed OpenSearch upgrading minor/major

### Source-engine support — always-true facts (do not invert)

| Tool | Apache Solr / SolrCloud | Elasticsearch 1.x–8.x | OpenSearch (in-place) |
|---|---|---|---|
| **Migration Assistant** | **YES — backfill only** via Migration Assistant Solr backfill (RFS / Reindex-from-Snapshot). **You MUST NOT tell a customer "MA is Elasticsearch-only" — Solr 6.x–9.x is a first-class MA backfill source per the AWS Solutions supported-paths matrix.** Live traffic Capture & Replay is **NOT** supported for Solr sources. | YES — RFS for backfill + Capture & Replay for live cutover (C&R is documented for ES 5.x–7.x and OpenSearch sources; verify the live support matrix per [`knowledge-retrieval.md`](knowledge-retrieval.md), Migration Assistant section) | n/a — use blue/green upgrade |
| **Snapshot / Restore** | NO | YES (single-major hop) | YES |
| **OSI** | NO | YES (ES 7.x / OS 2.x sources) | YES |

The table above is stable-core — draft the path recommendation from it directly. Tag exact per-release caps (current supported-source minor versions, the GovCloud RFS shard-size cap) `[verify]` and confirm against the canonical Migration Assistant sources (AWS Solutions doc, project doc, upstream repo README) in the Step 8 batch per [`knowledge-retrieval.md`](knowledge-retrieval.md) (Migration Assistant section).

### Path selection rules

| Dimension | Rule |
|---|---|
| **Source engine** | **Solr → default to Migration Assistant Solr backfill (RFS) at any data volume.** RFS reads the source's Lucene segments directly and is the **ONLY** tool that can recover Solr fields configured `stored="false"` in `schema.xml` / `managed-schema` — because non-stored field values exist only in the inverted index, not in any record the source can re-emit via `/select`, `/export`, or SolrJ `cursorMark`. Every Solr-source assessment MUST audit `<field>` and `<dynamicField>` definitions for `stored="false"` (and the index-time `copyField` graph) before recommending a non-RFS path. If any meaningful field is `stored="false"`, RFS is the only path that doesn't lose data. An export-then-bulk path is acceptable ONLY when (a) every needed field is `stored="true"` AND (b) the customer can trivially re-emit the source data from a system of record (CMS, RDBMS, message queue) — and you MUST flag this trade-off explicitly in the report. C&R is not available for Solr (backfill only). ES 1/2/5/6 → MA RFS (only practical multi-major path). ES 7 ≤ 7.10.2 → Snapshot OK (pre-fork). **ES ≥ 7.11 → MA RFS or `_reindex` from remote ONLY** because ES 7.11+ runs under ELv2/SSPL and Snapshot/Restore from ES ≥ 7.11 to OpenSearch is NOT a supported migration path on Amazon OpenSearch Service (see [`nuggets.md`](nuggets.md) #21 for the operational guidance). ES 8 → MA only. OS in-place → SNAPSHOT or in-place blue/green. OS self-managed → Serverless NextGen → MA preferred (verify NextGen-supported source matrix per [`knowledge-retrieval.md`](knowledge-retrieval.md)). |
| **Field-store status (`_source` for ES/OS, `stored=` for Solr)** | You MUST verify field-store status on every source index before recommending a path. **Elasticsearch / OpenSearch**: `_source: enabled` (default) → all paths above are open. `_source: false` → Migration Assistant RFS is the **ONLY** supported path; see [`nuggets.md`](nuggets.md) #22 for the mechanism + detection. **Solr**: every Solr field has its own `stored` attribute (default depends on field type / schema version). Any meaningful field with `stored="false"` → MA RFS is the **ONLY** path that can extract its values, because RFS reads the segment files directly (the same way ES `_source: false` is handled). Audit `<field>` and `<dynamicField>` definitions in `schema.xml` / `managed-schema` and the index-time `copyField` graph. |
| **Cutover** | Zero-downtime → **MA C&R** (only AWS-blessed option). Maintenance window OK → SNAPSHOT or REINDEX. Continuous replication → MA C&R or CCR (both ends OS+Service). |
| **Data volume** | < 100 GB → REINDEX. 100 GB – 10 TB → MA RFS or SNAPSHOT. 10–100 TB → MA RFS (scales with shard count). > 100 TB → MA RFS with shard-count parallelism. |
| **Transformations** | None / minor (`dense_vector`→`knn_vector`, `flattened`→`flat_object`) → MA built-ins. Field rewrites / PII redaction / joins → OSI (managed) or EMR/Spark. CDC live tail → Logstash with `tracking_field` or MA C&R. |
| **Source location** | On-prem / non-AWS → MA (capture proxy). AWS-resident → any path; SNAPSHOT cheapest. |
| **Cross-cloud / cross-account** | MA or OSI with SigV4 auth. |
| **Throughput cap** | Live writes > 4 TB/day → OSI fan-out or staged migration; MA C&R recommended ≤ 4 TB/day. |
| **GovCloud** | **Skill IP**: practitioners report a hard MA RFS shard-size cap in GovCloud — you MUST verify the current cap against the live Migration Assistant docs per [`knowledge-retrieval.md`](knowledge-retrieval.md) (Migration Assistant section) before quoting a number, and re-shard the source if any shard exceeds the verified cap. |

### Quick-reference path table

| Scenario | Path | Notes |
|---|---|---|
| ES 7.10.2 → OS 1.x / 2.x, 5 TB, planned downtime | Snapshot/Restore | Pre-fork; S3 transfer + S3 storage; lowest infra cost |
| **ES 7.11+ (e.g. 7.17) → OS** | **MA RFS or `_reindex` from remote** | **Snapshot is NOT safe post-fork** — use MA RFS (any volume) or `_reindex` from remote for small datasets |
| ES 5.x → OS 2.x, 2 TB, multi-major | MA RFS | Only practical path |
| ES 7.x → OS 2.x zero-downtime, 5 TB historical + 20 MBps live | MA C&R + RFS | Capture proxy on source |
| OS 1.3 → OS 3.x | Two-step in-place via 2.19 | Free for managed customers |
| OS 1.7 → OS 3.x | Two-step in-place (1.7 → 2.19 → 3.x) | Free for managed customers |
| ES 7.x, 50 GB | `_reindex` from remote | No extra infra |

You MUST plug volume + region inputs into the AWS Pricing Calculator at <https://calculator.aws> for cost.

---

## Storage tier (Managed)

| Index access pattern | Tier strategy |
|---|---|
| All reads recent (≤ 30 days) | Hot only on EBS gp3 |
| 30–90 days warm reads | Hot + UltraWarm (≥ 2.5 TiB cold-tier breakeven) |
| > 90 days, rare audits | Hot + UltraWarm + Cold |
| All reads recent + bursty | Consider Serverless NextGen time-series collection (verify current support per [`knowledge-retrieval.md`](knowledge-retrieval.md)) |

Tier-transition automation lives in ISM. For ES customers migrating, you MUST treat **ISM as distinct from ILM** because ILM JSON does not import as ISM and the policy must be rebuilt — see [`source-elasticsearch.md`](source-elasticsearch.md) and [`readiness-rubric.md`](readiness-rubric.md).

---

## k-NN engine

| Vector count | Recommendation |
|---|---|
| < 10M, want filtering during search | Lucene / HNSW |
| 10M – tens of billions, RAM abundant | FAISS / HNSW |
| 10M – tens of billions, RAM tight | FAISS / HNSW + PQ or fp16, or `mode=on_disk` |
| Need fastest indexing, accept lower recall | FAISS / IVF (+ optional PQ) |
| Migrating from NMSLIB | FAISS (default since k-NN 2.18.0.0; NMSLIB deprecated in k-NN 2.19.0.0 / OpenSearch 3.0) |
| Targeting Serverless NextGen | FAISS / HNSW only — verify current k-NN engine support per [`knowledge-retrieval.md`](knowledge-retrieval.md) |

Memory formulas live in [`sizing-formulas.md`](sizing-formulas.md).

---

## Reserved Instance term

| Pattern | RI choice |
|---|---|
| Predictable steady-state ≥ 1 year | 1-yr Reserved |
| Predictable steady-state ≥ 3 years | 3-yr Reserved (best discount) |
| Traffic shape uncertain | On-Demand for 90 days, re-evaluate |
| Bursty | Serverless NextGen instead of RI |

Upfront: All Upfront = max discount; Partial Upfront = mixed; No Upfront = opex-only. You MUST apply discounts in <https://calculator.aws>. You MUST NOT estimate dollar discounts in this skill because account-specific RI/Savings-Plan/EDP math is unverifiable by an LLM.

---

## Migration duration heuristics (skill IP)

Operational reality, not in any single doc. You MUST validate against an OpenSearch Benchmark (OSB) run before quoting.

- **In-place blue/green (AOS)**: 15 min – several hours
- **Snapshot/restore**: ~ source_data ÷ S3 throughput (typical 100–500 MB/s/node)
- **CCR-bootstrapped (multi-region)**: ≈ source / inter-region throughput
- **MA RFS**: scales with shard count; plan 50–70 % bulk capacity in low-impact mode
- **Rolling upgrade**: N nodes × (drain + restart) ≈ 5–30 min/node

---

## Migration "go / no-go" gates

| Gate | Threshold | If failing |
|---|---|---|
| Readiness score | ≥ 60 | Spike PoC for the lowest-scoring dimension |
| BLOCKING incompatibilities | 0 | Each MUST be resolved or accepted with explicit owner |
| Snapshot/restore validation | 100 % doc-count parity within 0.1 % | Investigate; restart |
| Top-N query parity | ≥ 95 % overlap (Jaccard) | Re-run query translation; tune analyzers |
| Performance | p99 latency ≤ 1.2× source | Tune sizing, refresh interval, replica count |
| Auth / RBAC | All roles round-trip tested | Audit role mappings |
| Dashboards | All saved objects render | Re-import; check version compatibility |
| Sizing | Within 10 % of customer's Pricing Calculator forecast | Right-size, consider RI / Serverless NextGen |

You MUST NOT declare a migration done without these gates passing because shipping with unresolved gates exposes the customer to silent data loss, query divergence, and rollback events.
