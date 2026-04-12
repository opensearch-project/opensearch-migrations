# E2E Orchestration Test Framework — Design

## Problem

We need to verify that the migration workflow's orchestration logic — skip, re-run, wait, and gate decisions — behaves correctly across multiple sequential workflow submissions with config changes between them. We are NOT testing data flow (traffic capture, snapshot contents, document migration). We ARE testing:

- A step skips when its checksum matches the prior run
- A step re-runs when its own config changes
- A downstream step blocks until its upstream dependency completes with the expected checksum
- A downstream step does NOT block when an upstream's operational-only field changes
- Gated changes suspend the workflow until approval is injected
- There are many combinations to test.
- Right now, we're concerned with the dependency and lifetime management, 
  but the idea of a combinatoric workflow staging and verification system is
  transferable to general migration tests.

## Core Idea: Argo-as-Test-Runner

The test itself is an Argo workflow. An outer "test workflow" orchestrates a sequence of inner migration workflow submissions, waits for each to complete, and asserts on CRD states plus a small amount of workflow-level evidence. If the outer workflow succeeds, the test passed.

```
outer workflow (the test):
  ├── run-1-configure       # workflow configure (delete + submit) with base config
  ├── run-1-wait            # wait for inner workflow to complete
  ├── run-1-assert          # check CRD states, expected checksums, wait evidence
  ├── run-2-configure       # workflow configure with mutated config
  ├── run-2-wait            # wait for inner workflow to complete
  ├── run-2-assert          # check CRD states, expected checksums, timing
  └── ...
```

Advantages:
- No long-running test process to babysit — Argo handles timeouts, retries, failure reporting
- The outer workflow is modeled with the TS builders — type-safe, reviewable, diffable
- CI just submits the outer workflow and checks its final phase
- Mid-flight injections (approvals) are just steps in the DAG with dependency edges
- Argo UI gives visual inspection of which step failed and why
- CRD state persists on the cluster across inner workflow runs — exactly what we're testing

### Inner Workflow Lifecycle

Each "run" in a scenario uses `workflow configure` — conceptually a delete-then-submit that ensures a clean workflow instance while preserving CRD state on the cluster. The inner migration workflow runs against the same CRDs left by the prior run, and the checkPhase/checksum logic determines what to skip or re-deploy.

## Execution Constraints for the First Cut

The first implementation is intentionally narrow:

- Only one outer test workflow runs at a time
- Only one inner migration workflow runs at a time
- Each scenario runs in a clean context
- All CRDs, workflows, and scratch ConfigMaps created for a scenario are purged at the end
- Mutations are always manually authored

This keeps the assertion surface small and avoids introducing correctness risk through automatic mutation generation too early.

## Test Oracle

The primary oracle is resource state, not workflow node naming.

For each component under test, assertions should be driven in this order:

1. Expected checksum for this run
2. Actual checksum recorded on the CR status or external resource annotation
3. Expected terminal lifecycle state (`Ready` / `Completed`)
4. Evidence that downstream resources observed the expected upstream checksum
5. Optional workflow-node evidence for debugging only

The design should not rely on Argo node display names or on specific nodes being `Omitted` as the main pass/fail signal. Those are useful diagnostics, but they are too coupled to template structure.

### Direct CR/Resource Observation

For CRDs we own, the assert container should read:

- `status.phase`
- `status.configChecksum`
- resource metadata needed to identify the object uniquely

For Kafka / other resources whose status we do not own, the assert container should read:

- readiness signals from status
- checksum annotation written by the workflow, e.g. `metadata.annotations.migration-configChecksum`

### Dependency Verification

To verify that a downstream component waited for the correct upstream state, the assert step should prefer direct resource evidence:

- upstream finished in the expected state with the expected checksum
- downstream finished with the expected checksum
- downstream did not complete before the upstream resource reached the expected checksum

The last point can be proven either by comparing workflow timestamps or by recording an observation snapshot at the end of each run and comparing those snapshots during the next run's assert step.

## State Handoff

State handoff between outer-workflow steps should use ConfigMaps.

For each run, the assert container writes a structured observation record to a scenario-scoped ConfigMap, for example:

```json
{
  "scenario": "proxy-podreplicas-skip",
  "run": 1,
  "innerWorkflowName": "test-migration-proxy-podreplicas-run-1",
  "observedAt": "2026-04-11T12:34:56Z",
  "resources": {
    "capture-proxy": {
      "kind": "CapturedTraffic",
      "name": "capture-proxy",
      "phase": "Ready",
      "configChecksum": "abcd1234"
    },
    "replay1": {
      "kind": "TrafficReplay",
      "name": "capture-proxy-target-replay1",
      "phase": "Ready",
      "configChecksum": "efgh5678"
    }
  }
}
```

The next run reads that ConfigMap to obtain the prior observed state. This is the source of truth for `prior checksum`, `prior phase`, and `prior workflow name`.

Benefits:

- explicit handoff between steps
- no hidden dependency on shell variables
- debuggable after failure
- naturally namespaced by scenario

## Checksums as a First-Class Test Input

The config processor should expose checksum computation as an inspectable operation, not just as a side effect of producing workflow config.

The ideal shape is:

- given a fully normalized migration config, produce the denormalized workflow config
- expose each component's derived checksum in a machine-readable form
- expose the dependency edges used in checksum chaining

That can be implemented either by:

- refactoring `MigrationConfigTransformer` so checksum generation is a reusable public step
- or adding an adjacent inspection command that emits a checksum report from the same logic path

Example output:

```json
{
  "kafkaClusters": {
    "default": {
      "configChecksum": "1111aaaa"
    }
  },
  "proxies": {
    "capture-proxy": {
      "configChecksum": "2222bbbb",
      "checksumForSnapshot": "4444dddd",
      "checksumForReplayer": "4444dddd",
      "dependsOn": ["default"]
    }
  },
  "snapshots": {
    "source-snap1": {
      "configChecksum": "3333cccc",
      "checksumForSnapshotMigration": "3333cccc",
      "dependsOn": ["capture-proxy"]
    }
  },
  "snapshotMigrations": {
    "source-snap1": {
      "configChecksum": "5555eeee",
      "checksumForReplayer": "6666ffff",
      "dependsOn": ["source-snap1"]
    }
  },
  "trafficReplays": {
    "capture-proxy-target-replay1": {
      "configChecksum": "7777gggg",
      "dependsOn": ["capture-proxy", "source-snap1"]
    }
  }
}
```

The per-dependency checksums (`checksumForSnapshot`, `checksumForReplayer`, `checksumForSnapshotMigration`) are critical for precise assertions. They let the assert step verify that the *right* checksum changed — not just that the component's self-checksum changed. For example, changing `podReplicas` on the proxy changes `configChecksum` but not `checksumForReplayer`, which is exactly why the replayer skips.

This makes prior/expected checksum comparison clean:

- expected checksum comes from the current mutated config via the checksum report
- prior checksum comes from the previous run observation ConfigMap

The assert step can then compare expected vs observed directly instead of inferring behavior indirectly from workflow structure.

## Scenario Authoring Model

### Breadth Over Depth

The primary goal is not exhaustive per-field testing. The primary goal is broad coverage of orchestration behaviors:

- safe change
- gated change
- impossible change
- approval action
- delete-and-recreate action
- dependency blocking
- dependency propagation across chains
- independence of unrelated branches

Per-field expansion is a secondary tool. The default modeling unit should be:

- a component role in a dependency graph
- a class of change
- a remediation action, if needed
- the expected effect on downstream and independent paths

In other words, we care more about:

- "what happens when a gated proxy change is approved"
- "what happens when an impossible snapshot change is handled by deleting the underlying resource"
- "what happens when A changes in an A → B → C chain"

than about:

- "did every single proxy field get its own test"

## Behavior-First Scenario Modeling

### Derived Dependency Subgraph

The framework should derive the dependency graph from the transformed config and checksum report. A test author should usually not write a `graph:` block by hand.

Example derived subgraph:

```text
capture-proxy -> snap1 -> migration1
capture-proxy -> replay1
rfs-independent    # unrelated branch
```

The test framework should understand:

- which components are downstream of the chosen focus
- which components are immediate dependents of the focus
- which components are transitive dependents of the focus
- which components are independent of the focus path

This lets the author say:

- change the focus and expect the whole downstream chain to react
- change an immediate dependent and expect only lower dependents to react
- change an unrelated branch and expect the focus path not to react

### Change Classes

Every authored mutation should be associated with a behavioral class:

- `safe`
- `gated`
- `impossible`

And every run may optionally define a handling action:

- `none`        : ordinary submission, no operator intervention expected
- `approve`     : approval annotation is injected after suspension
- `delete`      : underlying resource is deleted so the workflow can recreate it

This is the main modeling axis for the first implementation.

### Behavior-Oriented Scenario Spec

A scenario should let the author describe:

- the focus component or focus path family
- the mutation class
- the operator action, if any
- the expected propagation across dependent and independent paths

Example:

```yaml
name: safe-change-on-a-propagates-through-chain
base: fullMigrationWithTraffic.wf.yaml

focus: capture-proxy

runs:
  - name: baseline
    expect:
      completed: [capture-proxy, snap1, migration1, rfs-independent]

  - name: mutate-a-safe
    mutate:
      - useMutator: traffic.proxies.capture-proxy.proxyConfig.noCapture
        class: safe
    expect:
      reran: [capture-proxy, snap1, migration1]
      blockedOn:
        snap1: [capture-proxy]
        migration1: [snap1]
      unchanged: [rfs-independent]
```

Example with approval:

```yaml
name: gated-change-on-b-requires-approval
base: fullMigrationWithTraffic.wf.yaml

focus: capture-proxy

runs:
  - name: baseline
    expect:
      completed: [capture-proxy, snap1, migration1]

  - name: mutate-b-gated
    mutate:
      - useMutator: snapshot.someGatedField
        class: gated
    action:
      type: approve
    expect:
      suspended: [snap1]
      blockedOn:
        migration1: [snap1]
      reranAfterAction: [snap1, migration1]
      unchanged: [capture-proxy]
```

Example with delete to handle an impossible change:

```yaml
name: impossible-change-on-b-requires-delete
base: fullMigrationWithTraffic.wf.yaml

focus: capture-proxy

runs:
  - name: baseline
    expect:
      completed: [capture-proxy, snap1, migration1]

  - name: mutate-b-impossible
    mutate:
      - useMutator: snapshot.someImpossibleField
        class: impossible
    action:
      type: delete
      targets: [snap1]
    expect:
      failedOrBlockedBeforeAction: [snap1]
      blockedOn:
        migration1: [snap1]
      reranAfterAction: [snap1, migration1]
      unchanged: [capture-proxy]
```

### What We Assert For Each Behavior

#### Standard Baseline Validation: No-Op Resubmit

Every scenario should include a no-op resubmit after the initial baseline run and before any mutations. This validates that the checksum matching and skip logic work at all:

```yaml
  - name: no-op-resubmit
    # No mutations — identical config resubmitted
    expect:
      skipped: [capture-proxy, replay1, snap1, migration1]
```

If this run fails, the checksum computation or CRD stamping is broken and no subsequent mutation test can be trusted. This is a cheap sanity check that should be the first thing to pass.

#### Safe Change

For a `safe` change on component `X`:

- `X` reruns
- each downstream dependent path reruns in dependency order
- unrelated paths remain unchanged

#### Gated Change

For a `gated` change on component `X`:

- `X` reaches a suspended/gated state before approval
- all downstream dependents remain blocked while `X` is suspended
- after approval, `X` reruns
- downstream dependents rerun in dependency order
- unrelated paths remain unchanged

#### Impossible Change

For an `impossible` change on component `X`:

- The inner workflow submits the change via `kubectl apply`
- The VAP rejects it (or the step cannot proceed because the existing resource is incompatible with the requested update)
- The inner workflow cannot make progress while the old resource still exists
- Downstream dependents remain blocked while `X` is unresolved
- The outer test workflow (or operator) deletes the underlying CR to clear the obstacle
- The outer test workflow then retries or resubmits the same logical change
- On retry, the apply succeeds by creating the resource fresh, and the workflow proceeds
- Downstream dependents rerun in dependency order
- Unrelated paths remain unchanged

#### Observing "Blocked" — Before/After Action Split

For gated and impossible flows, the outer workflow has an explicit action step (approve/delete) between two observation points. This gives a natural proof of blocking:

```
run-N-configure → run-N-wait-for-block → run-N-assert-before-action
                                        → run-N-action (approve/delete)
                                        → run-N-wait → run-N-assert-after-action
```

The **assert-before-action** step reads CRs and confirms:
- The target component has not progressed (phase/checksum unchanged from prior run)
- Downstream dependents have not progressed

The **assert-after-action** step confirms:
- The target component completed with the expected new checksum
- Downstream dependents completed with their expected new checksums
- Unrelated components remain unchanged

This two-phase observation is the proof of blocking. The before-action snapshot shows the system was stuck; the after-action snapshot shows it unstuck after the intervention.

For safe cascades (no action step), "blocked" means "waited for" — the proof is ordering: the downstream's CR was updated after the upstream's. The observation record timestamps handle this.

### Chain Propagation Cases To Generate

Given a derived chain `focus -> immediate-dependent -> transitive-dependent`, the framework should support generating at least these breadth-first cases:

1. `focus-change`
   expect focus, then all downstream dependents to rerun; independent paths unchanged

2. `immediate-dependent-change`
   expect focus unchanged; immediate and lower dependents rerun; independent paths unchanged

3. `transitive-dependent-change`
   expect only the transitive dependent reruns; everything above it unchanged; independent paths unchanged

4. `focus-gated-change`
   expect downstream dependents blocked until approval; independent paths unchanged

5. `immediate-dependent-impossible-change`
   expect lower dependents blocked until delete action resolves the impossible change; focus unchanged; independent paths unchanged

This is a better first-cut matrix than "one case per field".

### What A Test Author Actually Provides

For a new test, a developer writes only two things:

1. An approved mutator entry
2. A scenario spec

Everything else is derived by the test framework.

### First-Cut Scope

The first implementation should intentionally prove the framework on the smallest useful case before tackling the full design.

The first cut should include only:

- one baseline config
- one focus component
- one simple matrix definition
- one change class: `safe`
- two or three concrete reviewed mutators
- one no-op resubmit sanity check
- one visible structured report
- one intentional failure mode that can be observed in the report

The first cut should not yet require:

- approval flow automation
- delete/recreate automation
- full mutator coverage accounting across many components
- multi-focus scenarios
- broad scenario packs

The goal is to see:

- how expansion works
- how a generated test is named
- how an observation is recorded
- how a failure is rendered in the report
- how teardown behaves

#### 1. Approved Mutator Entry

An approved mutator is the concrete, reviewed way to change one field while staying in a valid input band.

A mutator should declare:

- the config path it changes
- the replacement logic
- the reason this is a valid mutation for testing
- optional tags that help scenario expansion select it

Example:

```typescript
export const approvedMutators: Record<string, ApprovedMutator> = {
  "traffic.proxies.capture-proxy.proxyConfig.podReplicas": {
    path: "traffic.proxies.capture-proxy.proxyConfig.podReplicas",
    apply: cfg => set(cfg, "traffic.proxies.capture-proxy.proxyConfig.podReplicas", 2),
    rationale: "safe operational proxy scaling change",
    tags: ["proxy", "operational"]
  },
  "traffic.proxies.capture-proxy.proxyConfig.noCapture": {
    path: "traffic.proxies.capture-proxy.proxyConfig.noCapture",
    apply: cfg => set(cfg, "traffic.proxies.capture-proxy.proxyConfig.noCapture", true),
    rationale: "material proxy behavior change that should cascade to replay",
    tags: ["proxy", "material", "replayer-dependent"]
  }
};
```

Author rule:

- if you cannot state a concrete valid value confidently, do not add the mutator yet

#### 2. Scenario Spec

A scenario spec says:

- which baseline config to start from
- which runs happen in order
- which mutator or explicit mutation each run applies
- what behavior is expected after each run

There are two supported authoring modes.

Mode A: hand-written runs

```yaml
name: proxy-podreplicas-skip
base: fullMigrationWithTraffic.wf.yaml

runs:
  - name: baseline
    expect:
      completed: [capture-proxy, replay1, snap1]

  - name: change-podreplicas
    mutate:
      - useMutator: traffic.proxies.capture-proxy.proxyConfig.podReplicas
    expect:
      reran: [capture-proxy]
      skipped: [replay1, snap1]
```

Mode B: matrix expansion across reviewed mutators and derived dependency patterns

```yaml
name: chain-behavior-matrix
base: fullMigrationWithTraffic.wf.yaml

matrix:
  - focus: capture-proxy
    select:
      - changeClass: safe
        patterns: [focus-change, immediate-dependent-change, transitive-dependent-change]
      - changeClass: gated
        patterns: [focus-gated-change, immediate-dependent-gated-change]
      - changeClass: impossible
        patterns: [immediate-dependent-impossible-change]
```

Author rule:

- use hand-written runs when sequence matters
- use matrix expansion when the behavior pattern is repeated and each selected pattern can be bound to a reviewed mutator

### End-to-End Author Workflow

When adding a new orchestration test, the developer workflow is:

1. Pick the baseline config file.
2. Choose the focus component and behavior class to test.
3. Add or reuse an approved mutator for the concrete field that will represent that behavior.
4. Write a scenario spec that references that mutator, or a matrix that references the derived dependency pattern plus behavior class.
5. Run local validation:
   the mutated config still validates
   the config processor succeeds
   the checksum reporter succeeds
   the workflow templates render
6. Submit the outer test workflow and inspect the resulting report.

### What "Small But Real" Looks Like

A good first implementation target is:

- focus: `capture-proxy`
- patterns:
  - `focus-change`
  - `immediate-dependent-change`
- change class:
  - `safe`

Concrete generated tests:

- `capture-proxy-chain-behaviors/focus-change/safe/noCapture`
- `capture-proxy-chain-behaviors/immediate-dependent-change/safe/snapshot.someSafeField`

And one intentionally failing variant, for example:

- expected `migration1` to stay unchanged when the framework knows it should rerun

That gives a visible pass case and a visible fail case before scaling up the design.

### What The Framework Derives Automatically

The developer does not need to hand-author:

- CRD names
- expected checksums
- checksum dependency edges
- inner workflow names
- state handoff ConfigMap names
- teardown ordering

Those are derived from the config processor output, checksum report, and scenario/run identity.

### Framework Contracts That Must Exist Before Expansion

Before matrix expansion is implemented, the framework must provide these concrete contracts:

1. A checksum-report API
   Input: baseline or mutated config
   Output: component checksums plus dependency relationships

2. A derived-subgraph API
   Input: checksum report plus focus component
   Output: focus, immediate dependents, transitive dependents, independent components

3. A mutator binding API
   Input: pattern + change class + focus + approved mutator registry
   Output: concrete mutator candidates

4. A report schema
   Input: observations from configure/wait/assert steps
   Output: stable machine-readable result document

Without those contracts, another agent will end up inventing interfaces during implementation.

### Minimal Example

To add a new test for "changing proxy `podReplicas` should rerun only the proxy":

1. Add or reuse:

```typescript
"traffic.proxies.capture-proxy.proxyConfig.podReplicas": {
  path: "traffic.proxies.capture-proxy.proxyConfig.podReplicas",
  apply: cfg => set(cfg, "traffic.proxies.capture-proxy.proxyConfig.podReplicas", 2),
  rationale: "safe operational proxy scaling change"
}
```

2. Add scenario:

```yaml
name: proxy-podreplicas-skip
base: fullMigrationWithTraffic.wf.yaml
runs:
  - name: baseline
    expect:
      completed: [capture-proxy, replay1, snap1]
  - name: mutate-podreplicas
    mutate:
      - useMutator: traffic.proxies.capture-proxy.proxyConfig.podReplicas
    expect:
      reran: [capture-proxy]
      skipped: [replay1, snap1]
```

3. The framework then does the rest:

- computes the mutated config
- computes expected checksums
- renders and submits the inner workflow
- records prior-run observations in a ConfigMap
- compares expected vs observed resource state
- tears everything down at the end

### Manual Mutators Only

Mutations are always hand-written. There is no generic "generate a new value for this field type" mechanism in the first cut.

Each mutator must:

- target a specific path
- provide a concrete replacement value
- stay within a known-valid band for that field
- be reviewed as part of the scenario

That gives us interchangeability without guessing. Over time, these mutators can be typed explicitly, but the first implementation should treat them as curated test fixtures.

### Schema-Guided Expansion, Not Schema-Driven Value Synthesis

Schema annotations are still useful, but only for selecting which fields belong in which test category. They should not be used to invent values.

The scenario compiler may expand:

- "all reviewed mutators that represent a safe focus change"
- "all reviewed mutators that represent a gated immediate-dependent change"
- "all reviewed mutators that represent an impossible change on a component that has downstream dependents"

But each selected field must map to an explicit mutator from a library of reviewed mutations.

If a field has no approved mutator, it is not eligible for matrix expansion.

#### What "Only When Every Selected Field Already Has An Approved Mutator" Means

The matrix selector chooses a set of candidate fields.

Example candidate set from schema metadata:

- `traffic.proxies.capture-proxy.proxyConfig.noCapture`
- `traffic.proxies.capture-proxy.proxyConfig.setHeader`
- `traffic.proxies.capture-proxy.proxyConfig.suppressCaptureForMethod`

If the approved mutator registry contains entries for:

- `noCapture`
- `setHeader`

but not for:

- `suppressCaptureForMethod`

then the framework has two possible policies:

1. **Strict mode**
   the matrix definition is rejected because not every selected field is backed by a reviewed mutator

2. **Explicit partial mode**
   the framework expands only the eligible fields and reports the missing ones as uncovered

For the first cut, strict mode is preferable unless the scenario explicitly opts into partial expansion. That keeps the generated test set predictable.

Example:

```yaml
name: proxy-material-matrix
base: fullMigrationWithTraffic.wf.yaml

matrix:
  - component: traffic.proxies.capture-proxy.proxyConfig
    schema: USER_PROXY_PROCESS_OPTIONS
    select:
      - category: each-material
        dependency: replayer
        requireFullCoverage: true
        expect:
          reran: [capture-proxy, replay1]
          skipped: [snap1]
```

If even one selected field has no approved mutator, this scenario does not compile.

If instead the author writes:

```yaml
name: proxy-material-matrix
base: fullMigrationWithTraffic.wf.yaml

matrix:
  - component: traffic.proxies.capture-proxy.proxyConfig
    schema: USER_PROXY_PROCESS_OPTIONS
    select:
      - category: each-material
        dependency: replayer
        requireFullCoverage: false
        expect:
          reran: [capture-proxy, replay1]
          skipped: [snap1]
```

then the framework may expand only fields with approved mutators, but the final report must include the fields that were skipped because no reviewed mutator existed.

#### Behavior-Oriented Matrix Template

```yaml
name: chain-behavior-matrix
base: fullMigrationWithTraffic.wf.yaml

matrix:
  - focus: capture-proxy
    select:
      - changeClass: safe
        patterns: [focus-change, immediate-dependent-change, transitive-dependent-change]
        expect:
          propagation: downstream-only
          independentPathsStayUnchanged: true

      - changeClass: gated
        patterns: [focus-gated-change, immediate-dependent-gated-change]
        action: approve
        expect:
          blocksDependentsUntilAction: true
          independentPathsStayUnchanged: true

      - changeClass: impossible
        patterns: [immediate-dependent-impossible-change]
        action: delete
        expect:
          blocksDependentsUntilAction: true
          independentPathsStayUnchanged: true
```

#### What the Driver Produces

Given:

- a focus component
- a derived dependency subgraph
- approved mutators tagged by behavior class
- a mapping from dependency patterns to concrete components

the driver generates concrete tests such as:

```
chain-behavior-matrix/focus-change/safe/noCapture
chain-behavior-matrix/focus-change/safe/setHeader
chain-behavior-matrix/immediate-dependent-change/safe/snapshotSafeField
chain-behavior-matrix/transitive-dependent-change/safe/migrationSafeField
chain-behavior-matrix/focus-gated-change/enableMSKAuth
chain-behavior-matrix/immediate-dependent-gated-change/snapshotGatedField
chain-behavior-matrix/immediate-dependent-impossible-change/snapshotImpossibleField
```

Each generated test still becomes a concrete sequence of runs, but the expectations are derived from topology plus behavior class rather than just field identity.

#### Mutator Registry

Instead of value generation, the driver resolves a reviewed mutator:

```typescript
type ApprovedMutator = {
  path: string;
  apply: (baseConfig: unknown) => unknown;
  rationale: string;
};

const approvedMutators: Record<string, ApprovedMutator> = {
  "traffic.proxies.capture-proxy.proxyConfig.podReplicas": {
    path: "traffic.proxies.capture-proxy.proxyConfig.podReplicas",
    apply: cfg => set(cfg, path, 2),
    rationale: "safe operational scale change"
  },
  "traffic.proxies.capture-proxy.proxyConfig.noCapture": {
    path: "traffic.proxies.capture-proxy.proxyConfig.noCapture",
    apply: cfg => set(cfg, path, true),
    rationale: "material downstream-affecting proxy behavior change"
  }
};
```

#### Concrete Matrix Example

Suppose the approved mutator registry contains:

```typescript
const approvedMutators: Record<string, ApprovedMutator> = {
  "traffic.proxies.capture-proxy.proxyConfig.noCapture": {
    path: "traffic.proxies.capture-proxy.proxyConfig.noCapture",
    apply: cfg => set(cfg, "traffic.proxies.capture-proxy.proxyConfig.noCapture", true),
    rationale: "safe focus change that should propagate downstream",
    tags: ["safe", "focus-change"]
  },
  "traffic.proxies.capture-proxy.proxyConfig.enableMSKAuth": {
    path: "traffic.proxies.capture-proxy.proxyConfig.enableMSKAuth",
    apply: cfg => set(cfg, "traffic.proxies.capture-proxy.proxyConfig.enableMSKAuth", true),
    rationale: "gated focus change requiring approval",
    tags: ["gated", "focus-gated-change"]
  },
  "snapshot.someSafeField": {
    path: "snapshotMigrationConfigs[0].perSnapshotConfig.snap1.someSafeField",
    apply: cfg => set(cfg, "snapshotMigrationConfigs[0].perSnapshotConfig.snap1.someSafeField", "value-b"),
    rationale: "safe immediate dependent change",
    tags: ["safe", "immediate-dependent-change"]
  },
  "snapshot.someImpossibleField": {
    path: "snapshotMigrationConfigs[0].perSnapshotConfig.snap1.someImpossibleField",
    apply: cfg => set(cfg, "snapshotMigrationConfigs[0].perSnapshotConfig.snap1.someImpossibleField", "value-c"),
    rationale: "impossible immediate dependent change requiring delete",
    tags: ["impossible", "immediate-dependent-impossible-change"]
  },
  "migration.someSafeField": {
    path: "snapshotMigrationConfigs[0].perSnapshotConfig.snap1.migrations[0].documentBackfillConfig.podReplicas",
    apply: cfg => set(cfg, "snapshotMigrationConfigs[0].perSnapshotConfig.snap1.migrations[0].documentBackfillConfig.podReplicas", 2),
    rationale: "safe transitive dependent change",
    tags: ["safe", "transitive-dependent-change"]
  }
};
```

and the derived dependency subgraph says:

```text
focus = capture-proxy
immediate dependent = snap1
transitive dependent = migration1
independent branch = rfs-independent
```

The following matrix:

```yaml
name: chain-behavior-matrix
base: fullMigrationWithTraffic.wf.yaml

matrix:
  - focus: capture-proxy
    select:
      - changeClass: safe
        patterns: [focus-change, immediate-dependent-change, transitive-dependent-change]
        requireFullCoverage: true
        expect:
          propagation: downstream-only
          independentPathsStayUnchanged: true

      - changeClass: gated
        patterns: [focus-gated-change]
        action: approve
        requireFullCoverage: false
        expect:
          blocksDependentsUntilAction: true
          independentPathsStayUnchanged: true

      - changeClass: impossible
        patterns: [immediate-dependent-impossible-change]
        action: delete
        requireFullCoverage: true
        expect:
          blocksDependentsUntilAction: true
          independentPathsStayUnchanged: true
```

would expand to:

```text
chain-behavior-matrix/focus-change/safe/noCapture
  run-0 baseline
  run-1 apply mutator traffic.proxies.capture-proxy.proxyConfig.noCapture
  expect reran:   [capture-proxy, snap1, migration1]
  expect blocked: snap1 waits for capture-proxy, migration1 waits for snap1
  expect unchanged: [rfs-independent]

chain-behavior-matrix/immediate-dependent-change/safe/snapshot.someSafeField
  run-0 baseline
  run-1 apply mutator snapshot.someSafeField
  expect reran:   [snap1, migration1]
  expect unchanged: [capture-proxy, rfs-independent]

chain-behavior-matrix/transitive-dependent-change/safe/migration.someSafeField
  run-0 baseline
  run-1 apply mutator migration.someSafeField
  expect reran:   [migration1]
  expect unchanged: [capture-proxy, snap1, rfs-independent]

chain-behavior-matrix/focus-gated-change/enableMSKAuth
  run-0 baseline
  run-1 apply mutator traffic.proxies.capture-proxy.proxyConfig.enableMSKAuth
  expect suspended: [capture-proxy]
  expect blocked: [snap1, migration1]
  action: approve
  expect reran after action: [capture-proxy, snap1, migration1]
  expect unchanged: [rfs-independent]

chain-behavior-matrix/immediate-dependent-impossible-change/snapshot.someImpossibleField
  run-0 baseline
  run-1 apply mutator snapshot.someImpossibleField
  expect unresolved before action: [snap1]
  expect blocked: [migration1]
  action: delete snap1
  expect reran after action: [snap1, migration1]
  expect unchanged: [capture-proxy, rfs-independent]
```

#### What Gets Projected Besides Mutations

The matrix does not just pick mutation paths. It also projects:

- the dependency pattern under test (`focus-change`, `immediate-dependent-change`, etc.)
- the behavior class (`safe`, `gated`, `impossible`)
- the handling action (`approve`, `delete`, or none)
- the expectation template attached to the selector
- the scenario/run naming
- the report labels and coverage bookkeeping

So one selector expands into multiple concrete tests, each with:

- one reviewed mutator
- one dependency pattern
- one behavior class
- one expected propagation pattern
- one checksum report
- one observation handoff chain

#### Resulting Report Shape

For the matrix above, the report should include both execution results and coverage results.

Example:

```json
{
  "scenario": "chain-behavior-matrix",
  "status": "Succeeded",
  "expandedTests": [
    {
      "name": "chain-behavior-matrix/focus-change/safe/noCapture",
      "mutator": "traffic.proxies.capture-proxy.proxyConfig.noCapture",
      "status": "Succeeded",
      "changeClass": "safe",
      "pattern": "focus-change",
      "expect": {
        "reran": ["capture-proxy", "snap1", "migration1"],
        "unchanged": ["rfs-independent"],
        "blockedOn": {
          "snap1": ["capture-proxy"],
          "migration1": ["snap1"]
        }
      },
      "observed": {
        "capture-proxy": {"phase": "Ready", "changed": true},
        "snap1": {"phase": "Completed", "changed": true},
        "migration1": {"phase": "Completed", "changed": true},
        "rfs-independent": {"phase": "Completed", "changed": false}
      }
    },
    {
      "name": "chain-behavior-matrix/immediate-dependent-impossible-change/snapshot.someImpossibleField",
      "mutator": "snapshot.someImpossibleField",
      "status": "Succeeded",
      "changeClass": "impossible",
      "pattern": "immediate-dependent-impossible-change",
      "action": "delete",
      "expect": {
        "unresolvedBeforeAction": ["snap1"],
        "blocked": ["migration1"],
        "reranAfterAction": ["snap1", "migration1"],
        "unchanged": ["capture-proxy", "rfs-independent"]
      },
      "observed": {
        "beforeAction": {
          "snap1": {"phase": "Blocked"},
          "migration1": {"phase": "Pending"}
        },
        "afterAction": {
          "capture-proxy": {"phase": "Ready", "changed": false},
          "snap1": {"phase": "Completed", "changed": true},
          "migration1": {"phase": "Completed", "changed": true},
          "rfs-independent": {"phase": "Completed", "changed": false}
        }
      }
    }
  ],
  "coverage": {
    "selectedCases": [
      "focus-change/safe",
      "immediate-dependent-change/safe",
      "transitive-dependent-change/safe",
      "focus-gated-change",
      "immediate-dependent-impossible-change"
    ],
    "expandedCases": [
      "focus-change/safe/noCapture",
      "immediate-dependent-change/safe/snapshot.someSafeField",
      "transitive-dependent-change/safe/migration.someSafeField",
      "focus-gated-change/enableMSKAuth",
      "immediate-dependent-impossible-change/snapshot.someImpossibleField"
    ],
    "uncoveredCases": []
  }
}
```

What the developer should expect to see:

- one concrete expanded test per eligible dependency-pattern-plus-behavior case
- the exact mutator id used for each expanded test
- the expected propagation/blocked/unchanged behavior echoed back in the report
- observed changed/unchanged status for each named component
- for gated or impossible cases, before-action and after-action observations
- a coverage section showing which selected cases were exercised and which are still missing

#### Detailed End-to-End Example

Author input:

```yaml
name: capture-proxy-chain-behaviors
base: fullMigrationWithTraffic.wf.yaml

matrix:
  - focus: capture-proxy
    select:
      - changeClass: safe
        patterns: [focus-change, immediate-dependent-change, transitive-dependent-change]
        requireFullCoverage: true
      - changeClass: gated
        patterns: [focus-gated-change]
        action: approve
        requireFullCoverage: false
      - changeClass: impossible
        patterns: [immediate-dependent-impossible-change]
        action: delete
        requireFullCoverage: true
```

Framework derivation:

1. Transform the base config and compute checksum report.
2. Derive the dependency subgraph rooted at `capture-proxy`.
3. Resolve:
   `focus-change` -> `capture-proxy`
   `immediate-dependent-change` -> `snap1`
   `transitive-dependent-change` -> `migration1`
   `independent` -> `rfs-independent`
4. Match each requested pattern and change class to an approved mutator.
5. Generate one concrete scenario per successful match.

Concrete generated tests:

```text
capture-proxy-chain-behaviors/focus-change/safe/noCapture
capture-proxy-chain-behaviors/immediate-dependent-change/safe/snapshot.someSafeField
capture-proxy-chain-behaviors/transitive-dependent-change/safe/migration.someSafeField
capture-proxy-chain-behaviors/focus-gated-change/enableMSKAuth
capture-proxy-chain-behaviors/immediate-dependent-impossible-change/snapshot.someImpossibleField
```

What the author should expect in the report:

- a top-level scenario status
- a list of generated concrete tests
- for each concrete test:
  - focus
  - pattern
  - change class
  - mutator used
  - expected behavior
  - observed behavior
- a coverage section saying whether every requested pattern was exercised

Representative report excerpt:

```json
{
  "scenario": "capture-proxy-chain-behaviors",
  "status": "Succeeded",
  "summary": {
    "generated": 5,
    "passed": 5,
    "failed": 0
  },
  "expandedTests": [
    {
      "name": "capture-proxy-chain-behaviors/focus-change/safe/noCapture",
      "focus": "capture-proxy",
      "pattern": "focus-change",
      "changeClass": "safe",
      "mutator": "traffic.proxies.capture-proxy.proxyConfig.noCapture",
      "expect": {
        "reran": ["capture-proxy", "snap1", "migration1"],
        "unchanged": ["rfs-independent"]
      },
      "observed": {
        "capture-proxy": {"changed": true},
        "snap1": {"changed": true},
        "migration1": {"changed": true},
        "rfs-independent": {"changed": false}
      }
    }
  ]
}
```

### Constraints for Safe Expansion

The expansion engine must reject a candidate field unless all of the following hold:

- the field is selected by schema metadata
- the field has a reviewed mutator
- the mutated config still validates against the schema
- the config processor succeeds
- the template renderer succeeds
- the scenario compiler can resolve the affected component names to concrete resources

If any of those fail, the scenario is not generated.

### Hand-Written Scenarios Still Exist

The matrix approach covers "does each field cascade correctly?" but some scenarios need hand-written sequencing:

```yaml
name: multi-step-cascade
base: fullMigrationWithTraffic.wf.yaml

runs:
  # Run 1: baseline
  - name: initial
    expect:
      completed: [capture-proxy, replay1, snap1]

  # Run 2: operational change + material change in same submission
  - name: mixed-change
    mutate:
      - path: traffic.proxies.capture-proxy.proxyConfig.podReplicas
        value: 5
      - path: traffic.proxies.capture-proxy.proxyConfig.noCapture
        value: true
    expect:
      reran: [capture-proxy, replay1]
      skipped: [snap1]
      waited-for: { replay1: [capture-proxy] }

  # Run 3: revert the material change, keep the operational one
  - name: revert-material
    mutate:
      - path: traffic.proxies.capture-proxy.proxyConfig.noCapture
        value: false
    expect:
      reran: [capture-proxy, replay1]
      skipped: [snap1]
```

These test multi-step sequences, reverts, and combined mutations that the matrix can't express.

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                 Scenario Specs (YAML)                        │
│  ┌─────────────────┐  ┌──────────────────────────────────┐  │
│  │ Hand-written     │  │ Matrix templates                 │  │
│  │ (multi-step      │  │ (schema-driven combinatorial     │  │
│  │  sequences)      │  │  expansion)                      │  │
│  └────────┬────────┘  └──────────────┬───────────────────┘  │
└───────────┼──────────────────────────┼───────────────────────┘
            │                          │
            ▼                          ▼
┌──────────────────────────────────────────────────────────────┐
│              Scenario Compiler (TypeScript)                   │
│                                                              │
│  1. Parse scenario YAML                                      │
│  2. For matrix specs: read schema .meta() annotations,       │
│     expand only across fields that have approved mutators     │
│  3. For each concrete scenario:                              │
│     a. Build outer Argo workflow using TS builders            │
│     b. Each run → [configure, wait, assert] step group       │
│     c. Assert steps are container tasks running validation    │
│  4. Output: outer workflow YAML ready to submit               │
│                                                              │
│  Uses: config-processor, template-renderer, schema metadata  │
└──────────────────────┬───────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────┐
│              Outer Argo Workflow (the test)                   │
│                                                              │
│  run-0-configure ──→ run-0-wait ──→ run-0-assert             │
│                                          │                   │
│  run-1-configure ──→ run-1-wait ──→ run-1-assert             │
│                                          │                   │
│  ...                                     ▼                   │
│                                    final-report              │
└──────────────────────────────────────────────────────────────┘
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
   ┌────────────┐ ┌─────────┐ ┌──────────┐
   │ Config     │ │ Template│ │ Inner    │
   │ Processor  │ │ Renderer│ │ Migration│
   │            │ │         │ │ Workflow │
   └────────────┘ └─────────┘ └──────────┘
```

### Assert Steps

Each assert step is a container that runs a validation program. It receives:

- current scenario/run identity
- expected behavior
- current expected checksum report
- prior observation ConfigMap contents

The assert implementation should primarily validate CR state and checksum observations, and use workflow-node inspection only as secondary diagnostics.

Pseudo-code:

```bash
for each expected component:
  CURRENT = read live resource state
  PRIOR   = read prior observation from ConfigMap
  EXPECT  = read expected checksum from checksum report

  assert CURRENT.phase matches expected terminal state
  assert CURRENT.configChecksum matches EXPECT.configChecksum

  if expectation == reran:
    assert CURRENT.configChecksum != PRIOR.configChecksum

  if expectation == skipped:
    assert CURRENT.configChecksum == PRIOR.configChecksum
```

A TypeScript validation container is the preferred implementation. It should take structured JSON expectations and produce structured JSON observations plus pass/fail output.

### Configure Steps

Each configure step:
1. Takes the mutated `wf.yaml` config as a parameter
2. Runs the config processor to produce the workflow config
3. Runs checksum inspection to produce the expected checksum report for this run
4. Runs the template renderer to produce the inner Argo workflow YAML
5. Deletes any existing inner workflow (`argo delete`)
6. Submits the new inner workflow (`argo submit`)
7. Writes the checksum report to a scenario-scoped ConfigMap
8. Outputs the inner workflow name for the wait step

This is the `workflow configure` operation — delete + submit as a single logical step.

#### Compile-Time vs Runtime Mutation

All mutations are applied at compile time by the scenario compiler. The compiler:

1. Reads the scenario YAML
2. Applies each run's mutator to produce the fully materialized `wf.yaml`
3. Runs the config processor on each materialized config
4. Extracts the checksum report
5. Renders the inner workflow YAML
6. Bakes all of the above into the outer workflow as parameters

At runtime, the configure step just reads its pre-computed parameters and submits. No TypeScript, no mutator code, no config processor needed in the container — just `kubectl`/`argo` CLI.

This means the outer workflow is a self-contained, inspectable artifact. You can review exactly what it will do before submitting it.

#### On-Demand Generation

The scenario compiler is a TypeScript CLI that generates outer workflow YAML on demand:

```bash
# List available test cases
e2e-gen list --tier=fast

# Generate and submit one test
e2e-gen render proxy-field-cascade/focus-change/safe/noCapture | argo submit -

# Generate and submit a filtered set
e2e-gen list --tier=fast | xargs -I{} sh -c 'e2e-gen render {} | argo submit -'
```

The generator is deterministic — the same commit always produces the same workflow for the same test case ID. There is no need to pre-materialize all workflows to disk. CI calls the generator for the selected tier and submits the results. For large matrix expansions, the generator can produce workflows one at a time, keeping memory and disk usage constant regardless of the total number of test cases.

### Wait Steps

A resource template that watches the inner workflow until it reaches a terminal phase:

```yaml
resource:
  action: get
  manifest: |
    apiVersion: argoproj.io/v1alpha1
    kind: Workflow
    metadata:
      name: "{{inputs.parameters.innerWorkflowName}}"
  successCondition: "status.phase == Succeeded"
  failureCondition: "status.phase in (Failed, Error)"
```

### Injection Steps (for gated and impossible changes)

For scenarios that test gated or impossible changes, the outer workflow includes injection steps between configure and wait:

#### Gated Change Flow

```
run-N-configure → run-N-wait-for-suspend → run-N-assert-before → run-N-approve → run-N-wait → run-N-assert-after
```

The `wait-for-suspend` step watches the inner workflow for a suspended node. The `assert-before` step confirms downstream components haven't progressed. The `approve` step patches the CRD with the approval annotation and resumes the inner workflow.

#### Impossible Change Flow

```
run-N-configure → run-N-wait-for-failure → run-N-assert-before → run-N-delete-resource → run-N-approve → run-N-wait → run-N-assert-after
```

1. The inner workflow submits the impossible change; the VAP rejects it
2. The inner workflow's step fails and the workflow waits for operator intervention
3. `assert-before` confirms the component and its downstream dependents haven't progressed
4. `delete-resource` deletes the underlying CR (e.g. `kubectl delete CapturedTraffic snap1`)
5. `approve` resumes the failed step (by name derived from resource labels), triggering the inner workflow to loop back and retry
6. On retry, the apply succeeds (creating the resource fresh) and the workflow proceeds
7. `assert-after` confirms the component and downstream dependents completed with expected checksums

## Component Name Resolution

Component names in the scenario spec are the config-level keys:
- `capture-proxy` → `traffic.proxies.capture-proxy` → CRD `CapturedTraffic/capture-proxy`
- `replay1` → `traffic.replayers.replay1` → CRD `TrafficReplay/capture-proxy-target-replay1`
- `snap1` → `snapshotMigrationConfigs[].perSnapshotConfig.snap1` → CRD `DataSnapshot/source-snap1`

The config processor already produces these CRD names. The scenario compiler reads the config processor output to build the mapping, so it stays in sync automatically.

## Isolation and Cleanup

For the first cut, every scenario runs with the same clean starting context.

Rules:

- one outer workflow active at a time
- one inner migration workflow active at a time
- one scenario namespace or session at a time
- all scenario-created CRDs, inner workflows, and scratch ConfigMaps are deleted during teardown

Recommended teardown order:

1. stop/delete the inner workflow
2. delete migration CRDs created by the scenario
3. delete approval / observation / checksum-report ConfigMaps created by the scenario
4. verify the namespace/session is empty before marking the outer test complete

If teardown fails, the outer workflow should fail loudly rather than allowing a contaminated next run.

### Failure Halts Everything

If any assert step fails, the outer workflow should fail immediately. There is no value in continuing subsequent runs after a failure — the CRD state is now in an unexpected condition and subsequent assertions would be meaningless. The outer workflow uses Argo's `failFast: true` semantics.

On failure, the observation ConfigMaps and CRD states are preserved for debugging. Teardown only runs on success.

## Open Questions

### 1. Assert container implementation

Options:
- **Shell script** — simple, uses `kubectl` and `jq` directly. Easy to debug but brittle.
- **TypeScript container** — structured input/output, reusable validation logic, better error messages. Requires building and publishing a container image.
- **Migration console container** — already has `kubectl` and `argo` CLI. Could add a validation command.

Recommendation: start with a TypeScript container that takes JSON expectations and produces JSON results. It can be built from the same monorepo and shares types with the scenario compiler.

### 2. Where do real source/target clusters come from?

The inner migration workflow needs source and target clusters. Options:
- **Pre-provision** before the outer workflow starts (a setup step in the outer workflow)
- **Use the existing `full-migration-with-clusters` WorkflowTemplate** which creates clusters as part of the first run
- **Imported clusters** — point at pre-existing clusters

### 3. First run duration

The first run deploys everything from scratch and takes significantly longer than subsequent runs. Should the outer workflow have a longer timeout for run-0?

### 4. Matrix explosion control

A full matrix over all proxy fields × all replayer fields × all RFS fields could produce hundreds of test runs. Controls needed:
- **Tier labels** — `fast` (small reviewed mutator set), `full` (all reviewed mutators)
- **Component scoping** — run the matrix for one component at a time
- **CI budget** — fast tier in PR checks, full tier in nightly
- **Mutator coverage accounting** — explicitly track which eligible fields do not yet have approved mutators

### 5. Inner workflow naming

Each inner workflow submission needs a deterministic name (for the wait step to reference). Options:
- Fixed name per scenario (e.g. `test-migration-<scenario-name>`) — simple, but only one scenario can run at a time
- Generated name with the run index (e.g. `test-migration-<scenario>-run-<n>`) — but then we need to delete the prior run's workflow

For the first cut, keep resource names inside the migration config fixed. Those names are part of the behavior under test and should not be randomized.

For the inner Argo workflow object itself, generated names with the run index are preferred:

- `test-migration-<scenario>-run-0`
- `test-migration-<scenario>-run-1`

This avoids races around delete/recreate of the Workflow CR while preserving stable resource names for proxies, snapshots, and replayers.

The outer workflow already knows which run it is waiting on, so there is no need to reuse the same Workflow CR name.

### 6. CI integration

The outer workflow is just another Argo workflow. CI submits it and checks the final phase. This fits naturally into the existing Jenkins pipeline that already submits Argo workflows for integ tests.

### 7. Observability of inner workflow state from outer workflow

The assert step may need to inspect the inner workflow's timestamps and high-level state. Options:
- `argo get <inner> -o json` from within the assert container
- K8s API directly (the inner workflow is a CR in the same namespace)
- The inner workflow writes its own summary to a ConfigMap that the assert step reads

Recommendation: use `argo get -o json` or the K8s API for timestamps and high-level phase, but do not make detailed node-name matching the primary oracle.

## Phased Rollout

### Future Enhancement: Checksum Diff for Diagnostics

When a test fails, it can be hard to tell *why* a checksum changed (or didn't). A diff tool that takes two checksum reports (prior run vs current run) and shows exactly which fields and dependency checksums changed would speed up debugging:

```
capture-proxy: configChecksum      CHANGED  8de5a27a → 3f1c9b2e
               checksumForSnapshot UNCHANGED
               checksumForReplayer UNCHANGED
               cause: podReplicas 1 → 5 (operational only)
snap1:         configChecksum      UNCHANGED
replay1:       configChecksum      UNCHANGED
```

This is not needed for v1 assertions (which compare expected vs observed checksums directly), but would make the failure report much more actionable. It could be implemented as a mode of the `checksumReporter.ts` that takes two reports and produces a structured diff.

### Phase 1: Manual scenario, single component
- Hand-written scenario: proxy deploy → change podReplicas → assert skip
- Outer workflow built with TS builders
- Assert step is a TypeScript container
- ConfigMap-based state handoff implemented
- Teardown implemented and required for success
- Validates the full pipeline: scenario → compiler → outer workflow → inner workflow → assert

### Phase 2: Matrix expansion for one component
- Matrix template for proxy fields
- Driver reads schema annotations and expands only through reviewed mutators
- Validates: every proxy field cascades (or doesn't) as annotated

### Phase 3: Multi-component cascades
- Matrix templates for proxy→replayer and migration→replayer cascades
- Hand-written scenarios for multi-step sequences

### Phase 4: Gated change scenarios
- Injection steps for approval flow
- Matrix template for gated fields

## File Structure

```
orchestrationSpecs/
  packages/
    e2e-orchestration-tests/
      scenarios/
        proxy-field-cascade.matrix.yaml     # matrix template
        multi-step-cascade.scenario.yaml    # hand-written sequence
        gated-change.scenario.yaml          # approval flow
      src/
        scenarioCompiler.ts      # parse specs, expand matrices, build outer workflows
        matrixExpander.ts        # read schema .meta(), expand only approved mutators
        approvedMutators.ts      # curated mutation library
        assertContainer/         # TypeScript container for validation
          validate.ts            # takes JSON expectations, checks CRDs + workflow summaries
          Dockerfile
        componentResolver.ts     # config names → CRD names (from config processor output)
        checksumReporter.ts      # shared logic / wrapper around config-transform checksum inspection
        stateStore.ts            # ConfigMap read/write helpers for observation handoff
      package.json
```
