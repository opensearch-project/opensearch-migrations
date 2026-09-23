# Replayer Rebuild Plan A — build a new module alongside, then swing

**Status:** primary plan, selected 2026-09-22

**Fallback:** [`replayerRebuildPlanB-inPlace.md`](replayerRebuildPlanB-inPlace.md) — see
[§10 Rollback](#10-rollback-to-plan-b)

**Supersedes:** [`replayerRebuildPlan.md`](replayerRebuildPlan.md) for sequencing and code disposition.
That document remains authoritative for three things only: the `D1`–`D18` defect analysis (§2), the
`R1`–`R19` obligation set (§6.5), and deployed-configuration compatibility (§7). Everything it says
about step order, class disposition, and per-step procedure is replaced here.

**Governed by:** [`../AGENTS.md`](../AGENTS.md). Read it before this file. Its red lines, escalation
format, review policy, test policy, and `-x spotless` rule apply to every milestone below and are not
restated here.

**Authoritative design:** unchanged — the documents under `docs/captureAndReplay/`. This plan does not
define or change product behavior.

---

## 1. Why this shape

The in-place rebuild ran S0 through S6b and then stopped converging. The reason is measurable, and it
is not a matter of taste.

As of commit `822abde4e`, the structural facts are these. They are about **where responsibilities live and
which components exist**, which is what matters; the file sizes noted below are symptoms and would prove
little on their own.

- **Both correctness models are live simultaneously.** `ReplayTransaction` still has six production
  callers while `RecordWorkTracker` and `ObservedRecordCommitQueue` also exist. The previous plan's §1
  explicitly forbade this ("No second correctness model … at no point should a third be added"). The
  rule was written down and violated anyway.
- `ReplayIdentity` holds **twelve** records, of which three are design identities (`KafkaRecordId`,
  `PartitionGenerationId`, `ReplayRequestId`) and nine are legacy or invented. Five design identities
  are still absent. Net progress across the entire run: one record.
- The whole Kafka-source and intake-state layer is still unbuilt: `KafkaSourceOwner`,
  `KafkaSourceInputQueue`, `PartitionSourceState`, `WakeupController`, `PartitionIntakeState`,
  `SourceConnectionState`, `WriterPartitionTimeState`, `GenerationCleanupTracker`, `TupleWriter`,
  `ProtocolViolationTerminator`, `TargetChannelPort` — all absent. The list is not exhaustive; it is
  every named component the milestones below require and `grep` cannot find.
- `RecordDispositionLedger` has **zero** production and zero test references — 707 lines dead for some
  time, and nobody deleted it.
- **`RequestSenderOrchestrator` still owns permit handling, retry, backoff, and channel lifecycle
  together**, which the design separates across four places, and its extraction target
  `TargetChannelPort` does not exist. It is now 2,560 lines against the 1,701 the previous plan recorded —
  so the milestone meant to dismantle it moved nothing out and added to it.
- **`TargetConnectionOwner` still holds request-lifecycle responsibilities** the design splits with
  `RequestReplayOwner`, despite that split having nominally happened. It is 2,134 lines, with
  `RequestReplayOwner` a further 556, against the 740-line `ConnectionActor` they replaced.

The mechanism behind all of that is one constraint: every step had to leave the old implementation
compiling beside the new one. Under that constraint the cheapest correct move at each step is to add,
not replace — so twelve slices of replacement work left responsibilities where they were and produced two
live models. Removing that constraint is the entire point of this plan.

**Confidence:** the reference counts, absent types, and identity-record inventory are measured, as are the
file sizes. The claim that responsibilities failed to move is read from the absence of the design's named
components and from what the extraction targets do not contain; it has not been confirmed by reading those
two owners end to end, which is why Plan B makes that read its first milestone.

## 2. The shape

Build the replayer as a **new Gradle module** using the **same Java package names**, with **no
dependency on the existing module in either direction**. Nothing is copied forward unless it arrives
in the form it will have in the final implementation. The existing module stays in the tree, untouched
and buildable, as reference.

Two modules may hold the same package names because they are never on one classpath. That is the
mechanic that makes this cheap: **there is no rename at the end.** The final cutover is a directory
deletion plus removing one `settings.gradle` line — not a module-wide package refactor, and not a
coordinate or image change.

It is expected and acceptable that the assembled application does not work for most of this plan.
Individual milestones are proved in isolation.

### 2.1 Module mechanics

- **Rename the old module out of the way; the new module takes the real name from its first commit.**
  `git mv TrafficCapture/trafficReplayer TrafficCapture/trafficReplayerLegacy`, and build the new module
  at `TrafficCapture/trafficReplayer/`. Both are registered in `settings.gradle`; the existing
  `include 'TrafficCapture:trafficReplayer'` line is never touched, and a line for the legacy module is
  added now and deleted at `G11`.

  This is better than giving the new module a temporary name, and the reason is the asymmetry of failure.
  With a temporary name, the risky work — restoring the right Gradle path, the right Maven coordinates,
  the right directory name, re-enabling publication — all happens at the **end**, under time pressure,
  and forgetting any part of it ships something wrong. With the legacy rename, the new module holds its
  final name, final Maven coordinates, final Gradle path, and final jib mapping from day one, and the
  only end-state action is **deleting a directory**. Deletions are safe; renames under pressure are not.
  There is no `2`, no `v2`, and no rename commit anywhere in the plan.
- Package names are the final ones: `org.opensearch.migrations.replay…`.
- **The build must forbid any dependency on `:TrafficCapture:trafficReplayerLegacy`.** Not a convention —
  a check that fails. This converts "no second correctness model" from a rule an agent must remember into
  something the compiler enforces. Without this, Plan A degrades into Plan B silently.
- The new module may depend on `captureProtobufs`, `coreUtilities`, `testHelperFixtures`, and the other
  shared libraries exactly as the current module does.
- **Suppress Maven publication on the legacy module.** Every subproject except `:TrafficCapture` and
  `:TrafficCapture:dockerSolution` gets a `mavenJava` publication from the root `build.gradle`
  (jar + javadoc + sources, and the `testFixtures` jar separately), and Gradle derives the artifactId
  from the **project name**. So add `:TrafficCapture:trafficReplayerLegacy` to the existing
  `excludedProjectPaths` list, and no `trafficReplayerLegacy` artifact is ever published. The
  `trafficReplayer` coordinates keep publishing throughout, from the new module, unchanged — which is the
  main reason this arrangement beats a temporarily-named module. Suppressing publication on code that is
  about to be deleted is obviously safe; suppressing it on the arriving module means remembering to turn
  it back on. **Publication is a red-line-2 surface and the most externally visible one in this plan.**

Two consequences of the new module owning the real name from day one. Both are acceptable, and both are
better surfaced early than at the swing:

- **The `traffic_replayer` image builds from an incomplete module immediately**, because the jib mapping
  keys on `TrafficCapture:trafficReplayer` and that path now resolves to the new module. The legacy module
  gets no jib entry at all, so `buildImages/build.gradle` is never edited — not now, not at `G11`. The
  assembled application is therefore broken from the first commit until the new module can run, which the
  owner has already accepted ("It would be ok for the overall main app to not work at all while we built
  the pieces out in isolation"). `G10` is the first milestone that needs a working image.
- **The eight `transformation/` modules of §2.3 retarget to the new module immediately**, and their test
  compilation breaks until the new `testFixtures` surface exists. Recommended handling: point those
  `build.gradle` lines at `:TrafficCapture:trafficReplayerLegacy` for the duration — one line per module,
  mechanical, reverted at `G11` — with a status-table row naming `G11` as the removal milestone. This is
  named scaffolding on code scheduled for deletion, not a bridge between the two correctness models, so it
  does not weaken the prohibition above.
- **One package is off-limits.** `TrafficCapture/tupleSink` declares production classes in
  `org.opensearch.migrations.replay.sink` (`TupleSink`, `CallbackTupleSink`, `GzipJsonLinesSink`,
  `S3TupleSink`) and is a real dependency of the replayer, so it *is* on the same classpath. The new
  module must declare nothing in `…replay.sink`. Every other `…replay*` package is safe to duplicate
  because the two replayer modules are never on one classpath — which is exactly what the §2.1
  dependency prohibition guarantees.

### 2.2 What comes over, and on what terms

Nothing is ported because it exists. A file moves only if it is already in final form, and moving it is
a decision recorded in the status table.

`../AGENTS.md` §1 red line 3 applies with full force here: **choosing to bring a legacy type forward is
an escalated decision**, not a default. The temptation to reuse is the failure mode this plan exists to
prevent.

The previous plan's §5.4 "keep as-is" list is the honest candidate set (`CompletionGate`,
`ActorMailbox`, `OwnerThreadGuard`, `ByteBufList`, `NettyUtils`, `RefSafeHolder`, the Netty
data-handler pipeline, the tracing contexts, `SourceTargetCaptureTuple`, and similar). Each is
evaluated when first needed, not in advance.

**Do not preserve git history at the cost of sequencing.** Use `git mv` where a file genuinely moves
nearly intact; accept fresh history for anything rewritten. `git log --follow` covers the renames that
matter. Blame on rewritten code is worth very little and the process gymnastics to preserve it are not
free.

### 2.3 The external contract surface

This is the complete set of things the swing must not break. It is small, which is why this plan is
viable. Each item is a red-line-2 contract; changing any of them is an escalated decision.

| Surface | Where it is established |
|---|---|
| **Published Maven artifact** `trafficReplayer`, plus its separately published `testFixtures` jar | Root `build.gradle:265-272` — every subproject outside `excludedProjectPaths` publishes `mavenJava`; artifactId comes from the project name |
| Docker image name `traffic_replayer` | `buildImages/build.gradle:22,46,51` |
| Dump modes `dump-raw`, `dump-http`, `dump-both` | `KafkaTopicDumper`, invoked from `TrafficReplayer.java:644` — a user-facing CLI mode, for Kafka and file sources both |
| Module path `:TrafficCapture:trafficReplayer` | `settings.gradle:70` |
| CLI options and inline-JSON keys | Previous plan §7 — the full alias list, including the deprecated parse-and-warn set |
| Workflow schema fields | `orchestrationSpecs/packages/schemas/src/userSchemas.ts`, `workflowTemplates/replayer.ts` |
| Deployment service and image references | `deployment/cdk/opensearch-service-migration/lib/service-stacks/traffic-replayer-stack.ts`, `stack-composer.ts`, `deployment/k8s` values, `TrafficCapture/dockerSolution/src/main/docker/docker-compose.yml` and `docker-compose.large-requests.yml` |
| Exit codes 80 and 89 | `ReplayProcessFatalHandlerTest` |
| OTEL metric and trace names | Consumed by the Grafana dashboard in `deployment/k8s/charts/components/k6LoadTest` |
| `testFixtures` artifact API | **Six** `transformation/` modules consume it via `testImplementation testFixtures(...)` |
| Compiled Java API | **Six** `transformation/` modules depend on the module directly, all at test scope; four test files import `org.opensearch.migrations.replay` |
| Sonar suppression path glob | `sonar-project.properties:274` (`**/trafficReplayer/**/replay/ClientConnectionPool.java`) |
| K8s task label `trafficReplayer` | `TrafficCapture/trafficLoadTest/scripts/lib/common.sh:253`, Argo `replayer.ts`, `resourceManagement.ts` |

**There is no production Java consumer of the replayer module anywhere in the repository.** This was
measured, not assumed: no `src/main` source set outside `TrafficCapture/trafficReplayer/` imports
`org.opensearch.migrations.replay`, and the only four external files that do are in `src/test`
(`TypeMappingsSanitizationProviderTest`, `JsonJMESPathTransformerProviderTest`, `PayloadRepackingTest`,
`AddCompressionEncodingTest`). The one non-`testImplementation` edge is
`jsonTypeMappingsSanitizationTransformer`'s `testFixturesImplementation project(':TrafficCapture:trafficReplayer')`
— still test scope, but it means that module's *fixtures* compile against replayer production types.

The two rows of "six" are **overlapping but different sets**, and their union is **eight** modules — six
consume `testFixtures`, six depend on the module directly, and four do both. Do not treat "the six
modules" as a single list when planning the swing.

Because `testFixtures` is **published to Maven**, its API is a public contract and not merely an
arrangement with six sibling modules. That raises the stakes on the `G11` decision below: updating six
in-repo consumers is cheap, but anyone outside the repo compiling against the published fixtures jar is
not visible from here.

So the entire *in-repo* compile-time coupling to be satisfied at the swing is test-scope, in one directory
tree. Everything else references the replayer as an *artifact* — by Maven coordinates, image name, service
name, CLI surface, or K8s label — and does not care which module produced it. That is the measurement that
makes this plan viable rather than ambitious.

## 3. Ground rules specific to this plan

Beyond `../AGENTS.md`:

1. **Nothing comes over carrying code that is going to be discarded.** Partial work is fine. Work that
   is close to final is fine — the Netty pacing code is the archetype: substantively correct, expensive
   to re-derive, and worth bringing over to finish in place. What must not come over is anything with
   legacy structure still attached, because that is what silently reinstalls the old shape. The question
   at the moment of moving a file is not "is this finished?" but **"is anything in here going to be
   deleted later?"** If yes, strip it before the move or leave the file behind. This replaces the previous
   plan's §5.1–§5.3 — three sections of per-class disposition bookkeeping — with one question.
2. **`D1`–`D18` are now a test list, not a work list.** There are no defects to repair in a module that
   does not exist yet. Each of the eighteen becomes a behavior the new implementation must be shown
   *not* to exhibit. The analysis keeps all of its value; it simply stops driving the sequencing.
3. **`R1`–`R19` are unchanged** and remain the definition of done.
4. **No milestone is production.** "Compiles" and "this milestone's tests pass" never imply readiness.
5. **Observability lands with the component that needs it**, not afterward. See §5.

## 4. Milestones

Dependency order, not a schedule. Each milestone is one bounded review unit.

> **How to read the milestone descriptions below. This matters more than anything else in this document.**
>
> The prose in each milestone is **scope, not specification**. It tells you which components and which
> behaviors belong to that milestone; it does **not** define them. The designs under
> `docs/captureAndReplay/` define them, they were built carefully, and they are the only authority.
>
> **Every milestone must carry a `Design refs:` line** — the documents and sections that define its
> behavior — and before implementing you read those sections. Where this plan's wording and a design
> section differ, **the design is right and this plan has a bug**: implement the design and report the
> discrepancy.
>
> Use the `Design refs:` lines, and `grep docs/captureAndReplay/` for anything they do not cover. One
> milestone's prose here was already caught inverting a design requirement by dropping a qualifier;
> assume others may remain, and read the cited section rather than trusting the summary.
>
> **The designs carry their own required-test lists** — `kafkaLLD §17`, `connLLD §19`, `procCommit §13`,
> `captureAndReplayArchitecture §15` — and those are authoritative. Where a milestone's `Exit:` line and
> its cited required-test section differ, **the design's list wins**; treat the `Exit:` line as a summary
> of intent, not a complete specification of evidence.
>
> Short names used in `Design refs:` lines, all under `docs/captureAndReplay/`: `replayerLLD` =
> `replayerLowLevelDesign.md`, `kafkaLLD` = `replayerKafkaSourceAndIntakeLowLevelDesign.md`, `connLLD` =
> `replayerConnectionAndRequestLowLevelDesign.md`, `procCommit` =
> `replayerProcessingAndCommitArchitecture.md`, `async` = `asyncMessagePassingProgrammingGuide.md`.

Note a constraint that **dissolves** under this plan: the previous S11 and S12 had to land in a single
merge unit because deleting the ownership caps before the demand model existed created a deadlock and
evidence-starvation window. There are no caps to delete here, so `G6` and `G7` are independent.

### G0 — Module, shells, fixtures

**Design refs:** identities `replayerLLD §1:44-60` and `kafkaLLD §2:61-105`; owners
`replayerLLD §2:62-97`; cross-owner message rules `async §2:39-49`; what the fixtures must be able to
express `kafkaLLD §17:939-1005` and `connLLD §19:727-788`; fixture strategy
`procCommit §13.1-13.2:1533-1616`.

- `git mv` the existing module to `TrafficCapture/trafficReplayerLegacy`, create the new module at
  `TrafficCapture/trafficReplayer`, add the build rule forbidding any dependency on the legacy module, add
  the legacy module to `excludedProjectPaths`, and redirect the eight `transformation/` modules at the
  legacy module with a status row naming `G11` (§2.1).
- Start `docs/replayerRebuildStatus.md`. Traceability from `R1`–`R19` into the designs lives in its
  `Defined in` and `Required tests` columns. **There is no derived digest or summary document** — the
  per-milestone `Design refs:` lines below plus `grep docs/captureAndReplay/` are the whole mechanism,
  deliberately, because any second copy of the design can drift whether it paraphrases or quotes.
- Declare all eight identity records: `PartitionGenerationId`, `KafkaRecordId`, `WriterPartitionId`,
  `CapturedConnectionId`, `ConnectionProcessingId`, `ReplayRequestId`, `PartitionBatchRequestId`,
  `CancellationDeadline`. Exactly eight. None added to the capture protobuf.
- Declare the named component types and sealed result types as empty shells or deliberately simplified
  implementations. This is the shells-first move: it is cheap precisely because nothing depends on them
  yet.
- Build the four deterministic fixtures: `FakeClock`, `TestEventLoop` (`runUntilIdle`/`runNext`/
  `advance`), `RecordScript` emitting `CaptureRecord` sequences with explicit partition, offset,
  `LogAppendTime`, `writerNodeId`, and payload case, and `PumpedKafkaSource` driven only by the test
  through `runOnce`. No sleeps, no polling, no unmanaged threads. One self-test must supply a
  deliberately wrong expected-association set and prove the fixture rejects it.
- Start the status table.

**Exit:** module compiles; the dependency prohibition is proved by a failing build when violated;
fixture self-tests pass; a mixed traffic/heartbeat/probe script pumps through with exact broker
timestamps and observable pause, wakeup, and commit events.

### G1 — Reality contact: decode and dump a real topic

**Design refs:** record types `proxyCaptureProtocol §4.1:408-490`; protocol records
`captureAndReplayArchitecture §2.2:261-299`; the exhaustive payload switch, including
`PAYLOAD_NOT_SET -> protocolViolation`, `kafkaLLD §7.1:486-504`; probe semantics
`kafkaLLD:501-504`.

The cheapest possible end-to-end evidence, deliberately placed first.

- Decode the `CaptureRecord` envelope exhaustively: `TrafficStream`, `WriterPartitionHeartbeat`,
  `CaptureCapabilityProbe`, and `PAYLOAD_NOT_SET` as a violation.
- **Carry over the existing dump mode.** This is not new tooling: `KafkaTopicDumper` already implements
  `dump-raw`, `dump-http`, and `dump-both` for Kafka and file sources, already decodes `CaptureRecord`,
  and is invoked from `TrafficReplayer.java:644`. Its three mode names are a user-facing CLI contract
  (§2.3), so they are preserved. `TrafficStreamDumper` and `HttpTransactionDumper` come with it. This is
  a good example of §3 rule 1 in the permissive direction — useful, close to final, and worth moving.

**Exit:** the new module reads and dumps a topic written by the **current, unmodified proxy**, and
every envelope case is handled explicitly. This is the milestone that prevents a greenfield module from
drifting away from what the proxy actually emits, and it is why it comes before any owner work.
Covers `D1` — "the replayer cannot read its own capture topic" is the total-loss-of-function defect, and
this is the milestone that retires it.

### G2 — Kafka source owner

**Design refs:** `kafkaLLD §5:235-439` in full — `§5.1` partition source state and the no-cap
trade-off, `§5.2` assignment, `§5.3` batch request and delivery, `§5.4` poll interruption and wakeup,
`§5.5` poll-result registration, `§5.6` record-processing-finished, `§5.7` commit submission. Also
`kafkaLLD §3:106-147`; `procCommit §5.1:518-651` and `§8:1048-1071`; `replayerLLD §2:75-94`.
**Required tests: `kafkaLLD §17.4:970-994`** — nineteen cases, the densest list in the corpus.

`KafkaSourceOwner`, `KafkaSourceInputQueue`, `PartitionSourceState`, `WakeupController`,
`ObservedRecordCommitQueue` registration. Deferred, coalesced `wakeup()`, suppressed during rebalance
callbacks, commit, pause, resume, and control draining. `onPartitionsAssigned` pauses the complete
resulting assignment before returning. Pause and resume are per-partition with three independent,
non-aliasing reasons: batch demand, prior-generation cleanup, revocation/shutdown. Poll failures are
fatal, never empty successes.

**Commit handling during `onPartitionsRevoked` is implemented per the design — not deferred, and not
removed.** The design has commits happening inside the callback: while it waits for the grace deadline it
processes commit-related and lifecycle inputs from `KafkaSourceInputQueue`, and completed work may still
produce commit requests that the source owner attempts
(`replayerProcessingAndCommitArchitecture.md` §9.2 step 5 and line 1320;
`replayerKafkaSourceAndIntakeLowLevelDesign.md` §15.1). `D5`'s own title carries the qualifier that matters
— "`commitSync` runs inside `onPartitionsRevoked` **with no cancellation delivered**." What is forbidden is
that specific shape: a blocking commit issued at the top of the callback before any cancellation has been
delivered, which can exceed `max.poll.interval.ms` and trigger a rebalance from inside a rebalance. The
question is which commits, when, and whether one can hold the callback past its deadline — never whether
commits occur at all.

**Exit:** proved against `PumpedKafkaSource` — a queued input wakes a long poll without interrupting a
rebalance callback or another Kafka operation; one partition paused for cleanup does not stall
unrelated partitions; a poll failure is fatal; and a commit attempted during revocation cannot hold the
callback past the grace deadline. Covers `D5`, `D12`, `D16`; contributes `R3`, `R9`.

### G3 — Replay intake owner and source assembly

**Design refs:** `kafkaLLD §6:440-466` partition intake state, `§7:467-504` applying one record in its
ten defined steps, `§8:505-562` record work tracking including association relabeling and mixed
records, `§9:563-650` source connection state. Also `procCommit §3.3:205-225`, `§5.2:652-743`,
`§6.1-6.2:763-831`; inputs table `kafkaLLD §4.1:150-206`.
**Required tests: `kafkaLLD §17.1:941-950` and `§17.2:952-960`.**

`ReplayIntakeOwner` with its own thread and typed immutable inputs, `ReplayIntakeInputQueue`,
`PartitionIntakeState`, `SourceConnectionState`, `RecordWorkTracker`. Associations are created **as each
observation is applied**, many per record, never rejecting a second association. Records contributing
source-response bytes stay associated through tuple durability. Source reconstruction is honest: a
close-truncated response is never labelled `COMPLETE`, and an incomplete final response carries no
partial bytes rendered as complete.

**Exit:** a record carrying `read+EOM` for request *N* and `read` for request *N+1* has exactly both
associations, matching the `RecordScript` oracle; no record emits completion while an expected
association remains; a source connection that dies mid-response produces a tuple that says so. No owner
blocks on a stage only its own thread can complete. Covers `D2`, `D4`, `D8`, `D11`, `D18`; contributes
`R11`, `R12`, `R13`.

### G4 — Commit authority

**Design refs:** `kafkaLLD §5.5:368-394` observed-record queue as an ordered deque tolerating physical
offset gaps, `§5.6:395-418` the single `RecordProcessingFinished` and head-removal rule, `§5.7:419-439`
commit submission and its five distinguished outcomes, `§8.3:548-562` the exact completion predicate.
Also `procCommit §3.6:362-375`, `§6.3:832-862`, `§6.4:863-870`;
`captureAndReplayArchitecture §9:908-1006`. **Required tests: `kafkaLLD §17.1:941-950`**, noting
`:950` on physical offset gaps.

`RecordProcessingFinished(KafkaRecordId)` is the only input that can advance a commit position. Commit
is a contiguous prefix from the observed-record head, computed by the Kafka source owner alone. A gap at
the head blocks every completed record behind it. Duplicate registration or completion is an invariant
failure. Every newly assigned `PartitionGenerationId` gets a fresh queue at Kafka's assigned position;
no state survives from a revoked generation.

**Exit:** no request, accumulator, target result, or policy can commit or retain a record; out-of-order
completion, head gap, contiguous advancement, duplicate registration, duplicate completion, and
completion of an unregistered record all have deterministic tests. Covers `D3`; contributes `R11`.

### G5 — Connection and request owners

**Design refs: `connLLD` in full, lines 59-726** — this milestone is essentially that document. Key
sections: `§2` owner creation and publication, `§3` inputs from intake, `§5` the two queues and `§5.3`
why two suffice, `§6` preparation, `§7` target turn and permits including the four things a permit must
not be held across, `§8` target attempt and `FirstTargetWriteSubmitted`, `§10` connection-turn
completion, `§11` final source response, `§12` tuple durability, `§13` request-processing completion,
`§14` the request state model and its fatal impossible transitions, `§15` resource lifetime, `§18`
activity monitoring. Also `procCommit §3.2:155-204`, `§3.4:226-279`, `§7:871-1047`.
**Required tests: `connLLD §19.1-19.3:729-762`.**

`TargetConnectionOwner` with separated admission and execution queues, `RequestReplayOwner`,
`TargetChannelPort`, `TargetAttemptPermitProvider`, `TupleWriter`. Two milestones per request:
`ConnectionTurnFinished` clears the turn, removes the execution entry, advances the head, and **keeps
the request in the registry**; `RequestProcessingFinished` is gated on `TupleDurable` and routed through
the connection owner. Owner removal requires an empty registry. Permits are acquired for the execution
head immediately before the turn and released as soon as an attempt produces an outcome — never held
across backoff, source-response waits, or tuple writes. `FirstTargetWriteSubmitted` is reported once
per request and stays local to the connection owner. `TargetAttemptOutcome` replaces exception-carried
no-response. Tuple output is unconditional and retried to durability.

**Exit:** every admitted request produces at most one turn completion and at most one processing
completion, and a normal completion produces both in order with the second after tuple durability; with
the permit count at 1, exactly one target attempt is in flight and queued requests consume no permits.
Covers `D6`, `D7`, `D10`, `D17`; contributes `R2`, `R8`, `R10`.

### G6 — Retry boundary and broker-time expiration

**Design refs:** `kafkaLLD §10:651-717` — `§10.1` partition timestamp validation and the fatal
backward-skew rule, `§10.2` the `ExactHeartbeat`/`FirstTrafficFallback` sealed reference, `§10.3`
expiration evaluation and its five steps. Retry boundary `kafkaLLD §11:718-743` and
`procCommit §8.2:1141-1218`, including `:1200-1202` on irreversibility and `:1204-1208` on the accepted
`S` tolerance. Derivation and proof `captureAndReplayArchitecture §8:706-907` including `§8.1` for the
`E + 2S` fallback. Proxy side `proxyCaptureProtocol §5:669-846`, `§6.3:880-907`.
**Required tests: `kafkaLLD §17.3:961-969`.**

Split retry input from final input: `RetrySourceResponse` and `FinalSourceResponse`, each accepted
exactly once. `R - B >= W` on `LogAppendTime` with an irreversible first crossing, emitting
`SourceResponseUnavailableForRetry` **before** the crossing record's payload is applied. No retry cap on
the no-response path. `(writerNodeId, partition)` heartbeat baselines; `R - M >= E + S`; restart
fallback `R - T_first >= E + 2S`, replaced by the first heartbeat after `T_first`. A higher-offset record
more than `S` below the partition's greatest observed `LogAppendTime` is process-fatal. Expired and
fresh lifetimes of one `CapturedConnectionId` get different `ConnectionProcessingId`s and cannot
cross-route. No wall-clock expiration anywhere.

**Exit:** retry behavior does not depend on Kafka read timing; expiration is broker-time end-to-end and
owned by intake state. Covers `D9`; contributes `R6`, `R7`, `R14`.

### G7 — Demand model

**Design refs:** `kafkaLLD §13:765-829` — the four transitions that may change the count, the
recomputation trigger list, and the `idle`/`requested`/`applying` state machine. Symbol definitions and
the counting rule `procCommit §8.1:1072-1140`; the explicit non-goal `§8.3:1219-1248`; the no-cap
trade-off stated once at `kafkaLLD §5.1:264-269`. The design writes this as `N = P * T`
(`procCommit:1079`); this plan writes `T_threads` because the design also uses `T` for the
first-traffic fallback timestamp (`captureArch §8.1`). Same rule; the `T_threads`/`T_first` convention is
declared at `replayerRebuildPlan.md:91`.
**Required tests: `kafkaLLD §17.4:970-994`.**

`RequestNextPartitionBatch` / `PartitionRecordBatch`, at most one outstanding request per partition
generation, `N = P * T_threads` over requests with resolved retry input and unfinished target turns.
Empty polls neither resolve a request nor reach intake. No record or byte cap exists to be removed.

**Exit:** intake requests another batch only while retry-ready supply is below `N`; a fast complete
response satisfies supply before `B + W`; a target-finished or cancelled request cannot re-enter supply;
no cap can block the reads needed to reach retry or heartbeat evidence. Covers `D15` — the hard-cap
deadlock cannot recur because no cap exists to saturate. Contributes `R4`, `R5`, `R7`, `R18`.

### G8 — Cancellation, generation cleanup, protocol violation

**Design refs:** cancellation model `replayerLLD §6:198-219`; `kafkaLLD §15:849-921` — `§15.1` graceful
including what the Kafka thread does while awaiting the deadline, `§15.2` force, `§15.3` the six
conditions for cleanup completeness. Connection side `connLLD §17:658-699`. Protocol violation
`kafkaLLD §16:922-938` and `procCommit §10.2:1390-1404`. Architecture `procCommit §3.7:376-392` and
`§9:1249-1370`, especially `§9.2:1261-1332` for the eight-step grace sequence and `§9.3` read gating.
**Required tests: `kafkaLLD §17.5:996-1005` and `connLLD §19.5:774-781`.**

`GracefulGenerationCancellation(deadline)` and `ForceGenerationCancellation` scoped to
`PartitionGenerationId`, 5-second default grace. `onPartitionsRevoked` returns as soon as intake
*accepts* force cancellation. Typed per-owner cleanup results that never authorize commit; successor
generations stay paused until `GenerationCleanupFinished`. `ProtocolViolationTerminator`: mark the record
commit-ineligible, block commits at and past that offset, pause intake, fixed 60-second drain limit,
terminate with a code distinct from 80, poison pill on restart.

**Exit:** cancellation cleanup cannot produce a commit request; a newer generation waits for the
previous one's cleanup; unrelated partitions continue throughout; a corrupted record terminates within
the drain limit without committing past the violating offset and stops at the same record on restart.
Contributes `R15`, `R16`, `R17`.

Per `../AGENTS.md` §4 and the human's explicit direction: `R16` and `R17` must have solid, fast,
deterministic unit coverage **here**. The load test is later affirmation, never the loop that discovers
these.

### G9 — Process, configuration, supervision

**Design refs:** process-failure boundary and the supervisor's six duties `replayerLLD §8:236-248`;
what is fatal versus a value `replayerLLD §4:146-164` and `async §2:44`; shutdown and failure
`procCommit §10:1371-1425`; `captureAndReplayArchitecture §12:1193-1273`. Startup validation
`procCommit:1081` (`P >= 1`, `T >= 1`) and `:1151` (`W` positive). Archive configuration and the
`preserve` / `rebase-without-expiration` modes `BringYourOwnCapturedTraffic.md:103-149`. The CLI and
inline-JSON surface, including every deprecated parse-and-warn alias, is `replayerRebuildPlan.md §7`.
**Required tests: `connLLD §19.6:783-788` and `procCommit §13.5:1708-1716`.**

`ProcessSupervisor` and the fatal ladder: `System.exit` → bounded hooks (ten minutes) → thread dump to
stderr → `Runtime.halt` with the same code. Bounded shutdown with one named limit; the fatal path skips
the intake fence; no unbounded doubling loop; no join on a group that may be dead. The full CLI and
config surface from previous-plan §7, including every deprecated parse-and-warn alias. Startup rejects
`P < 1`, `T_threads < 1`, `W <= 0`, `E <= 0`, `S < 0`.

**Exit:** killing a target event loop under load yields exit code 80 with bounded hook time and a thread
dump at the watchdog bound, and cannot hang; every retired deployed option parses, warns, has no
behavioral effect, and does not fail as an unrecognized key. Covers `D13`, `D14`; contributes `R1`,
`R19`.

### G9.5 — Full correctness review at production-complete

**Design refs:** the nine safety arguments `procCommit §12:1448-1530` are the checklist — each is a
proof the implementation must not have invalidated. Also `captureAndReplayArchitecture §14:1308-1403`
for the ten system-level arguments. The obligation and required-test inventories to check against are the
`Defined in` and `Required tests` columns of `replayerRebuildStatus.md`.

The production code is now implementation-complete and no test triage has happened yet. **One deep
correctness review of the whole implementation against the design** — the only unbounded-scope review in
the process, per `../AGENTS.md` §3.2, and placed here deliberately because this is the first moment a
complete artifact exists to review.

Reviewed as a whole rather than as a diff: the owner boundaries, the commit-authority path end to end,
every `R1`–`R19` obligation, and every `D1`–`D18` behavior confirmed absent. Findings triaged and brought
to the owner before `G10` burns time debugging a rig against code with a known structural defect.

### G10 — The fuse

**Design refs:** `captureAndReplayArchitecture §15:1404-1491` system-level verification requirements;
`procCommit §13.4:1640-1707` backpressure and Kafka tests. Background for scale and workload shape:
`docs/LoadTestingBackground.md`.

One small rig, topologically identical to the ship gate: **one proxy, two topics, one replayer**, a few
hundred requests per second, tens of seconds, docker compose rather than k8s or Argo, reusing the
existing `TrafficCapture/trafficLoadTest` scenarios. Assert on the conservation ledger and on a
source-versus-target doc count.

This is the "everything breaks, now debug it" milestone. Read the first run as an **observability test
first and a correctness test second**: gaps in what can be seen are the findings.

At this scale, run **both** exact per-record comparison and the counter-based check and confirm they
agree. That validates the cheap oracle against the expensive one, which is what earns the right to rely
on counters at 200 MB/s.

**Exit:** deliberately underspecified. Settled at the `G9` boundary with the human.

### G11 — The swing

**Design refs:** none — this milestone touches no designed behavior. Its authority is the external
contract surface in §2.3 of this document, and `replayerRebuildPlan.md` §7 for the CLI surface.

Nothing to point anywhere and nothing to rename — the new module has been at the real Gradle path, Maven
coordinates, and image mapping since `G0`. The swing is subtraction:

- Revert the eight `transformation/` modules' `build.gradle` lines from `trafficReplayerLegacy` back to
  `:TrafficCapture:trafficReplayer`, which now means the new module. Satisfying that compile surface — six
  via `testFixtures`, six via a direct dependency, four via both — or changing those modules instead is a
  red-line-2 decision taken at this boundary, not assumed. All of it is test scope, so it is cheaper than
  it looks; the awkward one is `jsonTypeMappingsSanitizationTransformer`, whose own `testFixtures` compile
  against replayer production types.
- Update the four external test files that import `org.opensearch.migrations.replay` if their imports
  moved, and the `sonar-project.properties:274` path glob if `ClientConnectionPool` is gone.
- Delete `TrafficCapture/trafficReplayerLegacy/` wholesale, and its `settings.gradle` include, and its
  `excludedProjectPaths` entry. Its entire contents were dispositioned by a single rule (§3, rule 1), so
  there is nothing to argue about file by file.

**Exit:** the assembled application runs from the new module; the §2.3 contract surface is unchanged;
the old module is gone.

### G12 — Ship gate

**Design refs:** `captureAndReplayArchitecture §15:1404-1491`; `docs/LoadTestingBackground.md` for
workload profiles, memory asymmetry, and the horizontal-scaling claims under test. Note from that
document that complete connection identity is `(writerNodeId, connectionId)` and ordering is guaranteed
within a connection, not across.

The existing k6 rig at scale: 10+ proxies, 20 topics, 10+ replayers, >100K requests/second, ~200 MB/s
aggregate, ten minutes, verified by source-versus-target doc count with all metrics reconciling.

Deliberately not specified further. The rig substantially exists — `trafficLoadTest` scenarios and
configs, the `k6LoadTest` chart with the k6-operator and Argo templates, the Grafana dashboard,
`eksCdcK6LoadTestCover.groovy`, and the console `loadtest` CLI. What it needs is delivery verification
rather than dashboards. Settled with the human when `G10` passes.

Known open question, banked and not acted on: doc counts catch loss but not ordering, and a stateful
sequence replayed out of order surfaces as a silent comparison mismatch rather than a count delta.

## 5. Per-milestone exit criteria

Every milestone, in addition to its own exit condition:

1. It compiles and is wired to its neighbors — or to a shell that names the milestone which replaces it.
2. **Its logging and metrics exist**, including the conservation counters for anything that moves
   records. This is a gate, not a nicety: it is what makes `G10` debuggable.
3. Every decision hitting an `../AGENTS.md` red line was escalated, and the reversible ones were
   recorded with their defaults.
4. The status table is updated: obligations proved, open, deferred, blocked.
5. One bounded review, findings triaged and reported.
6. **Its cited required-test sections have been read**, and each case in them is either covered, or
   listed in the status table as deferred or won't-fix with a reason. This is not a coverage metric — it
   is a finite enumerated list the design already wrote, and the honest options are "done", "deferred to
   *milestone*", or "we are not doing this, because".

**There is no coverage gate.** There is no requirement to re-read the whole design corpus — only the
sections this milestone cites. There is no prose checkpoint narrative.

## 6. Status tracking

`docs/replayerRebuildStatus.md`, grep-able, one row per obligation:

```
| ID | Obligation | Milestone | Test | State | Notes |
```

States: `proved`, `open`, `deferred(<milestone>)`, `blocked(<reason>)`, `wontfix(<reason>)`. Rows exist
for `R1`–`R19`, `D1`–`D18`, every temporary shell, every deferred test, and every reversible decision
taken on a default. This table replaces the 936-line prose execution log, which is retired.

## 7. Proxy workstream

`PA1`–`PA3` and previous-plan §6.6 are **out of scope for this document** and are handled in a separate
conversation. The proxy is not modified by this plan. `G1` deliberately consumes the current proxy's
output unchanged, which means any proxy/replayer protocol mismatch surfaces at `G1` as a decode failure
rather than at final acceptance.

## 8. Reviews

Codex is the reviewer, invoked per milestone against the milestone diff **and the design sections named in
that milestone's `Design refs:` line**, with the specific invariants to check. Findings come back to the human as a summary with impact and mitigations,
not as raw output. See `../AGENTS.md` §3.

## 9. Open decisions

Escalated per `../AGENTS.md` §2. Blocking items first.

| Item | Decision needed | Reversible? | Recommendation |
|---|---|---|---|
| Module naming | Rename the existing module to `trafficReplayerLegacy` and give the new module the real name immediately (§2.1) | Yes | **Owner's call, adopted 2026-09-22.** Removes the end-state rename entirely and keeps `trafficReplayer` Maven coordinates publishing throughout |
| Broken `traffic_replayer` image during construction | Accept it, or keep the image building from the legacy module until `G10`? | Yes | Accept. Keeping it alive means a jib edit at `G11`, and the owner has already accepted a non-working app during construction |
| Eight `transformation/` modules redirected at the legacy module | Named scaffolding removed at `G11`, or break their tests for the duration? | Yes | Redirect. One line per module, mechanical, on code scheduled for deletion — not a bridge between correctness models |
| `RecordDispositionLedger` (707 lines, 0 refs) | Delete from the legacy module now, or leave it frozen? | Yes | Leave frozen — the legacy module is reference and gets deleted whole at `G11` |
| Nine obsolete `ReplayIdentity` records | Not carried forward; new module declares exactly eight | No — internal contract break | Proceed; escalating for the record since red line 3 makes *keeping* them a decision too |
| `testFixtures` API for six `transformation/` modules | Preserve the existing surface, or update those six modules? | No — contract break | Decide at `G11`; preferring to update six test-only consumers over freezing a fixture API we'd otherwise redesign |
| Ship gate in the definition of done | Does `G12` become a required acceptance condition alongside `R1`–`R19`? | No | Deferred by the human 2026-09-22; revisit at `G10` |

## 10. Rollback to Plan B

**Switching plans is the owner's decision and his alone.** An agent never invokes Plan B, never begins
hedging toward it, and never treats a signal below as authorization to change approach. What an agent does
is **report**, in the milestone summary, and keep executing Plan A until told otherwise. These are
instrumentation, not a trigger.

Plan A fails in one specific, detectable way: the new module starts depending on the old one, in spirit
if not in the build file — legacy types carried over for convenience, shells left simplified because
real implementations were inconvenient, the swing receding indefinitely.

Signals to report, any one of which is worth the owner's attention:

1. The dependency prohibition in §2.1 is weakened or waived for any reason.
2. More than two red-line-3 decisions in one milestone resolve as "carry the legacy type forward."
3. `G1` cannot decode the current proxy's output and the cause is a design ambiguity rather than an
   implementation bug — that means the greenfield module is being built against a misread of the
   protocol, and the in-place plan's continuous contact with working code is worth more.
4. Two consecutive milestones miss their exit condition for reasons of size rather than difficulty.

Plan B is a complete alternative, not a degraded one, and the work done under Plan A is not wasted if it
is invoked: the four fixtures, the eight identity records, the `Design refs:` citations, and the status
table all transfer directly.
