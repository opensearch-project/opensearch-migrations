# Remaining TypeScript Implementation Tasks

> These tasks require deep familiarity with the `argo-workflow-builders` TypeScript API
> (expression builder, `addStepToSelf`, `makeStringTypeProxy`, `makeDirectTypeProxy`, etc.).
> The YAML/Helm changes (CRD merge, RBAC, VAPs) are complete. These TS changes remain.

## What's Done

1. ✅ **CRD merge** — `migrationOptionsCrds.yaml` merged into `migrationCrds.yaml`, old file deleted
2. ✅ **RBAC** — Added proxyconfigs, replayerconfigs, snapshotconfigs, rfsconfigs + /status to workflow-deployer-role
3. ✅ **All VAPs** — Kafka migrated to UID approval, Proxy VAP, DataSnapshot VAP, SnapshotMigration VAP, Lock-on-Complete
4. ✅ **`resourceManagement.ts`** — Added `patchResourcePhase` (generic status phase patch) and `patchApprovalAnnotation` (UID approval patch) templates

## What Remains

### Task 1: `setupKafka.ts` — Add UID label + auto-patch to retry loops

**What to do:**

1. Add a `workflowUid` parameter to every manifest builder function:
   - `makeDeployKafkaNodePool` — add `"workflows.argoproj.io/run-uid": makeStringTypeProxy(args.workflowUid)` to `metadata.labels`
   - `makeDeployKafkaClusterKraftNoAuthManifest` — same
   - `makeDeployKafkaClusterKraftScramManifest` — same
   - `makeKafkaTopicManifest` — same
   - `makeManagedKafkaUserManifest` — same (if it exists)

2. Thread `expr.getWorkflowValue("uid")` through the template chain. The top-level `*WithRetry` templates should pass it down. Example chain:
   - `deployKafkaClusterWithRetry` → `deployKafkaClusterKraftWithRetry` → `deployKafkaCluster` → `deployKafkaClusterKraftNoAuth` → `makeDeployKafkaClusterKraftNoAuthManifest`

3. Insert an auto-patch step in each `*WithRetry` template between `waitForFix` and `retryLoop`:
   ```typescript
   .addStep("patchApproval", ResourceManagement, "patchApprovalAnnotation", c =>
       c.register({
           resourceApiVersion: expr.literal("kafka.strimzi.io/v1"),
           resourceKind: expr.literal("KafkaNodePool"),
           resourceName: expr.literal("dual-role"),  // or threaded from input
       }),
       {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
   )
   ```
   Then change `retryLoop`'s `when` to depend on `c.patchApproval.status` instead of `c.waitForFix.status`.

4. Apply to all three retry templates: `deployKafkaNodePoolWithRetry`, `deployKafkaClusterKraftWithRetry`, `createKafkaTopicWithRetry`.

**Reference:** The `patchApprovalAnnotation` template is already in `resourceManagement.ts`. Import `ResourceManagement` from `./resourceManagement` if not already imported.

### Task 2: `setupCapture.ts` — Two-phase commit for proxy

**What to do:**

1. Create `makeProxyConfigManifest` — field-by-field mapping from `DENORMALIZED_PROXY_CONFIG` to the ProxyConfig CRD spec. Follow the pattern of `makeProxyDeploymentManifest`. Each field must use `expr.get()` / `expr.dig()` from the deserialized config and `makeDirectTypeProxy()` / `makeStringTypeProxy()`. Include the `run-uid` label:
   ```typescript
   labels: {
       "workflows.argoproj.io/run-uid": makeStringTypeProxy(expr.getWorkflowValue("uid"))
   }
   ```

2. Add an `applyProxyConfig` leaf template that does `action: "apply"` with the manifest.

3. Add `applyProxyConfigWithRetry` using the same pattern as `deployKafkaNodePoolWithRetry`:
   - `tryApply` with `continueOn: {failed: true}`
   - `waitForFix` suspend
   - `patchApproval` via `ResourceManagement.patchApprovalAnnotation` (apiVersion: `migrations.opensearch.org/v1alpha1`, kind: `ProxyConfig`)
   - `addStepToSelf` for retry

4. Create `setupProxyWithLifecycle` that wraps the existing `setupProxy`:
   ```
   applyProxyConfigWithRetry
     → patchResourcePhase("CapturedTraffic", proxyName, "Running")
     → setupProxy (existing steps, with continueOn: {failed: true})
     → if succeeded: patchResourcePhase("CapturedTraffic", proxyName, "Ready")
     → if failed: patchResourcePhase("CapturedTraffic", proxyName, "Error")
   ```

### Task 3: `fullMigration.ts` — Wire phase transitions

**What to do:**

In `setupSingleProxy`, replace the call to `SetupCapture.setupProxy` + `ResourceManagement.patchCapturedTrafficReady` with a single call to the new `SetupCapture.setupProxyWithLifecycle` (from Task 2). The phase transitions are now handled inside `setupCapture.ts`.

### Task 4: `test-vap-kafka.yaml` — Update Helm test

Replace value-based annotation tests with UID-based:

```bash
# Before:
kubectl annotate kafkanodepool "$TEST_POOL" \
  migrations.opensearch.org/approved-replicas=2 --overwrite

# After:
TEST_UID="test-uid-$(date +%s)"
kubectl label kafkanodepool "$TEST_POOL" \
  workflows.argoproj.io/run-uid="$TEST_UID" --overwrite
kubectl annotate kafkanodepool "$TEST_POOL" \
  migrations.opensearch.org/approved-by-run="$TEST_UID" --overwrite
```

Update the "wrong value" test to use a mismatched UID instead of a wrong replica count.

### Task 5: Update `setupKafkaRetryFlow.test.ts`

The existing test checks for `continueOn`, step names, and `when` conditions. After adding the `patchApproval` step, the step indices shift:
- `steps[0][0]` = tryApply (unchanged)
- `steps[1][0]` = waitForFix (unchanged)
- `steps[2][0]` = patchApproval (NEW)
- `steps[3][0]` = retryLoop (was steps[2][0])

Update assertions accordingly.
