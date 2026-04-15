## Goal

Replace every remaining Argo suspend / suspension-style manual intervention point with `ApprovalGate` CRs.

This includes:

- direct `.addSuspend()` usage
- suspend-wrapper templates whose purpose is “pause for human action”
- VAP / validation retry checkpoints
- any other manual remediation pause that should survive workflow replacement

The end state is:

- all approval state lives in `ApprovalGate` CRs
- the workflow only waits on those CRs
- the CLI `approve` command operates on those CRs
- there is no remaining split between Argo-native suspension and CR-based approval
- retry loops can safely re-enter the same approval checkpoint multiple times because the gate is explicitly reset before recursion

## 1. Inventory all remaining suspend points

Find every remaining:

- `.addSuspend()`
- suspend-template wrapper
- step whose semantics are “wait for operator to fix something and continue”

At minimum, current known spots include:

- `setupKafka.ts`
- `setupCapture.ts`

Also check for:

- VAP-related retry/remediation flows
- any “retry after fix” branches
- any comments that refer to manual intervention / pause / retry

For each suspend point, write down:

- what failed or is being gated
- what the operator is expected to do before approval
- what resource or migration scope it belongs to
- whether it is retrying a Kafka / proxy / snapshot / replay path
- whether it is a one-shot approval or part of a recursive retry loop

## 2. Define deterministic ApprovalGate names for every checkpoint

All gate names should be deterministic and initializer-known.

Use the same basic naming style already used in metadata migration:

- dotted names for approval gates
- scoped by the resource or migration context they belong to

General rule:

- gate names should encode the exact workflow sub-operation being approved
- they should be stable across workflow resubmission for the same CR graph

Recommended examples:

- Kafka / VAP repair gates:
    - `<kafka-cluster>.vapRetry`
    - or, if needed, a more explicit operation name such as `<kafka-cluster>.deployKafka`

- Proxy / VAP repair gates:
    - `<proxy-name>.vapRetry`
    - or, if needed, `<proxy-name>.deployProxy`

- Metadata migration gates:
    - keep the current pattern:
        - `<source>.<target>.<snapshot>.<migration>.evaluateMetadata`
        - `<source>.<target>.<snapshot>.<migration>.migrateMetadata`

Do not use:

- Argo node IDs
- workflow UID
- retry counters
- any other ephemeral values

The same logical checkpoint must resolve to the same gate name across workflow replacement.

## 3. Centralize gate-name construction in the initializer/config side

The initializer should be the canonical place that knows:

- which `ApprovalGate`s exist
- what their names are

Add or extend helper functions in:

- `migrationInitializer.ts`

to generate all needed gate names from the transformed workflow config.

That helper should cover:

- metadata migration approvals
- Kafka / proxy VAP retry approvals
- any other remaining manual intervention checkpoints

The point is:

- workflow templates should reference deterministic gate names
- initializer should precreate exactly those gates
- gate naming logic should not be duplicated ad hoc in multiple workflow templates

## 4. Precreate all ApprovalGate CRs in the initializer

Extend `migrationInitializer.ts` so that every gate identified in step 3 is emitted as an `ApprovalGate` CR.

ApprovalGate behavior should remain:

- precreated before workflow submission
- independent of the `dependsOn` graph
- no owned resources

ApprovalGate CR instances should have:

- deterministic `metadata.name`
- empty `spec` unless other fields are already part of the design
- initial status representing “not yet approved” (for example `Initialized` or `Pending`, consistent with the CLI behavior)

## 5. Add explicit ApprovalGate status patch helpers in `resourceManagement.ts`

Add explicit templates for gate lifecycle control.

At minimum:

- `waitForApproval`
    - already exists
    - waits for `status.phase == Approved`

- `patchApprovalGatePhase`
    - new
    - inputs:
        - `resourceName`
        - `phase`
    - patches:
        - `apiVersion: migrations.opensearch.org/v1alpha1`
        - `kind: ApprovalGate`
        - `metadata.name`
        - `status.phase`

Optional narrower wrappers can be added if helpful:

- `resetApprovalGate`
    - just patches `status.phase = Pending`

The key requirement is:
- workflow code must be able to reset a gate back to a non-approved state before recurring through a retry loop

## 6. Replace each suspend point with `waitForApproval`

For every remaining suspend point:

- remove `.addSuspend()`
- replace it with a call to:
    - `ResourceManagement.waitForApproval`

If the workflow path previously suspended only on failure/remediation:

- keep the same `when` condition
- only the pause mechanism changes

The workflow should not create approval state ad hoc if the initializer already precreates the gates.
It should only wait on the known deterministic gate name.

## 7. Preserve retry/remediation semantics around VAP flows

For each VAP/manual-retry checkpoint, preserve the current behavior while changing the persistence mechanism.

Current semantics are effectively:

1. attempt operation
2. if validation / admission / rollout issue occurs
3. suspend for operator remediation
4. retry or continue

Convert that to:

1. attempt operation
2. if remediation is needed
3. wait for the corresponding `ApprovalGate`
4. apply the follow-up approval/remediation patch that the retry flow already expects
5. reset the `ApprovalGate` back to a non-approved state
6. recurse / retry

The business logic stays the same.
Only the persistence mechanism changes from Argo suspend to `ApprovalGate` CR state.

## 8. Reset the gate before recursion in retry loops

This is required for recursive retry loops.

Why:

- if a gate remains `Approved`
- and the recursive retry path reaches the same gate again
- `waitForApproval` will pass immediately
- the operator loses the ability to intervene on the next retry cycle

So for every recursive VAP retry flow, the structure should become:

1. `tryApply`
2. `waitForApproval` (only if remediation is needed)
3. `patchApprovalAnnotation` or equivalent follow-up step
4. `patchApprovalGatePhase(resourceName=<gate>, phase=Pending)`
   or equivalent reset helper
5. recurse / retry

Recommended gate reset state:

- `Pending`

Reason:

- the CLI already treats `Initialized` and `Pending` as actionable
- `Pending` better communicates “this gate may need approval again”

This reset should happen **after** the current approval has been consumed and **before** recursion.

## 9. Keep gate ownership separate from the CR dependency DAG

ApprovalGates should remain independent:

- no `dependsOn`
- no ownerReferences to migration CRs
- cleanup handled explicitly by initializer and/or workflow exit

They are control artifacts, not infrastructure dependencies.

## 10. Ensure CLI approval coverage matches the new gate set

After adding new gate names, verify the CLI can:

- list pending gates
- autocomplete them
- approve them by exact name or pattern

This likely needs little or no code if the CLI already works generically on `ApprovalGate`,
but it should be validated once the new names exist.

Also confirm:

- both `Initialized` and `Pending` states are handled correctly by the CLI
- resetting a gate to `Pending` makes it appear again as an approvable item

## 11. Cleanup lifecycle

Initializer-side cleanup should remain the required cleanup path:

- delete stale `ApprovalGate`s before recreating them

Optional later enhancement:

- top-level workflow `onExit` cleanup for gates

But the required work for this conversion is:

- ensure no stale gates block resubmission
- ensure every expected gate is precreated
- ensure recursive retry flows can reuse the same gate names safely because gate phase is reset before recursion

## 12. Testing / validation

### Code-level validation

- update or add config-processor tests to assert the initializer emits the new `ApprovalGate` CRs
- update workflow-template snapshots if gate wait/reset steps change rendered YAML
- add or update template-level tests for:
    - Kafka VAP retry loop using `ApprovalGate`
    - proxy VAP retry loop using `ApprovalGate`
    - gate reset to `Pending` before recursion

### Live/manual validation

For at least one Kafka/proxy VAP remediation path:

1. run workflow until the gate is reached
2. confirm no Argo-native suspend is used
3. confirm the workflow is waiting on `ApprovalGate`
4. approve via CLI
5. confirm the workflow resumes correctly
6. if the retry loops again, confirm the same gate name is reused but the gate had been reset to `Pending` before the second wait

Also validate:

- resubmitting/replacing the workflow preserves the same gate names
- approval state survives workflow replacement
- retry loops do not bypass approval on the second pass because of stale `Approved` state

## 13. Implementation order

1. inventory all remaining suspend points
2. define deterministic gate names for each
3. extend initializer to precreate the full gate set
4. add `patchApprovalGatePhase` / reset helper in `resourceManagement.ts`
5. replace suspend points with `waitForApproval`
6. update VAP retry loops to:
    - wait on gate
    - perform follow-up patch
    - reset gate to `Pending`
    - recurse
7. validate CLI approval flow against the new gates
8. update tests/snapshots

