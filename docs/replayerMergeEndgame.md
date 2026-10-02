# Capture/replay hardening merge endgame

Updated 2026-10-02.

This is a non-authoritative working checklist. The authoritative designs, `AGENTS.md`, Plan A, and the
live status register retain their defined roles.

The test posture and Plan A milestone exits can now be treated as settled. The remaining work is workspace
consolidation, limbo removal, mainline integration, release accounting, and final cleanup—not another broad
test audit or another G10/G11 exit cycle.

## Current state

| Area | State | Evidence / next action |
|---|---|---|
| Pushed PR branch | `55eb82567` | Behavioral evidence, imported-target cleanup, and limbo removal are published; current CI state not rechecked here |
| Local candidate | implementation baseline `8aa7a526f`, with `dcf88fc96` merged from mainline | Changelog, contract audit, README/Compose corrections, and status reconciliation are complete in the working tree; merge commit and endgame edits are not yet pushed |
| Behavioral audit | folded into durable changelog | The temporary audit informed Steps 4–5; its findings are now self-contained in `docs/captureReplayHardeningChangelog.md` and no audit IDs or file links remain there |
| Test posture | settled | Do not start another broad test-surface audit |
| Plan A milestones | complete by owner direction | Do not run another G10/G11 exit cycle; remaining cleanup is owned by this endgame checklist |
| Publication | owner-gated | Do not push additional work to PR #3394 until Greg explicitly directs it |

## Completed cleanup

| Item | State | Evidence |
|---|---|---|
| Remove unreachable replay helpers and unused overloads | complete | Consolidated in `967c5be5c`; no live references remain |
| Retain `SolrSnapshotDataProvider` | complete | The file and Solr reader path remain unchanged |
| Keep `AsyncLink` expository and remove production adoption | complete | Design wording and its dated register row are isolated in `350963823`; production cleanup is in `967c5be5c` |
| Remove synthetic Kafka and heartbeat samples from `test_cdc_base.py` | complete | `967c5be5c`; parser coverage and deployed heartbeat evidence remain |
| Remove retired replayer configuration aliases | complete | Only `maxConcurrentRequests` remains; `numClientThreads=0` delegates to Netty auto-sizing |
| Remove TypeScript tests duplicated by snapshots | complete | Focused validation tests remain |
| Retire obsolete markdown and Plan B references | complete | Consolidated in `350963823` |
| Extract interrupted Full ES recovery from PR #3394 | complete | The implementation moved to PR #3419 and is absent from compressed PR #3394 history |
| Verify the accumulated cleanup batch | complete | Focused Java, Python, schema, config-processor, workflow-template, type-check, and diff checks passed |
| Restore branch-owned behavioral evidence | complete locally | Commit `94be4a504` restores the selected behavioral tests identified by the audit |
| Consolidate the working state | complete locally | Primary candidate is authoritative; K6 source was reconciled, stale mutations were restored, and obsolete worktree registration was pruned |
| Remove final replay limbo | complete locally | Commit `55eb82567` deletes 142 wholly inert files, strips all remaining source limbo/trace records, restores Spotless, and removes stale Sonar exceptions |
| Integrate mainline | complete locally | `8aa7a526f` merges mainline `dcf88fc96`; the two localized conflicts preserve the branch's cross-connection ordering posture and adopt `searchClusterTestFixtures` |

## Remaining merge sequence

### 1. Consolidate the working state

Completed 2026-10-02. The primary checkout is the authoritative candidate. K6 source matches it; generated
investigation material remains uncommitted. The old G10/G3 edits were reconciled or restored, and the stale
`/private/tmp/cdc-g5-test-7c8` registration was pruned.

### 2. Remove limbo and rebuild scaffolding before mainline integration

Completed locally in `55eb82567`.

- The pre-cleanup verifier passed over 183 scaffolded files: 142 wholly inert files and 41 mixed/trace-only
  files.
- All dead predecessor bodies, source markers, trace records, temporary notes, and the traffic-replayer
  Spotless exclusion are gone.
- `TrafficReplayerTopLevelShutdownTest.normalShutdownUsesTheOrderlyDrainBeforeClosingTargetOwners` is the
  shipping-owner evidence that a source wakeup during teardown still reaches orderly application close.
- Explicit `spotlessJavaCheck` and the complete traffic-replayer unit test task pass.
- The exact pre-cleanup source backup and manifests are retained at
  `/private/tmp/replayer-limbo-cleanup.mUk7nM`.

Removing this scaffolding before integrating mainline is an explicit owner-directed sequencing change
intended to make the merge smaller and easier to reason about.

### 3. Integrate mainline and freeze the candidate

Local integration completed in `8aa7a526f`.

- Mainline `dcf88fc96` is the merge commit's second parent.
- The only content conflicts were `TrafficCapture/trafficReplayer/build.gradle` and
  `OpenSearch37ReplayTest.java`.
- The dependency resolution keeps the branch's reduced live dependency set while adopting mainline's
  `searchClusterTestFixtures` module.
- The test resolution keeps the branch's `CaptureRecord`-based completed replay runs, which preserve exact
  same-connection order without asserting cross-connection FIFO, while adopting the shared
  `SearchClusterContainer` package.
- All 12 Gradle repository/credential tests passed. Helm lint/render passed and retained both pipeline
  verifiers. The label-triggered Jenkins workflow expressions are present.
- Spotless, traffic-replayer unit tests, Kafka factory evidence, and the S3/OpenSearch isolated tests pass.
- The durable changelog records the integrated SHAs, branch behavior, and retained mainline checks without
  depending on temporary audit identifiers.

Publishing the merge and observing its final integrated CI run remain before the candidate is frozen.
- Run focused verification for conflict resolutions and the final integrated CI suite.

### 4. Create the durable changelog

Completed in `docs/captureReplayHardeningChangelog.md` on 2026-10-02.

- The changelog records integrated mainline `dcf88fc9688cd6a8cb257d75873692a4b7839195` and audited
  implementation candidate `8aa7a526f60afb7a589b74610d839004a9509330`.
- Fifteen non-overlapping `UF-xx` rows cover user/operator-visible behavior.
- Five `IC-xx` rows cover internal but published or contractual APIs, ownership, fixtures, and module
  boundaries.
- Breaking, intentionally incompatible, preserved, additive, and stricter-validation behavior is labeled
  explicitly with required action and evidence.

Keep it outside `docs/captureAndReplay/`, which is the authoritative design corpus. Use the behavioral
audit as the primary source, then audit the remaining externally meaningful contract surfaces.
Record the integrated mainline and final candidate SHAs when creating it.

#### User-facing changes

Include:

- Workflow schema keys, defaults, validation, and removed aliases.
- CLI flags, defaults, diagnostics, and exit behavior.
- Kafka topic creation, timestamp policy, partitioning, and BYOC behavior.
- Ordering, retry, expiration, shutdown, and replay behavior.
- Deployment topology, readiness, logs, metrics, and operational behavior.

#### Non-user-facing contractual changes

Include:

- Published APIs, modules, fixtures, protobuf, and serialization formats.
- Lifecycle, ownership, commit, cancellation, and conservation invariants.
- Metrics or logging contracts not directly exposed to ordinary users.
- Image entrypoints, environment variables, and integration interfaces.
- Removed externally consumable test-fixture or utility APIs.

Use two non-overlapping sections. A contract that is user- or operator-visible belongs only in the
user-facing section.

Each row should contain:

| ID | Surface | Mainline behavior | New behavior | Compatibility | Required action | Evidence |
|---|---|---|---|---|---|---|

Use `UF-xx` identifiers for user-facing changes and `IC-xx` identifiers for non-user-facing contractual
changes.

### 5. Run the changelog and contract-completeness audit

Completed in the working tree on 2026-10-02.

- All eight behavioral changes and eight preserved-contract findings from the temporary audit were folded
  into self-contained `UF-xx` and `IC-xx` entries; no temporary audit identifiers remain in the durable
  changelog.
- Schema, CLI, workflow, Kafka protocol, metrics/logging, published API/fixture, deployment, image, runtime
  dependency, RFS/transformation, and load-test contract families have explicit audit dispositions.
- Rebuild, test, generated-output, and CI-only edits are identified as intentionally excluded rather than
  presented as product changes.
- The live register now contains the exact managed live-capture `LogAppendTime` and managed BYOC
  `CreateTime` policy, plus final Plan A/limbo/fixture/order dispositions.
- Mainline repository-routing, EKS NodePool/pipeline-verification, and Jenkins label-expression checks are
  recorded directly without depending on the temporary audit appendix.
- The audit corrected the large-request Compose command to `--maxConcurrentRequests` and removed dead
  packet-timeout dump plumbing and stale README claims.
- The audit also found and fixed a design-conformance defect in tuple flushing: eager flush is now
  reference-counted by generation and ends after the last affected connection owner finishes, instead of
  remaining enabled for all later traffic on that worker.
- The three design-silent internal questions retained in the temporary register are not presented as
  external compatibility promises; this audit makes no new design decision for them.

- Map every behavioral-difference row to a changelog row or explicitly classify it as test-only or an
  internal implementation change.
- Diff schemas, CLI parsers, workflow templates, metrics, protobuf, published fixtures, deployment charts,
  and image interfaces.
- Fold the previously open top-level-directory review into this audit. Inspect root files, `.github`, `RFS`,
  `TrafficCapture`, `testHelperFixtures`, `tools`, `transformation`, and `vars`, in addition to the already
  reviewed `coreUtilities`, `deployment`, `docs`, `migrationConsole`, and `orchestrationSpecs`.
- Add the owner-authorized managed-topic timestamp policy to `docs/replayerRebuildStatus.md`:
  managed live-capture topics default to and require `LogAppendTime`; managed BYOC import topics default to
  and require `CreateTime`.
- Reconcile every other compatibility and design-decision row in the live status register.
- Record breaking, intentionally incompatible, and intentionally preserved behavior explicitly.
- Rerun the observable and contractual surface inventory against the integrated tree.
- Close the mainline-test confirmation appendix.
- Confirm that every behavioral-audit entry and contract-surface change has a final disposition.

The changelog describes every externally meaningful difference—not every internal refactor. The behavioral
audit establishes what behavior changed; the separate contract audit ensures that configuration,
operational, serialization, and integration surfaces are not missed.

### 6. Remove the rebuild plans and execution scaffolding

- Preserve any durable product behavior, compatibility decisions, changelog entries, and evidence before
  deleting their temporary source documents.
- Delete exactly these root and planning files:
  - `AGENTS.md`
  - `CLAUDE.md`
  - `docs/replayerCleanupPunchlist.md` (already replaced by this checklist)
  - `docs/replayerMergeEndgame.md` (this checklist, after every item is complete)
  - `docs/replayerRebuildPlan.md`
  - `docs/replayerRebuildPlanA-inPlace.md`
  - `docs/replayerRebuildStatus.md`
  - `docs/archive/AGENTS-full-through-2026-09-24.md`
  - `docs/archive/replayerRebuildPlan-full.md`
  - `docs/archive/replayerRebuildStatus-through-2026-09-25.md`
  - `docs/captureAndReplay/APPROVED-AT`
- Delete the entire `TrafficCapture/trafficReplayer/tools/` directory:
  - `falsify-g2.sh`
  - `falsify-g3.sh`
  - `minimizeBlameChangesTask.md`
  - `unmark-limbo.awk`
  - `verify-limbo-markers.sh`
- Delete exactly these top-level rewrite-support tools:
  - `tools/JavaStructuralEqualizer.java`
  - `tools/capture-replay-context-tools.md`
  - `tools/compress-history-then-minimize-blame-task-prompt.md`
  - `tools/compress-history-then-minimize-blame.sop.md`
  - `tools/fresh-capture-replay-milestone.sop.md`
  - `tools/fresh-capture-replay-task-prompt.md`
  - `tools/gradle-evidence.sh`
  - `tools/java-structural-equalizer.py`
  - `tools/java-without-limbo.py`
  - `tools/review-prompt-design-conformance.md`
  - `tools/test-gradle-evidence.sh`
  - `tools/test-java-structural-equalizer.sh`
  - `tools/test-java-without-limbo.sh`
  - `tools/test-verify-design-authorization.sh`
  - `tools/verify-commit-scope.sh`
  - `tools/verify-design-authorization.sh`
- Delete exactly these rewrite-support fixture directories:
  - `tools/testdata/gradle-evidence/`
  - `tools/testdata/java-structural-equalizer/`
  - `tools/testdata/java-without-limbo/`
- Retain exactly these authoritative capture/replay product designs:
  - `docs/captureAndReplay/BringYourOwnCapturedTraffic.md`
  - `docs/captureAndReplay/asyncMessagePassingProgrammingGuide.md`
  - `docs/captureAndReplay/captureAndReplayArchitecture.md`
  - `docs/captureAndReplay/managedFleetCaptureRecovery.md`
  - `docs/captureAndReplay/proxyCaptureProtocol.md`
  - `docs/captureAndReplay/replayerConnectionAndRequestLowLevelDesign.md`
  - `docs/captureAndReplay/replayerKafkaSourceAndIntakeLowLevelDesign.md`
  - `docs/captureAndReplay/replayerLowLevelDesign.md`
  - `docs/captureAndReplay/replayerProcessingAndCommitArchitecture.md`
- Retain the durable changelog created by Step 4:
  - `docs/captureReplayHardeningChangelog.md`
- No other file or directory is deleted by Step 6. Other files may be edited only to remove references to
  the deletion manifest above.
- Run repository-wide reference and dead-link checks after the deletion.

### 7. Prepare the final review candidate

- Run the remaining applicable design-authorization, commit-scope, DCO, diff, and clean-tree checks before
  deleting any tool needed to perform them.
- Repair or explicitly authorize the historical `6d928f3d9` history-wave treatment metadata: the current
  design-authorization check reports that pre-existing mixed design/Java commit even though the working
  design corpus has no unrecorded change.
- Perform the final design-conformance review against the shipping tree and authoritative designs.
- Run final CI after all endgame cleanup.
- Link the changelog prominently from the PR description and update the description with acceptance
  evidence, findings, and dispositions.
- Push or force-with-lease only when Greg explicitly directs it.
