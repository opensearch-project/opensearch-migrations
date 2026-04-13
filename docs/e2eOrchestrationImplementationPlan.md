# E2E Orchestration Test Framework — Implementation Plan

## Goal

Implement the orchestration test framework incrementally, starting with one simple matrix-driven safe-change case that produces:

- a visible expanded test case
- a visible structured report
- a visible failure mode
- deterministic teardown

Only after that initial slice is stable should the implementation expand to gated and impossible-change flows.

## Guiding Constraints

- Start narrow
- Prefer visible reporting over clever abstraction
- Use ConfigMaps for state handoff from the start
- Keep mutations manually authored
- Run one outer test workflow at a time
- Run one inner migration workflow at a time
- Require teardown for success
- Keep the live runner and the outer-workflow path semantically aligned

## Planned PR Boundary

The current branch should stop after the first visible runtime slice, cut a PR, and then merge `#2462` before the next round of hardening.

Why this boundary exists:

- the current work proves matrix expansion, reporting, and a live runtime path
- `#2462` changes the workflow lifecycle in exactly the area the next improvements depend on
- approval/reset/resubmit/delete behavior should be aligned to the post-`#2462` CLI and CRD lifecycle, not implemented twice

Working assumption for follow-up work:

- land the current MVP-oriented PR first
- merge or rebase in `opensearch-project/opensearch-migrations#2462`
- only then implement the full gated/impossible/delete hardening

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

Implementation options:

- refactor `MigrationConfigTransformer` to expose checksum computation
- or wrap it with a dedicated inspection path that uses the same logic

First cut requirements:

- support one known baseline config
- include enough information to derive:
  - self checksum
  - dependency edges
  - downstream-facing checksum projections, if used by assertions

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

First cut:

- support the one baseline topology used by the first scenario
- do not optimize for generality yet

Exit criteria:

- for `capture-proxy`, the helper can identify one downstream chain, one upstream prerequisite, and one unrelated branch

## Phase 3: Approved Mutator Registry

Implement `approvedMutators.ts` with only a small reviewed set.

Minimum first-cut set:

- one safe `focus-change` mutator on `capture-proxy`
- one safe `immediate-dependent-change` mutator on `snap1` or replay
- optionally one safe `transitive-dependent-change` mutator on `migration1`

Suggested shape:

```typescript
type ApprovedMutator = {
  id: string;
  path: string;
  changeClass: "safe" | "gated" | "impossible";
  patterns: string[];
  apply: (baseConfig: unknown) => unknown;
  rationale: string;
};
```

Exit criteria:

- mutators can be looked up deterministically by pattern and change class

## Phase 4: Minimal Matrix Expander

Implement only one matrix mode:

- `focus`
- `changeClass: safe`
- patterns:
  - `focus-change`
  - `immediate-dependent-change`

Target behavior:

- take one matrix spec
- derive the focus subgraph
- bind matching reviewed mutators
- emit concrete expanded test cases

Example output:

```text
capture-proxy-chain-behaviors/focus-change/safe/noCapture
capture-proxy-chain-behaviors/immediate-dependent-change/safe/replay.someSafeField
```

Exit criteria:

- a matrix spec expands into concrete named cases without any Argo execution yet

## Phase 5: Report Schema And Failure Rendering

Implement `reportSchema.ts` and a stable JSON report shape.

The report must include:

- top-level scenario status
- generated case count
- pass/fail count
- per-case:
  - test name
  - focus
  - pattern
  - mutator id
  - expected behavior
  - observed behavior
- coverage:
  - selected cases
  - expanded cases
  - uncovered cases

Add one intentional failing scenario/case locally so the report can demonstrate:

- what a mismatch looks like
- how it is surfaced

Exit criteria:

- one passing report and one failing report are easy to read and compare

## Phase 6: ConfigMap State Store

Implement `stateStore.ts` to read/write observation ConfigMaps.

Responsibilities:

- write per-run observations
- read prior-run observations
- write expected checksum reports for a run
- scope names by scenario/run identity

Suggested keys:

- `observation.json`
- `checksum-report.json`

Exit criteria:

- the framework can persist and reload prior observations between steps

## Phase 7: Assert Container MVP

Implement a TypeScript assert container.

Inputs:

- current scenario/run id
- expected behavior
- current checksum report
- prior observation

Assertions for first cut:

- current component checksum equals expected checksum
- current component phase equals expected terminal phase
- rerun vs unchanged behavior for:
  - focus
  - one immediate dependent
  - one unrelated branch

Do not implement blocked-before-action logic yet.

Exit criteria:

- given synthetic inputs, the container emits a structured pass/fail result

## Phase 8: Outer Workflow MVP

Build the smallest useful outer workflow using TS builders.

It should run:

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

Use ConfigMaps for state handoff from the beginning.

Exit criteria:

- one simple matrix case runs end to end in Argo
- a report is produced

## Phase 9: Visible Failure Path

Add one intentionally wrong expectation to a dev-only scenario so you can inspect:

- what the outer workflow failure looks like
- what the scenario report looks like
- what data is available for debugging

This should be easy to toggle on/off and should not live in the default CI scenario set.

Exit criteria:

- a developer can run one known-failing case and inspect the report shape

## Phase 10: Merge And Reconcile With #2462

After the current MVP-oriented PR is cut, merge or rebase in `#2462` before continuing.

Required reconciliation work:

- re-run the live runner against the post-`#2462` workflow CLI
- align the outer-workflow control flow with the new reset/resubmit/approval/delete semantics
- replace any pre-`#2462` assumptions about workflow lifecycle, teardown, or gate progression
- update docs and examples so the plan describes the post-merge control surface, not the pre-merge spike

Exit criteria:

- the package still runs the safe matrix slice correctly after the `#2462` merge
- the plan and code agree on the new workflow lifecycle primitives

## Phase 11: Runtime Hardening After #2462

Harden the runtime paths before expanding breadth.

Required hardening:

1. make the outer workflow match the live runner's semantics
2. require validation before approval in gated paths
3. remove any silent-success or auto-approve-only shortcuts from the final gated implementation
4. keep the TypeScript assert logic and shell/outer-workflow assertions equivalent
5. ensure teardown and observation collection cover all resources touched across all runs, not just the final mutated set

Concrete implementation direction:

- split the outer workflow's gate handling into monitor, validate, approve, and continue steps
- pass fixture-driven validations into the outer-workflow gate path, not just the live runner
- make approval conditional on validation success
- fail fast when setup fixtures fail
- keep cleanup in `finally`-style control flow where possible

Exit criteria:

- the live runner and the outer-workflow path enforce the same approval semantics
- failed validations stop the scenario before approval
- teardown leaves the scenario context clean after success or failure

## Phase 12: Teardown Hardening

Make teardown mandatory for success.

Teardown responsibilities:

1. delete inner workflow(s)
2. delete migration CRDs created by the scenario
3. delete scenario-scoped ConfigMaps
4. verify empty final state

If teardown fails, the outer workflow should fail.

Exit criteria:

- repeated runs start from the same clean context

## Phase 13: Expand Safe Matrix Coverage

Only after the MVP works and the post-`#2462` runtime hardening is done:

- add `transitive-dependent-change`
- add more reviewed safe mutators
- add coverage accounting for unexpanded cases
- add direct tests for spec loading, fixture resolution, gate matching, and outer-workflow rendering

Exit criteria:

- the framework can exercise a broader safe-change matrix with understandable reports
- the expanded authoring/report surface has direct automated coverage

## Phase 14: Add Gated Flow

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

## Phase 15: Add Impossible/Delete Flow

Implement one impossible case only:

- `immediate-dependent-impossible-change`

Required additions:

- wait-for-block step
- assert-before-action
- delete action
- retry/resubmit path
- assert-after-action

Important semantic:

- impossible means impossible to update in place
- not impossible to proceed after deleting the old resource and retrying

Post-`#2462` expectation:

- this flow should use the merged workflow lifecycle primitives rather than a parallel custom recovery path

Exit criteria:

- blocked dependent path is visible before delete
- resumed path is visible after delete/retry

## Recommended First Delivery Slice

If you are handing this to another agent, the first concrete milestone should stop after Phase 9.

That milestone should deliver:

- package scaffold and types
- checksum report API
- derived subgraph helper
- tiny approved mutator registry
- one simple safe matrix expander
- ConfigMap state handoff
- TypeScript assert container
- outer workflow MVP
- one passing report
- one intentionally failing report
- teardown

It should not yet attempt:

- full approval validation in the outer workflow
- impossible/delete recovery
- broad matrix coverage
- multiple focuses
- complex scenario packs
- post-`#2462` lifecycle reconciliation

## Suggested First Scenario

Use one baseline config and one focus:

- focus: `capture-proxy`

Generated cases:

- `focus-change/safe`
- `immediate-dependent-change/safe`

Optional third case:

- `transitive-dependent-change/safe`

Expected value of this slice:

- you can see expansion
- you can see execution
- you can see reporting
- you can see failure rendering
- you can verify teardown

That is enough confidence to cut the first PR, merge `#2462`, and then move on to gated and impossible behavior.

## Agent Handoff Checklist

This section is written as a direct execution guide for another agent.

### Ground Rules

- Do not try to implement the full design in one pass.
- Stop after the first visible end-to-end slice unless explicitly asked to continue.
- Prefer small, inspectable artifacts over broad abstraction.
- Keep all mutations manually authored.
- Keep the first matrix limited to `safe` cases only.
- Do not implement approval or impossible/delete flows in the first slice.
- After the first slice lands, merge `#2462` before continuing into gated and impossible behavior.

### Workspace Targets

Primary new package:

```text
orchestrationSpecs/packages/e2e-orchestration-tests/
```

Primary design references:

- [e2eOrchestrationTestDesign.md](/Users/schohn/dev/m2/docs/e2eOrchestrationTestDesign.md)
- [e2eOrchestrationImplementationPlan.md](/Users/schohn/dev/m2/docs/e2eOrchestrationImplementationPlan.md)

Likely integration points:

- `packages/config-processor/`
- `packages/migration-workflow-templates/`
- `packages/schemas/`
- `packages/argo-workflow-builders/`

### Phase-by-Phase Execution

#### Phase 0

Create the package scaffold and types only.

Files to create:

```text
packages/e2e-orchestration-tests/
  package.json
  tsconfig.json
  src/types.ts
  src/reportSchema.ts
```

Recommended exported types:

```typescript
export type ChangeClass = "safe" | "gated" | "impossible";

export type DependencyPattern =
  | "focus-change"
  | "immediate-dependent-change"
  | "transitive-dependent-change"
  | "focus-gated-change"
  | "immediate-dependent-gated-change"
  | "immediate-dependent-impossible-change";

export type ApprovedMutator = { ... };
export type ScenarioSpec = { ... };
export type ExpandedTestCase = { ... };
export type ChecksumReport = { ... };
export type DerivedSubgraph = { ... };
export type ScenarioObservation = { ... };
export type ScenarioReport = { ... };
```

Done means:

- package builds
- types compile
- no runtime implementation yet

#### Phase 1

Implement checksum inspection.

Files to create/update:

```text
packages/e2e-orchestration-tests/src/checksumReporter.ts
packages/config-processor/...   # only if refactor is needed
```

Recommended functions:

```typescript
export async function buildChecksumReport(rawConfig: unknown): Promise<ChecksumReport>;
export async function buildWorkflowArtifacts(rawConfig: unknown): Promise<{
  workflowConfig: unknown;
  checksumReport: ChecksumReport;
}>;
```

Done means:

- one fixture config can produce a checksum report
- report is deterministic
- report is snapshot-tested

#### Phase 2

Implement derived-subgraph resolution.

Files to create:

```text
packages/e2e-orchestration-tests/src/derivedSubgraph.ts
packages/e2e-orchestration-tests/tests/derivedSubgraph.test.ts
```

Recommended functions:

```typescript
export function deriveSubgraph(
  report: ChecksumReport,
  focus: string
): DerivedSubgraph;
```

Done means:

- for the chosen baseline config, `capture-proxy` resolves to:
  - focus
  - one immediate dependent
  - one transitive dependent
  - one upstream prerequisite
  - one independent component

#### Phase 3

Implement the initial approved mutator registry.

Files to create:

```text
packages/e2e-orchestration-tests/src/approvedMutators.ts
packages/e2e-orchestration-tests/tests/approvedMutators.test.ts
```

Initial mutators:

- one `focus-change` safe mutator
- one `immediate-dependent-change` safe mutator
- optional one `transitive-dependent-change` safe mutator

Done means:

- mutators can be resolved by `changeClass` and `pattern`
- mutators produce valid configs for the chosen baseline
- mis-tagged mutators are rejected by expansion-time validation

#### Phase 4

Implement the smallest matrix expander.

Files to create:

```text
packages/e2e-orchestration-tests/src/scenarioCompiler.ts
packages/e2e-orchestration-tests/src/matrixExpander.ts
packages/e2e-orchestration-tests/tests/matrixExpander.test.ts
```

Recommended functions:

```typescript
export function expandScenario(
  scenario: ScenarioSpec,
  report: ChecksumReport
): ExpandedTestCase[];
```

First-cut supported inputs:

- one `focus`
- `changeClass: safe`
- patterns:
  - `focus-change`
  - `immediate-dependent-change`

Done means:

- one matrix spec expands into concrete test cases with stable names
- `requireFullCoverage` is enforced
- expectations are derived from topology, not just raw checksum diffs

#### Phase 5

Implement the report schema and one intentional failure path.

Files to create:

```text
packages/e2e-orchestration-tests/src/reportSchema.ts
packages/e2e-orchestration-tests/tests/reportSchema.test.ts
packages/e2e-orchestration-tests/fixtures/
```

Done means:

- one passing report example exists
- one failing report example exists
- both are stable and readable

#### Phase 6

Implement ConfigMap state handoff helpers.

Files to create:

```text
packages/e2e-orchestration-tests/src/stateStore.ts
```

Recommended functions:

```typescript
export async function writeObservationConfigMap(...): Promise<void>;
export async function readObservationConfigMap(...): Promise<ScenarioObservation | null>;
export async function writeChecksumReportConfigMap(...): Promise<void>;
export async function readChecksumReportConfigMap(...): Promise<ChecksumReport | null>;
```

Done means:

- observation and checksum data can be round-tripped through ConfigMaps

#### Phase 7

Implement the TypeScript assert container.

Files to create:

```text
packages/e2e-orchestration-tests/src/assertContainer/validate.ts
packages/e2e-orchestration-tests/src/assertContainer/Dockerfile
```

Recommended function boundaries inside the validator:

```typescript
function loadExpectedInput(...): ...;
function loadPriorObservation(...): ...;
function readCurrentResourceState(...): ...;
function compareObservedToExpected(...): ...;
function emitScenarioResult(...): ...;
```

First-cut assertions:

- focus changed when expected
- immediate dependent changed when expected
- unrelated branch unchanged

Done means:

- validator can produce a machine-readable pass/fail result from prepared inputs

#### Phase 8

Implement the outer workflow MVP.

Files to create:

```text
packages/e2e-orchestration-tests/src/buildOuterWorkflow.ts
packages/e2e-orchestration-tests/src/templates/...   # if needed
packages/e2e-orchestration-tests/tests/outputMatch.test.ts   # or equivalent
```

Workflow steps for first cut:

1. baseline configure
2. baseline wait
3. baseline assert
4. no-op resubmit
5. no-op wait
6. no-op assert
7. safe matrix case configure
8. safe matrix case wait
9. safe matrix case assert
10. teardown

Done means:

- one simple generated matrix case runs end to end
- a structured report is emitted
- the live runner and outer workflow can both exercise the same safe scenario

#### Phase 9

Add one intentional failing case.

Approach:

- create a dev-only scenario fixture
- make one expected unchanged/reran assertion intentionally wrong

Done means:

- a developer can see how failure appears in:
  - the outer workflow
  - the scenario report

#### Phase 10

After the first PR lands, merge `#2462` and reconcile the runtime path.

Files to revisit:

```text
packages/e2e-orchestration-tests/src/e2e-run.ts
packages/e2e-orchestration-tests/src/buildOuterWorkflow.ts
packages/e2e-orchestration-tests/src/fixtures.ts
packages/e2e-orchestration-tests/src/fixtureRegistry.ts
```

Done means:

- the safe scenario still runs after the `#2462` merge
- runtime lifecycle control matches the merged CLI/CRD behavior
- docs and code no longer rely on pre-`#2462` lifecycle assumptions

#### Phase 11

Harden the post-`#2462` runtime semantics.

Key requirements:

- validation must run before approval in gated paths
- the outer workflow must not be weaker than the live runner
- setup failures should stop the scenario
- teardown and observation must account for all touched resources

Done means:

- a failed validation prevents approval
- the live runner and outer workflow enforce the same gate semantics
- cleanup leaves the environment clean on success and failure

#### Phase 12

Harden teardown.

Files to create/update:

```text
packages/e2e-orchestration-tests/src/teardown.ts
packages/e2e-orchestration-tests/src/buildOuterWorkflow.ts
```

Recommended responsibilities:

- delete inner workflow
- delete CRDs created by the scenario
- delete scenario ConfigMaps
- verify clean final state

Done means:

- rerunning the same scenario starts cleanly

### First Slice Deliverable

The first deliverable handed back by the agent should include:

- new `e2e-orchestration-tests` package
- checksum report generation
- derived subgraph helper
- initial mutator registry
- minimal matrix expansion for safe cases
- ConfigMap state store
- TypeScript assert container
- outer workflow MVP
- one passing report
- one failing report
- teardown

### Explicit Non-Goals For First Slice

The agent should not implement yet:

- gated approval flow automation in the outer workflow
- impossible/delete recovery flow
- broad scenario packs
- multi-focus scenarios
- full coverage accounting across many components
- post-`#2462` lifecycle reconciliation beyond keeping the plan ready for it

### Suggested First Scenario Fixture

Create one baseline fixture around:

- focus: `capture-proxy`

With these generated cases:

- `focus-change/safe`
- `immediate-dependent-change/safe`

Optional:

- `transitive-dependent-change/safe`

And one intentionally failing expectation for local/dev inspection.

### What To Show Back After First Slice

Ask the agent to return:

1. The files created/changed
2. The exact scenario fixture used
3. The expanded concrete test names
4. One passing report example
5. One failing report example
6. What remains unimplemented from the full design
