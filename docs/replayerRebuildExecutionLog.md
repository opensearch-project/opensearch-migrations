# Replayer Rebuild Execution Log

This log records implementation evidence for
[`replayerRebuildPlan.md`](replayerRebuildPlan.md). Every milestone remains non-production until
S0-S15, PA1-PA3, and final acceptance are complete.

## Starting state

- Branch: `stableAndScalableLiveReplay`, created from `origin/integrating3231`
- Starting commit: `a586c05d6cf09afb031300a0da311e657cb46612`
- Source checkout: `/Users/schohn/dev/replayerCommitHardening`
- New checkout: `/Users/schohn/dev/cdcHardeningCodex`
- Source checkout unrelated files preserved:
  - `Screenshot 2026-09-19 at 6.56.07 PM.png`
  - `docs/captureAndReplayReviewFindings-2026-09-14-round3.md`
  - `docs/captureAndReplayReviewFindings-2026-09-14.md`
  - `docs/replayer-implementation-plan.md`
- New checkout started clean.

## Baseline

| Command | Result | Evidence |
|---|---|---|
| `./gradlew :TrafficCapture:trafficReplayer:compileJava --no-daemon` | Failed with 40 errors | Matches the S1 baseline: all errors are in the obsolete liveness/absence-proof path and removed protobuf fields/types. |

## S0 — deterministic fixtures

### Start

- Re-read the execution contract in §3.1 and traceability matrix in §6.5.
- No production code or authoritative design document changed.
- Planned evidence: deterministic clock ordering, event-loop task/timer ordering, record-script
  payload and metadata fidelity, source-pump pause/resume/wakeup/commit observations, script
  exhaustion, injected failure reporting, and rejection of an independently supplied incorrect
  association expectation.
- Traceability preparation: fixtures will support R3-R7, R9, R11-R18; no obligation is claimed
  proved by fixture existence alone.

### End

- Re-read the execution contract in §3.1 and traceability matrix in §6.5.
- Added shared `FakeClock` and replaced both test-local mutable clocks.
- Extracted `TestEventLoop` from `ConnectionActorTest` and reused it across the lifecycle tests
  that previously supplied private fake mailbox clocks.
- Added `RecordScript` with explicit topic, partition, offset, `LogAppendTime`, writer identity,
  exhaustive envelope payload case, and literal expected-association sets.
- Added `PumpedKafkaSource` with explicit `runOnce`, scripted nonempty batches, typed LLD-shaped
  source inputs, a pluggable source-owner driver, coalesced wakeup observation, independent pause
  reasons, batch delivery, commit submission, script exhaustion, and failure reporting.
- Added fixture self-tests, including an intentionally incomplete association set that must fail.
- `git diff --check`: passed.
- `./gradlew :TrafficCapture:trafficReplayer:compileTestFixturesJava
  -x :TrafficCapture:trafficReplayer:compileJava --no-daemon`: could not compile the module's
  existing fixtures because excluding the known-broken main compile also removes every main class
  from the fixture classpath. The new fixture tests remain pending until S1 removes the 40-error
  obsolete production path. This is a baseline build-order constraint, not a fixture failure.
- Open obligations: all R1-R19 remain implementation obligations. S0 supplies deterministic proof
  infrastructure only.
