# Partition Revocation Cleanup — Implementation Plan

Test-first implementation plan for all 7 planned changes from `replayer-architecture.md`.
Each phase: write failing tests → implement → verify all tests pass → log results in `results.md`.

Run all replayer tests after each phase:
```
./gradlew :TrafficCapture:trafficReplayer:test :TrafficCapture:trafficReplayer:isolatedTest
```

---

## Phase 1 — Cache Invalidation Before Close

**Goal:** `ClientConnectionPool` invalidates the session cache entry immediately when a close is
scheduled, not after the Netty channel close completes.

### Failing Tests to Write

**Unit** (`ClientConnectionPoolCacheInvalidationTest` — file already exists, add to it):
- `cacheEntryInvalidatedBeforeNettyCloseCompletes`: schedule a close on a session, assert that
  `getCachedSession` returns a NEW session before the Netty close future completes.
- `newRequestAfterCloseScheduledGetsNewSession`: schedule a close, immediately call
  `getCachedSession` for the same `(connectionId, sessionNumber)`, assert the returned session
  is not the same object as the one being closed.

### Implementation

In `ClientConnectionPool.closeConnection()`, move `connectionId2ChannelCache.invalidate(...)` to
before `closeClientConnectionChannel(...)` is called, not inside its completion callback.

### Verification

All existing `ClientConnectionPoolCacheInvalidationTest` tests still pass. New tests pass.

---

## Phase 2 — `onPartitionsLost` Override

**Goal:** When Kafka calls `onPartitionsLost` (consumer timeout/fence), skip the commit attempt
and go directly to cleanup.

### Failing Tests to Write

**Unit** (`TrackingKafkaConsumerTest` — new file):
- `onPartitionsLost_doesNotAttemptCommit`: call `onPartitionsLost` with a partition that has
  pending commits; assert `kafkaConsumer.commitSync()` is NOT called (use a mock consumer that
  throws on `commitSync` to make the failure obvious).
- `onPartitionsLost_cleansUpPartitionState`: assert that `partitionToOffsetLifecycleTrackerMap`,
  `nextSetOfCommitsMap`, and `nextSetOfKeysContextsBeingCommitted` are cleared for the lost
  partitions.

### Implementation

Override `onPartitionsLost(Collection<TopicPartition>)` in `TrackingKafkaConsumer`. Skip
`safeCommit()`, go directly to the cleanup steps (remove from maps, recalculate counters,
record in `pendingCleanupPartitions`).

### Verification

All existing `TrackingKafkaConsumer`-related tests pass. New tests pass.

---

## Phase 3 — Active Connection Tracking + Accumulator Feedback Callback

**Goal:** `KafkaTrafficCaptureSource` maintains `partitionToActiveConnections`. The accumulator
notifies the source (via a registered callback) when a connection is done, so the source can
update the active set and call `channelContextManager.releaseContextFor()`.

### Failing Tests to Write

**Unit** (`KafkaTrafficCaptureSourceTest` — add to existing file):
- `activeConnectionsTrackedPerPartition`: consume records for 3 connections on partition 0;
  assert all 3 appear in `partitionToActiveConnections.get(0)`.
- `activeConnectionRemovedOnSourceClose`: consume a record with a close observation; assert the
  connection is removed from the active set after the accumulator processes it.
- `activeConnectionRemovedOnExpiry`: simulate accumulator expiry (`onTrafficStreamsExpired`);
  assert the connection is removed from the active set.
- `releaseContextForCalledOnConnectionDone`: assert `channelContextManager.releaseContextFor()`
  is called when the feedback callback fires (verify via a spy or by checking the context map
  size decrements).

**Integration** (`KafkaPartitionRevocationTest` — new `@Tag("isolatedTest")` file):
- `activeConnectionsPopulatedFromKafka`: produce 5 traffic streams to a Kafka TestContainer;
  consume them; assert all 5 connections appear in the active set.

### Implementation

1. Add `Map<Integer, Set<ScopedConnectionIdKey>> partitionToActiveConnections` to
   `KafkaTrafficCaptureSource`.
2. In `readNextTrafficStreamSynchronously`, add each `(partition, connectionId)` to the map.
3. Add a `Consumer<ScopedConnectionIdKey> onConnectionDoneCallback` parameter to
   `KafkaTrafficCaptureSource` constructor (or register it post-construction).
4. The callback: removes from `partitionToActiveConnections` and calls
   `channelContextManager.releaseContextFor(ctx)`.
5. Register the callback with the accumulator via a new `AccumulationCallbacks` method or by
   wrapping the existing `onConnectionClose` / `onTrafficStreamsExpired` in
   `TrafficReplayerAccumulationCallbacks`.

### Verification

All existing Kafka tests pass. New tests pass.

---

## Phase 4 — Synthetic Close Events

**Goal:** When a partition is truly lost (in `onPartitionsAssigned`: `trulyLost = pending - new`),
enqueue `SyntheticPartitionReassignmentClose` records for all active connections on those
partitions. These are returned from `readNextTrafficStreamChunk()` before any real Kafka records.
The accumulator handles them with `ReconstructionStatus.REASSIGNED`, closing target connections
and calling the feedback callback (which handles `releaseContextFor`).

### Failing Tests to Write

**Unit**:
- `syntheticCloseQueueDrainedBeforeRealRecords` (`KafkaTrafficCaptureSourceTest`): manually
  populate `syntheticCloseQueue` with 3 entries; call `readNextTrafficStreamChunk()`; assert the
  3 synthetic closes are returned before any Kafka records.
- `accumulatorHandlesSyntheticClose` (`PartitionRevocationStaleStateTest` — add to existing):
  feed a `SyntheticPartitionReassignmentClose` to the accumulator; assert
  `onConnectionClose(REASSIGNED, ...)` is called; assert `onConnectionClose` is NOT called with
  `COMPLETE`.
- `reassignedStatusDoesNotCommit` (`TrafficReplayerAccumulationCallbacksTest` — new file):
  assert that `commitTrafficStream` is NOT called when status is `REASSIGNED`.
- `reassignedStatusCallsReleaseContextFor`: assert the feedback callback fires for `REASSIGNED`
  closes (verifying `releaseContextFor` is called indirectly).
- `onPartitionsAssigned_enqueuesSyntheticClosesForTrulyLostPartitions`
  (`TrackingKafkaConsumerTest`): revoke partition 0, assign partition 1; assert synthetic closes
  are enqueued for partition 0's connections but NOT partition 1's.

**Integration** (`KafkaPartitionRevocationTest`):
- `syntheticClosesFireWhenPartitionTrulyLost`: produce traffic for 3 connections on partition 0;
  simulate partition revocation (partition 0 not in new assignment); assert 3 synthetic close
  events are processed; assert target connections are closed (verify via mock HTTP server
  connection count).
- `noSyntheticClosesWhenPartitionComesBack`: revoke partition 0, reassign partition 0 to same
  consumer; assert NO synthetic closes are enqueued.

### Implementation

1. Add `ReconstructionStatus.REASSIGNED` to `RequestResponsePacketPair`.
2. Create `SyntheticPartitionReassignmentClose` implementing `ITrafficStreamWithKey`.
3. Add `Queue<SyntheticPartitionReassignmentClose> syntheticCloseQueue` to
   `KafkaTrafficCaptureSource`.
4. In `readNextTrafficStreamSynchronously`, drain `syntheticCloseQueue` first before polling
   Kafka.
5. In `TrackingKafkaConsumer.onPartitionsRevoked`, record `pendingCleanupPartitions`.
6. In `TrackingKafkaConsumer.onPartitionsAssigned`, compute `trulyLost`, notify
   `KafkaTrafficCaptureSource` to enqueue synthetic closes.
7. In `CapturedTrafficToHttpTransactionAccumulator.accept()`, detect
   `SyntheticPartitionReassignmentClose` and fire `onConnectionClose(REASSIGNED, ...)`.
8. In `TrafficReplayerAccumulationCallbacks.onConnectionClose`, skip commit for `REASSIGNED`;
   fire the feedback callback.

### Verification

All existing tests pass. New tests pass. Run full suite including `isolatedTest`.

---

## Phase 5 — `ConnectionReplaySession` Generation + Cancellation

**Goal:** `ConnectionReplaySession` stores a `generation` field. When a new request arrives with
a higher generation than the cached session, the old session is cancelled (complete
`scheduleFuture` futures exceptionally + close Netty channel) and a new session is created.

### Failing Tests to Write

**Unit** (`ClientConnectionPoolCacheInvalidationTest` — add):
- `oldSessionCancelledOnGenerationBump`: create a session with generation 1; schedule a request
  on it; call `getCachedSession` with generation 2; assert the old session's `scheduleFuture`
  futures complete exceptionally; assert a new session is returned.
- `sameGenerationReusesSession`: call `getCachedSession` twice with the same generation; assert
  the same session object is returned.
- `cancelledSessionDrainsWithExceptions`: cancel a session that has pending work; assert all
  pending `OnlineRadixSorter` items complete exceptionally rather than hanging.

**Integration** (`KafkaPartitionRevocationTest`):
- `rapidReassignmentDoesNotViolateRequestOrdering`: produce traffic for connection "conn1" on
  partition 0; simulate rapid revocation + reassignment; assert requests from the new assignment
  are sent on a new Netty connection; assert the old session's requests complete (with errors)
  before the new session's requests start on the target.

### Implementation

1. Add `int generation` field to `ConnectionReplaySession`.
2. Add `generation` parameter to `ClientConnectionPool.getCachedSession()`.
3. In `getCachedSession`, if cached session's generation < incoming generation:
   a. Complete all `FutureWorkPoint.scheduleFuture` entries exceptionally
   b. Call `closeClientConnectionChannel(session)` (already invalidated in Phase 1)
   c. Create new session with new generation
4. Thread `generation` from `ITrafficStreamKey.getSourceGeneration()` through
   `RequestSenderOrchestrator.submitUnorderedWorkToEventLoop()` to `getCachedSession()`.

### Verification

All existing tests pass. New tests pass.

---

## Phase 6 — Quiescent Metadata for Handoff Connections

**Goal:** When a `TrafficStream` arrives for a connection not in `partitionToActiveConnections`
and without a READ/open observation, tag it with a `quiescentUntil` wall-clock instant.
`ReplayEngine.scheduleRequest()` uses `max(timeShiftedStart, quiescentUntil)` for the first
request on that connection.

### Failing Tests to Write

**Unit**:
- `handoffConnectionDetected` (`KafkaTrafficCaptureSourceTest`): consume a stream for a
  connection not in the active set, without a READ observation; assert the returned
  `ITrafficStreamWithKey` has a non-null `quiescentUntil`.
- `nonHandoffConnectionNotTagged`: consume a stream for a connection already in the active set;
  assert `quiescentUntil` is null.
- `freshConnectionWithOpenNotTagged`: consume a stream that starts with a READ observation for
  a new connection; assert `quiescentUntil` is null (this is a fresh connection, not a handoff).
- `replayEngineHonorsQuiescentDelay` (`ReplayEngineTest` — new or existing): schedule a request
  with a `quiescentUntil` 500ms in the future; assert the request is not sent until after that
  instant.

**Integration** (`KafkaPartitionRevocationTest`):
- `handoffConnectionDelayedByQuiescentPeriod`: produce mid-connection traffic (no open) for a
  connection not in the active set; assert the first request is delayed by the configured
  quiescent period; assert subsequent requests are not delayed.

### Implementation

1. Add `Instant quiescentUntil` to `ITrafficStreamWithKey` (default null) or carry it on the
   key.
2. In `KafkaTrafficCaptureSource.readNextTrafficStreamSynchronously`, detect handoff connections
   and set `quiescentUntil = Instant.now().plus(quiescentDuration)`.
3. Propagate `quiescentUntil` through `Accumulation` to `onRequestReceived`.
4. In `ReplayEngine.scheduleRequest()`, use
   `start = max(timeShifter.transformSourceTimeToRealTime(originalStart), quiescentUntil)`.

### Verification

All existing tests pass. New tests pass.

---

## Phase 7 — Cooperative Rebalancing (Configuration)

**Goal:** Switch to `CooperativeStickyAssignor`. Under cooperative rebalancing,
`onPartitionsRevoked` only fires for partitions being moved, so cleanup can happen immediately
without the deferred `onPartitionsAssigned` diff logic.

### Failing Tests to Write

**Integration** (`KafkaPartitionRevocationTest`):
- `cooperativeRebalancingOnlyRevokesMovedPartitions`: start two consumers in the same group with
  `CooperativeStickyAssignor`; add a third consumer; assert `onPartitionsRevoked` only fires for
  the partitions that actually moved, not all partitions.

### Implementation

1. Add `partition.assignment.strategy=CooperativeStickyAssignor` to `buildKafkaProperties()`.
2. Simplify `onPartitionsRevoked`: since revoked partitions are always truly lost under
   cooperative rebalancing, enqueue synthetic closes immediately rather than deferring to
   `onPartitionsAssigned`.
3. Keep the deferred logic as a fallback for eager rebalancing (configurable).

### Verification

All existing tests pass. New tests pass. Full suite including `isolatedTest`.

---

## Test Infrastructure Notes

**New test files:**
- `TrackingKafkaConsumerTest.java` — unit tests for rebalance callbacks
- `KafkaPartitionRevocationTest.java` — `@Tag("isolatedTest")`, uses `ConfluentKafkaContainer`
  + `SimpleNettyHttpServer` for end-to-end partition revocation scenarios
- `TrafficReplayerAccumulationCallbacksTest.java` — unit tests for REASSIGNED status handling

**Existing files to extend:**
- `ClientConnectionPoolCacheInvalidationTest.java` — Phases 1 and 5
- `KafkaTrafficCaptureSourceTest.java` — Phases 3 and 6
- `PartitionRevocationStaleStateTest.java` — Phase 4

**Test containers needed:**
- `ConfluentKafkaContainer` — already used in `KafkaKeepAliveTests`, `KafkaRestartingTrafficReplayerTest`
- `SimpleNettyHttpServer` — already used in `KafkaRestartingTrafficReplayerTest` for target

---

## Results Log

See `results.md` for test run output after each phase.
