# Workflow Reset CRD Cleanup Plan

## Goal

Restore a clean boundary between the config initializer and the Argo workflow templates for the CRD-based workflow reset work.

The intended model is:

- The initializer is the first step of the workflow lifecycle and creates the full CR graph before Argo starts.
- `fullMigration.ts` consumes those precreated CRs, waits on them, attaches owned resources under them, and patches status/checksum state as execution proceeds.
- Resource deletion and operational reconfiguration stay outside the workflow unless there is a clear reason they must be workflow-managed.

## Scope

This cleanup covers:

- `orchestrationSpecs/packages/config-processor/src/migrationInitializer.ts`
- `orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/fullMigration.ts`
- `orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/resourceManagement.ts`
- Any related workflow-template helpers that became inconsistent with the initializer-first model

## Plan

### 1. Move All Resource And Approval CR Creation Into The Initializer

`migrationInitializer.ts` should create the full a priori CR graph, not just the legacy subset.

This includes:

- `KafkaCluster`
- `CapturedTraffic`
- `DataSnapshot`
- `SnapshotMigration`
- `TrafficReplay`
- `ApprovalGate`

Initializer work should also add:

- deterministic CR names
- `spec.dependsOn` wiring
- initial status fields
- initial checksum fields where the workflow expects them

For `ApprovalGate` specifically:

- generate gates a priori from the same config expansion that drives other workflow resources
- use deterministic names
- clean up gates before recreation with two layers:
- delete all approval gates matching a workflow/submission label
- explicitly delete the specific gate names that are about to be recreated with `ignore-not-found`

### 2. Refactor `fullMigration.ts` To Consume Precreated CRs

Once the initializer owns CR creation, `fullMigration.ts` should stop creating steady-state resource CRs inline.

The workflow should instead:

- wait for existing CRs
- read CR status/checksum state
- attach owned Kubernetes resources under those CRs
- patch CR status as execution progresses
- wait on precreated approval gates rather than creating them

This should remove inline creation for:

- `KafkaCluster`
- `CapturedTraffic`
- `SnapshotMigration`
- `TrafficReplay`

It should keep the workflow focused on orchestration rather than graph initialization.

### 3. Restore Checksum-Driven Orchestration

While cleaning up `fullMigration.ts`, restore or preserve the existing checksum discipline.

This includes:

- checksum-aware readiness waits
- checksum-aware skip/reuse logic
- resource-specific readiness semantics
- avoiding coarse phase-only checks where checksum tracking previously guarded correctness

This step is coupled to the workflow refactor and should be done at the same time rather than deferred.

### 4. Remove Workflow-Side Deletion And Listener Logic That No Longer Belongs

After the initializer/workflow boundary is corrected, remove long-lived lifecycle logic from the workflow templates that is compensating for the current split model.

Candidates include:

- `waitForCrdDeletion` watcher flows embedded in `fullMigration.ts`
- proxy listener-style teardown/reconfiguration paths
- other workflow-side delete/wait-for-gone patterns that should remain CLI-side

Approval-gate waiting remains in-scope for the workflow, but approval-gate creation should move to the initializer.

### 5. Simplify `resourceManagement.ts` To Match The Final Boundary

Keep:

- status patch helpers
- read/wait helpers
- approval wait helpers

Remove or narrow:

- creation helpers that become initializer-owned
- delete-watcher helpers that no longer belong in workflow execution
- generic phase-based shortcuts that displaced checksum-aware orchestration

## Order Of Execution

The recommended implementation order is:

1. Expand the initializer to own all CR creation, including approval gates.
2. Refactor `fullMigration.ts` to consume precreated CRs.
3. Restore checksum-driven orchestration while doing the workflow refactor.
4. Remove workflow-side delete/listener logic.
5. Prune `resourceManagement.ts` to match the final architecture.

## Notes

- `ApprovalGate` is intentionally included in the initializer-created graph.
- Approval-gate cleanup should be explicit and deterministic rather than relying on workflow ownership.
- Static gate names are preferred so that label cleanup and explicit delete-by-name cleanup can both be applied safely.
