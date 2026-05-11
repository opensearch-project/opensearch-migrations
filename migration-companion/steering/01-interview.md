# Phase 1 — Interview

**Goal:** Pin down (a) the user's persona, (b) source engine + version,
(c) target engine + flavor. Stepwise — one question per message.

## Persona

> Are you closer to a **Search Relevance Engineer** (queries,
> mappings, ranking), a **DevOps / Platform Engineer** (clusters,
> snapshots, IAM), or a **Business Stakeholder** (timelines, cost,
> milestones)?

Use it to tune depth, not to gate content:
- SRE: full DSL / mapping detail, surface analyzer differences.
- DOP: emphasize snapshots, IAM, networking, sizing.
- BSH: summarize technical detail as schedule/cost/risk.

## Source engine

If `intent_path == POC`: skip — we'll seed the source for them in
Phase 4. Tell them which source we'll seed (default ES 7.17, see
Phase 3 to override) and move on.

Otherwise:

> What are you migrating from — **Elasticsearch** or **OpenSearch**?
> And which version? (e.g. ES 7.17, OS 2.11)

Validate the version against the source pack's matrix:
- ES: 5.x, 6.x, 7.x, 8.x
- OS: 1.x, 2.x, 3.x

If it's something else (Solr, Kibana-only, a fork), stop and ask
the user to clarify. Solr → point them at
`AIAdvisor/skills/solr-opensearch-migration-advisor/`.

## Target

> Where are you migrating to?
>
> 1. **Self-managed OpenSearch** (you run the cluster).
> 2. **Amazon OpenSearch Service** (AOS — managed domains).
> 3. **Amazon OpenSearch Serverless** (AOSS — collections, no cluster
>    management).

If they say "AWS" without specifying, ask. The difference matters:
AOSS doesn't support snapshot restore, AOS does.

## Confirm

Echo back what you heard in one short paragraph and ask them to
confirm. Example:

> So: Elasticsearch 7.17 → Amazon OpenSearch Service, you're on the
> DevOps side, MIGRATE path. Confirm?

## Exit criteria

You know `persona`, `source_engine`, `source_version`,
`target_flavor`, and `intent_path`. Move on per the phase map in
`SKILL.md`.
