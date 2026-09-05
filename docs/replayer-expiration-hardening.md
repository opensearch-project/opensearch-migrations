# Hardening Replayer Expirations

**Status:** Design — Phase 0 shipped (PR #3225), Phases 1 & 2 awaiting review

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [How Time Works Today](#2-how-time-works-today)
3. [Livelock Taxonomy](#3-livelock-taxonomy)
4. [Phase 0 — Correctness Fixes (shipped)](#4-phase-0--correctness-fixes-shipped)
5. [Phase 1 — Epsilon Lookahead + Scanner + Liveness + Proxy Cap](#5-phase-1--epsilon-lookahead--scanner--liveness--proxy-cap)
6. [Phase 2 — Decoupled Tuple API + Response Recreation](#6-phase-2--decoupled-tuple-api--response-recreation)
7. [Rejected Approaches](#7-rejected-approaches)
8. [Dependencies on PR #3231](#8-dependencies-on-pr-3231)

---

## 1. Problem Statement

The replayer can permanently stall its Kafka offset commit under two categories of
conditions:

**Category A — bugs (Phase 0):** Two code paths in the accumulator could permanently orphan
offsets in `OffsetLifecycleTracker`, regardless of configuration. Both are fixed as of
PR #3225. PR #3231 identifies two more in the same family — see §8.

**Category B — design limits (Phase 1):** A connection whose source-time span exceeds
`lookahead − connectionTimeout` cannot be expired by the current timestamp-driven mechanism
if we shrink the lookahead to reduce memory pressure. Today's `lookahead(400) > timeout(360)`
masks this, but at the cost of buffering 400s of traffic — unacceptable for memory-constrained
deployments or adversarial traffic.

The operator posture throughout: **prefer OOM over silent data loss**, but prefer neither.
A commit must be justified by *evidence* that the records are not needed, never by
*impatience* (elapsed time).

---

## 2. How Time Works Today

Two independent clocks drive the replayer.

### Replay clock (wall-clock derived)

```
sourceTime = sourceTimeStart + (wallNow − systemTimeStart) × speedupFactor
```

Marches continuously regardless of what data is available. `TimeShifter` owns the mapping;
`setFirstTimestamp` pins the origin exactly once (CAS).

### Frontier (gates reads)

`BlockingTrafficSource` blocks reads while `stopReadingAtRef < lastTimestampSecondsRef`.
`stopReadsPast(pointInTime)` advances the barrier to `pointInTime + bufferTimeWindow`
(the lookahead), monotonically via `Utils.setIfLater`.

Two drivers push the frontier:

1. **Completed work** — `ReplayEngine.hookWorkFinishingUpdates`: each finished send
   (success *or* failure — it hangs off `whenComplete`) pushes `lastCompletedSourceTimeEpochMs`
   and calls `stopReadsPast`.
2. **Idle updater** — `ReplayEngine.updateContentTimeControllerWhenIdling`: on a timer
   (`lookahead/8`), pushes the barrier toward the replay clock — **but only when
   `!isWorkOutstanding()`**.

### Expiry (drives commit)

`ExpiringKeyQueue.expireOldSlots` fires from `addObservationToAccumulation` — i.e. only when
an observation is processed. Expiry is **data-driven, not timer-driven.** It computes:

```
cutoff = largestObservedSourceTimestamp − connectionTimeout
```

and sweeps accumulations whose `newestPacketTimestampInMillis < cutoff`. Note this keys off the
**newest** packet in an accumulation, not the first — a connection dribbling one byte per second
never becomes eligible while it keeps dribbling.

An accumulation's offsets commit when it completes, is expired, or its connection closes.

### Load-bearing invariant: commit ≠ frontier

A zombie connection (no EOM, no close) holds its **offsets** (pinning the commit) but was
**never scheduled as work** (no EOM → nothing to send), so it does *not* suppress the
idle-updater and does *not* hold the frontier. The frontier keeps climbing on the wall clock →
the barrier keeps rising → newer records are read → their timestamps advance the expiry cutoff →
the zombie expires → offsets release → commit advances.

This decoupling is why today's design (with `L > T`) does not livelock on zombie connections.

It is also precisely why shrinking `L` to `ε` breaks expiry. The cutoff sits at
`largestObservedTs − T`, and reads are capped at `frontier + L`, so:

```
cutoff ≈ frontier + (L − T)
```

With `L=400, T=360` the cutoff runs **40s ahead** of the frontier — expiry outruns replay.
With `L=ε=30, T=360` it runs **330s behind** — reads can never reach
`zombie.lastPacketTs + T`, so timestamp-driven expiry structurally cannot fire.

**Consequence: ε-lookahead cannot ship without a replacement expiry trigger.** That is the
scanner in §5.2. The `lookahead > timeout` CLI validation exists to enforce this coupling.

---

## 3. Livelock Taxonomy

### Genuine locks (Phase 0 targets — now fixed)

**F1 — connectionException offset orphan.** A request spanning ≥2 Kafka records
(`flushIfNeeded` mid-request, common for bulk bodies) followed by a `connectionException`
observation permanently orphaned prior records' offsets. The handler held the current TSK into
the rrPair, could not rotate (state is `ACCUMULATING_READS`, not `WRITES`), then
`resetForNextRequest()` nulled the rrPair — discarding the held list without committing.
The end-of-`accept()` fallback only committed the exception-bearing record's TSK. Prior
offsets stayed in `OffsetLifecycleTracker`'s priority queue forever, so every later commit
returned `BLOCKED_BY_OTHER_COMMITS` and that partition never committed again.
Restart-proof — re-delivery replays the same path deterministically.

**F2 — expiry double-commit crash.** A keep-alive connection (`numberOfResets > 0`) whose
second request was partially accumulated and then expired: `fireAccumulationsCallbacksAndClose`
called `onTrafficStreamsExpired` (committing held TSKs), then the `finally` block fired
`onConnectionClose` with the **same** list, because the early `return` does not skip `finally`
in Java. The second `removeAndReturnNewHead` threw `IllegalStateException`. Since
`commitKafkaKey` only *stages* offsets into `nextSetOfCommitsMap` (the real `commitSync` runs
on the next poll/touch), a crash before that flush means Kafka never learns → restart
re-delivers → deterministic crash loop.

### Not locks (bounded-slow)

**Concurrency-gate saturation.** All `maxConcurrentRequests` permits held by slow sends.
Self-heals: sends are bounded by `MAX_RETRIES(4) × targetServerResponseTimeoutSeconds(150)`,
so permits free and queued work proceeds.

**Frontier gated by the slowest outstanding send.** The idle-updater is suppressed while work
is outstanding, so the frontier advances at `min(replay-clock, slowest-send)`. Under
ε-lookahead this coupling tightens (less slack to hide behind). Not a lock — just slower.
The scanner must not depend on frontier advancement for its own verdicts.

### Not locks but look like them

**Legitimately long transactions.** A connection with minutes between request and response
holds its offsets for that span — by design. Consumer lag grows and then resolves when
replay-time reaches the response. Any expiry mechanism must distinguish "no follow-up exists"
(dead) from "follow-up exists but replay hasn't reached it" (alive). The scanner's verdict is
therefore **structural**, never temporal.

---

## 4. Phase 0 — Correctness Fixes (shipped)

Merged in **PR #3225** (`403c103ba`). Both fixes live in
`CapturedTrafficToHttpTransactionAccumulator.java`.

### F1: commit held TSKs before reset in the connectionException handler

The shipped fix reuses the existing `handleDroppedRequestForAccumulation` helper — which
commits every held TSK via `onTrafficStreamIgnored` and then calls
`resetToIgnoreAndForgetCurrentRequest()` — rather than the bare `resetForNextRequest()`:

```java
} else if (observation.hasConnectionException()) {
    rotateAccumulationIfNecessary(trafficStreamKey.getConnectionId(), accum);
    exceptionConnectionCounter.incrementAndGet();
    // Commit all held TSKs before nulling the rrPair. Without this, offsets from
    // prior TrafficStream records are permanently orphaned in OffsetLifecycleTracker.
    // Note: we do NOT holdTrafficStream(trafficStreamKey) first — that would
    // double-commit the current record (once here, once by the end-of-accept()
    // fallback). The fallback commits the current record.
    handleDroppedRequestForAccumulation(accum);
    ...
    return Optional.of(CONNECTION_STATUS.ALIVE);
```

Two details worth preserving, both learned the hard way:

- **Do not `holdTrafficStream` the current TSK here.** An earlier draft did, which
  double-committed the exception-bearing record (once in the handler's `forEach`, once in the
  end-of-`accept()` fallback) — an `IllegalStateException` in production.
- **The assertion near the end of `accept()` needed no change.** Because the fix routes
  through `resetToIgnoreAndForgetCurrentRequest()`, the resulting state is
  `WAITING_FOR_NEXT_READ_CHUNK`, already in the asserted set. An intermediate draft that used
  `resetForNextRequest()` left state `ACCUMULATING_READS` and required widening the assert;
  that widening was correctly reverted.

### F2: prevent double-commit in fireAccumulationsCallbacksAndClose

```java
if (accumulation.hasRrPair()) {
    listener.onTrafficStreamsExpired(
        status,
        accumulation.trafficChannelKey.getTrafficStreamsContext(),
        Collections.unmodifiableList(accumulation.getRrPair().trafficStreamKeysBeingHeld)
    );
    // Null the rrPair so the finally-block's onConnectionClose (which always runs
    // despite the return) does not double-commit the same TSKs —
    // getTrafficStreamsHeldByAccum returns List.of() when hasRrPair()==false.
    accumulation.resetForNextRequest();
}
return;
```

### Why the exhaustive test never caught either bug

`ExhaustiveCapturedTrafficToHttpTransactionAccumulatorTest` *does* assert commit coverage
(`trafficStreams.length == indicesProcessedPass1.size() + indicesProcessedPass2.size()`).
The assertion was fine; it never saw a triggering input:

- the generator had no `connectionException` or `close` directives — every generated
  connection completed a full request/response cycle;
- every observation used `Instant.EPOCH`, so no timestamp gaps ever existed and the expiry
  sweep never fired.

PR #3225 extended the generator (`OffloaderCommandType.ConnectionException/Close`,
matching `ObservationDirective` factories, terminal-directive handling in `makeTrafficStream`,
and `ObservationType.ConnectionException` in the classifier). With the F1 fix reverted the
exhaustive suite fails **139 of 487** cases; with it, 487/487 pass. Note this suite is
`@Tag("longTest")` — it runs under `gradlew slowTest`, *not* `gradlew test`, which is why
earlier full-suite runs looked green.

**Scope caveat:** `accumulateWithAccumulatorPairAtPoint` verifies records are *emitted through
accumulator callbacks*, not *committed to Kafka*. Commit-machinery correctness is a separate
concern at the `TrafficReplayerCore` / `TrackingKafkaConsumer` boundary. F1 is catchable at the
accumulator layer only because the accumulator fails to emit at all.

---

## 5. Phase 1 — Epsilon Lookahead + Scanner + Liveness + Proxy Cap

Ships as one unit — epsilon cannot ship without the scanner (see §2, "load-bearing invariant").

Four pieces, and it is worth being clear up front about which problem each one solves, because they
are easy to mistake for redundant:

| Piece | Solves |
|---|---|
| **Epsilon** (§5.1) | Memory: stop buffering 400s of traffic |
| **Scanner** (§5.2) | Reaching proof that is beyond the read barrier, *without* buffering payloads |
| **Proxy cap** (§5.3) | Making "how far must I look?" a finite, operator-chosen number |
| **Liveness snapshots** (§5.4) | Turning absence-of-evidence into an explicit, offset-ordered statement by the proxy |

The scanner and liveness snapshots are complementary, not alternatives. Liveness covers
*proxy alive, connection dead* — the common case — cheaply and exactly. The scanner covers
*proxy dead*, where no snapshot will ever arrive, and remains the only way to reach either kind of
proof when it sits past `frontier + ε`.

### 5.1 Just-in-time lookahead (epsilon)

Redefine `--lookahead-time-window` from "read-ahead buffer" to "smoothing margin."
Default: **30s** — enough to hide Kafka fetch and transform latency, nothing more. The
`lookahead > timeout` validation (currently `exit(4)`) is relaxed, because the scanner
replaces the guarantee that validation was protecting.

**Effect:** buffered records collapse from ~400s of traffic to ~30s. Memory held = the real
working set (in-flight connections' accumulated bytes) + ε of read-ahead.

> **Depends on the `isWorkOutstanding()` guard.** This memory bound holds only because the
> frontier stays coupled to completed work — a stalled target slows reading. PR #3231 proposes
> removing that guard, which decouples read-ahead from progress and makes it a pure function of
> the replay clock. If that lands, ε alone no longer bounds memory and this section needs an
> explicit read-ahead cap. See §8.



**Frontier advancement under ε:** the idle-updater becomes the primary driver, pushing the
barrier toward the replay clock whenever no work is outstanding. A request spanning 360s of
source time is read *incrementally* as the replay clock crawls through it — no expiry needed,
just patience. Its accumulated bytes are unavoidable working set.

Scanning is **continuous**, not stall-triggered: since ε ≪ the expiration window, the sooner
garbage is expired the lower the memory pressure, and steady-state scanning makes replayer and
Kafka load predictable instead of bursty.

### 5.2 Continuous scanner

A **metadata-only scan-ahead cursor** on the *same* consumer and the *same* assigned
partitions. Not a separate consumer group.

**Implementation** — one Kafka consumer, two logical cursors:

- **Replay cursor** — the normal poll position driving replay and commits.
- **Scan cursor** — `seek()`-based. After each poll batch, seek ahead from the replay position,
  poll metadata only (connectionId + timestamps + observation types; **payloads discarded**),
  build the set of connectionIds seen in the window, then `seek()` back to the replay position
  before the next real poll.

`poll()` returns records **commingled across all assigned partitions**, so one sweep covers
every partition the consumer owns. Partition affinity is guaranteed *by construction* —
both cursors belong to the same consumer, so they can never disagree about assignment. This is
the decisive argument against a second consumer group (§7).

**Expiry verdict.** For the connection at the commit head (the blocker), if the scanner finds
**no observation for that connectionId** anywhere in the scan window → **confirmed dead.**
Mark it expired by pushing a synthetic expire event through the accumulator, which in turn
clears the head-of-line blocker and releases its offsets. If follow-up records *do* exist →
**alive**; do not expire, let replay reach them naturally.

**Two termination conditions, either of which ends a scan.** The scanner stops as soon as it can
justify a verdict:

1. **Liveness omission (cheap, exact).** Two consecutive snapshots from the blocker's `nodeId` on the
   blocker's partition both omit the connection, with its last record preceding the earlier of the
   two. Confirmed dead. Distance: `2 × snapshotInterval` (see §5.4).
2. **Window exhausted (expensive, inferential).** No observation for the connectionId anywhere in
   `scanWindow = connectionTimeout + maxConnectionDuration` (the proxy cap from §5.3). Confirmed
   dead. This is the fallback for a **dead proxy**, where condition 1 can never fire because no
   further snapshots exist.

Anything short of one of these is `Inconclusive` — do not expire. In particular, *absence of
snapshots is not evidence*: the scanner cannot distinguish a crashed proxy from a stalled one (GC
pause, partitioned from Kafka, backed-up producer), so silence never authorizes a commit. Only an
explicit omission or an exhausted window does.

Condition 1 is why the scanner survives liveness snapshots rather than being replaced by them, and
why liveness does not let us raise ε instead. With a 30s snapshot interval the proof sits up to 60s
of source time ahead of the frontier. Reaching it by raising ε to 60s would mean buffering 60s of
payloads — which, as with the original 400s, is exactly the memory problem we are trying to remove.
The scan cursor reaches the same offset holding **metadata only**, so ε stays at ~30s independently
of how far the proof is. Proof distance and buffered bytes are decoupled; that decoupling is the
scanner's real job.

**Two expiry modes (invariant — do not conflate these):**

| Mode | Trigger | Commit? | Rationale |
|---|---|---|---|
| **Confirmed dead** | Scanner finds no follow-up within the bound | **Yes** | Evidence-based; the records are garbage and must not be re-read |
| **Out of runway** | Partition reassignment or shutdown | **No** | Whoever picks up the partition must go back to the records that weren't completely handled |

The second row is a hard requirement: when removing a partition we **must not** commit
messages that another replayer session would otherwise handle. This matches the existing
`TRAFFIC_SOURCE_READER_INTERRUPTED` suppression in `commitTrafficStreams`.

**Today's status enum cannot express both rows.** `ReconstructionStatus.EXPIRED_PREMATURELY` is
*not* in `commitTrafficStreams`' suppress set (which holds only `CLOSED_PREMATURELY` and
`TRAFFIC_SOURCE_READER_INTERRUPTED`), so any expiry routed through it commits. Phase 1 must
therefore either add a status that distinguishes confirmed-dead from out-of-runway, or pass the
commit decision explicitly alongside the status. This was surfaced by reviewing PR #3231, whose
force-expiry path fires `EXPIRED_PREMATURELY` and consequently commits — see §8.

### 5.3 Capture proxy: max connection/request duration

Bound the connection span at the source, so the replayer's scan window is a finite,
operator-chosen quantity rather than an attacker-chosen one. This also defends against
"low-and-slow" adversarial clients whose only goal is to OOM the replayer.

**New CLI flag** on `CaptureProxy.Parameters`:

- `--maxConnectionDuration <seconds>` — maximum total time a frontside connection may stay
  open. Default off (0). Recommended: keep in agreement with the replayer's scan window; the
  two values are a matched pair and should be documented as such.

**Enforcement:** a Netty `ScheduledFuture` armed on `channelActive`; on expiry write
`addCloseEvent(Instant.now())` to the offloader and close the channel. Dedupe the logging
(reuse the `UnauthenticatedClientLogDeduper` pattern).

**Effect on the replayer:** a capped connection produces a real `CloseObservation`, so the
accumulator treats it as a normal close and its offsets commit promptly — no scanner
involvement needed for the common case.

**What the cap is still load-bearing for, once §5.4 exists.** Liveness snapshots prove death without
reference to the cap, so the cap is no longer required for the ordinary verdict. It remains required
for the **dead-proxy** case: with no further snapshots from that `nodeId`, the only available proof is
"nothing in the window," and that means nothing unless the window is finite. So the cap stays
mandatory for epsilon mode, but its role narrows from *the* bound on every verdict to the bound on
the residual case.

### 5.4 Capture proxy: liveness snapshots

**The problem this solves.** Both the scanner's original verdict and the timestamp sweep infer death
from *absence*: nothing showed up, therefore nothing exists. That inference is only as good as the
bound on how far you had to look, which is why it drags in the proxy cap, and why the window is
minutes wide. The proxy already knows the answer exactly — it holds the channels. Having it *say so*
converts an inference into a statement.

**Mechanism.** Every `snapshotInterval` (default **30s**), each capture proxy emits, for each
partition `P` in its shard set (§5.4.4), one record:

```proto
message ProxyLivenessSnapshot {
  string nodeId = 1;            // the emitting process's UUID
  int32 partition = 2;          // the partition this record is being written to
  int64 emittedAtMillis = 3;    // diagnostic only; never used in a verdict
  repeated bytes idleConnections = 4;  // suffixes, see 5.4.3
}
```

Semantics: *"I, node X, at this point in partition P's log, hold exactly these idle open connections
whose traffic goes to P."* Everything else about X on P is either closed or actively flushing.

**Why only *idle* connections.** A connection that is actively flushing records announces its own
liveness through the data stream — listing it adds bytes and says nothing new. Only a connection that
has gone quiet is ambiguous to the replayer, so only those need naming. Define idle as *no bytes
flushed since the previous snapshot*.

These records are a **new stream-level message type on the traffic topic**, gated by the incompatible
`TrafficStream` version bump: the new proxy always emits them, there is no feature flag, and the
topic is assumed empty at cutover so no mixed-mode reader exists.

#### 5.4.1 Absence is proved by offsets, not by clocks

The naive reading — "the snapshot omits C, so C is dead" — is wrong, and wrong in the direction that
loses data. Two hazards:

- **Flush latency.** C may have flushed bytes that had not yet reached Kafka when the snapshot was
  built, so C's records can land at offsets *after* the snapshot that omits it.
- **Weakly-consistent iteration.** The snapshot is built by iterating a `ConcurrentHashMap`. An entry
  added while the iteration is in flight may legitimately be missed even though the connection is
  live.

Both dissolve if the verdict is stated in terms of **offset order on a single partition** rather than
time. The rule:

> Connection `C` of node `X` is confirmed dead on partition `P` when two consecutive snapshots from
> `X` for `P`, at offsets `K₁ < K₂`, both omit `C`, **and** `C`'s last record on `P` is at an offset
> `< K₁`.

Why this holds. Since `C`'s traffic and `X`'s snapshots for `P` are both in `P`, their relative order
is total and observable — no clock is involved, and "hadn't flushed yet" is not expressible, because
a flush that happened would appear at some offset and the rule reads offsets, not timestamps. If `C`
were open-and-idle throughout, `ConcurrentHashMap` would have returned it in at least one of two
complete iterations (an entry present for the whole traversal is always visited; only entries added
mid-traversal may be skipped). If `C` were open-and-active, it would have flushed a record after
`K₁`. Neither happened, so `C` was removed from `X`'s table — the channel is gone. Its accumulation
at the replayer will never be completed by anyone, which is exactly the condition for expiring it.

The two-consecutive rule is what makes the effective timeout `2 × snapshotInterval` (60s at the
default) rather than one interval.

**The partition number is stamped in the record and asserted on read.** The entire argument rests on
`C`'s records and `X`'s snapshot being in the *same* partition. If a partition-count change, a
producer-side partitioner change, or a shard-set bug ever broke that, the offset ordering would be
meaningless while still *looking* like proof. So the reader compares `snapshot.partition` against the
partition it was consumed from and, on mismatch, refuses the verdict and halts loudly. A cheap
assertion guarding the one assumption whose silent failure commits live data.

#### 5.4.2 Empty snapshots are a statement, not a heartbeat

A snapshot listing zero connections is retained and emitted. It is not filler: *"I hold no idle open
connections on P"* is a positive structural claim, and it is what lets the last zombie on an
otherwise quiet partition be expired. Contrast with emitting nothing, which is indistinguishable from
a crashed or stalled proxy and therefore proves nothing (§5.2). Empty snapshots are also tiny, so
they flush and land promptly.

**Liveness records bypass the obligation model entirely.** They create no accumulation, hold no
offsets, and never appear at the commit head; their own offsets commit as soon as they are decoded.
Routing them through the ordinary record lifecycle would let the liveness stream pin the very commit
head it exists to unblock.

#### 5.4.3 Sizing

The constraint is a hard one and it is *pre-compression*: `KafkaCaptureFactory` allocates
`ByteBuffer.allocate(messageSize − KAFKA_MESSAGE_OVERHEAD_BYTES)` and enforces the limit against that
buffer, so producer-side compression shrinks what crosses the wire but not what has to fit. Default
`maximumTrafficStreamSize` is 1 MiB.

`connectionId` is `ch.id().asLongText()` — 60 hex-and-dash characters, e.g.
`0242acfffe13000a-0000000a-00000005-1eb087a9beb83f3e-a32794b4`, structured as:

| Field | Bytes | Varies by |
|---|---|---|
| machineId | 8 | host — **constant within a process** |
| pid | 4 | process — **constant within a process** |
| sequence | 4 | connection (monotonic) |
| timestamp | 8 | connection (`Long.reverse(nanoTime()) ^ currentTimeMillis()` — scrambled, incompressible) |
| random | 4 | connection |

So 12 of the 28 encoded bytes are identical for every connection in the snapshot, and the text form
doubles the rest. Encoding raw bytes with the per-process prefix elided once per record:

| Encoding | Per entry | Entries in 1 MiB |
|---|---|---|
| Full 60-char text | ~62 B | ~16,900 |
| Raw bytes, prefix elided | ~17 B | ~61,600 |

A proxy saturating 32K ephemeral ports needs 32,768 entries: **1.94 MiB** as text (over the cap),
**557 KiB** raw (comfortable). Sharding (§5.4.4) divides this by the number of partitions the node
uses, so per-record counts are far smaller in practice; the raw encoding is what makes the worst case
safe rather than merely likely-to-fit. Further headroom exists if ever needed — the sequence numbers
are near-monotonic and would delta-encode well — but is not worth designing for now.

Two related questions, answered: **compression does not help this problem** (it applies after the
enforced limit), and **there is no destination address to drop** — `BindObservation` /
`ConnectObservation` carry no address at all in the current proto, the field is commented out.

#### 5.4.4 Node-sharded partitions

Emitting to every partition costs `N × M` records per interval, which scales badly in the wrong
dimension: every replayer partition pays for every node in the fleet. At `N=32`, `M=1024`, 30s
interval that is ~1,090 records/s of pure overhead.

Instead, each node writes only to a shard set `S(X)` of `K` partitions (default **16**), derived
deterministically from `hash(nodeId)`, and — this is the whole trick — it writes **both its traffic
and its liveness snapshots** only to `S(X)`. Traffic goes to `S(X)[hash(connectionId) mod K]`, set
explicitly via the four-argument `ProducerRecord` constructor rather than left to key hashing.

| | Records/interval | Records/s (30s) | Per partition |
|---|---|---|---|
| Broadcast (`N × M`) | 32,768 | ~1,090 | ~1.07/s |
| Sharded (`N × K`, K=16) | 512 | ~17 | ~0.017/s |

**The requirement is self-consistency, not coverage or disjointness.** All the proof in §5.4.1 needs
is that a node's traffic and its snapshots land in the same partitions — which is true by
construction, since one set defines both. It does **not** need the shard sets to be disjoint, to
cover all `M` partitions, or to be recomputed as the fleet changes. That is what makes this work
without knowing `N`: nodes can be added or removed freely, collisions between shard sets are
harmless, and only `M` and `K` are fixed configuration. `M` becomes fleet headroom rather than a
correctness parameter — `M ≈ maxNodes × K` keeps overlap light.

A restart reshuffles `S(X)`, since the nodeId is new (§5.4.5). That costs nothing: a new process has
all-new connections, so there is nothing to keep co-located with anything.

Repartitioning the topic is out of scope — it invalidates the co-location assumption, which the
§5.4.1 assertion will catch and halt on rather than silently mis-prove.

#### 5.4.5 nodeId stays a fresh UUID per process

`CaptureProxy.getNodeId()` returns `UUID.randomUUID().toString()` — a new identity on every start.
That is **deliberate and must not be "fixed" to a stable per-host id**, even though a stable id looks
strictly more useful (a restarted proxy could then prove its predecessor's connections dead by
omitting them).

The reason is fencing. With a stable nodeId, consider a proxy that is not dead but merely stalled — a
long GC pause, partitioned from Kafka, or a producer backed up behind a slow broker — while a
replacement instance comes up carrying the same id. The replacement's snapshots omit the stalled
process's still-open connections. The replayer proves them dead, commits past their records, and then
the original process recovers and flushes the rest at higher offsets. Committed means skipped on
restart, so the tail of every one of those connections is **silently lost**. A per-process UUID makes
that unrepresentable: two processes never share an identity, and a snapshot can only ever speak about
the connections of the process that emitted it. The nodeId *is* the fencing token.

The framing that makes this obvious: when a proxy restarts, every connection it held has already been
severed. For every purpose that matters here, the new process is a new host.

**The consequence, stated plainly.** A dead proxy's connections can never be expired by liveness,
because no successor is authorized to speak for them and the dead process emits nothing further. That
residue belongs to the scanner's window-exhausted verdict (§5.2, condition 2), which is the other
reason both mechanisms ship.

### 5.5 Metrics

Most of this already exists via the OTel context hierarchy (`RootReplayerContext` →
`KafkaRecordContext` → `TrafficStreamsLifecycleContext` → `ReplayerHttpTransactionContext`)
and the heartbeat loggers. Confirm and fill gaps:

| Metric | Source | Exists? |
|---|---|---|
| In-flight requests | `requestWorkTracker.size()` | ✔ heartbeat |
| In-flight connections | `liveStreams.values().count()` | ✔ heartbeat |
| Commit-head age | `OffsetLifecycleTracker.peekHeadMetadata().addedAt` | ✔ heartbeat |
| Commit-head connectionId | `peekHeadMetadata().connectionId` | ✔ heartbeat |
| Backpressure engaged | `BlockingTrafficSource` heartbeat | ✔ |
| Buffered records (ε utilization) | `kafkaRecordsLeftToCommitEventually` | ✔ |
| Scanner: window distance, deaths confirmed/cycle, scan latency | — | **NEW** |
| Scanner: verdicts by kind (liveness-omission / window-exhausted / inconclusive) | — | **NEW** |
| Proxy: connections killed by duration cap | — | **NEW** |
| Proxy: snapshot emit latency, entries per snapshot, snapshot bytes (p99 vs. the 1 MiB cap) | — | **NEW** |
| Proxy: partitions in shard set, snapshots dropped by producer backpressure | — | **NEW** |
| Replayer: nodeIds with live obligations but no snapshot in >2 intervals (suspected dead proxies) | — | **NEW** |
| Replayer: liveness partition-mismatch assertions (§5.4.1) — must be zero | — | **NEW** |

Scanner metrics should be top-level counters/histograms, not spans — it isn't per-request work.

**Known caveat on `peekHeadMetadata().addedAt`:** it records *wall-clock at insertion*, i.e.
roughly when the blocker started. The design's original motivation was tracking the
**backside ceiling** — the *last observed source timestamp* for the blocking sequence, which is
what determines when the blocker becomes expiry-eligible. These are different quantities and
`addedAt` is not a substitute. The scanner sidesteps the need for it by answering the
structural question directly, but any diagnostic that claims "this blocker should have expired
by now" needs the backside ceiling, not `addedAt`.

---

## 6. Phase 2 — Decoupled Tuple API + Response Recreation

**Explicitly out of scope:** adding a new data store, or changing how we write to S3. What
this phase does is refactor the *API* so a future granular writer becomes possible, and
preserve today's S3 behavior exactly.

### 6.1 Four-part tuple accumulator interface

Refactor the tuple-write API so each component of a tuple is written against the tuple's id,
with its own asynchronous completion that can independently bump the commit:

```java
interface TuplePartWriter {
    /** Write the source request; callback fires when durably stored. */
    CompletableFuture<Void> writeSourceRequest(UniqueRequestId id, HttpMessageAndTimestamp request);

    /** Write the source response; callback fires when durably stored. */
    CompletableFuture<Void> writeSourceResponse(UniqueRequestId id, HttpMessageAndTimestamp response);

    /** Write the target request+response pair; callback fires when durably stored. */
    CompletableFuture<Void> writeTargetExchange(UniqueRequestId id, TransformedTargetRequestAndResponseList exchange);

    /** Write the comparison/diff; callback fires when durably stored. */
    CompletableFuture<Void> writeComparison(UniqueRequestId id, ComparisonResult comparison);
}
```

**Source request and source response get separate commit callbacks.** The current S3 writer
implements this interface by bumping *both* callbacks after the whole record is accumulated —
byte-for-byte today's behavior, no functional change until a granular writer exists.

### 6.2 Why decoupling helps

Today the replayer doesn't commit until the source **response** has been fully received. A
response can arrive minutes after its request. That holds the commit head far longer than
necessary, which means more stress on the replayer and more re-replay after a crash — and
crashes are likelier precisely when the replayer is stressed.

One Kafka partition still has exactly one commit watermark, so releasing a request's offsets
only helps when doing so lets the **lowest** offset advance. That is common in practice: a
later connection's request often sits at a lower offset than an earlier connection's late
response. Decoupling shrinks the window in which the head is pinned by a slow response.

### 6.3 Response recreation by id on restart

This is the hard part, and it is a prerequisite for §6.2 rather than an optional extra.

Once a request's offsets commit before its response's, a crash means the **response records
are re-delivered while the request records are not.** The replayer must then reconstruct the
response *by itself, with the right id*, and pass it through — rather than discarding it until
the next request arrives or the stream ends.

**Mechanism:** on restart, for a re-delivered response whose request-part already exists in the
durable store (lookup by `UniqueRequestId`), skip transform-and-send and write only the
source-response and comparison parts. Requires from the store:

- `existsById(UniqueRequestId)` — was the request already written?
- retrieval of the request, when the comparison part still needs generating.

### 6.4 What changes when the external store arrives

The bookkeeping store is future work (requests/responses reach 100MB, so a plain document
store is out; a bucket + OpenSearch index for metadata is the likely shape). When it lands,
these are the integration points to revisit — recorded here so the shift is a known quantity:

- `OffsetLifecycleTracker` tracks request-offsets and response-offsets as independent
  lifecycles instead of one per transaction.
- `commitTrafficStreams` fires on durable **part** write rather than transaction completion.
- The scanner's verdict refines from one question to two: "no request follow-up" (commit the
  request offsets, keep scanning for the response) vs. "no response follow-up."
- Independent request/response timeouts only become meaningful *after* commits decouple —
  before that they buy nothing.

---

## 7. Rejected Approaches

### Time-based force-commit (PR #3207's approach)

Three mechanisms: flip `CLOSED_PREMATURELY` to commit; a wall-clock expiry watchdog on a 30s
timer; a stale-head reaper with a 5-minute threshold in `OffsetLifecycleTracker`.

**Why rejected:**

- Commits on **impatience** (elapsed time), not **evidence** (structural confirmation that no
  follow-up exists). Under a slow target, `speedup < 1`, or ordinary replay lag, legitimately
  in-flight records exceed the threshold and get reaped → **silent data loss**, because
  committed means skipped on restart.
- The `CLOSED_PREMATURELY` flip contradicts at-least-once semantics: incomplete transactions at
  shutdown should re-deliver on restart, not be marked done.
- The wall-clock watchdog runs on a second thread against a documented single-threaded
  accumulator.
- All three paper over root causes (the F1/F2 bugs, or the structural-expiry gap the scanner
  closes) instead of fixing them.

See also §"Wall-clock expiry in the heartbeat" in the PR #3231 review notes
(`replayer-3231-review-notes.md`), which reaches the same verdict for the same reasons.

### Unbounded read-ahead

Raise the `BlockingTrafficSource` barrier to `blockerCeiling + timeout` whenever the commit
stalls, so reads can always reach the point where the blocker becomes expiry-eligible.

**Why rejected:** nothing bounds how much data must be buffered to expire a single connection.
Under adversarial traffic — or a crashed proxy that left many zombies — this is an OOM vector.
The scanner plus the proxy cap bound the problem without unbounded buffering. (This was the
original shape of the design; it is retained here as the alternative the scanner replaced.)

### Separate consumer group for the scanner

A second `group.id` reading the same topic.

**Why rejected:** no guarantee of partition affinity. The scanner could be assigned different
partitions than the replay consumer, which makes its verdicts worthless — it would be answering
"is this connection alive?" about partitions nobody is replaying. One consumer with two
logical cursors gives affinity for free.

### Per-connection heartbeat observations

The obvious first shape for §5.4: every N seconds, write a `HeartbeatObservation` into each open
connection's own stream.

**Why rejected — the offloader's flush is buffer-driven, not time-driven.**
`StreamChannelConnectionCaptureSerializer.flushIfNeeded(requiredSize)` flushes only when
`spaceLeft < requiredSize`; the only other flush call sites are per-request and connection-final
(`ConditionallyReliableLoggingHttpHandler`, `LoggingHttpHandler`). So a heartbeat written into an idle
connection's serializer sits in that connection's buffer indefinitely — the exact connections we need
to hear about are the exact ones that never flush. Forcing a flush instead means one Kafka record per
idle connection per interval plus a per-connection timer, which is strictly worse than the problem: at
32K idle connections and a 30s interval that is ~1,090 records/s and 32K timers, versus one record.
Snapshots coalesce all of it into a single write per partition and need no per-connection timers at
all — just a periodic sweep of a map the proxy already maintains.

### Broadcasting each snapshot to every partition

Emit every node's snapshot to all `M` partitions, so any replayer sees any node's liveness.

**Why rejected:** `N × M` records per interval (~1,090/s at N=32, M=1024, 30s), and the cost lands on
every replayer partition regardless of whether any node's traffic is there. Node-sharded shard sets
(§5.4.4) get the same guarantee for `N × K` because the proof only ever needs *same-partition*
ordering, never fleet-wide visibility.

### Dense-index bitmaps instead of id lists

Assign each connection a small dense integer and ship a bitmap.

**Why rejected:** Netty channel ids are not dense, so this requires the proxy to maintain its own
index allocator — new mutable state, plus index recycling, which reintroduces exactly the
identity-reuse ambiguity the id-based scheme avoids. The raw-suffix encoding (§5.4.3) already fits
32K connections in ~557 KiB, so there is no problem left to solve.

### A stable per-host nodeId

Derive nodeId from hostname, MAC, or instance id so it survives restarts, letting a restarted proxy
prove its predecessor's connections dead.

**Why rejected:** it makes the liveness signal forgeable by a successor process and converts a proxy
stall into silent data loss. Full argument in §5.4.5.

### Byte-count caps on the lookahead buffer

Bound read-ahead by bytes rather than time.

**Why rejected:** it's a proxy for the thing we actually care about, and a poorly-behaved one —
it means killing the process before memory is genuinely exhausted, and it adds another value
that has to be tracked correctly. `--max-concurrent-requests` already throttles connection
count (to avoid port exhaustion) but does not bound lookahead buffers. If we truly need to
buffer more, the preference is to keep going and let the OOM happen, with metrics good enough
to distinguish "slow target" from "rough traffic stream."

---

## 8. Dependencies on PR #3231

[PR #3231](https://github.com/opensearch-project/opensearch-migrations/pull/3231) fixes an active
rebalance/drain-gate incident in the same machinery this design rewrites. Detailed
change-by-change analysis lives in
[`replayer-3231-review-notes.md`](replayer-3231-review-notes.md); this section records only what
*this* document has to change depending on how that PR settles.

The PR is expected to change substantially before merge, so treat the specifics below as
conditional. Re-check them against what actually lands before implementing Phase 1.

### 8.1 Phase 0 grows by two bugs

Two of #3231's changes are Phase 0 material — same family as F1/F2, i.e. machinery that silently
drops something it was required to deliver:

- **`BlockingTrafficSource` never delegated the close callbacks.** It implements
  `ITrafficCaptureSource` without overriding `onNetworkConnectionClosed` or
  `onConnectionAccumulationComplete`, both of which are `default {}` no-ops. Since production
  wires `setGlobalOnSessionClose` through a `BlockingTrafficSource`, **every** close notification
  was silently swallowed instead of reaching `KafkaTrafficCaptureSource`, so the synthetic-close
  counter never decremented and the drain gate never reopened. This is the incident's root cause
  and is a genuine deadlock, not a slow path.
- **`closeClientConnectionChannel` called `schedule.clear()`**, dropping pending futures without
  completing them — leaking `requestWorkTracker` entries and `TrafficStreamLimiter` permits and
  stalling `OnlineRadixSorter`. `drainWithCancellation()` completes them exceptionally instead,
  matching what `cancelConnection` already does.

Once these land, fold them into §4 as F3/F4. Their existence strengthens rather than weakens the
design's premise: the failure mode that matters here is *silent* loss of a required signal, and
it has now appeared four times in four different places.

### 8.2 If the `isWorkOutstanding()` guard is removed (§5.1 at risk)

#3231 proposes deleting the guard in `ReplayEngine.updateContentTimeControllerWhenIdling`. The
review recommends against it, but if it lands:

- **§5.1's memory argument breaks.** ε bounds read-ahead only while the frontier is coupled to
  completed work. Without the guard, a stalled target no longer slows reading, so read-ahead
  becomes unbounded in the one scenario where bounding it matters most. Phase 1 would need an
  explicit read-ahead cap (a record or byte count), which §7 rejects as a poorly-behaved
  proxy — so we'd be picking the least-bad option rather than a good one.
- **§3's "bounded-slow" classification needs revisiting.** "Frontier gated by the slowest
  outstanding send" is currently classified as not-a-lock precisely because sends are bounded by
  `MAX_RETRIES × targetServerResponseTimeout`. Removing the guard doesn't invalidate that
  reasoning, but it does mean the symptom it describes disappears for a reason unrelated to
  fixing it.

Preferred outcome: the guard stays, and the reported gate-freeze turns out to be a symptom of the
orphaned futures fixed above (futures that never complete ⇒ work that is outstanding forever ⇒ a
genuinely unbounded stall). That would make the guard removal unnecessary and leave §5.1 intact.

### 8.3 If wall-clock force-expiry lands (§5.2 conflict)

#3231 adds force-expiry to the accumulator heartbeat, keyed on
`System.currentTimeMillis() − newestPacketTimestampInMillis`. That subtracts a **source**
timestamp from a **wall** clock, which only means anything at `speedup == 1` on live traffic; on
a historical capture every connection looks arbitrarily stale and gets expired immediately — and
because it fires `EXPIRED_PREMATURELY`, those expirations **commit**. The review recommends
dropping it.

If any form of it lands, Phase 1 **must remove it**, not coexist with it. Two expiry mechanisms
racing — one asking "has enough time passed?", the other "do follow-up records exist?" — resolve
in favor of whichever fires first, and the impatient one always does. That would silently defeat
the scanner's entire purpose while leaving it in the codebase looking authoritative.

### 8.4 Smaller adjustments

- **§5.5's metrics table should absorb #3231's observability work** rather than duplicate it.
  Its `TrackingKafkaConsumer` heartbeat reports the *worst* commit head across all partitions,
  which is strictly better than the arbitrary-first-partition behavior the table assumed.
  Caveat unchanged: that `age` derives from `peekHeadMetadata().addedAt` (wall-clock at
  insertion), which is a stall signal, **not** the backside ceiling.
- **Expect merge conflicts.** #3231 renames `logHeartbeat` → `heartbeatAndExpireStaleConnections`
  and touches `CapturedTrafficToHttpTransactionAccumulator`, `ReplayEngine`,
  `KafkaTrafficCaptureSource`, and `TrafficReplayer`'s heartbeat scheduler — all Phase 1 files.
  If the force-expiry is dropped the rename should go with it, which removes most of the overlap.
- **Phase 2's commit-on-exception semantics interact with #3231's `finally`-commit change.**
  Phase 2 multiplies the number of independent commit callbacks by four, so whatever exception
  policy is settled for change 1 (see the review's three-class table: deterministic → commit
  loudly; transient → retry then halt without committing; teardown → neither commit nor crash)
  must be applied per-part, not per-transaction. Settling that policy in #3231 first is the
  cheaper order.
