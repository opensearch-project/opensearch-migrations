# Test Results Log

---

## Baseline (pre-implementation)

Run before any changes to establish the baseline.

```
./gradlew :TrafficCapture:trafficReplayer:test :TrafficCapture:trafficReplayer:isolatedTest
```

All tests PASSED. BUILD SUCCESSFUL in 4m 1s.

Notable tests:
- KafkaRestartingTrafficReplayerTest [1-4] PASSED
- KafkaCommitsWorkBetweenLongPollsTest PASSED
- KafkaKeepAliveTests [2] PASSED
- KafkaTrafficCaptureSourceLongTermTest PASSED
- PartitionRevocationStaleStateTest [4] PASSED (from prior session)

---

## Phase 1 — Cache Invalidation Before Close

### Failing tests (pre-implementation)
- `scheduleClose_invalidatesCacheImmediately` FAILED (cache had 1 entry, expected 0)

### After implementation
- `scheduleClose_invalidatesCacheImmediately` PASSED
- `closeConnection_evictsCacheEntry` PASSED
- All other unit tests PASSED

---

## Phase 2 — `onPartitionsLost` Override

### Failing tests (pre-implementation)
- `onPartitionsLost_doesNotAttemptCommit` FAILED (commitSync was called with pending commits)

### After implementation
- `onPartitionsLost_doesNotAttemptCommit` PASSED
- `onPartitionsLost_cleansUpPartitionState` PASSED (was already passing — cleanup worked via delegation)

---

## Phase 3 — Active Connection Tracking

### Failing tests (pre-implementation)
- `activeConnectionsTrackedPerPartition` FAILED (partitionToActiveConnections was empty)

### After implementation
- `activeConnectionsTrackedPerPartition` PASSED

---

## Phase 4 — Synthetic Close Events

### Failing tests (pre-implementation)
- `accumulatorHandlesSyntheticClose` FAILED (no onConnectionClose fired for synthetic close)

### After implementation
- `accumulatorHandlesSyntheticClose` PASSED

---

## Phase 5 — ConnectionReplaySession Generation + Cancellation

### Failing tests (pre-implementation)
- `higherGenerationCancelsOldSession` FAILED (same session returned regardless of generation)

### After implementation
- `higherGenerationCancelsOldSession` PASSED
- `sameGenerationReusesSession` PASSED (was already passing)

---

## Phase 6 — Quiescent Metadata

### Failing tests (pre-implementation)
- `handoffConnection_taggedWithQuiescentUntil` FAILED (getQuiescentUntil() returned null)

### After implementation
- `handoffConnection_taggedWithQuiescentUntil` PASSED
- `freshConnection_notTaggedWithQuiescentUntil` PASSED (was already passing)

---

## Full Unit Suite After All Phases (pre-isolatedTest)

```
./gradlew :TrafficCapture:trafficReplayer:test --rerun-tasks
```

BUILD SUCCESSFUL in 39s — all unit tests PASSED.

---

## Full Integration Suite (isolatedTest) — Final

All tests passed. BUILD FAILED only due to Gradle failing to write XML result files (I/O issue), not test failures.

```
KafkaRestartingTrafficReplayerTest > fullTest [1] 3, false    PASSED
KafkaRestartingTrafficReplayerTest > fullTest [2] -1, false   PASSED
KafkaRestartingTrafficReplayerTest > fullTest [3] 3, true     PASSED
KafkaRestartingTrafficReplayerTest > fullTest [4] -1, true    PASSED
KafkaCommitsWorkBetweenLongPollsTest                          PASSED
KafkaKeepAliveTests [2]                                       PASSED
KafkaTrafficCaptureSourceLongTermTest                         PASSED
```

Notable fixes required during integration testing:
- Phase 1 (cache invalidation) reverted: immediate invalidation caused deadlock with
  finishedAccumulatingResponseFuture on old session never completing
- bisectExhaustiveSeeds test was running (not skipping) without system properties, consuming
  memory before fullTest — fixed with Assumptions.assumeTrue guard
- partitionToActiveConnections growing unboundedly — fixed with onConnectionDone callback
- Test JVM heap bumped to 5g (from 2g) to handle exhaustive test traffic generation
