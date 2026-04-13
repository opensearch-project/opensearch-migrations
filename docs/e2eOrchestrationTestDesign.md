# E2E Orchestration Test Framework — Design

## Purpose

This framework exists to verify orchestration behavior across repeated workflow submissions with config changes between runs.

Its primary goals are:

- verify skip, re-run, wait, gate, and recovery behavior from real config and CRD state
- make complex orchestration scenarios cheap to author with a small local surface
- let a developer add a new case with a base config, a compact spec, and fixtures or mutators instead of edits across many packages

This is not a data-validation framework. Its core subject is orchestration and lifecycle behavior. It can, however, orchestrate data checks from elsewhere through fixtures, approval validations, setup hooks, and cleanup hooks.

We are not primarily testing traffic contents, snapshot contents, or migrated documents. We are testing:

- a component skips when its checksum matches the prior run
- a component re-runs when its relevant config changes
- downstream components wait for the expected upstream checksum
- unrelated branches remain unchanged
- gated changes suspend until approval is injected
- impossible in-place changes require delete-and-retry style recovery

## Current Architecture

The current implementation has four layers.

### 1. Planning Layer

The planning layer is pure TypeScript and is the most stable part of the system.

It includes:

- `checksumReporter`: derives a machine-readable component/checksum/dependency view from a user config
- `derivedSubgraph`: classifies components relative to a chosen focus
- `approvedMutators`: curated config mutations that stay within a reviewed valid band
- `matrixExpander`: turns a matrix selector into concrete test cases with expectations
- `assertLogic`: compares observed runtime state to expected checksums and phases

Current source of truth:

- [types.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/types.ts)
- [checksumReporter.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/checksumReporter.ts)
- [derivedSubgraph.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/derivedSubgraph.ts)
- [approvedMutators.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/approvedMutators.ts)
- [matrixExpander.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/matrixExpander.ts)
- [assertLogic.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/assertLogic.ts)

### 2. Authoring Layer

A developer should usually provide only:

- a baseline config
- a compact test spec
- referenced fixtures and approval validations
- reviewed mutators when a new mutation shape is needed

Today that authoring surface is centered on:

- [proxy-focus-change.test.json](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/specs/proxy-focus-change.test.json)
- [specLoader.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/specLoader.ts)
- [fixtures.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/fixtures.ts)
- [fixtureRegistry.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/fixtureRegistry.ts)

The intended authoring rule is simple:

- keep test intent local to the spec, fixtures, and mutators
- do not require scenario-specific edits across workflow templates or unrelated packages

### 3. Runtime Layer

There are currently two executors.

The live runner is the more complete runtime path today:

- [e2e-run.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/e2e-run.ts)

It uses the real user-facing workflow CLI path:

- `workflow configure edit --stdin`
- `workflow submit`
- `workflow approve`

The generated outer workflow is the long-term in-cluster executor:

- [buildOuterWorkflow.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/buildOuterWorkflow.ts)

It already models the same broad sequence, but it is still being hardened to match the live runner exactly, especially around approval-time validations and post-`#2462` lifecycle behavior.

### 4. Report Layer

The framework produces structured pass/fail output based on:

- expected checksums for the run
- observed CRD phase and checksum
- changed-vs-unchanged behavior relative to the previous run

Current report and assertion shapes are defined in:

- [types.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/types.ts)
- [reportSchema.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/reportSchema.ts)
- [assertLogic.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/assertLogic.ts)

## Execution Model Today

A concrete test case currently follows this model:

1. Load a baseline user config.
2. Build a checksum report from that config.
3. Choose a focus component.
4. Expand the matrix selector into one or more concrete cases using approved mutators.
5. For each case, run:
   - baseline submission
   - noop resubmission
   - mutated submission
6. Observe CRD state after each run.
7. Compare observed phase and checksum against the expected report.
8. Tear everything down.

For gated flows, the runtime may also:

- detect suspension points
- run validation fixtures while the inner workflow is suspended
- approve matching gates to continue

For impossible flows, the design expects an explicit delete-and-retry step, but that path should be finalized after `#2462` is merged and the lifecycle semantics are reconciled.

## Core Data Model

The framework should describe components in terms of the current `ChecksumReport` and `DerivedSubgraph` model, not in terms of handwritten graph declarations.

### Checksum Report

A checksum report is the planning-time view of the config:

- component identity
- resource name
- component checksum
- downstream checksum projections
- dependency edges

This is the basis for:

- expectation generation
- rerun vs unchanged classification
- runtime checksum assertions

### Derived Subgraph

Given a focus component, the framework derives:

- `focus`
- `immediateDependents`
- `transitiveDependents`
- `upstreamPrerequisites`
- `independent`

This is enough for the current behavior-oriented patterns without asking test authors to hand-specify a graph.

## Scenario Model

The current scenario model should stay behavior-first.

### Change Classes

Each approved mutator belongs to one change class:

- `safe`
- `gated`
- `impossible`

### Dependency Patterns

The current user-facing dependency patterns are:

- `focus-change`
- `immediate-dependent-change`
- `transitive-dependent-change`
- `focus-gated-change`
- `immediate-dependent-gated-change`
- `immediate-dependent-impossible-change`

These are selectors over the derived subgraph, not manually authored graph coordinates.

### What A Test Author Provides

For a normal new test, the author should provide:

1. a baseline config
2. a spec that chooses `focus` plus one or more matrix selectors
3. referenced fixtures for setup, cleanup, and approval-time validation
4. a new approved mutator only when an existing mutator does not cover the needed behavior

That is the intended long-term authoring contract.

## Quickstart

This is the smallest useful way to define and run a scenario today.

### 1. Write A Spec

A spec chooses a baseline config, a focus component, one or more matrix selectors, and named fixtures.

Example:

```json
{
  "baseConfig": "../../config-processor/scripts/samples/fullMigrationWithTraffic.wf.yaml",
  "matrix": {
    "focus": "proxy:capture-proxy",
    "select": [
      { "changeClass": "safe", "patterns": ["focus-change"] }
    ]
  },
  "fixtures": {
    "setup": [],
    "cleanup": ["delete-target-indices", "delete-source-snapshots"],
    "approvalGates": [
      {
        "approvePattern": "*.evaluateMetadata",
        "description": "After metadata evaluation",
        "validations": []
      },
      {
        "approvePattern": "*.migrateMetadata",
        "description": "After metadata migration",
        "validations": ["compare-indices"]
      },
      {
        "approvePattern": "*.documentBackfill",
        "description": "After document backfill",
        "validations": ["compare-indices"]
      }
    ]
  }
}
```

This is intentionally compact. The spec does not inline shell commands, rewrite templates, or hand-author a dependency graph.

### 2. Reference Named Fixtures

The spec references fixture names, not raw commands. The registry resolves them to concrete actions.

In the current package, those names come from [fixtures.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/fixtures.ts) and are resolved by [fixtureRegistry.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/fixtureRegistry.ts).

For the example above:

- `delete-target-indices` clears the target cluster before and after the scenario
- `delete-source-snapshots` removes test snapshots from the source cluster
- `compare-indices` checks index presence and doc counts while the workflow is suspended at an approval gate

That gives a developer a small, reusable vocabulary instead of per-test shell scripts.

### 3. Use Approved Mutators

The matrix selector does not directly describe how to edit the config. It selects from reviewed mutators.

Example current mutators:

- `proxy-numThreads`: safe `focus-change` on `proxy:capture-proxy`
- `proxy-noCapture-toggle`: gated `focus-gated-change`
- `replayer-speedupFactor`: safe `immediate-dependent-change`
- `rfs-maxConnections`: safe `transitive-dependent-change`

So for the example spec above:

- focus is `proxy:capture-proxy`
- selector is `safe + focus-change`
- the framework picks `proxy-numThreads`
- the framework applies that mutator to the baseline config
- the framework computes the mutated checksum report
- the framework derives which components should rerun vs remain unchanged

### 4. What The Framework Expands

The quickstart spec above expands to one concrete case today:

```text
proxy:capture-proxy/focus-change/proxy-numThreads
```

That concrete case includes:

- the mutated config
- the baseline checksum report
- the mutated checksum report
- the expected `reran` set
- the expected `unchanged` set
- any derived `blockedOn` relationships

The important point is that the test author does not write those expectations by hand for the simple case. They come from the checksum graph plus the selected mutator.

### 5. What Actually Runs

For that concrete case, both runtime paths aim at the same logical sequence:

1. run cleanup fixtures
2. submit the baseline config
3. wait for completion, validating and approving any configured gates
4. observe CRD phase and checksum state
5. resubmit the same config as a noop run
6. observe again and verify checksums stayed unchanged
7. submit the mutated config
8. observe again and verify rerun vs unchanged behavior
9. run teardown and cleanup fixtures

In the current implementation:

- the live runner in [e2e-run.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/e2e-run.ts) is the most complete version of that flow
- the generated outer workflow in [buildOuterWorkflow.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/buildOuterWorkflow.ts) is the in-cluster version being hardened toward the same semantics

### 6. How To Run It

Live runner:

```bash
npx tsx packages/e2e-orchestration-tests/src/e2e-run.ts \
  packages/e2e-orchestration-tests/specs/proxy-focus-change.test.json
```

Generated outer workflow YAML:

```bash
npx tsx packages/e2e-orchestration-tests/src/generate-outer.ts \
  packages/e2e-orchestration-tests/specs/proxy-focus-change.test.json > /tmp/e2e-outer.yaml
kubectl -n ma create -f /tmp/e2e-outer.yaml
```

### 7. How To Add Another Case

The expected developer workflow should be:

1. choose a baseline config
2. decide the focus component and behavior pattern you care about
3. reuse an existing approved mutator if possible
4. otherwise add one reviewed mutator to `approvedMutators.ts`
5. add or reuse fixture names if setup, cleanup, or validation is needed
6. add or update a small JSON spec

That should be enough for most new cases. If a test requires edits across many directories, the framework is not meeting its authoring goal.

## Runtime Oracle

The primary oracle is resource state, not workflow node naming.

For each component under test, the framework should assert in this order:

1. expected checksum from the checksum report
2. observed checksum on the CRD status or equivalent annotation
3. terminal lifecycle phase such as `Ready` or `Completed`
4. changed-vs-unchanged behavior relative to the previous run
5. optional workflow-node evidence for debugging only

This matches the current `assertLogic` model much better than the older document language around node-phase inspection.

## Fixtures And External Data Checks

Fixtures are an execution hook mechanism.

They can be used for:

- cluster setup
- cluster cleanup
- approval-time validation
- external data checks such as index existence or doc-count comparisons

Those checks are supporting evidence invoked by the orchestration framework. They are not themselves the primary subject of the framework.

The current fixture model in [fixtures.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/e2e-orchestration-tests/src/fixtures.ts) is a reasonable base and should remain compact and declarative.

## Current Constraints

The design should state the current limits directly.

- one outer scenario at a time
- one inner migration workflow at a time
- manual mutators only
- teardown is mandatory
- the live runner uses the shared `migration-workflow` name and should not run concurrently with unrelated workflow CLI use in the same namespace
- the live runner is the most complete runtime path today
- the generated outer workflow exists, but it still needs hardening to match the live runner on gate validation and lifecycle control

## What The Design Should Not Overstate

The document should avoid implying any of the following are already fully solved:

- broad generic framework support beyond the current package model
- full parity between the live runner and the generated outer workflow
- exhaustive field-level mutation coverage
- finalized impossible/delete flow behavior before the `#2462` merge
- graph authoring as a normal test-author task

Those ideas may remain future goals, but they should not dominate the document.

## Direction After #2462

The next major design step should happen after merging `opensearch-project/opensearch-migrations#2462`.

That merge changes the workflow lifecycle in exactly the area this framework depends on:

- approval handling
- reset and resubmit behavior
- delete and recovery semantics
- teardown and lifecycle ownership

After that merge, the framework should converge on one semantic model across both executors:

- same spec format
- same mutator and matrix expansion model
- same assertion logic
- same setup, validate, approve, assert, and teardown semantics

The most important post-merge hardening is not broader matrix generation. It is runtime convergence.

## Recommended Shape For This Document

This design doc should stay high-level and implementation-aligned.

It should answer:

- what the framework is for
- what a developer writes
- what the code currently does
- what is still intentionally narrow
- what gets hardened after `#2462`

It should not try to be both:

- a complete future taxonomy of every possible orchestration test shape
- and a precise description of the package as it exists today

When those two compete, prefer the current package shape and keep future expansion short and explicit.
