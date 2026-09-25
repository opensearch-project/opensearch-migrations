# Replayer Rebuild Plan A — rebuild in place, marking what has not been decided

**Status:** primary plan, selected 2026-09-22

**Fallback:** [`replayerRebuildPlanB-inPlace.md`](replayerRebuildPlanB-inPlace.md) — see
[§10 Rollback](#10-rollback-to-plan-b)

**Supplemented by:** [`replayerRebuildPlan.md`](replayerRebuildPlan.md), which contains no competing
sequencing and is authoritative only for the `D1`–`D18` defect analysis (§2), the PA1–PA3 proxy
milestones (§3.2), the `R1`–`R19` obligation set (§6.5), and deployed-configuration compatibility (§7).

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

Rebuild the replayer **in the module it already lives in**. Every member is either live — it belongs in the
final deliverable — or inside a `REBUILD-LIMBO` region at the path it will ship from. There is no second
module, no rename, and no cutover.

The property this protects is the one the in-place attempt lost: **exactly one live correctness model.** That
attempt failed because every step had to leave the old implementation compiling beside the new one, so the
cheapest correct move at each step was to add rather than replace, and twelve slices later two models were
live. Marking gives the property directly and more cheaply than a second module did — marked code does not
compile, so nothing can depend on it, while it stays visible at the path it will ship from. A second module
achieved the same separation by keeping two classpaths apart; marking needs no classpath at all.

Three consequences worth stating, because each replaces something the earlier shape needed:

- **The module keeps its real Gradle path, Maven coordinates, package names and jib image mapping
  throughout.** That was the original mechanic's whole purpose, and it now holds trivially rather than by
  arrangement. There is no end-state rename to forget.
- **Progress is measurable and falsifiable.** `grep -rl REBUILD-LIMBO-START src` is the outstanding-work
  count, and the rebuild is complete when it reaches zero. That is a check, not a judgment.
- **The assembled application is broken for most of this plan, and that is expected.** Individual milestones
  are proved in isolation. `G10` is the first that needs a working image.

`../AGENTS.md` §8a governs how marked code is used. The rule that matters most: it is the **first** place to
look, not a graveyard. Before writing any new class, method or test, find the thing you are about to create
among the marked regions and refactor it rather than inventing beside it.

### 2.1 Module mechanics — superseded 2026-09-23

**There is one replayer module and no swing.** This section previously specified renaming the existing module
to `trafficReplayerLegacy`, starting a new module at the real path, forbidding dependencies between them,
suppressing publication on the legacy one, and redirecting eight `transformation/` modules at it for the
duration. None of that exists, and none of it is needed.

The mechanic it was protecting against is gone. Its purpose was to stop the old implementation from staying
compiled beside the new one, which is what produced two live correctness models in the in-place attempt.
In-place marking gets that property directly: carried code sits inside `REBUILD-LIMBO` regions, so it does
not compile and cannot be depended on, while remaining visible at the path it will ship from. One module,
one live implementation, no classpath to keep separate.

What that removes, all of it now deleted rather than deferred to `G11`: the second Gradle module and its
`settings.gradle` entry, the `excludedProjectPaths` publication suppression, the
`verifyReplayerModuleIsolation` build check, and the thirteen redirected dependency lines across eight
`transformation/` modules, which point at the real module again.

What it preserves unchanged, and the reason the original mechanic was designed at all: the module keeps its
real Gradle path, Maven coordinates, package names, and jib image mapping throughout, so there is never a
rename under time pressure. That was the point, and it is now true trivially rather than by arrangement.

### 2.2 What comes over, and on what terms

**Revised 2026-09-23 by the owner.** The original text here said a file moves only if it is already in
final form, and that history must not be preserved at the cost of sequencing. Both were reversed in
practice, and this section records what replaced them. The `G0` walk is the authority on the result; this
is the rule it followed.

**Everything comes over. Nothing is judged in advance.** The whole legacy tree is carried into the new
module as one up-front categorization, because the question "are we preserving the functionality?" is only
answerable if the answer is in the tree rather than in a document.

**The unit is the member, not the file** — fields, methods, nested classes. A file sits at the path it will
ship from whenever *any* part of it is wanted, and its other members are marked in place with
`REBUILD-LIMBO` regions. Two locations, and the second is small:

| Location | Means |
|---|---|
| its final path under `src/`, unmarked | live: this functionality ships |
| its final path under `src/`, inside a `REBUILD-LIMBO` region | carried; resolves later to dead, keep, or refactor |
| deleted | affirmatively abandoned, per member, once that decision is made |

**Default to marked. Being live requires a decision about that member**, recorded by the milestone that
makes it, never a consequence of happening to compile. Marking in place rather than parking files elsewhere
is deliberate: inline, the existing implementation is unavoidable when you next open the file, which is what
stops it being reinvented. See `../AGENTS.md` §8a.

The abandoned set is reserved for code that is provably dead, that the design positively forbids, or that
duplicates something already present — *not* for code whose responsibility the design merely
reassigns. That distinction was got wrong once: reassignment means refactor, and refactoring needs the code.

**`git mv`, always.** Blame is preserved on carry and on restore, with no dependence on copy detection —
which matters because GitHub's blame performs none. This inverts the original guidance: the process cost is
one `git mv` and the benefit is that every surviving line keeps its author. See the measured basis and the
three limits in `docs/replayerRebuildStatus.md`.

`../AGENTS.md` §1 red line 3 still applies, but its target moved. Carrying a file into limbo is not the
decision — **promoting one to live is**, and so is writing something new when a limbo counterpart exists.
`../AGENTS.md` §8a governs the second, and it exists because it was violated.

### 2.2a Two sources of "must survive", and they are different

A member survives for one of two independent reasons, and conflating them is how functionality gets lost:

1. **The new design needs it.** Judged against `docs/captureAndReplay/`. This is the reason most of the
   marked set will be promoted or refactored.
2. **Mainline depends on it**, regardless of what the new design says. Behaviour, contracts, CLI surface,
   metric names, exit codes, wire formats and published APIs that exist on `main` and that something outside
   this module relies on. These survive **even where the design is silent about them**, because the design
   describes the replayer's internals and says nothing about, for example, which CLI aliases deployments
   already pass.

**Reason 2 is the one that gets forgotten**, because it is invisible from inside the design. The design is the
authority on how the replayer should work; it is not an inventory of what the outside world already expects.
So "the design does not mention it" is never sufficient grounds to drop something — the question is also
whether anything on mainline would notice.

The practical test at promotion or deletion time is two questions, not one:

- Does the new design need this? If yes, promote or refactor it.
- Does anything outside this module depend on it as it exists on `main`? If yes, it survives in a form that
  keeps that dependency satisfied, even if the design would have shaped it differently. Changing it anyway is
  a red-line-2 decision under `../AGENTS.md` §1, not an implementation detail.

§2.3 enumerates the contract surface known today. **That list is evidence, not a guarantee of completeness** —
it was measured once, and two of its rows were already found overstated by an order of magnitude. Before
deleting anything, check mainline for consumers rather than trusting the list to have caught them.

**The safest deletions are therefore members that mainline never had.** Code added on this branch that the new
design does not need cannot have a mainline consumer, so it can be removed on design grounds alone.
Everything else needs the second question answered first.

### 2.3 The external contract surface

This is the complete set of things the rebuild must not break. It is small, which is why this plan is
viable. Each item is a red-line-2 contract; changing any of them is an escalated decision.

| Surface | Where it is established |
|---|---|
| **Published Maven artifact** `trafficReplayer`, plus its separately published `testFixtures` jar | Root `build.gradle:265-272` — every subproject outside `excludedProjectPaths` publishes `mavenJava`; artifactId comes from the project name |
| Docker image name `traffic_replayer` | `buildImages/build.gradle:22,46,51` |
| Dump modes `dump-raw`, `dump-http`, `dump-both` | `KafkaTopicDumper`, invoked from `TrafficReplayer.java:644` — a user-facing CLI mode for Kafka topics. The owner retired the file-backed branch on 2026-09-24 |
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
modules" as a single list when reasoning about those consumers.

Because `testFixtures` is **published to Maven**, its API is a public contract and not merely an
arrangement with six sibling modules. That raises the stakes on the `G11` decision below: updating six
in-repo consumers is cheap, but anyone outside the repo compiling against the published fixtures jar is
not visible from here.

So the entire *in-repo* compile-time coupling to be satisfied is test-scope, in one directory
tree. Everything else references the replayer as an *artifact* — by Maven coordinates, image name, service
name, CLI surface, or K8s label — and does not care which module produced it. That is the measurement that
makes this plan viable rather than ambitious.

## 3. Ground rules specific to this plan

Beyond `../AGENTS.md`:

1. **Nothing goes *live* carrying code that is going to be discarded.** Revised 2026-09-23: the original
   rule applied this test at the moment of *moving* a file, which conflated two decisions and caused code
   to be abandoned that we still wanted. Everything moves (§2.2); the test applies at **promotion** into a
   compiled source set.

   Partial work is fine in limbo. Work close to final is fine — the Netty pacing code is the archetype:
   substantively correct, expensive to re-derive, worth finishing in place. What must not go **live** is
   anything with legacy structure still attached, because that is what silently reinstalls the old shape.
   So the question at promotion is not "is this finished?" but **"is anything in here going to be deleted
   later?"** If yes, it stays in limbo, or comes back live with the doomed parts in in-file limbo regions.

   Two corollaries, both learned the hard way. A file whose responsibility the design *reassigns* still
   comes over, because reassignment is a refactor and the code is the input to it. And per `../AGENTS.md`
   §8a, if a limbo counterpart exists you refactor it rather than writing something new beside it — the
   limbo set is the first place to look, not a graveyard. This still replaces the previous plan's
   §5.1–§5.3 per-class bookkeeping, now with two questions instead of one.
2. **`D1`–`D18` are now a test list, not a work list.** There are no defects to repair in a module that
   does not exist yet. Each of the eighteen becomes a behavior the new implementation must be shown
   *not* to exhibit. The analysis keeps all of its value; it simply stops driving the sequencing.
3. **`R1`–`R19` are unchanged** and remain the definition of done.
4. **No milestone is production.** "Compiles" and "this milestone's tests pass" never imply readiness.
5. **Observability lands with the component that needs it**, not afterward. See §5.

## 4. Milestones

Dependency order, not a schedule. Each milestone is one review unit -- reviewed repeatedly until it conforms,
not reviewed once (`../AGENTS.md` §3.1).

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

- Bring every legacy member to the path it will ship from in `TrafficCapture/trafficReplayer`, marking
  everything not yet decided with `REBUILD-LIMBO` regions (§2.1, §2.2). No second module, no rename, no
  redirects: the module keeps its real path, coordinates, packages and image mapping throughout.
- Start `docs/replayerRebuildStatus.md`. Traceability from `R1`–`R19` into the designs lives in its
  `Defined in` and `Required tests` columns. **There is no derived digest or summary document** — the
  per-milestone `Design refs:` lines below plus `grep docs/captureAndReplay/` are the whole mechanism,
  deliberately, because any second copy of the design can drift whether it paraphrases or quotes.
- Declare all eight identity records: `PartitionGenerationId`, `KafkaRecordId`, `WriterPartitionId`,
  `CapturedConnectionId`, `ConnectionProcessingId`, `ReplayRequestId`, `PartitionBatchRequestId`,
  `CancellationDeadline`. Exactly eight. None added to the capture protobuf.
- Declare the named component types and sealed result types as empty shells or deliberately simplified
  implementations. This is the shells-first move: it is cheap precisely because nothing depends on them
  yet. **Partly not delivered, and now deferred rather than owed here.** Six of the seven named types —
  `PartitionIntakeState`, `ConnectionAdmissionEntry`, `TargetChannelPort`, `RequestPreparationResult`,
  `RetryDecision`, `TupleWriteResult` — are absent from `main`. The cheapness this clause relied on has
  expired: each type's shape is now decided by the milestone that gives it a consumer, so declaring an empty
  shell first would fix a shape before the thing that constrains it exists. `PartitionIntakeState` goes to
  `G3`, which already builds it; the connection and request types to `G5`; `TupleWriter`/`TupleWriteResult`
  to `G9`. One ledger row each.
- Build the four deterministic fixtures: `FakeClock`, `TestEventLoop` (`runUntilIdle`/`runNext`/
  `advance`), `RecordScript` emitting `CaptureRecord` sequences with explicit partition, offset,
  `LogAppendTime`, `writerNodeId`, and payload case, and `PumpedKafkaSource` driven only by the test
  through `runOnce`. No sleeps, no polling, no unmanaged threads. One self-test must supply a
  deliberately wrong expected-association set and prove the fixture rejects it.
- Start the status table.

**Exit:** module compiles; carried code is proved uncompilable rather than merely unused, which is what
§2.1 replaced the two-module dependency prohibition with — this clause previously asked for that
prohibition to fail a build, and the check it named no longer exists; fixture self-tests pass; a mixed
traffic/heartbeat/probe script pumps through with exact broker timestamps and observable pause, wakeup,
and commit events.

### G1 — Reality contact: decode and dump a real topic

**Design refs:** record types `proxyCaptureProtocol §4.1:408-490`; protocol records
`captureAndReplayArchitecture §2.2:261-299`; the exhaustive payload switch, including
`PAYLOAD_NOT_SET -> protocolViolation`, `kafkaLLD §7.1:486-504`; probe semantics
`kafkaLLD:501-504`.

The cheapest possible end-to-end evidence, deliberately placed first.

- Decode the `CaptureRecord` envelope exhaustively: `TrafficStream`, `WriterPartitionHeartbeat`,
  `CaptureCapabilityProbe`, and `PAYLOAD_NOT_SET` as a violation.
- **Carry over the existing Kafka dump mode.** This is not new tooling: `KafkaTopicDumper` already implements
  `dump-raw`, `dump-http`, and `dump-both`, already decodes `CaptureRecord`,
  and is invoked from `TrafficReplayer.java:644`. Its three mode names are a user-facing CLI contract
  (§2.3), so they are preserved. `TrafficStreamDumper` and `HttpTransactionDumper` come with it. This is
  a good example of §3 rule 1 in the permissive direction — useful, close to final, and worth moving.
- **Scope: `dump-raw` against a Kafka topic.** G1 needs no source owner and no architecture. The dump
  path opens a plain `KafkaConsumer` with `assign()`, no consumer group, an explicit seek, and no commit,
  which is exactly why this milestone can precede G2. The only member it needs from the marked
  `KafkaTrafficCaptureSource` is the self-contained static `buildKafkaProperties`.

**Deferred out of G1 — Kafka `dump-http` and `dump-both` to G3.** The HTTP modes require
HTTP transaction reconstruction, whose closure was the legacy accumulator and tracing identity chain.
Rebuilding those here would be the lateral milestone expansion §6 forbids. G3 rebuilds source assembly, so
the Kafka-backed modes return there — see G3. The owner retired file-backed dumping on 2026-09-24 rather
than carrying that branch into the rebuild; dump modes reject `--input` as unsupported.

**Exit:** the module reads and dumps a topic written by the **current, unmodified proxy** via `dump-raw`,
and every envelope case is handled explicitly, `PAYLOAD_NOT_SET` included. This is the milestone that
prevents a greenfield module from drifting away from what the proxy actually emits, and it is why it comes
before any owner work. Covers `D1` — "the replayer cannot read its own capture topic" is the
total-loss-of-function defect, and this is the milestone that retires it.

### G2 — Kafka source owner

**Design refs:** `kafkaLLD §5:235-439` in full — `§5.1` partition source state and the no-cap
trade-off, `§5.2` assignment, `§5.3` batch request and delivery, `§5.4` poll interruption and wakeup,
`§5.5` poll-result registration, `§5.6` record-processing-finished, `§5.7` commit submission. Also
`kafkaLLD §3:106-147`; `procCommit §5.1:518-651` and `§8:1048-1071`; `replayerLLD §2:75-94`.
**Required tests: `kafkaLLD §17.4:983-1007`** — twenty-eight cases, the densest list in the corpus, and
**shared with `G7`**. The section's title is its own partition: the Kafka half is G2's, the demand half is
G7's, which cites the same section. G2 owns cases 7 (source-side), 8, 9, 10, 13, 15, 16, 17, 18 and 19.
Cases 1–6, 11, 12, 14 and case 7's intake half need the `§13` supply count, `N = P * T_threads`, and the
retry boundary — none of which exists before `G6`/`G7` — and are deferred to `G7` with a row each in the
register's deferral ledger. Writing them here would mean standing up intake demand state inside G2, which is
the lateral expansion §6 of `AGENTS.md` forbids.

The owner-authorized assignment-bootstrap amendment arrived after G2 closed. Its new cases 25–27
are owned by G7, where source bootstrap and intake demand are implemented together; case 28's
cleanup-gated release is owned by G8. G2's Exit therefore does not claim those post-close cases.

The rejected-versus-unknown commit-outcome distinction was not proved before G2 closed and is not
part of G2's scope or Exit. G4 owns the issuance, outcome classification, conservation disposition,
and deterministic evidence as one commit-authority chain.

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
callback past the grace deadline. Also `§17.4` case 18: a wakeup arriving between revocation and assignment
postpones the assignment callback to a later poll without losing it. **The real replay construction chain is
deferred to G5**, not because startup happens later, but because G2's source owner has no real connection/request
consumer until G5 builds it. G5 constructs source owner, queues, intake owner, and connection/request consumer
as one usable chain; G9 later places that already-integrated application under process supervision and deployed
configuration. Covers `D5`, `D12`, `D16`; contributes `R3`, `R9`.

### G3 — Replay intake owner and source assembly

**Design refs:** `kafkaLLD §6:499-525` partition intake state, `§7:526-563` applying one record in its
ten defined steps, `§8:564-621` record work tracking including association relabeling and mixed
records, `§9:622-709` source connection state. Also `procCommit §3.3:205-225`, `§5.2:652-743`,
`§6.1-6.2:763-831`; inputs table `kafkaLLD §4.1:150-206`.
**Required tests: `kafkaLLD §17.1:1032-1042` and `§17.2:1043-1051`.**

> The `kafkaLLD` line numbers above were +59 stale after the owner-authorized `§5.7`, `§15.1` and `§15.4`
> amendments, and were corrected by reading the section headings rather than trusting them. **Verify a line
> citation before reading from it**, in this milestone and every later one: §6 forbids implementing from a
> paraphrase, and a stale cite is how someone reads the wrong section while believing they read the design.

`ReplayIntakeOwner` with its own thread and typed immutable inputs, `ReplayIntakeInputQueue`,
`PartitionIntakeState`, `SourceConnectionState`, `RecordWorkTracker`. Associations are created **as each
observation is applied**, many per record, never rejecting a second association. Records contributing
source-response bytes stay associated through tuple durability. Source reconstruction is honest about what it
can know: `kafkaLLD §9.2` makes a following request on the same connection the only proof that the source
finished a response, so every other completion is marked unproven rather than presented as whole, and
`SourceResponseIncomplete` is reserved for expiry and cancellation — the replayer's own doing.

Source interim responses use only PA2 item 5's typed whole or segmented interim-response observations. G3
preserves those source bytes and their record associations while leaving the current request open until its
request end marker. It does not infer an interim response from an ordinary `Write`, and it provides no
compatibility path for captures produced before the typed protocol. PA2 owns the proxy producer and its direct
tests; G3 owns decoding, source assembly, the intake queues and owner, the dump consumer, observability, and
real-topic evidence. G3 cannot close its interim-response criterion until the PA2 producer interoperates with
that complete consumer chain.

**Complete usable G3 chain.** `KafkaTopicDumper` is the bounded producer and construction path for this
milestone: Kafka records enter `ReplayIntakeInputQueue`, the real `ReplayIntakeOwner` removes and applies them
on its owner thread, and `HttpTransactionDumper` consumes source-assembly output. A FIFO queue-control marker
outside the nine-value business-input family stops acceptance and proves every preceding accepted input was
applied before the owner terminates. `ReplayIntakeMetrics`, constructed from the carried
`RootReplayerContext`, observes owner start/stop, inputs and records applied, reconstructed requests, response
completion confidence, captured closes, and protocol violations with fixed cardinality. G5 replaces
`HttpTransactionDumper` with the real connection/request consumer because the design types for that consumer
do not exist until G5; G3 nevertheless ships a reachable, observable, terminating production chain rather
than an unconstructed component.

**Inherited from G1 — restore Kafka `dump-http` and `dump-both`.** G1 deferred them because HTTP transaction
reconstruction was the legacy accumulator's job, and this is the milestone that rebuilds it. They belong
here rather than anywhere later because they are the cheapest possible observation of what this milestone
builds: run source assembly over a real topic and print each reconstructed transaction. The sink already
exists — `HttpTransactionDumper` consumes reconstruction callbacks and is carried, marked. Whether it
still needs a tracing context is a G3 question: it required `ChannelContextManager` and
`RootReplayerContext` only because the *legacy* accumulator did.

**Deferred to PA2 — proxy sequence accounting after intentional capture suppression.** G3 consumes
`RequestIntentionallyDropped` and advances or preserves the captured ordinal exactly as `§9.4` requires, but
the proxy producer currently emits the marker without incrementing the `eomsSoFar` value later serialized as
`TrafficStream.priorRequestsReceived`. Repairing that proxy-side protocol mismatch is PA2 item 4 in
`docs/replayerRebuildPlan.md`; it is not delivered by G3.

**Deferred to PA2 — typed source-interim producer.** G3 does not deliver the proxy half of source-interim
capture. PA2 item 5 adds the typed whole and segmented observations and classifies source `1xx` responses other
than `101`; G3 consumes only that protocol. This is a protocol break with no decoder or fallback for captures
written before PA2.

**Inherited lifecycle evidence reassigned by responsibility.** G3 preserves the carried files but does not
recreate the predecessor's synthetic-close mechanism. G3 proves same-generation source-lifetime reuse and
fresh-lifetime separation directly. G5 receives connection-owner retirement and connection-context lifetime;
G8 receives revocation cancellation, stale source-assembly release, cleanup acknowledgement, and successor
read gating; G11 receives only the process-teardown assertion that an interrupted source cannot bypass
application close. The register names the member-level split and the deferral ledger keeps all three receiving
milestones visible.

**Deferred to G8 — cancellation-path tracker-gauge balance.** G3 decrements
`replayIntakeActiveRecordTrackers` when ordinary completion retires a tracker, but does not deliver removal of
unfinished trackers during generation cancellation because `GenerationCleanupTracker` and the cancellation
state do not exist until G8. G8 removes those trackers, decrements the same process-wide gauge once per
removal, and proves the gauge returns to its pre-generation value without a generation metric dimension.

**Deferred to G4 — one-shot asynchronous commit resolution.** G3 does not repair the Kafka source's
commit-callback resolution path. A wakeup or callback race must not resolve one asynchronous submission twice,
but the correction has to share G4's one-operation-in-flight state, callback owner, monotonic staged-position
handling, and commit observability. G4 owns that complete chain rather than taking a G3-only latch.

**Exit:** a record carrying `read+EOM` for request *N* and `read` for request *N+1* remains unfinished after
request *N* completes and finishes exactly once only after request *N+1* completes; no record emits completion
while an expected association remains; a source connection that dies mid-response produces source-assembly output that marks
completion unproven. Every reconstituted request whose source response reaches a terminal boundary emits
exactly one final `SourceResponseComplete` or `SourceResponseIncomplete`; a later connection event cannot
replace or repeat that result. Typed whole and segmented source-interim observations preserve their bytes and record
associations without ending request assembly, and an ordinary `Write` is never accepted as a compatibility
encoding for them. No owner
blocks on a stage only its own thread can complete. The producer → queue → owner-thread → consumer path is
constructed by `KafkaTopicDumper`, terminates through its FIFO stop marker, and emits its fixed-cardinality
intake/assembly metrics. **`dump-http` and `dump-both` work again against a real topic, and a response nothing
proved finished is visible as `UNPROVEN` in that output** —
reconstruction honesty stated as something a person can read. This criterion previously asked for a
close-truncated response to be visible *as truncated*, which `§9.2` establishes is not detectable: the capture
protocol marks the end of a request and not of a response, and the owner ruled against adding response parsing
to the proxy. Unproven is the strongest claim the output can make, and a claim that can be made truthfully is
worth more than one that cannot. G3 does **not** claim that the proxy's successor
`priorRequestsReceived` baseline includes an intentionally dropped request; PA2 repairs and proves that
producer-side obligation. G3 also does **not** claim cancellation-path tracker-gauge balance; G8 owns that
cleanup exit. It does **not** claim one-shot asynchronous commit resolution; G4 owns the complete commit
submission and callback chain. Covers `D2`, `D4`, `D8`, `D11`, `D18`; contributes `R11`, `R12`, `R13`.

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

**Inherited from G3 review — one-shot asynchronous commit resolution.** Build the ordinary asynchronous
submission, its single in-flight operation identity, callback handling, wakeup propagation, staged-position
retention, and fixed-cardinality outcome observability as one owner-confined chain. Every accepted submission
resolves exactly once. A callback that races a wakeup or retirement cannot credit, clear, or restage the same
operation twice; uncertainty retains a monotonic position for a later commit and never backpedals.

**Inherited from G2 closure — rejected versus unknown outcomes.** G4 owns the complete correction:
commit issuance must distinguish a rejected operation, which proves the submitted offsets did not move, from
an unknown operation, which may already have reached the broker. Carry that distinction through owner
resolution, revocation abandonment, conservation instrumentation, and deterministic evidence; do not infer a
per-partition result from a batched operation.

**Exit:** no request, accumulator, target result, or policy can commit or retain a record; out-of-order
completion, head gap, contiguous advancement, duplicate registration, duplicate completion, and
completion of an unregistered record all have deterministic tests. The ordinary asynchronous submission and
callback chain proves each accepted operation resolves once under callback/wakeup races, and an uncertain
outcome can only preserve or advance the staged position while that generation remains owned; revocation
discards the old generation's staged state. Rejected and unknown commit operations remain distinct through
submission, resolution, revocation abandonment, fixed-cardinality observability, and conservation evidence.
Covers `D3`; contributes `R11`.

### G5 — Connection and request owners

**Design refs: `connLLD` in full, lines 59-726** — this milestone is essentially that document. Key
sections: `§2` owner creation and publication, `§3` inputs from intake, `§5` the two queues and `§5.3`
why two suffice, `§6` preparation, `§7` target turn and permits including the four things a permit must
not be held across, `§8` target attempt and `FirstTargetWriteSubmitted`, `§10` connection-turn
completion, `§11` final source response, `§12` tuple durability, `§13` request-processing completion,
`§14` the request state model and its fatal impossible transitions, `§15` resource lifetime, `§18`
activity monitoring. Also `procCommit §3.2:155-204`, `§3.4:226-279`, `§7:871-1047`.
**Required tests: `connLLD §19.1-19.3:729-762`.**

**Inherited from G2 — the real replay construction chain.** G2 could not construct its source owner against a
real downstream consumer because the connection/request owner types did not exist. Construct
`KafkaSourceOwner`, `KafkaConsumerSourcePort`, both owner queues, the wakeup controller,
`ReplayIntakeOwner`, and the real connection/request assembly sink together here. The composition root may be
a top-level application object that G9 starts under its supervisor; it must already be a reachable source →
intake → connection/request chain in this milestone. This does not inherit G3 observability: G3 already
constructs and instruments replay intake and source assembly. `ChannelContextManager`'s separate non-atomic
refcount defect remains G5 work only where the connection-owner tracing design actually requires it.

**Inherited from G0 — the shells for `ConnectionAdmissionEntry`, `TargetChannelPort`,
`RequestPreparationResult` and `RetryDecision`**, which G0 never declared. They arrive here as real types
rather than shells, since this is the milestone that gives them consumers and therefore decides their shape.
`RequestPreparationResult` is `connLLD §6`'s two cases and **only** those two: an unexpected preparation
throw is process-fatal, not an outcome value. Strike `PreparationOutcome.Filtered` and `.Failed` from
`ReplayOutcomes` as part of this — expected transformation fallback travels inside
`RequestPreparationReady` via `HttpRequestTransformationStatus`; filtering follows `connLLD §6`.

`TargetConnectionOwner` with separated admission and execution queues, `RequestReplayOwner`,
`TargetChannelPort`, `TargetAttemptPermitProvider`, `TupleWriter`. Two milestones per request:
`ConnectionTurnFinished` clears the turn, removes the execution entry, advances the head, and **keeps
the request in the registry**; `RequestProcessingFinished` is gated on `TupleDurable` and routed through
the connection owner. Owner removal requires an empty registry. Permits are acquired for the execution
head immediately before the turn and released as soon as an attempt produces an outcome — never held
across backoff, source-response waits, or tuple writes. `FirstTargetWriteSubmitted` and
`FinalTargetWriteSubmitted` are each reported once per request and stay local to the connection owner.
They answer different questions and both are needed: first-write decides whether a cancelled
request's channel must be closed rather than reused, and final-write decides whether graceful
cancellation waits on the request at all (`connLLD §8`, `§17.1`). `TargetAttemptOutcome` replaces exception-carried
no-response. Tuple output follows `connLLD §12`, including intentional transformation drop as
successful logical completion.

Request admission and every correctness-required callback use the typed links defined by `connLLD §3` and
`procCommit §4.2`: acceptance or rejection reaches replay intake exactly once, normal source-response and
request-owner results reach their required receiver exactly once, and the receiver's completion closes the
linked operation. Register each outstanding linked operation before submission, retain it until its typed
completion is processed, and expose that same registry to the activity monitor. Diagnostics observe those
owner transitions directly; no parallel callback-accounting model is introduced.

G5 restores all 33 replay-pipeline metric names shipped on `main`, with their existing units and
fixed-cardinality attributes. Its source-record, pacing/retry, connection-lifecycle, physical target-channel,
and target-byte events are emitted by the G5 context/owner/Netty chain. Request preparation receives the
transformation context whose parser and transformation callbacks emit the remaining transformation metrics,
and tuple construction receives the tuple context whose close emits `tupleComparison`. G9 only constructs
those already-defined adapters from deployed configuration; it must not replace semantic producers with owner
transition approximations.

**Deferred to G9 — configured bounded tuple-writer placement and stable sink identity.** G5 proves the
logical transform/drop/retry/durability chain with a temporary writer owned by each connection binding. G9
replaces that placement with the configured positive number of bounded writer workers, each owning one
non-concurrently invoked tuple transformer and physical sink. Each worker retains a stable integer index,
passed through deployed sink construction so S3 naming remains stable, and G9 owns configured construction,
closure, and deletion of G5's per-connection transformer/sink placement. G5's scope and Exit exclude only
that deployed placement/configuration change, not tuple transformation, intentional drop, retry, durability,
or processing-completion ordering.

**Deferred to POST1 — target interim-response preservation.** G5 builds the target response and tuple-input
chain, but it does not preserve target `1xx` responses in tuples. The current target handler may continue to
discard `1xx` responses other than `101` throughout the rewrite. POST1, after G12, owns replacing that discard
with target aggregation and tuple serialization based on PR #3000. No target-interim state is added to the G5
owner model as temporary scaffolding.

**Inherited lifecycle evidence from G3.** Preserve the assertions that a connection is reused across
keep-alive requests, that `ConnectionOwnerFinished` removes only the matching process-local connection owner,
and that connection/tracing context is scoped to `ConnectionProcessingId` rather than reused across a fresh
generation. Refactor the applicable members of `ActiveConnectionTrackingTest` and
`PartitionRevocationStaleStateTest` onto the G5 owner chain; do not restore the predecessor's source-owned
connection registry.

**G5 trace-evidence boundary.** Before G5 exits, audit every baseline method whose responsibility G5 moved,
split, substantially rewrote, or retired under `AGENTS.md` §7. Put each exact source and target record
immediately before its method. Keep trace-eligible source members marked through the final completeness sweep,
including `RETIRED` members; that sweep removes dead sources and trace records together. Do not restore a
source already absent before Plan A's carry baseline: anchor its record to `2fe4538a` at the surviving
predecessor/replacement seam and record the gap in the live register.

**Exit:** every admitted request produces at most one turn completion and at most one processing
completion, and a normal completion produces both in order with the second after tuple durability; with
the permit count at 1, exactly one target attempt is in flight and queued requests consume no permits;
**preparation has exactly the two outcomes `connLLD §6` names, and an unexpected preparation throw reaches
the process-failure boundary rather than becoming a value**. Admission acceptance/rejection and every normal
source-response, turn, processing, attempt, and tuple result reaches its required receiver exactly once through
a typed link; the link completes only after the receiver, and activity diagnostics observe the same registered
operations. The real Kafka source → replay intake →
connection/request path is constructed and reachable through its production queues, with no test-only caller
standing in for a missing consumer. G5 does not claim target-interim tuple preservation; POST1 receives and
proves that obligation after the rewrite. The inherited connection-lifetime assertions above run against the
new owner and context chain. Required evidence includes the filtered and intentional-drop cases in
`connLLD §19.3` and the completion/force race in `§19.5`. Covers `D6`, `D7`, `D10`, `D17`; contributes
`R2`, `R8`, `R10`. The method-local G5 trace audit is complete and every retained baseline source has an exact
moved, split, rewritten, or retired disposition, with any pre-carry source gap recorded.

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
after-every-input demand pass, and the bootstrap plus explicit-request state machines. Symbol definitions and
the counting rule `procCommit §8.1:1072-1140`; the explicit non-goal `§8.3:1219-1248`; the no-cap
trade-off stated once at `kafkaLLD §5.1:264-269`. The design writes this as `N = P * T`
(`procCommit:1079`); this plan writes `T_threads` because the design also uses `T` for the
first-traffic fallback timestamp (`captureArch §8.1`). Same rule; this paragraph declares the
`T_threads`/`T_first` convention.
**Required tests: `kafkaLLD §17.4:983-1007`**, shared with `G2`. **This milestone owns the demand half**:
cases 1–6, 11, 12, 14, 25–27 and the intake half of case 7, all deferred here from `G2` because they need the
`§13` supply count, `N = P * T_threads`, and — for cases 3, 4 and 14 — the `G6` retry boundary. G2 proved
the Kafka half. See the register's deferral ledger for the row behind each case.

`RequestNextPartitionBatch` / `PartitionRecordBatch`, one assignment bootstrap entitlement plus at
most one intake-issued request per partition generation, `N = P * T_threads` over requests with
resolved retry input and unfinished target turns. Empty polls resolve neither entitlement and do
not reach intake. No record or byte cap exists to be removed. G7 owns the shared
`finishedOrCancelled` supply transition and proves it directly; G8 receives the typed cancellation
producer that must invoke that transition before generation cleanup.

**Exit:** intake requests another batch only while retry-ready supply is below `N`; a fast complete
response satisfies supply before `B + W`; a target-finished request cannot re-enter supply, and the shared
cancelled-request transition is directly proved for G8 to invoke;
no cap can block the reads needed to reach retry or heartbeat evidence; **intake enforces at most one
intake-issued batch request per generation on its own side, recomputes demand after every input
including assignment, applies bootstrap and explicit batches completely in delivery order, and a
batch—or the bounded two-batch assignment overshoot—that exceeds `N` loses and reorders nothing**. Covers `D15` — the
hard-cap deadlock cannot recur because no cap exists to saturate. Contributes `R4`, `R5`, `R7`, `R18`.

### G8 — Cancellation, generation cleanup, protocol violation

**Design refs:** cancellation model `replayerLLD §6:198-219`; `kafkaLLD §15:849-921` — `§15.1` graceful
including what the Kafka thread does while awaiting the deadline, `§15.2` force, `§15.3` the six
conditions for cleanup completeness. Connection side `connLLD §17:658-699`. Protocol violation
`kafkaLLD §16:922-938` and `procCommit §10.2:1390-1404`. Architecture `procCommit §3.7:376-392` and
`§9:1249-1370`, especially `§9.2:1261-1332` for the eight-step grace sequence and `§9.3` read gating.
**Required tests: `kafkaLLD §17.5:996-1005`, `kafkaLLD §17.4` case 28, and
`connLLD §19.5:774-781`.**

`GracefulGenerationCancellation(deadline)` and `ForceGenerationCancellation` scoped to
`PartitionGenerationId`, 5-second default grace. `onPartitionsRevoked` returns as soon as intake
*accepts* force cancellation. Typed per-owner cleanup results that never authorize commit; successor
generations stay paused until `GenerationCleanupFinished`. `ProtocolViolationTerminator`: mark the record
commit-ineligible, block commits at and past that offset, pause intake, fixed 60-second drain limit,
terminate with a code distinct from 80, poison pill on restart. Every cancellation-ended target turn invokes
G7's `finishedOrCancelled` supply transition exactly once before cleanup; later retry input cannot re-add it.

**Inherited lifecycle evidence from G3.** Refactor the revocation and synthetic-close tests into the typed
generation-cancellation model: stale source assembly is released before successor-generation records apply,
cleanup acknowledgement is generation-scoped and idempotent, and real reads resume only after every prior
generation cleanup obligation settles. This receives the cancellation members of
`StaleAccumulationCancelOnRejoinTest`, `StaleAccumulationCancelOnRejoinKafkaTest`,
`PartitionRevocationStaleStateTest`, `TrafficSourceReaderInterruptedCloseWiringTest`, and
`TrafficSourceReaderInterruptedCloseAccountingTest`. Preserve the assertions; do not restore
`TrafficSourceReaderInterruptedClose`. Generation cleanup also decrements
`replayIntakeActiveRecordTrackers` for every unfinished tracker it removes, without adding generation as a
metric dimension.

**Exit:** cancellation cleanup cannot produce a commit request; a newer generation waits for the
previous one's cleanup; unrelated partitions continue throughout; a corrupted record terminates within
the drain limit without committing past the violating offset and stops at the same record on restart. The
inherited revocation/cleanup assertions above pass through typed cancellation and cleanup inputs, and the
process-wide active-record-tracker gauge returns to its pre-generation value when cleanup completes.
Cancellation removes every counted retry-ready request from supply exactly once before cleanup and late
retry input cannot re-add it.
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

**Inherited from G5 — construct the preserved metric producers from deployed configuration.** Startup selects
the G5 request-preparation, target-channel, pacing/retry, source-read, and tuple-construction adapters without
changing their metric names, units, attributes, or semantic events. Do not substitute owner-transition
approximations for parser, physical-channel, byte, or tuple-comparison events.

**Inherited from G5 — replace temporary tuple-writer placement.** Construct the configured positive number of
bounded writer workers; each worker owns one transformer and sink that are never invoked concurrently and are
closed explicitly. Preserve a stable integer worker index and pass it to deployed sink construction, including
S3 object naming. Delete G5's per-connection transformer/sink creation and closure when the worker pool is
wired. `TupleWriter` and `TupleWriteResult` are already live G5 types, not G9 shells.

**Exit:** killing a target event loop under load yields exit code 80 with bounded hook time and a thread
dump at the watchdog bound, and cannot hang; every retired deployed option parses, warns, has no
behavioral effect, and does not fail as an unrecognized key; the already-integrated replay application from
G5 is started and stopped through the supervisor and deployed configuration, without replacing its owner or
queue construction; configured startup selects every preserved replay-pipeline metric producer without
changing its semantics; the bounded writer workers retain stable sink indices and G5's temporary
per-connection writer placement is gone. Covers `D13`, `D14`; contributes `R1`, `R19`.

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

### G11 — Formerly the swing; now the last marked region

**Largely dissolved 2026-09-23.** There is no module to delete, nothing to point anywhere, and no rename —
see §2.1. The external contract surface in §2.3 is unchanged and still must not break, but satisfying it is
no longer a distinct step: it happens when the members those consumers need are promoted.

What remains under this heading is bookkeeping that the earlier milestones produce as a side effect:

- Every `REBUILD-LIMBO` region resolved, so `grep -rl REBUILD-LIMBO-START src` is empty. This is the real
  completion signal for the whole rebuild, and it is checkable.
- The `TrafficSourceReaderInterruptedCloseWiringTest` assertion that interrupted source teardown cannot skip
  application close is refactored onto the final supervisor/application chain. Its revocation and cleanup
  assertions belong to G8; G11 owns only this process-teardown member.
- The `sonar-project.properties:274` path glob updated if `ClientConnectionPool` is gone.
- The `REBUILD-LIMBO` scaffolding note in the module's `build.gradle` removed with the last region.

**Exit:** the assembled application runs, the §2.3 contract surface is unchanged, interrupted source teardown
still reaches application close, and no marked region remains.

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

### POST1 — Preserve target interim responses in tuples

**Scheduled after the G0–G12 rewrite workstream.** This milestone does not gate the rewrite or G12. It is the
named receiver for target-side interim-response preservation deliberately excluded from G5.

Use PR #3000 as the implementation starting point. Replace `InterimHttpResponseHandler`'s discard of target
`1xx` responses other than `101` with a complete usable chain: the target channel produces typed interim
response data, target aggregation owns it, tuple input carries it, tuple serialization writes it, construction
wires the path, and metrics/spans plus focused tests prove it. Keep `101` on the terminal protocol-switch path.
Do not introduce a second source of target-response truth.

**Exit:** tuples preserve every target interim response in wire order before the final target response, for
both content-free and content-bearing/segmented forms supported by the target decoder; the producer, owner,
consumer, observability, construction path, and evidence are all present, and the POST1 TODO in
`InterimHttpResponseHandler` is removed.

## 5. Per-milestone exit criteria

Every milestone, in addition to its own exit condition:

1. It compiles and is wired to its neighbors — or to a shell that names the milestone which replaces it.
2. **Its logging and metrics exist**, including the conservation counters for anything that moves
   records. This is a gate, not a nicety: it is what makes `G10` debuggable.
3. Every decision hitting an `../AGENTS.md` red line was escalated, and the reversible ones were
   recorded with their defaults.
4. The status table is updated: obligations proved, open, deferred, blocked.
5. Review repeated until a pass returns no unfixed design-conformance defect, per `../AGENTS.md` §3.1. Other
   findings triaged once and reported.
6. **Its cited required-test sections have been read**, and each case in them is either covered, or
   listed in the status table as deferred or won't-fix with a reason. This is not a coverage metric — it
   is a finite enumerated list the design already wrote, and the honest options are "done", "deferred to
   *milestone*", or "we are not doing this, because".
7. **Its timing, ordering, interruption and waiting tests were falsified** — each shown to fail when the
   property is removed from production, per `../AGENTS.md` §4.1, with the inversion and the observed
   failure recorded. A test that still passes is the finding.

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
conversation. The proxy is not modified by this plan. G1 consumes the protocol that exists before PA2; G3's
source-interim criterion then depends on PA2 item 5's typed protocol producer and supplies its replayer
consumer. Any other proxy/replayer protocol mismatch still surfaces at the earliest consuming milestone as a
decode or interoperability failure rather than at final acceptance.

## 8. Reviews

Codex is the reviewer, invoked per milestone against the milestone diff **and the design sections named in
that milestone's `Design refs:` line**, with the specific invariants to check. Findings come back to the human as a summary with impact and mitigations,
not as raw output. See `../AGENTS.md` §3.

**Repeated, not single.** `../AGENTS.md` §3.1 gates closure on a pass returning no unfixed
design-conformance defect, and every production change — including one made to fix a finding — re-opens the
review. `G0`–`G2` took five passes, and the later ones found defects in the earlier ones' fixes. Use
`../tools/review-prompt-design-conformance.md`: the prompt is the instrument, and each of its requirements
corresponds to a way a pass here has actually gone wrong.

The review pass also runs the **falsification check** of `../AGENTS.md` §4.1 as a separate subagent in a
throwaway worktree: it breaks one timing, ordering, interruption or waiting property at a time and
confirms the corresponding test fails. This is the one review activity that mutates code, which is why it
is isolated to a discarded worktree rather than run under §3.2a's read-only reviewer. It starts at `G3`,
whose accumulation and apply-order tests are the first large set of ordering assertions in the rebuild.

## 9. Open decisions

Escalated per `../AGENTS.md` §2. Blocking items first.

| Item | Decision needed | Reversible? | Recommendation |
|---|---|---|---|
| Module naming | ~~Rename the existing module to `trafficReplayerLegacy`~~ | — | **Reversed 2026-09-23.** One module, marked in place; see §2.1. The goal it served — no end-state rename, coordinates publishing throughout — holds trivially now |
| Broken `traffic_replayer` image during construction | Accept it, or keep the image building from the legacy module until `G10`? | Yes | Accept. Keeping it alive means a jib edit at `G11`, and the owner has already accepted a non-working app during construction |
| Eight `transformation/` modules redirected at the legacy module | Named scaffolding removed at `G11`, or break their tests for the duration? | Yes | Redirect. One line per module, mechanical, on code scheduled for deletion — not a bridge between correctness models |
| `RecordDispositionLedger` (707 lines, 0 refs) | Delete from the legacy module now, or leave it frozen? | Yes | Leave frozen — the legacy module is reference and gets deleted whole at `G11` |
| Nine obsolete `ReplayIdentity` records | Marked, not promoted; exactly eight design identities declared | No — internal contract break | Proceed; escalating for the record since red line 3 makes *keeping* them a decision too |
| `testFixtures` API for six `transformation/` modules | Preserve the existing surface, or update those six modules? | No — contract break | Decide at `G11`; preferring to update six test-only consumers over freezing a fixture API we'd otherwise redesign |
| Ship gate in the definition of done | Does `G12` become a required acceptance condition alongside `R1`–`R19`? | No | Deferred by the human 2026-09-22; revisit at `G10` |

## 10. Rollback to Plan B

**Switching plans is the owner's decision and his alone.** An agent never invokes Plan B, never begins
hedging toward it, and never treats a signal below as authorization to change approach. What an agent does
is **report**, in the milestone summary, and keep executing Plan A until told otherwise. These are
instrumentation, not a trigger.

Plan A fails in one specific, detectable way: **marked code becomes load-bearing rather than being resolved.**
Not by compiling — it cannot — but by the marked set ceasing to shrink while new code is written beside it. The
symptoms are new implementations appearing next to marked counterparts that were never read, regions being
un-marked wholesale without the legacy structure stripped, and the marked count flat across a milestone.

Signals to report, any one of which is worth the owner's attention:

1. `grep -rl REBUILD-LIMBO-START src` does not fall across a milestone that was supposed to resolve regions.
2. A new class or test is added where a marked counterpart existed, without that counterpart having been read
   and its reuse explicitly rejected. This is an `../AGENTS.md` §8a violation and has already happened once.
3. Any escape or transformation of marked code is introduced without a guard that reverses it mechanically.
   `tools/unmark-limbo.awk` must continue to round-trip every marked file.
4. `G1` cannot decode the current proxy's output and the cause is a design ambiguity rather than an
   implementation bug — that means the rebuild is proceeding against a misread of the protocol.
5. Two consecutive milestones miss their exit condition for reasons of size rather than difficulty.

Plan B is a complete alternative, not a degraded one, and the work done under Plan A is not wasted if it
is invoked: the four fixtures, the eight identity records, the `Design refs:` citations, and the status
table all transfer directly.
