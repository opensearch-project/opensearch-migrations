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
| G5 | proved | Production implementation is complete through `19c05162d`; the 60-test focused milestone set passed in `/private/tmp/gradle-evidence.95607.20320.log`, the final transformation-fallback correction passed its reopened two-test evidence in `/private/tmp/gradle-evidence.94140.3082.log`, and exact-revision falsification caught every applied production-property removal, including the separately owned tuple-writer force-before-durability cleanup inversion in `/private/tmp/gradle-evidence.80172.31070.log`. Claude session `fc13e47d-fbea-4f74-859a-efb46d729d0f` resumed through the exact final production diff and returned `NO_ACTIONABLE_FINDINGS`. The exact-method trace audit completed in `4905d409f` with 147 balanced mappings across 16 files, unchanged and branch-only claims removed, retained sources preserved for the final sweep, and the pre-carry RequestSender source gap recorded |
| G6 | proved | Production implementation is complete through `45cfa95a5`; four exact inherited-responsibility trace pairs were added in `cc44f427b`. Exact-commit `compileJava` passed in `/private/tmp/gradle-evidence.38094.13160.log`, and the six-class focused set passed 56 tests with zero failures or skips in `/private/tmp/gradle-evidence.38541.15964.log`. One batched direct Codex worker caught all 18 valid timing, ordering, waiting, cap, and fatal-boundary mutations and restored clean at `45cfa95a5`. Claude session `replayer-G6-20260925T170810Z-a882` resumed through the implementation fix and confirmed trace commit and returned `NO_ACTIONABLE_FINDINGS` |
| Production compile | proved | G6 exact committed source `cc44f427b` passed with the required Gradle Spotless exclusions in `/private/tmp/gradle-evidence.38094.13160.log` |
| Compiled test set | proved | G6 focused set: 56 tests, 0 failures, 0 errors, 0 skipped in `/private/tmp/gradle-evidence.38541.15964.log`; last broader recorded set remains 108 tests, 0 failures; inherited unresolved tests remain marked and owned below |
| Limbo regions | open | Measured 2026-09-25: 176 files with `REBUILD-LIMBO-START`; historical counts drift and the START grep is authoritative |
| Limbo integrity | proved | `TrafficCapture/trafficReplayer/tools/verify-limbo-markers.sh`; rerun after every marking change |
| PR strategy | decided | Keep draft PR #3394 and the same branch through the red-CI stretch; repair CI at the swing |
| DCO debt | `deferred(post-G12)` | Seven inherited PR commits lack sign-off: `5150f20ed`, `d7aa79540`, `34d286154`, `68cf95444`, `997a6c44f0`, `139853523`, `6fb2cb040`; preserve history during the eventual rewrite |

## Design obligations R1–R19

| ID | Obligation | Milestone | Defined in | Required tests | State | Notes |
|---|---|---|---|---|---|---|
| R1 | One named owner per mutable value | G9 | procCommit §3.1; replayerLLD §2; async §2 | connLLD §19.6; procCommit §13.1 | open | |
| R2 | At most one turn + one processing completion; normal has both | G5 | procCommit §3.2; connLLD §10, §13 | connLLD §19.2 | proved | Deterministic milestone and cancellation tests prove one-shot turn/processing/cleanup emission and normal turn-before-processing order |
| R3 | At most one intake-issued batch request per generation, plus one assignment bootstrap entitlement | G2, G7 | kafkaLLD §4.2, §13; replayerLLD §2 | kafkaLLD §17.4 | open | G2 proved explicit-request rejection; G7 adds and proves the independent bootstrap slot |
| R4 | Delivered batch matches bootstrap or explicit entitlement and is applied in delivery order | G7 | kafkaLLD §5.3, §13 | kafkaLLD §17.4 | open | |
| R5 | Demand open while retry-ready supply below N | G7 | procCommit §8.1; kafkaLLD §13 | kafkaLLD §17.4 | open | |
| R6 | Fast responses satisfy supply before B+W | G6 | procCommit §8.2; kafkaLLD §11 | kafkaLLD §17.3, §17.4 | open | G6 proves complete-before-boundary and unavailable-at-boundary inputs are irreversible and delivered exactly once; G7 still owns their supply-count integration |
| R7 | Finished/cancelled cannot re-enter supply | G6, G7 | kafkaLLD §12, §13; procCommit §8.1 | kafkaLLD §17.4 | open | G6 proves a finished request cannot satisfy the retry-ready predicate after its boundary resolves; G7 still owns remove-once demand bookkeeping and late-input idempotence |
| R8 | Target-write start stays local cancellation state | G5 | connLLD §8; procCommit §3.2 | connLLD §19.2 | proved | First/final-write milestones remain owner-local, are emitted once, and drive channel reuse/abort and graceful-cancellation decisions |
| R9 | Queued input wakes long poll without interrupting protected work | G2 | kafkaLLD §5.4; replayerLLD §2 | kafkaLLD §17.4 | proved | Protected work, coalescing, RUNNING-window entry, owner absorption, and revoke/assign interleaving proved |
| R10 | No processing completion before tuple durability | G5 | connLLD §12, §13; replayerLLD §5 | connLLD §19.2, §19.3 | proved | Normal, filtered, intentional-drop, fallback, retry, and force-race tests gate processing completion on `TupleDurable` |
| R11 | No record completion with unfinished associations | G3, G4 | kafkaLLD §8 | kafkaLLD §17.1 | open | |
| R12 | Shared record waits for all its requests | G3 | kafkaLLD §8.3; procCommit §6.2 | kafkaLLD §17.1 | open | |
| R13 | Source-response records associated through tuple durability | G3 | kafkaLLD §8.2, §9.2 | kafkaLLD §17.1 | open | |
| R14 | Expired and fresh lifetimes cannot cross-route | G6 | replayerLLD §1; kafkaLLD §10.3; connLLD §3.4, §16.2 | kafkaLLD §17.2; connLLD §19.4 | proved | Expired and fresh lifetimes receive distinct `ConnectionProcessingId`s; expiration and later retained-baseline expiration route only to the matching process-local owner |
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
| D6 | Target-concurrency bound is false | G5 | proved | Application-wide atomic permits are one-shot; aborted attempts retain their permit until asynchronous channel teardown completes, and capacity-one evidence prevents replacement overlap |
| D7 | Required cross-owner submission silently dropped | G5 | proved | Typed links remain registered until receiver completion; rejected owner posts retain the operation, release transferred resources, and reach fatal handling |
| D8 | Truncated source response labelled complete | G3 | open | Current design distinguishes proved and unproven complete; final proof remains |
| D9 | No-response retry capped at 4 | G6 | proved | No-response retries continue beyond four without entering exceptional control flow; the independently configured HTTP-response path retains four retries |
| D10 | No-response is an exception and arbitrary exceptions retry | G5 | proved | The target-channel boundary produces typed `NoTargetResponseObtained`; unexpected throwables remain exceptional/fatal and only typed outcomes enter retry policy |
| D11 | `ReplayEngine.admitWork` blocks on own-thread stage | G3 | refuted; proof obligation open | |
| D12 | Poll failures become empty successes | G2 | open proof obligation | Final acceptance must prove fatal/typed handling in the rebuilt source |
| D13 | Fatal path halts instead of supervisor ladder | G9 | open | Supervisor still owes failing owner/operation capture and input stop |
| D14 | Termination waits for orderly recovery | G9 | open | Three inherited wait sites remain assigned to G9 |
| D15 | Hard ownership caps deadlock | G7 | open | No hard count/byte cap may block evidence |
| D16 | Assignment is not paused per partition | G2 | open proof obligation | Final per-partition read-gating proof remains |
| D17 | Connection owner forgets request at turn end | G5 | proved | Turn completion advances the execution head but request removal waits for accepted processing completion and an empty owner-operation registry |
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

## G5 latest dispositions

| Finding / decision | Class | State | Required disposition |
|---|---|---|---|
| Application-wide attempt concurrency and corrupt-counter handling | A / owner decision | proved | Atomic capacity-one provider checks before mutation, latches one fatal state, settles queued acquisitions, and records permit conservation; 10 focused tests passed in `/private/tmp/gradle-evidence.76491.2269.log` |
| Abort released its permit before asynchronous target-channel teardown | A / D6 | proved | `AbortingAttempt` retains the permit through `abort()` completion; replacement acquisition cannot start while old bytes remain in flight |
| Turn completion could remove a request before durable processing acceptance | A / D17 | proved | Connection turn advances independently, but registry removal waits for `TupleDurable`, accepted `RequestProcessingFinished`, and drained owner operations |
| Required callback or receiver delivery can be rejected | A / D7 | proved | Transferred permits/resources are released, the registered operation remains diagnostically visible when completion was not applied, and the failure reaches the fatal boundary |
| No-response and preparation failures used exception-carried domain outcomes | A / D10 | proved | Preparation has exactly ready/cancelled values; no-response is typed at the channel boundary; unexpected failures remain exceptional |
| Filtered and intentional-drop requests could bypass the required tuple chain | A | proved | Filtered heads open no target exchange or permit but complete the turn and transform their skipped tuple; intentional whole-tuple drop performs no physical sink write and returns logical durability |
| Force cancellation racing separately owned tuple durability could strand cleanup or emit both terminal milestones | A | proved | Durable-before-force retains normal completion; force-before-durable emits cleanup exactly once; processing and cleanup terminal milestones are mutually exclusive |
| Expected transformation fallback was rejected before target replay | A | proved | Completed or error transformation status starts the target attempt; removing that production transition fails `expectedTransformationFallbackRunsTargetAttemptAndTupleChain` |
| Deployed preparation joins per-packet futures on the connection event loop | B | wontfix(current synchronous pipeline) | `HttpJsonTransformingConsumer` uses `EmbeddedChannel`, so each joined future is already complete and does not park today; adding a deferring handler would require redesign because it could block the event loop and prevent preparation cancellation from taking effect |
| Mainline replay metrics were approximated from owner transitions | owner decision / contract | proved | All 33 shipped names retain semantic parser, transformation, source, target-I/O, pacing/retry, and tuple-comparison producers; seven provisional G2 signals and `ReplayTransactionMetrics` are removed |
| Inherited connection reuse and context-lifetime evidence | B | proved | `ActiveConnectionTrackingTest` and the live `PartitionRevocationStaleStateTest` members prove matching-lifetime removal and separation by generation and process-local lifetime |
| G5 production construction | conformance | proved | Real Kafka source, source/input queues, replay intake, connection/request owners, deployed Netty target channel, tuple path, and one operation registry are reachable through `TrafficReplayerTopLevel` |
| G5 falsification | test fidelity | proved | Exact-revision direct-worker mutations were caught for preparation ordering, processing-acceptance retention, permit wake/cancel/corruption, retry permit/source-wait ordering, abort teardown, transformation metrics, tuple comparison attributes, fallback replay, and separately owned tuple durability after force cancellation. The final inversion removed `RequestReplayOwner.applyTupleResult`'s `tryEmitCleanup()` call and failed `durableFromASeparatelyOwnedTupleWriterAfterForceEmitsCleanupExactlyOnce` with expected `[turn:12, cleanup-finished]` versus actual `[turn:12]` in `/private/tmp/gradle-evidence.80172.31070.log`; the worker restored the file and finished clean at `19c05162d` |
| G5 conformance review | conformance | proved | Existing Claude session `fc13e47d-fbea-4f74-859a-efb46d729d0f` was resumed through `e28b8b2d3..19c05162d`; its final pass returned `NO_ACTIONABLE_FINDINGS` |
| G5 exact responsibility trace audit | navigation | proved | Commit `4905d409f` records 147 balanced exact mappings across 16 files, rejects unchanged and branch-only candidates, retains eligible sources through final sweep, and records the pre-carry RequestSender gap |

## G6 latest dispositions

| Finding / decision | Class | State | Required disposition |
|---|---|---|---|
| Retry and final source-response inputs shared one lifecycle result | A | proved | `RetrySourceResponseComplete` / `SourceResponseUnavailableForRetry` and `FinalSourceResponseComplete` / `FinalSourceResponseIncomplete` are independent one-shot owner inputs |
| Crossing record could apply payload before freezing retry input | A | proved | `ReplayIntakeOwner.applyRecord` validates time and emits unavailable at exact `R-B >= W` before applying the crossing payload; later completion cannot reverse it |
| Writer expiration reference was deleted after expiring current lifetimes | A / review | fixed | Retain per-`(writerNodeId,partition)` exact/fallback reference so a fresh lifetime remains governed and can expire independently |
| Heartbeat and restart expiration boundaries | A | proved | Exact heartbeat uses `R-M >= E+S`; restart fallback uses `R-T_first >= E+2S`; the first later heartbeat replaces fallback and a heartbeat at or after `E` does not refresh exact evidence |
| Higher-offset broker time moved more than `S` backward | A | proved | Process-fatal `BrokerTimeViolation` occurs before the record becomes time or payload evidence |
| Expired and fresh captured-connection lifetimes could cross-route | A / R14 | proved | Each lifetime gets a distinct `ConnectionProcessingId`; source expiration and target expiration commands use only that identity |
| `SourceConnectionState.expire()` had no production caller | A | proved | Broker-time expiration in `ReplayIntakeOwner` calls the source lifetime, settles associations, retires captured identity, and sends the matching target-owner command |
| Response-path and no-response retry limits were coupled | owner decision / D9 | proved | Four HTTP-response retries means five total response attempts; typed no-response outcomes remain indefinitely retryable |
| Retry-boundary scan traversed every request on every record | B / review | fixed | `unresolvedRetryBoundaries` indexes only unresolved requests and is maintained with every retry-input and request-removal transition |
| FIFO processing fence is exposed by the intake queue | B | wontfix(test synchronization) | The control entry carries no replay business state and is the deterministic signal proving prior accepted production inputs were applied; immediate completion is falsified by the top-level chain |
| Test convenience constructors contain explicit broker-time defaults | C / `SILENT` | wontfix(no live callers) | Deployed construction always supplies `TrafficReplayerTopLevel.Configuration`; G9 owns final deployed configuration compatibility |
| G6 responsibility placement | conformance | proved | Broker references, retry boundary, and lifetime registry: `PartitionIntakeState`; ordered record application: `ReplayIntakeOwner`; per-lifetime stop: `SourceConnectionState`; retry/final request state: `RequestReplayOwner`; target input routing: `TargetConnectionOwner`; composition: `TrafficReplayerTopLevel`; counters: `ReplayIntakeMetrics` |
| G6 exact responsibility trace audit | navigation | proved | Four balanced pairs cover inherited expiration sweep, expiration transition, pre-payload retirement, and response-retry cap; broker-time references, skew validation, retry boundary, retry/final split, and counters are branch-new and untraced |
| G6 falsification | test fidelity | proved | One exact-revision direct Codex worker caught 18/18 valid mutations: both inclusive expiration thresholds, fallback replacement, timely/late heartbeat handling, fatal skew and pre-payload ordering, retry equality/order/irreversibility, finished-request exclusion, retained baseline, response/no-response limits, FIFO fence, and final-response waiting |
| G6 conformance review | conformance | proved | Fresh Claude session `replayer-G6-20260925T170810Z-a882` resumed through the retained-baseline fix and the confirmed trace commit; both final passes returned `NO_ACTIONABLE_FINDINGS` |

## Standing implementation decisions

| Key | Decision | Owner / state | Milestone |
|---|---|---|---|
| D-1 | Top-level application owns an atomic target-attempt counter, threaded to every connection owner; no permit actor/input family; invariant failure reaches supervisor | owner decided | G5 |
| D-1 fairness | Permit release wakes an acquirer without cross-connection FIFO guarantee | reversible default through G9.5 | G5 |
| D-2 | No-response retries indefinitely; HTTP-response retries retain cap 4, lifted to top-level config | owner decided | G5/G9 |
| D-3 | Record accounting is over `(generation, offset)` read events; retain the tiered and balancing equations in the metric table below | owner decided; AGENTS wording still needs owner-confirmed reconciliation | G4/G10/G12 |
| D-4 | Preserve all 33 replay-pipeline metric names shipped on `main`, including the five dashboard-pinned names, only with their existing semantics, units, and fixed-cardinality attributes | owner decided | G5/G9 |
| Source interim protocol | Typed whole/segmented observations based on PR #3000; ordinary `Write` is never a fallback and old captures get no compatibility decoder | owner decided; design amendment still required | PA2/G3 |
| Target interim responses | Preserve after the rewrite with a complete target-channel → aggregation → tuple chain; keep current discard/TODO until then | owner deferred | POST1 |
| Record association placement | `PartitionIntakeState` is the sole source of contributing-record and reverse-association state | owner decided | G3 |
| Record tracker retirement | Ordinary completion removes only after required source-queue submission is accepted; cancellation removal and gauge balance belong to G8; prove via emitted messages and fixed-cardinality metrics, not map accessors | owner decided | G3/G8 |
| Generation observability | Generation identity belongs in logs/exceptions/spans, never as a metric dimension | owner decided | G3+ |
| Carried lifecycle evidence | Preserve inherited assertions until replacement behavior is proved; split members by responsibility | owner decided | G5/G8/G11 |
| Async commit uncertainty | Stage monotonic recommit and never backpedal; one owner-confined operation identity resolves callback/wakeup races once | owner decided; proved | G4 |
| Late protocol violation after generation cleanup | Preserve the existing `CaptureProtocolViolation` path; when its generation is already gone, do not mutate commit state or conservation metrics. Do not reopen protocol crash-style refinements before the working component chains are complete | owner decided | G4+ |
| Tuple writer | G5 owns logical transform/drop/retry/durability; G9 replaces temporary per-connection placement with a configured bounded worker set, one non-concurrently invoked transformer/sink per worker, explicit close, and a stable integer worker index passed through deployed sink construction and S3 naming | owner decided 2a, 2026-09-25 | G5/G9 |
| Replay contexts carry identity | Contexts are normative identity carriers: when a context owns an identifier, downstream APIs derive it from the context instead of carrying the same identifier separately | owner decided, 2026-09-25 | G5+ |
| Shutting-down target event loop | Preserve the explicit pre-connect `eventLoop.isShuttingDown()` guard; do not attempt a socket connect, complete exceptionally, and let the owner fatal/infrastructure path classify it | owner decided 1a, 2026-09-25 | G5 |
| Image | `traffic_replayer` may remain broken during construction | reversible through G10 | G0 |
| Branch/PR | Same branch and draft PR #3394 through red CI | owner decided | through swing |
| History | Preserve blame by carrying content once, in place, before stripping; final reconstruction only if needed | owner decided | through G11/post-G12 |
| Phase traceability | Baseline executable mainline behavior at `2fe4538a`; trace only moved, split, merged, substantially rewritten, or retired responsibilities with exact paired `OldClass.oldMethod -> NewClass.newMethod` records immediately before each method. Keep every eligible source member marked through the final completeness sweep, including `RETIRED` sources; remove dead source and trace together after the sweep. A post-baseline method may be a target only for the inherited responsibility slice it receives; unchanged and branch-only responsibilities receive no trace. A source already absent before Plan A's carry baseline is not restored: anchor its source record to `2fe4538a` at the surviving seam and record the gap. Calibrate each phase on 3–5 owner-confirmed samples before bulk editing | owner decided, 2026-09-25 | G5 through final |
| Replay pipeline metric contracts | Preserve all 33 names shipped on `main` with their existing units and attributes; discard the seven provisional G2-only connection-actor and target-exchange metrics | owner decided, 2026-09-25 | G5 |
| Replay transaction metrics | Discard `ReplayTransactionMetrics` and every metric introduced only through it; it was post-branch interim work and created a misleading parallel signal | owner decided, 2026-09-25 | G5 |
| Final cleanup | Remove `AGENTS.md`, `CLAUDE.md`, plans, and archived execution scaffolding only after durable rules migrate | deferred(final cleanup) | final |

### History-preserving carry rule

Inherited content arrives once, in one commit, as a move or copy from its legacy source. Strip or edit in that
commit or later; never delete it and restore it later. A temporarily noncompiling carry commit is acceptable;
a restore-deleted commit is not. Keep the first carry pairable near Git's default 50% rename threshold, and
finish all carries by G11 while the legacy source still exists in the parent commit. Open measurement: push a
representative cross-file-copy branch and compare GitHub blame with ordinary local `git blame`; `-C -C -C`
is diagnostic only and is not an acceptable substitute for preserving blame through the default view.

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
| P8 Observability adapters | carry with root rewrite | Preserve inherited replay context and metric producers in place while rewriting `ReplayContexts`/`RootReplayerContext`; discard post-branch `ReplayTransactionMetrics`; preserve D-4 names only with identical semantics |
| P9 Owner-discipline primitives | carry/refactor | Keep `CompletionGate`, `RequestLifecycleInput`, `ObservedRecordCommitQueue`, one-shot permit release, held-duration metric, and pending-acquisition cancellation. Strip lazy `OwnerThreadGuard.guard(Runnable)`, `OwnerTransitionRunner.applyNowOrPost`, `RecordWorkTracker.completedRecords` escape/comment deferring commit authority, `ReplayIntakeInputQueue` per-item `CompletableFuture`, mailbox wall clock, and permit `cost`. Reshape the permit provider into the D-1 application-owned atomic with fatal callback; leave mailbox wrappers |
| P10 Dump modes | carry Kafka only | Preserve `dump-raw`/`dump-http`/`dump-both`, exhaustive payload handling, control records, broker time, and base epoch; strip fabricated commit data and no-commit per-record span. File input is retired and must not be restored |
| P11 CLI and supervision | carry contract surface | Preserve the 54-option surface and aliases unless an explicit contract decision says otherwise. Strip tuple-writer/legacy consumer wiring, ownership-cap and ignored liveness arguments, and `RequestSenderOrchestrator.FatalReplayHandler` coupling |
| P12 Deterministic fixtures | carry/refactor | Keep exhaustive generator and Mockito-free fixtures. Strip `RecordScript` inheritance and duplicate `RecordId`; `PumpedKafkaSource` duplicate generation/batch IDs; `ActorRequestTestUtils` permit-pool overload; two fixture sleeps; and synchronized/`AtomicInteger` array-cursor scaffolding |

### Additional active and carried-predecessor findings

| Finding | Owner | State / required result |
|---|---|---|
| Permit conservation failure currently lacks a supervisor boundary | G5 | proved — replacement atomic counter checks before mutation, latches the first fatal condition, and invokes the injected fatal callback |
| Legacy `ReplayIntakeInput` family includes permit/progress/tracker inputs beyond kafkaLLD §4.1 | G5/G7 | G5 permit/progress vocabulary removed from live intake; G7 still owns final promotion of the carried `RequestLifecycleInput` predecessor |
| Duplicate/pending permit acquisition invariant failures become ordinary failed futures | G5 | proved — impossible transitions settle affected acquisitions and reach process-fatal handling |
| Dropped permit-delivery post can leave `pendingAttemptPermit` non-null forever | G5/G8 | G5 proved release-on-rejected-post and fatal handling; G8 retains only generation-cleanup integration |
| Permit cleanup failure is suppressed onto a shared normal cancellation object | G5 | proved — infrastructure failure remains exceptional/fatal and typed cancellation remains a domain result |
| Legacy “permit pool” names and metrics survive the renamed concept | G5/G9 | proved for G5 — legacy names and seven provisional metrics are absent from live code; G9 constructs the preserved shipped metric adapters without renaming them |
| Permit `cost` parameter permits state absent from the design; every caller passes one | G5 | proved removed from live provider and callers |
| Permit release can precede asynchronous aborted-channel teardown | G5 | proved — no replacement attempt can acquire until abort completion releases the old permit |
| Latent marked `ChannelContextManager.RefCountedContext.release` at `:54-58`, reached from `releaseContextFor` at `:88-95`, is non-atomic and assert-dependent at `:56`/`:91` | G5 | proved replaced by atomic live lifetime management; the trace-eligible predecessor remains marked only through the final completeness sweep |
| Latent marked `ISourceTrafficChannelKey.getSourceGeneration` at `:23-25` defaults to zero and lets lifetimes collide in legacy keys | G3/G5 | proved absent from the live owner/context chain; identity derives from `ConnectionProcessingId` with real generation and fresh-lifetime tests prove no cross-route |
| Broad G5 `REBUILD-TRACE` prose predates the exact-method convention | G5 | proved — 147 unique exact mappings balance source and target records across 16 files; unchanged and branch-only claims were removed. Retained eligible source members remain in limbo through the final sweep, including the deliberate `TrafficReplayerTopLevel.getCurrentAccumulator -> RETIRED` source. The baseline `RequestSenderOrchestrator` constructors and named scheduling, cancellation, retry, and packet-send methods were already absent before Plan A's carry baseline; their source records are anchored to `2fe4538a` at the surviving replacement seam rather than restoring deleted bodies. In-place `ChannelContextManager`, `ReplayContexts`, and `ParsedHttpMessagesAsDicts` rewrites colocate paired records; the history-preserving `ThreadLocalTupleWriter` → `TupleWriter` rename colocates predecessor and target records |
| Mainline `SigV4AuthTransformerFactory` is unchanged but not independently promotable | G5 | proved unchanged and intentionally retained in limbo through the final sweep; G9's configured construction decides its live consuming path. Add no trace unless its responsibility later moves, splits, merges, substantially rewrites, or retires |
| `SourceConnectionState.expire()` has no production caller | G6 | proved — broker-time expiration calls the source lifetime, settles its record associations, retires its captured identity, and routes the target command by `ConnectionProcessingId` |
| Live and marked predecessor files both use the name `ReplayIntakeInput` | G5/G7 owner promotion | Resolve when promoting `RequestLifecycleInput`; do not create an undesigned business-input variant |
| Existing deprecated-option “parse-and-warn” adapter set does not exist | G9 | Build the exact compatibility behavior required by focused authority §7; do not claim preservation |
| Five of the 33 preserved names are pinned in three `capture-replay-dashboard.json` copies, not the k6 dashboard | G9 | Apply D-4 semantic-preservation decision to the complete 33-name set |

## Deferral ledger

| Deferred | From | To | Why | State |
|---|---|---|---|---|
| Kafka-backed `dump-http` and `dump-both` | G1 | G3 | Source assembly was required first | proved — `SourceAssemblyEvidenceTest` |
| Proxy dropped-request successor baseline | G3 | PA2 | Serializer does not increment `eomsSoFar`; proxy-owned repair | open — PA2 item 4 |
| Typed source-interim observation producer | G3 | PA2 | Proxy must classify source `1xx` other than `101` and emit typed whole/segmented observations before G3 can interoperate; no compatibility path | open — PA2 item 5 |
| Preserve target interim responses in tuples | G5 | POST1 | Owner deliberately placed the complete target-channel → aggregation → tuple chain after the rewrite | open |
| Replace temporary per-connection tuple-writer placement and preserve stable sink index | G5 | G9 | G5 owns the complete logical transform/drop/retry/durability chain; G9 owns the configured bounded worker set, stable integer worker/sink index including S3 naming, per-worker transformer/sink construction, explicit close, and deletion of G5's per-connection placement | open |
| Inherited connection-owner and context-lifetime assertions | G3 | G5 | Requires the G5 connection owner and process-local registry/context chain | proved — matching-lifetime release, generation separation, and fresh process-local lifetime assertions run on the G5 context chain |
| Inherited revocation, stale-assembly, cleanup-acknowledgement, and successor-gating assertions | G3 | G8 | Requires typed generation cancellation and cleanup chain | open |
| Active-record-tracker gauge cleanup balance | G3 | G8 | Only G8 can remove unfinished trackers during generation cancellation; decrement once per removal and prove return to the pre-generation value | open |
| Interrupted source teardown still reaches application close | G3 | G11 | Process-teardown member belongs to the final supervisor/application chain | open |
| Real replay construction from Kafka owner through connection/request consumer | G2 | G5 | Real consumer does not exist before G5 | proved — production queues connect Kafka source, replay intake, connection/request owners, target channel, tuple writer, and typed completions |
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
| `ConnectionAdmissionEntry`, `TargetChannelPort`, `RequestPreparationResult`, `RetryDecision` shells | G0 | G5 | Land with first real consumer | proved — live G5 types have production consumers and exact outcome vocabularies |
| `TupleWriter`, `TupleWriteResult` shells | G0 | G5 | Land with tuple path and threading contract; G9 changes only deployed worker placement | proved — live logical transform/drop/retry/durability link |
| `PartitionIntakeState` shell | G0 | G3 | Land with source assembly | proved; implementation and conformance review complete |
| One-shot asynchronous commit resolution under callback/wakeup races | G3 | G4 | G4 owns the complete submission identity, in-flight state, callback owner, monotonic recommit, observability, and deterministic race evidence | proved |
| Rejected-versus-unknown commit outcome split | G2 | G4 | G2 closed without proving the distinction; G4 owns issuance, operation-level resolution, revocation abandonment, conservation instrumentation, and deterministic evidence | proved |

## Scaffolding and known-broken-test ownership

| Item | Introduced | Removal / repair | State |
|---|---|---|---|
| `REBUILD-LIMBO` regions | G0 | each owning milestone; zero at rebuild completion | open — 176 START files measured 2026-09-25; remeasure with the command |
| `REBUILD-LIMBO-NOTE` stand-ins | varies | named milestone in each note | open — 5 NOTE files measured 2026-09-25 |
| Module `build.gradle` limbo note | G0 | last limbo region | open |
| `KafkaSourceRootContext` | G2 | G3 | proved deleted |
| Proxy Kafka tests missing `LogAppendTime` topic setup | inherited | PA2 item 1 | open; masks PA2 item 2 |
| Proxy `MAX_ID_SIZE` assertion; replayer `-da:` workaround | inherited/G1 workaround | PA2 item 2; delete workaround in same repair | open |
| In-process proxy `System.exit(78)` and broker-lifetime workaround | inherited/G1 workaround | PA2 item 3; delete workaround in same repair | open |
| Proxy dropped-request successor baseline | inherited | PA2 item 4 | open |
| Marked connection/context lifetime members | G2 carry | G5 | proved live refactor; trace-eligible predecessor members remain marked through the final completeness sweep |
| Marked revocation/stale-assembly/cleanup members | G2 carry | G8 | open; preserve and refactor onto typed cancellation/cleanup |
| Interrupted-source application-close member | G2 carry | G11 | open; process teardown only |
| Marked quiescent connection tests | G2 carry | G5 | proved refactored into live deterministic connection/context lifetime tests; unrelated later-milestone members remain marked under their receiving milestone |
| Marked long-running Kafka/replayer integration tests | G2 carry | G9 | open |
| Marked deterministic `HttpTransactionDumper` test | inherited | G3 | proved refactored in place |
| Historical 4 fixture and 57 test compile errors | S6b | G11/wontfix while marked | compiled active suite green; do not restore without owner milestone |
| Eight transformation-module inherited test failures | S6b | follows G11 test-fixture resolution | open ownership; current redirects no longer create a second module |

## Metrics

Fixed-cardinality counters are pre-authorized; identity-cardinality attributes require escalation.

| Metric | Meaning | Owner |
|---|---|---|
| `kafkaRecordsRead` / `kafkaBytesRead` / `trafficStreamsRead` | Shipped source-read event and byte contracts | G5 |
| `parsedHeader` / `parsedPayload` / `parsedPayloadSuccess` | Shipped request parse contracts | G5 |
| `transformedJsonRequired` / `transformedJsonSucceeded` / `transformedTextSucceeded` / `transformedTextFailed` / `transformedPayloadBinary` | Shipped request transformation-path contracts | G5 |
| `originalPayloadBytesIn` / `uncompressedBytesIn` / `uncompressedBytesOut` / `finalPayloadBytesOut` | Shipped transformation byte contracts | G5 |
| `transformSuccess` / `transformSkipped` / `transformError` / `transformBytesIn` / `transformBytesOut` / `transformChunksIn` / `transformChunksOut` | Shipped transformation result and stream contracts | G5 |
| `scheduleLag` / `numRetriedRequests` / `lagBetweenSourceAndTargetRequests` | Shipped pacing and retry contracts | G5 |
| `activeReplayerChannels` / `nonRetryableConnectionFailures` | Shipped replay-connection lifecycle contracts | G5 |
| `activeTargetConnections` / `connectionsOpened` / `connectionsClosedCount` / `bytesWrittenToTarget` / `bytesReadFromTarget` | Shipped target-connection and I/O contracts | G5 |
| `tupleComparison{sourceStatusCode,targetStatusCode,statusCodesMatch,method}` | Shipped fixed-cardinality source/target comparison contract | G5 |
| `targetAttemptPermitAcquisitionRequests` / `targetAttemptPermitAcquisitionsPending` / `targetAttemptPermitAcquisitionsCancelled` / `targetAttemptPermitAcquisitionsFailed` | Target-attempt permit acquisition conservation | G5 |
| `targetAttemptPermitsAcquired` / `targetAttemptPermitsActive` / `targetAttemptPermitsReleased` / `targetAttemptPermitHeldDuration` | Target-attempt permit ownership and held-time conservation | G5 |
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
| `replayIntakeRetrySourceResponsesComplete` / `replayIntakeRetrySourceResponsesUnavailable` | Independent exactly-once retry-input outcomes | G6 |
| `replayIntakeWriterTimeTransitions{writerTimeTransition}` | Fixed-cardinality exact/fallback heartbeat-state transitions | G6 |
| `replayIntakeSourceConnectionsExpired` / `replayIntakeTargetConnectionExpirationsSent` | Source-lifetime expiration and routed target-owner command conservation | G6 |
| `replayIntakeBrokerTimeViolations` | Fatal higher-offset backward-skew violations | G6 |

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

Discarded by owner decision on 2026-09-25 because they were provisional G2-only instruments and never
shipped on `main`: `connectionActorQueuedCommands`, `connectionActorHeadWait`,
`connectionActorActiveDuration`, `connectionActorAbortDuration`, `connectionActorPendingAbortChild`,
`targetExchangeActivePhase`, and `targetChannelState`.

## Design changes — only ever on the owner's instruction

Every row records explicit owner authorization. The detailed rationale is retained in the archived status.

| Date | Section | Authorized change |
|---|---|---|
| 2026-09-25 | `captureArch §11`; `connLLD §5.2`, `§6`, `§10`, `§12`, `§14`, `§15.4`, `§17.1`, `§19.3`; `procCommit §7.2`, `§7.5`, `§9.2`, `§13.2` | Expected request filtering acquires no target permit and opens no target exchange, but completes its turn normally and sends a skipped tuple candidate through tuple transformation; transformation may intentionally drop the whole tuple, completing logical output without a sink write |
| 2026-09-25 | `captureArch §11`, `§12.2`; `connLLD §17.1`, `§17.2`, `§19.5`; `procCommit §9.4` | Normal completion established before force handling may still produce a commit request; existing Kafka-source commit authority decides its outcome; top-level wording now says the current process leaves unfinished records uncommitted for a future owner rather than redelivering them |
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
| Cross-file blame behavior in GitHub UI | open measurement, due before G11 | Push a disposable representative branch and compare GitHub blame with ordinary local `git blame`; use `-C -C -C` only as diagnostics |
| Fuse and ship-gate acceptance detail | deferred(G10) | Revisit at G9 boundary |
| Whether G12 is required alongside R1–R19 | deferred(G10) | Owner decision |
| Doc-count does not directly prove ordering | open(G12) | Stateful out-of-order replay should surface as comparison mismatch |
| Published `testFixtures` contract beyond measured consumers | open(G11) | Measured direct imports are exactly `replay.TestCapturePacketToHttpHandler`, `replay.TestUtils`, and `tracing.InstrumentationTest`, plus transitive `tracing.TestContext`; external consumers remain unknown |
| Git history cleanup and seven-signoff repair | deferred(post-G12) | One final evidence-based rewrite decision |
| Two dependency gaps: `libs.jackson.databind`, Guava | open at first consuming promotion | Add only with the code that needs each dependency |
| Final deletion of execution scaffolding | deferred(final cleanup) | Execution log already archived; active rules must migrate before deleting AGENTS/CLAUDE/plans |
