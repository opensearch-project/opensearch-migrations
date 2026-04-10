# Implementation Reference: State-Aware Resource Management

> Companion to [reconfiguringWorkflows.md](./reconfiguringWorkflows.md) — this file contains the concrete file changes, code patterns, and migration steps.

---

## Files to Create/Modify

| File | Action | Status | Description |
|------|--------|--------|-------------|
| `.../validatingAdmissionPolicies.yaml` | **MODIFY** | ✅ Done | Kafka VAPs use UID pattern; CapturedTraffic, DataSnapshot, SnapshotMigration, Lock-on-Complete VAPs added |
| `.../migrationCrds.yaml` | **MODIFY** | ✅ Done | Unified CRDs: CapturedTraffic (proxy config + lifecycle), DataSnapshot (snapshot config + lifecycle), SnapshotMigration (RFS + metadata config + lifecycle). No separate config CRDs. |
| `.../migrationOptionsCrds.yaml` | **DELETE** | ✅ Done | Superseded by unified CRDs in `migrationCrds.yaml` |
| `.../workflowRbac.yaml` | **MODIFY** | ✅ Done | RBAC for unified CRD types |
| `.../test-vap-kafka.yaml` | **MODIFY** | ✅ Done | Uses UID annotation pattern |
| `expression.ts` | — | ✅ Already has `getWorkflowValue("uid")` | Emits `{{workflow.uid}}` — no new helper needed |
| `taskBuilder.ts` | — | ✅ Done | `continueOn` support in TaskOpts |
| `setupKafka.ts` | **MODIFY** | ✅ Done | Auto-patch steps + UID labels on all Kafka manifests |
| `setupCapture.ts` | **MODIFY** | ✅ Done | Applies CapturedTraffic CRD with retry loop, lifecycle phase transitions |
| `fullMigration.ts` | **MODIFY** | ✅ Done | Uses `setupProxyWithLifecycle` |
| `resourceManagement.ts` | **MODIFY** | ✅ Done | Generic `patchResourcePhase` + `patchApprovalAnnotation` templates |
| `migrationInitializer.ts` | — | ✅ Done | Creates placeholder CRDs (`spec: {}`, `status.phase: Initialized`) |

---

## Execution Order

These changes have dependencies. Implement in this order:

1. **CRD + RBAC** ✅ — Unified CRDs in `migrationCrds.yaml` (CapturedTraffic, DataSnapshot, SnapshotMigration). No separate config CRDs.
2. **`resourceManagement.ts`** ✅ — Generic phase patch + approval annotation templates.
3. **`validatingAdmissionPolicies.yaml`** ✅ — All VAPs use UID pattern. CapturedTraffic, DataSnapshot, SnapshotMigration, Lock-on-Complete policies added.
4. **`setupKafka.ts`** ✅ — Auto-patch steps + UID labels on all Kafka manifests.
5. **`setupCapture.ts`** ✅ — Applies CapturedTraffic CRD directly (no separate ProxyConfig). Lifecycle phase transitions.
6. **`fullMigration.ts`** ✅ — Uses `setupProxyWithLifecycle`.
7. **`test-vap-kafka.yaml`** ✅ — Uses UID annotation pattern.

---

## 1. CRD Design (Unified)

Each migration component uses a single CRD that holds both configuration (`.spec`) and lifecycle state (`.status.phase`). There are no separate "config" and "resource" CRDs — this follows the standard Kubernetes pattern.

The three resource types in `migrationCrds.yaml`:

* **`CapturedTraffic`** — proxy config fields directly in `.spec` (listenPort, noCapture, enableMSKAuth, tls, podReplicas, etc.)
* **`DataSnapshot`** — snapshot config fields directly in `.spec` (snapshotPrefix, indexAllowlist, maxSnapshotRateMbPerNode, etc.)
* **`SnapshotMigration`** — nested config under `.spec.documentBackfillConfig` (RFS fields) and `.spec.metadataMigrationConfig`

RBAC in `workflowRbac.yaml` covers these three types plus their `/status` subresources:

```yaml
- apiGroups: ["migrations.opensearch.org"]
  resources: [
    "capturedtraffics", "datasnapshots", "snapshotmigrations",
    "capturedtraffics/status", "datasnapshots/status", "snapshotmigrations/status"
  ]
  verbs: ["create", "get", "list", "watch", "update", "patch", "delete"]
```

---

## 2. Phase Patch Templates (`resourceManagement.ts`)

Add generic templates for transitioning `status.phase` via the `/status` subresource. These are used by all workflow files.

```typescript
// Generic phase patch — parameterized by kind, name, and target phase
.addTemplate("patchResourcePhase", t => t
    .addRequiredInput("resourceKind", typeToken<string>())
    .addRequiredInput("resourceName", typeToken<string>())
    .addRequiredInput("phase", typeToken<string>())
    .addResourceTask(b => b
        .setDefinition({
            action: "patch",
            flags: ["--type", "merge", "--subresource=status"],
            manifest: {
                apiVersion: CRD_API_VERSION,
                kind: b.inputs.resourceKind,
                metadata: { name: b.inputs.resourceName },
                status: { phase: makeStringTypeProxy(b.inputs.phase) }
            }
        }))
    .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
)
```

This single template replaces the need for separate `patchPhaseRunning`, `patchPhaseReady`, etc. Callers pass the target phase as a parameter.

Also add the existing `patchCapturedTrafficReady` and `patchDataSnapshotReady` templates should be refactored to use this generic template, but that can be done as a follow-up to avoid breaking existing workflows.

---

## 3. ValidatingAdmissionPolicies

### 3a. Migrate Kafka VAPs to UID Approval

All existing Kafka VAP checks currently use value-based annotations. Migrate to the UID pattern.

**Before (current — each gated check):**
```yaml
- expression: |
    object.spec.replicas == oldObject.spec.replicas ||
    ('migrations.opensearch.org/approved-replicas' in object.metadata.annotations &&
     object.metadata.annotations['migrations.opensearch.org/approved-replicas'] == string(object.spec.replicas))
```

**After (UID pattern — same check):**
```yaml
- expression: |
    object.spec.replicas == oldObject.spec.replicas ||
    (has(object.metadata.annotations) &&
     'migrations.opensearch.org/approved-by-run' in object.metadata.annotations &&
     has(object.metadata.labels) &&
     'workflows.argoproj.io/run-uid' in object.metadata.labels &&
     object.metadata.annotations['migrations.opensearch.org/approved-by-run'] ==
       object.metadata.labels['workflows.argoproj.io/run-uid'])
  messageExpression: |
    "Gated Change [replicas]: ..."
```

Apply this transformation to:
- `kafkanodepool-policy`: replicas, storage.size checks
- `kafka-policy`: version check
- `kafkatopic-policy`: replicas check

Impossible checks (storage.type, roles, partition decrease, cluster assignment) stay unchanged — they have no approval escape hatch.

Update all `messageExpression` strings from `"Major Change Blocked [...]"` to `"Gated Change [...]"`.

### 3b. CapturedTraffic VAP

Policy for `capturedtraffics` in the `migrations.opensearch.org` API group:

```yaml
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata:
  name: {{ .Release.Namespace }}-capturedtraffic-policy
spec:
  failurePolicy: Fail
  matchConstraints:
    resourceRules:
    - apiGroups: ["migrations.opensearch.org"]
      apiVersions: ["v1alpha1"]
      operations: ["UPDATE"]
      resources: ["capturedtraffics"]
  validations:
    # Impossible: listenPort
    - expression: |
        !has(oldObject.spec.listenPort) || !has(object.spec.listenPort) ||
        object.spec.listenPort == oldObject.spec.listenPort
      message: "Impossible: listenPort cannot be changed. Delete and recreate the proxy."
    # Impossible: internetFacing
    - expression: |
        !has(oldObject.spec.internetFacing) || !has(object.spec.internetFacing) ||
        object.spec.internetFacing == oldObject.spec.internetFacing
      message: "Impossible: internetFacing cannot be changed. Delete and recreate the proxy."
    # Gated: noCapture, enableMSKAuth, tls.mode, suppressCapture* fields
    # All gated fields share a single UID check
    - expression: |
        (
          (!has(object.spec.noCapture) || !has(oldObject.spec.noCapture) ||
           object.spec.noCapture == oldObject.spec.noCapture) &&
          (!has(object.spec.enableMSKAuth) || !has(oldObject.spec.enableMSKAuth) ||
           object.spec.enableMSKAuth == oldObject.spec.enableMSKAuth) &&
          (!has(object.spec.tls) || !has(oldObject.spec.tls) ||
           object.spec.tls == oldObject.spec.tls) &&
          (!has(object.spec.suppressCaptureForHeaderMatch) || !has(oldObject.spec.suppressCaptureForHeaderMatch) ||
           object.spec.suppressCaptureForHeaderMatch == oldObject.spec.suppressCaptureForHeaderMatch) &&
          (!has(object.spec.suppressCaptureForMethod) || !has(oldObject.spec.suppressCaptureForMethod) ||
           object.spec.suppressCaptureForMethod == oldObject.spec.suppressCaptureForMethod) &&
          (!has(object.spec.suppressCaptureForUriPath) || !has(oldObject.spec.suppressCaptureForUriPath) ||
           object.spec.suppressCaptureForUriPath == oldObject.spec.suppressCaptureForUriPath) &&
          (!has(object.spec.suppressMethodAndPath) || !has(oldObject.spec.suppressMethodAndPath) ||
           object.spec.suppressMethodAndPath == oldObject.spec.suppressMethodAndPath)
        ) ||
        (has(object.metadata.annotations) &&
         'migrations.opensearch.org/approved-by-run' in object.metadata.annotations &&
         has(object.metadata.labels) &&
         'workflows.argoproj.io/run-uid' in object.metadata.labels &&
         object.metadata.annotations['migrations.opensearch.org/approved-by-run'] ==
           object.metadata.labels['workflows.argoproj.io/run-uid'])
      message: "Gated changes detected on CapturedTraffic. Workflow approval is required."
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicyBinding
metadata:
  name: {{ .Release.Namespace }}-capturedtraffic-binding
spec:
  policyName: {{ .Release.Namespace }}-capturedtraffic-policy
  validationActions: [Deny]
  matchResources:
    namespaceSelector:
      matchExpressions:
      - key: kubernetes.io/metadata.name
        operator: In
        values: ["{{ .Release.Namespace }}"]
```

### 3c. Add Lock-on-Complete VAP

Applies to all terminal resources (`datasnapshots` and `snapshotmigrations`). Freezes the entire spec once the work is done:

```yaml
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata:
  name: {{ .Release.Namespace }}-lock-on-complete-policy
spec:
  failurePolicy: Fail
  matchConstraints:
    resourceRules:
    - apiGroups: ["migrations.opensearch.org"]
      apiVersions: ["v1alpha1"]
      operations: ["UPDATE"]
      resources: ["datasnapshots", "snapshotmigrations"]
  validations:
    - expression: |
        !has(oldObject.status) ||
        !has(oldObject.status.phase) ||
        oldObject.status.phase != 'Completed' ||
        (object.spec == oldObject.spec)
      message: "Consistency Guard: This resource is 'Completed'. The specification is permanently sealed to maintain provenance. Delete the resource to run a new job with these parameters."
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicyBinding
metadata:
  name: {{ .Release.Namespace }}-lock-on-complete-binding
spec:
  policyName: {{ .Release.Namespace }}-lock-on-complete-policy
  validationActions: [Deny]
  matchResources:
    namespaceSelector:
      matchExpressions:
      - key: kubernetes.io/metadata.name
        operator: In
        values: ["{{ .Release.Namespace }}"]
```

### 3d. Add DataSnapshot VAP (All-Impossible)

Since every field on DataSnapshot is Impossible, no spec change is ever allowed once the resource leaves `Initialized`. This is stricter than Lock-on-Complete — it also covers the `Running` state (changing snapshot params mid-flight would corrupt the output).

```yaml
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata:
  name: {{ .Release.Namespace }}-datasnapshot-policy
spec:
  failurePolicy: Fail
  matchConstraints:
    resourceRules:
    - apiGroups: ["migrations.opensearch.org"]
      apiVersions: ["v1alpha1"]
      operations: ["UPDATE"]
      resources: ["datasnapshots"]
  validations:
    # Allow spec changes only when status is Initialized (first apply from workflow)
    - expression: |
        !has(oldObject.status) ||
        !has(oldObject.status.phase) ||
        oldObject.status.phase == 'Initialized' ||
        (object.spec == oldObject.spec)
      message: "Impossible: DataSnapshot spec cannot be changed after initial apply. Delete and recreate to use different parameters."
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicyBinding
metadata:
  name: {{ .Release.Namespace }}-datasnapshot-binding
spec:
  policyName: {{ .Release.Namespace }}-datasnapshot-policy
  validationActions: [Deny]
  matchResources:
    namespaceSelector:
      matchExpressions:
      - key: kubernetes.io/metadata.name
        operator: In
        values: ["{{ .Release.Namespace }}"]
```

### 3e. Add SnapshotMigration VAP (Impossible + Gated fields)

SnapshotMigration has a mix: metadata migration fields are all Impossible, RFS has Impossible, Gated, and Safe. Safe fields need no CEL. Lock-on-Complete (3c) handles the `Completed` state.

```yaml
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata:
  name: {{ .Release.Namespace }}-snapshotmigration-policy
spec:
  failurePolicy: Fail
  matchConstraints:
    resourceRules:
    - apiGroups: ["migrations.opensearch.org"]
      apiVersions: ["v1alpha1"]
      operations: ["UPDATE"]
      resources: ["snapshotmigrations"]
  validations:
    # Impossible: entire metadataMigrationConfig block
    - expression: |
        !has(oldObject.spec.metadataMigrationConfig) ||
        !has(object.spec.metadataMigrationConfig) ||
        object.spec.metadataMigrationConfig == oldObject.spec.metadataMigrationConfig
      message: "Impossible: Metadata migration configuration cannot be changed. Delete and recreate to use different parameters."
    # Impossible: RFS fields that define what data is migrated
    - expression: |
        (!has(oldObject.spec.documentBackfillConfig) || !has(object.spec.documentBackfillConfig) ||
         (
           (!has(object.spec.documentBackfillConfig.indexAllowlist) ||
            !has(oldObject.spec.documentBackfillConfig.indexAllowlist) ||
            object.spec.documentBackfillConfig.indexAllowlist == oldObject.spec.documentBackfillConfig.indexAllowlist) &&
           (!has(object.spec.documentBackfillConfig.allowLooseVersionMatching) ||
            !has(oldObject.spec.documentBackfillConfig.allowLooseVersionMatching) ||
            object.spec.documentBackfillConfig.allowLooseVersionMatching == oldObject.spec.documentBackfillConfig.allowLooseVersionMatching) &&
           (!has(object.spec.documentBackfillConfig.docTransformerConfigBase64) ||
            !has(oldObject.spec.documentBackfillConfig.docTransformerConfigBase64) ||
            object.spec.documentBackfillConfig.docTransformerConfigBase64 == oldObject.spec.documentBackfillConfig.docTransformerConfigBase64)
         ))
      message: "Impossible: Document backfill data-defining fields (indexAllowlist, allowLooseVersionMatching, docTransformerConfigBase64) cannot be changed. Delete and recreate."
    # Gated: RFS operational fields that affect performance/behavior
    - expression: |
        (!has(oldObject.spec.documentBackfillConfig) || !has(object.spec.documentBackfillConfig) ||
         (
           (!has(object.spec.documentBackfillConfig.initialLeaseDuration) ||
            !has(oldObject.spec.documentBackfillConfig.initialLeaseDuration) ||
            object.spec.documentBackfillConfig.initialLeaseDuration == oldObject.spec.documentBackfillConfig.initialLeaseDuration) &&
           (!has(object.spec.documentBackfillConfig.maxConnections) ||
            !has(oldObject.spec.documentBackfillConfig.maxConnections) ||
            object.spec.documentBackfillConfig.maxConnections == oldObject.spec.documentBackfillConfig.maxConnections) &&
           (!has(object.spec.documentBackfillConfig.maxShardSizeBytes) ||
            !has(oldObject.spec.documentBackfillConfig.maxShardSizeBytes) ||
            object.spec.documentBackfillConfig.maxShardSizeBytes == oldObject.spec.documentBackfillConfig.maxShardSizeBytes)
         ))
        ||
        (has(object.metadata.annotations) &&
         'migrations.opensearch.org/approved-by-run' in object.metadata.annotations &&
         has(object.metadata.labels) &&
         'workflows.argoproj.io/run-uid' in object.metadata.labels &&
         object.metadata.annotations['migrations.opensearch.org/approved-by-run'] ==
           object.metadata.labels['workflows.argoproj.io/run-uid'])
      message: "Gated changes detected on SnapshotMigration backfill config. Workflow approval is required."
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicyBinding
metadata:
  name: {{ .Release.Namespace }}-snapshotmigration-binding
spec:
  policyName: {{ .Release.Namespace }}-snapshotmigration-policy
  validationActions: [Deny]
  matchResources:
    namespaceSelector:
      matchExpressions:
      - key: kubernetes.io/metadata.name
        operator: In
        values: ["{{ .Release.Namespace }}"]
```

---

## 4. Kafka Retry Loop Update (`setupKafka.ts`)

Two changes to the existing Kafka retry templates:

### 4a. Add `run-uid` label to Kafka manifests

Every manifest builder function (`makeDeployKafkaNodePool`, `makeDeployKafkaClusterKraftManifest`, `makeKafkaTopicManifest`) must accept a `workflowUid` parameter and include it as a label:

```typescript
function makeDeployKafkaNodePool(args: {
    clusterName: BaseExpression<string>,
    replicas: BaseExpression<number>,
    workflowUid: BaseExpression<string>,  // NEW
}) {
    return {
        // ... existing fields ...
        metadata: {
            name: "dual-role",
            labels: {
                "strimzi.io/cluster": args.clusterName,
                "workflows.argoproj.io/run-uid": makeStringTypeProxy(args.workflowUid)  // NEW
            }
        },
        // ...
    };
}
```

Thread `expr.getWorkflowValue("uid")` from the top-level templates down through all callers.

### 4b. Insert auto-patch step in retry loops

Add a `patchApprovalAnnotation` template and insert it between `waitForFix` and `retryLoop` in each `*WithRetry` template. The pattern:

```typescript
// Shared template — can live in setupKafka.ts or resourceManagement.ts
.addTemplate("patchApprovalAnnotation", t => t
    .addRequiredInput("resourceApiVersion", typeToken<string>())
    .addRequiredInput("resourceKind", typeToken<string>())
    .addRequiredInput("resourceName", typeToken<string>())
    .addResourceTask(b => b
        .setDefinition({
            action: "patch",
            flags: ["--type", "merge"],
            manifest: {
                apiVersion: makeStringTypeProxy(b.inputs.resourceApiVersion),
                kind: makeStringTypeProxy(b.inputs.resourceKind),
                metadata: {
                    name: b.inputs.resourceName,
                    annotations: {
                        "migrations.opensearch.org/approved-by-run":
                            makeStringTypeProxy(expr.getWorkflowValue("uid"))
                    }
                }
            }
        }))
    .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
)
```

Then update each retry template (example for KafkaNodePool):

```typescript
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
            c.register({ name: expr.literal("KafkaNodePool") }),
            {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
        )
        // NEW: auto-patch UID approval after user clicks Resume
        .addStep("patchApproval", INTERNAL, "patchApprovalAnnotation", c =>
            c.register({
                resourceApiVersion: expr.literal("kafka.strimzi.io/v1"),
                resourceKind: expr.literal("KafkaNodePool"),
                resourceName: expr.literal("dual-role"),
            }),
            {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
        )
        .addStepToSelf("retryLoop", c =>
            c.register({
                clusterName: b.inputs.clusterName,
                clusterConfig: b.inputs.clusterConfig,
                retryGroupName_view: b.inputs.retryGroupName_view,
            }),
            {when: c => ({templateExp: expr.equals(c.patchApproval.status, "Succeeded")})}
        )
    )
)
```

Apply the same pattern to `deployKafkaClusterKraftWithRetry` and `createKafkaTopicWithRetry`.

---

## 5. Proxy Two-Phase Commit (`setupCapture.ts`)

### 5a. Add CapturedTraffic CRD manifest builder

The manifest must map each spec field individually, following the same pattern as `makeDeployKafkaNodePool`, `makeProxyDeploymentManifest`, etc. A bulk `spec: makeDirectTypeProxy(...)` won't work — the builder needs each field explicitly for proper expression/literal handling.

```typescript
// Build out field-by-field from DENORMALIZED_PROXY_CONFIG,
// extracting each field via expr.get()/expr.dig() from the deserialized config.
//
// function makeCapturedTrafficManifest(args: {
//     proxyName: BaseExpression<string>,
//     proxyConfig: BaseExpression<Serialized<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>>,
// }) {
//     const config = expr.deserializeRecord(args.proxyConfig);
//     const proxyOpts = expr.get(config, "proxyConfig");
//     return {
//         apiVersion: "migrations.opensearch.org/v1alpha1",
//         kind: "CapturedTraffic",
//         metadata: {
//             name: args.proxyName,
//             labels: {
//                 "workflows.argoproj.io/run-uid":
//                     makeStringTypeProxy(expr.getWorkflowValue("uid"))
//             }
//         },
//         spec: {
//             listenPort: makeDirectTypeProxy(expr.get(proxyOpts, "listenPort")),
//             noCapture: makeDirectTypeProxy(expr.get(proxyOpts, "noCapture")),
//             enableMSKAuth: makeDirectTypeProxy(expr.get(proxyOpts, "enableMSKAuth")),
//             podReplicas: makeDirectTypeProxy(expr.get(proxyOpts, "podReplicas")),
//             numThreads: makeDirectTypeProxy(expr.get(proxyOpts, "numThreads")),
//             // ... every field from USER_PROXY_OPTIONS, one by one
//         }
//     };
// }
```

### 5b. Add apply + retry template for CapturedTraffic

Follow the same pattern as Kafka. The `applyCapturedTraffic` leaf template does the apply; `applyCapturedTrafficWithRetry` wraps it with the suspend → auto-patch → retry loop.

### 5c. Update `setupProxy` orchestration

The current `setupProxy` template does: createKafkaTopic → deployService + provisionCert → waitForCert → deployProxy.

The new version wraps this with CRD lifecycle management:

```
applyProxyConfigWithRetry (Phase 1: assert CRD spec)
  → patchResourcePhase(Running)
  → [existing setupProxy steps: topic, service, cert, deployment]
  → patchResourcePhase(Ready)
```

**Error handling (Fail Forward):** Use Argo's `onExit` handler on the template to catch failures. The `onExit` template runs regardless of success or failure, and can check the workflow status to decide whether to patch `Ready` or `Error`:

```typescript
// Option A: onExit handler (preferred — catches any failure in the subgraph)
.addTemplate("setupProxyWithLifecycle", t => t
    .addRequiredInput("proxyName", typeToken<string>())
    // ... other inputs ...
    .addSteps(b => b
        .addStep("assertCrd", INTERNAL, "applyProxyConfigWithRetry", c => /* ... */)
        .addStep("markRunning", ResourceManagement, "patchResourcePhase", c =>
            c.register({
                resourceKind: expr.literal("CapturedTraffic"),
                resourceName: b.inputs.proxyName,
                phase: expr.literal("Running"),
            })
        )
        .addStep("deploy", INTERNAL, "setupProxy", c => /* existing steps */,
            {continueOn: {failed: true}}
        )
        // If deploy succeeded → Ready; if failed → Error
        .addStep("markReady", ResourceManagement, "patchResourcePhase", c =>
            c.register({
                resourceKind: expr.literal("CapturedTraffic"),
                resourceName: b.inputs.proxyName,
                phase: expr.literal("Ready"),
            }),
            {when: c => ({templateExp: expr.equals(c.deploy.status, "Succeeded")})}
        )
        .addStep("markError", ResourceManagement, "patchResourcePhase", c =>
            c.register({
                resourceKind: expr.literal("CapturedTraffic"),
                resourceName: b.inputs.proxyName,
                phase: expr.literal("Error"),
            }),
            {when: c => ({templateExp: expr.equals(c.deploy.status, "Failed")})}
        )
    )
)
```

The `continueOn: {failed: true}` on the deploy step lets the workflow continue to the phase-marking steps even on failure. The `when` conditions route to either `Ready` or `Error`.

---

## 6. Wire into `fullMigration.ts`

The current `setupSingleProxy` template ends with `patchCapturedTrafficReady`. This should be replaced by the new phase-aware flow from `setupCapture.ts`. The `patchCapturedTrafficReady` call in `fullMigration.ts` can be removed since the phase transition is now handled inside `setupCapture.ts`.

---

## 7. Update Helm Test (`test-vap-kafka.yaml`)

Replace value-based annotation tests with UID-based tests:

**Before:**
```bash
kubectl annotate kafkanodepool "$TEST_POOL" \
  migrations.opensearch.org/approved-replicas=2 --overwrite
```

**After:**
```bash
# Simulate workflow UID label + approval annotation
TEST_UID="test-uid-$(date +%s)"
kubectl label kafkanodepool "$TEST_POOL" \
  workflows.argoproj.io/run-uid="$TEST_UID" --overwrite
kubectl annotate kafkanodepool "$TEST_POOL" \
  migrations.opensearch.org/approved-by-run="$TEST_UID" --overwrite
```

Also add a test that a mismatched UID is rejected (label says one UID, annotation says another).

---

## Design Decisions & Known Limitations

### Gated vs Impossible failure distinction

The current retry loop suspends on **any** failure (`expr.equals(c.tryApply.status, "Failed")`). It does not distinguish Gated (recoverable via approval) from Impossible (unrecoverable). If a user clicks Resume on an Impossible failure, the auto-patch runs, the retry fails again, and the workflow re-suspends.

This is acceptable for now — the VAP error message clearly states "Impossible: ..." vs "Gated: ...", and the user can cancel the workflow. A future improvement could parse the error message with `expr.regexMatch` to hard-fail immediately on Impossible errors.

### `expr.getWorkflowValue("uid")`

The expression builder already supports `expr.getWorkflowValue("uid")` which emits `{{workflow.uid}}`. This is an Argo template variable resolved at runtime. No new helper is needed.

### Initialization

The `MigrationInitializer` class (`config-processor/src/migrationInitializer.ts`) creates empty placeholder CRD resources before the Argo workflow starts. It runs via the `initialize` CLI command in `cliRouter.ts`, invoked by the initialization shell script (`scripts/updateWorkflowTemplates.sh` or equivalent).

Each resource is created with `spec: {}` and `status: { phase: "Initialized" }`. The workflow's first apply populates the spec with real parameters.
