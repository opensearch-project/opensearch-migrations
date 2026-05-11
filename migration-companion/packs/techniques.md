# Techniques pack: how migrations actually move data

A reference for the seven approaches a Companion run can pick from.
Phase 3 (`steering/03-evaluate.md`) is where the agent narrows to one;
this pack is the longer-form description for users who want to read
or compare without going through the interview.

If you've never thought about index migration before, read this top
to bottom. If you're triaging a known case, jump to "When to use what"
and the "Common combinations" at the bottom.

## TL;DR matrix

| # | Technique                          | Live cutover? | Transforms? | AOSS target? | ES 8 source? | Setup cost | Per-migration cost |
|---|------------------------------------|---------------|-------------|--------------|--------------|------------|--------------------|
| 1 | Snapshot + restore                 | no            | no          | no           | no           | low        | low                |
| 2 | Reindex-from-Snapshot (RFS) via MA | no¹           | yes         | yes          | yes          | high       | low                |
| 3 | `_reindex` from remote             | no            | limited     | no           | partial²     | low        | medium             |
| 4 | Capture-and-replay (MA)            | **yes**       | yes         | yes          | yes          | very high  | medium             |
| 5 | Logstash / Fluentd dual-write      | yes           | yes         | yes          | yes          | medium     | medium             |
| 6 | Cross-cluster replication (CCR)    | yes (ongoing) | no          | no           | no           | medium     | low                |
| 7 | Application-layer dual-write       | yes           | yes         | yes          | yes          | medium³    | medium             |

¹ RFS is offline replay; pair with #4 capture-and-replay for zero-downtime.
² ES 8 source TLS/auth model is not supported by every OS reindex client matrix.
³ Low *technical* cost; high *organizational* cost (app teams must change code).

---

## 1. Snapshot + restore

**What it does.** Take a snapshot on source via the snapshot API,
restore it on target via the restore API. Native to both Elasticsearch
and OpenSearch. The snapshot is a Lucene-segment-level copy; restore
is byte-shuffling, not document-by-document.

**Pros.**
- Simplest path. One snapshot + one restore. No moving parts.
- Fastest at scale. Restore I/O is bounded by your snapshot store
  bandwidth (S3, NFS), not by the target's indexing throughput.
- Preserves shard layout, doc IDs, and `_seq_no` exactly.

**Cons.**
- No transformation hook. Source mappings/templates land verbatim on
  target. If the target rejects them (deprecated field types,
  unsupported analyzers), the restore fails and you start over.
- Tight version compatibility window. Source major must be ≤ target
  major + 1. ES 8 → any OS is unsupported (different snapshot format
  internals, no compatibility layer).
- AOSS as target is unsupported. AOSS has no snapshot API.
- Cluster-state items don't restore (templates can; ISM policies,
  watcher jobs, security users do not).

**Use when.** Same-engine like-for-like upgrade (OS 1.3 → OS 2.x,
or ES 7.10 → OS 1.x via the OpenSearch compatibility shim), no
transforms required, multi-TB scale where any other approach would
take days.

**Don't use when.** AOSS target. ES 8 source. Anything that needs
mapping rewrites, index renames, or schema-level changes.

---

## 2. Reindex-from-Snapshot (RFS) via Migration Assistant

**What it does.** MA reads the source snapshot from a shared S3 or
filesystem location, parses index metadata, applies any configured
transforms, and bulk-writes documents into the target via its HTTP
indexing API. The actual byte stream goes:

```
source snapshot  →  RFS worker  →  transform pipeline  →  target /_bulk
```

**Pros.**
- Widest compatibility matrix in this skill. Every supported source
  pair (ES 5/6/7/8, OS 1/2/3) works against every supported target
  (OS self-managed, AOS, AOSS).
- Transforms are first-class. Mapping rewrites, index renames, type
  collapses, field exclusions all run in the pipeline.
- Scales horizontally. Increase the worker fleet (`console backfill
  scale N`) and you increase indexing throughput linearly until you
  saturate the target's `_bulk` queue.
- Resumable. Workers checkpoint per shard; if one dies, another picks
  up the same shard's remaining docs.

**Cons.**
- Highest setup cost. Requires k8s (or EKS), the MA helm chart,
  Strimzi/Kafka if you're also doing capture-and-replay, plus the
  migration-console and Argo for orchestration.
- Doc-level rewrite cost. Unlike snapshot/restore, every document
  goes through `_bulk`, so target indexing CPU is the bottleneck.
- Snapshot must reach the workers. Cross-account / cross-region /
  cross-VPC snapshots need wiring (see pitfalls P19).

**Use when.** This is the default for any non-trivial migration. ES
or OS source, any OS target, transforms needed, dataset large enough
that `_reindex` from remote would be too slow.

**Don't use when.** Tiny datasets where the helm install takes longer
than the migration itself. Single-shot homework where #1 or #3 would
finish in a single command.

---

## 3. `_reindex` from remote (built-in)

**What it does.** The target cluster pulls documents directly from
the source over HTTP using the built-in `_reindex` API with a
`source.remote` clause. No intermediate snapshot, no MA.

```
POST $TARGET/_reindex?wait_for_completion=false
{ "source": { "remote": {"host": "$SOURCE"}, "index": "*" },
  "dest":   { "index": "{{index}}" } }
```

**Pros.**
- Lowest moving-parts count of any live-data approach. One curl.
- No snapshot store needed. Target reads source directly.
- Works fine for hundreds of millions of small documents on a fast
  intra-VPC link.

**Cons.**
- Requires `reindex.remote.allowlist` to include source. This is a
  **static** cluster setting on most distributions, meaning a target
  restart. AOS supports it via UpdateDomainConfig (no manual
  restart, but the domain blue-greens). AOSS does **not** expose it
  at all.
- Doc-by-doc HTTP. WAN latency multiplies; works poorly across
  regions or over VPN.
- Limited transforms. The `script` clause is a Painless one-liner,
  not a pipeline.
- ES 8 source is in-and-out depending on the OS reindex client
  matrix; some combinations fail TLS/auth handshake.
- No resume. If the task dies, you restart from zero (or hand-craft
  a `_search_after`-based wrapper).

**Use when.** Dataset < ~100 GB, both clusters in the same VPC, no
transforms or one trivial Painless rewrite, AOS or self-managed
target, and you'd rather not stand up MA.

**Don't use when.** AOSS target. WAN-separated clusters. Anything
that needs to resume after a target restart.

---

## 4. Capture-and-replay (Migration Assistant)

**What it does.** MA's capture-proxy sits in front of the source
cluster's HTTP endpoint and mirrors all writes into a Kafka topic
while passing requests through unchanged. Backfill (RFS, #2) runs in
parallel. After backfill catches up, MA's traffic-replayer drains
the buffered writes into the target, achieving cutover with no
write loss.

```
clients → capture-proxy → source        (passthrough)
                       ↓
                     Kafka  →  traffic-replayer  →  target
   (parallel)
   source snapshot  →  RFS  →  target
```

**Pros.**
- Only approach in this list that gives true zero-downtime cutover
  with no application change.
- Replayer dedup logic uses sequence numbers, so write order is
  preserved.
- Works for any source that's behind a proxy you can insert.

**Cons.**
- Highest infrastructure cost in this skill. Capture-proxy + Strimzi
  + Kafka cluster + traffic-replayer + everything RFS needs.
- Capture-proxy is the **only MA image not published on public ECR**
  at the time of writing — see pitfalls P18. You need a private
  build path.
- Inserting a proxy in front of the source is operationally
  invasive. Most managed services (Elastic Cloud, AOS) won't let
  you do it.
- If write rate > replayer throughput, Kafka backs up. You can
  scale, but at some point you've built a streaming system, not a
  migration.
- POC scale: way too much infra for a demo. Don't reach for this
  unless the zero-downtime requirement is real.

**Use when.** Production, regulated workload, source is self-managed
(or behind your own load balancer), zero-downtime is genuinely
non-negotiable.

**Don't use when.** POC. Source is a managed service you can't put
a proxy in front of. The "zero-downtime" requirement is aspirational
rather than contractual.

---

## 5. Logstash / Fluentd dual-write

**What it does.** A stream processor (Logstash, Fluentd, Vector)
reads from the application's write path — or from a queue the app
already publishes to — and forks each event to both source and
target clusters. After target steady-state, flip reads.

**Pros.**
- AOSS target works (Logstash has a sigv4 output). #4 capture-replay
  also reaches AOSS, but Logstash is far less infra.
- Many shops already have Logstash for ingest — you're adding an
  output, not a service.
- Writes go to both clusters as native `_bulk` requests, so target
  is always seeing real shape — no schema drift between source and
  target snapshot eras.

**Cons.**
- No backfill story. Logstash starts mirroring *now*; everything
  written before "now" needs a separate backfill (#1, #2, or #3).
  This means you're running two techniques simultaneously, which
  doubles the operational story.
- Schema drift between source and Logstash filter config silently
  corrupts target data. Field whose source mapping is `keyword`
  but Logstash output config sends as `text` does not error — it
  indexes wrong.
- Logstash write failures during cutover window can be missed if
  retry/DLQ is misconfigured.

**Use when.** App team already runs Logstash, AOSS target, dual-write
fits the existing ingest topology, willing to combine with a backfill
technique for history.

**Don't use when.** No existing Logstash. Need a single-tool story.
Schema is volatile.

---

## 6. Cross-cluster replication (CCR)

**What it does.** OpenSearch's CCR plugin lets a follower index on
target track a leader index on source. After replication catches up,
flip clients.

**Pros.**
- Built into OpenSearch. No external pipeline.
- Per-index granularity — replicate only what you need.
- Sustained replication, so cutover can be deferred indefinitely.

**Cons.**
- OS → OS only. Any ES source is unsupported.
- AOSS as either side is unsupported.
- One-shot migration is overkill — CCR is for ongoing replication
  topologies, not "I need to move data once". For a one-shot move,
  #1 or #2 is simpler.
- Cross-cluster trust has its own setup (see opensearch-ccr-shared-
  ca-trust skill in the broader Hermes corpus). Easy to misconfigure
  silently.

**Use when.** Both source and target are OpenSearch (self-managed or
AOS), the user actually wants ongoing replication (DR, geo-redundancy),
not a one-shot migration. If they say "we want CCR" but mean "we
want to migrate", redirect them to #1 or #2.

**Don't use when.** Any ES source. AOSS anywhere. One-shot migration.

---

## 7. Application-layer dual-write

**What it does.** Application code writes to both clusters explicitly
on every write path. Backfill (separate) catches up history. Reads
flip after target is steady.

**Pros.**
- No infrastructure introduced — it's all code in the app.
- Target sees writes in their native, application-canonical shape.
- App team has full control over the write semantics during cutover.

**Cons.**
- Highest organizational cost. Every write path in the app must be
  identified and modified, including batch jobs, cron tasks, and
  any internal tooling that bypasses the main service.
- Dual-write failures in app code are easy to miss — partial writes
  desync the two clusters silently.
- Engineering time scales with the app's complexity, not the
  cluster's size.
- Doesn't help with backfill — pair with #1, #2, or #3 for history.

**Use when.** AOSS target with hard zero-downtime, source can't be
proxied (so #4 is out), app team owns the change and is willing to
ship code, or the user explicitly wants migration logic in their
codebase rather than as a separate system.

**Don't use when.** Many writer paths the central team doesn't
control. Shadow batch jobs. Schema is changing during the cutover
window.

---

## When to use what (quick decision)

Walk in this order — first match wins:

1. **Target is AOSS** → rule out #1 (no snapshot API), #6 (CCR not
   supported). Default to **#2 RFS**. Consider **#5 Logstash** if
   the team already runs Logstash. Consider **#7 app dual-write**
   only if hard zero-downtime is required.
2. **Source is ES 8** → rule out #1 (snapshot format incompatible).
   Default to **#2 RFS**.
3. **Hard zero-downtime requirement** → **#4 capture-and-replay**
   (if source can be proxied) or **#5 Logstash / #7 app dual-write**
   otherwise. Pair with **#2 RFS** for backfill.
4. **Dataset < 100 GB, same VPC, no transforms, target accepts
   `reindex.remote.allowlist`** → **#3 `_reindex` from remote**.
5. **OS → OS, ongoing replication wanted (not just migration)** →
   **#6 CCR**.
6. **Otherwise** → **#2 RFS via MA** is the safe default.

LEARN-path users (educational only) should see #1, #2, #3 in detail,
then a one-line mention of #4-#7 with a "ask if you want to know
more" tail.

## Common combinations

In practice these aren't always one-or-the-other:

- **#2 RFS + #4 capture-and-replay** — the canonical Migration
  Assistant deployment. RFS does the bulk backfill; capture-replay
  catches everything written during and after backfill. This is what
  the MA helm chart deploys by default.
- **#1 snapshot/restore + #6 CCR** — bootstrap a brand-new follower
  with snapshot/restore, then start CCR for ongoing sync. Less wire
  time than CCR initial sync from empty.
- **#5 Logstash + #1 snapshot/restore** — Logstash does live mirror
  going forward; snapshot/restore handles backfill. Common when
  AOSS isn't the target (else #1 isn't available).
- **#2 RFS + #3 `_reindex` from remote** — RFS for the bulk of
  indices; `_reindex` from remote for a few small indices that
  weren't worth bringing up in the snapshot path. Edge case, but
  legitimate.

## What this pack is NOT

This pack does not enumerate the *combinations* exhaustively, nor
does it prescribe choices for any given user. That's Phase 3's
job (`steering/03-evaluate.md`), which walks the actual decision
tree against a known source/target pair. Use this pack as the
reference; use Phase 3 as the workflow.
