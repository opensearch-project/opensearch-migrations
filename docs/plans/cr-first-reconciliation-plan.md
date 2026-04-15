# CR-First Reconciliation Plan

## Goal

Restore and standardize the intended lifecycle model for workflow-managed root CRs:

- `KafkaCluster`
- `CapturedTraffic`
- `SnapshotMigration`
- `TrafficReplay`

The model should be:

1. the CR is the durable contract
2. the workflow reconciles the CR first
3. only after CR reconciliation succeeds does the workflow reconcile child resources
4. if the CR is already identical, the workflow should skip unnecessary subtree work
5. if the CR update is gated by VAP, the workflow should wait for approval and retry
6. if the CR update is impossible, the workflow should fail clearly and require delete/recreate

This restores the mainline “don’t recreate if already created and unchanged” behavior for Kafka and extends the same design consistently to `SnapshotMigration` and `TrafficReplay`.

---

## Current Gap

The current codebase only partially follows this model.

What is already close:

- `CapturedTraffic`
  - workflow attempts to `apply` the CR
  - VAP-gated failures enter an approval/retry loop
  - once the CR is reconciled, the workflow continues with child-resource work

- Strimzi Kafka child resources
  - Kafka/KafkaNodePool/KafkaUser/KafkaTopic each have VAP-aware retry loops

What is missing or weakened:

- `KafkaCluster`
  - the workflow is too focused on child-resource reconciliation
  - it no longer clearly restores the old “already created and unchanged means skip the recreate path” behavior at the CR contract level

- `SnapshotMigration`
  - the CR is precreated and its status is patched later
  - there is no workflow-side CR-spec reconcile loop
  - gated/impossible update policy exists, but the workflow does not actually exercise it

- `TrafficReplay`
  - the CR has VAP policy for gated/impossible fields
  - but there is no workflow-side CR-spec reconcile loop
  - the workflow jumps to child-resource setup instead of reconciling the CR contract first

So the admission policies and the workflow behavior are currently out of sync for `SnapshotMigration` and `TrafficReplay`, and partially for `KafkaCluster`.

---

## Design Principle

For every workflow-managed root CR:

1. the CR spec is the source of truth
2. the workflow must try to reconcile that CR spec on every run
3. the result of CR reconciliation determines whether the subtree runs

The child-resource subtree should never be the first place where new configuration becomes real.

That means:

- if a user changes a safe field, the CR update should succeed and the subtree should run
- if a user changes a gated field, the CR update should be blocked by VAP until approved
- if a user changes an impossible field, the CR update should fail and the workflow should stop
- if nothing changed, the workflow should treat the CR as already reconciled and skip unnecessary work

---

## Common Reconciliation State Machine

All root CRs should use the same high-level flow.

### Inputs

Each reconcile template should take:

- serialized desired CR spec/config
- CR name
- CR resource UID if relevant for child ownership
- config checksum
- deterministic approval gate name if gated retries are possible
- user-facing group name for workflow UI

### Step 1: Read current CR state

Read:

- current phase
- current config checksum
- possibly current spec if needed for explicit comparison

This read step is used only to decide whether the CR is already reconciled and whether subtree work can be skipped.

### Step 2: Attempt CR reconciliation

Attempt to apply the desired CR manifest.

Behavior:

- if the resource does not exist, create it
- if the resource exists and the spec is identical, the apply should be a no-op
- if the resource exists and safe fields changed, the apply should succeed
- if the resource exists and a gated field changed, VAP should deny the update
- if the resource exists and an impossible field changed, VAP should deny the update permanently

Important:

- this reconcile step must patch the actual CR spec fields that VAP evaluates
- patching only `status.phase`, `status.configChecksum`, or other status fields is not enough
- if the desired safe/gated fields are not actually written into the CR spec, then:
  - VAP never evaluates the intended update
  - the workflow cannot distinguish safe vs gated CR changes correctly
  - child-resource subtrees may run from workflow-local inputs instead of from the reconciled CR contract

So each root reconcile path must construct and apply the real desired CR spec, not just patch running/ready/completed status.

### Step 3: Branch on the result

The workflow should distinguish:

- `reconciled`
  - create/update succeeded
  - proceed into subtree

- `unchanged`
  - resource already matched desired contract
  - subtree may be skipped or may perform only cheap reads, depending on resource type

- `gated`
  - VAP denied the update because approval is required
  - go through approval loop

- `impossible`
  - VAP denied the update because the change is forbidden
  - fail clearly

- `unexpected_error`
  - fail clearly

The workflow should not infer these from brittle Argo node error strings if that can be avoided.
If needed, add a small helper/wrapper step to normalize the outcome.

### Step 4: Approval loop for gated updates

For gated failures, use the same pattern already adopted for Kafka/proxy VAP loops:

1. wait on deterministic `ApprovalGate`
2. patch the CR approval annotation (`migrations.opensearch.org/approved-by-run`)
3. reset the gate phase to `Pending`
4. retry the CR reconcile

Important:

- recursion must happen only after the gate reset succeeds
- otherwise the next pass may observe stale `Approved` state and skip the intended pause

### Step 5: Subtree execution

Only after CR reconcile succeeds should the workflow enter the child-resource subtree.

The subtree should assume:

- the CR contract is now current
- any gated change has already been approved
- impossible changes have already been rejected

---

## Shared Template/Builder Work

To make the pattern reusable, add a shared set of helpers/templates.

### 1. Read helpers

In `resourceManagement.ts`, add or extend helpers to read:

- CR phase
- config checksum
- optionally spec hash/comparison signal if needed

These may be generic:

- `readResourcePhase`
- `readResourceSpecChecksum`

or root-specific if needed.

### 2. CR apply helpers

Add explicit templates for reconciling root CRs:

- `applyKafkaCluster`
- `applyCapturedTraffic`
- `applySnapshotMigration`
- `applyTrafficReplay`

These should encapsulate:

- desired manifest shape
- `action: apply`
- retry parameters

These helpers must write the actual VAP-relevant spec fields for the CR.

Examples:

- `SnapshotMigration`
  - `metadataMigrationConfig`
  - `documentBackfillConfig`
  - any other spec fields that define or operationally tune the migration

- `TrafficReplay`
  - gated replay auth/transformer-related spec fields
  - impossible replay fields where comparison is still needed to surface the correct error path

- `KafkaCluster` / related Kafka-root contract
  - the fields whose updates determine whether the subtree should rerun or require approval

Without this, the reconcile step is incomplete because it would only observe status while leaving the CR spec stale.

### 3. Outcome-normalization helpers

Add a small helper step or template for classifying a failed apply:

- unchanged/reconciled
- gated
- impossible
- unexpected_error

If Argo-resource-task status and output are not rich enough, this can be a small wrapper script step that:

- attempts the apply
- captures stderr/status
- emits a normalized output parameter

### 4. Approval helpers

Keep using:

- `waitForApproval`
- `patchApprovalGatePhase`
- `patchApprovalAnnotation`

and reuse them for all gated root CR updates.

---

## Resource-Specific Plans

## KafkaCluster

### Desired behavior

Restore the mainline behavior:

- if Kafka is already created and unchanged, do not recreate/reconcile the whole subtree unnecessarily
- if Kafka CR config changes safely, reconcile and continue
- if Kafka CR config changes in a gated way, require approval and retry
- if Kafka CR config changes impossibly, fail clearly

### Proposed structure

Add a root-level Kafka reconcile template before the existing child-resource steps.

Suggested flow:

1. `readKafkaClusterState`
2. `reconcileKafkaCluster`
3. if `unchanged` and phase/checksum already indicate ready:
   - skip the subtree entirely
4. if `reconciled`:
   - proceed into Kafka child-resource subtree
5. if `gated`:
   - approval loop
   - retry reconcile
6. if `impossible`:
   - fail

### Child subtree

Only once the root Kafka CR contract is reconciled should the workflow run:

- `KafkaNodePool`
- `Kafka`
- `KafkaUser`
- `KafkaTopic`

The existing VAP-aware loops on those resources should remain.

This gives two layers:

- root CR contract reconciliation
- child-resource reconciliation

That is acceptable and keeps the durable contract clear.

### Skip semantics

To restore “don’t recreate Kafka if it already exists”:

- if the `KafkaCluster` CR is already `Ready` with matching checksum/spec
- skip child subtree reconciliation unless there is a concrete reason to rerun it

This is the main behavior regression to recover.

---

## CapturedTraffic

### Desired behavior

`CapturedTraffic` is already closest to the intended pattern and should remain the reference implementation.

### Keep

- CR-first apply/update
- VAP-aware approval loop
- gate reset to `Pending` before recursion
- subtree only after CR reconcile

### Optional cleanup

Bring its helper/template naming into alignment with the new common pattern so it becomes the canonical reusable example.

---

## SnapshotMigration

### Desired behavior

`SnapshotMigration` should be reconciled first as the durable contract for snapshot-side execution.

That means:

- if the desired `SnapshotMigration.spec` is identical, skip unnecessary spec reconciliation and possibly skip subtree work if already completed/current
- if a safe operational field changes, apply it and then let the subtree update the underlying RFS resources
- if a gated field changes, require approval and retry the CR update
- if an impossible field changes, fail clearly and require delete/recreate

This is necessary because the VAP policy already distinguishes:

- impossible metadata/backfill data-defining fields
- gated RFS operational fields

### Proposed structure

Add a new root reconcile template for `SnapshotMigration`.

Suggested flow:

1. `readSnapshotMigrationState`
   - phase
   - checksum

2. `reconcileSnapshotMigration`
   - apply desired CR spec
   - normalize outcome

3. Branch:
   - `unchanged/current`
     - if already `Completed` with matching checksum, skip subtree entirely
     - otherwise continue as needed
   - `reconciled`
     - continue into metadata/backfill subtree
   - `gated`
     - `waitForApproval`
     - `patchApprovalAnnotation` on `SnapshotMigration`
     - `patchApprovalGatePhase(..., Pending)`
     - retry reconcile
   - `impossible`
     - fail clearly

### Subtree contract

Only after the `SnapshotMigration` CR reconcile succeeds should the workflow enter:

- metadata migration
- document backfill / RFS deployment
- coordinator setup

This makes the RFS deployment an effect of the reconciled CR contract, not of workflow-local config alone.

### CR-spec patching requirement

This path must explicitly apply the desired `SnapshotMigration.spec`, not just patch status on the existing CR.

At minimum, that includes the spec fields that VAP evaluates, such as:

- `metadataMigrationConfig`
- `documentBackfillConfig`
- other spec fields that define the desired snapshot-migration behavior

The intended control flow is:

1. try to reconcile the `SnapshotMigration` CR spec
2. if identical/current, skip unnecessary subtree work
3. if the spec update succeeds, run the snapshot-migration subtree
4. if the spec update is gated, wait for approval and retry
5. if the spec update is impossible, fail clearly

The subtree should not be the first place where these new parameters become real.

### Gate naming

Add deterministic gate names in the initializer for this path, e.g.:

- `<snapshot-migration-name>.vapretry`

or a more explicit operation-scoped name if you want room for future separation:

- `<snapshot-migration-name>.documentBackfill.vapretry`

### Why this matters

Today the policy advertises gated/impossible `SnapshotMigration` updates, but the workflow does not actually reconcile the CR spec on rerun.
This change makes policy and workflow behavior consistent.

---

## TrafficReplay

### Desired behavior

`TrafficReplay` should follow the same pattern as `SnapshotMigration`.

That means:

- reconcile the `TrafficReplay` CR first
- if unchanged/current, skip unnecessary subtree work
- if safe update, proceed
- if gated update, require approval and retry
- if impossible update, fail clearly

### Proposed structure

Add a new root reconcile template for `TrafficReplay`.

Suggested flow:

1. `readTrafficReplayState`
   - phase
   - checksum

2. `reconcileTrafficReplay`
   - apply desired CR spec
   - normalize outcome

3. Branch:
   - `unchanged/current`
     - if already `Ready` with matching checksum, skip subtree
   - `reconciled`
     - continue into replayer deployment subtree
   - `gated`
     - wait for approval
     - patch approval annotation on `TrafficReplay`
     - reset gate to `Pending`
     - retry reconcile
   - `impossible`
     - fail

### Subtree contract

Only after `TrafficReplay` CR reconcile succeeds should the workflow continue into:

- kafka client config ConfigMap creation
- replayer deployment apply
- ready status patch

### CR-spec patching requirement

This path must explicitly apply the desired `TrafficReplay.spec`, not just patch ready/running status around the child deployment.

At minimum, that includes the replay spec fields covered by the validating policy, especially the gated replay configuration fields.

The intended control flow is:

1. try to reconcile the `TrafficReplay` CR spec
2. if identical/current, skip unnecessary subtree work
3. if the spec update succeeds, run the replay subtree
4. if the spec update is gated, wait for approval and retry
5. if the spec update is impossible, fail clearly

This keeps replay deployment behavior derived from the reconciled CR contract instead of from workflow-local inputs alone.

### Gate naming

Add deterministic gate names in the initializer for replay VAP retries, e.g.:

- `<traffic-replay-name>.vapretry`

### Why this matters

The validating policy already defines gated/impossible `TrafficReplay` updates.
Without CR-first reconcile, that policy is mostly inert from the workflow’s perspective.

---

## DataSnapshot

### Current assessment

`DataSnapshot` does not need the same VAP approval loop today.

Why:

- the policy is effectively immutable-after-create
- the workflow does not reconcile `DataSnapshot.spec`; it creates snapshot work and only patches status later
- there are no meaningful gated updates to route through approval under the current model

### Recommendation

Do not add VAP approval/retry loops for `DataSnapshot` unless the design changes to allow workflow-side spec reconciliation of that CR.

---

## ApprovalGate Expansion

The initializer should precreate gates for every root CR path that can be gated.

That now includes:

- Kafka VAP retry gates
- CapturedTraffic VAP retry gates
- SnapshotMigration VAP retry gates
- TrafficReplay VAP retry gates
- metadata migration approval gates

ApprovalGate principles stay the same:

- deterministic names
- precreated by initializer
- no `dependsOn`
- no owner refs
- explicit cleanup on submission and/or workflow exit

---

## Submit/Initializer Behavior

The initializer and submit path must support CR-first reconciliation cleanly.

### Required behavior

- precreate missing CRs as before
- but do not rely on create-once/skip semantics as the main update model
- the workflow itself must be responsible for reconciling root CR specs on rerun

### Implication

The submit script can still create CRs initially, but the workflow must not assume:

- “already exists” means “good enough”

Instead, the workflow should always:

- read
- reconcile
- branch on the reconcile outcome

This is the crucial difference between simple bootstrap and true durable-contract reconciliation.

---

## Outcome Classification

Each root reconcile path should classify errors into one of these buckets:

- `current`
  - already matches desired config and state is acceptable

- `reconciled`
  - create/update applied successfully

- `gated`
  - denied due to approval requirement

- `impossible`
  - denied because the policy forbids the change

- `unexpected_error`
  - infrastructure/serialization/permissions/etc.

This classification should be surfaced explicitly to the workflow logic instead of relying on broad “step failed” semantics.

---

## Cleanup Semantics

CR-first reconciliation does not change the cleanup model:

- CRs remain the durable records
- transient compute should still be cleaned up after success where appropriate

Examples:

- `SnapshotMigration`
  - keep CR
  - clean up RFS deployment/coordinator resources after success

- `TrafficReplay`
  - keep CR
  - child deployment is runtime compute and can be cleaned up based on existing lifecycle decisions

- metadata migration remains transient Argo compute and intentionally not CR-owned

---

## Testing Plan

## 1. Unit/template coverage

Add tests for each root reconcile path:

- unchanged/current path
- successful update path
- gated path enters approval loop
- gate reset happens before recursion
- impossible path fails clearly

## 2. Initializer coverage

Extend config-processor tests to assert precreated ApprovalGates for:

- SnapshotMigration VAP retry
- TrafficReplay VAP retry
- Kafka root-level reconcile gate if distinct from child-resource gates

## 3. Snapshot/template snapshots

Update workflow-template snapshots to show:

- root CR reconcile steps before subtree execution
- approval-gate wait/reset steps for gated root CR updates

## 4. Live/manual validation

Validate for each root CR type:

1. unchanged rerun
   - subtree is skipped

2. safe update
   - CR update succeeds
   - subtree runs

3. gated update
   - workflow pauses on deterministic `ApprovalGate`
   - user approves
   - gate resets to `Pending`
   - reconcile retry succeeds
   - subtree runs

4. impossible update
   - workflow fails clearly
   - no subtree execution

---

## Implementation Order

1. Define the exact desired-spec builders for each root CR
   - `KafkaCluster`
   - `CapturedTraffic`
   - `SnapshotMigration`
   - `TrafficReplay`
   - make sure these builders include the real VAP-relevant spec fields, not just status patches

2. Introduce a common CR-first reconcile pattern and outcome-classification helper
   - read current CR state
   - apply desired CR spec
   - classify outcome as `current`, `reconciled`, `gated`, `impossible`, or `unexpected_error`

3. Restore Kafka root-level CR reconcile and skip-if-current behavior
   - reconcile the root Kafka contract first
   - only then run Kafka child-resource reconciliation when needed

4. Add `SnapshotMigration` CR-spec reconcile before its subtree
   - apply the real `SnapshotMigration.spec`
   - gate subtree execution on the reconcile result
   - make RFS/metadata execution derive from the reconciled CR contract

5. Add `TrafficReplay` CR-spec reconcile before its subtree
   - apply the real `TrafficReplay.spec`
   - gate replay subtree execution on the reconcile result

6. Extend initializer to precreate any new retry gates
   - root-level Kafka reconcile gates if distinct
   - `SnapshotMigration` retry gates
   - `TrafficReplay` retry gates

7. Wire approval-loop behavior consistently
   - wait on gate
   - patch CR approval annotation
   - reset gate to `Pending`
   - retry CR reconcile

8. Update tests and snapshots
   - root-CR reconcile paths
   - gated retry behavior
   - subtree skip behavior when current
   - explicit coverage that CR spec fields, not just status, are reconciled

9. Run live validation against VAP-protected update scenarios
   - unchanged/current
   - safe update
   - gated update
   - impossible update

---

## Final Intended Behavior

After this work:

- Kafka is not needlessly recreated when already current
- `SnapshotMigration` and `TrafficReplay` behave like true durable contracts
- admission policies and workflow behavior are aligned
- gated changes are human-approvable and retryable
- impossible changes fail early and clearly
- child-resource subtrees run only after root CR reconciliation succeeds

That is the consistent design target across the workflow-managed CR graph.
