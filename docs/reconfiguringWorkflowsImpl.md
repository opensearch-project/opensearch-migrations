# Implementation Reference: State-Aware Resource Management

> Companion to [reconfiguringWorkflowsNew.md](./reconfiguringWorkflowsNew.md) — this file contains the concrete file changes, code patterns, and migration steps.

---

## Files to Create/Modify

| File | Action | Status | Description |
|------|--------|--------|-------------|
| `.../templates/resources/validatingAdmissionPolicies.yaml` | **MODIFY** | ✅ Kafka done; Proxy + Lock-on-Complete TODO | Add Proxy VAP (UID approval), Lock-on-Complete for Snapshot CRDs, migrate Kafka VAPs to UID pattern |
| `.../templates/resources/migrationCrds.yaml` | **MODIFY** | TODO | Merge `migrationOptionsCrds.yaml` bodies into this file |
| `.../templates/resources/migrationOptionsCrds.yaml` | **DELETE** | TODO | Bodies merged into `migrationCrds.yaml` |
| `.../templates/resources/workflowRbac.yaml` | **MODIFY** | ⚠️ Partial | VAP ClusterRole done; add RBAC for merged CRD types (`proxyconfigs`, `replayerconfigs`, `snapshotconfigs`, `rfsconfigs` + `/status`) |
| `.../taskBuilder.ts` | — | ✅ DONE | `continueOn` support in TaskOpts |
| `.../setupKafka.ts` | **MODIFY** | ⚠️ Retry done; needs auto-patch step for UID approval | Add auto-patch step between suspend and retry |
| `.../setupCapture.ts` | **MODIFY** | TODO | Two-phase commit: apply CRD → deploy → mark Ready, with UID retry loop |
| `.../resourceManagement.ts` | **MODIFY** | TODO | Add status phase patch templates (`patchPhaseRunning`, `patchPhaseReady`, `patchPhaseError`) |
| `config-processor/.../migrationInitializer.ts` | — | ✅ DONE | Creates empty placeholder CRD resources (`spec: {}`, `status.phase: Initialized`) before workflow starts |

---

## Kafka VAP Migration to UID Approval

The deployed Kafka VAPs currently use value-based annotations (e.g., `approved-replicas=3`). These should be migrated to the Workflow UID pattern for consistency.

**Before (current):**
```yaml
- expression: |
    object.spec.replicas == oldObject.spec.replicas ||
    ('migrations.opensearch.org/approved-replicas' in object.metadata.annotations &&
     object.metadata.annotations['migrations.opensearch.org/approved-replicas'] == string(object.spec.replicas))
```

**After (UID pattern):**
```yaml
- expression: |
    object.spec.replicas == oldObject.spec.replicas ||
    (has(object.metadata.annotations) &&
     'migrations.opensearch.org/approved-by-run' in object.metadata.annotations &&
     has(object.metadata.labels) &&
     'workflows.argoproj.io/run-uid' in object.metadata.labels &&
     object.metadata.annotations['migrations.opensearch.org/approved-by-run'] == object.metadata.labels['workflows.argoproj.io/run-uid'])
```

This applies to all existing Kafka VAP checks: `kafkanodepool-policy` (replicas, storage.size), `kafka-policy` (version), `kafkatopic-policy` (replicas). The `messageExpression` should also be updated to say `"Gated Change [replicas]: ..."` instead of `"Major Change Blocked [replicas]: ..."`.

The Helm test (`test-vap-kafka.yaml`) must be updated to use the UID annotation pattern instead of value-based annotations.

---

## The Retry Loop Pattern (Reference: `setupKafka.ts`)

All retry loops follow this established pattern. The key addition for Phase 2 is the **auto-patch step** between suspend and retry.

### Current Pattern (Phase 1 — Kafka)

```typescript
// From setupKafka.ts — this is the deployed, working pattern

.addTemplate("suspendForRetry", t => t
    .addRequiredInput("name", typeToken<string>())
    .addSuspend()
)

.addTemplate("deployKafkaNodePoolWithRetry", t => t
    .addRequiredInput("clusterName", typeToken<string>())
    .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())
    .addOptionalInput("retryGroupName_view", c => "Apply")

    .addSteps(b => b
        .addStep("tryApply", INTERNAL, "deployKafkaNodePool", c =>
            c.register({
                clusterName: b.inputs.clusterName,
                clusterConfig: b.inputs.clusterConfig,
            }),
            {continueOn: {failed: true}}
        )
        .addStep("waitForFix", INTERNAL, "suspendForRetry", c =>
            c.register({
                name: expr.literal("KafkaNodePool")
            }),
            {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
        )
        .addStepToSelf("retryLoop", c =>
            c.register({
                clusterName: b.inputs.clusterName,
                clusterConfig: b.inputs.clusterConfig,
                retryGroupName_view: b.inputs.retryGroupName_view,
            }),
            {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
        )
    )
)
```

### Phase 2 Pattern — With Auto-Patch UID Approval

The only structural change is inserting an **auto-patch step** between `waitForFix` (suspend) and `retryLoop` (recursive self-call). When the user clicks Resume, the workflow patches the CRD resource with the approval annotation before retrying.

```typescript
// New template for patching the UID approval annotation onto a CRD resource
.addTemplate("patchApprovalAnnotation", t => t
    .addRequiredInput("resourceKind", typeToken<string>())
    .addRequiredInput("resourceName", typeToken<string>())
    .addRequiredInput("workflowUid", typeToken<string>())
    .addResourceTask(b => b
        .setDefinition({
            action: "patch",
            flags: ["--type", "merge"],
            manifest: {
                apiVersion: "migrations.opensearch.org/v1alpha1",
                kind: b.inputs.resourceKind,
                metadata: {
                    name: b.inputs.resourceName,
                    annotations: {
                        "migrations.opensearch.org/approved-by-run":
                            makeStringTypeProxy(b.inputs.workflowUid)
                    }
                }
            }
        }))
    .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
)

// Updated retry loop with auto-patch
.addTemplate("deployProxyWithRetry", t => t
    .addRequiredInput("proxyName", typeToken<string>())
    .addRequiredInput("proxyConfig", typeToken<...>())
    .addOptionalInput("retryGroupName_view", c => "Apply")

    .addSteps(b => b
        .addStep("tryApply", INTERNAL, "deployProxy", c =>
            c.register({ /* ... */ }),
            {continueOn: {failed: true}}
        )
        .addStep("waitForFix", INTERNAL, "suspendForRetry", c =>
            c.register({ name: expr.literal("CaptureProxy") }),
            {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
        )
        // NEW: Auto-patch the UID approval annotation after user clicks Resume
        .addStep("patchApproval", INTERNAL, "patchApprovalAnnotation", c =>
            c.register({
                resourceKind: expr.literal("ProxyConfig"),
                resourceName: b.inputs.proxyName,
                workflowUid: expr.workflowUid(),  // {{workflow.uid}}
            }),
            {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
        )
        .addStepToSelf("retryLoop", c =>
            c.register({
                proxyName: b.inputs.proxyName,
                proxyConfig: b.inputs.proxyConfig,
                retryGroupName_view: b.inputs.retryGroupName_view,
            }),
            {when: c => ({templateExp: expr.equals(c.patchApproval.status, "Succeeded")})}
        )
    )
)
```

**Key details:**
- The `patchApprovalAnnotation` template uses `action: "patch"` (not apply) to surgically add the annotation without touching the spec.
- `{{workflow.uid}}` is an Argo expression — it must flow through the builder as a `BaseExpression<string>`. If `expr.workflowUid()` doesn't exist yet, add it as a helper that emits the raw Argo expression string.
- The `run-uid` label goes on the **CRD resource** (e.g., ProxyConfig), not on the workflow pod. The manifest builder must include `labels: { "workflows.argoproj.io/run-uid": {{workflow.uid}} }` on the resource being applied.

---

## The Two-Phase Commit (Proxy Example)

### Phase 1: Apply / Assert

The workflow applies the CRD resource with the full spec from user params. The manifest must include the `run-uid` label:

```typescript
function makeProxyConfigManifest(args: {
    proxyName: BaseExpression<string>,
    proxyConfig: BaseExpression<...>,
    workflowUid: BaseExpression<string>,
}) {
    return {
        apiVersion: "migrations.opensearch.org/v1alpha1",
        kind: "ProxyConfig",
        metadata: {
            name: args.proxyName,
            labels: {
                "workflows.argoproj.io/run-uid":
                    makeStringTypeProxy(args.workflowUid)
            }
        },
        spec: makeDirectTypeProxy(args.proxyConfig)
    };
}
```

- If the resource is empty (placeholder from initializer) → sets spec, proceeds.
- If spec matches etcd → `200 OK` no-op, proceeds.
- If a Gated field changed → VAP returns 403, enters retry loop.
- If an Impossible field changed → VAP returns 403, workflow hard-fails.
- If status is `Completed` and spec differs → Lock-on-Complete rejects, workflow hard-fails.

### Phase 2: Deploy & Wait

After the CRD apply succeeds, the workflow:

1. Patches `status.phase = Running` (via `/status` subresource)
2. Deploys the actual infrastructure (Deployment, Service, etc.)
3. Waits for readiness using Argo's native resource waiter

### Phase 3: Mark Done

On success:
- Long-lived (Proxy): patch `status.phase = Ready`
- Terminal (Snapshot): patch `status.phase = Completed`

On failure:
- Patch `status.phase = Error` — the resource is "poisoned" per the Fail Forward principle.

**Status patches use the `/status` subresource:**
```typescript
.addTemplate("patchPhaseRunning", t => t
    .addRequiredInput("resourceKind", typeToken<string>())
    .addRequiredInput("resourceName", typeToken<string>())
    .addResourceTask(b => b
        .setDefinition({
            action: "patch",
            flags: ["--type", "merge", "--subresource=status"],
            manifest: {
                apiVersion: "migrations.opensearch.org/v1alpha1",
                kind: b.inputs.resourceKind,
                metadata: { name: b.inputs.resourceName },
                status: { phase: "Running" }
            }
        }))
    .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
)
```

Create similar templates for `patchPhaseReady`, `patchPhaseCompleted`, and `patchPhaseError`.

---

## CRD Merge

Merge the bodies from `migrationOptionsCrds.yaml` (ProxyConfig, ReplayerConfig, SnapshotConfig, RfsConfig) into `migrationCrds.yaml` alongside the existing CapturedTraffic, DataSnapshot, SnapshotMigration definitions. Then delete `migrationOptionsCrds.yaml`.

After the merge, update the RBAC in `workflowRbac.yaml` to add the new resource types to the `workflow-deployer-role`:

```yaml
# Add to the existing migrations.opensearch.org rule
- apiGroups: ["migrations.opensearch.org"]
  resources: [
    "capturedtraffics", "datasnapshots", "snapshotmigrations",
    "capturedtraffics/status", "datasnapshots/status", "snapshotmigrations/status",
    "proxyconfigs", "replayerconfigs", "snapshotconfigs", "rfsconfigs",
    "proxyconfigs/status", "replayerconfigs/status", "snapshotconfigs/status", "rfsconfigs/status"
  ]
  verbs: ["create", "get", "list", "watch", "update", "patch", "delete"]
```

---

## Initialization

The `MigrationInitializer` class (`config-processor/src/migrationInitializer.ts`) creates empty placeholder CRD resources before the Argo workflow starts. It runs via the `initialize` CLI command in `cliRouter.ts`, invoked by the initialization shell script.

Each resource is created with `spec: {}` and `status: { phase: "Initialized" }`. This ensures downstream `waitFor` steps in the workflow can find the resources. The workflow's first apply will populate the spec with real parameters.
