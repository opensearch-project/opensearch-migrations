---
name: migrating-to-opensearch
description: Assesses migrations to Amazon OpenSearch Service or Serverless NextGen from Apache Solr (any version), Elasticsearch (any version), or OpenSearch (in-place 1.x→2.x→3.x upgrades). Triggers on conversational phrasings — "should we migrate", "moving off Solr", "ES to OpenSearch", "Solr → OpenSearch path", "upgrade to OS 3.x", "Lucene 10 reindex", "ILM to ISM", "k-NN engine swap NMSLIB → FAISS", "snapshot vs Migration Assistant", "Capture-and-Replay vs dual-write", "Serverless NextGen or Managed", "sizing for OpenSearch" — and on artifact pastes (schema.xml, solrconfig.xml, eDisMax, _cat/indices, X-Pack ILM/Watcher/ELSER/runtime fields). Outputs schema mapping, query translation, target shape recommendation, ranked migration-path scoring across six tool families, sizing guidance (instances, shards, JVM heap, OCUs), a 0-100 readiness score, and an authoritative citation list. Does not move data (use Migration Assistant for Amazon OpenSearch Service) or estimate dollar cost (use AWS Pricing Calculator).
owner_team: OpenSearch-Migrations
owner_cti: '"AWS OpenSearch"/Migrations/Primary'
stages: [preprod]
version: 1
metadata:
  service: [opensearch, opensearch-serverless, opensearch-ingestion]
  task: [assess, migrate, plan, estimate-cost]
  persona: [architect, developer, devops]
  workload: [search, logs, vector]
---

# Migrating to OpenSearch

## Overview

This SOP produces a structured migration assessment for Apache Solr (6.x–9.x), Elasticsearch (1.x–8.x), or OpenSearch (in-place upgrades 1.x→2.x→3.x) workloads moving to Amazon OpenSearch Service (managed domain or Serverless NextGen). It is **analytical only**: it ranks six data-movement families — Migration Assistant for Amazon OpenSearch Service (RFS + Capture & Replay), snapshot/restore, OpenSearch Ingestion, reindex from remote, Logstash/EMR/Spark, in-place blue/green upgrade — and recommends whichever fits the workload.

Outputs: target-shape recommendation, ranked migration-path scoring, sizing guidance (instances / shards / JVM heap / OCUs), 0–100 readiness score, ≥ 5 authoritative citations.

The SOP is **retrieval-first**. The canonical recipe (topic → tool → URL, with browser/`curl`/AWS-CLI fallback when the AWS MCP server is not bound) lives in [`references/knowledge-retrieval.md`](references/knowledge-retrieval.md). Every other step in this SOP assumes that pattern and does not duplicate the fallback prose.

**Hard pre-condition for every step below.** You MUST call `aws___read_documentation` (or the documented fallback) BEFORE writing any version-specific claim — instance types, plugin support, X-Pack parity row, Migration Assistant capability, k-NN engine support, sizing limit. You MUST NOT assert from training-data memory because version-compatibility tables drift per release and a stale claim is the #1 failure mode this skill exists to prevent.

## Parameters

- **source_engine** (required): One of `solr`, `elasticsearch`, or `opensearch`. The source-search-engine family.
- **source_version** (required): Source major.minor (e.g. `8.11`, `7.10.2`, `1.7`). Drives compatibility scanning and migration-path scoring.
- **target_region** (required): AWS region for Amazon OpenSearch Service or Serverless NextGen (e.g. `us-east-1`, `eu-west-1`).
- **persona** (required): One of `SRE` (Search Relevance Engineer), `DOP` (DevOps / Platform Engineer), or `BSH` (Business Stakeholder). Drives report depth and emphasis. See [`references/intake.md`](references/intake.md).
- **discovery_inputs** (required): The customer-provided artifacts to assess against — `_cat`/`_cluster`/`_nodes` JSON for ES/OS, or `schema.xml` + `solrconfig.xml` + sample queries for Solr. The intake checklist in [`references/intake.md`](references/intake.md) lists the full set per persona.
- **downtime_tolerance** (optional, default: `short`): `none` | `short` | `long`. Drives migration-path scoring (zero-downtime → Capture & Replay; long window → snapshot/restore).
- **target_shape_hint** (optional): `managed` | `serverless` if the customer has a preference; otherwise the SOP scores both per [`references/decision-trees.md`](references/decision-trees.md).
- **continuous_replication_required** (optional, default: `false`): Whether the customer needs ongoing replication post-cutover.

**Constraints for parameter acquisition:**

- You MUST first scan the customer's opening message for inferable parameters (engine name + version, target region, role/persona cues, source artifacts pasted inline). If `source_engine`, `source_version`, `target_region`, and `persona` are all inferable from that message, you MUST proceed straight to Step 2 with the inferred values — **do not** ask the customer to repeat or confirm what they already wrote, because re-asking what was just said wastes a turn and erodes customer trust. Restate the inferred values once in your first sentence (Step 1) so the customer can correct a misread, then keep going.
- You MUST ask for the missing required parameters only when one or more of the four are not inferable. When you do ask, you MUST ask for ALL missing parameters in a single prompt rather than one at a time, because asking sequentially wastes turns.
- You MUST NOT block on a missing `discovery_inputs` artifact since real customers rarely have every artifact ready; capture what the user has, mark the rest UNKNOWN, and surface assumptions in the recap.
- You SHOULD support multiple input methods for `discovery_inputs`:
  - Direct input: JSON / XML pasted into the conversation
  - File path: `cat /path/to/cluster_health.json`
  - URL: link to an internal artifact store
- You MUST proceed to Step 2 once all four required parameters are either inferred or supplied. You MUST NOT ask the customer to "confirm" inferred values before proceeding because the Step 1 restate-and-continue pattern already gives them an opportunity to correct.

## Steps

### 1. Identify source family and persona, then restate-and-continue

Restate the source name, version, target region, and persona back to the customer in the FIRST sentence (e.g. "You're on Apache Solr 8.11 SolrCloud, target Amazon OpenSearch Service us-east-1, DOP persona — here's the assessment for that source.") so the customer can correct a misread, then **keep going** to Step 2 in the same response. Do not pause for confirmation when the values were given in the customer's message.

**Constraints:**

- You MUST identify the source family + version + target region + persona in the FIRST sentence of the assessment.
- You MUST detect Solr from `<requestHandler>` / `schema.xml` / `solrconfig.xml` artifacts and Elasticsearch / OpenSearch from `_cat/indices` style output, version strings (`ES 7.10`, `OS 1.7`), or vendor names ("Elastic Cloud", "Amazon OpenSearch Service").
- You MUST apply the persona-detection cues + mixed-persona rule from [`references/intake.md`](references/intake.md) (Personas section). You MUST ASK once only if persona is unambiguously unclear, then proceed.
- You MUST NOT pause to ask the customer to "confirm" engine, version, region, or persona that they already named in their opening message because the restate-in-first-sentence pattern already gives them an opportunity to correct.

### 2. Acquire inputs and produce a fingerprint

Walk the per-persona intake in [`references/intake.md`](references/intake.md). Normalize whatever the customer provided into a single fingerprint JSON in this shape (omit any field for which the input was not supplied — mark as UNKNOWN in the report rather than guessing):

```json
{
  "engine": "elasticsearch | opensearch | solr",
  "version": "7.10.2",
  "summary": {
    "node_count": 6,
    "index_count": 120,
    "total_docs": 3200000000,
    "total_gb": 8000,
    "plugin_count": 7,
    "health_status": "green"
  },
  "indices": [
    {"name": "logs-2024-11", "docs": 50000000, "store_size": "120gb", "primary": 6, "replica": 1}
  ],
  "plugins": [
    {"node": "ip-10-0-1-12", "component": "analysis-icu", "version": "7.10.2"}
  ],
  "cluster_settings": { "persistent": {}, "transient": {}, "defaults_subset": {} },
  "nodes": { "node_count": 6, "node_stats": [ /* per-node jvm + fs */ ] },
  "files_provided": ["_cat/indices.json", "_cluster/health.json", "_nodes/stats.json"]
}
```

For Solr sources, build the fingerprint by hand from `schema.xml`, `solrconfig.xml`, and the intake answers per [`references/solr-schema-migration.md`](references/solr-schema-migration.md).

**Constraints:**

- For **BSH persona**, you MUST run the six-question business intake in [`intake.md`](references/intake.md) (BSH section) BEFORE any technical question. Ask them as a numbered list in your response. You MUST NOT ask BSH users for technical detail (versions, schema, instance types) until the six are complete.
- For **SRE / DOP persona**, you MUST proceed straight to Steps 3–7 using whatever discovery inputs the customer provided in their opening message. You MUST NOT block on missing artifacts; capture what is available, mark the rest UNKNOWN, and surface assumptions inline in the report rather than as a "please confirm before I continue" prompt.
- You MUST NOT ask the SRE/DOP customer to validate or confirm the inputs before producing the assessment because the customer asked for the assessment, not for a back-and-forth intake interview. Surface assumptions and UNKNOWNs inside the report itself (e.g. "Assumed `replicas=1` since not provided"), then proceed to Step 3.
- You SHOULD save the fingerprint JSON to `<workspace>/fingerprint.json` so subsequent steps can reference a single canonical artifact.

### 3. Compatibility scan and risk inventory

Walk the source-engine surface and emit one entry per finding using the schema below.

```json
{
  "id": "ES_RUNTIME_FIELDS",
  "feature": "Elasticsearch runtime fields",
  "severity": "BLOCKING|HIGH|MEDIUM|LOW",
  "category": "schema|query|auth|ops|dashboards|plugin|version|sizing",
  "description": "...",
  "workaround": "...",
  "citation_url": "..."
}
```

**Constraints:**

- You MUST tag each finding with the severity rubric defined in [`references/compatibility-rubric.md`](references/compatibility-rubric.md).
- You MUST retrieve current X-Pack / k-NN / plugin-support tables per the recipe in [`references/knowledge-retrieval.md`](references/knowledge-retrieval.md) — never quote stale numbers from this skill's body, because version-compatibility tables drift per release.
- For Solr sources, you MUST flag legacy Trie field types and the Solr ≤ 5.x TF-IDF → BM25 similarity change per [`solr-schema-migration.md`](references/solr-schema-migration.md) §1. Trie types are HIGH severity (no direct OpenSearch equivalent); the similarity change is HIGH/MEDIUM behavioral incompatibility.
- You SHOULD be conservative: a false-positive flag is far less harmful than a missed BLOCKING.

### 4. Select the target shape and migration path

Walk the trees in [`references/decision-trees.md`](references/decision-trees.md) for **target shape** (Managed Domain vs Serverless NextGen), **migration path** (six families), and **k-NN engine**.

**Constraints:**

- You MUST default to **MANAGED** when the target-shape inputs are ambiguous, because Managed Domain is more flexible and re-evaluating to Serverless NextGen after stable traffic is straightforward.
- "Serverless NextGen" in this skill means **Amazon OpenSearch Serverless NextGen collections** exclusively. The original Serverless NextGen collection model is being superseded; you MUST NOT assert NextGen support / non-support / sizing / supported-source rows from training memory. Retrieve the current Serverless NextGen capability matrix every assessment via [`references/knowledge-retrieval.md`](references/knowledge-retrieval.md) (Amazon OpenSearch Serverless NextGen section). Pair this skill with the companion `aoss-nextgen` skill whenever target shape lands on Serverless NextGen.
- You MUST score all six migration-path families against the workload before recommending one. You MUST NOT assume Migration Assistant for Amazon OpenSearch Service is the answer just because the customer asked a "should we migrate" question — small workloads (< 100 GB) and same-major hops often have cheaper paths (snapshot/restore, reindex from remote). **Solr is the exception**: see the next bullet.
- For **Solr** sources you MUST default to Migration Assistant Solr backfill (RFS) regardless of data volume, because RFS is the **only** tool that can recover Solr fields configured `stored="false"` in `schema.xml` / `managed-schema`. RFS reads the source's Lucene segments directly; non-RFS paths (Solr `/export`, SolrJ `cursorMark`, Spark + opensearch-spark) can only emit values stored in `_source`-equivalent records and will silently lose any field whose `stored` attribute is false. You MUST audit `<field>` and `<dynamicField>` definitions in the schema before recommending a non-RFS path; recommend a non-RFS path ONLY when (a) every needed field is `stored="true"` AND (b) the customer can trivially re-emit the source data from a system of record AND (c) the dataset is small (<100 GB). Flag the trade-off explicitly. See the source-engine row in [`references/decision-trees.md`](references/decision-trees.md).
- **Migration Assistant auto-translates Solr schema → OpenSearch mappings AND auto-converts Solr documents → OpenSearch document format during backfill.** You MUST NOT put "translate the Solr schema to OpenSearch mappings" or "convert Solr documents to OpenSearch documents" as separate operator-driven steps in a Solr → OpenSearch migration plan when the path is MA RFS. The plan steps are: deploy MA → metadata migration (MA emits the OpenSearch mappings) → operator reviews + overrides if needed → backfill (MA converts the documents) → validate. The reference at [`references/solr-schema-migration.md`](references/solr-schema-migration.md) is for AUDITING MA's auto-translation output and identifying the rare cases that need a transformer override (custom analyzer chains, legacy field types, regex `dynamicField` patterns) — NOT a checklist the operator walks through manually. You MUST describe MA's auto-translate as the default behavior in the plan; you MUST NOT describe schema/document conversion as work the operator does.
- You MUST retrieve the current Migration Assistant capability matrix before quoting source-engine support per [`references/knowledge-retrieval.md`](references/knowledge-retrieval.md), because caps and source-version support change with each release. Migration Assistant supports **Apache Solr / SolrCloud** via the Solr backfill workflow (Reindex-from-Snapshot, RFS) AND **Elasticsearch / OpenSearch** sources — do NOT tell a customer Migration Assistant is "Elasticsearch-only" or that it "doesn't support Solr".
- You MUST confirm regional availability of the recommended instance family per the Regional-availability recipe in [`references/knowledge-retrieval.md`](references/knowledge-retrieval.md) before quoting.

### 5. Produce sizing guidance

For every assessment, deliver Compute, Storage, Shards, JVM heap, Topology, and Tier strategy per the rules + formulas in [`sizing-formulas.md`](references/sizing-formulas.md). For Solr sources, also apply the match-source sizing rule in [`solr-sizing-and-performance.md`](references/solr-sizing-and-performance.md) (target ≈ 1–1.5× source unless workload signals demand more).

**Constraints:**

- You MUST NOT estimate dollar costs because pricing changes monthly and account-specific RI / Savings Plan / EDP discount math is outside an LLM's reliable scope. Plug into <https://calculator.aws> instead.
- You MUST end the sizing section with: *"Plug these inputs into the AWS Pricing Calculator at <https://calculator.aws> for the dollar figure."*

### 6. Compute the readiness score and consult the gotcha catalog

Score the workload across the seven dimensions in [`references/readiness-rubric.md`](references/readiness-rubric.md): compatibility 25, operational readiness 15, sizing fitness 15, data-movement complexity 15, cutover complexity 15, sizing-input completeness 10, stakeholder alignment 5. Sum to a 0–100 overall.

**Constraints:**

- You MUST tier the result: **GREEN ≥ 80** (proceed; surface top 3 risks); **YELLOW 60–79** (PoC + spike on the weakest dimension before committing); **RED < 60** (do not commit; revisit weakest dimension first).
- You MUST cross-reference at least one applicable gotcha from [`references/nuggets.md`](references/nuggets.md) and cite it by number in the risk register because many of those gotchas are not in any AWS doc and missing them is the most common production-readiness gap.
- You MUST NOT pad the risk register with all 20 nuggets because a 20-item parade buries the 2–3 risks that actually matter for this workload.

### 7. Render the migration report

Render the report into the templates under `assets/`:

- `assets/report-template.md` → `MIGRATION_ASSESSMENT.md` (full assessment)
- `assets/executive-summary-template.md` → `EXECUTIVE_SUMMARY.md` (BSH summary)
- `assets/tech-deepdive-template.md` → `TECHNICAL_DEEP_DIVE.md` (SRE / DOP deep dive)
- For Solr sources: `assets/solr-report-template.md`, `assets/solr-index-template-skeleton.md`, `assets/solr-gap-register.md`

**Constraints:**

- You MUST emit every required section, in order: Executive Summary, Source, Target, Migration Path, Sizing, Readiness, Risks, Citations.
- You MUST cite ≥ 5 unique authoritative URLs with retrieval timestamps.
- You MUST surface <https://calculator.aws> for any cost-related question.
- You MAY skip the templates and answer in-line when the user asked a focused operational question (e.g. "snapshot vs reindex for 50 GB?") that doesn't warrant the full report shape.
- You SHOULD lead with the **decision and the top three risks** and use terse tables (not prose paragraphs) for schema mappings, gap registers, and feature-parity rows. For complex source workloads (X-Pack heavy, Solr 6.x with multiple breaking field types, multi-major hops) the agent has hit `max_tokens` limits with long-form prose; defer exhaustive enumeration to follow-up if the customer asks.
- You MUST NOT include credentials, endpoints, or master usernames in examples or generated reports because reports are routinely shared with stakeholders and may end up in unapproved repos.

### 8. Verify before delivery

Before declaring the assessment done, you MUST reproduce this checklist in your response and tick each box. Skipping this step is the most common cause of incomplete assessments.

```
- [ ] All 8 required sections emitted, in order (Exec / Source / Target / Migration Path / Sizing / Readiness / Risks / Citations)
- [ ] ≥ 5 unique authoritative URLs cited, each with a retrieval timestamp
- [ ] Every version-specific claim (Lucene, instance limit, plugin support) carries an inline citation
- [ ] <https://calculator.aws> surfaced for the cost handoff
- [ ] At least 1 nugget from references/nuggets.md cross-referenced by name
- [ ] Target shape default = MANAGED unless workload explicitly justifies Serverless NextGen
- [ ] Migration path was scored across all six families (you didn't default to Migration Assistant without scoring)
- [ ] Persona-correct depth (BSH = exec summary; SRE/DOP = full technical body)
- [ ] No embedded credentials, endpoints, or master usernames anywhere in the report
- [ ] Security section cites the canonical control list in references/security.md and confirms each control was assessed
```

If any box can't be ticked, you MUST fix the gap before responding. You MUST NOT deliver a report with unticked items because the rubric grades on completeness and a missing citation drops the workload below the §4 ≥80% bar.

## Security Considerations

Every assessment report MUST include a Security section. The full canonical recommendations (auth, authorization, transport + ACM, web-facing headers, encryption at rest, network, audit + SNS-KMS, migration-tool security, secrets, sensitive-data handling, production-like defaults, three-layer throttling) live in [`references/security.md`](references/security.md). You MUST cite that file in the report's Security section and confirm each control rather than re-inventing the list.
## Examples

### Example: small ES 7.17 cluster, 50 GB

**Input.** "Small Elasticsearch 7.17 cluster, 50 GB total, single index. We have a 2-hour maintenance window. What's the cheapest path to AWS?"

**Expected output.** A focused operational answer (no full report scaffold) that:
1. Recommends `_reindex` from remote as the cheapest path. Snapshot/Restore from ES ≥ 7.11 (under ELv2/SSPL) is NOT a supported migration path on Amazon OpenSearch Service — see [`nuggets.md`](references/nuggets.md) #21.
2. Notes that Migration Assistant Reindex-from-Snapshot (RFS) is the supported fallback if `_reindex` from remote is not viable (e.g. source-side network restrictions); MA is heavier than needed for a 50 GB workload.
3. Confirms the 2-hour window is sufficient for `_reindex` from remote on 50 GB.
4. Provides sizing for a small Managed configuration (e.g. `m6g.large` × 3, gp3 storage) or a single-OCU dev/test Serverless NextGen plan.
5. Lists the runbook (pre-create destination index, configure `reindex.remote.allowlist` on target, trigger reindex, validate doc count + top-N query parity, switch traffic).
6. Names the AWS doc URLs cited.
7. Surfaces <https://calculator.aws> for the dollar figure.

### Example: OS 1.7 with k-NN, 50M × 768-dim vectors

**Input.** "We're on OpenSearch 1.7 with k-NN using NMSLIB engine, 50 million vectors at 768 dimensions. Want to upgrade to OpenSearch 3.x on AWS."

**Expected output.** A full assessment report that:
1. Identifies the source (OS 1.7) in the first sentence.
2. States OS 1.x cannot upgrade directly to 3.x; mandatory hop through OS 2.19.
3. Identifies older-Lucene indexes carried forward from OS 1.x as incompatible with OS 3.x (Lucene 10 segment-format wall — see [`references/nuggets.md`](references/nuggets.md) #7) and notes they must be reindexed before reaching 3.x.
4. Flags NMSLIB engine deprecation; recommends FAISS as the replacement.
5. Recommends in-place blue/green upgrade as the primary migration path.
6. Provides a sizing recommendation appropriate for 50M × 768-dim vectors per [`references/sizing-formulas.md`](references/sizing-formulas.md).
7. Cites at least one authoritative AWS doc URL.
8. Provides clear sequencing: 1.7 → 2.x intermediate → 2.19 → 3.x with reindex step.

## Troubleshooting

### Customer asks for a dollar cost figure

Reply: "This skill produces sizing inputs (instance type, count, OCU plan, storage). Plug those into the AWS Pricing Calculator at <https://calculator.aws> for an authoritative figure that reflects your account's RI / Savings Plan / EDP discounts. I don't estimate dollars because pricing changes monthly and account-specific discount math is outside this skill's scope."

### `aws___read_documentation` returns "All URL(s) must be from..."

The tool enforces a domain allow list. Use `WebFetch` for non-AWS hosts (`docs.opensearch.org`, `solr.apache.org`, `elastic.co`, etc.). Per-domain routing rules live in [`references/knowledge-retrieval.md`](references/knowledge-retrieval.md) (Three retrieval primitives section).

### Recommended path is `in-place-upgrade` but the source is on-prem self-managed OpenSearch

In-place applies only to AWS-managed clusters. Flag the mismatch in the report and re-score with the customer's actual deployment shape.

### Customer wants Serverless NextGen but workload uses custom plugins or Lucene k-NN

Serverless NextGen does not support either. Re-score for Managed Domain.

### OpenSearch 1.x → 3.x in one hop

Not supported. Recommend the in-place upgrade path through OS 2.19, then 3.x.

### ES 7.10-or-older indexes destined for OS 3.x

Lucene 10 incompatibility — reindex before upgrade.

## Additional Resources

References (loaded on demand):

- [`references/decision-trees.md`](references/decision-trees.md) — target shape, migration-path matrix, k-NN engine, RI term, go/no-go gates, duration heuristics
- [`references/intake.md`](references/intake.md) — personas + per-role intake (SRE / DOP / BSH; ES, OS, Solr)
- [`references/source-elasticsearch.md`](references/source-elasticsearch.md), [`references/source-opensearch.md`](references/source-opensearch.md) — version matrices + breaking changes
- [`references/compatibility-rubric.md`](references/compatibility-rubric.md), [`references/nuggets.md`](references/nuggets.md) — severity rubric, always-flag list, 20 production gotchas
- [`references/sizing-formulas.md`](references/sizing-formulas.md), [`references/readiness-rubric.md`](references/readiness-rubric.md) — sizing IP + 7-dimension readiness score
- [`references/knowledge-retrieval.md`](references/knowledge-retrieval.md) — topic → tool → URL (canonical retrieval recipe with browser/CLI fallback)
- [`references/scope.md`](references/scope.md), [`references/assumptions.md`](references/assumptions.md) — scope guardrails + standing assumptions
- Solr deep dives: [`references/solr-schema-migration.md`](references/solr-schema-migration.md), [`references/solr-transformation-rules.md`](references/solr-transformation-rules.md), [`references/solr-query-behavior-edge-cases.md`](references/solr-query-behavior-edge-cases.md), [`references/solr-analysis.md`](references/solr-analysis.md), [`references/solr-legacy-features.md`](references/solr-legacy-features.md), [`references/solr-sizing-and-performance.md`](references/solr-sizing-and-performance.md)

Assets: `assets/{report-template, executive-summary-template, tech-deepdive-template, solr-report-template, solr-index-template-skeleton, solr-gap-register}.md`.

External: every URL the skill ever cites is centralized in [`references/knowledge-retrieval.md`](references/knowledge-retrieval.md). The only inline URL anywhere in the skill is <https://calculator.aws> (every dollar-cost question routes there). Always retrieve before quoting — do NOT treat any URL list as a snapshot.
