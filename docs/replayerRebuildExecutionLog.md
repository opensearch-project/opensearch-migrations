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

## S4a — intake-owned record/work association

### Start

- Re-read the execution contract in §3.1 and traceability matrix in §6.5.
- Confirmed that the legacy disposition/commit path remains the sole authority throughout S4a; the
  new tracker is non-production association evidence only until the atomic S4b cutover.
- Confirmed one `RecordWorkTracker` per `KafkaRecordId`, open while observations are applied, with a
  many-association set, atomic relabeling, and one completion latch. Association completion cannot
  itself choose or submit a Kafka disposition in this milestone.
- Confirmed associations are created per observation for the operation actually affected. Request
  assembly associations move without a gap to `ReplayRequestId`; every later source-response record
  remains associated with that request until tuple durability.
- Planned evidence uses literal `RecordScript` expectations: mixed request-N EOM/request-N+1 read,
  keep-alive, request-spanning records, cross-record source response, duplicate association
  idempotence, relabel without transient completion, and no completion while any expected
  association remains.
- Traceability target: establish tracker-state proofs for R11-R13. Commit-order authority and
  production completion emission remain open until S4b.

### End

- Re-read the execution contract in §3.1, the S4a boundary, and traceability matrix in §6.5.
- Added intake-owned `RecordWorkTracker` state for each accepted `KafkaRecordId`: a many-operation
  association set, close-to-new-associations flag, completion latch, and reverse operation index.
  Duplicate association of one record with one operation is idempotent; selective completion removes
  only the named operation; completion is emitted internally once and `associationFinished` returns
  no commit authority.
- The accumulator now registers Kafka records before applying them, associates each applicable
  observation with its current source-request assembly or `ReplayRequestId`, atomically relabels
  every contributing request-assembly record when the request is reconstituted, and closes the
  tracker only after the entire record is successfully applied.
- A keep-alive observation that both finishes request N's source response and begins request N+1 now
  gives its containing record both independent associations. Dropped, closed, expired, or otherwise
  abandoned incomplete requests release only their source-assembly associations.
- Every record contributing source-response observations remains associated with the same
  `ReplayRequestId`. Synchronous and asynchronous tuple paths return one immutable
  `AssociationFinished` input to replay intake only after tuple durability; tuple failure leaves the
  association unfinished.
- Added literal `RecordScript` oracle tests for a mixed keep-alive record and a response spanning
  Kafka records. Added tracker tests for duplicate association idempotence, several associations per
  record, relabeling across already-closed records without transient completion, immediate empty
  completion, owner-input return, and off-owner mutation rejection.
- Direct clean `javac` compilation passed for all 175 main, 21 test-fixture, and 119 test source
  files. A focused JUnit Platform run passed 32 tests covering the new association model plus the
  existing randomized accumulator settlement, keep-alive/terminal reconstruction, replay-intake
  owner, fatal handling, and shutdown suites.
- `git diff --check` passed. The legacy `RecordDispositionLedger`, `holdTrafficStream`, and
  ignored-record callback remain the sole commit authority exactly for this non-production S4a
  checkpoint; the new tracker's completion listener is diagnostic evidence only.
- Traceability: S4a establishes the tracker-state portions of R11 and R12 and the cross-record
  response association portion of R13. Commit-prefix authority remains open for S4b; the complete
  request-processing and delayed durable-tuple proofs remain assigned to S5 and S7.

## S4b — atomic commit-authority cutover

### Start

- Re-read the execution contract in §3.1, the S4b cutover boundary, and the traceability matrix in
  §6.5.
- Confirmed that this milestone must replace commit authority atomically: the legacy record handle,
  disposition ledger, accumulator ignored-record callback, disposition policy, target result, and
  request lifecycle may not commit or retain Kafka records after the cutover.
- Confirmed that Kafka ordering is the order records were observed by this consumer, not arithmetic
  continuity of physical offsets. Each active `PartitionGenerationId` therefore owns one deque whose
  head alone controls contiguous-prefix advancement.
- Confirmed that `RecordProcessingFinished(KafkaRecordId)` is accepted exactly once after the
  intake-owned record tracker closes with no remaining associations. Active-generation duplicate or
  unknown completion is fatal; stale-generation completion is diagnostic-only because Kafka will
  redeliver that generation's records.
- Planned evidence: physical offset gaps, completed records blocked behind an unfinished observed
  head, multi-record contiguous advancement, active duplicate/unknown registration or completion,
  stale-generation fencing, mixed records shared by several requests, and source-response records
  held through request-processing completion.
- Traceability target: complete R11 and R12 for the record/commit model and establish the S4b portion
  of R13. Durable tuple ordering remains assigned to S5 and S7.

### End

- Re-read the execution contract in §3.1, the S4b boundary, and the traceability matrix in §6.5.
- Added generation-scoped `ObservedRecordCommitQueue`, bound to its Kafka owner thread and ordered by
  the records actually returned by polls. It permits physical offset gaps and stages a commit only
  when completion drains a consecutive prefix from the observed head.
- Kafka records are registered before decoding/intake delivery. The intake-owned
  `RecordWorkTracker` now emits exactly one `RecordProcessingFinished(KafkaRecordId)` after a record
  is closed to new associations and every distinct assembly, request, or terminal-close association
  has finished.
- `KafkaTrafficCaptureSource.recordProcessingFinished` is the sole production crossing that releases
  the source record context, advances the observed prefix, and flushes its staged Kafka commit.
  Active-generation duplicate or unknown completion is an invariant failure. A late completion for
  a revoked generation cannot touch its successor queue.
- Added immutable observed-queue snapshots for heartbeat and next-touch monitoring, so non-owner
  monitoring threads do not read owner-confined mutable queue state.
- Removed production use of `RecordDispositionLedger`, `ReplayDispositionPolicy`,
  `TrafficStreamRecordHandle`, request-level record retention, accumulator ignored-record commits,
  and target-result `haltReplay`. Deleted `ReplayDispositionPolicy`, `OffsetLifecycleTracker`, their
  authority-defining tests, and the obsolete defensive `holdTrafficStream` representation.
- Target outcomes now affect target/evidence reporting only. Source reconstruction associates a
  natural terminal-close record with `TerminalSourceConnectionId` and releases it only after the
  ordered target close is accepted.
- Added deterministic queue tests for out-of-order completion, physical gaps, blocked heads,
  contiguous advancement, duplicate registration, duplicate completion, unknown completion, stale
  generations, partition loss, and immediate touch for staged commits. Added an off-owner monitoring
  snapshot test.
- Added a deterministic randomized property test covering 200 mixed-record/request association
  graphs. It proves no record completes while any associated request remains and every record emits
  completion exactly once. Literal `RecordScript` tests continue to prove mixed keep-alive and
  cross-record source-response associations.
- Updated source, rebalance, actor, and request-lifecycle tests to drive `AsyncPermitPool` and Kafka
  callbacks through their real single-owner executors instead of caller-thread fixtures.
- Clean direct `javac` compilation passed for all 175 main, 21 test-fixture, and 107 retained test
  source files. The temporary local JUnit launcher used because Gradle cannot create its sandboxed
  lock socket was removed from the tree.
- Focused S4b suites passed: observed queue (3), tracking consumer (7), record tracker including the
  randomized property (6), association accumulator (2), Kafka source (11), request lifecycle (21),
  top-level shutdown (4), replay intake (3), simple accumulator (5), and replay progress (3).
  A 46-test lifecycle sweep passed. A 68-test Kafka/source sweep initially passed 66 tests; the two
  failures were legacy fixtures violating owner-thread rules, and both passed after fixture repair.
- `git diff --check` passed. Production source search finds no legacy record-handle commit/release,
  `haltReplay`, `holdTrafficStream`, target/request commit authority, or accumulator ignored-record
  commit call. The no-op legacy callback adapter and uninstantiated disposition vocabulary remain
  isolated for deletion in S7; neither participates in production correctness.
- Traceability: S4b completes R11 and R12 for tracker closure plus observed-prefix commit authority.
  It establishes R13 through cross-record response association and commit blocking; the required
  delayed tuple-durability proof and final vocabulary deletion remain assigned to S5 and S7.

## S5 — two request milestones and persistent request registry

### Start

- Re-read the execution contract in §3.1, the S5 plan boundary, the traceability matrix in §6.5,
  and the authoritative connection/request child LLD.
- Confirmed that `TargetConnectionOwner` and every `RequestReplayOwner` share one selected Netty
  event loop. There is no separate connection-owner executor, and no owner-confined mutable state
  may be read or changed off that event loop.
- Confirmed that admission and execution are distinct ordered queues. Transformation readiness may
  arrive out of order but cannot change captured execution order, and only the execution head may
  reserve a target turn.
- Confirmed the two independent request milestones: `ConnectionTurnFinished` ends target
  connection ordering and advances the execution head; `RequestProcessingFinished` occurs only
  after tuple durability and removes the request from the connection-owned registry after required
  replay-intake acceptance.
- Confirmed that `FirstTargetWriteSubmitted` is observable only at Netty's first accepted write,
  changes local connection cancellation state exactly once, and is never sent to replay intake.
- Confirmed the exact attempt outcome split: `TargetResponseObtained` includes successful and
  unsuccessful complete responses; `NoTargetResponseObtained` alone represents absence of a target
  response. Abort and retry-policy throws may not be converted into exceptional control flow that
  fabricates a retry.
- Planned evidence: request-state and owner transition tables, out-of-order preparation with ordered
  turns, turn completion before delayed tuple durability, registry retention through tuple
  durability, exactly-once milestones, first-write locality, and impossible transition plus rejected
  owner submission reaching `ProcessSupervisor`.
- Traceability target: establish the S5 portions of R1, R2, R8, R10, R13, and R19. Final disposition
  vocabulary deletion and unconditional tuple-writer retry remain assigned to S7.

### Progress checkpoint — S5a production owner contracts

- Replaced `ConnectionActor` in production with event-loop-confined `TargetConnectionOwner` and
  per-request `RequestReplayOwner`. Admission and execution use separate FIFO queues, request and
  close inputs share one explicit captured-ordinal space, and the request registry survives target
  turn completion.
- Added typed request admission. Acceptance occurs only after registry insertion, processing
  registration, preparation ownership transfer, and queue insertion. Rejection leaves preparation
  and processing cleanup with the sender.
- Added typed processing-cancellation arbitration. Permanent request-processing outcomes are
  `TupleDurable` and `RequestCleanupFinished`; the owner does not infer cancellation from a
  `Throwable` subtype. The legacy `TargetOutcome` remains isolated at the pre-S7
  `ReplayTransaction` boundary; no new compatibility settlement vocabulary was retained.
- Split target-attempt results into `TargetResponseObtained` and `NoTargetResponseObtained`.
  Retry-policy throws remain failures, and target abort returns an explicit value instead of
  fabricating a retry through exceptional control flow.
- Routed `ConnectionRequestFinished` and `RequestProcessingFinished` through replay intake.
  Connection-turn completion removes only the execution entry and advances the head; normal
  processing completion waits for tuple durability and both required intake acceptances before
  registry removal.
- Made `FirstTargetWriteSubmitted` a connection-local, one-shot callback at Netty's accepted
  `writeAndFlush` boundary. It is not a replay-intake input and retries do not repeat it.

| S5a requirement | Production location | Focused evidence in the current test migration |
|---|---|---|
| Typed admission and cleanup ownership | `TargetConnectionOwner.RequestAdmissionResult`; `RequestSenderOrchestrator.scheduleRequestLifecycle` | wrong-generation, late-admission, rejected-submission, and throwing-`begin()` cases |
| Separate captured-order queues | `TargetConnectionOwner.admissionCommands` and `executionCommands`; ordinal validation | out-of-order preparation; nonzero request/request/close ordinals |
| Two milestones and persistent registry | `settleRequest`; `applyRequestProcessingCompletion`; replay-intake lifecycle routing | target head advances before delayed tuple durability; registry waits for intake acceptance |
| Typed cancellation races | `ProcessingCancellationResult`; `ReplayTransaction.requestCancellation`; owner arbitration | both decision-before-completion and completion-before-decision orders |
| First-write locality | `NettyPacketToHttpConsumer.writePacketAndUpdateFuture`; connection-owner callback | accepted-write locality, one-shot delivery, and retry reuse |
| Fatal owner transitions | owner transition runner and injected fatal handler | rejected immediate/scheduled submissions, failed lifecycle acceptance, and unexpected cancellation |

- A parallel contract review found two admission defects: an accepted request could be rolled back to
  sender ownership when `RequestPreparation.begin()` threw, and mailbox rejection could remove from
  the owner-confined registry on the submitting thread. Both were fixed. Registry/obligation rollback
  now occurs only in the owner mailbox; after accepted ownership, a throwing `begin()` remains an
  owner-fatal transition. A regression test covers that accepted-ownership boundary.
- The required read-only Claude Code review first snapshotted the pre-fix staged tree and independently
  reported the off-event-loop registry mutation as Medium severity. Disposition: valid and already
  fixed before its response arrived. A fresh review of the final staged tree explicitly verified both
  admission fixes and returned `NO_ACTIONABLE_FINDINGS`.
- The exact production-only staged tree compiled in an isolated worktree with
  `:TrafficCapture:trafficReplayer:compileJava --parallel --max-workers=18`. The serialized
  `TargetConnectionOwnerTest` suite passed 48/48.
- A serialized 109-test lifecycle sweep passed 108 tests. The sole failure,
  `globalRunwayLossReachesAnActorCreatedDuringShutdown`, asserts that an unstarted preparation future
  is cancelled through legacy `ReplayTransactionRegistry` runway-loss behavior. The request itself
  completes exceptionally as required. This stale transitional assertion is assigned to the
  immediate test-migration slice; no production bridge will be added for vocabulary deleted in S7.
- S5 remains open. The focused test migration, owner/test decomposition, and complete green module
  run are required before adding the S5 End entry.

### Progress checkpoint — S5b cancellation and request-owner contract evidence

- Added focused `ReplayTransactionCancellationTest` and `RequestReplayOwnerTest` suites rather than
  continuing to grow the connection-owner fixture. They pin typed cancellation arbitration,
  in-flight evidence preemption, exact cancellation-cause identity, connection-before-processing
  milestone order, request-owner confinement, one-shot first-write state, and cleanup release.
- The request-owner evidence uses one independently constructed owner per test. Foreign-thread and
  same-thread/outside-mailbox mutations are both rejected; no event loop or mutable state is shared
  across tests.

| S5b contract | Focused evidence |
|---|---|
| Cancellation can win a live transaction | cancellation completes with `CancellationWon`, preserves the exact `CancellationException`, and remains terminal when an in-flight durable evidence result arrives later |
| Normal completion can win cancellation | already-durable and mailbox-queued durability paths both return `ProcessingCompletionWon` and assert `EvidenceOutcome.Durable` |
| Connection milestone precedes processing milestone | `TupleDurable` cannot finish before connection-turn acceptance; the completion-won race drives both milestones in order |
| Unresolved cancellation cannot masquerade as durability | `TupleDurable` is rejected while cancellation arbitration remains unresolved and accepted only after `ProcessingCompletionWon` |
| Owner state is event-loop confined | first and later mutations fail from another thread, and the configured owner thread still fails outside a mailbox task |
| Local first-write and cleanup states are one-shot | duplicate first-write, connection, processing, and cleanup transitions are rejected |

- The required read-only Claude reviews found and drove closure of the following test defects:
  cancellation was initially asserted only after another terminal path had already won; durable
  evidence was not asserted for completion-won cases; unfinished processing-state tests were
  non-discriminating; the live-cancellation evidence assertion was unreachable; and one typed race
  fixture stopped with a queued connection turn. Each finding was valid and fixed. The final review
  compiled and ran the two classes independently, passed 24/24 tests, and returned
  `NO_ACTIONABLE_FINDINGS`.
- The serialized Gradle run of both suites against the current migration tree passed 24/24:
  `:TrafficCapture:trafficReplayer:test --tests '*ReplayTransactionCancellationTest' --tests
  '*RequestReplayOwnerTest' --no-parallel --max-workers=1 --rerun-tasks`.
- The exact test-only staged tree intentionally does not yet compile `compileTestFixturesJava`.
  Existing fixture APIs still target pre-S5 constructors and lack the Kafka client test-fixture
  dependency. This known broken intermediate is assigned to the immediately following bounded
  fixture-compile closure; no production compatibility bridge will be introduced.
- S5 remains open. Owner decomposition, fixture/test migration, and the complete green module run
  are still required before adding the S5 End entry.

### Progress checkpoint — S5c1 owner-transition runner extraction

- Extracted mailbox dispatch, owner-thread enforcement, fatal-transition latching, rejected
  submission handling, and process-fatal reporting from `TargetConnectionOwner` into the injected,
  package-local `OwnerTransitionRunner`. The connection owner no longer carries its own thread
  guard, fatal handler, or fatal-state field.
- The runner keeps its fatal latch owner-confined. An owner-inline failed submission poisons the
  owner; an off-mailbox failed submission reports process-fatal without mutating owner state.
  Asynchronous cleanup reporting is valid both inline and off-mailbox and never infers latch state.
- Transition failure handlers are terminal cleanup callbacks. Their own failures are suppressed onto
  the primary failure and cannot prevent the original owner failure from reaching the process
  supervisor. An original `Error` retains identity while gaining owner/operation context as a
  suppressed diagnostic.
- Added a dedicated eight-test `OwnerTransitionRunnerTest` instead of expanding the connection-owner
  fixture. It covers posted confinement, rejected and unexpected synchronous submissions,
  owner-inline poisoning, impossible transitions, `Error` identity plus context, callback-failure
  suppression, and non-latching asynchronous cleanup reporting.
- Parallel and required Claude reviews found valid defects during extraction: callback throws could
  suppress fatal reporting; the fatal latch was read before confinement validation; unexpected
  synchronous submission failures bypassed owner poisoning; owner-inline and off-owner submission
  failures were classified inconsistently; the `Error` test did not prove poisoning; and two
  diagnostic messages lost or misstated execution context. All findings were fixed. The final
  bounded Claude review returned `NO_ACTIONABLE_FINDINGS`.
- The exact staged production slice passed
  `:TrafficCapture:trafficReplayer:compileJava --parallel --max-workers=18 --rerun-tasks` in an
  isolated worktree in 21 seconds.
- The serialized focused run passed all 56 tests: eight `OwnerTransitionRunnerTest` cases and 48
  `TargetConnectionOwnerTest` cases, with `--no-parallel --max-workers=1`.
- The next bounded S5 slice is the five-file fixture compile closure already identified by the S5b
  exact-tree failure. It must align fixtures with typed lifecycle APIs without recognizing
  `CancellationException` as a substitute for an explicit cleanup outcome.
- S5 remains open. Fixture/test migration and the complete green module run are still required before
  adding the S5 End entry.
