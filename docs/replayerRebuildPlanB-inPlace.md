# Replayer Rebuild Plan B — refactor in place

**Status:** fallback plan. Not in effect. Invoked only on explicit instruction.

**Primary:** [`replayerRebuildPlanA-inPlace.md`](replayerRebuildPlanA-inPlace.md) — selected
2026-09-22. Read that first; this document assumes it and describes what changes if it is abandoned.

**Supersedes:** [`replayerRebuildPlan.md`](replayerRebuildPlan.md) for sequencing and per-step
procedure. That document remains authoritative for the `D1`–`D18` defect analysis (§2), the `R1`–`R19`
traceability matrix (§6.5), and deployed-configuration compatibility (§7).

**Governed by:** [`../AGENTS.md`](../AGENTS.md). Its red lines, escalation format, review policy, test
policy, and `-x spotless` rule apply to every milestone below and are not restated here.

**Authoritative design:** unchanged — the documents under `docs/captureAndReplay/`.

---

## 1. When this plan is in effect

Two ways in.

**From Plan A's tripwires** (Plan A §10): the dependency prohibition gets waived, legacy types start
coming over for convenience, `G1` can't decode the real proxy output for design reasons, or two
consecutive milestones miss their exit for reasons of size. Any of those means the greenfield module is
quietly becoming a second copy of the problem, and continuous contact with working code is worth more
than isolation.

**From a standing start**, if the greenfield approach is rejected before `G0`.

This plan is a complete alternative, not a degraded one. It is slower and carries more risk of the
specific failure that stalled the first attempt, and §2 is about why — but it has one real advantage
Plan A gives up: the application keeps working, so every step is verifiable against reality instead of
against a fixture.

### 1.1 Entering from a partially executed Plan A

If Plan A ran and stopped, the tree holds a second module with shells, the four deterministic fixtures,
the eight identity records, and the status table. **None of that is wasted and none
of it should be deleted reflexively.**

| Artifact | Disposition on entering Plan B |
|---|---|
| Plan A's per-milestone `Design refs:` citations | Keep. They are plan-independent traceability into the designs, and the `B`↔`G` mapping in §5 makes them usable as-is. |
| `docs/replayerRebuildStatus.md` | Keep. Re-map the milestone column from `G`*n* to `B`*n*. |
| The four test fixtures (`FakeClock`, `TestEventLoop`, `RecordScript`, `PumpedKafkaSource`) | Move into the existing module's `testFixtures`. These are the highest-value transfer — they are what makes the test loop fast and deterministic, and they were written against no production code. |
| The eight identity records | Move in. They replace `ReplayIdentity`'s twelve. |
| Any component built under Plan A | Move in file by file, on Plan A §3 rule 1's test: partial or nearly-final work is welcome; anything still carrying legacy structure destined for deletion is not. |
| Shells never filled in | Delete. A shell with no implementation and no caller is not an asset in a plan that has working code to refactor. |
| The two module directories | Under Plan A §2.1 the *new* module holds the real name `trafficReplayer` and the old code sits at `trafficReplayerLegacy`. Reversing that means: drain what you want out of the new module, delete it, `git mv trafficReplayerLegacy` back to `trafficReplayer`, remove the legacy `settings.gradle` include and its `excludedProjectPaths` entry, and revert the eight `transformation/` module redirects. |

That reversal is a red-line-2 decision — it touches `settings.gradle`, the published Maven coordinates, and
the `traffic_replayer` image mapping — and it blocks on the owner. Say what is being drained and what is
being dropped. Note the asymmetry deliberately built into Plan A: **its happy path is a deletion and its
abandonment path is a rename.** That is the right way round, and it is a reason to be confident about Plan A
rather than a reason to hedge.

## 2. The one rule that has to change

The first in-place attempt ran S0–S6b and left responsibilities where they started: permits, retry,
backoff, and channel lifecycle still together in `RequestSenderOrchestrator` whose extraction target does
not exist; request-lifecycle state still in `TargetConnectionOwner` despite the split with
`RequestReplayOwner` having nominally happened; both correctness models live at once; the entire
Kafka-source layer unbuilt. Plan A §1 has the details.

The previous diagnosis — that a no-scaffolding rule forced atomic cutovers which then got subdivided —
is only part of it. Churn predated the compatibility layers. The durable mechanism is simpler:

> **Every step had to leave the old implementation compiling. Under that constraint, adding is always
> cheaper than replacing. So every step added.**

Plan A removes the constraint. Plan B cannot — that constraint *is* Plan B. So Plan B has to attack the
consequence instead, and it does so by moving the prohibition from the wrong place to the right one:

- **The previous plan forbade scaffolding.** That made cutovers atomic, so steps got large, so they got
  subdivided into "migrate the remaining callers" slices. The rule meant to prevent debt produced churn.
- **This plan permits scaffolding and forbids co-existing correctness models.** Scaffolding is allowed
  wherever it keeps a step small, on one condition: a row in the status table naming the milestone that
  removes it. What is *not* allowed is two live mechanisms for the same decision — two ways to know a
  record is done, two ways to authorize a commit, two ways to expire a connection.

The difference is that the second rule catches the actual failure. `ReplayTransaction` with six
production callers next to `RecordWorkTracker` and `ObservedRecordCommitQueue` is not a compatibility
bridge with a removal date; it is two answers to "is this record finished." A shim that adapts an old
signature to a new one is harmless. A second model is not.

**Corollary, and the thing to enforce mechanically:** for each subsystem, the new model's first
production caller and the old model's last production caller land in the **same** milestone. There is no
in-between state where both have callers. This is the invariant a reviewer checks with `grep`, not
judgment.

## 3. First acts, before any milestone

Cheap, unblock everything else, and shrink the surface the rest of the plan reasons about.

1. **Delete `lifecycle/RecordDispositionLedger`.** 707 lines, zero production references, zero test
   references. It has been dead for some time. Free.
2. **Inventory the second correctness model.** Enumerate `ReplayTransaction`'s six production callers
   and the types that exist only to serve it: `ReplayTransactionRegistry` (391, 1 production caller),
   `RecordDisposition` (1), `ResolvedRecordIndex` (165, 1), `SourceReconstructionPolicy` (1),
   `ReplayTransactionMetrics` (1). Bring the list and a removal plan to the owner as one escalation.
   Do not start removing callers one at a time — that is the churn pattern.
3. **Reduce `ReplayIdentity` from twelve records to eight.** The nine legacy or invented records
   (`ConnectionSessionKey`, `ReplaySessionWorkId`, `SourceConnectionKey`,
   `SourceConnectionPartitionGenerationKey`, `SourceControlRecordId`, `SourcePartitionKey`,
   `SourceRequestAssemblyId`, `TerminalSourceConnectionId`, `TrafficStreamRecordId`) go; the five missing
   design records (`WriterPartitionId`, `CapturedConnectionId`, `ConnectionProcessingId`,
   `PartitionBatchRequestId`, `CancellationDeadline`) arrive. Exactly eight when done, and none added to
   the capture protobuf. Identity is what every later milestone is expressed in, so a wrong identity set
   is paid for repeatedly.
4. **Do not write a derived design digest or summary.** Traceability lives in Plan A's per-milestone
   `Design refs:` lines and in `replayerRebuildStatus.md`'s `Defined in` column; everything else is
   `grep docs/captureAndReplay/`. Any second copy of the design can drift, whether it paraphrases or
   quotes verbatim.
5. **Start `docs/replayerRebuildStatus.md`**, or re-map it if inherited.
6. **Build the four deterministic fixtures** in the module's `testFixtures` — same as Plan A `G0`,
   including the self-test that supplies a deliberately wrong expected-association set and proves the
   fixture rejects it. Note that `testFixtures` here is **published to Maven as its own jar** (root
   `build.gradle:265-272`) as well as consumed by six `transformation/` modules, so additions are cheap
   and removals are a red-line-2 contract change. This matters more in Plan B than in Plan A: Plan B
   modifies the module that is *already* publishing, so every fixture-API change ships.

## 4. Rewrite-or-refactor classification

This is the section Plan B exists for. Plan A disposes of the entire old module with one rule; Plan B has
to make the call file by file.

**Read the confidence note before using this table.** The classification below is derived from measured
line counts, measured production and test reference counts, and each file's role in the design. It is
**not** derived from having read all 31,867 production lines. Treat it as the starting hypothesis, to be
confirmed or overturned when the file is first opened. An overturn is a normal outcome, not a failure —
but per `../AGENTS.md` red line 3, **flipping a `Rewrite` to a `Refactor` is an escalated decision**,
because that is precisely the choice to preserve something that makes the legacy structure survive.

**The `Lines` column in the tables below is context for scoping the work, never the reason for a
classification** — the reason is in the adjacent column. A long file is not a defect if it reads as one
coherent state machine; what makes a file a `Rewrite` is holding responsibilities the design assigns
elsewhere, or correctness depending on informal combinations of independent fields.

Legend — `Rewrite`: the file is encasement; its replacement shares no structure. `Refactor`: it contains
technique that is correct and expensive to rediscover; reshape it. `Delete`: the design has no such
concept. `Keep`: already in final or near-final form.

### 4.1 The encasement — rewrite

| File | Lines | Why |
|---|---|---|
| `RequestSenderOrchestrator` | 2,560 | Permit handling, retry, backoff, and channel lifecycle are interleaved here; the design separates all four, and its extraction target `TargetChannelPort` does not exist. Four owners' responsibilities in one file, not merely a big file. |
| `lifecycle/TargetConnectionOwner` | 2,134 | Needs separated admission and execution queues and the two-milestone request model; neither is a local change. It also still holds request-lifecycle responsibilities the design splits with `RequestReplayOwner`. |
| `CapturedTrafficToHttpTransactionAccumulator` | 933 | Home of `D2`, `D4`, `D8`, `D18`. Associations must be created as each observation is applied, many per record; source reconstruction must be honest about truncation. The existing shape assumes one association per record. |
| `TrafficReplayerCore` | 945 | Old scheduling and lifecycle model. |
| `TrafficReplayerTopLevel` | 936 | Same. Replaced by `ProcessSupervisor` plus the owner set. |

The two large owners dominate this list. Confirming or overturning their `Rewrite` classification
is the single highest-value read in this plan and should happen in `B0`, before sequencing is committed.

### 4.2 Valuable technique — refactor

| File | Lines | What is worth keeping |
|---|---|---|
| `TrackingKafkaConsumer` | 1,174 | Real, hard-won Kafka mechanics: pause/resume, wakeup, commit, rebalance callbacks. The design reassigns *authority* over these to `KafkaSourceOwner`; it does not change what the consumer must do. Reshape, don't rediscover. |
| `KafkaTrafficCaptureSource` | 912 | Same reasoning, one layer up. |
| `NettyPacketToHttpConsumer` | 896 | Netty pipeline handling. Correct and unpleasant to rewrite. Target-side changes are mostly at its boundary. |
| `TrafficReplayer` | 1,034 | The CLI and config surface is a red-line-2 contract (previous plan §7, including the deprecated parse-and-warn aliases). `--lookaheadTimeSeconds` is load-bearing for deployed Kubernetes: the alias is declared at `TrafficReplayer.java:247`, and what turns any alias into an accepted inline-JSON key is the derivation loop at `coreUtilities/.../jcommander/JsonCommandLineParser.java:394-398`. Refactor behind a preserved surface. |
| `lifecycle/ReplayIntakeOwner` | 570 | Already the right shape; needs `PartitionIntakeState` and `SourceConnectionState` underneath it. |
| `lifecycle/RequestReplayOwner` | 556 | Right concept, wrong split against `TargetConnectionOwner`. Re-cut the boundary rather than rewriting both. |
| `lifecycle/ReplayIdentity` | 190 | Twelve records to eight — see §3 item 3. |
| `ObservedRecordCommitQueue` | 174 | Commit-authority mechanism. Refactor to be the *only* path that advances a position. |
| `ProcessSupervisor` | 146 | Needs the full fatal ladder: `System.exit` → bounded hooks → thread dump → `Runtime.halt` with the same code. |

### 4.3 No such concept in the design — delete

Each row is a red-line-2 or red-line-3 escalation, because deleting these changes internal contracts and
*keeping* them would equally be a decision.

| File | Lines | Prod refs | Why it goes |
|---|---|---|---|
| `lifecycle/RecordDispositionLedger` | 707 | 0 | Dead. Delete in §3 item 1, free. |
| `lifecycle/ReplayTransaction` | 717 | 6 | The second correctness model. Its removal is the central act of this plan. |
| `ReplayTransactionRegistry` | 391 | 1 | Exists to serve `ReplayTransaction`. |
| `ResolvedRecordIndex` | 165 | 1 | Same. |
| `RecordDisposition` / `SourceReconstructionPolicy` / `ReplayTransactionMetrics` | — | 1 each | Same. |
| `ReplayEngine` | 321 | 3 | Old scheduling model; superseded by owner threads and the demand model. |
| `BlockingTrafficSource` | 304 | 5 | Blocking lookahead is replaced by `RequestNextPartitionBatch` / `PartitionRecordBatch`. **The CLI option `--lookaheadTimeSeconds` must survive as parse-and-warn** — the mechanism goes, the contract does not. |
| `ExpiringTrafficStreamMap` | 218 | 2 | Wall-clock expiration. `D9`, `R6`, `R7` require broker-time `LogAppendTime` expiration owned by intake state. Direct contradiction of the design, not a refinement. |
| `KafkaRecordOwnershipBudget` | 182 | 1 | The design has no record or byte cap. Its removal is what the previous plan's S11/S12 deadlock was about — see §5. |
| `ReplayProgressController` | 416 | 5 | No counterpart in the design. Five production callers, so verify what they need before deleting; the likely answer is the demand model plus the conservation counters. |
| `BehavioralPolicy` | 114 | 3 | Policy-object indirection the design replaces with typed results and explicit owners. Callers are `ExpiringTrafficStreamMap`, `ExpiringKeyQueue`, and `CapturedTrafficToHttpTransactionAccumulator` — the first two are themselves `Delete`, so most of this resolves for free. Classification least certain in this table, but also the smallest stake. |

### 4.4 Already right — keep

`RecordWorkTracker` (232), `TargetAttemptPermitProvider` (301), `OwnerTransitionRunner` (202),
`ReplayContexts` (878 — and its OTEL metric and trace names are a red-line-2 contract consumed by the
Grafana dashboard in `deployment/k8s/charts/components/k6LoadTest`), plus the previous plan's §5.4 list:
`CompletionGate`, `ActorMailbox`, `OwnerThreadGuard`, `ByteBufList`, `NettyUtils`, `RefSafeHolder`, the
Netty data-handler pipeline, `SourceTargetCaptureTuple`.

### 4.5 Still absent, so built new either way

`KafkaSourceOwner`, `KafkaSourceInputQueue`, `PartitionSourceState`, `WakeupController`,
`PartitionIntakeState`, `SourceConnectionState`, `WriterPartitionTimeState`, `GenerationCleanupTracker`,
`TupleWriter`, `ProtocolViolationTerminator`, `TargetChannelPort`. Not exhaustive — it is every named
component the milestones require that `grep` cannot find.

The entire Kafka-source and intake-state layer is in this list. **Plan B's advantage over Plan A is
smallest exactly here** — for these there is nothing to refactor, so the work is identical in both
plans and the in-place constraint buys nothing. Conversely §4.2 is where Plan B genuinely wins: nine
files, ~5,700 lines of working Kafka and Netty mechanics that Plan A must re-derive.

That asymmetry is the real trade, and it suggests the sequencing in §5: **do the absent layer first**,
while the old model is still intact and usable as a reference, and delete the old model only once its
replacement has a caller.

## 5. Milestones

> **How to read the milestone table below.** The `Content` column is **scope, not specification**. The
> designs under `docs/captureAndReplay/` define behavior and are the only authority; where this table's
> wording and a design section differ, the design is right and this plan has a bug — implement the design
> and report the discrepancy.
>
> **Design citations are not duplicated here.** Each `B` milestone covers the same design surface as the
> `G` milestone it corresponds to, so use Plan A's `Design refs:` lines via this mapping:
>
> | `B1` | `B2` | `B3` | `B4` | `B5` | `B6` | `B7`+`B8` | `B9` | `B10` | `B10.5` | `B11` | `B13` |
> |---|---|---|---|---|---|---|---|---|---|---|---|
> | `G1` | `G2` | `G3` | `G4` | `G5` | `G6` | `G7` | `G8` | `G9` | `G9.5` | `G10` | `G12` |
>
> `B0` corresponds to `G0` for the identity and fixture work, and adds the classification read
> that has no `G` counterpart. `B12` (test triage) and `G11` (the swing) have no design refs, because
> neither touches designed behavior.

Dependency order, not a schedule. One bounded review unit each. Obligation and defect assignment matches
Plan A's `G`-series milestone for milestone, so the status table is shared. Assignment only — whether an
obligation is proved, open, or deferred lives in `docs/replayerRebuildStatus.md`, never here.

A constraint that **does not dissolve** here, unlike in Plan A: `B7` and `B8` must land in a single merge
unit. Deleting `KafkaRecordOwnershipBudget` before the demand model exists creates a window with neither
a cap nor a supply target, which deadlocks reads and starves the evidence needed to reach retry and
heartbeat thresholds. Plan A has no caps to delete and so has no such coupling. This is one concrete
place where Plan B is harder, and it should not be subdivided to make it reviewable — if the merged unit
is too large to review, escalate rather than slice it.

| Milestone | Content | Covers |
|---|---|---|
| `B0` | §3 first acts, plus reading the two large owners to confirm or overturn §4.1. Deliverable: the classification table with its confidence upgraded and every flip escalated. | — |
| `B1` | Exhaustive `CaptureRecord` envelope decode — `TrafficStream`, `WriterPartitionHeartbeat`, `CaptureCapabilityProbe`, `PAYLOAD_NOT_SET` as a violation — proved against a topic written by the current unmodified proxy. Cheap, and the same reality-contact move as Plan A `G1`. | `D1` |
| `B2` | `KafkaSourceOwner` and the source layer, refactoring `TrackingKafkaConsumer` and `KafkaTrafficCaptureSource` beneath it. Deferred coalesced `wakeup()`, suppressed during rebalance callbacks; `onPartitionsAssigned` pauses the complete assignment before returning; per-partition pause/resume with three non-aliasing reasons; poll failures fatal, never empty successes. Commit handling during `onPartitionsRevoked` is implemented per the design, **not deferred and not removed** — the callback processes commit-related inputs while awaiting the grace deadline, and `D5` forbids only the current shape, a blocking commit issued before any cancellation has been delivered. See Plan A `G2` for the full statement. | `D5`, `D12`, `D16`; `R3`, `R9` |
| `B3` | `PartitionIntakeState`, `SourceConnectionState`, and the accumulator rewrite. Associations created as each observation is applied, many per record, never rejecting a second; records contributing source-response bytes stay associated through tuple durability; truncated responses never labelled `COMPLETE`. No owner blocks on a stage only its own thread can complete. | `D2`, `D4`, `D8`, `D11`, `D18`; `R11`, `R12`, `R13` |
| `B4` | Commit authority. `RecordProcessingFinished(KafkaRecordId)` the only input that advances a position; contiguous prefix from the observed head computed by the source owner alone; head gap blocks everything behind it; duplicate registration or completion is an invariant failure; fresh queue per `PartitionGenerationId`. **`ReplayTransaction` and §4.3's dependent types die here** — this is the milestone where the old model's last caller and the new model's first caller coincide. | `D3`; `R11` |
| `B5` | Re-cut `TargetConnectionOwner` / `RequestReplayOwner`, extract `TargetChannelPort` from `RequestSenderOrchestrator`, add `TupleWriter`. Separated admission and execution queues; two milestones per request, with `RequestProcessingFinished` gated on `TupleDurable`; owner removal requires an empty registry; permits released as soon as an attempt produces an outcome, never held across backoff or tuple writes; `TargetAttemptOutcome` replaces exception-carried no-response. | `D6`, `D7`, `D10`, `D17`; `R2`, `R8`, `R10` |
| `B6` | Retry boundary and broker-time expiration. `RetrySourceResponse` / `FinalSourceResponse` each accepted once; `R - B >= W` on `LogAppendTime` with an irreversible first crossing emitting `SourceResponseUnavailableForRetry` *before* the crossing record's payload is applied; no retry cap on the no-response path; `(writerNodeId, partition)` heartbeat baselines; `R - M >= E + S`; restart fallback `R - T_first >= E + 2S`; backward skew past `S` is process-fatal. **`ExpiringTrafficStreamMap` dies here.** No wall-clock expiration anywhere. | `D9`; `R6`, `R7`, `R14` |
| `B7`+`B8` | **One merge unit.** Demand model (`N = P * T_threads`, at most one outstanding batch request per partition generation, empty polls neither resolve a request nor reach intake) *and* removal of `KafkaRecordOwnershipBudget`. See the note above. | `D15`; `R4`, `R5`, `R7`, `R18` |
| `B9` | Cancellation, generation cleanup, protocol violation. Graceful and force cancellation scoped to `PartitionGenerationId` with a 5-second default grace; `onPartitionsRevoked` returns as soon as intake *accepts* force cancellation; typed cleanup results that never authorize commit; successors paused until `GenerationCleanupFinished`; `ProtocolViolationTerminator` with a 60-second drain limit, an exit code distinct from 80, and a poison pill on restart. `R16`/`R17` get solid fast deterministic coverage **here** — the load test is affirmation, never the loop. | `R15`, `R16`, `R17` |
| `B10` | Process, configuration, supervision. Fatal ladder; bounded shutdown with one named limit; fatal path skips the intake fence; no unbounded doubling loop; no join on a possibly-dead group. Full CLI surface including every deprecated parse-and-warn alias. Startup rejects `P < 1`, `T_threads < 1`, `W <= 0`, `E <= 0`, `S < 0`. | `D13`, `D14`; `R1`, `R19` |
| `B10.5` | **Full correctness review at production-complete.** The production code is implementation-complete and no test triage has happened yet. One deep review of the whole implementation against the design — the only unbounded-scope review in the process (`../AGENTS.md` §3.2) — covering owner boundaries, the commit-authority path end to end, every `R1`–`R19` obligation, and every `D1`–`D18` behavior confirmed absent. Findings triaged and brought to the owner before `B11` burns time debugging a rig against code with a known structural defect. | — |
| `B11` | The fuse: one proxy, two topics, one replayer, a few hundred requests/second, tens of seconds, docker compose, reusing `TrafficCapture/trafficLoadTest` scenarios. Assert the conservation ledger and a source-versus-target doc count. Run exact per-record comparison *and* the counter check and confirm they agree, which is what earns the right to trust counters at 200 MB/s. Read the first run as an observability test first. | — |
| `B12` | Test triage. **Only now**, once the production-code decisions have landed, sort the failing historical tests into matters-now, deferred, and never-matters. Doing this earlier biases every decision toward preserving the old encasement, because a test that asserts the old model makes removing the old model look like a regression. | — |
| `B13` | Ship gate — the existing k6 rig at scale: 10+ proxies, 20 topics, 10+ replayers, >100K requests/second, ~200 MB/s, ten minutes, verified by source-versus-target doc count with all metrics reconciling. Deliberately not specified further; settled with the owner when `B11` passes. | — |

`B12` has no counterpart in Plan A, because Plan A has no inherited test suite. It is the last major
difference between the two plans and the one the owner asked for explicitly: production code first,
file by file, then the question of which failing tests matter.

## 6. Per-milestone exit criteria

Identical to Plan A §5, and identical because it is `../AGENTS.md` §4 restated:

1. Compiles and is wired to its neighbors, or to named scaffolding with a registered removal milestone.
2. **Logging and metrics exist**, including conservation counters for anything that moves records.
3. Every red-line decision escalated; reversible ones recorded with their defaults.
4. Status table updated.
5. One bounded review, findings triaged and reported.
6. **Plan-B-specific:** no subsystem has callers of both the old and the new model. Reviewer-checkable
   by `grep`, per §2's corollary.
7. **Its cited required-test sections have been read** — `kafkaLLD §17`, `connLLD §19`,
   `procCommit §13`, `captureAndReplayArchitecture §15` as applicable — and each case is covered, or
   listed in the status table as deferred or won't-fix with a reason. A finite list the design already
   wrote, not a coverage metric.

**No coverage gate.** No whole-corpus re-read — only the sections a milestone cites. No prose checkpoint
narrative.

## 7. Status tracking

`docs/replayerRebuildStatus.md`, shared with Plan A, one grep-able row per obligation:

```
| ID | Obligation | Milestone | Test | State | Notes |
```

States: `proved`, `open`, `deferred(<milestone>)`, `blocked(<reason>)`, `wontfix(<reason>)`. Rows for
`R1`–`R19`, `D1`–`D18`, every named scaffold with its removal milestone, every deferred test, and every
reversible decision taken on a default. Replaces the retired 936-line prose execution log.

Scaffolding rows matter more here than in Plan A. Plan B permits scaffolding; the register is the only
thing that keeps permission from becoming accumulation.

## 8. Proxy workstream

`PA1`–`PA3` and previous-plan §6.6 are out of scope for this document and handled in a separate
conversation. The proxy is not modified. `B1` consumes the current proxy's output unchanged, so a
protocol mismatch surfaces at `B1` as a decode failure rather than at final acceptance.

## 9. Open decisions

Escalated per `../AGENTS.md` §2.

| Item | Decision needed | Reversible? | Recommendation |
|---|---|---|---|
| Entering from a partial Plan A | Drain and delete the new module, or freeze it in place? | No — `settings.gradle` | Drain the fixtures, identity records, and any finished components; then delete. A frozen module is the `v2` debt both plans avoid |
| `ReplayTransaction` removal timing | All six callers at `B4`, or earlier as they are touched? | No | All at `B4`. Removing callers one at a time is the churn pattern that stalled the first attempt |
| `ReplayProgressController` (416, 5 callers) | Delete, or is something real hiding in it? | No | Read it in `B0` before classifying. Least confident `Delete` in §4.3 after `BehavioralPolicy` |
| `BehavioralPolicy` (3 callers) | Delete or refactor | No | Read in `B0`. Classified `Delete` with low confidence |
| `B7`+`B8` as one merge unit | Accept a large unreviewable-by-halves unit, or find a safe split? | Yes | Accept it. A split here is a correctness risk, and `../AGENTS.md` §6 forbids slicing for reviewability |
| `--lookaheadTimeSeconds` | Parse-and-warn, or honor it in the demand model? | No — deployed contract | Parse-and-warn with no behavioral effect. It is load-bearing for deployed Kubernetes, so it cannot become an unrecognized key |
| Ship gate in the definition of done | Does `B13` become a required acceptance condition alongside `R1`–`R19`? | No | Deferred by the owner 2026-09-22; revisit at `B11` |

## 10. Why this is the fallback

Honest accounting, since the point of writing this down is to make the choice reversible on evidence
rather than on mood.

**Plan B is better at:** keeping a working application, which means every milestone can be checked
against reality; preserving ~5,700 lines of correct Kafka and Netty mechanics (§4.2) that Plan A must
re-derive; and needing no swing, so there is no single high-risk cutover.

**Plan B is worse at:** the failure mode that already happened once. The constraint that every step leave
the old implementation compiling is what made adding cheaper than replacing, and that constraint is
structural to this plan. §2's rule change is a real mitigation, not a guarantee.

**Signals that Plan B is failing the same way.** As in Plan A §10, these are instrumentation to **report**
in the milestone summary, not triggers an agent acts on. Changing plans, relaxing a rule, or abandoning the
approach is the owner's decision and his alone.

1. A subsystem has production callers of both the old and the new model at a milestone boundary — §6
   criterion 6 failing.
2. A responsibility the milestone was supposed to move out of a §4.1 file is still there at the boundary.
   Checkable against the design's owner assignment rather than sensed — and checked that way rather than
   by size, since a file that stays long but sheds the responsibilities the design places elsewhere has
   succeeded.
3. A scaffolding row's removal milestone slips twice.
4. A `Rewrite` flips to `Refactor` for reasons of inconvenience rather than discovered value.

Tripwire 2 is the one to instrument, and instrument it by **responsibility placement rather than size** —
at each boundary, name the file holding each responsibility the milestone was supposed to move and compare
that against the design's owner assignment. A coordinator that stayed large but shed the responsibilities
the design puts elsewhere is fine; one that kept them is the failure, whatever its length.
