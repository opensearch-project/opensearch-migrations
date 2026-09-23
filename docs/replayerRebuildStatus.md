# Replayer Rebuild Status

Grep-able register. **Replaces** `replayerRebuildExecutionLog.md`, which is retired. One row per
tracked item; no prose. Update as part of every milestone exit (`AGENTS.md` §4).

**States:** `proved` · `open` · `deferred(<milestone>)` · `blocked(<reason>)` · `wontfix(<reason>)`

**On IDs.** Only `R1`–`R19` and `D1`–`D18` are labels. Both are plan-side only — neither appears in the
designs, the code, commit messages, or CI — but both are worth having because several documents need to
refer to the same item: `R`*n* names an otherwise-unnumbered design bullet, and `D`*n* names a row of the
defect table that both plans point at. **The other tables below deliberately have no ID column.** Nothing
references those rows, so a serial number would be ceremony that has to be maintained. If something ever
does need to cite one, give it a descriptive key rather than a number.

Milestone IDs are Plan A's `G`-series. Under Plan B use the `B`↔`G` mapping in Plan B §5.

**Obligation text is `docs/captureAndReplay/replayerLowLevelDesign.md` §9, lines 253-278** — nineteen
unnumbered bullets, in order, so R*n* is the *n*th bullet. The R-numbering exists only here and in the
plans; it is not in the designs and must not be added to them. `replayerRebuildPlan.md` §6.5 restates
the same list in a table and says at its head that it mirrors §9; where they differ, §9 wins.

Defect inventory with verified `file:line` references: `replayerRebuildPlan.md` §2 (lines 138-166).

Short names in the citation columns, all under `docs/captureAndReplay/`: `replayerLLD` =
`replayerLowLevelDesign.md`, `kafkaLLD` = `replayerKafkaSourceAndIntakeLowLevelDesign.md`, `connLLD` =
`replayerConnectionAndRequestLowLevelDesign.md`, `procCommit` =
`replayerProcessingAndCommitArchitecture.md`, `async` = `asyncMessagePassingProgrammingGuide.md`.

---

## Operational state

Verified 2026-09-22. This changes; correct it rather than trusting it blind.

| Item | State |
|---|---|
| Branch | `stableAndScalableLiveReplay` |
| Remote | `origin` = `github.com/gregschohn/opensearch-migrations` (a fork, **not** `opensearch-project`) |
| Local vs origin | Local is **2 commits ahead**: `822abde4e` (S6b) and the plan reset are both unpushed. A prior handoff note claiming origin is at `822abde4e` is stale — `origin/stableAndScalableLiveReplay` is at `2c4f305f3` |
| Pull request | #3394, open and blocked. Failing categories: inherited DCO, Spotless, Gradle shards, macOS build, Sonar, several E2E jobs |
| DCO debt | **24 of the 48 commits** since `origin/integrating3231` lack `Signed-off-by`. A prior note named only seven (`5150f20ed`, `d7aa795405`, `34d2861545`, `68cf954449`, `997a6c44f0`, `139853523e`, `6fb2cb040c`) — all seven confirmed missing it, but the real debt is larger. Any rewrite must preserve trees, topology, messages, authorship, and original dates, behind a backup ref, pushed with `--force-with-lease` |
| Test compilation | **Broken** at four stale fixture API errors: `ActorRequestTestUtils` still references `AsyncPermitPool`, and `ReplayEngineFactory` constructs `RequestSenderOrchestrator` without the permit-provider argument. More stale callers are expected behind them |
| Production compile | Passes — see the verified invocation in `AGENTS.md` §5 |
| S6b review | Committed at the owner's direction **without** an agent review. Hotspots if reviewed later: abort racing initial or retry permit delivery, permit cleanup on rejected event-loop submission, permit-close failure and suppressed-failure preservation, off-event-loop mutation of `TargetConnectionOwner`, stale `activePermit`, duplicate acquire or release, whether queued or preparing requests can acquire, and whether a permit is held while retry policy waits on source-response input |
| `stash@{0}` | `09df7b9a4` — S6 pre-commit backup, redundant. Do not apply |
| `stash@{1}` | `f676a7bc7` — ~3,000 lines of an abandoned test/harness direction. Do not merge wholesale; inspect only if explicitly asked |
| Other checkout | `/Users/schohn/dev/replayerCommitHardening` holds an earlier copy of the docs. This repo is authoritative |

## Design obligations R1–R19

The `Defined in` column is the traceability reference: the design sections that define the behavior. The
`Required tests` column is the design's own test list for it — cite those rather than inventing exit
criteria, and on conflict the design's list wins.

| ID | Obligation | Milestone | Defined in | Required tests | State | Notes |
|---|---|---|---|---|---|---|
| R1 | One named owner per mutable value | G9 | procCommit §3.1; replayerLLD §2; async §2 | connLLD §19.6; procCommit §13.1 | open | |
| R2 | At most one turn + one processing completion; normal has both | G5 | procCommit §3.2; connLLD §10, §13 | connLLD §19.2 | open | |
| R3 | At most one outstanding batch request per generation | G2 | kafkaLLD §4.2, §13; replayerLLD §2 | kafkaLLD §17.4 | open | |
| R4 | Delivered batch matches one request, applied before next | G7 | kafkaLLD §5.3, §13 | kafkaLLD §17.4 | open | |
| R5 | Demand open while retry-ready supply below N | G7 | procCommit §8.1; kafkaLLD §13 | kafkaLLD §17.4 | open | |
| R6 | Fast responses satisfy supply before B+W | G6 | procCommit §8.2; kafkaLLD §11 | kafkaLLD §17.3, §17.4 | open | |
| R7 | Finished/cancelled cannot re-enter supply | G6, G7 | kafkaLLD §12, §13; procCommit §8.1 | kafkaLLD §17.4 | open | |
| R8 | Target-write start stays local cancellation state | G5 | connLLD §8; procCommit §3.2 | connLLD §19.2 | open | |
| R9 | Queued input wakes long poll without interrupting protected work | G2 | kafkaLLD §5.4; replayerLLD §2 | kafkaLLD §17.4 | open | |
| R10 | No processing completion before tuple durability | G5 | connLLD §12, §13; replayerLLD §5 | connLLD §19.2, §19.3 | open | |
| R11 | No record completion with unfinished associations | G3, G4 | kafkaLLD §8 | kafkaLLD §17.1 | open | |
| R12 | Shared record waits for all its requests | G3 | kafkaLLD §8.3; procCommit §6.2 | kafkaLLD §17.1 | open | |
| R13 | Source-response records associated through tuple durability | G3 | kafkaLLD §8.2, §9.2 | kafkaLLD §17.1 | open | |
| R14 | Expired and fresh lifetimes cannot cross-route | G6 | replayerLLD §1; kafkaLLD §10.3; connLLD §3.4, §16.2 | kafkaLLD §17.2; connLLD §19.4 | open | |
| R15 | Cancellation cleanup cannot produce a commit request | G8 | replayerLLD §6; kafkaLLD §15.3; connLLD §17.2 | connLLD §19.5; kafkaLLD §17.5 | open | |
| R16 | Successor generation waits for prior cleanup | G8 | kafkaLLD §15.3; procCommit §9.3 | kafkaLLD §17.5 | open | Owner directive: solid fast deterministic coverage here, not via load test |
| R17 | Unrelated partitions continue | G8 | kafkaLLD §5.1; procCommit §8 | kafkaLLD §17.4, §17.5 | open | Same directive as R16 |
| R18 | No hard cap blocks retry or heartbeat evidence | G7 | kafkaLLD §5.1; procCommit §8.3 | kafkaLLD §17.4:986 | open | |
| R19 | Every unexpected owner failure reaches the supervisor | G9 | replayerLLD §8, §4; async §2 | connLLD §19.6; procCommit §13.5 | open | |

## Defects D1–D18 — behaviors the implementation must not exhibit

| ID | Defect | Milestone | State | Notes |
|---|---|---|---|---|
| D1 | Replayer cannot read its own capture topic | G1 | open | Reassigned from G9 during the 2026-09-22 citation pass |
| D2 | A request can own zero Kafka records | G3 | open | |
| D3 | Ordinary target failures select Retain and halt | G4 | open | |
| D4 | Mixed records structurally unrepresentable | G3 | open | |
| D5 | Blocking commitSync in onPartitionsRevoked with no cancellation delivered | G2 | open | "with no cancellation delivered" is load-bearing. The design has commit handling inside the callback — read procCommit §9.2 and kafkaLLD §15.1 before concluding otherwise |
| D6 | Target-concurrency bound is a lie | G5 | open | |
| D7 | Required cross-owner submission silently dropped | G5 | open | |
| D8 | Truncated source response labelled COMPLETE | G3 | open | |
| D9 | No-response retry capped at 4 | G6 | open | |
| D10 | NoTargetResponseObtained is an exception; any exception retries | G5 | open | |
| D11 | ReplayEngine.admitWork blocks on own-thread stage | G3 | open | Reassigned from G9 during the citation pass |
| D12 | Poll failures become empty successes | G2 | open | |
| D13 | Fatal path halts instead of running the supervisor ladder | G9 | open | |
| D14 | Termination waits for orderly recovery in three places | G9 | open | |
| D15 | Hard ownership caps deadlock by construction | G7 | open | Reassigned from G9 during the citation pass |
| D16 | onPartitionsAssigned does not pause the resulting assignment | G2 | open | |
| D17 | Connection owner forgets a request at turn end | G5 | open | |
| D18 | Work admitted under a fabricated partition identity | G3 | open | |

Non-blocking, fold into the relevant milestone (`replayerRebuildPlan.md:163-166`):

| Item | Milestone | State |
|---|---|---|
| Non-atomic refcount read-modify-write, `tracing/ChannelContextManager.java:127` | G5 | open |
| `ISourceTrafficChannelKey.getSourceGeneration()` defaults to 0, letting two lifetimes collide | G3 | open |

## Named scaffolding — every row needs a removal milestone

| Scaffold | Introduced | Removal | State |
|---|---|---|---|
| Eight `transformation/` modules' build.gradle redirected at `:TrafficCapture:trafficReplayerLegacy` | G0 | G11 | open |

## Reversible decisions taken on a recommended default

Logged per `AGENTS.md` §2 so they can be vetoed later.

| Decision | Default taken | Milestone | Vetoable until |
|---|---|---|---|
| Legacy module renamed, new module takes the real name immediately | Adopted — owner's proposal 2026-09-22 | G0 | G0 |
| `traffic_replayer` image broken during construction rather than kept alive from the legacy module | Accept | G0 | G10 |
| Publication suppressed on `trafficReplayerLegacy` via `excludedProjectPaths` | Accept | G0 | G11 |
| `RecordDispositionLedger` left frozen in the legacy module rather than deleted now | Leave frozen | G0 | G11 |

## Deferred and unresolved

| Item | State | Notes |
|---|---|---|
| **Branch and PR strategy for the long red-CI stretch** | open — **blocks G0** | Plan A leaves the assembled app broken from G0 until G11 by design. Decide whether #3394 becomes a draft and CI is left red until the swing, or a fresh PR carries the greenfield work. Recommendation: draft #3394 and defer CI work — the module layout changes at G11, so most CI fixes done before then are rework |
| **DCO rewrite timing** | open — **blocks any push** | 24 of 48 commits lack sign-off and the PR cannot merge without it. Do it now as one rebase behind a backup ref, or fold it into the end-of-project history cleanup. Recommendation: now — mechanical, independent of plan choice, and it permanently clears one red check |
| **Pull-over inventory from S0–S6b** | open — **first G0 escalation** | Every file carried over is a red-line-3 decision. Strongest candidates: the S0 deterministic fixtures, the S6a/S6b permit semantics (which track `connLLD §7` closely), `RecordWorkTracker`, `ObservedRecordCommitQueue`, `OwnerTransitionRunner`, `ProcessSupervisor`, the dump-mode classes, the tracing/OTEL contexts, and the Netty data-handler pipeline. Bring one table, not forty interruptions |
| Inspect `stash@{1}` for fixture material | open | ~3,000 lines of abandoned harness work may contain usable `TestEventLoop`/`FakeClock`. Inspect those files only; do not merge wholesale |
| Fuse and ship-gate acceptance detail | `deferred(G10)` | Owner deferred 2026-09-22; revisit at the G9 boundary |
| Does G12 become a required acceptance condition alongside R1–R19? | `deferred(G10)` | |
| Doc-count comparison catches loss but not ordering | open | Banked at G12; a stateful sequence replayed out of order shows as a comparison mismatch, not a count delta |
| `testFixtures` is published to Maven, so its API is a public contract | open | Raises the stakes on the G11 decision; external consumers are not visible from this repo |
| Git history cleanup of the 23 `S0`–`S6b` commits | `deferred(post-G12)` | Recommendation: one rebase at the end against a final diff, not speculatively now |
| Delete `replayerRebuildExecutionLog.md` | `deferred(final cleanup)` | 945 tracked lines, superseded by this file. Owner's decision: leave it until the final sweep |
