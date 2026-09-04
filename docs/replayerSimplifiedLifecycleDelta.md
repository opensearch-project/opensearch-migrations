# Simplified Replayer Lifecycle — Delta From the Current Implementation

**Status:** Draft for discussion

**Date:** 2026-09-03

**Baseline:** branch `integrating3231` (current architecture as described in
[replayerArchitecture.md](replayerArchitecture.md); gaps as inventoried in
[replayerWorkLifecycleResponsibilityAudit.md](replayerWorkLifecycleResponsibilityAudit.md))

**Target:** [replayerSimplifiedLifecycleDesign.md](replayerSimplifiedLifecycleDesign.md)

**Policy input:** [replayer-expiration-hardening.md](replayer-expiration-hardening.md);
PR #3231 analysis in [replayer-3231-review-notes.md](replayer-3231-review-notes.md)

This is the tactical alternative to
[replayerHardenedArchitectureDesign.md](replayerHardenedArchitectureDesign.md) /
[replayerCurrentToProposedArchitectureMap.md](replayerCurrentToProposedArchitectureMap.md).
Instead of replacing the orchestration layer with actors and transactions, it keeps the current
execution structure and flattens the contracts between existing classes. Same invariants,
much smaller blast radius.

## 1. What Is Kept As-Is

Explicitly retained, contra the actor proposal:

| Component | Kept role |
| --- | --- |
| `TrackingKafkaConsumer` / `KafkaTrafficCaptureSource` | Kafka thread confinement, generation tracking, rebalance callbacks, inline-rebalance recovery |
| `OffsetLifecycleTracker` | Per-partition commit low-watermark |
| `CapturedTrafficToHttpTransactionAccumulator` + `Accumulation` state machine | Source reconstruction, including the F1/F2 fixes from PR #3225 |
| `ExpiringTrafficStreamMap` timestamp sweep | Opportunistic source-time expiry (no longer the *only* expiry path — see D6) |
| `TrafficStreamLimiter` | Concurrency cap and its semaphore/feeder mechanism (gains a drain contract only) |
| `ReplayEngine` + `TimeShifter` | Time shifting, task counting, `hookWorkFinishingUpdates`, the idle updater **with its `isWorkOutstanding()` guard** |
| `RequestSenderOrchestrator`, `ConnectionReplaySession`, `OnlineRadixSorter`, `TimeToResponseFulfillmentFutureMap` | Per-connection ordering and timed scheduling |
| `ClientConnectionPool`, `NettyPacketToHttpConsumer` | Channel cache and target exchange |
| Tuple writers (`ThreadLocalTupleWriter` / `TupleSink` / legacy consumer) | Whole-tuple evidence output |
| Quiescent-period, generation-check, and cooperative-rebalance behavior | Unchanged |

No sorter removal, no `ConnectionActor`, no `AsyncPermitPool`, no `ReplayTransaction` object
hierarchy, no new executors.

## 2. Change Sets

Six change sets, each independently landable. Audit IDs refer to
[replayerWorkLifecycleResponsibilityAudit.md](replayerWorkLifecycleResponsibilityAudit.md).

### D1 — One disposition function (closes R13, R14, R15; reworks #3231 change 1)

**Today:** `TrafficReplayerCore.commitTrafficStreams(shouldCommit, keys)` is called from five
different owners with a boolean derived from `ReconstructionStatus`; the tuple-packaging path
(`processCompletedTransaction` / `tryPackageAndWriteTuple`) rethrows a failed/cancelled target
exception **before** reaching it, so request-owned keys get neither context closure nor an
offset decision (audit Finding 1). PR #3231's proposed `finally`-commit would close the gap by
committing unconditionally — converting silent-orphan into silent-drop.

**Change:**

* Add `disposeRecords(tag, sourceOutcome, targetOutcome, evidenceOutcome, keys)` on the
  coordinator side of `TrafficReplayerCore`, implementing the design's decision matrix and the
  three-class failure policy (deterministic → commit + loud skip; transient → retry then halt
  without commit; teardown → retain quietly).
* `handleCompletedTransaction` wraps everything so that *every* exit — success, request
  failure, `CancellationException` from the schedule/sorter drain, tuple-writer failure —
  reaches `disposeRecords` with the real outcomes instead of rethrowing past it. The
  `WorkOutcome`/cause distinction from the sorter must survive to this call rather than being
  flattened back into a bare `Throwable`.
* `commitTrafficStreams(boolean, keys)` becomes a private helper of `disposeRecords`; all other
  callers (`onConnectionClose`, `onTrafficStreamsExpired`, `onTrafficStreamIgnored`,
  `failReplayForTupleWrite`) route through `disposeRecords` with explicit outcomes.
* Extend `failReplayForTupleWrite`'s halt-loudly treatment to the legacy
  `tupleWriter == null` path so both evidence modes implement one policy.
* The deterministic-poison classifier reuses the `--nonRetryableDocExceptionTypes` /
  `BulkItemErrorClassifier` shape already present on the RFS side; default is
  retain-and-halt-loudly for anything unclassified.
* `requestWorkTracker` removal moves to *after* `disposeRecords` returns, so tracker drain
  implies disposition (fixes the audit's "tracker drained, offset pinned" hazard, R11).

**Key test:** cancel a send mid-drain (the exact path #3231's change 4 creates) and assert the
held offsets are not committed, contexts are closed once, and records redeliver on the next
assignment.

### D2 — Flatten `AccumulationCallbacks` into typed events (closes R1's continuation coupling, C1, C2)

**Today:** `AccumulationCallbacks` is a bundle: `onRequestReceived` (which returns a
`Consumer<RequestResponsePacketPair>` that the accumulator stores inside the `rrPair` and must
remember to invoke), `onConnectionClose`, `onTrafficStreamsExpired`, `onTrafficStreamIgnored`,
plus `onConnectionAccumulationComplete` — whose delivery is skipped whenever `onConnectionClose`
receives an empty key list, leaving `partitionToActiveConnections` stale (audit Finding 4,
C2).

**Change:**

* Replace the bundle with the design's sealed `SourceEvent` sink: `RequestReady`,
  `SourcePairSettled`, `ConnectionFinished(tag, cause, heldKeys)`, `StreamsDiscarded`.
  `TrafficReplayerAccumulationCallbacks` becomes the single `SourceEventSink` implementation
  and keeps a `RequestTag → pending transaction` map instead of handing closures into
  accumulator state. `finishedAccumulatingResponseFuture` stays, but it is completed by the
  coordinator on `SourcePairSettled`, not by a consumer threaded through the `Accumulation`.
* `ConnectionFinished` always fires when an accumulation is removed — including the
  empty-held-keys case and the `hasSignaledRequests() == false` case — and carries a full
  `ConnectionTag` (nodeId, connectionId, sessionNumber = `startingSourceRequestIndex`,
  generation). Active-connection deregistration keys off the tag, never off surviving keys.
* `ReconstructionStatus` is replaced in the event payload by `ConnectionEndCause` /
  `SourceEndCause`, which split today's overloaded `EXPIRED_PREMATURELY` into
  `ConfirmedDead(proof)` (commit-eligible) vs. runway causes (never commit). This is the status
  split that the expiration design (§5.2) and the #3231 review (§5, open question) both flag as
  mandatory before any scanner-driven expiry can exist.
* Accumulator internals (state machine, rotation, F1/F2 handling) are untouched; only its
  outbound edge changes.

### D3 — Close-acknowledgement obligations (closes C8, C9, C10 partially; supersedes #3231 6a/6c)

**Today:** the synthetic-close drain gate is
`outstandingTrafficSourceReaderInterruptedCloseSessions` (an `AtomicInteger`) plus
`pendingTrafficSourceReaderInterruptedCloses` (a `ConcurrentHashMap` keyed by
`connectionId + ":" + sessionNumber + ":" + generation` with a placeholder session number that
three sites must keep in lockstep). A connection that never created a `ConnectionReplaySession`
produces no `onClose`, so the counter can stay nonzero forever (audit Finding 4). #3231's 6c
adds a 5-minute timeout that resets the gate lossily.

**Change:**

* Replace counter + string map with a `CloseAckLedger`: `expect(ConnectionTag) →
  CompletableFuture<CloseAck>`; the gate condition is `allOf` over the revoked generation's
  obligations. Registration and acknowledgement share the `ConnectionTag` type, so key
  divergence is impossible and `PENDING_CLOSE_SESSION_NUMBER_PLACEHOLDER` is deleted.
* `ClientConnectionPool.cancelConnection`: when `sessionCache.getIfPresent` finds nothing,
  complete the obligation with `NoSessionExisted` instead of returning silently. Same for
  accumulations that finish without a signaled request.
* Delete the lossy drain-gate timeout (6c). Keep the in-drain `touch()` keep-alive. A watchdog
  may log the stuck obligations (with tags) and halt loudly; it may not clear them.
* Keep #3231's 6b (`BlockingTrafficSource` forwarding of `onNetworkConnectionClosed` /
  `onConnectionAccumulationComplete`) — already on this branch — and then remove the trap
  class-wide: lifecycle notifications move off `ITrafficCaptureSource` default methods onto a
  required listener object passed explicitly at wiring time, so a wrapper cannot silently
  swallow them again (the F3 family).

### D4 — Real barriers for close, cancel, and shutdown (closes R2, R4/C4, C5, C6, C10, C11; keeps #3231 change 4)

**Today:** `cancelConnection` returns an already-completed future while its drain and channel
close are still in flight; ordinary close drains schedule+sorter (the
`drainWithCancellation`/`cancelAllWork` work from this branch) but omits transformation timers;
`shutdownNow()` kills event loops without enumerating sessions; `TrafficStreamLimiter.close()`
strands queued `workDequeuedByLimiterFuture`s; `waitForRemainingWork` cancels the aggregate,
not the children.

**Change:**

* `ClientConnectionPool.closeConnection` / `cancelConnection` return a composed future:
  transformation-timer drain (added to the ordinary-close path, not just reassignment) →
  schedule drain → sorter drain → channel close (`channel.close()` future actually awaited) →
  cache invalidation → `onClose` delivery / ledger acknowledgement. `ReplayEngine`'s
  cancel/close task accounting hangs off this composed future, so `isWorkOutstanding()` and
  `waitForRemainingWork` see the truth.
* `ConnectionReplaySession.hasWorkRemaining()` includes pending transformation timers.
* `TrafficStreamLimiter.close()` drains `workQueue`, completing each queued future
  exceptionally with a teardown cause (which flows into D1's teardown row).
* `ClientConnectionPool.shutdownNow()` first snapshots the session cache and applies the
  composed close to every session, awaits them (bounded, halt-loudly on breach), then shuts the
  event-loop group.
* Remove `TimeToResponseFulfillmentFutureMap.clear()` (audit Finding 7) so the orphaning API
  cannot return.

### D5 — Payload ownership contract (closes R16, R17, R18)

**Today:** the transformed `ByteBufListProducer`'s original reference has no releaser; signing
mode leaks one `ByteBufList` per attempt; the diagnostic snapshot is released only if tuple
packaging is reached (audit Finding 8).

**Change:**

* `TransformedOutputAndResult` transfers exactly one producer reference into the pending
  transaction (D1's registry entry); `disposeRecords` closes it on every terminal path.
* Document and enforce `get()` semantics: callers own the returned list;
  `sendRequestWithRetries` releases each attempt list after handing retained duplicates to the
  channel. The trivial producer returns a retained duplicate rather than its shared list so the
  attempt-release rule is uniform.
* The retry collector's diagnostic snapshot becomes a retained copy owned by the summary,
  released by `SourceTargetCaptureTuple.close()` *or* by transaction disposal when packaging is
  never reached.
* Add refcount instrumentation tests for trivial + signing producers across success,
  pre-send cancellation, mid-send cancellation, and multi-retry paths.

### D6 — Epsilon + scanner + liveness + proxy cap (implements expiration-hardening Phase 1; rejects #3231 changes 2 and 5)

**Today:** expiry is purely timestamp-driven off `ExpiringTrafficStreamMap`, which requires
`lookahead(400s) > connectionTimeout(360s)` and therefore ~400s of buffered traffic; the CLI
enforces that coupling with `exit(4)`.

**Change (ships as one unit, and only after D1–D3 are in):**

* **Scanner:** `TrackingKafkaConsumer` gains a scan cursor on the same consumer — after a poll
  batch: snapshot positions/generation, seek ahead within `scanWindow = connectionTimeout +
  maxConnectionDuration`, poll metadata only (connectionId, timestamps, observation kinds;
  payloads discarded), seek back, discard results if the assignment changed. Verdicts about the
  commit-head blocker (from `OffsetLifecycleTracker.peekHeadMetadata`, which must carry a
  `ConnectionTag`) are emitted as control events into the same pre-poll queue that
  `TrafficSourceReaderInterruptedClose` already uses — the intake path exists today; the
  scanner is a second producer, not a new mechanism. The accumulator handles a
  `ConfirmedDead(proof)` event on the intake thread by firing the normal
  close/expire machinery with the new cause (D2), which commits via D1's confirmed-dead row.
* **Epsilon:** `--lookahead-time-window` default drops to ~30s; the `lookahead > timeout`
  validation is replaced by "epsilon mode requires scanner enabled + finite proxy cap
  configured." The `isWorkOutstanding()` guard in
  `ReplayEngine.updateContentTimeControllerWhenIdling` **stays** (rejecting #3231 change 2):
  it is the memory bound that makes epsilon meaningful. D4's honest cancel/close barriers
  remove the orphaned-future scenario that motivated deleting it.
* **Wall-clock expiry:** rejected (#3231 change 5). Heartbeats report; they never call
  `fireAccumulationsCallbacksAndClose`. If the rename `logHeartbeat →
  heartbeatAndExpireStaleConnections` came in with it, revert the rename too.
* **Proxy cap:** `CaptureProxy` gains `--maxConnectionDuration`; a Netty `ScheduledFuture`
  armed on `channelActive` writes a real `addCloseEvent(Instant.now())` to the offloader
  before closing. Capped connections then commit through the ordinary captured-close path with
  no scanner involvement. Document the cap and the scan window as a matched pair.
* **Proxy liveness snapshots** (expiration-hardening §5.4): `CaptureProxy` maintains a two-level
  `ConcurrentHashMap` of partition → open connectionIds and, every `--livenessSnapshotInterval`
  (default 30s), emits one record per partition in its shard set listing that partition's **idle**
  connections — empty snapshots included, since "nothing idle here" is a positive claim. Partition
  selection moves to the explicit four-argument `ProducerRecord` constructor over a shard set
  `S(nodeId)` of `K` partitions, used for traffic *and* snapshots, which is the whole basis of the
  proof. `getNodeId()` stays `UUID.randomUUID()` per process — it is the fencing token that keeps a
  successor from speaking for a predecessor's connections (§5.4.5). No per-connection timers and no
  per-connection heartbeat observations: the offloader's flush is buffer-driven, so a heartbeat
  written into an idle connection's stream never leaves its buffer.
* **Liveness on the replayer side:** `TrackingKafkaConsumer` records snapshots into a
  `(nodeId, partition)` index keyed by offset, asserting the stamped partition matches the source
  partition (mismatch ⇒ halt, never expire). The scanner gains a second, cheaper verdict:
  two consecutive omissions with the blocker's last record preceding both ⇒ `ConfirmedDead`. These
  records bypass the accumulator, the obligation model, and `OffsetLifecycleTracker` entirely — they
  must never be able to pin the commit head they exist to unblock. Silence from a nodeId is a
  diagnostic, never a verdict.
* **Metrics:** absorb #3231's worst-commit-head heartbeat (change 7); make `commitTail`
  consistent with it; add scanner distance/latency/verdict counters and a proxy
  cap-close counter. Do not present `peekHeadMetadata().addedAt` (wall clock at insertion) as
  the backside ceiling.

### D7 — Kafka record contexts on non-commit paths (closes C13)

**Today:** `KafkaRecordContext` closure and the `ChannelContextManager` reference release
happen only via the successful broker-commit callback (`safeCommit → callbackUpTo →
onKeyFinishedCommitting`); partition cleanup drops staged queues without them.

**Change:** `cleanupRevokedPartitions` and every explicit retain decision from D1 route through
a small `releaseWithoutCommit(keys)` owner on `KafkaTrafficCaptureSource` that closes the
record context and releases the channel reference without touching offsets. One more consumer
of D1's decision, not a new mechanism.

## 3. Audit Coverage Map

| Audit IDs | Change set |
| --- | --- |
| R1 (continuation), C1 | D2 |
| R2 | D4 |
| R3, R11, R12 | D1 (disposition-gated tracker removal; contexts closed in one place) |
| R4, R5, C4 | D4 (timer drain on ordinary close) |
| R6, R7, R8 | Already covered on this branch; D4 adds the missing barrier and removes `clear()` |
| R9, C6 | D4 (awaited channel close in the composed barrier) |
| R10, C12 | D4 + D6 (honest futures feed `hookWorkFinishingUpdates`; guard retained) |
| R13, R14, R15 | D1 |
| R16, R17, R18 | D5 |
| C2 | D2 (tag-based `ConnectionFinished`, always fired) |
| C3, C5, C7 | Covered today; D4 sequences cache invalidation after close within the barrier |
| C8, C9, C10 | D3 (+D4 for the barrier itself) |
| C11 | D4 |
| C13 | D7 |

All 10 `Missing` rows and every `Partial` row in the audit are owned by exactly one change set.

## 4. PR #3231 Disposition Under This Plan

| #3231 change | Disposition here |
| --- | --- |
| 1 — `finally` commit | Superseded by D1 (classified disposition; teardown never commits) |
| 2 — remove `isWorkOutstanding()` guard | Rejected; D4 fixes the underlying orphaned-work stall |
| 3 — `TrackedFuture` duplicate-parent warn | Keep |
| 4 — `drainWithCancellation` on close | Keep (already on this branch); D4 builds its barrier on top |
| 5 — wall-clock heartbeat expiry | Rejected; D6's scanner is the structural replacement |
| 6a — placeholder-key lookup | Superseded by D3 (typed tags remove the key-agreement problem) |
| 6b — `BlockingTrafficSource` forwarding | Keep (already on this branch); D3 removes the default-method trap class-wide |
| 6c — lossy drain-gate timeout | Rejected; D3's watchdog halts loudly instead |
| 7 — observability | Keep; absorbed into D6 metrics |

## 5. Sequencing

1. **D1** — the disposition function. Highest-value, self-contained, and the precondition for
   everything that emits a new outcome. Exit gate: the mid-drain cancellation test passes; no
   caller reaches `commitTrafficStreams` directly.
2. **D2** — event flattening and the status split. Exit gate: `ConnectionFinished` fires for
   every accumulation removal including empty-key and no-signaled-request cases;
   `partitionToActiveConnections` has no stale entries after the exhaustive suite.
3. **D3 + D4** — obligations and barriers (they interlock: the barrier delivers the ack). Exit
   gate: no-session synthetic close reopens the gate; rebalance/shutdown tests show no early
   completion and no teardown commit; limiter close drains its queue.
4. **D5** — payload ownership. Exit gate: refcount tests at zero across all terminal paths.
5. **D7** — non-commit record-context owner. Exit gate: context-closure counters exact across
   commit, retain, and revocation paths.
6. **D6** — epsilon + scanner + liveness + proxy cap, only now. The proxy half (cap, snapshots, shard
   selection) can land first and be measured on its own; unread snapshots are inert. Exit gate: dead
   blocker at the commit head clears with proof — by omission while the proxy is alive, by window scan
   after it is killed; live long connection survives; an idle keep-alive connection is not expired
   across many intervals; a silent nodeId never expires anything; stalled target does not grow
   read-ahead; epsilon refused at startup without scanner + finite cap.

Each step keeps the existing tests green (or replaces them with an explicitly approved policy
change — the intentional differences are: teardown paths now retain instead of
committing-or-orphaning, and expiry now needs evidence).

## 6. Open Questions

1. Exact operator interface for the deterministic-poison classifier (flag name, matching
   semantics) — reuse the RFS `BulkItemErrorClassifier` shape.
2. Whether confirmed-dead discards of never-completed requests require durable discard evidence
   from day one, or a metric+log suffices initially (D1 matrix row).
3. Scan-cursor cadence and budget per poll cycle (fraction of `keepAliveInterval`), and whether
   scanning pauses while the commit head is not blocked.
4. Liveness shard width `K` and snapshot interval — configured or derived, and their startup
   validation against the topic's partition count. Tuning only: the offset-ordered proof is correct
   for any self-consistent shard set, so a wrong value costs overhead and expiry latency, not
   correctness.
5. Whether `SourcePairSettled` should also carry partial-response bytes for evidence on
   confirmed-dead connections, or only the settled cause.
6. Migration of `sessionNumber` into `ConnectionTag` for keep-alive reuse: populate from
   `Accumulation.startingSourceRequestIndex` at `accept()` time (the `GenerationalSessionKey`
   upgrade already sketched in replayerArchitecture.md) — confirm no other consumer of the
   placeholder constant remains before deleting it.
