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

### What needs to be built

**A1. `fireAccumulationsCallbacksAndClose` before `onConnectionClose`**

In `CapturedTrafficToHttpTransactionAccumulator.accept()`, when handling a
`SyntheticPartitionReassignmentClose`:
1. Look up the existing `Accumulation` for the connection via `liveStreams.getIfPresent(tsk)`
2. Capture `channelInteractionNum = accum.numberOfResets` and
   `sessionNumber = accum.startingSourceRequestIndex` before any state changes
3. Call `fireAccumulationsCallbacksAndClose(CLOSED_PREMATURELY)` on the accumulation — this
   completes `finishedAccumulatingResponseFuture` for connections in `ACCUMULATING_WRITES` state
4. Remove the accumulation from `liveStreams`
5. Fire `onConnectionClose(REASSIGNED, channelInteractionNum, ctx, sessionNumber, ...)`

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

**A4. Coordinator logic in `TrafficReplayerTopLevel`**

Move synthetic close registration from `KafkaTrafficCaptureSource` to `TrafficReplayerTopLevel`
(which has access to both the source and `ClientConnectionPool`). For each active connection on
a truly lost partition:
- Call `connectionId2ChannelCache.asMap().computeIfPresent(key, (k, session) -> { ... })` to
  atomically check presence and register in `pendingSyntheticCloses`
- If session was present: increment `outstandingSyntheticCloseSessions`, enqueue synthetic close
- If session was absent: skip (already closed, nothing to wait for)

**A5. Universal `onClose` callback on `ConnectionReplaySession`**

- Add `Consumer<ConnectionReplaySession> onClose` to `ConnectionReplaySession` constructor
- Fire it in `closeClientConnectionChannel`'s completion handler
- The coordinator's callback calls `pendingSyntheticCloses.remove(connKey_with_gen)` — if
  non-null, decrement `outstandingSyntheticCloseSessions`

**A6. `pendingSyntheticCloses` map**

`ConcurrentHashMap<GenerationalConnectionKey, Boolean>` in the coordinator, keyed by
`(nodeId, connectionId, generation)`. Populated atomically in A4. Cleared by the `onClose`
callback in A5.

### Failing Tests to Write

**Unit**:
- `syntheticClose_completesFinishedAccumulatingResponseFuture`: feed a
  `SyntheticPartitionReassignmentClose` for a connection in `ACCUMULATING_WRITES` state; assert
  `finishedAccumulatingResponseFuture` is completed before `onConnectionClose` fires
- `syntheticClose_schedulesChannelClose`: assert `replayEngine.closeConnection()` is called for
  `REASSIGNED` closes (currently skipped)
- `outstandingCounter_incrementsOnEnqueue_decrementsOnClose`: register a synthetic close;
  assert counter is 1; fire the `onClose` callback; assert counter is 0
- `emptyBatchReturnedWhileCounterPositive`: populate `outstandingSyntheticCloseSessions = 1`;
  call `readNextTrafficStreamSynchronously`; assert empty list returned
- `exactlyOneDecrement_regularCloseBeforeSynthetic`: register synthetic close; fire regular
  close `onClose` first; assert counter decrements once; fire synthetic close `onClose`; assert
  counter unchanged
- `exactlyOneDecrement_syntheticCloseBeforeRegular`: same as above, reversed order

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
