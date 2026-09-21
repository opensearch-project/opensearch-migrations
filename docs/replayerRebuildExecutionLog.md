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

## S2 — honest fatal failure

### Start

- Re-read the execution contract in §3.1 and traceability matrix in §6.5.
- Confirmed the settled process-failure order from `replayerLowLevelDesign.md` §8 and
  `replayerProcessingAndCommitArchitecture.md` §10.3: first fatal signal, high-severity diagnostics,
  `System.exit`, a ten-minute watchdog for bounded shutdown hooks, thread dump, then
  `Runtime.halt` with the same reason-specific code.
- Confirmed that rejected required connection-owner submissions are fatal and may not transfer
  mutation or cleanup authority to the submitting thread.
- Planned evidence: immediate and scheduled connection-actor submission rejection reaches the fatal
  handler; the supervisor arms its watchdog before `System.exit`; watchdog expiry dumps threads and
  halts with the same code; fatal shutdown skips owner-confined orderly cleanup; runtime shutdown
  hooks signal without joining; normal remaining-work waiting has one named bound.

### End

- Re-read the execution contract in §3.1 and traceability matrix in §6.5.
- Required `ConnectionActor` mailbox and scheduled-head submissions now route
  `RejectedExecutionException` to the process-fatal handler with the rejected operation and
  connection identity. Added deterministic tests for both rejection points.
- Added `ProcessSupervisor` behind the existing fatal-handler seam. It arms a non-daemon ten-minute
  watchdog before initiating `System.exit`; watchdog expiry writes a full thread dump to stderr and
  invokes `Runtime.halt` with the same reason-specific exit code.
- Production fatal handlers now use the supervisor in both bootstrap paths. The fatal handler remains
  one-shot, flushes diagnostics, signals fatal shutdown without waiting, and then initiates the
  supervisor ladder.
- Fatal shutdown skips `beginReplayShutdownAfterIntakeFence` and Netty/actor cleanup. Runtime shutdown
  hooks signal shutdown without joining. Normal remaining-work waiting uses one named two-minute
  bound instead of an unbounded doubling loop.
- Added focused tests for watchdog ordering and exit-code preservation, fatal-shutdown cleanup
  exclusion, non-joining shutdown hooks, the single remaining-work bound, and a subprocess whose
  live target event loop terminates and must exit with code 80.
- `git diff --check`: passed. Source search confirms the only production `Runtime.halt` reference is
  the final watchdog stage in `ProcessSupervisor`; no shutdown hook joins and no exponential
  remaining-work loop remains.
- Gradle was redirected to a writable isolated user home and the installed Gradle distribution, but
  startup requires a loopback datagram socket for its file-lock service. The sandbox denied that
  socket, and the escalation service rejected the approval request with its own encrypted-summary
  validation error. Compilation and focused tests remain pending CI.
- Traceability: S2 proves R19 for immediate and scheduled connection-owner submission rejection and
  adds the required live event-loop process test. R19 remains open for the replay-intake, request,
  Kafka-source, and tuple-writer owners introduced by later steps.

## S3 — dedicated replay-intake owner

### Start

- Re-read the execution contract in §3.1 and traceability matrix in §6.5.
- Confirmed the settled owner boundary from `replayerLowLevelDesign.md` §2 and
  `replayerKafkaSourceAndIntakeLowLevelDesign.md` §§1-4: replay intake owns one dedicated thread,
  removes named immutable inputs from a thread-safe queue, applies one input completely, and never
  waits synchronously for Kafka, target, or tuple completion.
- Confirmed `ReplayIntakeMailbox` is not a migration target: its generic `Runnable` queue, inline
  execution, and both blocking `await` methods must be deleted rather than renamed.
- Confirmed the source remains pull-driven only for this milestone. Each source-read stage will
  return through a typed success/failure input; the dedicated owner will apply the whole batch before
  starting the next read.
- Confirmed `PartitionGenerationId` must use the exact
  `(TopicPartition topicPartition, long localSequence)` shape. Intake-owned APIs may not fabricate a
  missing generation or retain a raw `int sourceGeneration` escape hatch.
- Planned evidence: deterministic queue ordering and whole-input application, owner-thread
  enforcement, required-submission rejection, injected owner failure reaching the process
  supervisor, source read success/EOF/failure handling without an intake-thread wait, and source
  generation identity preservation.
- Traceability target: establish the replay-intake portion of R1 and R19. R3-R18 remain assigned to
  their later state-machine and commit-authority milestones.

### PA1 — proxy classification and estimate

| Implementation group | Classification | Existing proof and remaining scope |
|---|---|---|
| `CaptureProxy` startup, configuration, shutdown, and process behavior | repair | Existing tests prove `0 < H < E`, configurable `F/H/E`, fail-closed default, fail-open selection, first-assignment readiness, and bounded orderly shutdown. The proxy still needs to receive and validate the run's `S` value so deployment can prove the same `E/S` values reach proxy and replayer. |
| `KafkaCaptureFactory`, `CaptureKafkaMembership`, `CaptureAssignmentPublisher` | verified | Unit and real-Kafka tests prove out-of-group probes precede membership, initial heartbeats gate readiness, replacement assignments are installed in callback order, the last usable assignment survives empty/lost/retriable polls, and resources close exactly once before and after ownership transfer. |
| `CaptureRoutingState` | verified | Deterministic tests prove fresh assignment-scoped writer identity, immutable connection routes, independent continuous heartbeat baselines, strict expiration-boundary rejection, registry retention through terminal acknowledgement, and local-only retirement. |
| `CaptureKafkaPublisher` | verified | Deterministic tests prove exhaustive envelope publication, no type headers, per-connection send/ack order, serialized heartbeats, deadline and late-ack irreversibility, critical-mutation broker-time validation, publisher-lane retirement, and permanent failure after the first application-visible Kafka error. |
| `CaptureKafkaWriteGate` | verified | Deterministic tests prove an accepted submission does not block a terminal trip, every later submission is rejected, and only the first terminal cause is published. |
| `CaptureKafkaCapabilityProbe`, `TrafficTopicMetadata` | verified | Unit and real-Kafka tests prove one inert probe envelope per representative partition, positive `LogAppendTime` acceptance, CreateTime/missing timestamp rejection, and stable partition metadata validation. |
| Netty pipeline and capture-offloader callbacks | verified, with proof gaps | Handler tests prove configured mutating requests wait for complete acknowledgement, fail-closed never forwards the mutation, fail-open becomes irreversible process-wide pass-through, synchronous capture failures and terminal failures reach the process path, diagnostic exceptions are nonterminal, and close is the sole terminal observation. Serializer tests prove fixed monotonic `F`, detachment before a boundary observation, contiguous connection sequence, size/terminal flush, and no empty idle record. |
| Managed controller acknowledgement and fleet recovery | managed-only | Deliberately absent from this standalone implementation, as required by §3.1 and `proxyCaptureProtocol.md`. |

- PA2 estimated repair scope: one focused proxy/deployment configuration change for shared `S`
  plumbing and validation, plus any small wiring correction exposed by its deterministic test.
- Missing deterministic proof estimate: four focused tests — shared `E/S` configuration,
  unobserved detached-publication failure propagation, post-terminal observation rejection, and
  proof that heartbeat activity cannot mutate per-connection state.
- PA3 scope remains the two mandatory real-Kafka proxy/replayer interoperability scenarios in §6.6.
  Existing real-Kafka proxy membership tests are component evidence, not substitutes for those
  end-to-end scenarios.
- No proxy mechanism currently requires replacement.

### End

- Re-read the execution contract in §3.1 and traceability matrix in §6.5.
- Added `ReplayIntakeOwner` and `ReplayIntakeInputQueue`. The owner starts and owns the dedicated
  `replay-intake-owner` thread, removes only sealed immutable inputs, and exhaustively applies owner,
  permit-pool, progress-controller, and disposition-ledger inputs one at a time.
- Source reads now return through typed success/failure inputs. The owner applies the complete
  delivered batch before requesting the next pull, treats EOF as normal completion, and performs no
  `join`, `get`, `await`, or semaphore acquisition on its thread.
- Deleted `ReplayIntakeMailbox` and both blocking await paths. `TrafficReplayerTopLevel` now starts,
  fences, closes, and stops the intake owner explicitly; shutdown and source lifecycle crossings use
  typed inputs rather than generic `Runnable` work.
- `AsyncPermitPool`, `ReplayProgressController`, and `RecordDispositionLedger` now expose sealed
  component inputs for the owner crossing and guard their mutable state with `OwnerThreadGuard`.
- `ReplayEngine.admitWork` now returns a stage. Request and close processing compose admission,
  transaction registration, and target startup without blocking the intake owner.
- Added exact `PartitionGenerationId(TopicPartition, long localSequence)` and carried it through
  intake-owned progress, disposition, and resolved-record state. Removed the fabricated
  `unpartitioned-session` fallback; missing generation identity is now a bug.
- Required input rejection reports process-fatal. Deterministic tests prove whole-batch ordering on
  the dedicated thread, rejected submission handling, and an injected owner failure reaching
  `ReplayProcessFatalHandler`.
- Direct clean `javac` compilation passed for all 175 main, 21 test-fixture, and 117 test source
  files. This also closes the source-compilation gap recorded in S1 and S2.
- A focused JUnit Platform run with the cached Mockito agent passed all 53 selected S1-S3 tests:
  replay-intake ownership, process supervision, fatal handling, shutdown fencing, connection-owner
  rejection, exhaustive Kafka envelope intake, and dump formatting.
- `git diff --check` passed. Source searches found no `ReplayIntakeMailbox`,
  `unpartitioned-session`, blocking intake-owner wait, or raw `int sourceGeneration` owner input.
- Traceability: S3 proves the replay-intake portion of R1 and R19. The remaining owner inventories
  and R2-R18 stay open for their assigned milestones.
