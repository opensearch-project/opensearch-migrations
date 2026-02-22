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

The active connections are tracked as `(nodeId, connectionId)` but the pool is keyed by
`(connectionId, sessionNumber)`. The `sessionNumber` is `Accumulation.startingSourceRequestIndex`
— it changes each time a connection is reused across keep-alive requests. One
`(nodeId, connectionId)` maps to exactly one active `(connectionId, sessionNumber)` at any given
time (the current session). The coordinator registration key is therefore
`(connectionId, sessionNumber, generation)` — all three captured from the `Accumulation` at the
time the synthetic close fires in `accept()`.

### Threading Contract

| Operation | Thread | Synchronization |
|---|---|---|
| Enqueue synthetic close, increment counter | `kafkaExecutor` (inside `onPartitionsAssigned`) | `computeIfPresent` on Guava cache (thread-safe); `AtomicInteger` |
| `pendingSyntheticCloses.putIfAbsent` | `kafkaExecutor` | `ConcurrentHashMap` |
| `onClose` callback fires, `remove` + decrement | Netty event loop | `ConcurrentHashMap.remove` (atomic) |
| `readNextTrafficStreamSynchronously` reads counter | `kafkaExecutor` | `AtomicInteger.get()` |

### What needs to be built

**A1. `fireAccumulationsCallbacksAndClose` before `onConnectionClose`**

In `CapturedTrafficToHttpTransactionAccumulator.accept()`, when handling a
`SyntheticPartitionReassignmentClose`:
1. Look up the existing `Accumulation` via `liveStreams.getIfPresent(tsk)`
2. If accumulation exists: capture `channelInteractionNum = accum.numberOfResets` and
   `sessionNumber = accum.startingSourceRequestIndex` before any state changes
3. Call `fireAccumulationsCallbacksAndClose(CLOSED_PREMATURELY)` — completes
   `finishedAccumulatingResponseFuture` for `ACCUMULATING_WRITES` state; no-op for
   `ACCUMULATING_READS` (no future exists yet)
4. Remove the accumulation from `liveStreams`
5. Fire `onConnectionClose(REASSIGNED, channelInteractionNum, ctx, sessionNumber, ...)`
6. If no accumulation exists: still fire `onConnectionClose(REASSIGNED, ...)` with
   `channelInteractionNum=0, sessionNumber=0` — the coordinator will have already registered
   the session via the cache check (A4), so the close will still drain correctly

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
(which has access to both the source and `ClientConnectionPool`). For each active connection on
a truly lost partition:

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

**Enqueue criteria:**
- Accumulation exists, cache session exists → register + enqueue (normal case)
- Accumulation exists, no cache session → no request was ever dispatched; accumulation cleanup
  happens synchronously in `accept()`; don't increment counter
- No accumulation, cache session exists → connection had requests dispatched but accumulation
  already expired/cleaned up; register + enqueue so the session drains
- Neither exists → connection fully closed; skip

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
