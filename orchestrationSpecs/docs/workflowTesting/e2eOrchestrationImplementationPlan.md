# E2E Orchestration Test Framework Implementation Plan

This is an adjusted implementation plan for [e2eOrchestrationTestFramework.md](e2eOrchestrationTestFramework.md) after checking the current repository layout and the migration workflow implementation. Keep the original document as the design narrative and source of intent. Use this file as the implementation checklist and sequencing guide.

## Repo Fit

The framework should live under `orchestrationSpecs`, not a repo-root `src/` directory. The closest fit is a new workspace package:

```
orchestrationSpecs/packages/e2e-orchestration-tests/
```

Reasons:

- The framework is TypeScript and depends on `@opensearch-migrations/schemas`, `@opensearch-migrations/config-processor`, and Argo/Kubernetes types.
- Existing orchestration packages are npm workspaces under `orchestrationSpecs/packages/*`.
- Test and type-check commands already run package scripts through the workspace.
- The baseline config samples are already in `orchestrationSpecs/packages/config-processor/scripts/samples/`.

Add this package to the existing workspace automatically by placing it under `packages/*`. Give it package scripts for `test`, `type-check`, `generate-transition-trees`, `run`, and later `generate-outer`.

## Design Contract

`e2eOrchestrationTestFramework.md` is the source of truth for framework semantics: specs, fixtures, responses, checkpoints, expected CRD `ComponentTopology`, transition trees, snapshots, and the constraint walk. This plan only describes implementation order and repo placement. If this plan conflicts with the design document, update the plan.

## Target Files

Create these files in `orchestrationSpecs/packages/e2e-orchestration-tests`:

| File | Purpose |
|------|---------|
| `package.json` | Workspace package metadata and scripts |
| `tsconfig.json` | Package TypeScript config |
| `jest.config.js` | Unit test config |
| `src/types.ts` | Shared Zod schemas and TypeScript types |
| `src/componentTopology.ts` | Expected CRD `ComponentTopology` model and topology helpers |
| `src/componentTopologyResolver.ts` | Creates expected CRD `ComponentTopology` from spec/baseline/fixtures |
| `src/observedTopologyAdapter.ts` | Optional adapter from generated/live CRDs for comparison |
| `src/specLoader.ts` | Test spec YAML loading and validation |
| `src/fixtureRegistry.ts` | Registry for mutators, actors, observers, checkers, providers |
| `src/matrixExpander.ts` | Nested-loop expansion from spec selectors to expanded test cases |
| `src/transitionTreeGenerator.ts` | Zod schema metadata to committed transition trees |
| `src/transitionTreeMapper.ts` | User-config field paths to expected component IDs |
| `src/assertLogic.ts` | Constraint walk and diagnostics |
| `src/phaseCompletion.ts` | Wait/predicate logic for terminal or held component states |
| `src/k8sClient.ts` | Thin Kubernetes custom object client wrapper |
| `src/liveExecutor.ts` | Four-run live execution engine |
| `src/e2e-run.ts` | CLI entrypoint |
| `src/snapshotStore.ts` | Diagnostic JSON snapshot writer |
| `src/reportSchema.ts` | Snapshot schema |
| `src/fixtures/` | Built-in mutators, actors, observers, checkers |
| `specs/` | Initial specs |
| `transitionTrees/` | Generated and reviewed transition trees |
| `snapshots/` | Local run output, probably gitignored |

Also update:

- `orchestrationSpecs/package.json` with convenience scripts if desired.
- `orchestrationSpecs/tsconfig.typecheck.all.json` only if this package should participate in the existing global type-check.
- `.gitignore` or package-local `.gitignore` for generated `snapshots/`.

## Implementation Sequence

Status markers are inline with the implementation sequence:

- `[x]` done in the current branch
- `[~]` partially done; usable scaffold exists but exit criteria are not complete
- `[ ]` not started

### 1. Package Skeleton And Pure Types `[x]`

Current branch status:

- `[x]` Workspace package skeleton exists under `orchestrationSpecs/packages/e2e-orchestration-tests`.
- `[x]` Package-local `test` and `type-check` scripts run successfully.
- `[x]` Core spec/schema loading exists for `baseConfig`, `phaseCompletionTimeoutSeconds`, `matrix`, `lifecycle`, and `approvalGates`.
- `[~]` Selector validation exists, but the `subject-change` comment and implementation still need to be reconciled.

Create the package with no cluster dependency yet.

Implement:

- `types.ts` with `ChangeClass`, `DependencyPattern`, `Response`, `LifecyclePhase`, `ComponentKind`, `ComponentId`, `Checkpoint`, `ObservedComponent`, `ObservedSnapshot`, and `Violation`.
- Spec schema with `baseConfig`, `phaseCompletionTimeoutSeconds`, `matrix`, `lifecycle`, and `approvalGates`.
- Fixture interfaces from the design doc, but keep `WorkflowConfig` as `z.infer<typeof OVERALL_MIGRATION_CONFIG>` for mutators because specs point at user configs.

Exit criteria:

- `npm run -w @opensearch-migrations/e2e-orchestration-tests type-check` works.
- Unit tests validate spec parsing and invalid enum failures.

### 2. Thin Live E2E Harness `[~]`

Current branch status:

- `[x]` A noop-oriented runner exists and can write snapshots.
- `[x]` Baseline and noop submissions use distinct workflow names, with tests covering deterministic and production suffix paths.
- `[x]` CLI runs with the built-in actor registry by default, and programmatic callers can add or omit actors.
- `[x]` Minimal lifecycle actor registry exists; setup actors run before submission, teardown actors run from `finally`, and teardown failures are recorded as diagnostics.
- `[x]` `create-basic-auth-secrets` setup actor reads basic-auth secret names from the baseline config and applies idempotent local test secrets before configure/submit.
- `[x]` Generic phase-completion no longer treats `Pending` as terminal for topology components.
- `[x]` A live noop spec exists at `packages/e2e-orchestration-tests/tests/live-specs/fullMigrationNoop.test.yaml`.
- `[x]` Snapshots include an ordered event history for setup, configure, submit, approve, wait, observe, cleanup, and teardown.
- `[x]` Workflow CLI failures include stderr/stdout diagnostics in error snapshots.
- `[x]` Each submitted run deletes and waits for both the outer submitted Argo workflow and the fixed inner `migration-workflow`; failure to confirm baseline cleanup stops the noop run before it can collide.
- `[~]` Workflow names are unique but not yet sanitized or length-limited for Kubernetes/Argo naming constraints.
- `[~]` The first live run has reached subject-under-test behavior issues, but expected pass/fail assertions are not wired yet.

Get a real cluster-backed test running as early as possible, even if it covers only one safe/noop path and uses hardcoded scenario data. This is intentionally a vertical slice from the design document's "How A Test Case Runs" section, not the full framework.

Implement:

- A CLI that accepts one spec path and one exact case name.
- Submission of a baseline config using the existing config-processor path.
- A generated unique workflow name for each submission, including the initial noop slice. Do not rely on the workflow CLI's default name.
- Structural gate approval from the spec's `approvalGates`.
- A basic phase-completion wait over observed CRD components. `Pending` must not be treated as terminal for ordinary topology components; approval-gate waiting should be handled separately.
- A basic CRD observation dump after baseline.
- A second submission of the same config for `noop-pre`.
- Execution of lifecycle teardown actors in a `finally` path when configured. Setup actors may be minimal in the first slice, but teardown-on-failure must be wired before live runs are trusted.
- A snapshot file with raw observations, even before all assertions exist.

Exit criteria:

- `[x]` One local command submits `fullMigrationWithTraffic.wf.yaml`, approves structural gates, waits, resubmits unchanged config, and writes a snapshot or actionable error snapshot.
- `[x]` Baseline and noop submissions have distinct workflow names in tests and live output.
- `[x]` A non-gate component in `Pending` does not satisfy phase completion.
- `[x]` Failures still run teardown actors if configured.
- `[x]` The snapshot shape matches the design document enough that later assertions can read it.
- `[ ]` Workflow names are sanitized/length-limited before broader matrix expansion.

### 3. Minimal Observation And Snapshot Model `[~]`

Current branch status:

- `[x]` `k8sClient.ts`, `snapshotStore.ts`, and `reportSchema.ts` exist with focused tests.
- `[x]` Snapshot storage/report schema validate snapshots and reject duplicate observation keys.
- `[x]` CRD observation captures resource state including kind/name/phase/generation/UID/raw resource data.
- `[x]` Case snapshots include operational event history and fatal command stderr/stdout.
- `[~]` Raw Argo workflow phase and node diagnostics are not yet captured.

Stabilize the data captured by the thin live test.

Implement:

- `k8sClient.ts` with list/read/patch helpers for migration CRDs and `ApprovalGate`.
- `snapshotStore.ts` and `reportSchema.ts`.
- Observation fields for CRD kind, name, phase, checksum, UID, generation, `dependsOn`, and approval gate phase.
- Raw Argo workflow phase and node dump as diagnostic data.

Exit criteria:

- `[x]` Snapshots are valid JSON and validate against `reportSchema`.
- `[x]` Duplicate observation keys are rejected.
- `[x]` A failed live run still writes enough diagnostic data to debug command/setup/cleanup failures.
- `[ ]` Raw Argo workflow phase and node diagnostics are captured for failed component execution.

### 4. Temporary Expected CRD ComponentTopology For The First Scenario `[~]`

Current branch status:

- `[x]` `ComponentTopology` exists with cycle, missing-node, upstream/downstream, and independence tests.
- `[x]` Temporary `ComponentTopologyResolver` exists for `fullMigrationWithTraffic.wf.yaml`.
- `[x]` The temporary topology uses expected CRD graph semantics: `DataSnapshot` depends on `CaptureProxy`; `SnapshotMigration` has no `spec.dependsOn` edge.
- `[x]` Generator-backed regression test derives topology from the real sample via `MigrationInitializer` and compares it to the hardcoded topology.
- `[x]` `fullMigrationWithTraffic.wf.yaml` was adjusted so it validates with current schema rules (`removeAuthHeader` removed from the replayer config).
- `[~]` Live/generated observed-topology comparison is not yet wired into runner output.

Add only the topology support needed by the first E2E case. `ComponentTopology` represents the expected CRD resource graph: expected resource IDs and expected CRD `spec.dependsOn` edges. It does not represent user-config nesting or workflow-only execution ordering.

Do not generate this expected topology from live production CRDs. It may be hardcoded or deduced independently for the first slice, then compared against generated/live CRD observations as a double-entry check.

Use a stable component ID format:

```
<kind-lowercase>:<expected-resource-name>
```

Examples:

- `kafkacluster:default`
- `capturedtraffic:capture-proxy-topic`
- `captureproxy:capture-proxy`
- `datasnapshot:source-snap1`
- `snapshotmigration:source-target-snap1-migration-0`
- `trafficreplay:capture-proxy-target-replay1`

Implement:

- `componentTopology.ts` with `ComponentTopology` and helpers: `downstreamOf`, `upstreamOf`, and `independentOf`.
- A temporary `ComponentTopologyResolver` path for `fullMigrationWithTraffic.wf.yaml`.
- A generator-backed unit test that derives topology from the sample via `MigrationInitializer` and fails when the hardcoded topology drifts.
- A comparison between expected topology and observed live/generated CRD `spec.dependsOn`, reported as its own failure category.

Exit criteria:

- A test can fail if production CRD generation emits an unexpected resource name or dependency.
- The same `ComponentTopology` drives cascade and independence assertions.

### 5. Assertion Logic `[~]`

Current branch status:

- `[x]` Pure `assertLogic` exists for `baseline-complete`, `noop`, and safe `mutated-complete`.
- `[x]` `baseline-complete` is observation-only and does not require a subject.
- `[x]` Noop checks every component is `skipped`.
- `[x]` Safe `mutated-complete` checks subject/downstream `reran` and upstream/independent `skipped`.
- `[ ]` Gated and impossible checkpoints still return explicit unimplemented-checkpoint violations.

Build `assertLogic` as a pure library, then wire it into the thin live E2E slice.

Inputs:

- `ObservedSnapshot`
- `ComponentTopology`
- `subject`
- `changeClass`
- `checkpoint`

Behavior:

- Noop checkpoint: every component must have behavior `skipped`.
- `baseline-complete`: observation anchor only; no pass/fail assertions.
- Safe `mutated-complete`: subject and downstream components must `reran`; upstream and independent components must `skipped`.
- Gated `before-approval`: subject `Paused`; downstream `Unstarted` or equivalent not-started state.
- Gated `after-approval`: subject and downstream `reran`; independent `skipped`.
- Impossible `on-blocked`: subject `Blocked`; downstream `Unstarted` or `Blocked`; independents unchecked.
- Impossible `after-approve-without-reset`: subject still `Blocked`; downstream not advanced; independents unchecked.
- Impossible `after-reset`: subject `Deleted`; downstream not advanced; independents checked.
- Impossible `after-approve`: subject and downstream `reran`; independent `skipped`.

Keep observed phase and observed behavior separate. CRD status phases like `Ready`, `Initialized`, `Deleting`, or `Blocked` are not always the same thing as behavior labels like `ran`, `reran`, and `skipped`.

Exit criteria:

- `[x]` Unit tests cover noop and safe mutation checkpoints, including upstream stability.
- `[x]` Violation messages include component ID, constraint, checkpoint, expected states, and observed state for implemented checkpoints.
- `[ ]` Violation messages include relevant changed paths.
- `[ ]` Unit tests cover gated and impossible checkpoints.

### 6. Live Runner Safe Mutation Slice `[~]`

Current branch status:

- `[x]` Safe-case runner scaffold exists as `runSafeCase`: baseline -> noop-pre -> mutated -> noop-post.
- `[x]` `proxyNumThreadsMutator` exists as the first safe mutator candidate.
- `[x]` Behavior derivation exists from CRD observation deltas.
- `[x]` Matrix expansion and mutator registry scaffolding exist.
- `[ ]` CLI still needs to run expanded cases by default.
- `[ ]` Noop-only mode should become explicit opt-in, e.g. `--noop-only`.
- `[ ]` Live CRD checksum/fingerprint extraction has not been verified against actual CRD shape.
- `[ ]` Argo workflow/node execution status is not yet captured or cross-checked.

Extend the thin live harness from noop-only to one safe mutator.

Default runner behavior should be expansion-first:

- Load the spec.
- Expand all cases from `matrix.select` and the mutator registry. If `matrix.select` is omitted, expand the default selectors for the subject.
- Run all expanded cases sequentially in the namespace.
- Run the noop-only harness only when explicitly requested by a developer flag such as `--noop-only`.

Running "too much" is acceptable for the next slice; avoid requiring an exact case name before the runner can prove the safe path. A later developer convenience flag may select one exact case.

Sequence:

1. Run setup actors.
2. Submit baseline with a unique workflow name.
3. Approve structural gates from `approvalGates`.
4. Wait for phase completion.
5. Observe baseline.
6. Resubmit same config as `noop-pre`.
7. Wait, observe, assert noop.
8. Submit mutated config.
9. Approve structural gates.
10. Wait, observe, assert safe mutation.
11. Resubmit mutated config as `noop-post`.
12. Wait, observe, assert noop.
13. Run teardown actors.
14. Write snapshot.

This slice assumes the noop runner already has unique workflow names, teardown-on-failure, and component phase-completion semantics. Do not defer those basics until multi-case execution; otherwise the first live safe run may overwrite evidence or assert against unsettled resources.

Define behavior from both CRD state deltas and Argo execution evidence:

- `ran`: no prior successful checksum for this resource in the case.
- `skipped`: checksum/fingerprint and resource UID unchanged and Argo did not execute the component body.
- `reran`: checksum/fingerprint, generation, UID, or correlated Argo node execution changed and the component reached ready after the mutated submission.
- `Unstarted`: CRD exists but has not advanced from initialization for this run, or downstream Argo node has not started.

The first live safe run must inspect the actual CRD shape and teach `extractCrdObservation` where the migration framework records config checksum/fingerprint data. Candidate locations to check are CRD `status`, `spec`, labels, and annotations. If checksum and Argo node execution disagree, record both in the snapshot diagnostics instead of silently choosing one.

Exit criteria:

- `[ ]` CLI expands and runs all selected safe cases by default.
- `[ ]` `--noop-only` or equivalent preserves the current noop harness as an opt-in developer mode.
- `[ ]` One safe proxy or replay spec runs end-to-end locally.
- `[ ]` Snapshot is written per expanded case.
- `[ ]` `assertLogic` is the pass/fail oracle, not a direct snapshot comparison.
- `[ ]` CRD checksum/fingerprint and Argo node execution evidence are both captured when available.
- `[ ]` Snapshot diagnostics call out any disagreement between checksum-derived and Argo-derived behavior.

### 7. Matrix Expander And Mutator Registry `[~]`

Current branch status:

- `[x]` Minimal `MutatorRegistry` exists.
- `[x]` Minimal `matrixExpander` exists.
- `[x]` Default safe `subject-change` expansion exists when `matrix.select` is absent.
- `[x]` First safe proxy mutator exists.
- `[ ]` Mutated configs are not yet validated against `OVERALL_MIGRATION_CONFIG` before submission.
- `[ ]` Gated/impossible response expansion is not implemented.

Expand cases with straightforward nested-loop code; it does not need a complex topology resolver.

Conceptually:

```typescript
for (const selector of spec.matrix.select ?? defaultSelectorsFor(subject)) {
  for (const mutator of registry.findMutators(subject, selector)) {
    for (const response of responsesFor(selector, mutator)) {
      cases.push({ subject, mutator, response });
    }
  }
}
```

Start with a small fixture set tied to actual sample config paths:

- Safe proxy mutator: change a scalar in `traffic.proxies.<name>.proxyConfig` that already exists in the schema.
- Safe replay mutator: change a scalar under `traffic.replayers.<name>`.
- Add gated/impossible mutators only after the thin live path and one safe mutation are stable.

Each mutator should declare:

- `subjectKind` or `componentKind`.
- `componentNamePattern`.
- `changeClass`.
- `dependencyPattern`.
- `changedPaths`, using user-config paths.

Exit criteria:

- `[x]` A spec with `select` expands to the expected safe cases.
- `[ ]` Mutated configs validate against `OVERALL_MIGRATION_CONFIG`.
- `[ ]` The CLI runner executes all expanded safe cases by default.
- `[ ]` Optional exact-case selection exists only as a developer convenience, not as required behavior.

### 8. Transition Tree Generator `[ ]`

Implement a Zod schema walker over `OVERALL_MIGRATION_CONFIG`.

Rules:

- Read `schema.meta()?.changeRestriction`.
- Missing metadata means `safe`.
- Preserve arrays and records using path tokens such as `[]` and `{key}`.
- Write deterministic JSON sorted by path.
- Include generator metadata: schema package name/version if available, generation timestamp, and source schema export.

Recommended initial output:

```
transitionTrees/userConfigFields.json
```

Shape:

```json
{
  "sourceSchema": "OVERALL_MIGRATION_CONFIG",
  "fields": [
    { "path": "traffic.proxies.{key}.proxyConfig.listenPort", "changeClass": "safe" },
    { "path": "...", "changeClass": "gated" }
  ]
}
```

Then implement `transitionTreeMapper.ts` separately to map changed user-config paths to expected component IDs.

Exit criteria:

- Generator output is deterministic across two runs.
- Unit tests cover explicit `gated`, explicit `impossible`, and default `safe`.
- A mutator whose declared `changeClass` disagrees with generated tree data fails at expansion time.

### 9. Phase Completion Predicate `[~]`

Current branch status:

- `[x]` Basic polling predicate exists and returns structured timeout diagnostics.
- `[x]` `Pending` is not terminal for generic topology components; approval-gate held phases are modeled separately.
- `[ ]` Phase names still need validation against the first live runs.

After the safe slice works, harden waiting semantics.

Terminal or held states should be defined from actual CRD phases observed in the cluster. Start with:

- Terminal/held: `Ready`, `Skipped`, `Failed`, `Blocked`, `Paused`, `Deleted`, `Approved`
- Non-terminal: `Initialized`, `Pending`, `Running`, `Deleting`

Adjust these names based on observed CRD status in the first live runs.

Exit criteria:

- Timeout produces a distinct `phase-timeout` result in the snapshot and process exit.
- `assertNoViolations` is never called with non-terminal components unless the test is explicitly checking non-advancement.

### 10. Gated Flow `[ ]`

Implement `response: approve` and `response: leave-blocked` after safe flow is stable.

Use `ApprovalGate` CRD observation:

- Identify mutation-triggered gates separately from structural gates.
- Snapshot gate name and status phase.
- Patch `status.phase = Approved` for approval responses.

Exit criteria:

- `before-approval` and `after-approval` checkpoints are populated from real CRD observations.
- `leave-blocked` ends after verifying the gate is still pending/paused and writes a complete snapshot.
- Gate-time validations use split observer/checker fixtures.

### 11. Impossible Flow `[ ]`

Implement only after reset behavior is understood per component kind.

Actors should wrap the existing reset semantics:

- `workflow reset <name> --cascade` where dependents must be deleted.
- Add `--include-proxies` only for tests that intentionally reset proxies.
- Add `--delete-storage` only for Kafka storage tests that need it.

Responses:

- `reset-then-approve`
- `approve-only`
- `reset-only`
- `leave-blocked`

For `approve-only` and `reset-only`, verify non-advancement by comparing a before/after observation window, not just a single phase value.

Exit criteria:

- All four responses produce snapshots.
- The AND condition is proven: approval alone does not advance, reset alone does not advance, reset plus approval does advance.

### 12. Multi-Case Execution `[ ]`

Run all expanded cases in a spec. For the next slice, this starts with all expanded safe cases; gated/impossible cases join after their single-case semantics are reliable.

Rules:

- One case at a time in a namespace.
- Reuse the unique workflow naming and mandatory teardown behavior established in the noop/safe slices.
- Snapshot filename includes spec, subject, mutator, and response.
- Process exit is nonzero if any case fails.
- Noop-only execution is opt-in developer mode, not the default CLI behavior.

Exit criteria:

- A spec with multiple safe selectors creates one snapshot per expanded case.
- Failure in one case does not skip teardown.

### 13. Timing Capture `[ ]`

Add Argo node timing once behavior assertions are stable.

Extract:

- `startedAt`
- `finishedAt`
- computed `startedAtSeconds` relative to workflow submission
- computed `durationSeconds`

Keep timing diagnostic only.

Exit criteria:

- Component entries include timing where an Argo node can be correlated.
- Missing timing is represented explicitly rather than failing assertions.

### 14. Outer Workflow / CI Wrapper `[ ]`

Only after the live runner is dependable, add the unattended path.

The outer workflow should invoke the same package runner rather than reimplementing sequencing. Any difference in behavior between live and CI should be treated as a bug.

Exit criteria:

- Generated outer workflow can run the same spec and produce the same snapshot schema.
- CI can archive snapshots.

## First Implementation Slice

Status for the original concrete slice:

1. `[x]` Create `orchestrationSpecs/packages/e2e-orchestration-tests`.
2. `[x]` Implement `types.ts` and `specLoader.ts` only far enough to load one spec from the design document's shape.
3. `[x]` Implement `k8sClient.ts`, `snapshotStore.ts`, and `reportSchema.ts` for raw observation capture.
4. `[x]` Implement `e2e-run.ts` with one exact noop case: baseline submission, structural approval, wait, observe, resubmit unchanged, observe, write snapshot.
5. `[x]` Add a temporary `ComponentTopologyResolver` path for the chosen baseline.
6. `[ ]` Add noop assertions, then add one safe mutator and the safe mutation sequence.

This gets a real E2E test into the repo early. The fuller transition tree generator, matrix expansion, and generalized topology handling should grow around that live slice instead of blocking it.

The next delegatable work should start at step 5, `Assertion Logic`, with a narrow noop assertion slice. Keep it pure and unit-tested first, then wire it into the existing noop runner before starting step 6's safe mutation sequence.

## Follow-Up Quality Work

- Split `compare-indices` into `target-index-snapshot` observer and `index-parity` checker.
- Add contradiction diagnostics that name both the changed user-config path and the affected CRD component.
- Add a developer mode that runs a single expanded case by exact case name.
- Add a dry-run command that expands specs and prints planned cases without touching Kubernetes.
- Add a regeneration check for transition trees in CI.
- Document observed CRD phase values once the first live run is available.
