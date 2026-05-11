# Phase 3 — Evaluate

**Goal:** lay out every viable migration approach with honest pros and
cons, then narrow to one strategy and propose it for sign-off.

Run on paths: POC, ANALYZE, MIGRATE. (LEARN skips this — goes to Phase 6
report instead.)

The evaluation is consultative, not prescriptive. The Companion knows
the trade-offs cold; the user picks once they understand them.

## The seven approaches

Each row: **what it is**, **fits when**, **breaks when**, **complexity**.
Mention all that apply to the source/target pair. Don't pretend an
inapplicable option exists.

### 1. Snapshot + restore (no transformation)

  - What: take a snapshot on source, restore it on target. Native to
    Elasticsearch and OpenSearch.
  - Fits: source major <= target major + 1, both self-managed or AOS,
    no schema/mapping changes needed, multi-GB to multi-TB scale.
  - Breaks: AOSS as target (not supported), ES 8.x source (not supported
    by OS), needs schema translation (no transform hook).
  - Complexity: lowest. One snapshot + one restore. No infra.

### 2. Reindex-from-Snapshot (RFS) via Migration Assistant

  - What: MA reads the source snapshot from a shared location (S3 or
    filesystem), reads index metadata, applies transforms if any, and
    bulk-writes documents into the target via its HTTP API.
  - Fits: any source/target pair MA supports (ES 5/6/7, OS 1/2/3 source;
    OS 1/2/3, AOS, AOSS target), needs transforms (mapping rewrites,
    index renames), needs to migrate to a *different major* than snapshot
    restore allows, large datasets (RFS scales horizontally with worker
    fleet).
  - Breaks: source you can't snapshot (rare), no place to put the
    snapshot, environment without k8s/EKS for the MA helm release.
  - Complexity: high one-time setup (helm chart + console + Argo +
    Kafka + Strimzi), low per-migration cost after that.

### 3. `_reindex` from remote (built-in)

  - What: target opensearch pulls documents directly from source using
    the built-in `_reindex` API with a remote source clause. No
    intermediate snapshot.
  - Fits: small to medium datasets (< 100 GB), source reachable over
    HTTP from target, you control target's `reindex.remote.allowlist`
    static setting, simple ES 7 -> OS X migrations.
  - Breaks: AOSS target (allowlist setting can't be set), wide-area
    network between source and target (slow, doc-by-doc), needs
    transforms beyond what reindex `script` can do, ES 8 source (TLS +
    auth model not supported by OS reindex client in some matrices).
  - Complexity: low. One curl. But the static `reindex.remote.allowlist`
    needs a target restart on most managed services.

### 4. Capture-and-replay (Migration Assistant)

  - What: MA capture-proxy sits in front of source, mirrors live writes
    to Kafka. After backfill, traffic-replayer drains the buffered
    writes into the target. Lets you cut over with zero data loss while
    backfill runs in parallel.
  - Fits: zero-downtime cutover required, can place capture-proxy in
    the source request path, OK with proxying live traffic.
  - Breaks: can't add a proxy hop in front of source (most managed ES),
    write rate exceeds replayer throughput (Kafka backs up), POC scale
    (way too much infra for a demo).
  - Complexity: highest. Capture-proxy (the only MA image NOT on public
    ECR), Kafka via Strimzi, traffic-replayer, plus everything RFS
    needs.

### 5. Logstash / Fluentd dual-write

  - What: a stream processor reads from source (or sits in app's write
    path) and writes to both source and target. Cut over once target
    is steady.
  - Fits: app teams already have Logstash, willing to redirect writes,
    AOSS target (Logstash supports AOSS sigv4 output), zero-downtime.
  - Breaks: there's no clean "starting point" for backfill (Logstash
    isn't built to replay history at scale), schema drift between
    source and Logstash output config silently corrupts data.
  - Complexity: medium. New service, new config, new operational story.

### 6. Cross-cluster replication (CCR)

  - What: native OpenSearch CCR plugin: target is a follower of source.
  - Fits: OS -> OS only, both self-managed or both AOS in compatible
    matrix, sustained replication needed (not just one-shot migration),
    user already has CCR plugin licensed/installed.
  - Breaks: any ES source (not supported), AOSS as either side (not
    supported), one-shot migration (CCR is overkill — just reindex).
  - Complexity: medium. CCR setup is straightforward but follower-index
    semantics are easy to mis-operate.

### 7. Application-layer dual-write

  - What: app writes to both clusters from the application code, reads
    from old until target is caught up via separate backfill.
  - Fits: app team owns the change, controls all writers, can tolerate
    a careful cut-over week, no other approach will work (e.g. AOSS
    target with hard zero-downtime constraint and source can't be
    proxied).
  - Breaks: many writer paths, batch jobs that bypass app, schema drift
    in either direction.
  - Complexity: highest organizationally, lowest technically.

## How to choose

Walk it interactively. Order matters:

  1. Is target AOSS? -> rule out #1, #6. Recommend #2 RFS or #5 Logstash.
  2. Is source ES 8? -> rule out #1. Recommend #2 RFS.
  3. Need zero-downtime? -> #4 capture-replay or #5/#7 dual-write.
     Otherwise drop them — they're a tax you don't owe.
  4. Total dataset < 100 GB and source reachable from target? -> #3
     `_reindex` is the simplest live option.
  5. Otherwise default to #2 RFS via MA.

For the LOCAL POC path: default to #2 RFS via MA on kind. The POC's
job is to teach the *real* tool, not the simplest tool. (See
`TrafficCapture/dockerSolution/`.) Drop to #3 `_reindex` only if the user is on a
machine that can't run kind + 12 GB of MA infra.

## What to propose to the user

After picking one strategy, write 4-7 bullets for sign-off:

  1. Strategy (one of the seven above).
  2. Execution backend: MA helm release on kind/EKS, raw HTTP, Logstash, …
  3. Index handling: 1:1 names, renames, filter pattern. Default:
     migrate everything except `.kibana*`, `.opendistro*`, `.opensearch*`,
     and other dot-prefixed system indices.
  4. Mapping/template handling: any translation needed? Cite the target
     pack capability profile by section.
  5. Auth model on source and target.
  6. The hard incompatibilities found in Phase 2 and how this plan
     handles each.
  7. What "done" looks like (Phase 5 validation criteria).

Then:

> Look right? Anything you want to change before I start?

Wait for explicit sign-off.

## Exit criteria

User has signed off on a written plan. Move to Phase 4 (POC, MIGRATE)
or Phase 6 (ANALYZE).
