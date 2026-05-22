# Implementation Plan — RFS Completion CronJob

Companion to [`rfsCronJobMonitor.md`](./rfsCronJobMonitor.md). Lists what to change, in what order, on the `cronJobRfsChecks` branch.

## Two-commit structure

This work goes in as a single PR with two commits to keep parity:

- **C1 — orchestrationSpecs (TS) + RBAC chart + CRD schema.** New CronJob, new apply-step Argo template, typed `SnapshotMigration.status.documentBackfill`, removal of workflow-side polling and runtime cleanup, RBAC update.
- **C2 — `MigrationConsole` (Python).** `workflow status` and `workflow manage` read `SnapshotMigration.status.documentBackfill` for RFS wait nodes instead of re-running live `console backfill status` calls.

## Pre-flight verification (done)

- `MigrationConsole` image ships `kubectl` and `curl`. **Confirmed.**
- `waitForSnapshotMigration` template exists at `resourceManagement.ts:1073` with `successCondition: status.phase == Completed AND status.<checksumField> == <configChecksum>`. Reusable for the RFS path with `checksumField = "configChecksum"`. **Confirmed.**
- `JobTemplateSpec` is generated as `IoK8SApiBatchV12` in `k8s-types/src/index.ts` but **not yet friendly-aliased**. Need to add the alias.
- `CronJob` and `Job` already have friendly aliases at `k8s-types/src/index.ts:8561-8562` and are re-exported from `argo-workflow-builders`. No re-export work.

## Scripting model

The CronJob's Job pod runs a bash script (the `MigrationConsole` image is the runtime). All cluster mutations use:

- `kubectl <verb>` for normal calls.
- `kubectl --raw -X <method>` for the two operations the CLI doesn't expose:
  - **DELETE cronjob with `Preconditions{UID, ResourceVersion}`** — INV-5.
  - **PATCH cronjob with `metadata.resourceVersion`** as optimistic-concurrency precondition for cadence updates.

The script lives **inline** in the manifest builder (matching the pattern of `getCheckBackfillStatusScript` in `documentBulkLoad.ts`), passed via `expr.fillTemplate` for the small set of values that vary at fill time. Other values come from env vars set on the Job template.

## C1 file changes

### `orchestrationSpecs/packages/k8s-types/scripts/generate.ts`
Add `JobTemplateSpec` to `additionalTypes`. Run `npm run -w @opensearch-migrations/k8s-types rebuild` to regenerate `src/index.ts` and pick up the friendly alias. Verify diff is alias-only.

### `deployment/k8s/charts/aggregates/migrationAssistantWithArgo/templates/resources/migrationCrds.yaml`

Add `SnapshotMigration.status.documentBackfill` with:
- `phase`, `updatedAt`, and `message`.
- Structured `summary` fields normalized from `console --json backfill status --deep-check`: `percentageCompleted`, `etaMs`, `started`, `finished`, `shardsTotal`, `shardsMigrated`, `shardsInProgress`, and `shardsWaiting`.

### `orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/documentBulkLoad.ts`

**Add:**
- `getRfsDoneCronJobName(sessionName)` name builder.
- `RFS_DONE_CRONJOB_SCRIPT` constant — the bash script (phases 0/1/2/3 + `maybe_bump_cadence`). Uses `expr.fillTemplate` placeholders for `RFS_CRONJOB_NAME`, `SNAPSHOT_MIGRATION_NAME`, `RFS_DEPLOYMENT_NAME`, `RFS_COORDINATOR_NAME`, `USES_DEDICATED_COORDINATOR`. All other values (`WORKFLOW_UID`, `CONFIG_CHECKSUM`, `CHECKSUM_FOR_REPLAYER`, `CLAIMED_AT`, `STARTUP_GRACE_SECONDS`, `PARENT_WORKFLOW_NAME`, `PARENT_WORKFLOW_UID`) come from env at runtime.
- `getRfsDoneCronJobManifest(args)` — returns `CronJob`. Sets:
  - `metadata.name`, `metadata.ownerReferences` (to the SM), `metadata.labels` (`rfs-monitor-workflow-uid`, `rfs-monitor-session`).
  - `spec.schedule: "*/1 * * * *"` (initial seed; the script ramps it).
  - `spec.concurrencyPolicy: "Forbid"`, `spec.successfulJobsHistoryLimit: 1`, `spec.failedJobsHistoryLimit: 3`.
  - `spec.jobTemplate` — labels (object + Pod template), env (the full list above), one `MigrationConsole`-image container running `["/bin/sh","-c", <SCRIPT>]`.
- `applyRfsDoneCronJob` Argo template — a container template (`MigrationConsole` image) running the apply bash:
  - `kubectl create -f <cronjob.yaml>` ; on 409, `kubectl patch cronjob NAME --type=merge -p <patch>` then `kubectl delete jobs/pods -l 'rfs-monitor-session=<S>,rfs-monitor-workflow-uid!=<NEW>' --force --grace-period=0`.
  - Read-back loop: `kubectl get cronjob NAME -o json | jq` checking the assertions in the design doc's Workflow Logic step 3.
  - `kubectl patch snapshotmigration NAME --subresource=status --type=merge` to seed `status.documentBackfill`.
  - Wrapped in `addRetryParameters({limit:"5", retryPolicy:"Always", backoff:{duration:"2s", factor:"2", maxDuration:"30s"}})`.

**Modify `setupAndRunBulkLoad`:**
1. New first step: `applyRfsDoneCronJob` (with the read-back inside the same script).
2. Existing `createRfsCoordinator` (when dedicated) — moves to second.
3. New step: idempotent `kubectl apply` of the RFS Deployment via `addResourceTask({action: "apply", ...})` — already in `startHistoricalBackfill`.
4. Replace `runBulkLoad`'s `waitForCompletion` step with `waitForSnapshotMigration` from `resourceManagement.ts` (`checksumField: "configChecksum"`).
5. Remove `stopHistoricalBackfill` step.
6. Remove `cleanupRfsCoordinator` step.

**Delete (after grep-confirming zero callers):**
- `stopHistoricalBackfill` template.
- `waitForCompletionInternal` template.
- `waitForCompletion` template.
- `getCheckBackfillStatusScript` helper.

If any of these have external callers (other workflow templates, other tests), I'll flag and ask before deleting.

### `orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/fullMigration.ts`

In `runSingleSnapshotMigration`:
- Remove the unconditional `patchSnapshotMigrationCompleted` step on the RFS path.
- Keep `patchSnapshotMigrationCompleted` on the metadata-only path (no `documentBackfillConfig`).

The `migrateFromSnapshot when:` guard (`currentConfigChecksum != configChecksum`) is unchanged.

### `orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/rfsCoordinatorCluster.ts`

No source changes — the existing `createRfsCoordinator` (idempotent apply) is reused. The `deleteRfsCoordinator` template is no longer called by `setupAndRunBulkLoad`, but may still be used by `testMigrationWithWorkflowCli` or another caller. Grep before deleting.

### `deployment/k8s/charts/aggregates/migrationAssistantWithArgo/templates/resources/workflowRbac.yaml`

Add `cronjobs` to the existing batch RBAC rule. Verbs: `create get list watch update patch delete`.

### Tests

- Snapshot tests under `migration-workflow-templates/tests/__snapshots__/` will regenerate when the templates change. Review diffs and update snapshots after `npm test`.
- One new lightweight test: `bash -n` (parse-only check) on the rendered CronJob script. Verifies the embedded bash is syntactically valid. Failure mode is real (a typo in the script renders bad bash); cost is one `bash -n` call.

## C2 file changes (Python — separate commit, same PR)

`migrationConsole/lib/console_link/console_link/workflow/`:

- `tree_utils.py`, `commands/status.py`, and TUI tree state helpers: for any RFS `waitForSnapshotMigration` node, read the named `SnapshotMigration` CR via the node's `resourceName` input and render `status.documentBackfill.summary` and `updatedAt`.

The exact file list will be confirmed by grep at C2 time. C1 lands the CR status writer; C2 makes the readers consume it.

## Build and verification

From `~/dev/opensearch-migrations/orchestrationSpecs`:

```
npm run -w @opensearch-migrations/migration-workflow-templates type-check-all
npm run -w @opensearch-migrations/migration-workflow-templates test
npm run dist-make-templates
```

Then visually diff:
- `k8sResources/document-bulk-load.yaml`
- `k8sResources/full-migration.yaml`

against the previous version. The diffs should match the design doc's Implementation Touch Points section.

## Order of execution within C1

1. Add `JobTemplateSpec` friendly alias in `k8s-types/scripts/generate.ts`. Rebuild `k8s-types`.
2. Add name builders in `documentBulkLoad.ts`.
3. Write `RFS_DONE_CRONJOB_SCRIPT` constant. `bash -n` it locally.
4. Write `getRfsDoneCronJobManifest`.
5. Write `applyRfsDoneCronJob` Argo template.
6. Modify `setupAndRunBulkLoad` ordering and step set.
7. Modify `fullMigration.ts` to drop `patchSnapshotMigrationCompleted` on the RFS path.
8. Run grep for callers of templates we want to delete; delete or report.
9. CRD schema and RBAC chart changes.
10. `type-check-all`, `test`, regenerate templates. Iterate on snapshot diffs.
11. Commit C1.

## Out of scope for this PR

- E2E integration tests for the cadence ramp (`*/1 → */5`). Snapshot tests cover the manifest correctness; runtime ramp behavior is hard to assert offline.
- Persisting raw diagnostic blobs in `SnapshotMigration.status`. The CR status keeps one normalized structured summary, not a second raw copy.
- Any change to `MigrationConsole`'s container image to add packages — `kubectl` and `curl` are already present.
