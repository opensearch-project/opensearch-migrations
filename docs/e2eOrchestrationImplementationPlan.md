# E2E Orchestration Test Framework — Implementation Plan

## Goal

Implement the orchestration test framework incrementally, starting with one simple matrix-driven safe-change case that produces:

- a visible expanded test case
- a visible structured report
- a visible failure mode
- deterministic teardown

Only after that slice is stable should the implementation expand to gated and impossible-change flows.

## Guiding Constraints

- Start narrow
- Prefer visible reporting over clever abstraction
- Keep mutations manually authored
- Run one outer test workflow at a time
- Run one inner migration workflow at a time
- Require teardown for success
- Keep test authoring local to specs, fixtures, mutators, and reports
- Avoid work in this branch that duplicates the lifecycle/control-plane changes already in flight elsewhere

## Branch Strategy

The current branch should keep moving, but it should avoid the lifecycle orchestration area that is still under active development in the other PR.

That means:

- keep building the planning layer, authoring layer, reporting, tests, docs, and safe scenarios
- keep improving the live runner only where it uses stable user-facing commands and does not depend on new CRD lifecycle semantics
- do not try to re-implement reset, resubmit, delete, or self-teardown control flow in `fullMigration.ts` or related workflow templates before the other PR lands

The goal is to make forward progress without setting up avoidable merge conflicts.

## What Is Safe To Build Now

Safe work in this branch:

- spec format and spec loader hardening
- fixture registry and fixture naming conventions
- approved mutator registry and matrix expansion rules
- checksum report and derived subgraph coverage
- assert/report schemas and human-readable rendering
- more safe-case scenarios and snapshots
- better diagnostics and UX in the live runner
- tests for spec loading, fixture resolution, matrix expansion, and reporting
- docs and handoff material

Work to defer until the other PR lands:

- CRD lifecycle ownership changes in `fullMigration.ts`
- create/getUid/delete/self-teardown orchestration in workflow templates
- gate/approval control flow changes that depend on new lifecycle primitives
- delete-and-retry mechanics for impossible flows
- any attempt to fully converge the outer workflow with a lifecycle model that is still moving

## Phase 0: Package And Contracts

Create a new package:

```text
orchestrationSpecs/packages/e2e-orchestration-tests/
```

Initial files:

```text
src/
  types.ts
  scenarioCompiler.ts
  approvedMutators.ts
  checksumReporter.ts
  derivedSubgraph.ts
  stateStore.ts
  reportSchema.ts
package.json
```

Define stable types first:

- `ApprovedMutator`
- `ScenarioSpec`
- `MatrixSelector`
- `ExpandedTestCase`
- `ChecksumReport`
- `DerivedSubgraph`
- `ScenarioObservation`
- `ScenarioReport`

Exit criteria:

- another agent can import these types and build against them without inventing interfaces ad hoc

## Phase 1: Checksum Report API

Implement a reusable way to inspect checksums from a config without running the full outer test harness.

Target API:

```typescript
type ChecksumReport = {
  components: Record<string, {
    kind: string;
    resourceName: string;
    configChecksum: string;
    downstreamChecksums?: Record<string, string>;
    dependsOn: string[];
  }>;
};

function buildChecksumReport(rawConfig: unknown): Promise<ChecksumReport>;
```

Exit criteria:

- given one baseline config, the package can emit a machine-readable checksum report

## Phase 2: Derived Subgraph API

Implement a helper that takes:

- a `ChecksumReport`
- a `focus` component name

and returns:

- the focus component
- immediate dependents
- transitive dependents
- upstream prerequisites
- independent components

Target API:

```typescript
type DerivedSubgraph = {
  focus: string;
  immediateDependents: string[];
  transitiveDependents: string[];
  upstreamPrerequisites: string[];
  independent: string[];
};

function deriveSubgraph(report: ChecksumReport, focus: string): DerivedSubgraph;
```

Exit criteria:

- for `capture-proxy`, the helper can identify one downstream chain, one upstream prerequisite, and one unrelated branch

## Phase 3: Approved Mutator Registry

Implement `approvedMutators.ts` with only a small reviewed set.

Minimum first-cut set:

- one safe `focus-change` mutator on `capture-proxy`
- one safe `immediate-dependent-change` mutator on replay or snapshot
- optionally one safe `transitive-dependent-change` mutator on snapshot migration

Exit criteria:

- mutators can be looked up deterministically by pattern and change class
- mis-tagged mutators are rejected by expansion-time validation

## Phase 4: Minimal Matrix Expander

Implement only one matrix mode at first:

- `focus`
- `changeClass: safe`
- patterns:
  - `focus-change`
  - `immediate-dependent-change`

Exit criteria:

- a matrix spec expands into concrete named cases without any Argo execution yet
- `requireFullCoverage` is enforced

## Phase 5: Report Schema And Failure Rendering

Implement `reportSchema.ts` and a stable JSON report shape.

The report must include:

- top-level scenario status
- generated case count
- pass/fail count
- per-case expected behavior and observed behavior
- coverage for selected, expanded, and uncovered cases

Exit criteria:

- one passing report and one failing report are easy to read and compare

## Phase 6: ConfigMap State Store

Implement `stateStore.ts` to read and write observation ConfigMaps.

Responsibilities:

- write per-run observations
- read prior-run observations
- write expected checksum reports for a run
- scope names by scenario and run identity

Exit criteria:

- the framework can persist and reload prior observations between steps

## Phase 7: Assert Container MVP

Implement a TypeScript assert container.

Assertions for first cut:

- current component checksum equals expected checksum
- current component phase equals expected terminal phase
- rerun vs unchanged behavior for:
  - focus
  - one immediate dependent
  - one unrelated branch

Exit criteria:

- given synthetic inputs, the container emits a structured pass/fail result

## Phase 8: Runtime MVP

Build the smallest useful runtime slice.

The logical run sequence is:

1. baseline configure
2. baseline wait
3. baseline assert
4. no-op resubmit
5. no-op wait
6. no-op assert
7. one generated safe matrix case
8. case wait
9. case assert
10. teardown

This phase may use both runtime paths, but the live runner is the authoritative path for branch-local progress.

Exit criteria:

- one simple safe matrix case runs end to end
- a report is produced

## Phase 9: Visible Failure Path

Add one intentionally wrong expectation to a dev-only scenario so a developer can inspect:

- what the failure looks like
- what the scenario report looks like
- what data is available for debugging

Exit criteria:

- a developer can run one known-failing case and inspect the report shape

## Phase 10: Branch-Safe Work Ahead

After the first visible slice, keep moving only in branch-safe areas until the lifecycle PR lands.

Recommended next work in this branch:

- finalize the JSON spec shape and quickstart usage
- add tests for `specLoader`, `fixtureRegistry`, `matrixExpander`, and `assertLogic`
- improve report formatting and failure diagnostics
- improve live-runner observability and operator UX
- add more safe-case specs and reviewed mutators
- add coverage accounting and better author feedback for missing mutators
- keep docs aligned with the package as it exists today

Explicitly avoid in this phase:

- changes to `fullMigration.ts` that introduce new CRD lifecycle ownership
- delete/reset/self-teardown patterns that overlap the other PR
- outer-workflow gate control changes that depend on still-moving lifecycle behavior
- impossible/delete runtime semantics

Exit criteria:

- the branch can keep delivering useful planning, reporting, and safe-case improvements without depending on unresolved lifecycle merges

## Phase 11: Pre-Merge Groundwork For Gated And Impossible Cases

Before the lifecycle PR lands, it is still safe to prepare the planning and authoring surface for later gated and impossible flows.

Good candidates for this phase:

- add more named fixtures for setup, cleanup, and approval-time validation
- add fixture registry coverage and tests for those new fixture names
- add reviewed `gated` and `impossible` mutators without wiring new runtime control flow
- add spec examples for gated and impossible scenarios
- extend the report schema so it can later represent before-action and after-action observations
- add pure planning tests for gated and impossible case expansion and selection
- improve documentation and quickstart examples for the broader fixture vocabulary

This phase should still avoid:

- new runtime approval orchestration
- delete or retry control flow
- workflow-template lifecycle changes
- outer-workflow behavior that depends on still-moving lifecycle primitives

Exit criteria:

- the branch has the authoring and reporting surface ready for gated and impossible cases
- fixture coverage is broader and better tested
- no runtime lifecycle behavior was duplicated from the other PR

## Phase 12: Wait For Lifecycle PR To Land

Do not force a merge of the lifecycle PR into this branch while that work is still churning.

Instead:

- let the current branch ship its safe-slice work first
- wait until the lifecycle PR lands or stabilizes enough to merge cleanly
- only then move the test framework into the lifecycle/control-plane area

Exit criteria:

- lifecycle primitives are stable enough that the framework can adopt them once instead of twice

## Phase 13: Post-Merge Reconciliation

Once the lifecycle PR lands, reconcile this package with the merged workflow lifecycle.

Required work:

- re-run the live runner against the merged workflow CLI
- align the outer-workflow control flow with the merged reset/resubmit/approval/delete semantics
- replace any pre-merge assumptions about lifecycle, teardown, or gate progression
- update docs and examples so they describe the merged control surface

Exit criteria:

- the package still runs the safe matrix slice correctly after the merge
- the plan and code agree on the new lifecycle primitives

## Phase 14: Runtime Convergence Hardening

After the merge, harden the runtime paths before expanding breadth.

Required hardening:

1. make the outer workflow match the live runner's semantics
2. require validation before approval in gated paths
3. remove silent-success or auto-approve-only shortcuts from the final gated implementation
4. keep the TypeScript assert logic and shell or outer-workflow assertions equivalent
5. ensure teardown and observation collection cover all resources touched across all runs, not just the final mutated set

Exit criteria:

- the live runner and the outer-workflow path enforce the same approval semantics
- failed validations stop the scenario before approval
- teardown leaves the scenario context clean after success or failure

## Phase 15: Expand Safe Matrix Coverage

Only after the merged runtime hardening is done:

- add `transitive-dependent-change`
- add more reviewed safe mutators
- add more safe-case specs
- add direct tests for spec loading, fixture resolution, gate matching, and outer-workflow rendering

Exit criteria:

- the framework can exercise a broader safe-change matrix with understandable reports
- the expanded authoring and report surface has direct automated coverage

## Phase 16: Add Gated Flow

Implement one gated case only:

- `focus-gated-change`

Required additions:

- wait-for-block step
- assert-before-action
- validation-before-approval step
- approve action
- assert-after-action

Exit criteria:

- blocked dependents are visible before approval
- progression after approval is visible in the report
- the same gate validations run in both the live runner and the outer workflow

## Phase 17: Add Impossible/Delete Flow

Implement one impossible case only:

- `immediate-dependent-impossible-change`

Required additions:

- wait-for-block step
- assert-before-action
- delete action
- retry or resubmit path
- assert-after-action

Important semantic:

- impossible means impossible to update in place
- not impossible to proceed after deleting the old resource and retrying

Exit criteria:

- blocked dependent path is visible before delete
- resumed path is visible after delete and retry

## Recommended Current Slice

If you are handing this to another agent today, the concrete milestone should stop after Phase 10.

That milestone should deliver:

- package scaffold and types
- checksum report API
- derived subgraph helper
- approved mutator registry
- simple safe matrix expansion
- ConfigMap state handoff
- TypeScript assert container
- live-runner-backed safe runtime slice
- one passing report
- one intentionally failing report
- teardown
- direct tests for the authoring and planning surface

It should not yet attempt:

- lifecycle-heavy workflow template changes
- gated approval convergence in the outer workflow
- impossible/delete recovery
- broad scenario packs
- post-merge runtime convergence work

## Suggested Branch-Safe Next Tasks

If you want to keep moving on this branch right away, prefer this order:

1. add tests for `specLoader`, `fixtureRegistry`, `generate-outer`, and more matrix/report cases
2. improve report output and operator diagnostics in `e2e-run.ts`
3. add one or two more safe-case specs and reviewed mutators
4. keep the docs and quickstart aligned with the package
5. leave `fullMigration.ts` and lifecycle ownership changes alone until the other PR lands

## Agent Handoff Checklist

### Ground Rules

- Do not try to implement the full design in one pass.
- Stop after the first visible end-to-end safe slice unless explicitly asked to continue.
- Prefer small, inspectable artifacts over broad abstraction.
- Keep all mutations manually authored.
- Keep this branch out of the workflow lifecycle control-plane area until the other PR lands.

### Safe Integration Points

Preferred files and modules for branch-local progress:

- `packages/e2e-orchestration-tests/src/types.ts`
- `packages/e2e-orchestration-tests/src/specLoader.ts`
- `packages/e2e-orchestration-tests/src/fixtureRegistry.ts`
- `packages/e2e-orchestration-tests/src/fixtures.ts`
- `packages/e2e-orchestration-tests/src/checksumReporter.ts`
- `packages/e2e-orchestration-tests/src/derivedSubgraph.ts`
- `packages/e2e-orchestration-tests/src/approvedMutators.ts`
- `packages/e2e-orchestration-tests/src/matrixExpander.ts`
- `packages/e2e-orchestration-tests/src/assertLogic.ts`
- `packages/e2e-orchestration-tests/src/reportSchema.ts`
- `packages/e2e-orchestration-tests/src/e2e-run.ts`
- `packages/e2e-orchestration-tests/specs/`
- `docs/e2eOrchestrationTestDesign.md`

Avoid for now unless explicitly asked:

- `packages/migration-workflow-templates/src/workflowTemplates/fullMigration.ts`
- workflow-template lifecycle ownership changes
- delete/reset/self-teardown orchestration

### Done Means For The Current Branch

The current branch is in good shape when:

- a developer can define a new safe scenario with a small spec and fixture surface
- the framework expands it into a concrete case deterministically
- the live runner can execute it and produce a useful report
- failures are understandable
- teardown is reliable
- no work was duplicated from the still-moving lifecycle PR
