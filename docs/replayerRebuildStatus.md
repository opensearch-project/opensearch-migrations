# Replayer Rebuild Status

Compact live register. Historical narrative and closed review detail are non-authoritative and retained in
[`archive/replayerRebuildStatus-through-2026-09-25.md`](archive/replayerRebuildStatus-through-2026-09-25.md).
Update rows, not prose checkpoints.

**States:** `proved` · `open` · `deferred(<milestone>)` · `blocked(<reason>)` · `wontfix(<reason>)`

Short design names: `captureArch`, `proxyProtocol`, `replayerLLD`, `kafkaLLD`, `connLLD`, `procCommit`,
and `async` refer to files under `docs/captureAndReplay/`. R1–R19 and D1–D18 are plan-side labels only.

## Context footprint

Active task-context membership changed intentionally: before compaction it included the live execution log
and full supplemental plan; afterward the 75,197-byte execution log is archived and the supplemental plan
contains only its four surviving authorities. The reduction therefore includes reclassification, not deletion.
Archived bytes are retained and excluded from active context on both sides. “Active corpus” also includes
Plan A and Plan B.

| Measurement | Before | After |
|---|---:|---:|
| Active task context | 461,527 bytes | 102,359 bytes |
| Active corpus | 560,959 bytes | 201,730 bytes |

## Current state and evidence

| Item | State | Current evidence / owner |
|---|---|---|
| Active sequencing | Plan A | Plan B only on explicit instruction; focused supplemental authority is `replayerRebuildPlan.md` |
| G0 | proved | One in-place module; member-level limbo; deterministic fixtures; marker verifier |
| G1 | proved | Kafka `dump-raw` reaches `TrafficReplayer.main`; real proxy/topic envelope evidence; multi-partition bounds and metadata checked |
| G2 | proved | Kafka source owner, wakeup boundary, generation/commit handling, and repeated design-conformance/falsification review; R9 proved |
| G3 | `blocked(PA2/design)` | Implementation, falsification, and repeated conformance review complete through `3dbec52cf`; the committed focused six-class validation reports 56 passing tests, and the final review found no production-code conformance defect. Closure waits only on PA2 item 5's typed producer/interoperability and the exact authorized design amendment removing ordinary-`Write` fallback semantics |
| G4 | proved | Commit authority complete through `49dd4cea0`: ordered observed-record head, one operation-level resolution latch, monotonic recommit, rejected/unknown distinction, revocation conservation, fixed-cardinality telemetry, 65-test focused validation, exact-revision falsification, and resumed Claude conformance review |
| Production compile | proved | Last recorded passing with the required Gradle Spotless exclusions |
| Compiled test set | proved | Last recorded 108 tests, 0 failures; inherited unresolved tests remain marked and owned below |
| Limbo regions | open | Measured 2026-09-25: 213 files with `REBUILD-LIMBO-START`; historical counts drift and the START grep is authoritative |
| Limbo integrity | proved | `TrafficCapture/trafficReplayer/tools/verify-limbo-markers.sh`; rerun after every marking change |
| PR strategy | decided | Keep draft PR #3394 and the same branch through the red-CI stretch; repair CI at the swing |
| DCO debt | `deferred(post-G12)` | Seven inherited PR commits lack sign-off: `5150f20ed`, `d7aa79540`, `34d286154`, `68cf95444`, `997a6c44f0`, `139853523`, `6fb2cb040`; preserve history during the eventual rewrite |

## Design obligations R1–R19

| ID | Obligation | Milestone | Defined in | Required tests | State | Notes |
|---|---|---|---|---|---|---|
| R1 | One named owner per mutable value | G9 | procCommit §3.1; replayerLLD §2; async §2 | connLLD §19.6; procCommit §13.1 | open | |
| R2 | At most one turn + one processing completion; normal has both | G5 | procCommit §3.2; connLLD §10, §13 | connLLD §19.2 | open | |
| R3 | At most one intake-issued batch request per generation, plus one assignment bootstrap entitlement | G2, G7 | kafkaLLD §4.2, §13; replayerLLD §2 | kafkaLLD §17.4 | open | G2 proved explicit-request rejection; G7 adds and proves the independent bootstrap slot |
| R4 | Delivered batch matches bootstrap or explicit entitlement and is applied in delivery order | G7 | kafkaLLD §5.3, §13 | kafkaLLD §17.4 | open | |
| R5 | Demand open while retry-ready supply below N | G7 | procCommit §8.1; kafkaLLD §13 | kafkaLLD §17.4 | open | |
| R6 | Fast responses satisfy supply before B+W | G6 | procCommit §8.2; kafkaLLD §11 | kafkaLLD §17.3, §17.4 | open | |
| R7 | Finished/cancelled cannot re-enter supply | G6, G7 | kafkaLLD §12, §13; procCommit §8.1 | kafkaLLD §17.4 | open | |
| R8 | Target-write start stays local cancellation state | G5 | connLLD §8; procCommit §3.2 | connLLD §19.2 | open | |
| R9 | Queued input wakes long poll without interrupting protected work | G2 | kafkaLLD §5.4; replayerLLD §2 | kafkaLLD §17.4 | proved | Protected work, coalescing, RUNNING-window entry, owner absorption, and revoke/assign interleaving proved |
| R10 | No processing completion before tuple durability | G5 | connLLD §12, §13; replayerLLD §5 | connLLD §19.2, §19.3 | open | |
| R11 | No record completion with unfinished associations | G3, G4 | kafkaLLD §8 | kafkaLLD §17.1 | open | |
| R12 | Shared record waits for all its requests | G3 | kafkaLLD §8.3; procCommit §6.2 | kafkaLLD §17.1 | open | |
| R13 | Source-response records associated through tuple durability | G3 | kafkaLLD §8.2, §9.2 | kafkaLLD §17.1 | open | |
| R14 | Expired and fresh lifetimes cannot cross-route | G6 | replayerLLD §1; kafkaLLD §10.3; connLLD §3.4, §16.2 | kafkaLLD §17.2; connLLD §19.4 | open | |
| R15 | Cancellation cleanup cannot produce a commit request | G8 | replayerLLD §6; kafkaLLD §15.3; connLLD §17.2 | connLLD §19.5; kafkaLLD §17.5 | open | |
| R16 | Successor generation waits for prior cleanup | G8 | kafkaLLD §15.3; procCommit §9.3 | kafkaLLD §17.5 | open | Must have fast deterministic evidence, not load-only evidence |
| R17 | Unrelated partitions continue | G8 | kafkaLLD §5.1; procCommit §8 | kafkaLLD §17.4, §17.5 | open | Must have fast deterministic evidence, not load-only evidence |
| R18 | No hard cap blocks retry or heartbeat evidence | G7 | kafkaLLD §5.1; procCommit §8.3 | kafkaLLD §17.4 | open | |
| R19 | Every unexpected owner failure reaches the supervisor | G9 | replayerLLD §8, §4; async §2 | connLLD §19.6; procCommit §13.5 | open | |

## Defects D1–D18

`open` includes a behavior still requiring proof of absence even when the original legacy mechanism was
refuted. Full measurements remain in the archived status.

| ID | Defect | Milestone | State | Live residue |
|---|---|---|---|---|
| D1 | Replayer cannot read its own capture topic | G1 | open, reworded | New path decodes envelopes; remaining proof covers broker time, control records, and observation sequence |
| D2 | A request can own zero Kafka records | G3 | open | |
| D3 | Ordinary target failures select Retain and halt | G4 | proved | Commit authority accepts only `RecordProcessingFinished(KafkaRecordId)`; no target result or policy can advance or retain a commit position |
| D4 | Mixed records structurally unrepresentable | G3 | open | |
| D5 | Blocking commit in revocation before cancellation | G2 | open proof obligation | New owner path reviewed; final absence remains part of acceptance |
| D6 | Target-concurrency bound is false | G5 | open residual | Abort currently releases before asynchronous channel teardown completes; configured default is effectively non-binding |
| D7 | Required cross-owner submission silently dropped | G5 | open | Required registry submissions must reach fatal handling |
| D8 | Truncated source response labelled complete | G3 | open | Current design distinguishes proved and unproven complete; final proof remains |
| D9 | No-response retry capped at 4 | G6 | refuted as stated; open decision implemented later | No-response is indefinite; response path retains configurable cap 4 by owner decision |
| D10 | No-response is an exception and arbitrary exceptions retry | G5 | refuted as stated; open residual | Produce typed no-response at the channel boundary rather than deriving it from stored `Throwable` |
| D11 | `ReplayEngine.admitWork` blocks on own-thread stage | G3 | refuted; proof obligation open | |
| D12 | Poll failures become empty successes | G2 | open proof obligation | Final acceptance must prove fatal/typed handling in the rebuilt source |
| D13 | Fatal path halts instead of supervisor ladder | G9 | open | Supervisor still owes failing owner/operation capture and input stop |
| D14 | Termination waits for orderly recovery | G9 | open | Three inherited wait sites remain assigned to G9 |
| D15 | Hard ownership caps deadlock | G7 | open | No hard count/byte cap may block evidence |
| D16 | Assignment is not paused per partition | G2 | open proof obligation | Final per-partition read-gating proof remains |
| D17 | Connection owner forgets request at turn end | G5 | open | |
| D18 | Work admitted under fabricated partition identity | G3 | open | |

## G3 latest dispositions and blockers

| Finding / decision | Class | State | Required disposition |
|---|---|---|---|
| Request-less close reached a nonexistent connection owner | A | fixed; final review clean | Source side settles close-only records; only a lifetime that reconstituted a request emits the ordered close |
| `SourceConnectionState` does not directly store the contributing record identities named by kafkaLLD §9 | A | owner ruled; fixed by placement | `PartitionIntakeState` remains the sole source of contributing-record identity and reverse-association state |
| Async commit callback plus `WakeupException` can resolve one submission twice | A; G4 owner | deferred(G4) | Plan A G4 now owns the complete one-operation identity, callback owner, wakeup propagation, monotonic restaging, observability, and race evidence |
| Checked-in G3 shell falsifier is stale and conflicts with direct-CLI falsification | B | open owner deletion decision | Delete or explicitly retain; current evidence is the exact-commit ephemeral worker |
| Six carried lifecycle tests still named G3 though their members belong elsewhere | B | fixed | Plan and ledger now split member responsibilities across G5, G8, and G11 |
| Carried deterministic `HttpTransactionDumper` test remained marked | B | fixed | Refactored in place onto live `SourceAssemblySink`, preserving request/response/close and first-line assertions |
| Response bytes across periodic record boundaries lacked direct proof | B | fixed | Split-record reconstruction test compares exact bytes |
| G7 insertion notes omitted request-state and separate bootstrap/requested batch state | B | fixed | Exact insertion notes now name all three states |
| Five new accessors and two private parameters had no caller | B | fixed | Tracker introspection, unused methods, and final unused import removed; tests use lifecycle messages and conservation metrics |
| Marked `replay/lifecycle/ReplayIntakeInput` references deleted enclosing predecessors | B | open owner decision | Retire as dead or restore enough predecessor shape for mechanical reconstruction |
| `replayIntakeInputsApplied` counts a rejected post-violation batch | B | wontfix | Input applied the terminal-state rule; record and rejection counters remain separate |
| Post-close observations across records | C | fixed; final review clean | Keep the explicitly closed lifetime in the existing current mapping while knowable; reject later observations without adding an identity tombstone |
| Request timing evidence | C | resolved by owner | Carry first-byte source time, request-EOM source time, and request-completing Kafka `LogAppendTime`; nominal target send time is anchored to request first byte |
| Bare `SegmentEnd` without an active segment | C | fixed by owner ruling | Ignore it in request and response assembly; deterministic tests cover both |
| Duplicate/out-of-order batch validation | C | withdrawn | `ObservedRecordCommitQueue` already owns ordering; do not add a second model |
| Typed source-interim protocol | owner decision | blocked on design/PA2 | PA2 item 5 produces typed whole/segmented observations; G3 consumes only those, with no ordinary-`Write` or old-capture compatibility path |
| Active-record-tracker gauge during generation cancellation | A, unreachable before G8 | deferred(G8) | Every unfinished tracker removed by `GenerationCleanupTracker` decrements the process-wide gauge once; prove return to the pre-generation value without generation metric attributes |
| G3 final review | conformance | proved | Seventh pass found no production-code design-conformance defect; only the typed-interim plan/design mismatch remains |

## G4 latest dispositions

| Finding / decision | Class | State | Required disposition |
|---|---|---|---|
| Accepted synchronous `RETRIABLE`/`GENERATION_STALE` was classified as proof that no offset moved | A | fixed | Only pre-acceptance async refusal is rejected; every failed accepted operation restores coverage as unknown |
| Late protocol violation after generation cleanup had no specified fatal style | C / owner decision | fixed(1a) | Preserve `CaptureProtocolViolation`; mutate commit state and conservation metrics only for the matching live generation |
| Submission-time timeout test exercised the callback branch | B | fixed | Separate construction-time and callback-delivered timeout tests; both classify as accepted unknown outcomes |
| Bounded synchronous uncertainty had no falsifying test | B | fixed | Completion arrives inside the revocation callback, forcing bounded `commitSync`; reverting rejected/unknown classification fails |
| Zero-grace rejected leg depended on the no-remainder boundary | B | fixed | Test comment records that zero grace intentionally preserves pre-acceptance rejection evidence |
| `commit_resolutions{outcome}` absent from metric register | B | fixed | Registered as a fixed-cardinality diagnostic event counter |
| `commit_resolutions` includes operation, duplicate, and late-callback events | B | wontfix(event-counter semantics) | Unit is events; it is diagnostic and excluded from conservation equations |
| Redundant synchronous uncertainty pre-mark | B | fixed | Removed the duplicate pre-mark; the common operation-resolution path owns uncertainty restoration and classification |
| Structurally invalid synchronous callback could be mistaken for a completed callback | A | fixed | Mark callback delivery only after classification and owner resolution return, so structural failures remain process-fatal |
| Structural callback failure is wrapped twice at the adapter boundary | B | wontfix(diagnostic-only) | Fatal propagation and cause chain are preserved; removing the redundant message layer does not change commit authority |
| Seven-argument owner constructor can omit metrics | B | wontfix(no live callers) | All current constructions pass metrics; G5 owns the production startup construction |
| Test-side aggregate conservation helper mirrors emitted deltas | B | wontfix(supplemental assertion) | Queue-derived production invariant and explicit terminal-bucket assertions are load-bearing |
| G4 conformance review | conformance | proved | Fresh named Claude session resumed through the final production correction; no unfixed Class A finding remains |
| G4 falsification | test fidelity | proved | Exact committed revisions cleanly detected callback/wakeup double resolution, backpedaling, rejected/unknown collapse, retirement omissions, timeout misclassification, poison-prefix loss, and ordered-head violations |

## Standing implementation decisions

| Key | Decision | Owner / state | Milestone |
|---|---|---|---|
| D-1 | Top-level application owns an atomic target-attempt counter, threaded to every connection owner; no permit actor/input family; invariant failure reaches supervisor | owner decided | G5 |
| D-1 fairness | Permit release wakes an acquirer without cross-connection FIFO guarantee | reversible default through G9.5 | G5 |
| D-2 | No-response retries indefinitely; HTTP-response retries retain cap 4, lifted to top-level config | owner decided | G5/G9 |
| D-3 | Record accounting is over `(generation, offset)` read events; retain the tiered and balancing equations in the metric table below | owner decided; AGENTS wording still needs owner-confirmed reconciliation | G4/G10/G12 |
| D-4 | Preserve the five dashboard-pinned names only with identical semantics; changed semantics require a new name and escalation | owner decided | G9 |
| Source interim protocol | Typed whole/segmented observations based on PR #3000; ordinary `Write` is never a fallback and old captures get no compatibility decoder | owner decided; design amendment still required | PA2/G3 |
| Target interim responses | Preserve after the rewrite with a complete target-channel → aggregation → tuple chain; keep current discard/TODO until then | owner deferred | POST1 |
| Record association placement | `PartitionIntakeState` is the sole source of contributing-record and reverse-association state | owner decided | G3 |
| Record tracker retirement | Ordinary completion removes only after required source-queue submission is accepted; cancellation removal and gauge balance belong to G8; prove via emitted messages and fixed-cardinality metrics, not map accessors | owner decided | G3/G8 |
| Generation observability | Generation identity belongs in logs/exceptions/spans, never as a metric dimension | owner decided | G3+ |
| Carried lifecycle evidence | Preserve inherited assertions until replacement behavior is proved; split members by responsibility | owner decided | G5/G8/G11 |
| Async commit uncertainty | Stage monotonic recommit and never backpedal; one owner-confined operation identity resolves callback/wakeup races once | owner decided; proved | G4 |
| Late protocol violation after generation cleanup | Preserve the existing `CaptureProtocolViolation` path; when its generation is already gone, do not mutate commit state or conservation metrics. Do not reopen protocol crash-style refinements before the working component chains are complete | owner decided | G4+ |
| Tuple writer | Transform off Netty loops on a bounded executor; one non-concurrently invoked transformer and sink per writer worker; explicit per-worker close; parallelism becomes a setting | reversible default, vetoable through G10 | G9 |
| Image | `traffic_replayer` may remain broken during construction | reversible through G10 | G0 |
| Branch/PR | Same branch and draft PR #3394 through red CI | owner decided | through swing |
| History | Preserve blame by carrying content once, in place, before stripping; final reconstruction only if needed | owner decided | through G11/post-G12 |
| Final cleanup | Remove `AGENTS.md`, `CLAUDE.md`, plans, and archived execution scaffolding only after durable rules migrate | deferred(final cleanup) | final |

### History-preserving carry rule

Inherited content arrives once, in one commit, as a move or copy from its legacy source. Strip or edit in that
commit or later; never delete it and restore it later. A temporarily noncompiling carry commit is acceptable;
a restore-deleted commit is not. Keep the first carry pairable near Git's default 50% rename threshold, and
finish all carries by G11 while the legacy source still exists in the parent commit. Open measurement: push a
representative cross-file-copy branch and compare GitHub blame with local `git blame -C -C -C`; until then,
move/copy-first sequencing remains mandatory.

### Pull-over decisions still governing live limbo

All P1–P12 defaults are reversible and owner-vetoable through G11 unless a row names an earlier decision.

| Group | Disposition | Named strips / constraints |
|---|---|---|
| P1 Netty/HTTP transform pipeline and JSON codec | carry | Remove the `<R>` type parameter used only by `RequestPipelineOrchestrator<R>`, `consumeBytes(byte[])` default, no-op `abort` default, and assertions standing in for one-shot guards |
| P2 Resource-ownership datatypes | carry | Preserve explicit owner transfer, release-on-accept/reject, and one-shot guards; remove `ByteBufListProducer`'s temporary compatibility base |
| P3 Target response aggregation | carry | Keep watcher/sniffer/interim handlers; absorb zero-standalone-use `AggregatedRawResult` and its self-typed builder into `AggregatedRawResponse` |
| P4 Tuple content and user-facing output | carry | Preserve source/target comparison and 1xx stripping; replace legacy identity/accumulator parameters with design values; remove `HttpByteBufFormatter`'s ambient `ThreadLocal` print style |
| P5 OpenSearch retry policy | carry | Replace `RequestSenderOrchestrator.RetryDirective` with sealed `RetryDecision`; retain response-path `MAX_RETRIES`, lifted to top-level config default 4 |
| P6 Auth transformers | carry as-is | Keep immediate-per-attempt signing and injected `Supplier<Clock>` |
| P7 Utilities | carry | Keep only `Utils.setIfLater`; remove the `NettyFutureBinders` overload that schedules the same task twice |
| P8 Observability adapters | carry with root rewrite | Preserve owner adapters and `ReplayTransactionMetrics` as G5's starting context/metric set; rewrite `ReplayContexts`/`RootReplayerContext`; preserve D-4 names only with identical semantics |
| P9 Owner-discipline primitives | carry/refactor | Keep `CompletionGate`, `RequestLifecycleInput`, `ObservedRecordCommitQueue`, one-shot permit release, held-duration metric, and pending-acquisition cancellation. Strip lazy `OwnerThreadGuard.guard(Runnable)`, `OwnerTransitionRunner.applyNowOrPost`, `RecordWorkTracker.completedRecords` escape/comment deferring commit authority, `ReplayIntakeInputQueue` per-item `CompletableFuture`, mailbox wall clock, and permit `cost`. Reshape the permit provider into the D-1 application-owned atomic with fatal callback; leave mailbox wrappers |
| P10 Dump modes | carry Kafka only | Preserve `dump-raw`/`dump-http`/`dump-both`, exhaustive payload handling, control records, broker time, and base epoch; strip fabricated commit data and no-commit per-record span. File input is retired and must not be restored |
| P11 CLI and supervision | carry contract surface | Preserve the 54-option surface and aliases unless an explicit contract decision says otherwise. Strip tuple-writer/legacy consumer wiring, ownership-cap and ignored liveness arguments, and `RequestSenderOrchestrator.FatalReplayHandler` coupling |
| P12 Deterministic fixtures | carry/refactor | Keep exhaustive generator and Mockito-free fixtures. Strip `RecordScript` inheritance and duplicate `RecordId`; `PumpedKafkaSource` duplicate generation/batch IDs; `ActorRequestTestUtils` permit-pool overload; two fixture sleeps; and synchronized/`AtomicInteger` array-cursor scaffolding |

### Additional active and carried-predecessor findings

| Finding | Owner | State / required result |
|---|---|---|
| Permit conservation failure currently lacks a supervisor boundary | G5 | Replacement atomic counter checks before mutation and invokes injected fatal callback |
| Legacy `ReplayIntakeInput` family includes permit/progress/tracker inputs beyond kafkaLLD §4.1 | G5/G7 | Remove non-intake owner vocabularies as their replacements land |
| Duplicate/pending permit acquisition invariant failures become ordinary failed futures | G5 | Route impossible transitions to process-fatal handling |
| Dropped permit-delivery post can leave `pendingAttemptPermit` non-null forever | G5/G8 | Clear owner state without leaking permit or blocking termination |
| Permit cleanup failure is suppressed onto a shared normal cancellation object | G5 | Keep infrastructure failure separate from typed cancellation |
| Legacy “permit pool” names and metrics survive the renamed concept | G5/G9 | Apply red-line-3 decision; preserve externally contracted metric names only by explicit ruling |
| Permit `cost` parameter permits state absent from the design; every caller passes one | G5 | Remove unless owner explicitly retains |
| Permit release can precede asynchronous aborted-channel teardown | G5 | Close D6 residual; no replacement attempt while old bytes remain in flight |
| Latent marked `ChannelContextManager.RefCountedContext.release` at `:54-58`, reached from `releaseContextFor` at `:88-95`, is non-atomic and assert-dependent at `:56`/`:91` | G5 | Inert by construction today; resolve during rewrite and do not apply an isolated legacy patch |
| Latent marked `ISourceTrafficChannelKey.getSourceGeneration` at `:23-25` defaults to zero and lets lifetimes collide in legacy keys | G3/G5 | Inert by construction today; final identities must use `ConnectionProcessingId`/real generation and prove no cross-route |
| `SourceConnectionState.expire()` has no production caller | G6 | Wire broker-time expiration trigger; direct G3 transition test is prerequisite evidence only |
| Live and marked predecessor files both use the name `ReplayIntakeInput` | G5/G7 owner promotion | Resolve when promoting `RequestLifecycleInput`; do not create an undesigned business-input variant |
| Existing deprecated-option “parse-and-warn” adapter set does not exist | G9 | Build the exact compatibility behavior required by focused authority §7; do not claim preservation |
| Dashboard contract is five names in three `capture-replay-dashboard.json` copies, not the k6 dashboard | G9 | Apply D-4 semantic-preservation decision |

## Deferral ledger

| Deferred | From | To | Why | State |
|---|---|---|---|---|
| Kafka-backed `dump-http` and `dump-both` | G1 | G3 | Source assembly was required first | proved — `SourceAssemblyEvidenceTest` |
| Proxy dropped-request successor baseline | G3 | PA2 | Serializer does not increment `eomsSoFar`; proxy-owned repair | open — PA2 item 4 |
| Typed source-interim observation producer | G3 | PA2 | Proxy must classify source `1xx` other than `101` and emit typed whole/segmented observations before G3 can interoperate; no compatibility path | open — PA2 item 5 |
| Preserve target interim responses in tuples | G5 | POST1 | Owner deliberately placed the complete target-channel → aggregation → tuple chain after the rewrite | open |
| Inherited connection-owner and context-lifetime assertions | G3 | G5 | Requires the G5 connection owner and process-local registry/context chain | open |
| Inherited revocation, stale-assembly, cleanup-acknowledgement, and successor-gating assertions | G3 | G8 | Requires typed generation cancellation and cleanup chain | open |
| Active-record-tracker gauge cleanup balance | G3 | G8 | Only G8 can remove unfinished trackers during generation cancellation; decrement once per removal and prove return to the pre-generation value | open |
| Interrupted source teardown still reaches application close | G3 | G11 | Process-teardown member belongs to the final supervisor/application chain | open |
| Real replay construction from Kafka owner through connection/request consumer | G2 | G5 | Real consumer does not exist before G5 | open |
| §17.4 case 1: demand below `N` | G2 | G7 | Requires G7 supply count | open |
| §17.4 case 2: unresolved retry input not supply | G2 | G7 | Requires G3 reconstitution and G7 bookkeeping | open |
| §17.4 case 3: fast response before `B + W` | G2 | G7 | Requires G6 boundary and G7 count | open |
| §17.4 case 4: slow/missing keeps demand open | G2 | G7 | Requires G6 unavailable state | open |
| §17.4 case 5: finished/cancelled removed once | G2 | G7 | Requires G5 completion input | open |
| §17.4 case 6: late retry input cannot re-add supply | G2 | G7 | Requires G7 idempotence state | open |
| §17.4 case 7 intake half: one explicit request | G2 | G7 | G7 owns bootstrap plus explicit request state | open |
| §17.4 case 11: next request waits for full batch application | G2 | G7 | Requires G7 applying/idle state | open |
| §17.4 case 12: one batch may overshoot `N` | G2 | G7 | Requires supply count | open |
| §17.4 case 14: no cap blocks evidence | G2 | G7 | Requires G6/G7 evidence types; also R18 | open |
| §17.4 cases 25–27: bootstrap, ordinary demand, bounded two-batch delivery | G2 | G7 | Source/bootstrap and intake demand land together | open |
| §17.4 case 28: bootstrap waits behind prior cleanup | G2 | G8 | G8 owns generation cleanup gate | open |
| `ConnectionAdmissionEntry`, `TargetChannelPort`, `RequestPreparationResult`, `RetryDecision` shells | G0 | G5 | Land with first real consumer | open |
| `TupleWriter`, `TupleWriteResult` shells | G0 | G9 | Land with tuple path and threading contract | open |
| `PartitionIntakeState` shell | G0 | G3 | Land with source assembly | proved; implementation and conformance review complete |
| One-shot asynchronous commit resolution under callback/wakeup races | G3 | G4 | G4 owns the complete submission identity, in-flight state, callback owner, monotonic recommit, observability, and deterministic race evidence | proved |
| Rejected-versus-unknown commit outcome split | G2 | G4 | G2 closed without proving the distinction; G4 owns issuance, operation-level resolution, revocation abandonment, conservation instrumentation, and deterministic evidence | proved |

## Scaffolding and known-broken-test ownership

| Item | Introduced | Removal / repair | State |
|---|---|---|---|
| `REBUILD-LIMBO` regions | G0 | each owning milestone; zero at rebuild completion | open — 215 START files; remeasure with the command |
| `REBUILD-LIMBO-NOTE` stand-ins | varies | named milestone in each note | open — 6 NOTE files |
| Module `build.gradle` limbo note | G0 | last limbo region | open |
| `KafkaSourceRootContext` | G2 | G3 | proved deleted |
| Proxy Kafka tests missing `LogAppendTime` topic setup | inherited | PA2 item 1 | open; masks PA2 item 2 |
| Proxy `MAX_ID_SIZE` assertion; replayer `-da:` workaround | inherited/G1 workaround | PA2 item 2; delete workaround in same repair | open |
| In-process proxy `System.exit(78)` and broker-lifetime workaround | inherited/G1 workaround | PA2 item 3; delete workaround in same repair | open |
| Proxy dropped-request successor baseline | inherited | PA2 item 4 | open |
| Marked connection/context lifetime members | G2 carry | G5 | open; preserve and refactor onto the connection-owner chain |
| Marked revocation/stale-assembly/cleanup members | G2 carry | G8 | open; preserve and refactor onto typed cancellation/cleanup |
| Interrupted-source application-close member | G2 carry | G11 | open; process teardown only |
| Marked quiescent connection tests | G2 carry | G5 | open |
| Marked long-running Kafka/replayer integration tests | G2 carry | G9 | open |
| Marked deterministic `HttpTransactionDumper` test | inherited | G3 | proved refactored in place |
| Historical 4 fixture and 57 test compile errors | S6b | G11/wontfix while marked | compiled active suite green; do not restore without owner milestone |
| Eight transformation-module inherited test failures | S6b | follows G11 test-fixture resolution | open ownership; current redirects no longer create a second module |

## Metrics

Fixed-cardinality counters are pre-authorized; identity-cardinality attributes require escalation.

| Metric | Meaning | Owner |
|---|---|---|
| `kafkaSourceWakeupsAbsorbedByProtectedOperation` | Wakeup consumed by protected Kafka work | G2 |
| `kafkaSourceRevocationsCleanedBeforeDeadline` / `kafkaSourceRevocationsReachingDeadline` | Grace-ceiling tuning pair | G2 |
| `replayIntakeOwnerStarted` / `replayIntakeOwnerStoppedAfterDraining` | Owner lifecycle and FIFO stop | G3 |
| `replayIntakeInputsApplied{inputKind}` / `replayIntakeRecordsApplied` | Applied input variants and records | G3 |
| `replayIntakeActiveRecordTrackers` / `replayIntakeRecordTrackersRetired` | Active tracker balance and accepted-retirement progress | G3 |
| `replayIntakeRequestsReconstituted` | Requests delivered from source assembly | G3 |
| `replayIntakeResponsesProvenComplete` / `replayIntakeResponsesUnprovenComplete` | Response confidence | G3 |
| `replayIntakeResponsesIncomplete{incompleteReason}` | Expiration/cancellation-ended assembly | G3 |
| `replayIntakeCapturedClosesAccepted` | Captured closes reaching a real sink | G3 |
| `replayIntakeCaptureProtocolViolations` | First-invalid-record cutoff events | G3 |
| `replayIntakeRecordBatchesRejectedAfterProtocolViolation` | Batches rejected after replay-wide cutoff | G3 |

Conservation is per partition and generation and counts read **instances** `(generation, offset)`, including
rereads in later generations.

G3 also proves `requests reconstructed == proven complete + unproven complete + incomplete` from the designed
lifecycle-result metrics; suppressing a terminal-result metric fails the focused evidence.

| Instrument | Type | Fires when | Owner |
|---|---|---|---|
| `records_read` | counter | one record instance is read | G4 |
| `records_committed` | terminal counter | commit position advances across the instance | G4 |
| `records_cancelled` | terminal counter | generation cancellation ends uncommitted work | G4/G8 |
| `records_abandoned_at_revocation{cause}` | terminal counter | ownership ends after rejected/unknown/unsubmitted commit | G4; rejected/unknown source distinction was assigned to G2 and remains unresolved below |
| `records_commit_ineligible` | terminal counter | protocol violation blocks the instance | G4 |
| `records_outstanding` | gauge | read instance has no terminal disposition | G4 |
| `commit_attempts_rejected` | diagnostic counter, not equation term | one commit attempt is rejected | G4 |
| `commit_resolutions{outcome}` | diagnostic event counter, not equation term | one operation resolution or late/duplicate resolution event is observed | G4 |

| Invariant | Scope |
|---|---|
| `records_read >= records_committed` | always |
| `records_read == records_committed` | happy case with no rebalance/reread |
| `records_read == records_committed + records_cancelled + records_abandoned_at_revocation + records_commit_ineligible + records_outstanding` | full accounting |
| commit-position advancement equals records committed | per partition/generation; preserves sequence continuity |

G4 owns the complete conservation instrumentation chain; G10/G12 assert the equations. Rejected and unknown
outcomes have opposite meanings: rejected proves the offset did not move, while unknown means it may have.
G4 distinguishes them at issuance: only a pre-acceptance asynchronous refusal is rejected; every
non-acknowledged accepted operation, including bounded synchronous revocation commits, is unknown. The
G2-to-G4 ownership correction is recorded in the deferral ledger and proved by deterministic evidence.

Dashboard-pinned names requiring identical semantics: `lagBetweenSourceAndTargetRequests`,
`bytesWrittenToTarget`, `bytesReadFromTarget`, `tupleComparison`, and the `kafkaCommit` span behind
`kafkaCommitCount`.

## Design changes — only ever on the owner's instruction

Every row records explicit owner authorization. The detailed rationale is retained in the archived status.

| Date | Section | Authorized change |
|---|---|---|
| 2026-09-25 | `connLLD §3.1`, `§5.1` | Nominal target send time is anchored to the captured source timestamp of the request's first byte, preserving the current main-branch scheduling behavior |
| 2026-09-24 | `kafkaLLD §9`, `§9.4`, `§16`; `procCommit §5.2`, `§10.2` | Inherited incomplete request reserves one ordinal; EOM/write/drop ends tail discard without advancing again; first capture-protocol violation latches replay-wide admission cutoff with a 60-second side-effect drain |
| 2026-09-24 | `kafkaLLD §9`; `procCommit §5.2` | Superseded later the same day: the ordinary-`Write` informational fallback remains historical only; typed source-interim observations now require a separately authorized amendment before production removes the fallback |
| 2026-09-24 | `kafkaLLD §9.3` | Captured close discards only incomplete request assembly and completes response assembly as `SourceResponseComplete(keptAlive=false)` before ordered close |
| 2026-09-24 | `proxyProtocol §4.1`; `kafkaLLD §9.4`; `procCommit §5.2` | `RequestIntentionallyDropped` ends partially captured suppressed request, releases associations, advances once, creates no replay request/tuple, leaves connection open; no-prefix marker is violation |
| 2026-09-24 | `kafkaLLD §4.1`; `procCommit §5.2`, `§10.1` | Orderly intake termination uses FIFO stop-after-draining queue control outside the nine business inputs; fatal shutdown does not wait |
| 2026-09-24 | `procCommit §5.2` | Response boundaries align with kafkaLLD: next request/close/exception complete with confidence; expiration/cancellation are incomplete |
| 2026-09-24 | `kafkaLLD §9.2` | Terminal response boundaries and `keptAlive` define proved/unproven complete; only expiration/cancellation are incomplete; no response-framing parser |
| 2026-09-24 | `captureArch §13.2`, `§13.3`; `replayerLLD §2`; `kafkaLLD §2`, `§3`, `§4.1`, `§4.2`, `§5.1`–`§5.3`, `§6`, `§13`, `§15.1`, `§15.3`, `§17.4`; `procCommit §1`, `§3.3`, `§5.1`, `§5.2`, `§8`, `§8.1`, `§9.3`, `§13.4` | Assignment installs source-local bootstrap entitlement before records; ordinary intake demand may add one explicit request; bounded two-batch overshoot accepted; cleanup remains independent gate |
| 2026-09-24 | `kafkaLLD §4.1` | `ForceGenerationCancellation` means cancellation is now required; deadline or early cleanup may trigger it |
| 2026-09-24 | `kafkaLLD §4.1` | Duplicate/already-cleaned force cancellation is inert and source submits it unconditionally |
| 2026-09-23 | `kafkaLLD §5.7` | Commit outcomes describe the operation, not individual partitions; partial application can accompany one batch failure |
| 2026-09-23 | `kafkaLLD §5.7` | Revocation commit has no minimum remaining interval below which it declines to attempt |
| 2026-09-23 | `kafkaLLD §5.7`, `§17.4`, `§17.5` | Outcomes name retry decision; ordinary commits async, revocation commit bounded sync; one operation in flight; revocation begins at callback entry; no inferred per-partition failure |
| 2026-09-23 | `connLLD §8`, `§17.1`, `§19.5`; `procCommit §9.2`; `kafkaLLD §15.1`, `§17.5`; `captureArch §11` | Graceful-cancellation boundary is final target write; add `FinalTargetWriteSubmitted`; grace relaxes no commit condition |
| 2026-09-23 | `procCommit §9.5`; `captureArch §11` | Define revocation forward-progress risk and report per-generation records committed/read |
| 2026-09-23 | `procCommit §9.2`; `captureArch §11`; `kafkaLLD §15.1` | Grace default is one second and configurable by `--cancellation-grace-ms` / `--cancellationGraceMs` |
| 2026-09-23 | `kafkaLLD §15.1` | One injected monotonic source creates and compares the entire deadline |
| 2026-09-23 | `kafkaLLD §5.7` | Clear staged position on acknowledgement; retain/reoffer while same generation owns; discard when generation revoked |

## Open and deferred decisions

| Item | State | Owner / next action |
|---|---|---|
| G3 closure | blocked(PA2/design) | Production conformance review is complete; obtain the exact source-interim design authorization and land PA2 item 5 producer/consumer interoperability |
| Authoritative source-interim design still describes the superseded ordinary-`Write` fallback | open; blocks typed G3 closure | Obtain explicit authorization for the exact design amendment; PA2 item 5 and G3 then implement only typed observations |
| Which preserved source timestamp anchors nominal target send time | resolved by owner, 2026-09-25 | Request first byte; design amended and G5/G7 may consume it |
| Conservation wording mismatch between AGENTS equation and D-3 decision | open, non-blocking | Owner confirmation required before semantic reconciliation |
| Rejected-versus-unknown commit outcome split was assigned to G2 but not proved | resolved ownership discrepancy | G2 scope/Exit now excludes it; G4 scope/Exit and the deferral ledger own the complete issuance, resolution, abandonment, observability, and evidence chain |
| `FinalTargetWriteSubmitted` was added as a second milestone by implication | open owner veto | Retain unless the owner rejects it; first-write still owns channel reuse while final-write owns graceful-cancellation completion |
| Relocate `utils/TrackedFutureJsonFormatter.java` and `trafficcapture/protos/TrafficStreamUtils.java` to owning modules | open owner decision | Avoid split packages; remove `TrackedFutureJsonFormatter`'s mutable static `ObjectMapper` during the owning-module move |
| Tuple-writer parallelism setting | reversible through G10 | Default remains bounded executor with one non-concurrently invoked transformer/sink per worker and explicit close |
| Cross-file blame behavior in GitHub UI | open measurement, due before G11 | Push a disposable representative branch and compare GitHub blame with local `git blame -C -C -C` |
| Fuse and ship-gate acceptance detail | deferred(G10) | Revisit at G9 boundary |
| Whether G12 is required alongside R1–R19 | deferred(G10) | Owner decision |
| Doc-count does not directly prove ordering | open(G12) | Stateful out-of-order replay should surface as comparison mismatch |
| Published `testFixtures` contract beyond measured consumers | open(G11) | Measured direct imports are exactly `replay.TestCapturePacketToHttpHandler`, `replay.TestUtils`, and `tracing.InstrumentationTest`, plus transitive `tracing.TestContext`; external consumers remain unknown |
| Git history cleanup and seven-signoff repair | deferred(post-G12) | One final evidence-based rewrite decision |
| Two dependency gaps: `libs.jackson.databind`, Guava | open at first consuming promotion | Add only with the code that needs each dependency |
| Final deletion of execution scaffolding | deferred(final cleanup) | Execution log already archived; active rules must migrate before deleting AGENTS/CLAUDE/plans |
