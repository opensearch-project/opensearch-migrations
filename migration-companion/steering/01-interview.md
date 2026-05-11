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

Path-specific defaults:

- `intent_path == POC`: skip the version question. The POC stack
  seeds the source for them (default ES 7.17 in
  `TrafficCapture/dockerSolution/`; the helm-chart POC defaults
  to OS 2.x). Tell them what will be seeded and move on. Phase 3
  is where they can override.
- `intent_path == LEARN`: don't require a specific version. Ask
  for a rough engine + era only ("ES 7-ish", "OS 2", "I'm not
  sure yet"). LEARN's job is to read `packs/techniques.md` and
  produce a planning report. A precise version isn't needed.
- `intent_path == ANALYZE`: if the user only has a snapshot in
  hand and doesn't know the source version, accept "I'll show
  you in Phase 2" — Phase 2 reads version off the snapshot
  metadata. Don't block on a version they don't have.
- `intent_path == MIGRATE`: ask for the version. Migrations need
  it pinned before any technique can be picked.

When you ask:

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

For `intent_path == LEARN`, accept "I haven't decided yet" — that
*is* the question they're trying to answer. Note it and move on;
Phase 6 will lay out the trade-offs.

For `intent_path == POC`, default to **self-managed OpenSearch** in
the same compose / kind stack as the source. POCs that target a
real AOS domain or AOSS collection are valid but no longer "POC" —
push back to the user if they pick AOS/AOSS for a POC and check
they meant it.

## Confirm

Echo back what you heard in one short paragraph and ask them to
confirm. Example:

> So: Elasticsearch 7.17 → Amazon OpenSearch Service, you're on the
> DevOps side, MIGRATE path. Confirm?

## Exit criteria

You know `persona`, `source_engine`, `source_version`,
`target_flavor`, and `intent_path`. Move on per the phase map in
`SKILL.md`.
