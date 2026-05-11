# Phase 2 — Probe

**Goal:** Read enough of the source cluster (or snapshot) to know what
needs to migrate. Read-only. No writes.

Run on paths: `ANALYZE`, `MIGRATE`. Skip on `LEARN` and `POC`.

## What you ask for

For MIGRATE:
- Source URL + auth (basic / API key / IAM-signed for AOS).
- (Optionally now, definitely by Phase 5) target URL + auth.

For ANALYZE:
- Either a snapshot location (S3 bucket + prefix, or filesystem path)
  OR access to a cluster you can read snapshots from.

If they don't have credentials handy, say so plainly and pause —
don't make up placeholders.

## What you probe (live cluster)

Run these in order, narrate each one:

```
GET /                                 # version, cluster name, lucene
GET /_cluster/health                  # status, node count
GET /_cat/indices?v&s=index&h=index,docs.count,store.size,pri,rep
GET /_cat/plugins?v&h=name,component  # security, ism, alerting, etc.
GET /_cluster/settings?include_defaults=false
GET /_template, /_index_template      # legacy + composable
```

For each user-data index (skip dot-prefixed system indices unless the
user calls them out):

```
GET /<index>/_mapping
GET /<index>/_settings
GET /<index>/_count
```

## What you probe (snapshot)

Open the snapshot's `index.latest` / `index-N` blob and read:
- Snapshot UUID, version, indices list.
- Per-index `meta-<uuid>.dat` for mappings + settings.

If the snapshot was taken on a major version >2 ahead of the target,
flag it as a hard incompatibility (e.g. ES 8 snapshot → OS 2.x is
**not** directly restorable; RFS is required).

## Pack loading

Now is the time. Load:
- `packs/source-{elasticsearch,opensearch}.md` based on Phase 1.
- `packs/target-{self-managed,aws-managed,aws-serverless}.md` based
  on Phase 1.

Cross-reference what you found against the source pack's "things
that don't carry over" list and the target pack's capability profile.
Build a mental list of incompatibilities. Don't write them to a file
yet — that's Phase 6.

## Exit criteria

You can answer:
- How many indices and docs?
- What plugins / security model is on the source?
- What features in use have a target-side caveat?

Move to Phase 3.
