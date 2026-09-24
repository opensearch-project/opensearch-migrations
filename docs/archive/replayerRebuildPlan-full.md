# Historical full replayer rebuild plan

> **NON-AUTHORITATIVE ARCHIVE.** Retained in full for history. The only still-active material formerly
> owned here is reproduced without semantic change in the focused
> [`../replayerRebuildPlan.md`](../replayerRebuildPlan.md): D1–D18 (§2), PA1–PA3 (§3.2),
> R1–R19 (§6.5), and deployed-configuration compatibility (§7). Use Plan A or explicitly selected
> Plan B for sequencing.

# Replayer Rebuild Plan

**Status:** execution-ready implementation plan

**Date:** 2026-09-21

This document is the standalone execution plan for rebuilding the traffic replayer and completing
its interoperability with the capture proxy. It defines implementation order, code disposition,
required evidence, configuration compatibility, and final acceptance. It does not define or change
product behavior.

## Reader orientation

### Authoritative design

Read these documents before implementing:

- [Capture and Replay Architecture](../captureAndReplay/captureAndReplayArchitecture.md) — externally
  observable protocol and system behavior;
- [Proxy Capture Protocol](../captureAndReplay/proxyCaptureProtocol.md) — proxy capture, publication,
  failure, and retirement behavior;
- [Replayer Processing and Commit Architecture](../captureAndReplay/replayerProcessingAndCommitArchitecture.md)
  — ownership, ordering, cancellation, demand, and commit structure;
- [Replayer Low-Level Design](../captureAndReplay/replayerLowLevelDesign.md) — shared replayer owner and
  message rules;
- [Kafka Source and Replay Intake Low-Level Design](../captureAndReplay/replayerKafkaSourceAndIntakeLowLevelDesign.md)
  — Kafka-source and replay-intake classes, messages, and state transitions; and
- [Connection and Request Replay Low-Level Design](../captureAndReplay/replayerConnectionAndRequestLowLevelDesign.md)
  — target-connection, request, retry, pacing, and tuple behavior.

[Asynchronous Message-Passing Programming Guide](../captureAndReplay/asyncMessagePassingProgrammingGuide.md)
provides the implementation pattern for typed owner inputs. `managedFleetCaptureRecovery.md` is a
future compatibility boundary only; it adds no implementation scope to this plan.

The documents above are authoritative. If this plan, existing code, or a test appears to conflict
with them, stop and consult the design owner before changing behavior.

### How to use this plan

1. Read §1 and the execution contract in §3.1.
2. Treat the defect inventory in §2 and class dispositions in §5 as a snapshot of the starting
   branch. Recheck a cited location if the branch has moved.
3. Execute S0, then S1–S15 in §4. Follow the PA1–PA3 proxy checkpoints in §3.2 alongside them.
4. At every step boundary, update the §6.5 traceability evidence and re-read §3.1 as required there.
5. Do not call the result complete until every condition in §10 passes.

Findings use `file:line` references so the mechanism can be checked directly. Short class-relative
paths refer to the matching package under `TrafficCapture/trafficReplayer` unless another module is
named. A finding marked **(verified)** was spot-checked against the starting tree.

### Execution map

| Work | Purpose |
|---|---|
| S0 | Build deterministic shared test fixtures |
| S1–S3 | Restore envelope input, honest fatal handling, and owner-thread foundations |
| S4a–S4b | Prove record associations, then atomically replace commit authority |
| S5–S10 | Implement request lifetime, attempt permits, source reconstruction, and retry boundaries |
| S11–S12 | Land demand-driven Kafka intake and broker-time expiration together |
| S13–S14 | Implement generation cancellation and protocol-violation termination |
| S15 | Remove migration scaffolding and minimize the final implementation |
| PA1–PA3 | Audit, repair, and prove proxy/replayer interoperability |

### Key terms and notation

| Term | Meaning in this plan |
|---|---|
| State owner | The only component allowed to mutate one named group of state |
| Partition generation | One process-local uninterrupted ownership period for a Kafka partition |
| Record association | One unfinished request, response, or source-assembly dependency preventing a Kafka record from finishing |
| Connection turn finished | Target sends and retries are done, so the next request on that captured connection may run |
| Request processing finished | Target work, final source-response handling, tuple durability, and resource release are all complete |
| Tuple durable | The tuple sink has acknowledged durable output; submission alone is insufficient |
| Process-fatal | The failure must reach `ProcessSupervisor`; it is not an ordinary retry or cancellation result |

| Symbol | Meaning |
|---|---|
| `E` | Configured capture-expiration interval |
| `S` | Maximum allowed backward Kafka `LogAppendTime` skew |
| `F` | Proxy-local maximum interval before a nonempty connection record is detached |
| `W` | Broker-time window for freezing unavailable source-response retry input |
| `P` | Retry-ready request-supply target per target event-loop thread |
| `T_threads` | Target Netty event-loop thread count fixed at startup |
| `N` | Per-partition request-supply target, `P * T_threads` |
| `B` | `LogAppendTime` of the record that completes a captured source request |
| `R` | `LogAppendTime` of a later evidence record being applied |
| `M` | Latest accepted exact heartbeat baseline for a writer and partition |
| `T_first` | `LogAppendTime` of the first traffic record used for restart fallback |

The authoritative documents use `T` independently for both event-loop thread count and first-traffic
restart time. This plan writes `T_threads` and `T_first` to keep those unrelated meanings distinct.
`D1`–`D18` identify starting defects, `S0`–`S15` identify implementation milestones, `PA1`–`PA3`
identify proxy checkpoints, and `R1`–`R19` identify required replayer proofs.

## 1. Guiding principles

- **The settled design is authoritative. Do not alter, weaken, extend, or reinterpret it without
  first consulting me.** When implementation reveals an ambiguity, contradiction, or apparently
  useful behavior that the design does not cover, stop at the design boundary and ask. Code,
  historical behavior, operational habit, and existing tests do not have authority to add a
  requirement.
- **Tests are evidence, not design authority.** A test defines required behavior only when that
  behavior is traceable to an explicit obligation in the authoritative design. Tests for behavior
  not listed in the design are non-defining. A failing outlier, legacy, timing-sensitive, or
  implementation-shaped test must not cause the implementation or design to change merely to make
  the test pass. Delete or rewrite such a test. If its behavior appears valuable, consult me before
  proposing a design change.
- **Prefer deletion and the smallest correct implementation.** There is no requirement to preserve
  large amounts of recently added code, internal compatibility layers, obsolete abstractions, or
  historical class boundaries. Reuse a mechanism only when doing so is smaller and clearer than
  implementing the settled owner and message model directly. Temporary adapters must have a named
  removal step.
- **Only the complete proxy and replayer-LLD deliverable may be called production sound.** Every
  S0–S15 milestone is non-production, even when its local exit criterion
  passes. Intermediate revisions may be incomplete and may temporarily contain migration
  scaffolding, but their incompleteness must be explicit and every temporary path must be removed by
  the final milestone. "Compiles" and "passes this step's tests" never imply production readiness.
- **The production-sound claim is deliberately narrower than managed-fleet soundness.** It covers the
  settled proxy capture protocol and the replayer low-level designs under their documented
  assumptions. It does not include fleet-wide capture coverage, managed recovery, automatic gap
  repair, or other guarantees from `managedFleetCaptureRecovery.md`. That managed-fleet code must
  not be implemented as part of this plan.
- **Soundness is not deferred.** Where a step would temporarily reintroduce a defect from §2, the
  step is reordered instead.
- **No second correctness model.** The branch currently holds two: disposition ledgers deciding
  commit per request, and offset trackers deciding commit per partition. At no point should a third
  be added "until the real one lands."
- **Delete tests that assert the old model before changing production.** Otherwise a correct
  refactor appears as a wall of red that hides real regressions. Deleting a non-defining test is
  preferable to carrying production code whose only purpose is satisfying that test.
- **Iterate with the smallest fast-failing test that proves the behavior.** Unit and deterministic
  component tests are the primary implementation loop. Long-running process, broker, or end-to-end
  tests confirm integration and final acceptance; they must not be repeatedly used to discover the
  next implementation mistake. When a large test finds a defect, split the discovered behavior into
  the smallest faster tests that reproduce it, fix and iterate there, then rerun the large test once
  as confirmation.

## 2. Starting-branch defects

Ordered by severity. "Fixed by" refers to §4 steps.

| # | Defect | Mechanism | Consequence | Fixed by |
|---|---|---|---|---|
| D1 | The replayer cannot read its own capture topic | `kafka/KafkaTrafficCaptureSource.java:813` calls `TrafficStream.parseFrom(kafkaRecord.value())`; the producer publishes `CaptureRecord` envelopes (`captureKafkaOffloader/.../CaptureKafkaPublisher.java:306,434`) **(verified)** | Total loss of function, not merely a compile failure | S1 |
| D2 | A request can own zero Kafka records | `CapturedTrafficToHttpTransactionAccumulator.java:371-372` calls `holdTrafficStream(tsk)` only after the whole record is applied, and `RequestResponsePacketPair`'s constructor (`:44-61`) never adds its own starting key **(verified)** | A record whose observations complete request *N* mid-record is associated only with request *N+1*. If *N+1* is filtered or fast, the offset commits while *N* is still retrying. Crash loses *N*'s tuple permanently with no redelivery | S4a, S4b |
| D3 | Ordinary target failures select `Retain` and halt the process | `lifecycle/ReplayDispositionPolicy.java:169,194,209,214` pass `haltReplay=true`; consumed at `TrafficReplayerCore.java:577-599` | A target 5xx withholds the commit forever *and* kills the replayer. The design gives `Retain` exactly three causes, none of them a target response | S4b |
| D4 | Mixed records are structurally unrepresentable | `lifecycle/RecordDispositionLedger.register` rejects a record already tracked (`:176-184`) | One record completing two requests is process-fatal. Two defensive comments in the accumulator (`:531-537`, `:787-793`) exist solely to dodge the resulting double-remove crashes | S4a, S4b |
| D5 | `commitSync` runs inside `onPartitionsRevoked` with no cancellation delivered | `kafka/TrackingKafkaConsumer.java:305-307` → `:326-328` → `safeCommit` → `:1036` blocking `commitSync`, then application callbacks at `:1040-1068` | A slow commit exceeds `max.poll.interval.ms` and triggers a second rebalance from inside the first. In-flight work for the revoked generation keeps running and may commit for a reassigned generation | S8 |
| D6 | The target-concurrency bound is a lie | `RequestSenderOrchestrator.java:1119` acquires the permit before scheduling preparation; gated at `:1166`; released only in `PreparedActorRequest.close` (`:335`) after every attempt, backoff, and source-response wait | `--max-concurrent-requests 50` can hold 50 permits with **zero** requests in flight to the target. The unbounded source-response wait pins a permit indefinitely | S6 |
| D7 | Required cross-owner submission is silently dropped | `lifecycle/ConnectionActor.post` (`:233-243`) catches `RejectedExecutionException` and returns `false`; callers at `:285,:473,:541,:588` ignore it | The event-loop termination listener fires on *termination*; the exception is thrown at shutdown *start*. In that window a completion is lost: the gate never completes, the head never advances, buffers leak, no fatal signal | S2 |
| D8 | A truncated source response is labelled `COMPLETE` | Close-triggered rotation calls `handleEndOfResponse(accum, COMPLETE)` (`CapturedTrafficToHttpTransactionAccumulator.java:668-677`); `ParsedHttpMessagesAsDicts.java:94,116` filters only `EXPIRED_PREMATURELY` | `CLOSED_PREMATURELY` renders partial bytes as a complete `sourceResponse` with a fabricated `response_time_ms`. A truncated status line flips a genuine target failure to success, and the record commits | S9 |
| D9 | No-response retry is capped at 4 | `http/retries/DefaultRetry.java:18` `MAX_RETRIES = 4`, applied to the no-response path at `:51-53` | An unreachable target yields a *terminal* result after five attempts. The request is recorded finished against a target that never received it | S10 |
| D10 | `NoTargetResponseObtained` is an exception, and any exception becomes a retry | `datahandlers/NettyPacketToHttpConsumer.abort` (`:793`) completes exceptionally; `http/retries/RetryCollectingVisitorFactory.retryAfterFailure` (`:74-83`) maps any `Throwable` to `RETRY` | Inverted in both directions: an expected no-response must be type-matched, and a genuine invariant breach ("another target request attempt is still active", `RequestSenderOrchestrator.java:701`) is laundered into a re-send | S5 |
| D11 | `ReplayEngine.admitWork` blocks on a stage only its own thread can complete | `ReplayEngine.java:133-139` `.join()`; `lifecycle/ReplayIntakeMailbox.execute` runs inline only when `!dispatching` (`:32-37`) | The current path works only because `dispatching == false`. The first input routed through the mailbox — e.g. an `AsyncPermitPool` completion, wired at `TrafficReplayerTopLevel.java:316-318` — deadlocks with no timeout | S3 |
| D12 | Poll failures become empty successes | `kafka/TrackingKafkaConsumer.safePollWithSwallowedRuntimeExceptions` (`:767-820`) returns an empty `ConsumerRecords` on any `RuntimeException` (`:818`) | An auth or serialization failure becomes an indefinite stream of empty polls. The replayer looks idle and healthy while making no progress | S8 |
| D13 | The fatal path halts instead of running the supervisor ladder | `ReplayProcessFatalHandler.onFatal` ends at `processTerminator.terminate(...)` (`:133`); both production wirings are `Runtime.getRuntime()::halt` (`TrafficReplayerTopLevel.java:171`, `TrafficReplayer.java:780`) | On event-loop loss the JVM dies immediately: up to `--tuple-max-buffer-seconds` (default 60) of buffered tuples discarded and no thread dump — losing exactly the diagnostics needed to identify the failed owner | S2 |
| D14 | Termination waits for orderly recovery in three places | `TrafficReplayerTopLevel.java:405` unbounded `for (var timeout = ofSeconds(60);; timeout = timeout.multipliedBy(2))`; `:59` two-minute actor limit; `TrafficReplayer.java:955-957` shutdown hook `.join()`s | When the Netty group is what died, the two-minute wait is for futures only that group could complete, then the doubling loop never exits. An operator sees a hung replayer instead of exit code 80 | S2 |
| D15 | Hard ownership caps deadlock by construction | `kafka/KafkaRecordOwnershipBudget` gates every record (`TrackingKafkaConsumer.java:722`); `isReadCapacityAvailable` (`:1112`) is what `BlockingTrafficSource.blockIfNeeded` parks on | A single oversized accumulation saturates the budget, reads stop, and the heartbeat record that would prove expiration and release that accumulation is behind the read barrier | S11 |
| D16 | `onPartitionsAssigned` does not pause the resulting assignment; pause/resume are whole-assignment | `TrackingKafkaConsumer.java:364-395` returns without pausing; `pause()`/`resume()` (`:487-527`) act on `assignment()` in bulk | The first poll after any rebalance returns records regardless of demand, so backpressure is faked by `seek` rewinds (`:747-753`). One partition waiting on cleanup stalls every other partition, and a demand-driven `resume()` un-pauses a partition still being torn down | S11, S8 |
| D17 | The connection owner forgets a request at turn end | `lifecycle/ConnectionActor.settleRequest` (`:508-528`) removes the command, untracks the obligation, and completes the single future; `runOrderedClose` (`:552`) terminates with no registry check | A connection owner terminates while tuple writes from it are outstanding, so later completion has no owner to route through — which is *why* the code routes commit authority around the connection owner into `ReplayTransaction`. D17 and D2 are the same defect from either end | S5 |
| D18 | Work is admitted under a fabricated partition identity | `TrafficReplayerCore.java:881-887` invents `new SourcePartitionKey("unpartitioned-session", 0, ...)` when no partition is known | That work is outside every real generation, so cancellation never matches it and the generation can be reported clean while it still runs — the precise condition that lets a commit pass an unfinished record | S3 |

Non-blocking but worth folding into the relevant step: `tracing/ChannelContextManager.java:127` does a
non-atomic read-modify-write on a refcount; `datatypes/ISourceTrafficChannelKey.java:12-14` supplies
`default int getSourceGeneration() { return 0; }`, which lets two lifetimes of one captured
connection collide on `ClientConnectionPool`'s default key path (`:46,:127`).

## 3. Where the branch and the design diverge structurally

The design names 18 components and 8 identity records. The branch does not yet contain those
component types, and 6 of the 8 identity records are absent, so this is a replacement structure
rather than a description of the branch. Three divergences drive most of the work:

**Commit authority.** The branch gives each request exclusive ownership of whole records and commits
each record immediately when its single owner disposes it. The design requires per-record trackers
holding many independent associations, one `RecordProcessingFinished` per record, and commit computed
as a contiguous prefix by the Kafka source owner alone. `RecordDispositionLedger`'s unit is
`(handle, String owner, SourcePartitionKey)` — one owner per record — and it holds no offset ordering
at all. Reaching the settled model requires replacement of that authority, not incremental edits to
the ledger.

**Owner threads.** One authorized owner thread is missing: a dedicated replay-intake thread. In the
starting branch, `main` becomes that owner by side effect at `ReplayIntakeMailbox.java:22` via
`TrafficReplayerTopLevel.java:314`. One unauthorized owner thread exists
(`BlockingTrafficSource.java:69-73`, which only parks on a semaphore), and the Kafka thread is a
submission target driven by `supplyAsync`/`.get()` from other threads rather than an owner draining
its own queue. There is no `wakeup()` call anywhere in the replayer — only a `WakeupException` catch.

**Request milestones.** The design has two per request: connection-turn finished, and
request-processing finished after tuple durability. The branch has one settlement point.

### 3.1 Agent execution contract

An implementation agent receives this plan together with the authoritative documents under
`docs/captureAndReplay/`. Before changing code, the agent must read
`captureAndReplayArchitecture.md`, `replayerLowLevelDesign.md` and both replayer child LLDs,
`replayerProcessingAndCommitArchitecture.md`, and the proxy-protocol sections referenced by §3.2 and
§3.3. Summaries, historical code, and historical tests do not replace that reading.

The agent must:

1. record the starting branch, commit, and dirty working-tree files and preserve unrelated user
   changes;
2. complete S0 before production changes, then work through S1–S15 in dependency order, treating
   every milestone as non-production;
3. delete obsolete code and non-defining tests whenever that produces the smaller faithful
   implementation;
4. maintain the traceability evidence in §6.5 while implementing and testing each obligation;
5. use named immutable values for owner crossings and report a rejected required submission as
   process-fatal;
6. stop and consult me when the design is ambiguous or an implementation choice would change
   behavior not already settled by the design;
7. never change the design to satisfy an outlier or historical test;
8. never implement managed-fleet behavior as part of this work;
9. land S11 and S12 in one review and merge unit, with no releasable revision between them;
10. at the start and end of every implementation step, re-read this execution contract and the
    traceability matrix in §6.5, then update the execution log with the obligations proved, still
    open, or blocked; and
11. use the smallest applicable deterministic test while implementing. If a long-running test finds
    a defect, stop using that test as the iteration loop, split the discovered behavior into the
    smallest faster unit or component tests that reproduce it, fix against those tests, and rerun
    the long test only for confirmation.

The final handoff must contain no temporary compatibility path other than the explicit
externally-required aliases and retired-option adapters in §7, shadow correctness authority, disabled
required test, unresolved TODO for a design obligation, or obsolete class listed for deletion in
§5.1.

### 3.2 Proxy posture and audit

The proxy implementation is assumed to be mostly complete. The agent should not begin with a proxy
rewrite. It must audit the existing implementation against `proxyCaptureProtocol.md`, retain the
working mechanisms, and make focused repairs where implementation roughness prevents the proxy from
satisfying the settled protocol or interoperating with the replayer.

The audit starts with:

- `CaptureProxy` for configuration, startup, shutdown, and process behavior;
- `KafkaCaptureFactory`, `CaptureKafkaMembership`, and `CaptureAssignmentPublisher` for assignment
  readiness and lifecycle;
- `CaptureRoutingState` for assignment-scoped `writerNodeId`, connection routing, heartbeat
  baselines, and local registry state;
- `CaptureKafkaPublisher` for envelope serialization, connection-local ordering, heartbeat
  serialization, acknowledgement handling, and publisher-lane retirement;
- `CaptureKafkaWriteGate` for capture-before-forward enforcement;
- `CaptureKafkaCapabilityProbe` and `TrafficTopicMetadata` for startup capability and
  `LogAppendTime` validation; and
- the proxy Netty pipeline and capture-offloader callbacks for request classification, connection
  lifecycle, observation ordering, and required failure propagation.

For each proxy obligation, classify the current code as:

- **verified** — implementation and deterministic test already match the protocol;
- **repair** — preserve the mechanism but fix a race, error path, configuration mismatch, resource
  leak, or protocol mismatch;
- **replace** — current code embodies a conflicting behavior and a smaller direct implementation is
  clearer; or
- **managed-only** — deliberately out of scope and not implemented.

A proxy test is retained only when it verifies an explicit proxy-protocol obligation. Existing proxy
code or tests must not be used to invent replayer behavior.

Begin the proxy audit early and record the classification for every responsibility above, but do not
gate S1 or any other function-restoring replayer work on completion of the audit. The audit and
focused proxy repairs proceed as a parallel workstream alongside the corresponding replayer steps.
All non-managed proxy obligations and the interoperability tests in §6.6 remain mandatory final
acceptance gates.

Treat the proxy workstream as a separately sized deliverable rather than incidental replayer cleanup.
Its known baseline is seven implementation groups in §3.2, ten acceptance areas in §6.6, and two
real-Kafka scenarios; repair scope is additional and is not estimated as zero merely because the
proxy is mostly implemented. The execution log tracks three proxy checkpoints:

- **PA1 — classify and estimate:** complete the responsibility classification, identify the existing
  proof for each area, and estimate the repair and missing-test scope no later than the end of S3;
- **PA2 — repair:** finish each focused proxy repair before accepting the corresponding
  proxy/replayer behavior; and
- **PA3 — interoperate:** pass the real-Kafka scenarios in §6.6 before S15 begins.

**PA2 repair backlog found from the replayer side.** These were discovered building the G1 supply-side
fixture (`docs/replayerRebuildPlanA-inPlace.md` G1) and are recorded here because PA2 is the milestone that
owns proxy repair — per `AGENTS.md` §2.1 a finding with no receiving milestone named in a plan is dropped
work, not deferred work. Status for each lives in `docs/replayerRebuildStatus.md`.

1. **The proxy's Kafka tests cannot start the proxy.** Every case in `KafkaConfigurationCaptureProxyTest`
   fails because nothing creates the traffic topic with `message.timestamp.type=LogAppendTime`, so the
   capability probe aborts startup. The repair is one `AdminClient.createTopics` call with that config,
   naturally in `KafkaContainerTestBase`; `ProxyWrittenTopic` in the replayer already does exactly this and
   can be copied. **Fix this first** — it masks the next item, so the two cannot be validated separately.
2. **A stale assert blocks every assertions-enabled capture test.**
   `StreamChannelConnectionCaptureSerializer:150` asserts `writerNodeId` and `connectionId` fit
   `MAX_ID_SIZE = 100`, commented as "the default size of netty connectionId and kafka nodeId". Actual is
   about 111: `ProcessHelpers.getNodeInstanceName()` returns `<base>_<uuid>`, never shorter than ~37 because
   the UUID alone is 36, and the proxy passes Netty's `Channel.id().asLongText()`, about 60. `MAX_ID_SIZE` is
   referenced nowhere else, so nothing sizes a buffer from it and production — which runs without `-ea` — is
   unaffected; the premise simply went stale. Either widen the budget to the real worst case or stop asserting
   on it. **The replayer carries a `-da:` workaround for this one class in
   `TrafficCapture/trafficReplayer/build.gradle`, which must be deleted as part of this repair.**
3. **`CaptureProxy` exits the JVM on a fatal capture error.** The handler calls `System.exit(78)`, which is
   correct for the real process but destroys the test JVM when `CaptureProxyContainer` runs
   `CaptureProxy.main` in-process — the symptom is a `SKIPPED` test and a Gradle "non-zero exit value 78"
   with the real cause only in the log above it. Needs an injectable exit hook so a test can observe the
   fatal transition instead of dying with it. PA3's real-Kafka scenarios will need this to assert on
   capture-failure behavior at all.
4. **Intentional capture suppression does not advance the successor stream's request baseline.**
   `StreamChannelConnectionCaptureSerializer.cancelCaptureForCurrentRequest` emits
   `RequestIntentionallyDropped` and resets request-assembly fields but does not increment `eomsSoFar`.
   A later `TrafficStream` therefore serializes `priorRequestsReceived` one request too low even though
   `proxyCaptureProtocol §4.1` says the marker advances the request sequence. Increment the same
   connection-owned counter used by `commitEndOfHttpMessageIndicator`, and add a serializer test that flushes
   after the drop and proves the successor stream carries the advanced baseline.
5. **Source interim responses need their own capture-protocol observations.** Integrate the source-side
   protocol model from PR #3000: add whole and segmented interim-response observations, using protobuf field
   numbers `17` and `19` because this branch already assigns `18` to
   `connectionObservationSequence`; classify source `1xx` responses other than `101` into those observations;
   and prove both forms directly. Ordinary `Write` observations remain source-response bytes and are never
   reinterpreted as interim responses. There is no compatibility decoder or fallback for captures written
   before this protocol change. G3 consumes the typed observations and keeps request assembly active until
   its request end marker.

**PA2 Exit:** all five focused repairs above have direct tests, their temporary replayer-side workarounds are
deleted, intentional suppression followed by a stream boundary preserves the successor
`priorRequestsReceived` ordinal, and source interim responses are emitted only as the typed whole or segmented
observations consumed by G3, with no ordinary-`Write` compatibility path.

If one agent owns both proxy and replayer, these are explicit scheduled checkpoints, not fictional
parallelism. Proxy work may be interleaved with replayer work, but PA3 cannot be deferred into final
cleanup.

### 3.3 Proxy/replayer interoperability contract

The final implementation must obey this boundary:

| Proxy output or shared setting | Required replayer interpretation |
|---|---|
| Kafka record value | Exactly one serialized `CaptureRecord`; no trial decoding and no alternate raw-`TrafficStream` path |
| `TrafficStream` payload | Apply observations in payload order to source assembly; use Kafka metadata for partition ownership rather than deprecated payload partition fields |
| `WriterPartitionHeartbeat` payload | Key time state by `(writerNodeId, Kafka partition)` and apply the settled `E + S` expiration rules |
| `CaptureCapabilityProbe` payload | Validate and account for the record without creating writer or connection state |
| `PAYLOAD_NOT_SET` or malformed envelope | Enter the bounded capture-protocol-violation path and do not commit past the record |
| Kafka partition | Authoritative partition identity for the application record |
| Kafka `LogAppendTime` | Broker-time evidence for retry boundaries and expiration; never source event time or local wall-clock time |
| `writerNodeId` | Assignment-scoped proxy writer identity; never inferred from Kafka membership or connection ID |
| `E` and `S` | Proxy and replayer receive the same run configuration and reject invalid values at startup |
| `F` | Proxy-local record-detachment deadline; it creates record boundaries but is not replayer expiration input |
| Connection lifecycle | Only `CloseObservation` is terminal; disconnect and connection-exception observations remain diagnostic |
| Source interim response (`1xx`, excluding `101`) | Encode only as the typed whole or segmented interim-response observation added by PA2 item 5; G3 consumes it without ending request assembly. Ordinary `Write` is never an alternate encoding |
| Kafka headers | Diagnostic only unless a future approved protocol version explicitly assigns semantics |

The proxy may be changed where this audit finds a protocol mismatch or rough implementation edge.
Such changes smooth the existing protocol implementation; they must not redesign the protocol,
create a second envelope, or add managed-fleet coordination.

## 4. Sequencing

S0 precedes production changes. S1–S3 establish the foundations; after S3, follow the listed step
order. S4a and S4b are separate review checkpoints, while S11 and S12 must land together. Every step
lists its local exit criterion, but every intermediate state remains a non-production rebuild
milestone. The proxy audit is a parallel workstream rather than an S1 prerequisite. No individual
step is a production candidate. Production soundness is evaluated only after S0 and S1–S15, the
replayer LLD verification obligations, and proxy-to-replayer interoperability are complete.

### S0. Build deterministic fixtures before production changes

This step owns the fixtures required by later exit criteria. They are designed around the settled
messages and ownership boundaries, not around obsolete production class shapes:

- promote one `FakeClock` into `testFixtures` and delete the duplicate test-local clocks;
- extract a reusable `TestEventLoop` with `runUntilIdle`, `runNext`, and explicit time advancement;
- add `RecordScript`, extending `TrafficStreamGenerator`, to emit `CaptureRecord` sequences with
  explicit Kafka partition, offset, `LogAppendTime`, `writerNodeId`, and payload case; and
- add `PumpedKafkaSource`, driven only by the test through `runOnce`, with scripted batches and
  observations of pause reasons, resume, coalesced wakeup, and commit submission. Define it against
  the LLD's typed source messages and a pluggable source-owner driver so S8 can connect the production
  `KafkaSourceOwner` without rewriting test scripts.

Each fixture must have narrow self-tests proving deterministic ordering, time advancement, script
exhaustion, and failure reporting. At least one self-test must supply a deliberately incorrect
expected-association set and prove that the fixture rejects it. Expected associations used by S4a
must be literal or independently constructed; they must not be computed by the same transition logic
as `RecordWorkTracker`. The fixtures use no sleep, polling loop, or unmanaged background thread.

**Exit:** the four fixtures compile from `testFixtures`, their self-tests pass, and a test can pump a
mixed traffic/heartbeat/probe script through a source-driver stub with exact broker timestamps and
observe pause, wakeup, and commit events deterministically; corrupting one expected association makes
the fixture fail for the intended mismatch.

### S1. Restore function: envelope decoding and the compile break

The starting branch fails `:TrafficCapture:trafficReplayer:compileJava` with 40 errors **(verified)**, all in
files this plan deletes. `ProxyNoMoreWrites` is absent from `captureProtobufs/src/main/proto/TrafficCaptureStream.proto`
at HEAD and at `139853523^`, and is imported by four production files and three test files — so
nothing under `kafka/` compiles or runs in the starting tree. Test results from other revisions are
not evidence for this branch.

- Delete the liveness and absence-proof cluster (§5.1) and its tests.
- Decode the `CaptureRecord` envelope exhaustively: `TrafficStream`, `WriterPartitionHeartbeat`,
  `CaptureCapabilityProbe`, and `PAYLOAD_NOT_SET` as a violation. At this milestone heartbeats and
  probes are accepted and counted, but the final broker-time state machine does not arrive until
  S12. This is an explicit non-production limitation, not an alternative expiration design.
- Fix the dump tooling in the same change. `KafkaTopicDumper`, `TrafficStreamDumper`, and
  `HttpTransactionDumper` break twice — on the envelope and on the source interface — and are the
  cheapest way to prove decoding works.

**Exit:** the replayer reads and dumps a topic written by the current proxy, and every envelope case
is handled explicitly. The milestone remains non-production because broker-time expiration and the
remaining LLD behavior are incomplete. Not "`compileJava` passes."

### S2. Make failure honest

Three narrow diffs, all independently valuable, all prerequisites for later steps that add submission
points.

- Route `RejectedExecutionException` from `ConnectionActor.post` (`:233`) and `startHead` (`:389`) into
  the fatal handler instead of returning `false` (D7).
- Implement the supervisor ladder behind the existing `ReplayProcessFatalHandler` seam:
  `System.exit` → bounded hooks (ten minutes) → thread dump to stderr → `Runtime.halt` with the same
  code (D13). `ReplayProcessFatalHandlerTest` already asserts codes 80 and 89 and the terminator
  interaction, so it validates the new implementation unchanged.
- Bound shutdown: replace the doubling loop (`TrafficReplayerTopLevel.java:405`) with one named limit,
  make the fatal path skip `beginReplayShutdownAfterIntakeFence` entirely, and change
  `awaitReplayerShutdown` (`TrafficReplayer.java:955-957`) to signal without joining (D14).

**Exit:** killing a target event loop under load produces exit code 80, gives bounded shutdown hooks
their documented opportunity to flush tuples, emits a thread dump when the watchdog bound is
reached, and cannot hang indefinitely waiting for work owned by the dead event loop.

### S3. Give replay intake its own thread

- Introduce `ReplayIntakeOwner` and `ReplayIntakeInputQueue` directly. The owner starts and owns its
  dedicated thread, drains named immutable inputs, and applies each input completely before taking
  the next. Do not turn `ReplayIntakeMailbox` into another long-lived generic `Runnable` queue.
- Delete both `ReplayIntakeMailbox.await` overloads and move the loop body of
  `TrafficReplayerCore.pullCaptureFromSourceToAccumulator` (`:1065-1121`) behind typed intake
  messages. Delete `ReplayIntakeMailbox` after its temporary call sites are migrated.
- Remove the `.join()` in `ReplayEngine.admitWork` (`:138`) by returning a stage the intake owner
  composes (D11).
- Introduce the exact `PartitionGenerationId` value and remove the fabricated partition key
  (`TrafficReplayerCore.java:881-887`); a message with no known generation is a bug, not a default
  (D18). Do not preserve an `int sourceGeneration` escape hatch in the owner APIs.

The Kafka source is still pull-driven here, so behavior is unchanged — but the owner boundary becomes
real and the `AtomicReference` field bag at `TrafficReplayerCore.java:133-137` collapses into
owner-confined plain fields.

**Exit:** no `join`, `get`, `await`, or semaphore acquire on the intake thread; owner-thread guard
installed and proved by deterministic owner/input-queue tests, including rejected cross-thread
mutation and an injected owner failure reaching `ProcessSupervisor`. End-to-end coverage is a later
integration confirmation, not the iteration loop for this step.

### S4a. Move record→work association into intake

This checkpoint makes the association model independently provable before commit authority changes.
The legacy commit path remains the sole authority during S4a, so this remains explicitly
non-production and must not be deployed.

- Introduce `RecordWorkTracker`: per `KafkaRecordId`, an association set, a `closedToNewAssociations`
  flag, and a `completionEmitted` latch. `associate` is many-per-record and never rejects a second
  association; `relabel(recordId, oldAssociation, newAssociation)` replaces the ledger's
  `transfer(owner)`; `associationFinished` returns nothing.
- Create an association as **each observation is applied**, not once per record at
  `CapturedTrafficToHttpTransactionAccumulator.java:371-372`.
- Records contributing source-response bytes stay associated until tuple durability.
- Use `RecordScript` expectations as the oracle for the exact association set. If
  `holdTrafficStream` remains temporarily, compare it only as a one-way diagnostic: the mixed-record
  cases that expose D2 and D4 are expected to disagree with the legacy model. Never weaken the new
  association set to make equality with the old mechanism pass.

**Exit:** a record carrying `read+EOM` for request *N* and `read` for request *N+1* has exactly both
associations; keep-alive and cross-record source-response cases match their `RecordScript` oracle; and
no record emits completion while an expected association remains. The legacy commit path is still
the only commit authority and the checkpoint is not deployable.

### S4b. Cut over commit authority atomically

- Introduce a new generation-scoped `ObservedRecordCommitQueue` in observed poll order. Do not
  implement it by preserving `OffsetLifecycleTracker`'s `PriorityQueue<Long>` or numeric-offset
  assumptions. Register each record before intake applies it and accept exactly one
  `RecordProcessingFinished(KafkaRecordId)` after its tracker closes with no associations.
- Make `RecordProcessingFinished` the only input that can advance a Kafka commit position. Delete
  `RecordHandle.commit`/`releaseWithoutCommit`, `RecordDispositionLedger.commit`/`acceptDisposition`,
  and the accumulator's `onTrafficStreamIgnored` → commit path
  (`TrafficReplayerCore.java:862-869`).
- Delete `ReplayDispositionPolicy` and strip `haltReplay` (D3). A target result never chooses Kafka
  redelivery or process termination.
- Remove `holdTrafficStream` and the two defensive comments at
  `CapturedTrafficToHttpTransactionAccumulator.java:531-537,:787-793`.
- Do not run the old and new commit authorities concurrently in production. A shadow comparison is
  acceptable in a test, diagnostic harness, or explicitly non-production intermediate revision, but
  the final implementation has exactly one commit authority.

**Exit:** commit advances only across consecutive completed entries from the observed-record head; a
gap at the head blocks every completed record behind it; duplicate registration or completion is an
invariant failure for an active generation; and no request, accumulator, disposition policy, target
result, or legacy record handle can commit or retain a Kafka record.

### S5. Two request milestones and a registry that outlives the turn

- Split `ConnectionActor` into `TargetConnectionOwner` and `RequestReplayOwner`, with the admission and
  execution queues separated and owner confinement replacing `lifecycleLock` (`:234,:247`).
- `settleRequest` (`:508`) becomes `ConnectionTurnFinished`: clear the turn, remove the *execution*
  entry, emit exactly one `ConnectionRequestFinished`, advance the head, and **keep the request in the
  registry** (D17).
- Add `RequestProcessingFinished` as the second milestone, gated on `TupleDurable`, routed through the
  connection owner and never directly to intake. Owner removal requires an empty registry.
- Report `FirstTargetWriteSubmitted` at `NettyPacketToHttpConsumer.writePacketAndUpdateFuture` (`:734`)
  — the only place the fact is observable — exactly once per request, never repeated on retry, and
  local to the connection owner.
- Replace `TargetOutcome` with `TargetAttemptOutcome permits TargetResponseObtained, NoTargetResponseObtained`;
  `abort` (`:793`) returns a value rather than completing exceptionally, and
  `RetryCollectingVisitorFactory.retryAfterFailure` (`:74`) stops converting throws into retries (D10).

**Exit:** every admitted request produces at most one connection-turn completion and at most one
request-processing completion, and a normally completed request produces both, in that order, with the
second after tuple durability.

### S6. Permits per attempt, head only

- Remove permit acquisition from `PreparationCoordinator.start` (`:1119`) and the `permitReady` gate
  (`:1166`); remove the permit from `PreparedActorRequest` (`:294,:335`).
- The connection owner acquires a permit for the execution head immediately before the turn and
  answers `RequestAttemptPermit` on retry; the request owner releases it as soon as the attempt
  produces an outcome, and never holds it across retry backoff, source-response waits, or tuple writes.
- Restructure `sendRequestWithRetries` (`:683`) from self-recursion into one attempt per call.
- Preserve source-time shifting as required by `captureAndReplayArchitecture.md` §10. A positive
  configured `speedupFactor` controls nominal target pacing through the existing `TimeShifter`
  responsibility; Kafka `LogAppendTime` never replaces source event time for target scheduling, and
  same-connection request order takes priority over an elapsed nominal send time.

`AsyncPermitPool` is already correct — nonblocking acquire, withdrawable pending acquisition, FIFO
waiters, idempotent close. Only the call site is wrong. Rename it `TargetAttemptPermitProvider`.

**Exit:** with the permit count set to 1, exactly one target attempt is in flight at a time and
queued requests do not consume permits; `--max-concurrent-target-attempts` means what it says (D6);
and deterministic timing tests prove positive `speedupFactor` pacing without allowing requests on
one source connection to overtake.

### S7. Delete the remaining disposition and transaction vocabulary

- Delete `RecordDisposition`, `RecordDispositionLedger`, `ResolvedRecordIndex`, the three commit
  exception classes, `ReplayTransaction`, `ReplayTransactionRegistry`, and
  `ReplayTransactionMetrics`. The request registry required by S5 is an owner-confined
  `Map<ReplayRequestId, RequestReplayOwner>` inside `TargetConnectionOwner`, not a retyped
  transaction registry with locks, pending commands, runway-loss state, or a second mailbox.
- Delete `retainRecordsRejectedBySettlement` (`TrafficReplayerCore.java:505-538`), whose javadoc
  admits it exists to plug a leak the single-owner model creates.
- Tuple output becomes unconditional and retried to durability: `TupleWriteResult permits TupleDurable,
  TupleWriteCancelled`, no `Failed` variant, no `NotRequired` skip path
  (`ReplayTransaction.java:469-471`), and `TupleWriter` owns all sink retries.
- Delete `OffsetLifecycleTracker` after its callers use `ObservedRecordCommitQueue`. Write
  deterministic tests for out-of-order completion, a gap at the head, contiguous advancement,
  duplicate registration, duplicate completion, and completion of an unregistered record. Preserve
  neither the old class nor its priority-queue implementation.

**Exit:** commit advances only across a contiguous completed prefix from the earliest uncommitted
record; a gap at the head blocks everything behind it; no code outside the Kafka source owner can
commit; no stored `Commit`/`Retain` disposition, replay transaction, or transaction registry exists
anywhere.

### S8. Kafka source becomes an owner

- Add a Kafka-source input queue plus deferred, coalesced `wakeup()` — suppressed during rebalance
  callbacks, `commit`, `pause`, `resume`, and while already draining control inputs.
- Convert `touch` (`KafkaTrafficCaptureSource.java:669-676`), `readNextTrafficStreamChunk` (`:697-704`),
  `acknowledgeSessionTermination` (`:380-388`), and `commitTrafficStreamAsync` (`:1146-1149`) from
  `supplyAsync`/`.get()`/`execute` into typed queue submissions.
- Replace `commitDataLock` (`TrackingKafkaConsumer.java:177`) with an owner-thread assertion one site
  at a time; the lock can remain and simply become uncontended, so every intermediate state compiles.
- `onPartitionsAssigned` pauses the complete resulting assignment before returning; pause and resume
  become per-partition with three independent, non-aliasing reasons: batch demand, prior-generation
  cleanup, revocation/shutdown (D16).
- Remove `safeCommit` from `cleanupRevokedPartitions` (`:326-328`) (D5) and stop swallowing poll
  exceptions (`:808-819`) (D12).

**Exit:** a queued input wakes a long poll without interrupting a rebalance callback or another Kafka
operation; one partition paused for cleanup does not stall unrelated partitions; a poll failure is
fatal rather than an empty success.

### S9. Honest source reconstruction

- Stop labelling close-truncated responses `COMPLETE`: either add a positive end-of-response
  determination or mark rotation-on-close incomplete (`CapturedTrafficToHttpTransactionAccumulator.java:514-527,:668-677`).
- Delete `SourceReconstructionPolicy` after moving its valid assembly-result mapping into
  intake-owned `SourceConnectionState` transitions. `SourceConnectionState`, under
  `ReplayIntakeOwner`, emits `SourceResponseComplete` or `SourceResponseIncomplete`; the separate
  broker-time retry boundary emits `SourceResponseUnavailableForRetry`. Do not retain a free-standing
  `SourceOutcome`/`RecordDisposition` bridge.
- Collapse `ReconstructionStatus`'s five values into the design's two final results, and make
  `IncompleteFinalSourceResponse` carry no partial bytes represented as complete. The final result must
  not distinguish captured close from expiration.
- Fix `ParsedHttpMessagesAsDicts.java:94,116` so any incomplete response is marked and never rendered
  as a complete `sourceResponse` (D8).

**Exit:** a source connection that dies mid-response produces a tuple that says so, and its truncated
status line cannot flip a target failure into a success.

### S10. Retry boundary

- Split the single `finishedAccumulatingResponseFuture` (`RequestTransformerAndSender.java:30`) into two
  independently frozen inputs: `RetrySourceResponse permits CompleteSourceResponseForRetry,
  SourceResponseUnavailableForRetry` and `FinalSourceResponse permits CompleteFinalSourceResponse,
  IncompleteFinalSourceResponse`, each accepted exactly once.
- Implement `R - B >= W` on Kafka `LogAppendTime` with an irreversible first crossing, emitting
  `SourceResponseUnavailableForRetry` **before** the crossing record's payload is applied. A later
  complete response may still become the tuple input but never changes retry policy.
- Remove `MAX_RETRIES` from the no-response path (`DefaultRetry.java:18,:51`) (D9).

**Exit:** a missing captured response bounds the retry decision deterministically rather than blocking
on it; no target response retries indefinitely while the generation and process remain valid; retry
behavior does not depend on Kafka read timing.

### S11. Demand model, then delete the caps

The order within this step matters: deleting the caps before the demand model exists creates a
deadlock and evidence-starvation window.
S11 and S12 must land in the same review and merge unit. There is no releasable or production
checkpoint after the caps are removed but before broker-time expiration is installed.

- First add `RequestNextPartitionBatch` / `PartitionRecordBatch`, at most one outstanding request per
  partition generation, `N = P * T_threads` over requests with resolved retry input and unfinished
  target turns, and empty polls that neither resolve a request nor reach intake.
- Only then delete `KafkaRecordOwnershipBudget` and its two flags (D15), `BlockingTrafficSource`,
  `BufferedFlowController`, `ReplayReadGate`, `ReplayProgressController`, and
  all lookahead-based read gating. The parser compatibility adapter for the retired lookahead options
  remains as specified in §7 but owns no flow-control state.

Do not retarget `ReplayProgressController` as another read gate. Its global source-time minimum,
quiescence tokens, and read-ahead watermark are not part of the settled partition-local demand
model. Reuse small transition-checking or deque ideas only when they reduce the final code; do not
preserve the class or its global authority. Preserve its valid failure policy explicitly: an
impossible owner-state transition must be reported to `ProcessSupervisor`, not converted into a
recoverable value or silently ignored.

**Careful — `--lookaheadTimeSeconds` is load-bearing for Kubernetes.** `orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/replayer.ts:195-198`
passes only `["---INLINE-JSON", <json>]`, and `coreUtilities/.../jcommander/JsonCommandLineParser.java:394-398`
derives JSON keys from `@Parameter` aliases, so the key exists *only* because that alias exists. Also
referenced in `orchestrationSpecs/packages/schemas/src/userSchemas.ts:674,777-782`,
`dockerSolution/src/main/docker/docker-compose.large-requests.yml:111`, and
`docs/reconfiguringWorkflows.md:313`. Remove the behavior and internal dependency, but retain the
deprecated parse-and-warn adapter from §7 until a separately approved compatibility removal.

**Exit:** intake requests another batch only while retry-ready supply is below `N`; a fast complete
response satisfies supply before `B + W`; a target-finished or cancelled request cannot re-enter
supply via later retry-input resolution; no byte or record cap can stop the reads needed to reach
retry or heartbeat evidence.

### S12. Broker-time expiration

This step closes the explicit non-production expiration gap left by S1. It follows the demand model
because the final implementation must be able to continue reading the records that provide heartbeat
and broker-time evidence. It lands in the same review and merge unit as S11. No earlier milestone may
be described or deployed as production sound.

- `(writerNodeId, partition)` baselines from accepted heartbeats; the ordinary rule `R - M >= E + S`;
  the restart fallback `R - T_first >= E + 2S`, replaced by the first heartbeat after `T_first`,
  which is accepted unconditionally.
- A higher-offset record more than `S` below the partition's greatest observed `LogAppendTime` is
  process-fatal before it can authorize expiration or commit.
- An expired lifetime and a later fresh lifetime for the same `CapturedConnectionId` get different
  `ConnectionProcessingId`s and cannot route messages to one another. Replace
  `ISourceTrafficChannelKey.getSourceGeneration`'s `return 0` default (`:12-14`) and rekey
  `ClientConnectionPool`.
- Remove wall-clock packet-timeout behavior and delete `ExpiringTrafficStreamMap` — already
  unreachable, since `CapturedTrafficToHttpTransactionAccumulator.java:459-460` routes to
  `observeWithoutExpiration`, which never calls `runDeferredExpiry`. Retain only the deprecated
  parse-and-warn compatibility adapter for `--packet-timeout-seconds`,
  `--packetTimeoutSeconds`, and `--observedPacketConnectionTimeout` as specified in §7.

**Exit:** expiration is driven by broker time end-to-end and is owned entirely by replay-intake
state; the structural scanner and wall-clock expiration are absent.

### S13. Cancellation and generation cleanup

- `GracefulGenerationCancellation(deadline)` and `ForceGenerationCancellation`, scoped to
  `PartitionGenerationId`, with a 5-second default grace. Graceful cancels every queued request that
  has not submitted a target write and lets one that has finish its target and tuple chain to the
  deadline; a request cancelled before sending emits no `ConnectionRequestFinished`.
- `onPartitionsRevoked` returns as soon as intake **accepts** force cancellation, not after cleanup.
- Typed per-owner cleanup results, distinct from normal completion and never authorizing commit;
  successor generations stay paused until `GenerationCleanupFinished`.
- Re-type `AbortReason.SOURCE_REASSIGNMENT`, which currently maps to a completed-null success
  (`TrafficReplayerCore.java:104-106`).
- Delete the fixed `quiescentPeriodMs` delay. The successor-generation cleanup barrier is the settled
  mechanism; retain only the deprecated parse-and-warn compatibility adapter from §7.

**Exit:** cancellation cleanup cannot produce a commit request; a newer local generation of a partition
waits for the previous one's cleanup; unrelated partitions continue throughout.

### S14. Protocol violation

- Loud log, metric, and alarm; mark the record commit-ineligible; block commits at and past that
  offset so no later record passes it; pause intake; fixed 60-second `protocolViolationDrainLimit`;
  terminate with a code distinct from 80. Poison pill on restart.

**Exit:** a deliberately corrupted record terminates the process within the drain limit without
committing past the violating offset, and restart stops at the same record.

### S15. Cleanup

Collapse the four `TrafficReplayerTopLevel` constructors, three pool factories, and six
`setupRunAndWaitForReplay*` overloads; split `TrafficReplayer` into a parameter holder, startup
validation, bootstrap, and dump-mode main; move `createCommitContext` and both
`createTrafficStreamContextFor*` onto `IRootReplayerContext` and drop the duplicated public fields;
add the new activity registrations to `ActiveContextMonitor.EXPECTED_MAX_AGE` (`:145-164`); delete
`TrafficReplayerTopLevel.shouldRetry` (`:652`); fix the `ChannelContextManager` refcount race (`:127`).

Finish with a deletion and minimality audit. Every remaining production class must map to a named
design component, a narrow port, a protocol value, or a demonstrably reused low-level utility. Delete
wrappers, factories, compatibility overloads, metrics types, and abstractions that have only one
caller and add no ownership or testing seam. Net line count is not itself a correctness criterion,
but when two implementations are equally sound, choose the smaller one.

## 5. Class dispositions

These are final-state dispositions, not a requirement to perform every deletion immediately.
Temporary extraction or migration use is allowed only where §4 names it, and all such scaffolding
must be gone by S15. When a class appears in more than one discussion, its implementation step
controls the migration sequence and this section controls whether it exists in the final tree.

### 5.1 Delete

**Liveness and absence-proof model:** `KafkaLivenessScanner`, `KafkaLivenessSnapshotRecord`,
`KafkaNoMoreWritesRecord`, `KafkaSupersededTrafficRecord`, `KafkaRecordOwnershipBudget`, `AbsenceProof`,
`CompleteSnapshotSpan`, `FollowUpRequirement`, `ScanEvidence`, `SourceControlEvent`, `KafkaBehavioralPolicy`,
`TrafficSourceReaderInterruptedClose`.

**Disposition framework:** `RecordDisposition`, `RecordDispositionLedger`, `ReplayDispositionPolicy`,
`ReplayTransaction`, `ReplayTransactionRegistry`, `ResolvedRecordIndex`,
`SourceReconstructionPolicy`, `SourceCommitNotAcceptedException`,
`SourceCommitUnknownAfterRevocationException`, `SourceRunwayLostException` (no producer in main code
at all), `ReplayTransactionMetrics`.

**Flow control:** `BlockingTrafficSource`, `BufferedFlowController`, `ReplayReadGate`,
`ReplayProgressController`, `ExpiringTrafficStreamMap`, `BehavioralPolicy`.

**Replaced owner and callback scaffolding:** `ReplayIntakeMailbox`,
`SourcePartitionLifecycleListener`, `UnconfiguredSourcePartitionLifecycleListener`,
`OffsetLifecycleTracker`. A throwing unconfigured listener may exist only as a temporary migration
guard and must be deleted when typed owner queues become authoritative.

**Additional obsolete compatibility types:** `KafkaCommitOffsetData`, `PojoKafkaCommitOffsetData`,
`ISimpleTrafficCaptureSource`, `ITrafficStreamWithKey.isResumedConnection`.

### 5.2 Extract required mechanisms, then delete the original classes

The following classes contain mechanisms worth inspecting, but preserving the original class is not
a goal. Extract only code that remains smaller and clearer than a direct implementation of the
settled component, then delete the original class.

| Class | Lines | Extract into |
|---|---|---|
| `ConnectionActor` | 740 | `TargetConnectionOwner` + `RequestReplayOwner`. Mailbox-transition, ordered-head, obligation, and termination logic are candidates for extraction, not APIs that must survive |
| `RequestSenderOrchestrator` | 1701 | Owner directory, `TargetChannelPort` implementation, request preparation state, and fatal-signal adapter. Delete coordination code that reproduces owner state |
| `CapturedTrafficToHttpTransactionAccumulator` | 816 | Intake-owned `SourceConnectionState` + `PartitionIntakeState`; the expiry machinery leaves with `ExpiringTrafficStreamMap` |
| `KafkaTrafficCaptureSource` + `TrackingKafkaConsumer` | — | `KafkaSourceOwner` plus reusable config/auth construction. Roughly 80% deletion is expected and welcome |
| `TrafficReplayerCore` | 1145 | `ReplayIntakeOwner` (the 830-line `TrafficReplayerAccumulationCallbacks` is that owner in all but name) + tuple helpers |
| `TrafficReplayerTopLevel` | 864 | Bootstrap + `ReplayShutdownSequencer` + observability bootstrap |
| `TrafficReplayer` | 1029 | `ReplayerCliParameters`, `ReplayerStartupValidation`, `ReplayerProcessBootstrap`, `DumpModeMain` |

The current three-way cut of the top-level classes is an inheritance seam, not an ownership boundary:
`TrafficReplayerCore` is abstract solely to leave `shutdown(Error)` to its subclass, while the subclass
mutates the base's `AtomicReference` field bag (`:133-137`).

### 5.3 Refactor only where the resulting type matches the design

| Class | Required result |
|---|---|
| `ReplayIdentity` | Replace the existing records with the exact eight design identities. Temporary call-site adapters may exist within a cutover, but owner APIs must not accept raw `int sourceGeneration`, fabricated source IDs, or obsolete session/work identities |
| `ReplayOutcomes` | Preserve sealed exhaustiveness, but split the umbrella vocabulary into the design's domain-specific result types and delete obsolete variants |

Also refactor rather than keep: `TargetResponseClassifier` (separate attempt outcome from
source-vs-target comparison), `RequestTransformerAndSender` (stop passing the permit pool down),
`ClientConnectionPool` (rekey on `ConnectionProcessingId`; expose the resolved event-loop count so `T_threads`
is knowable — `numThreads == 0` currently means Netty picks `2 * availableProcessors`),
`ConnectionReplaySession` (six constructors, four of which adapt away the cancellation signal),
`NettyPacketToHttpConsumer` (bound the `activateLiveChannel` self-recursion at `:176`),
`ParsedHttpMessagesAsDicts`, `RequestResponsePacketPair`, `Accumulation`, `RootReplayerContext`,
`KafkaCommitStateMetrics` (drop the saturation counter and the `/maximum` denominators).

### 5.4 Keep as-is

`AsyncPermitPool` (rename only), `CompletionGate`, `ActorMailbox`, `NettyEventLoopActorMailbox`,
`OwnerThreadGuard`, `ResourceOwnership`, `ByteBufList`, `ByteBufListProducer`, `OwnedPreparedRequest`,
`AttemptPayload`, `DiagnosticPayload`, `SourceResponseNormalizer`, `InterimHttpResponseHandler`,
`OpenSearchDefaultRetry` (signature changes only), `ActiveContextMonitor`, `OrderedWorkerTracker`,
`NettyUtils`, `RefSafeHolder`, `RefSafeStreamUtils`, `TrafficChannelKeyFormatter`, `ReplayContexts`,
`IReplayContexts`, `ReplayProcessFatalMetrics`, `AsyncPermitPoolMetrics`, `ResourceOwnershipMetrics`,
`TargetExchangeStateMetrics`, `SourceTargetCaptureTuple`, `ResultsToLogsConsumer` (observer, never the
durability authority), `TransformedTargetRequestAndResponseList`.

### 5.5 New

Identity records: `PartitionGenerationId`, `KafkaRecordId`, `WriterPartitionId`, `CapturedConnectionId`,
`ConnectionProcessingId`, `ReplayRequestId`, `PartitionBatchRequestId`, `CancellationDeadline`. None is
added to the capture protobuf.

Kafka source: `KafkaSourceOwner`, `KafkaSourceInputQueue`, `PartitionSourceState`,
`ObservedRecordCommitQueue`, `WakeupController`.

Replay intake: `ReplayIntakeOwner`, `ReplayIntakeInputQueue`, `PartitionIntakeState`,
`WriterPartitionTimeState`, `SourceConnectionState`, `RecordWorkTracker`, `GenerationCleanupTracker`.

Connection and request: `TargetConnectionOwner`, `ConnectionAdmissionEntry`, `ConnectionExecutionEntry`,
`RequestReplayOwner`, `TargetAttemptPermitProvider`, `TargetChannelPort`, `TupleWriter`.

Process: `ProcessSupervisor`, `ProtocolViolationTerminator`.

Sealed result types: `TargetAttemptOutcome`, `RetryDecision`, `RetrySourceResponse`, `FinalSourceResponse`,
`TupleWriteResult`, `RequestAdmissionResult`, `RequestPreparationResult`, and the per-owner cleanup
results.

## 6. Test strategy

Build fixtures first. Delete tests that assert obsolete behavior before changing that behavior. Add
the smallest proof tests with each implementation step rather than postponing testing until the
rebuild is assembled.

**Iteration rule:** use unit tests and deterministic, no-sleep component tests as the primary
implementation loop. Long-running broker, process, and end-to-end tests run at named integration
checkpoints and final acceptance; they must not be run repeatedly to discover and fix one mistake at
a time. When a large test exposes a defect, split the discovered behavior into the smallest focused
unit or component tests that reproduce it, fix and iterate against those faster tests, and then
rerun the large test once to confirm the integration behavior. Retain only the thin integration
scenario needed to prove boundaries that smaller tests cannot.

### 6.0 Tests do not create requirements

Every retained or new test must cite the design section or named invariant it verifies. A test that
cannot identify such an obligation is not a reason to preserve code or behavior.

When a test fails:

1. determine whether the asserted behavior is explicitly required by the authoritative design;
2. if it is required, fix the implementation or the test mechanism without changing the behavior;
3. if it is not required, classify the test as obsolete, outlier, implementation-shaped, or
   otherwise non-defining and delete or rewrite it; and
4. if the behavior appears useful enough to add to the design, consult me before changing either the
   design or implementation to support it.

Passing the historical suite is not an acceptance criterion. Passing the smallest suite that proves
the settled design is.

### 6.1 Four fixtures, owned by S0

S0 builds and self-tests these fixtures before production changes. Later steps may add scripts and
assertions but must not create competing clocks, event loops, source pumps, or record builders.

1. **`FakeClock`** — written twice (`KafkaStructuralExpirationTest.java:1002-1030`,
   `TrackingKafkaConsumerTest.java:56`) plus three fake `now()` overrides under `lifecycle/`. Promote
   one to `testFixtures` and delete the rest.
2. **`TestEventLoop`** with `runUntilIdle`/`runNext`/`advance` — already at `ConnectionActorTest.java:511-526`.
   Extract it and convert `RequestSenderOrchestratorLifecycleTest` (1194 lines, 17 `Thread.sleep(1)`
   poll loops) and `ConnectionReplaySessionStateTest` (18 poll loops) from polling to pumping.
3. **`PumpedKafkaSource`** — new, and the highest-leverage item here. The Kafka source on a
   single-threaded executor the *test* drives via `runOnce()`, never a background thread, with a
   scripted batch sequence and assertion hooks on pause, resume, wakeup, and commit submission. It is
   the only way to test the demand model, empty polls, the three pause reasons, wakeup coalescing, and
   broker-time expiration deterministically.
4. **`RecordScript`** — new; extend `TrafficStreamGenerator`. Emits `CaptureRecord` sequences with
   explicit `LogAppendTime`, `writerNodeId`, partition, and payload type, so `E + S`, `E + 2S`,
   `R - B >= W`, and the backward-timestamp fatal are written as arithmetic rather than as timing.

### 6.2 Delete first: tests that pass while asserting the old model

These are worse than no tests — they would fail a *correct* implementation:

- `SourceReconstructionPolicyTest.java:23-31` asserts timestamp-only expiry **commits**. Production's
  own comment at `SourceReconstructionPolicy.java:32` says that is wrong.
- `ExhaustiveCapturedTrafficToHttpTransactionAccumulatorTest.java:129,132` asserts zero duplicates
  across a restart, contradicting its own comment at `:110` and the at-least-once guarantee.
- `e2etests/TrafficReplayerRunner.java:107` retries the replayer until a run passes, and the
  commit-monotonicity assertion at `:209` is commented out — while the file remains the commit oracle
  for two e2e suites. (`:251-261` also uses `Math.max` where `Math.min` was intended.) Cap restarts at
  the number the test intends and restore the assertion as non-decreasing.
- `RequestFilterE2ETest.java:230` asserts a filter's unexpected throw still advances the cursor; that
  is process-fatal under the new model.
- `TrafficSourceReaderInterruptedCloseWiringTest.java:221` is named `doesNotSkipReplayEngineClose` and
  never observes `replayEngine.closeConnection`. Self-declared placeholder at `:167`.
- `FilteringTransformerWrapperTest.java:173` — `…AndReleasesBytes` asserts no refcount.
- `HttpTransactionDumperTest.java:128` — `assertTrue(contains("EXPIRED") || isEmpty())` passes on empty
  output.
- `WriteSegmentMissingTimestampTest.java:113` — `assertNotNull` on the value whose correctness is the
  point.

Bulk deletions follow the §5.1 class deletions: the liveness and absence-proof cluster (~2,100 lines),
the expiring-map trio (322), read gating (367), disposition policy and ledger (865), the quiescent pair
(281), and `OpenSearchDefaultRetryE2ETest` (409 lines duplicating `OpenSearchDefaultRetryTest`'s
assertion surface at roughly ten times the flake cost). Roughly 19 classes and 3,900 lines.

**Salvage before deleting `KafkaStructuralExpirationTest`:** its `MutableClock` (`:1002-1030`) and
`ScanForbiddenConsumer` (`:93-105`), plus the evidence-bypass test at `:221` — which is the kernel of
the correct obligation that no cap may block reads needed to reach retry or heartbeat evidence.

### 6.3 Layering

- **Unit, no clock and no threads** — the bulk. Record accounting, broker-time state machine, retry
  arithmetic, demand accounting, source assembly, the two connection queues and the request registry,
  request state, and message exhaustiveness. Reuse useful cases from the existing `lifecycle/`
  directory, but do not preserve its class count or 3,635-line size as a target.
- **Property tests where the space is combinatorial.** `CapturedTrafficSettlementPropertyTest` (512
  seeded cases, exactly-once oracle) is the best-designed test in the tree; retarget its oracle from
  accumulator callbacks to record accounting. Add one more: randomized interleavings of record
  application, request completion, and cancellation must never commit a record with outstanding work
  and never skip a prefix. That subsumes most of `RecordDispositionLedgerTest`'s 22 hand-written cases.
- **Component tests with a real event loop but no socket** — `EmbeddedChannel`/`LocalEventLoopGroup` for
  ordering, permits, refcount handoff, and exit-80 on event-loop death.
- **Replayer-module integration: keep exactly six, each with a class-level `@Timeout`.**
  `ReplayProcessFatalHandlerTest`, `e2etests/ReplayerProcessExitTest`,
  `e2etests/TupleWriteBlockingBehaviorTest`, `e2etests/GlobalSerialReplayE2ETest`,
  `e2etests/FullReplayerWithTracingChecksTest`, and one real-broker rebalance test. The proxy and
  cross-module interoperability scenarios in §6.6 are separate final-acceptance coverage.

Fix `ReplayerProcessExitTest`'s gating while keeping its assertion. `tuplesInS3 >= committedOffset`
(`:196`) is the single best acceptance assertion on the branch, but three assertions hang off
`Thread.sleep(5_000)` (`:141`) and `Thread.sleep(10_000)` (`:159`), and the first is a *negative*
assertion behind a fixed sleep — it also passes if the replayer never consumed anything. A negative
assertion needs positive evidence that the thing it denies had its chance: wait until consumer lag
drops, *then* assert the committed offset is still zero.

Bound every unbounded loop: `KafkaCommitsWorkBetweenLongPollsTest.java:91`,
`KafkaTestUtils.java:90,105`. In the starting tree each failure is a ten-minute CI hang with no stack.
This is the cheapest single change in the suite.

### 6.4 Required replayer multi-member integration coverage

There is **no replayer-side multi-member test anywhere**, and `writerNodeId` appears in zero replayer
tests — so `WriterPartitionId` and per-`(writerNodeId, partition)` heartbeat baselines have no coverage
path. The proxy side already has the pattern:
`trafficCaptureProxyServer/src/test/java/.../KafkaMembershipRebalanceCaptureTest.java` (745 lines) runs
two members in one group against a real broker and fences by container pause rather than sleeping
(`:200-217`). Reuse its broker and fencing technique, but write the smallest replayer-side test that
proves the required rebalance behavior rather than copying the class wholesale.

### 6.5 Replayer-LLD traceability matrix

This table mirrors the verification list in `replayerLowLevelDesign.md` §9. Do not rely on a
hard-coded item count: update the table only after consulting me if the authoritative list changes.

| # | Required design obligation | Implementation steps | Minimum proof |
|---|---|---|---|
| R1 | Each mutable value has one named owner | S3, S5, S8, S12, S13 | Owner-state inventory plus per-owner transition-table tests proving owner-thread enforcement and impossible transition → `ProcessSupervisor` |
| R2 | Every admitted request has at most one connection-turn and one processing completion; normal completion has both | S5, S7 | Request-state transition tests and durable-tuple component test |
| R3 | At most one intake-issued batch request is outstanding per partition generation; assignment may also hold its one bootstrap entitlement | S8, S11 | Pumped Kafka-source state test |
| R4 | Every delivered batch matches its bootstrap or explicit entitlement and batches are fully applied in delivery order | S8, S11 | Batch entitlement/delivery transition test |
| R5 | Demand remains open while retry-ready supply is below `N` | S10, S11 | Demand arithmetic tests across all recomputation inputs |
| R6 | Fast responses satisfy supply before `B + W`; slow or missing responses keep it open until resolved | S10, S11 | Explicit broker-time `RecordScript` tests |
| R7 | Target-finished or cancelled requests cannot re-enter supply | S10, S11, S13 | Out-of-order completion and retry-input tests |
| R8 | First target write remains local cancellation state | S5, S13 | Connection/request-owner routing test proving no intake message |
| R9 | Queued source input wakes a long poll without interrupting protected Kafka work | S8 | Wakeup race and callback-coalescing tests |
| R10 | Request-processing completion never precedes tuple durability | S5, S7 | Delayed/blocked tuple-writer test |
| R11 | Record completion never occurs with unfinished associations | S4a, S4b, S7 | RecordWorkTracker state and property tests |
| R12 | A record shared by several requests waits for all of them | S4a, S4b | Mixed-record deterministic and property tests |
| R13 | Source-response records remain associated through tuple durability | S4a, S4b, S5, S7, S9, S10 | Cross-record response plus delayed tuple test |
| R14 | Expired and later fresh connection lifetimes cannot cross-route messages | S12 | `ConnectionProcessingId` expiration/reuse test |
| R15 | Cancellation cleanup cannot produce a commit request | S13 | Cancellation-at-every-state property test |
| R16 | A successor generation waits for prior-generation cleanup | S8, S13 | Revoke/reassign generation-barrier test |
| R17 | Unrelated partitions continue during another partition's cleanup | S8, S11, S13 | Multi-partition component and real-broker tests |
| R18 | No hard cap can block records needed for retry or heartbeat evidence | S11, S12 | Oversized accumulation followed by evidence test |
| R19 | Every unexpected owner failure reaches the process supervisor | S2, S3, S5, S8, S11 | Per-owner injected failure and impossible-transition tests, plus the event-loop process test |

### 6.6 Proxy and interoperability acceptance

The proxy audit is complete only when the non-managed obligations below are either covered by an
existing deterministic test or by the smallest new test needed:

| Area | Required proof |
|---|---|
| Envelope | Traffic, heartbeat, and probe publication each produce one `CaptureRecord` with the expected payload case |
| Capture-before-forward | A configured mutating request cannot submit execution-enabling source bytes before complete Kafka acknowledgement |
| Producer safety | Idempotence, `acks=all`, ordering-preserving in-flight configuration, and ambiguous-result handling cannot be weakened |
| Assignment identity | Every usable assignment creates a fresh `writerNodeId`; existing connections retain their original writer and partition |
| Heartbeats | Initial assignment readiness, serialized publication, accepted broker-time baseline, deadline handling, and irreversible late/failure behavior match the protocol |
| Connection records | Per-connection order, monotonic `F` deadline, continuity fields, size/terminal flush, and no empty idle record |
| Terminal processing | Terminal observation and all predecessors are acknowledged before local connection-registry removal |
| Failure propagation | Local synchronous and unobserved asynchronous capture failures reach the process-wide failure path |
| Configuration | Proxy and replayer reject locally invalid `E` and `S`; deployment and interoperability fixtures supply equal values; the traffic topic is verified as `LogAppendTime` |
| Non-managed compromise | The configured standalone fail-closed or fail-open behavior follows the proxy protocol without adding a managed-controller acknowledgement path |

At least one real-Kafka interoperability test must:

1. start the current proxy implementation with explicit `E`, `S`, and `F`;
2. publish through a topic configured with `message.timestamp.type=LogAppendTime`;
3. send captured HTTP traffic through the proxy;
4. consume the resulting `CaptureRecord` values with the rebuilt replayer;
5. prove target-request ordering and expected retry behavior;
6. make tuple durability observable;
7. prove Kafka commit does not pass unfinished record work; and
8. include heartbeat/probe records and at least one record containing work for more than one request.

A second real-Kafka scenario must cover two proxy writer identities and a replayer rebalance. These
may be two methods in one fixture if that is smaller and clearer than separate classes.

### 6.7 Verification commands and environment

The agent must keep a short execution log containing the command, result, and design obligations
proved. At minimum, the final verification runs:

```bash
./gradlew :TrafficCapture:captureProtobufs:test \
  :TrafficCapture:captureKafkaOffloader:test \
  :TrafficCapture:trafficCaptureProxyServer:test \
  :TrafficCapture:trafficReplayer:test \
  --no-daemon
```

During development, use the narrowest applicable module or `--tests` filter, but the final run must
not depend on test filtering or ignored required tests. The final environment requires:

- Docker/Testcontainers for real Kafka and process tests;
- a traffic topic configured with `message.timestamp.type=LogAppendTime`;
- a deterministic target server capable of response, no-response, delay, disconnect, and retry
  scenarios;
- an observable tuple sink that can block, retry, acknowledge durability, and expose completed
  tuples; and
- Netty leak detection enabled for ownership-sensitive suites.

Final acceptance also requires `git diff --check`, the repository-configured formatting, lint, and
check tasks for every touched module, and a source search confirming that every class and option in
§5.1 and §7's delete list is absent. Do not claim "no new compiler warnings" unless S0 first records a
stable warning baseline or the build makes the relevant warnings errors; without one of those
mechanisms that statement is not an executable criterion.

## 7. Configuration

**Add** (none of these exists in the starting tree; grep returns zero hits for each): `E` default
30s, `S` clock-skew bound, `W` default 5s, `P` default 2, cancellation grace default 5s,
`protocolViolationDrainLimit` fixed 60s, the ten-minute watchdog. Startup must reject `P < 1`, `T_threads < 1`,
`W <= 0`, `E <= 0`, `S < 0`.

**Rename the concept but preserve the established external configuration:** add
`--max-concurrent-target-attempts` and `--maxConcurrentTargetAttempts` as the preferred names, while
retaining `--max-concurrent-requests`, `--maxConcurrentRequests`, and workflow
`spec.maxConcurrentRequests` as deprecated aliases for the same target-attempt limit. The compatibility
requirement is established by `docs/reconfiguringWorkflows.md:314`,
`orchestrationSpecs/packages/schemas/src/userSchemas.ts:676`,
`dockerSolution/src/main/docker/docker-compose.large-requests.yml:115`, and
`JsonCommandLineParser.java:394-398`, which derives accepted inline-JSON keys from every
`@Parameter` alias. Updating only repository call sites does not preserve already-deployed
configurations.

Rename internal fields and constructor parameters to `maxConcurrentTargetAttempts`. If a new workflow
field `spec.maxConcurrentTargetAttempts` is added, the old field must remain accepted with identical
mapping; conflicting simultaneous values must fail startup rather than acquire order-dependent
meaning.

Internal blast radius: `TrafficReplayerTopLevel.java:120,124,133,143,156,167,181,192,316`,
`TrafficReplayerCore.java:124,144,153,168,185-188`,
`testFixtures/.../RootReplayerConstructorExtensions.java:31,39`, and
`coreUtilities/.../EnvVarParameterPullerTest.java:271`.

**Make authoritative:** `--num-client-threads` becomes `T_threads` and must reject `0`.

**Retain and validate:** `--speedup-factor`, `--speedupFactor`, and workflow
`spec.speedupFactor`. Positive configured `speedupFactor` is part of the authoritative replay-timing
formula in `captureAndReplayArchitecture.md` §10, not obsolete flow control. Preserve the
`TimeShifter` responsibility, reject nonpositive values, and test target pacing independently from
Kafka broker-time behavior.

**Remove behavior while preserving deployed-option parsing:** lookahead-based read gating,
`quiescentPeriodMs` reassignment delay, and wall-clock observed-packet expiration are replaced by the
settled demand, generation-cleanup, and broker-time-expiration mechanisms. Nevertheless,
`spec.lookaheadTimeSeconds`, `spec.quiescentPeriodMs`, and
`spec.observedPacketConnectionTimeout` are documented safe rolling fields at
`docs/reconfiguringWorkflows.md:313,317,316`, and their CLI/inline-JSON aliases are already deployed.
For these options:

- continue accepting `--lookahead-time-window`, `--lookaheadTimeWindow`,
  `--lookaheadTimeSeconds`, `--quiescent-period-ms`, `--quiescentPeriodMs`,
  `--packet-timeout-seconds`, `--packetTimeoutSeconds`, and
  `--observedPacketConnectionTimeout`;
- represent their presence in dedicated nullable compatibility fields so omission is distinguishable
  from a historical default;
- emit one clear startup warning per supplied option naming the replacement mechanism and stating
  that the value is ignored;
- do not route the value into any owner, timer, demand calculation, expiration state, or cleanup
  state;
- update new workflow generation and examples to stop emitting these fields by default, while keeping
  the schema fields accepted and marked deprecated for existing resources; and
- remove an adapter only through a separately approved compatibility change, not as incidental
  cleanup.

Tests must prove that every retired deployed key parses, emits the expected warning, has no behavioral
effect, and does not cause an unrecognized-key failure.

**Delete outright after confirming no user-facing schema or documented deployment surface:**
`--max-owned-kafka-records`, `--max-owned-kafka-bytes`, and `--disable-liveness-scanner`. If the
repository audit finds an external surface for one of them, apply the same explicit compatibility
policy rather than allowing a generic parser failure.

**Pre-existing drift to fix independently:** `--preview-bytes-read` and `--preview-bytes-write` default
to 24 in code (`TrafficReplayer.java:155,161`) while their own descriptions and
`trafficReplayer/README.md:212-213,234,236` say 64. `userSchemas.ts:698` defaults `speedupFactor` to 1.1
against Java's 1.0. If the authoritative design does not settle either default, consult me before
choosing one; do not infer the answer from whichever existing layer is easiest to preserve.

## 8. Settled readings and required consultation

The authoritative design already settles these points; implementation and tests must not reopen
them:

1. `FirstTargetWriteSubmitted` is local connection-owner cancellation state. It is not forwarded to
   replay intake and does not affect Kafka demand.
2. Every newly assigned `PartitionGenerationId` creates a new `ObservedRecordCommitQueue` beginning at
   Kafka's assigned position. Mutable queue state from a revoked generation does not survive into the
   successor.

One implementation detail still requires consultation before S8 and S13 choose a Kafka API sequence:
whether a commit map staged before revocation should be submitted from inside
`onPartitionsRevoked` while the callback is processing grace-period commit inputs. The design permits
commit attempts during the grace while Kafka accepts them, but the exact staged-operation sequence
must not be invented from old code or tests. Consult me and, if necessary, clarify the design before
implementing that path.

## 9. Out of scope

**Managed-fleet behavior must not be implemented yet.** `managedFleetCaptureRecovery.md` exists to
confirm that the proxy and replayer architecture can support a future controller; it does not add
requirements to this implementation plan.

The final proxy and replayer may be described as production sound for the guarantees in the proxy
protocol and replayer LLDs without providing managed-fleet guarantees. In particular, this plan does
not implement or claim:

- fleet-wide capture coverage establishment;
- a `CaptureCoverageEstablished` payload or equivalent replayer input;
- a fleet or capture-domain controller;
- automatic recovery after a capture gap;
- cross-run orchestration that proves all expected proxy writers were represented; or
- the stronger completeness and recovery guarantees reserved for managed-fleet work.

The absence of those guarantees is a documented scope boundary, not a defect in this deliverable.
Tests expecting managed-fleet behavior are non-defining for this plan and must not cause that behavior
to be added. There is no fleet or capture-domain controller code in the starting replayer tree — `fleet`
appears twice, both in prose comments, and `captureDomain` has zero hits.

## 10. What "done" means

The combined implementation must prove every current item in `replayerLowLevelDesign.md` §9. That
LLD currently contains 19 listed obligations; the traceability table, rather than that count, is
authoritative for execution. Four obligations the starting branch gets wrong and that no starting
test covers are:

- one record shared by several requests cannot finish after only one request;
- records contributing source-response bytes remain associated through tuple durability;
- cancellation cleanup cannot produce a commit request;
- no hard record or byte limit can stop the reads needed to reach retry or heartbeat evidence.

The proxy must also satisfy `proxyCaptureProtocol.md`, and an interoperability test must prove the
supported proxy output can be consumed by the replayer through Kafka without an alternate envelope,
header convention, or trial-decoding path.

Exactly one design obligation is already well covered and should stay that way: exit code 80 on
event-loop-owner loss, asserted symbolically at `ReplayProcessFatalHandlerTest.java:94`, proved through
a forked JVM halt at `:68-89`, held distinct from 89 and from codes 1-5 at `:95-101`, and exercised
end-to-end at `RequestSenderOrchestratorLifecycleTest.java:966-1006`.

The final implementation also passes a minimality review:

- every mutable state field has one named owner;
- every cross-owner transition uses a named immutable value;
- every production class maps to a design component, narrow port, protocol value, or necessary
  low-level utility;
- no obsolete disposition, transaction, structural-liveness, global-read-gate, or compatibility
  framework remains; and
- no retained class or overload exists solely to satisfy a non-defining historical test.

Only after all of §10 is satisfied may the proxy and replayer deliverable be called production sound.
That statement is limited to the proxy protocol and replayer LLD guarantees and deliberately excludes
the managed-fleet guarantees listed in §9.
