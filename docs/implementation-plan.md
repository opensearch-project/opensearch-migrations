# Partition Revocation Cleanup — Implementation Plan

Test-first implementation plan for the remaining planned changes from `replayer-architecture.md`.
Each phase: write failing tests → implement → verify all tests pass → log results in `results.md`.

Run all replayer tests after each phase:
```
./gradlew :TrafficCapture:trafficReplayer:test :TrafficCapture:trafficReplayer:isolatedTest
```

## Already Implemented

The following were completed in a prior session (all tests passing):

- `onPartitionsLost` override — skips `safeCommit()`, goes directly to cleanup
- `partitionToActiveConnections` tracking in `KafkaTrafficCaptureSource`
- `onConnectionDone` callback wired from `TrafficReplayerAccumulationCallbacks` to source
- `SyntheticPartitionReassignmentClose` + `ReconstructionStatus.REASSIGNED`
- `pendingCleanupPartitions` → `trulyLost` → synthetic close queue
- `syntheticCloseQueue` drained before real Kafka records in `readNextTrafficStreamSynchronously`
- `ConnectionReplaySession.generation` field; generation threaded from `ITrafficStreamKey`
  through `scheduleRequest` → `getCachedSession`
- Quiescent metadata tagging on handoff connections

---

## Phase A — Complete Synthetic Close Wiring

**Goal:** The synthetic close path currently fires `onConnectionClose(REASSIGNED)` but skips
`replayEngine.closeConnection()`, so the `ConnectionReplaySession` is never explicitly closed.
This phase wires the full drain: complete `finishedAccumulatingResponseFuture` for in-flight
requests, schedule the channel close, and confirm via the `outstandingSyntheticCloseSessions`
counter.

### Session Identity

The pool is keyed by `(connectionId, sessionNumber)`. The `sessionNumber` is
`Accumulation.startingSourceRequestIndex` — it changes each time a connection is reused across
keep-alive requests.

**Authoritative source for session keys**: `partitionToActiveConnections` is changed from
`Map<partition, Set<ScopedConnectionIdKey>>` to `Map<partition, Set<GenerationalSessionKey>>`
where `GenerationalSessionKey = (connectionId, sessionNumber, generation)`.

**Where and when the key is produced**: `sessionNumber = Accumulation.startingSourceRequestIndex`
is only known after the `Accumulation` is created or updated — which happens in
`CapturedTrafficToHttpTransactionAccumulator.accept()` on the main thread. Therefore
`partitionToActiveConnections` is populated and updated in `accept()`, not in
`readNextTrafficStreamSynchronously`. Specifically:
- On `getOrCreateWithoutExpiration` creating a new `Accumulation`: insert
  `GenerationalSessionKey(connectionId, startingSourceRequestIndex, generation)` into the
  partition's set. The partition number and generation come from `tsk.getPartition()` and
  `tsk.getSourceGeneration()` on the `ITrafficStreamKey`.
- On `resetForNextRequest()` (keep-alive reuse, new request on same TCP connection):
  `startingSourceRequestIndex` increments. Remove the old key and insert the new one with the
  updated `sessionNumber`. This keeps the map current for the active session.

**Removal semantics**: the `onConnectionDone` callback must carry the exact
`GenerationalSessionKey` that was inserted (not just the stream key), so the correct entry can
be removed. The callback is registered with the full key at insertion time in `accept()`.

The coordinator registration key is `(connectionId, sessionNumber, generation)` — always read
from `partitionToActiveConnections`, never derived from the `Accumulation` at close time.

### Threading Contract

| Operation | Thread | Synchronization |
|---|---|---|
| Populate `partitionToActiveConnections` with full `GenerationalSessionKey` | main thread (`readNextTrafficStreamSynchronously`) | `ConcurrentHashMap` + `ConcurrentHashMap.newKeySet()` |
| Enqueue synthetic close, increment counter | `kafkaExecutor` (inside `onPartitionsAssigned` OR `onPartitionsLost`) | `computeIfPresent` on Guava cache (thread-safe); `AtomicInteger` |
| `pendingSyntheticCloses.putIfAbsent` | `kafkaExecutor` | `ConcurrentHashMap` |
| `onClose` callback fires, `remove` + decrement | Netty event loop | `ConcurrentHashMap.remove` (atomic) |
| `readNextTrafficStreamSynchronously` reads counter | `kafkaExecutor` | `AtomicInteger.get()` |

### What needs to be built

**A1. `fireAccumulationsCallbacksAndClose` before `onConnectionClose`**

In `CapturedTrafficToHttpTransactionAccumulator.accept()`, when handling a
`SyntheticPartitionReassignmentClose`:
1. Look up the existing `Accumulation` via `liveStreams.getIfPresent(tsk)`
2. If accumulation exists: call `fireAccumulationsCallbacksAndClose(CLOSED_PREMATURELY)` —
   completes `finishedAccumulatingResponseFuture` for `ACCUMULATING_WRITES` state; no-op for
   `ACCUMULATING_READS` (no future exists yet). Remove the accumulation from `liveStreams`.
3. Fire `onConnectionClose(REASSIGNED, ...)` — the `channelInteractionNum` and `sessionNumber`
   come from the `GenerationalSessionKey` stored in `partitionToActiveConnections`, NOT from
   the `Accumulation`. This ensures the correct session is targeted even when no accumulation
   exists.
4. If no accumulation exists: skip `fireAccumulationsCallbacksAndClose`; still fire
   `onConnectionClose(REASSIGNED, ...)` using the key from `partitionToActiveConnections`.
   The session close will still drain correctly via the `onClose` callback.

**A2. `onConnectionClose(REASSIGNED)` must call `replayEngine.closeConnection()`**

In `TrafficReplayerAccumulationCallbacks.onConnectionClose`, remove the early return for
`REASSIGNED`. Instead: call `commitTrafficStreams(false, ...)` (no commit) AND
`replayEngine.closeConnection(channelInteractionNum, ctx, sessionNumber, timestamp)`.

**A3. `outstandingSyntheticCloseSessions` counter + empty-batch drain**

In `KafkaTrafficCaptureSource`:
- Add `AtomicInteger outstandingSyntheticCloseSessions`
- Increment when a synthetic close is registered (see A4)
- In `readNextTrafficStreamSynchronously`: after draining `syntheticCloseQueue`, return empty
  list while `outstandingSyntheticCloseSessions.get() > 0`
- Heartbeat is unaffected: `touch()` runs on `kafkaExecutor` independently of the main thread
  returning empty batches; `getNextRequiredTouch()` continues to fire based on
  `kafkaRecordsLeftToCommitEventually`

**A4. Coordinator logic in `TrafficReplayerTopLevel`**

Move synthetic close registration from `KafkaTrafficCaptureSource` to `TrafficReplayerTopLevel`
(which has access to both the source and `ClientConnectionPool`). The same registration logic
is called from three paths — all must use it:
- `onPartitionsAssigned`: for `trulyLost = pendingCleanupPartitions - newPartitions`
- `onPartitionsAssigned` when `newPartitions.isEmpty()`: all of `pendingCleanupPartitions` are
  truly lost
- `onPartitionsLost`: all partitions are truly lost immediately (no deferred diff needed)

For each active connection on a truly lost partition:

```
sessionKey = (connectionId, sessionNumber, generation)
// sessionNumber comes from the Accumulation captured at synthetic close time
// generation comes from TrackingKafkaConsumer.getConsumerConnectionGeneration()

registered = connectionId2ChannelCache.asMap().computeIfPresent(
    (connectionId, sessionNumber),
    (k, session) -> {
        pendingSyntheticCloses.putIfAbsent(sessionKey, true);  // idempotent
        return session;
    }
) != null

if (registered):
    outstandingSyntheticCloseSessions.incrementAndGet()
    syntheticCloseQueue.add(new SyntheticPartitionReassignmentClose(key))
// else: session already gone — accumulation cleanup happens in accept(), nothing to drain
```

**Enqueue criteria** (all decisions use `partitionToActiveConnections` as the key source):
- Key in `partitionToActiveConnections`, cache session present → register + enqueue (normal case)
- Key in `partitionToActiveConnections`, no cache session → session already closed; accumulation
  cleanup happens synchronously in `accept()`; don't increment counter
- Key NOT in `partitionToActiveConnections` → connection never seen on this partition; skip
- Neither accumulation nor cache session → connection fully closed; skip

There is no `sessionNumber=0` fallback. If the key is not in `partitionToActiveConnections`,
the connection is skipped entirely.

**A5. Universal `onClose` callback on `ConnectionReplaySession`**

- Add `Runnable onClose` to `ConnectionReplaySession` constructor (default no-op)
- Fire it in `closeClientConnectionChannel`'s completion handler, wrapped in try-finally to
  guarantee it fires even if the close throws
- The coordinator's callback: `if (pendingSyntheticCloses.remove(sessionKey) != null) outstandingSyntheticCloseSessions.decrementAndGet()`
- Fires for every close regardless of cause — regular source close, expiry, or synthetic close

**A6. `pendingSyntheticCloses` map**

`ConcurrentHashMap<GenerationalConnectionKey, Boolean>` in the coordinator, keyed by
`(connectionId, sessionNumber, generation)`. Populated with `putIfAbsent` in A4 (idempotent —
protects against duplicate registration). Cleared by the `onClose` callback in A5.

**Counter lifecycle edge cases:**
- *Lost-close callback*: `onClose` is in a try-finally in `closeClientConnectionChannel` —
  fires even if the channel close throws
- *Shutdown while counter > 0*: `shutdownNow()` closes all channels, firing all `onClose`
  callbacks, draining the counter; the main thread's empty-batch loop exits when
  `stopReadingRef` is set — no special handling needed
- *Duplicate registration*: `putIfAbsent` in A4 prevents double-registration for the same key;
  `computeIfPresent` atomicity prevents the session disappearing between check and registration

### Failing Tests to Write

**Unit**:
- `syntheticClose_completesFinishedAccumulatingResponseFuture`: feed a
  `SyntheticPartitionReassignmentClose` for a connection in `ACCUMULATING_WRITES` state; assert
  `finishedAccumulatingResponseFuture` is completed before `onConnectionClose` fires
- `syntheticClose_schedulesChannelClose`: assert `replayEngine.closeConnection()` is called for
  `REASSIGNED` closes (currently skipped)
- `outstandingCounter_incrementsOnEnqueue_decrementsOnClose`: register a synthetic close;
  assert counter is 1; fire the `onClose` callback; assert counter is 0
- `emptyBatchReturnedWhileCounterPositive`: set `outstandingSyntheticCloseSessions = 1`;
  call `readNextTrafficStreamSynchronously`; assert empty list returned
- `exactlyOneDecrement_regularCloseBeforeSynthetic`: register synthetic close; fire regular
  close `onClose` first; assert counter decrements once; fire synthetic close `onClose`; assert
  counter unchanged
- `exactlyOneDecrement_syntheticCloseBeforeRegular`: same, reversed order
- `noEnqueue_whenSessionAlreadyGone`: call coordinator registration when cache is empty; assert
  counter not incremented and no synthetic close enqueued
- `noEnqueue_whenAccumulationExistsButNoSession`: accumulation present, no cache session; assert
  counter not incremented

**Integration** (`KafkaPartitionRevocationTest`):
- `partitionRevocation_closesTargetConnections`: produce traffic for 3 connections; simulate
  partition loss; assert all 3 target connections are closed; assert source resumes after drain
- `partitionRevocation_noDataLoss`: verify at-least-once delivery across a partition revocation

### Verification

All existing tests pass. New tests pass. Full suite including `isolatedTest`.

---

## Phase B — Quiescent Period Wiring into ReplayEngine

**Goal:** The `quiescentUntil` metadata is tagged on handoff streams but not yet honored by
`ReplayEngine.scheduleRequest()`. This phase wires it through `Accumulation` to the actual
request scheduling.

### What needs to be built

1. Propagate `quiescentUntil` from `ITrafficStreamWithKey` through `Accumulation` to
   `onRequestReceived` context
2. In `ReplayEngine.scheduleRequest()`, use
   `start = max(timeShifter.transformSourceTimeToRealTime(originalStart), quiescentUntil)`
   for the first request on a connection (subsequent requests use normal timing)
3. Add `--quiescent-period-ms` command-line option to `TrafficReplayer` (default: 5000ms)

### Failing Tests to Write

**Unit**:
- `replayEngineHonorsQuiescentDelay`: schedule a request with `quiescentUntil` 200ms in the
  future; assert the request is not sent until after that instant
- `quiescentOnlyAppliesToFirstRequest`: second request on same connection uses normal timing

### Verification

All existing tests pass. New tests pass.

---

## Phase C — Cooperative Rebalancing

**Goal:** Switch to `CooperativeStickyAssignor` to eliminate stop-the-world rebalances and
simplify the deferred cleanup logic.

### What needs to be built

1. Add `partition.assignment.strategy=CooperativeStickyAssignor` to `buildKafkaProperties()`
2. Under cooperative rebalancing, `onPartitionsRevoked` only fires for partitions being moved —
   enqueue synthetic closes immediately rather than deferring to `onPartitionsAssigned`
3. Keep the deferred `onPartitionsAssigned` diff logic as a fallback for eager rebalancing

### Failing Tests to Write

**Integration** (`KafkaPartitionRevocationTest`):
- `cooperativeRebalancingOnlyRevokesMovedPartitions`: start two consumers; add a third; assert
  `onPartitionsRevoked` only fires for moved partitions

### Verification

All existing tests pass. New tests pass. Full suite including `isolatedTest`.

---

## Test Infrastructure

**New test files needed:**
- `KafkaPartitionRevocationTest.java` — `@Tag("isolatedTest")`, uses `ConfluentKafkaContainer`
  + `SimpleNettyHttpServer`

**Existing files to extend:**
- `ClientConnectionPoolCacheInvalidationTest.java` — Phase A (counter, onClose)
- `KafkaTrafficCaptureSourceTest.java` — Phase A (empty-batch drain), Phase B (quiescent)
- `PartitionRevocationStaleStateTest.java` — Phase A (finishedAccumulatingResponseFuture)

---

## Results Log

See `results.md` for test run output after each phase.
