# Replayer Rebuild Status

Compact live register. Historical narrative and closed review detail are non-authoritative and retained in
[`archive/replayerRebuildStatus-through-2026-09-24.md`](archive/replayerRebuildStatus-through-2026-09-24.md).
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
| Active task context | 443,332 bytes | 95,755 bytes |
| Active corpus | 533,694 bytes | 186,056 bytes |

## Current state and evidence

| Item | State | Current evidence / owner |
|---|---|---|
| Active sequencing | Plan A | Plan B only on explicit instruction; focused supplemental authority is `replayerRebuildPlan.md` |
| G0 | proved | One in-place module; member-level limbo; deterministic fixtures; marker verifier |
| G1 | proved | Kafka `dump-raw` reaches `TrafficReplayer.main`; real proxy/topic envelope evidence; multi-partition bounds and metadata checked |
| G2 | proved | Kafka source owner, wakeup boundary, generation/commit handling, and repeated design-conformance/falsification review; R9 proved |
| G3 | `open(review)` | Production source-assembly chain landed through `c0ec3ddd6`; 24 focused deterministic tests and three real proxy/topic `SourceAssemblyEvidenceTest` cases last recorded; latest fix reopened review and owner decisions remain below |
| Production compile | proved | Last recorded passing with the required Gradle Spotless exclusions |
| Compiled test set | proved | Last recorded 108 tests, 0 failures; inherited unresolved tests remain marked and owned below |
| Limbo regions | open | Measured 2026-09-24: 216 files with `REBUILD-LIMBO-START`; 6 files with `REBUILD-LIMBO-NOTE`; 221 files with either marker; 349 Java / 362 total files under `TrafficCapture/trafficReplayer/src` |
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
| D3 | Ordinary target failures select Retain and halt | G4 | open | |
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

## G3 open review and owner decisions

| Finding / decision | Class | State | Required disposition |
|---|---|---|---|
| Request-less close reached a nonexistent connection owner | A | fixed; review reopened | Re-review `c0ec3ddd6` production diff |
| `SourceConnectionState` does not directly store the contributing record identities named by kafkaLLD §9 | A | open owner decision | Choose redundant local state or accept the partition-owned reverse index placement |
| Async commit callback plus `WakeupException` can resolve one submission twice | A; G4 owner | open | Complete §2 deferral into G4 before G3 closes; use one resolution latch |
| Checked-in G3 shell falsifier is stale and conflicts with direct-CLI falsification | B | open owner deletion decision | Delete or explicitly retain; current evidence is the exact-commit ephemeral worker |
| Six carried lifecycle tests still name G3 though their members belong to later milestones | B | open | Correct every receiving milestone before G3 closes |
| Carried deterministic `HttpTransactionDumper` test remains marked | B | open register item | Restore/refactor with the existing test; do not reinvent |
| Five new accessors and two private parameters have no caller | B | open owner decision | Delete or explicitly retain |
| Marked `replay/lifecycle/ReplayIntakeInput` references deleted enclosing predecessors | B | open owner decision | Retire as dead or restore enough predecessor shape for mechanical reconstruction |
| `replayIntakeInputsApplied` counts a rejected post-violation batch | B | wontfix | Input applied the terminal-state rule; record and rejection counters remain separate |
| Post-close observations; duplicate request completion; within-batch offset validation; request source-event timestamp; segment-end without active segmented value | C | open owner decisions | Designs are silent or conflicting; do not infer |

## Standing implementation decisions

| Key | Decision | Owner / state | Milestone |
|---|---|---|---|
| D-1 | Top-level application owns an atomic target-attempt counter, threaded to every connection owner; no permit actor/input family; invariant failure reaches supervisor | owner decided | G5 |
| D-1 fairness | Permit release wakes an acquirer without cross-connection FIFO guarantee | reversible default through G9.5 | G5 |
| D-2 | No-response retries indefinitely; HTTP-response retries retain cap 4, lifted to top-level config | owner decided | G5/G9 |
| D-3 | Record accounting is over `(generation, offset)` read events; retain the tiered and balancing equations in the metric table below | owner decided; AGENTS wording still needs owner-confirmed reconciliation | G4/G10/G12 |
| D-4 | Preserve the five dashboard-pinned names only with identical semantics; changed semantics require a new name and escalation | owner decided | G9 |
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
| `PartitionIntakeState` shell | G0 | G3 | Land with source assembly | open; implementation exists, review closure pending |
| Async commit resolution latch finding | G3 | G4 | Commit authority is G4's complete-chain responsibility | open; Plan A endpoints must be amended before G3 closes |

## Scaffolding and known-broken-test ownership

| Item | Introduced | Removal / repair | State |
|---|---|---|---|
| `REBUILD-LIMBO` regions | G0 | each owning milestone; zero at rebuild completion | open — 216 START files |
| `REBUILD-LIMBO-NOTE` stand-ins | varies | named milestone in each note | open — 6 NOTE files |
| Module `build.gradle` limbo note | G0 | last limbo region | open |
| `KafkaSourceRootContext` | G2 | G3 | proved deleted |
| Proxy Kafka tests missing `LogAppendTime` topic setup | inherited | PA2 item 1 | open; masks PA2 item 2 |
| Proxy `MAX_ID_SIZE` assertion; replayer `-da:` workaround | inherited/G1 workaround | PA2 item 2; delete workaround in same repair | open |
| In-process proxy `System.exit(78)` and broker-lifetime workaround | inherited/G1 workaround | PA2 item 3; delete workaround in same repair | open |
| Proxy dropped-request successor baseline | inherited | PA2 item 4 | open |
| Marked revoke/reassign source-assembly tests | G2 carry | G3/G8 according to member responsibility | open; six stale G3 ownership labels must be corrected |
| Marked quiescent connection tests | G2 carry | G5 | open |
| Marked long-running Kafka/replayer integration tests | G2 carry | G9 | open |
| Marked deterministic `HttpTransactionDumper` test | inherited | G3 | open |
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
| `replayIntakeRequestsReconstituted` | Requests delivered from source assembly | G3 |
| `replayIntakeResponsesProvenComplete` / `replayIntakeResponsesUnprovenComplete` | Response confidence | G3 |
| `replayIntakeResponsesIncomplete{incompleteReason}` | Expiration/cancellation-ended assembly | G3 |
| `replayIntakeCapturedClosesAccepted` | Captured closes reaching a real sink | G3 |
| `replayIntakeCaptureProtocolViolations` | First-invalid-record cutoff events | G3 |
| `replayIntakeRecordBatchesRejectedAfterProtocolViolation` | Batches rejected after replay-wide cutoff | G3 |

Conservation is per partition and generation and counts read **instances** `(generation, offset)`, including
rereads in later generations.

| Instrument | Type | Fires when | Owner |
|---|---|---|---|
| `records_read` | counter | one record instance is read | G4 |
| `records_committed` | terminal counter | commit position advances across the instance | G4 |
| `records_cancelled` | terminal counter | generation cancellation ends uncommitted work | G4/G8 |
| `records_abandoned_at_revocation{cause}` | terminal counter | ownership ends after rejected/unknown/unsubmitted commit | G4; rejected/unknown source distinction was assigned to G2 and remains unresolved below |
| `records_commit_ineligible` | terminal counter | protocol violation blocks the instance | G4 |
| `records_outstanding` | gauge | read instance has no terminal disposition | G4 |
| `commit_attempts_rejected` | diagnostic counter, not equation term | one commit attempt is rejected | G4 |

| Invariant | Scope |
|---|---|
| `records_read >= records_committed` | always |
| `records_read == records_committed` | happy case with no rebalance/reread |
| `records_read == records_committed + records_cancelled + records_abandoned_at_revocation + records_commit_ineligible + records_outstanding` | full accounting |
| commit-position advancement equals records committed | per partition/generation; preserves sequence continuity |

G4 owns the complete conservation instrumentation chain; G10/G12 assert the equations. Rejected and unknown
outcomes have opposite meanings: rejected proves the offset did not move, while unknown means it may have.
The inherited no-callback `commitSync` path cannot distinguish them, so completing that split changes commit
issuance rather than merely adding a counter. The split was assigned to G2 alongside commit submission but was
not proved before G2 closed; the open-decisions table requires an explicit ownership/deferral correction.

Dashboard-pinned names requiring identical semantics: `lagBetweenSourceAndTargetRequests`,
`bytesWrittenToTarget`, `bytesReadFromTarget`, `tupleComparison`, and the `kafkaCommit` span behind
`kafkaCommitCount`.

## Design changes — only ever on the owner's instruction

Every row records explicit owner authorization. The detailed rationale is retained in the archived status.

| Date | Section | Authorized change |
|---|---|---|
| 2026-09-24 | `kafkaLLD §9`, `§9.4`, `§16`; `procCommit §5.2`, `§10.2` | Inherited incomplete request reserves one ordinal; EOM/write/drop ends tail discard without advancing again; first capture-protocol violation latches replay-wide admission cutoff with a 60-second side-effect drain |
| 2026-09-24 | `kafkaLLD §9`; `procCommit §5.2` | Source write before request EOM is informational; ignore it and preserve request assembly, ordinal, and response state |
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
| G3 review decisions listed above | open; blocks G3 | Batch for owner, amend any deferral endpoints, then resume same uniquely named Claude milestone session |
| Conservation wording mismatch between AGENTS equation and D-3 decision | open, non-blocking | Owner confirmation required before semantic reconciliation |
| Rejected-versus-unknown commit outcome split was assigned to G2 but not proved | open; milestone ownership discrepancy | Do not silently reassign it: amend G2 and a named receiving milestone plus this ledger under AGENTS §2 before closure |
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
