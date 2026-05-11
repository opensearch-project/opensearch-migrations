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

Prefer the console CLI when MA is already deployed (MIGRATE path,
sometimes ANALYZE):

```
console clusters connection-check       # source + target reachability
console clusters cat-indices             # canonical index summary
```

Falls through to raw HTTP otherwise. Run these in order, narrate
each one:

```
GET /                                 # version, cluster name, lucene
GET /_cluster/health                  # status, node count
GET /_cat/indices?v&s=index&h=index,docs.count,store.size,pri,rep
GET /_cat/plugins?v&h=name,component  # security, ism, alerting, etc.
GET /_cluster/settings?include_defaults=false
GET /_template, /_index_template      # legacy + composable
```

If `_cat/indices` returns more than ~200 indices, **don't dump the
whole list into context.** Summarize: total index count, total doc
count, top 10 by `store.size`, count of dot-prefixed system
indices. Then ask the user "which index patterns matter for the
migration?" before walking per-index mappings. A 10K-index cluster
hand-walked via `_mapping` will eat the agent's context for nothing.

For each user-data index *the user has flagged* (skip dot-prefixed
system indices unless the user calls them out):

```
GET /<index>/_mapping
GET /<index>/_settings
GET /<index>/_count
```

## What you probe (snapshot)

Don't try to hand-parse Lucene snapshot blobs. Use the MA tooling
that already exists:

  - If MA is deployed: `console snapshot status` and
    `console metadata evaluate` read the snapshot's index
    list, mappings, and per-index settings via the same code path
    the migration uses. `evaluate` is the dry-run that previews
    what `migrate` would do without writing to the target.
    This is the source of truth.
  - If MA isn't deployed yet but you have S3 access: register the
    snapshot repo on a throwaway OS instance (or the future target,
    if the version pair allows) and run
    `GET /_snapshot/<repo>/<snapshot>` for metadata.
  - If you only have S3 paths: read `index-N` and the per-index
    `meta-<uuid>.dat` blobs as JSON / SMILE — but this is the path
    of last resort. Lucene format details aren't part of this
    skill.

Cross-major incompatibility flag: if the source major is more than
`target major + 1`, **or** the source is ES 8.x against any
OpenSearch target, snapshot/restore is blocked. RFS is required.
(See `packs/techniques.md` "Version compatibility — quick
reference" for the full table.)

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
