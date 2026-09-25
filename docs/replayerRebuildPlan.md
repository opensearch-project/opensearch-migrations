# Replayer Rebuild Focused Supplemental Authority

This file is intentionally limited to the four still-active authorities inherited from the archived full
plan: D1–D18 (§2), PA1–PA3 (§3.2), R1–R19 (§6.5), and deployed-configuration compatibility (§7).
Their authority content is preserved from
[`archive/replayerRebuildPlan-full.md`](archive/replayerRebuildPlan-full.md). Use
[`replayerRebuildPlanA-inPlace.md`](replayerRebuildPlanA-inPlace.md), or explicitly selected Plan B, for
sequencing. The authoritative designs remain under `captureAndReplay/`.

The preserved §3.2 phrase “the execution log tracks” now points procedurally to the live status register;
the archived execution log is non-source. The historical-S routing note below and the current marked-source
citations in §2 are editorial maintenance corrections. They do not change any obligation.

Within §2, the authoritative content is each defect's identity, description, mechanism, and consequence;
the `Fixed by` `S` labels and the sentence referring to §4 are historical provenance, not current ownership.
Within §6.5, each obligation and minimum proof remains authoritative; its `Implementation steps` `S` labels
are likewise historical. Current sequencing and ownership come only from the selected Plan A/Plan B section
and the live register.

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

Non-blocking latent findings in carried, currently marked source: `ChannelContextManager.RefCountedContext.release`
(`:54-58`), reached from `releaseContextFor` (`:88-95`), does a non-atomic read-modify-write and relies on
assertions at `:56` and `:91`; `ISourceTrafficChannelKey.getSourceGeneration` (`:23-25`) supplies
`default int getSourceGeneration() { return 0; }`, which lets two lifetimes of one captured
connection collide on `ClientConnectionPool`'s default key path.

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

   **Evidence landed.** `SourceInterimResponseCaptureTest` proves fragmented/coalesced `100`, `102`, `103`,
   and multiple interims are typed while `101`, `413`, and `417` remain ordinary final writes.
   `StreamChannelConnectionCaptureSerializerTest` proves byte-exact whole and forced-segmented serialization.
   `SourceReconstructionTest` proves byte/order/ordinal/request continuation, later-final reconstruction, and
   rejection of ordinary `Write` as a compatibility encoding; `RecordAssociationAccumulatorTest` proves an
   interim-only record stays associated until the request lifecycle releases it. `TrafficStreamDumperTest`
   and `HttpTransactionDumperTest` prove both diagnostic consumers preserve the typed distinction.
   `SourceAssemblyEvidenceTest` proves real proxy → Kafka → rebuilt intake → `dump-http` interoperability for
   `100`, `103`, and the later `200`. The owner settled all three edge semantics found by the first review:
   source bytes that do not complete an interim header are ordinary final-response writes ordered before
   exception and close; a final response received before request EOM is retained and attached when EOM
   allocates the request identity; and a typed interim with no current request is ignored and never carried
   forward. Those rulings are now explicit in `kafkaLLD §9` and `procCommit §5.2`.

   One direct `/private/tmp` Codex worker at `b08be26e7` independently falsified typed classification, request
   continuation, incomplete-response ordering, suppressed-response isolation, and exception-time
   required-capture failure handling; every mutation produced the intended assertion failure and the worker
   restored the exact clean revision. A synthetic reviewer-injected telemetry fault at `f1832b5b0` confirmed
   that the defensive terminal-close path still captures the held write and close when diagnostic reporting
   fails. This was not an observed production failure and creates no additional PA2 telemetry requirement;
   the defensive terminal-close behavior remains without expanding telemetry-failure handling. The final
   production review returned no design-conformance defects.

   No source interim-header size bound is added. The source server is trusted, while the existing client-input
   bounds already constrain untrusted input. The typed producer/consumer chain and all five PA2 repairs are
   therefore closed on the recorded evidence.

**PA2 Exit:** all five focused repairs above have direct tests, their temporary replayer-side workarounds are
deleted, intentional suppression followed by a stream boundary preserves the successor
`priorRequestsReceived` ordinal, and source interim responses are emitted only as the typed whole or segmented
observations consumed by G3, with no ordinary-`Write` compatibility path. PA2 and G3 are closed with the
evidence recorded above and in `docs/replayerRebuildStatus.md`.

If one agent owns both proxy and replayer, these are explicit scheduled checkpoints, not fictional
parallelism. Proxy work may be interleaved with replayer work, but PA3 cannot be deferred into final
cleanup.

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

<!-- End of preserved focused authority. -->
