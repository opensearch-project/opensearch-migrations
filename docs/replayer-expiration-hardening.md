# Hardening Replayer Expirations

**Status:** Design — Phase 0 shipped (PR #3225), Phases 1 & 2 awaiting review

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [How Time Works Today](#2-how-time-works-today)
3. [Livelock Taxonomy](#3-livelock-taxonomy)
4. [Phase 0 — Correctness Fixes (shipped)](#4-phase-0--correctness-fixes-shipped)
5. [Phase 1 — Epsilon Lookahead + Scanner + Proxy Cap](#5-phase-1--epsilon-lookahead--scanner--proxy-cap)
6. [Phase 2 — Decoupled Tuple API + Response Recreation](#6-phase-2--decoupled-tuple-api--response-recreation)
7. [Rejected Approaches](#7-rejected-approaches)

---

## 1. Problem Statement

The replayer can permanently stall its Kafka offset commit under two categories of
conditions:

**Category A — bugs (Phase 0):** Two code paths in the accumulator could permanently orphan
offsets in `OffsetLifecycleTracker`, regardless of configuration. Both are fixed as of
PR #3225.

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

## 5. Phase 1 — Epsilon Lookahead + Scanner + Proxy Cap

Ships as one unit — epsilon cannot ship without the scanner (see §2, "load-bearing invariant").

### 5.1 Just-in-time lookahead (epsilon)

Redefine `--lookahead-time-window` from "read-ahead buffer" to "smoothing margin."
Default: **30s** — enough to hide Kafka fetch and transform latency, nothing more. The
`lookahead > timeout` validation (currently `exit(4)`) is relaxed, because the scanner
replaces the guarantee that validation was protecting.

**Effect:** buffered records collapse from ~400s of traffic to ~30s. Memory held = the real
working set (in-flight connections' accumulated bytes) + ε of read-ahead.

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

**Scan window** = `connectionTimeout + maxConnectionDuration` (the proxy cap from §5.3). This
is what bounds the scan: the scanner reads a fixed window ahead, never the whole topic.

**Two expiry modes (invariant — do not conflate these):**

| Mode | Trigger | Commit? | Rationale |
|---|---|---|---|
| **Confirmed dead** | Scanner finds no follow-up within the bound | **Yes** | Evidence-based; the records are garbage and must not be re-read |
| **Out of runway** | Partition reassignment or shutdown | **No** | Whoever picks up the partition must go back to the records that weren't completely handled |

The second row is a hard requirement: when removing a partition we **must not** commit
messages that another replayer session would otherwise handle. This matches the existing
`TRAFFIC_SOURCE_READER_INTERRUPTED` suppression in `commitTrafficStreams`.

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

### 5.4 Metrics

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
| Proxy: connections killed by duration cap | — | **NEW** |

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

### Byte-count caps on the lookahead buffer

Bound read-ahead by bytes rather than time.

**Why rejected:** it's a proxy for the thing we actually care about, and a poorly-behaved one —
it means killing the process before memory is genuinely exhausted, and it adds another value
that has to be tracked correctly. `--max-concurrent-requests` already throttles connection
count (to avoid port exhaustion) but does not bound lookahead buffers. If we truly need to
buffer more, the preference is to keep going and let the OOM happen, with metrics good enough
to distinguish "slow target" from "rough traffic stream."
