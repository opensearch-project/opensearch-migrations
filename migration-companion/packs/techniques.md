# Techniques pack: how migrations actually move data

A reference for the seven approaches a Companion run can pick from.
Phase 3 (`steering/03-evaluate.md`) is where the agent narrows to one;
this pack is the longer-form description for users who want to read
or compare without going through the interview.

If you've never thought about index migration before, read this top
to bottom. If you're triaging a known case, jump to "When to use what"
and the "Common combinations" at the bottom.

## TL;DR matrix

| # | Technique                          | Sources supported          | Targets supported              | Live cutover? | Transforms? | Source impact     | Setup cost | Per-migration cost |
|---|------------------------------------|----------------------------|--------------------------------|---------------|-------------|-------------------|------------|--------------------|
| 1 | Snapshot + restore                 | ES 6.8, 7.x; OS 1.x, 2.x⁸  | OS 1.x, 2.x, 3.x; AOS          | no            | no          | medium⁴           | low        | low                |
| 2 | Reindex-from-Snapshot (RFS) via MA | ES 1.x–8.x; OS 1.x, 2.x, 3.x | OS 1.x, 2.x, 3.x; AOS; AOSS  | no¹           | yes         | **none**          | high       | low                |
| 3 | `_reindex` from remote             | ES 1.x–7.x; OS 1.x, 2.x⁹   | OS 1.x, 2.x, 3.x; AOS          | no            | limited     | **high**⁵         | low        | medium             |
| 4 | Capture-and-replay (MA)            | ES 6.8–8.x; OS 1.x, 2.x, 3.x | OS 1.x, 2.x, 3.x; AOS; AOSS  | **yes**       | yes         | medium⁶           | very high  | medium             |
| 5 | Logstash / Fluentd dual-write      | ES 5.x–8.x; OS 1.x, 2.x, 3.x¹⁰ | OS 1.x, 2.x, 3.x; AOS; AOSS | yes        | yes         | variable⁷         | medium     | medium             |
| 6 | Cross-cluster replication (CCR)    | OS 1.1+, 2.x, 3.x          | OS 1.1+, 2.x, 3.x              | yes (ongoing) | no          | low (ongoing)     | medium     | low                |
| 7 | Application-layer dual-write       | anything the client lib speaks | anything the client lib speaks | yes      | yes         | **none**          | medium³    | medium             |

¹ RFS is offline replay; pair with #4 capture-and-replay for zero-downtime.
³ Low *technical* cost; high *organizational* cost (app teams must change code).
⁴ Initial snapshot reads every segment from disk — can saturate snapshot-thread-pool and disk I/O on a busy source. Incrementals after the first are cheap.
⁵ Source serves a continuous scroll/search workload to the target for the whole reindex window. Pressures source's search thread pool, heap (scroll contexts), and network egress. **Can take down an underprovisioned source.**
⁶ Capture-proxy adds a network hop and CPU cost to every source request. Read path is untouched; write path latency increases.
⁷ Depends on the Logstash input. `elasticsearch` input doing historical scroll = same pressure as #3. Tap into an existing ingest pipeline = zero source impact.
⁸ Snapshot/restore opens cross-major (ES 6→OS, ES 7→OS, OS 1→OS 2/3) but the indices keep their original segment format and mapping shape — see the "Snapshot restore tradeoffs vs RFS" section below. ES 1.x/2.x/5.x snapshot formats are too old for any direct OS restore. ES 8 changed snapshot internals and has no compatibility shim into OS. AOSS has no snapshot API at all. **Native-restore is only the unambiguous best path for same-major same-engine upgrades.**
⁹ `_reindex` from remote with an ES 8.x source is unreliable in practice — TLS/auth handshake mismatches with OS reindex clients fail in ways that depend on the exact patch versions. Treat ES 8 as "use #2 instead."
¹⁰ Logstash version determines source/target reach. Logstash 8.x inputs/outputs against ES 1.x/2.x have been removed; Logstash 7.x is the broad-compatibility branch. The OpenSearch output plugin (`logstash-output-opensearch`) targets OS 1.x/2.x/3.x and AOS/AOSS via sigv4.

**The "Source impact" column is critical for production migrations.**
A migration plan that ignores source pressure is how you turn a
migration into an outage.

## Version compatibility — quick reference

The technique's source/target columns above tell you *whether* a pair
is supported. They don't tell you *what configuration* you need for
edge versions. Use this when the user names a specific source/target
combo.

**Read this before recommending snapshot/restore:** native
snapshot/restore (#1) is only the unambiguous best path for **same
major version, same engine** (OS x.y → OS x.z, or like-for-like ES
upgrades within a major). Anything cross-major or cross-engine
carries real tradeoffs that RFS (#2) does not — see the "Snapshot
restore tradeoffs vs RFS" section below before defaulting to #1.

| Pair                                | Notes                                                                                                                                                  |
|-------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| ES 1.x / 2.x → OS                   | **RFS (#2) only.** Snapshot format predates the OS restore path entirely.                                                                              |
| ES 5.x → OS                         | **RFS (#2) preferred.** Snapshot/restore is technically possible only via an ES 5→6→7→OS chain of intermediate restores — multi-hop, slow, risky. Don't. |
| ES 6.8 → OS 1.x                     | **RFS (#2) preferred.** Snapshot/restore *can* open the indices, but they retain ES 6 segment format and *type mappings* — you inherit pre-Lucene-8 perf and have to live with `_doc`-style mapping shape. RFS rewrites both. Use #1 only if the user explicitly accepts staying on legacy segment format. |
| ES 7.x → OS 1.x / 2.x               | **RFS (#2) preferred.** ES 7.10.2 → OS 1.x is the fork point and #1 works mechanically, but you still skip OpenSearch's post-fork segment improvements and don't get a chance to fix mappings. Use #1 only for very-large-data emergency cutovers where you'll re-migrate later. |
| ES 8.x → OS / AOS / AOSS            | **RFS (#2) or capture-replay (#4).** Snapshot/restore (#1) blocked entirely. `_reindex` (#3) unreliable.                                               |
| OS 1.x → OS 2.x                     | Cross-major OS upgrade. #1 works, but you skip segment-format improvements and any deprecated mapping warnings carry forward. **#2 if you care about long-term performance; #1 if you need a fast one-shot and accept a future re-migration.** |
| OS 2.x → OS 3.x                     | Same as above — cross-major. Same tradeoff: #1 fast, #2 clean.                                                                                         |
| OS 1.x → OS 1.x (minor upgrade)     | **#1 snapshot/restore is the right answer.** Same engine, same major. No tradeoff.                                                                     |
| OS 2.x → OS 2.x (minor upgrade)     | **#1.** Same.                                                                                                                                          |
| OS 3.x → OS 3.x (minor upgrade)     | **#1.** Same.                                                                                                                                          |
| OS 1.x / 2.x / 3.x → AOSS           | RFS (#2), Logstash (#5), or app dual-write (#7) only. AOSS has no snapshot, no `reindex.remote.allowlist`, no CCR.                                     |
| OS 1.x / 2.x → OS 1.x / 2.x         | CCR (#6) viable for ongoing replication; #1 for same-major one-shot.                                                                                   |

If the user names a version combo not in this table, fall back to the
matrix above and Phase 3 of the steering flow. The MA version registry
(`transformation/src/main/java/org/opensearch/migrations/VersionMatchers.java`
in the same repo) is the source of truth for what RFS actually accepts.

### Snapshot restore tradeoffs vs RFS

When a user (or a tool) reflexively suggests snapshot/restore for a
cross-major or cross-engine move, these are what they're trading
away vs running RFS:

- **Segment format stays old.** ES 5 indices on ES 6, ES 6 indices
  restored to OS 1.x, OS 1.x indices restored to OS 2.x — all keep
  their original Lucene segment format until force-merged or
  reindexed. You inherit older codecs, older bkd-tree formats,
  older docvalues encodings. Performance improvements that
  shipped *between* the source and target majors do not apply
  to restored indices. RFS writes fresh segments using the
  target's current codec.
- **Mapping shape stays old.** Multi-type mappings from ES 5/6
  restore as-is; deprecated field types restore as-is; pre-2.x
  knn fields restore in their old shape. Restored indices may
  refuse new writes if the target rejects the legacy mapping.
  RFS applies any configured transforms (type collapses, field
  drops, keyword↔text rewrites) on the way in.
- **No transform window.** Snapshot/restore is byte-shuffle; you
  can't rename indices, drop fields, or rewrite analyzers without
  a follow-up reindex. RFS does this in flight.
- **Cluster-state items are lost.** Templates may carry; ISM
  policies, snapshot policies, index-state objects, security
  users/roles, and watcher jobs do not transfer. RFS doesn't
  solve this either, but at least the migration-console gives
  you a concrete migration path for them.
- **Force-merge or reindex required afterward** to actually pick
  up target-version performance improvements. That second pass
  is more work than just running RFS once.

**Rule of thumb:** if the source and target share both engine
*and* major version, snapshot/restore is the right call. Anything
else, default to RFS unless the user has explicitly decided a
fast restore-now-fix-later cutover is acceptable.

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
- **Versions:** Same-major same-engine is the only unambiguous fit (OS 1.x→1.x, 2.x→2.x, 3.x→3.x). Cross-major (ES 6.8→OS, ES 7→OS, OS 1→OS 2 or 3) is *mechanically supported* but trades real things — see "What you give up vs RFS" immediately below. ES 1.x/2.x/5.x: don't (snapshot format too old). ES 8: blocked. AOSS target: blocked (no snapshot API).
- **What you give up vs RFS** when you pick #1 for a cross-major move:
  - Restored indices keep the source's Lucene segment format and codec. Performance improvements shipped between the source and target majors do not apply until you force-merge or reindex.
  - Restored indices keep the source's mapping shape. ES 5/6 multi-type mappings, deprecated field types, and pre-2.x knn shapes carry over verbatim. Some restored indices may refuse new writes against the target's stricter mapping validation.
  - No transform window — can't rename indices, drop fields, type-collapse, or rewrite analyzers. Anything you'd want changed needs a follow-up reindex pass.
  - In short: native restore is byte-shuffle. RFS rewrites segments through the target's current codec and lets you fix mappings on the way in. The cost difference is RFS setup time vs an eventual second reindex pass.
- **Source impact: medium.** Initial snapshot reads every segment off disk — saturates snapshot-thread-pool and disk I/O on a busy source. Incrementals after the first are cheap. Schedule the *first* snapshot during a quiet window if the source is anywhere near its I/O ceiling.
- No transformation hook. Source mappings/templates land verbatim on
  target. If the target rejects them (deprecated field types,
  unsupported analyzers), the restore fails and you start over.
- Tight version compatibility window. Source major must be ≤ target
  major + 1. ES 8 → any OS is unsupported (different snapshot format
  internals, no compatibility layer).
- AOSS as target is unsupported. AOSS has no snapshot API.
- Cluster-state items don't restore (templates can; ISM policies,
  watcher jobs, security users do not).

**Use when.** Same-engine same-major minor upgrade (OS 1.3 → OS 1.5,
OS 2.7 → OS 2.15). For cross-major moves it's only the right pick
when the dataset is large enough that RFS setup is genuinely a
blocker *and* the user has explicitly accepted that they'll later
need to reindex or force-merge to reclaim the perf improvements.

**Don't use when.** AOSS target. ES 8 source. Anything that needs
mapping rewrites, index renames, or schema-level changes. Cross-major
moves where the user wants the target's full performance profile —
use RFS instead.

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
- **Versions:** Widest matrix in this skill. Source ES 1.x through 8.x and OS 1.x/2.x/3.x. Target OS 1.x/2.x/3.x, AOS, AOSS. The version registry that authoritatively decides this lives at `transformation/src/main/java/org/opensearch/migrations/VersionMatchers.java`. If a user is on a version older than ES 1.7 (yes, that ancient), this is still the only viable path — they'll likely need to validate with MA's snapshot fixtures first.
- **Source impact: none.** RFS reads from a snapshot store (S3, NFS), never connects to the live source. This is the only backfill technique that doesn't pressure source at all. Pair with #4 if the *write* path also needs to be untouched during cutover.
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
- **Versions:** Source ES 1.x–7.x, OS 1.x/2.x. ES 8 source is unreliable — TLS/auth handshake mismatches with the OS reindex client matrix fail in ways that depend on exact patch versions; treat ES 8 as "use #2 instead." OS 3.x source works but is unusual. Target must accept `reindex.remote.allowlist`, which AOS does (via UpdateDomainConfig blue-green) and AOSS does **not** at all.
- **Source impact: high.** This is the technique most likely to take down an underprovisioned source. Target opens a continuous scroll context against source for the entire reindex window — search thread pool pressure, heap consumption (scroll contexts hold segment readers open), and saturated network egress. Throttle with `requests_per_second` or `slices` if source is anywhere near production load. **Don't run unthrottled `_reindex` against a customer-serving source.**
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
- **Versions:** Source ES 6.8 through 8.x, OS 1.x/2.x/3.x. Target OS 1.x/2.x/3.x, AOS, AOSS. Capture-proxy understands the HTTP request shapes from ES 6.8 onward; older ES sources don't have a viable capture path here (use #5 or #7 if you must do live cutover from ES <6.8).
- **Source impact: medium.** Capture-proxy adds a network hop in front of source for **every** request. CPU cost on the proxy is real; latency cost on the source request path is small but measurable (sub-millisecond on a healthy proxy, 1-5ms on a busy one). Read path is untouched once the proxy is bypassed. The *risk* impact is higher than the steady-state impact: misconfigured proxies can drop traffic.
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
- **Versions:** Bound by the Logstash version more than by source/target version. Logstash 7.x has the broadest input/output matrix (ES 1.x through 8.x as either side, OS 1.x/2.x/3.x via the `logstash-output-opensearch` plugin). Logstash 8.x dropped support for ES 1.x/2.x as inputs. AOSS as target requires sigv4-aware Logstash output (`logstash-output-opensearch` 2.0+).
- **Source impact: variable — and this is the column to read carefully.** If Logstash hooks an existing ingest pipeline (Kafka topic, Kinesis stream, app log) and forks writes to both clusters, source impact is **zero**. If Logstash uses the `elasticsearch` input plugin to scroll history off a live source, you're back to #3-level pressure on source. **Pick the input deliberately.**
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
- **Versions:** OpenSearch only, both sides. CCR plugin shipped in OS 1.1; supported on 1.1+, 2.x, 3.x. Not in Elasticsearch (ES has its own CCR feature, but it's a different system and not interoperable). Self-managed OS or AOS only — AOSS does not run plugins. Cross-major-version replication (e.g. OS 1.x → OS 3.x) works but is not the recommended bootstrap path; pair with #1 to establish the follower at the right version, then start CCR.
- **Source impact: low and ongoing.** Follower pulls translog operations from leader continuously. Steady-state cost is modest — proportional to your write rate, not to your data size. Burst cost when establishing a new follower can briefly look like #1 (initial bootstrap reads segments). Once steady, this is the lightest-touch live-replication option.
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
- **Versions:** Bound by the application's chosen client library, not by MA. Common reach: `opensearch-java` and `opensearch-py` clients target OS 1.x/2.x/3.x and AOS/AOSS; the `elasticsearch` clients (any major) speak to ES 1.x–8.x. If the app is on the official Elasticsearch 8.x client, that client *will not* speak to OpenSearch (license check); use `opensearch-java` or a compatible 7.x client. This is a one-line client swap but worth confirming during planning.
- **Source impact: none.** App writes to source as it always has, plus an additional write to target. Source sees no extra read pressure, no proxy in the request path, no scroll context. Failure modes are all on the *target* and *application* sides — never source. This is why teams reach for it when the source is a fragile legacy ES 5/6 cluster they're afraid to touch.
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

1. **Source is ES 1.x / 2.x / 5.x** → only **#2 RFS** is viable. Snapshot/restore (#1) snapshot format is too old; capture-replay (#4) capture-proxy doesn't speak the older HTTP shapes; CCR (#6) isn't in ES at all. If the user *also* needs zero-downtime, pair #2 with #5 Logstash or #7 app dual-write.
2. **Source is ES 8.x** → rule out #1 (snapshot format incompatible) and #3 (handshake unreliable). Default to **#2 RFS**. If zero-downtime is required, **#2 RFS + #4 capture-replay**.
3. **Target is AOSS** → rule out #1 (no snapshot API), #3 (no `reindex.remote.allowlist`), #6 (CCR not supported). Default to **#2 RFS**. Consider **#5 Logstash** if the team already runs Logstash. Consider **#7 app dual-write** only if hard zero-downtime is required.
4. **Hard zero-downtime requirement** → **#4 capture-and-replay**
   (if source can be proxied) or **#5 Logstash / #7 app dual-write**
   otherwise. Pair with **#2 RFS** for backfill.
5. **Dataset < 100 GB, same VPC, no transforms, source is ES 6.8/7.x or OS, target accepts `reindex.remote.allowlist`** → **#3 `_reindex` from remote**.
6. **OS → OS, ongoing replication wanted (not just migration)** →
   **#6 CCR**. (Both sides must be OS 1.1+.)
7. **Same-engine same-major minor upgrade (OS 1.x → 1.x, 2.x → 2.x, 3.x → 3.x), no transforms** → **#1 snapshot/restore** is the simplest path. *Cross-major* OS upgrades (1→2, 2→3) are mechanically supported by #1 but inherit old segment format and mappings — pick #2 RFS unless the user has explicitly accepted a follow-up reindex.
8. **Otherwise** → **#2 RFS via MA** is the safe default.

LEARN-path users (educational only) should see #1, #2, #3 in detail,
then a one-line mention of #4-#7 with a "ask if you want to know
more" tail.

## Common combinations

In practice these aren't always one-or-the-other:

- **#2 RFS + #4 capture-and-replay** — the canonical Migration
  Assistant deployment. RFS does the bulk backfill; capture-replay
  catches everything written during and after backfill. This is what
  the MA helm chart deploys by default.
- **#1 snapshot/restore + #6 CCR** — bootstrap a brand-new OS
  follower with snapshot/restore, then start CCR for ongoing sync.
  Less wire time than CCR initial sync from empty. Same-major OS
  pairs only — for cross-major OS, use #2 RFS as the bootstrap
  path so the follower starts on the target's segment format.
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
