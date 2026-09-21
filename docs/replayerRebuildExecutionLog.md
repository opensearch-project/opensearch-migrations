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

## S1 — envelope decoding and compile restoration

### Start

- Re-read the execution contract in §3.1 and traceability matrix in §6.5.
- Confirmed that the active proxy writes exactly one `CaptureRecord` envelope per Kafka application
  record and that replay intake must dispatch its payload exhaustively without trial decoding or a
  raw-`TrafficStream` compatibility path.
- Confirmed the milestone boundary: heartbeat and capability-probe records are accepted, counted,
  and immediately settled after application, while their final broker-time state machine remains an
  explicit non-production gap until S12.
- Deletion scope follows the implementation-step ordering rule in §5: remove the liveness scanner,
  absence-proof records, control evidence, and behavioral policy now; retain the ownership budget
  until S11 and generation-interruption scaffolding until its replacement owner/cancellation steps.
- Planned evidence: current-proxy traffic envelopes replay, traffic/heartbeat/probe dump formatting
  is explicit, unset and malformed envelopes fail as protocol violations, and S0 fixture self-tests
  compile and pass once the main source set is restored.

### End

- Re-read the execution contract in §3.1 and traceability matrix in §6.5.
- Deleted the obsolete liveness scanner, absence-proof/control-evidence records, behavioral policy,
  and their defining tests. Retained ownership-budget and generation-interruption scaffolding only
  for their later replacement steps, as required by the plan's migration order.
- Kafka replay intake now parses exactly one `CaptureRecord` and dispatches `TrafficStream`,
  `WriterPartitionHeartbeat`, `CaptureCapabilityProbe`, and `PAYLOAD_NOT_SET` exhaustively. There is
  no raw-`TrafficStream` compatibility path.
- Heartbeats and capability probes are accepted, counted, and routed through the existing
  ignored-record settlement callback. Their broker-time semantics remain explicitly open until S12.
- `KafkaTopicDumper` and `TrafficStreamDumper` now handle every envelope payload explicitly in raw
  and HTTP dump modes. Malformed and unset envelopes are protocol violations instead of skipped
  records. Removed the deliberately non-defining empty-success expiration dumper test identified by
  §6.2.
- Migrated every traffic-replayer Kafka test producer/helper to publish the current proxy's
  `CaptureRecord` wire format; the distinct base64 file-input fixture remains unchanged.
- Added focused S1 tests for exhaustive payload decoding and counters, heartbeat/probe formatting,
  and malformed/unset protocol violations.
- `git diff --check`: passed.
- Source searches confirmed no production or test references remain to `KafkaLivenessScanner`,
  `KafkaLivenessSnapshotRecord`, `KafkaNoMoreWritesRecord`, `KafkaSupersededTrafficRecord`,
  `AbsenceProof`, `CompleteSnapshotSpan`, `FollowUpRequirement`, `ScanEvidence`,
  `SourceControlEvent`, or `KafkaBehavioralPolicy`; no Kafka source still parses a raw
  `TrafficStream`.
- `./gradlew :TrafficCapture:trafficReplayer:compileJava
  :TrafficCapture:trafficReplayer:compileTestJava --no-daemon`: blocked before Gradle startup because
  the sandbox cannot open the existing `~/.gradle` wrapper lock, and the approval service rejected
  escalation with its own encrypted-summary validation error. Compilation and focused tests remain
  pending PR CI; this is an execution-environment blocker, not a changed design obligation.
- Traceability: S1 restores the envelope boundary used by later obligations but does not claim any
  R1-R19 obligation complete. All remain open for their assigned implementation steps.
