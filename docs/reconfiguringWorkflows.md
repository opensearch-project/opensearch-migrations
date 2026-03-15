# State-Aware Resource Management for Migration Workflows

> **Status:** Implementation Plan (Ready for Review)

## Context

The migration workflow currently operates in an "always create" mode using `kubectl apply`. This works for initial deployments but creates problems when:

1. **Re-running workflows** - Should skip creation if resource exists with same config
2. **Configuration drift** - Some changes (replica count) require careful handling; others (storage type) should be blocked
3. **Protecting production** - Dangerous changes shouldn't silently apply

**First implementation target**: Kafka cluster (`Kafka`), `KafkaNodePool`, and `KafkaTopic` resources from Strimzi. These establish patterns for later resources (CapturedTraffic, DataSnapshot, SnapshotMigration).

### Goals

- Use Kubernetes ValidatingAdmissionPolicies (CEL) to enforce change rules at the API level
- Route policy rejections to suspend gates where users can fix and retry
- Keep parallel workflow branches running while one waits for user action
- Follow existing suspend/approval patterns from `metadataMigration.ts`

---

## Architecture Overview

```mermaid
flowchart TB
    subgraph Workflow["Argo Workflow"]
        A[Apply Kafka Resource] -->|success| B[Continue to next step]
        A -->|403 Forbidden| C{Parse Error}
        C -->|"Major Change Blocked"| D[Suspend & Wait]
        C -->|"Immutable Violation"| E[Fail with clear message]
        C -->|"Other Error"| F[Fail - real error]
        D -->|"User clicks Resume"| G[Re-attempt same apply]
        G -->|success| B
        G -->|still blocked| D
    end

    subgraph User["User Actions (outside workflow)"]
        U1[Fix config to match deployed state]
        U2[OR: kubectl annotate with specific approval flag]
    end

    subgraph K8s["Kubernetes API Server"]
        VAP[ValidatingAdmissionPolicy]
        API[API Server]
        Strimzi[Strimzi Operator]
    end

    D -.->|"displays error"| U1
    D -.->|"displays error"| U2
    U1 -.->|"then resume"| G
    U2 -.->|"then resume"| G

    A -.->|kubectl apply| API
    API -->|intercept| VAP
    VAP -->|allowed| Strimzi
    VAP -->|rejected| A
```

### Key Design Decision: "Retry" Model

The workflow does **not** automatically inject approval annotations. When a major change is blocked:

1. Workflow suspends and displays the VAP error message
2. User takes manual action outside the workflow:
   - **Option A**: Revert their config change to match what's already deployed
   - **Option B**: Manually `kubectl annotate` the resource with the specific approval flag
3. User clicks "Resume" in Argo UI
4. Workflow re-attempts the **same apply** operation - **OR** the user never does and eventually reruns the workflow with different settings.
5. Either succeeds (user resolved conflict) or fails again (loops back to suspend)

**Important**: Argo's suspend/resume continues from AFTER the suspend step, so we need a **recursive template call** to implement proper retry loops.

---

## Field Classification

### Kafka Cluster (`kafka.strimzi.io/Kafka`)

| Field | Category | Rationale | Restart Required? |
|-------|----------|-----------|-------------------|
| `metadata.name` | Immutable | Cluster identity | N/A |
| `spec.kafka.version` | **Major** | Version changes need planning | Yes (rolling) |
| `annotations["strimzi.io/kraft"]` | Immutable | Can't switch KRaft/ZK modes | N/A |
| `spec.kafka.config.*` | Minor | Safe operational tuning | No (dynamic) |
| `spec.kafka.listeners` | Minor | Can modify with care | Yes (rolling) |

**Note on `spec.kafka.config.*`**: These are Kafka broker configs that can be changed dynamically:
- `auto.create.topics.enable` - safe
- `offsets.topic.replication.factor` - safe (only affects new topics)
- `transaction.state.log.*` - safe
- `default.replication.factor` - safe (only affects new topics)
- `min.insync.replicas` - safe

**Note on `spec.kafka.listeners`**: These define how clients connect. The workflow doesn't expose these as inputs - they're hardcoded in `makeDeployKafkaClusterKraftManifest()`. If we later expose them, they'd be outputs the user reads, not inputs they configure.

### KafkaNodePool (`kafka.strimzi.io/KafkaNodePool`)

| Field | Category | Rationale | Restart Required? |
|-------|----------|-----------|-------------------|
| `spec.replicas` | **Major** | Scaling impacts availability/data | No (Strimzi handles rolling) |
| `spec.storage.type` | Immutable | ephemeral/persistent can't switch | N/A |
| `spec.storage.size` | **Major** | Storage expansion depends on CSI | Depends on provider |
| `spec.roles` | Immutable | Role changes are dangerous | N/A |

**Note on replicas**: Strimzi handles replica scaling gracefully with rolling restarts. The "Major" classification is for user awareness, not because it's dangerous.

### KafkaTopic (`kafka.strimzi.io/KafkaTopic`)

| Field | Category | Rationale | Restart Required? |
|-------|----------|-----------|-------------------|
| `metadata.labels["strimzi.io/cluster"]` | Immutable | Topic can't move clusters | N/A |
| `spec.partitions` (decrease) | Blocked | Kafka doesn't allow this | N/A |
| `spec.partitions` (increase) | Minor | Safe capacity addition | No |
| `spec.replicas` | **Major** | Replication factor changes | No |
| `spec.config.*` | Minor | retention, segment.bytes, etc. | No (dynamic) |

**Note on `spec.config.*`**: Topic configs are dynamically applied without restart:
- `retention.ms` - safe, immediate
- `segment.bytes` - safe, affects new segments
- `cleanup.policy` - safe, immediate

**Legend:**
- **Immutable**: Cannot change after creation (hard block)
- **Major**: Requires explicit approval annotation to proceed
- **Minor**: Allowed without approval

---

## Value-Based Approval Annotations

Each VAP check uses a **value-based approval annotation** where the annotation value must match the target value being requested. This prevents race conditions and auto-resets after the change succeeds.

| Check | Approval Annotation | Example |
|-------|---------------------|---------|
| KafkaNodePool replicas | `approved-replicas=<target>` | `approved-replicas=3` |
| KafkaNodePool storage.size | `approved-storage-size=<target>` | `approved-storage-size=10Gi` |
| Kafka version | `approved-version=<target>` | `approved-version=3.7.0` |
| KafkaTopic replicas | `approved-topic-replicas=<target>` | `approved-topic-replicas=2` |

**Benefits:**
- **No race conditions**: Approving `replicas=3` won't accidentally allow `replicas=20`
- **Auto-reset**: Once the change succeeds, the annotation value no longer matches future changes
- **Clear audit trail**: Annotation shows exactly what was approved

### skipApprovals Pattern Integration

Following the existing `skipApprovals` pattern from `metadataMigration.ts`, we can:
1. Add approval flags to the manifest **before** apply (pre-approved)
2. Use a global `skipAllApprovals` flag with care for dev/test environments

```typescript
// In workflow, when applying resource with pre-approval:
const manifest = {
    ...baseManifest,
    metadata: {
        ...baseManifest.metadata,
        annotations: {
            ...baseManifest.metadata.annotations,
            // Add approval annotation if skipApprovals is set
            ...(skipApprovals ? {
                'migrations.opensearch.org/approved-replicas': String(baseManifest.spec.replicas),
                'migrations.opensearch.org/approved-storage-size': baseManifest.spec.storage?.size
            } : {})
        }
    }
}
```

---

## Advanced Patterns & Operational Considerations

This section covers patterns for CRD lifecycle management, resource locking after completion, and efficient status detection — building on the core VAP + retry architecture above.

### CRD Upgrade "In-Flight" Handling

Updating a CRD while resources are being processed is a standard day-to-day operation in Kubernetes, but there are two ways it can go:

* **Non-Breaking Changes (Additive):** Adding a new optional field is safe — K8s is resilient here.
    * **The In-Flight Resource:** Existing objects in etcd won't have the new field. Controllers/workflows should treat the absence of the field as a default value.
    * **The Policy Impact:** If a CEL policy references a field that doesn't exist yet on the `oldObject`, the expression may error out.
    * **Solution:** Use the CEL `has()` or `?` operator. E.g., `!has(object.spec.newFeature) || object.spec.newFeature == oldObject.spec.newFeature`. This ensures the policy doesn't crash when comparing an old resource to a new manifest. (Note: the Phase 1 VAPs already follow this pattern for annotations and optional fields.)

* **Breaking Changes (Renames/Deletions):**
    * **Versioned APIs:** K8s handles this by letting you serve multiple versions (e.g., `v1alpha1` and `v1beta1`).
    * **Conversion Webhooks:** If you move a field, you'll eventually need a Conversion Webhook to translate between versions. Without one, if you change the storage version, K8s might lose data during the transition.

### "Lock-on-Complete" CEL Pattern (Subgraph Skip & Consistency Guard)

This pattern is **not** for infrastructure resources like Kafka clusters or node pools. It applies to resources that represent **completed work products** — snapshots, snapshot migrations, and similar artifacts where the resource tracks the outcome of an entire workflow subgraph.

**The problem it solves:** When a user re-runs a workflow, completed subgraphs should be skippable. But skipping is only safe if the parameters that produced the completed artifact match what the user is requesting now. If the user changed their config (e.g., different index filter, different target), the old "done" artifact is stale and the subgraph needs to re-run.

**How it works:** When the workflow completes a subgraph (e.g., snapshot creation), it marks the resource as Complete. On re-run, the workflow attempts to apply the manifest with the user's current parameters. If the spec matches what's already there, the apply is a no-op and the workflow skips the subgraph. If the spec differs, the CEL policy rejects the update instantly (403), signaling that the completed artifact is inconsistent with the new request.

> "If the status is 'Complete', then any update to the 'spec' must be exactly equal to the current 'spec'."

**Target resources** (future implementation, not Kafka):
- `DataSnapshot` — a completed snapshot can't be retroactively reconfigured
- `SnapshotMigration` — a completed migration's parameters are fixed
- `CapturedTraffic` — a completed capture session's config is immutable

```yaml
validations:
  - expression: |
      # If the existing resource is marked as 'Complete'
      !(oldObject.status.phase == 'Complete') || 
      # Then the new object's spec must match the old one exactly
      (object.spec == oldObject.spec)
    message: "Resource is in 'Complete' state and current parameters don't match. To re-run with different parameters, delete the existing resource first."
```

> **Note:** For CRDs where the status subresource is enabled, CEL may not see `status` during a standard UPDATE. In that case, reflect the phase into an annotation (e.g., `migrations.opensearch.org/phase=Complete`) and check that instead. See the Gotchas table below.

**Why this is better than a wait-based consistency check:**
- **Immediate Failure:** The `kubectl apply` fails instantly with a 403 Forbidden — no polling needed. The workflow can immediately branch to "stale artifact" handling.
- **Atomic Truth:** No race conditions where the workflow thinks it's okay to skip, but someone changed the spec a millisecond later. The API server is the single source of truth.
- **Subgraph Skip:** On match, the apply is a no-op. The workflow sees the resource is already Complete and skips the entire subgraph — no need to re-deploy, re-run, or re-wait.

This pattern is complementary to the value-based approval annotations above. Approvals gate *intentional changes* to infrastructure. Lock-on-Complete guards *consistency of completed work products* to enable safe subgraph skipping.

### Efficient "Done" vs. "Consistent" Detection in Argo

To avoid blind sleeps when waiting for resource readiness, use the Argo `resource` template with `successCondition` / `failureCondition`:

1. **Check/Apply Step:**
    * Apply the manifest.
    * If it fails due to the CEL consistency block (Lock-on-Complete), branch to "Inconsistent/Fail" logic.
    * If it succeeds, the update was either a no-op or a valid change.

2. **Wait Step:** Use a `resource` template:
    ```yaml
    successCondition: status.phase == Complete
    failureCondition: status.phase == Failed
    ```

Argo watches the resource and moves forward the instant the controller updates the status — no polling interval needed. This pairs well with the recursive retry loop in Phase 3.

### Major Gotchas

| Gotcha | Detail | Mitigation |
|--------|--------|------------|
| **Status Subresource** | If your CRD doesn't define `.spec.status: {}`, updates to status will increment `metadata.generation`, triggering unnecessary reconciliation loops. | Ensure all CRDs used with this pattern have the status subresource enabled. |
| **CEL and Status Visibility** | By default, many ValidatingAdmissionPolicies don't see the `status` field during a standard `UPDATE` if the status subresource is enabled. | Either target the correct subresource in the policy, or have the controller reflect key state into **annotations** (which CEL can always see). This is especially relevant if adopting the Lock-on-Complete pattern above. |
| **Two-Phase Race** | If the workflow updates status to "Complete" at the very end, cleanup steps in the same workflow could accidentally trigger the Lock-on-Complete policy. | Sequence cleanup steps *before* the status transition to "Complete", or exclude the workflow's service account from the lock policy. |

---

## Implementation Plan

### Files to Create/Modify

| File | Action | Description |
|------|--------|-------------|
| `deployment/.../templates/resources/validatingAdmissionPolicies.yaml` | **CREATE** | VAPs for Kafka, KafkaNodePool, KafkaTopic + bindings |
| `deployment/.../templates/resources/workflowRbac.yaml` | **MODIFY** | Add ClusterRole for VAP management |
| `orchestrationSpecs/.../setupKafka.ts` | **MODIFY** | Add reconciliation wrapper with retry loop |

**Note**: No separate `suspendForBlockedChange` template needed - inline the suspend within the retry loop template.

---

## Phase 1: Create ValidatingAdmissionPolicies

**New file:** `deployment/k8s/charts/aggregates/migrationAssistantWithArgo/templates/resources/validatingAdmissionPolicies.yaml`

```yaml
{{- if get .Values.conditionalPackageInstalls "argo-workflows" }}
# ValidatingAdmissionPolicies for Kafka resources
# Prevents accidental major changes without explicit approval

# ─────────────────────────────────────────────────────────────
# KafkaNodePool Policy - replicas, storage type, roles
# ─────────────────────────────────────────────────────────────
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata:
  name: {{ .Release.Namespace }}-kafkanodepool-policy
spec:
  failurePolicy: Fail
  matchConstraints:
    resourceRules:
    - apiGroups: ["kafka.strimzi.io"]
      apiVersions: ["v1", "v1beta2"]
      operations: ["UPDATE"]
      resources: ["kafkanodepools"]
  validations:
    # Major change: replica scaling requires specific approval
    - expression: |
        object.spec.replicas == oldObject.spec.replicas ||
        (has(object.metadata.annotations) &&
         has(object.metadata.annotations['migrations.opensearch.org/approved-replicas']) &&
         object.metadata.annotations['migrations.opensearch.org/approved-replicas'] == string(object.spec.replicas))
      messageExpression: |
        "Major Change Blocked [replicas]: NodePool scaling from " +
        string(oldObject.spec.replicas) + " to " + string(object.spec.replicas) +
        ". To proceed: kubectl annotate kafkanodepool/" + object.metadata.name +
        " migrations.opensearch.org/approved-replicas=" + string(object.spec.replicas) + " --overwrite"
    # Major change: storage size requires specific approval
    - expression: |
        !has(object.spec.storage.size) || !has(oldObject.spec.storage.size) ||
        object.spec.storage.size == oldObject.spec.storage.size ||
        (has(object.metadata.annotations) &&
         has(object.metadata.annotations['migrations.opensearch.org/approved-storage-size']) &&
         object.metadata.annotations['migrations.opensearch.org/approved-storage-size'] == object.spec.storage.size)
      messageExpression: |
        "Major Change Blocked [storage.size]: Storage resize from " +
        oldObject.spec.storage.size + " to " + object.spec.storage.size +
        ". To proceed: kubectl annotate kafkanodepool/" + object.metadata.name +
        " migrations.opensearch.org/approved-storage-size=" + object.spec.storage.size + " --overwrite"
    # Immutable: storage type
    - expression: |
        object.spec.storage.type == oldObject.spec.storage.type
      message: "Immutable: Storage type cannot be changed after creation"
    # Immutable: roles
    - expression: |
        object.spec.roles == oldObject.spec.roles
      message: "Immutable: Node pool roles cannot be changed after creation"

---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicyBinding
metadata:
  name: {{ .Release.Namespace }}-kafkanodepool-binding
spec:
  policyName: {{ .Release.Namespace }}-kafkanodepool-policy
  validationActions: [Deny]
  matchResources:
    namespaceSelector:
      matchExpressions:
      - key: kubernetes.io/metadata.name
        operator: In
        values: ["{{ .Release.Namespace }}"]

# ─────────────────────────────────────────────────────────────
# Kafka Cluster Policy - version changes
# ─────────────────────────────────────────────────────────────
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata:
  name: {{ .Release.Namespace }}-kafka-policy
spec:
  failurePolicy: Fail
  matchConstraints:
    resourceRules:
    - apiGroups: ["kafka.strimzi.io"]
      apiVersions: ["v1", "v1beta2"]
      operations: ["UPDATE"]
      resources: ["kafkas"]
  validations:
    - expression: |
        object.spec.kafka.version == oldObject.spec.kafka.version ||
        (has(object.metadata.annotations) &&
         has(object.metadata.annotations['migrations.opensearch.org/approved-version']) &&
         object.metadata.annotations['migrations.opensearch.org/approved-version'] == object.spec.kafka.version)
      messageExpression: |
        "Major Change Blocked [version]: Kafka version change from '" +
        oldObject.spec.kafka.version + "' to '" + object.spec.kafka.version +
        "'. To proceed: kubectl annotate kafka/" + object.metadata.name +
        " migrations.opensearch.org/approved-version=" + object.spec.kafka.version + " --overwrite"

---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicyBinding
metadata:
  name: {{ .Release.Namespace }}-kafka-binding
spec:
  policyName: {{ .Release.Namespace }}-kafka-policy
  validationActions: [Deny]
  matchResources:
    namespaceSelector:
      matchExpressions:
      - key: kubernetes.io/metadata.name
        operator: In
        values: ["{{ .Release.Namespace }}"]

# ─────────────────────────────────────────────────────────────
# KafkaTopic Policy - partition decrease, replica change
# ─────────────────────────────────────────────────────────────
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicy
metadata:
  name: {{ .Release.Namespace }}-kafkatopic-policy
spec:
  failurePolicy: Fail
  matchConstraints:
    resourceRules:
    - apiGroups: ["kafka.strimzi.io"]
      apiVersions: ["v1", "v1beta2"]
      operations: ["UPDATE"]
      resources: ["kafkatopics"]
  validations:
    # Blocked: partition decrease (Kafka limitation - cannot be overridden)
    - expression: |
        object.spec.partitions >= oldObject.spec.partitions
      messageExpression: |
        "Blocked: Partition count cannot decrease (Kafka limitation). " +
        "Current: " + string(oldObject.spec.partitions) + ", Requested: " + string(object.spec.partitions)
    # Major change: replica factor
    - expression: |
        object.spec.replicas == oldObject.spec.replicas ||
        (has(object.metadata.annotations) &&
         has(object.metadata.annotations['migrations.opensearch.org/approved-topic-replicas']) &&
         object.metadata.annotations['migrations.opensearch.org/approved-topic-replicas'] == string(object.spec.replicas))
      messageExpression: |
        "Major Change Blocked [replicas]: Topic replication factor change from " +
        string(oldObject.spec.replicas) + " to " + string(object.spec.replicas) +
        ". To proceed: kubectl annotate kafkatopic/" + object.metadata.name +
        " migrations.opensearch.org/approved-topic-replicas=" + string(object.spec.replicas) + " --overwrite"
    # Immutable: cluster assignment
    - expression: |
        !has(oldObject.metadata.labels) ||
        !has(oldObject.metadata.labels['strimzi.io/cluster']) ||
        object.metadata.labels['strimzi.io/cluster'] == oldObject.metadata.labels['strimzi.io/cluster']
      message: "Immutable: Topic cannot be moved to a different cluster"

---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingAdmissionPolicyBinding
metadata:
  name: {{ .Release.Namespace }}-kafkatopic-binding
spec:
  policyName: {{ .Release.Namespace }}-kafkatopic-policy
  validationActions: [Deny]
  matchResources:
    namespaceSelector:
      matchExpressions:
      - key: kubernetes.io/metadata.name
        operator: In
        values: ["{{ .Release.Namespace }}"]

{{- end }}
```

---

## Phase 2: Add RBAC for ValidatingAdmissionPolicies

**Modify:** `deployment/k8s/charts/aggregates/migrationAssistantWithArgo/templates/resources/workflowRbac.yaml`

VAPs are cluster-scoped resources. Add a new ClusterRole for VAP management:

```yaml
# Add after existing ClusterRoleBinding (around line 94)
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: {{ .Release.Namespace }}-vap-manager
rules:
  - apiGroups: ["admissionregistration.k8s.io"]
    resources: ["validatingadmissionpolicies", "validatingadmissionpolicybindings"]
    verbs: ["create", "get", "list", "watch", "update", "patch", "delete"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: {{ .Release.Namespace }}-vap-manager-binding
subjects:
  - kind: ServiceAccount
    name: argo-workflow-executor
    namespace: {{ .Release.Namespace }}
roleRef:
  kind: ClusterRole
  name: {{ .Release.Namespace }}-vap-manager
  apiGroup: rbac.authorization.k8s.io
```

---

## Phase 3: Add Reconciliation Templates to setupKafka.ts

**Modify:** `orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/setupKafka.ts`

Since Argo's suspend/resume continues from AFTER the suspend step, we need a **recursive template** to implement proper retry loops.

### Required: Add `continueOn` to TaskOpts

First, we need to add `continueOn` support to the workflow builder's `TaskOpts` type in `taskBuilder.ts`:

```typescript
// In taskBuilder.ts, update TaskOpts:
export type TaskOpts<
    S extends TasksOutputsScope,
    Label extends TaskType,
    LoopT extends PlainObject
> = {
    loopWith?: LoopWithUnion<LoopT> | ((tasks: AllTasksAsOutputReferenceableInner<S, Label>) => LoopWithUnion<LoopT>),
    when?: WhenCondition | ((tasks: AllTasksAsOutputReferenceableInner<S, Label>) => WhenCondition),
    continueOn?: { failed?: boolean, error?: boolean }  // NEW
};
```

### Retry Loop Pattern Using Recursive Template Call

```typescript
// In setupKafka.ts - add after existing templates

    // ── Reconciliation template with retry loop for KafkaNodePool ─────────
    // Uses recursive call: on VAP failure, suspends then calls itself again

    .addTemplate("deployKafkaNodePoolWithRetry", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())
        .addRequiredInput("suspendName", typeToken<string>())  // e.g., "myKafka.KafkaNodePool"

        .addSteps(b => b
            // Step 1: Try apply with continueOn.failed
            .addStep("tryApply", INTERNAL, "deployKafkaNodePool", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    clusterConfig: b.inputs.clusterConfig,
                }),
                { continueOn: { failed: true } }
            )

            // Step 2: If VAP blocked (error contains "Major Change Blocked"), suspend
            // Naming: waitForFixOrApproval
            .addStep("waitForFixOrApproval", INLINE, t => t
                .addRequiredInput("name", typeToken<string>())
                .addSuspend(),
                c => c.register({
                    name: b.inputs.suspendName
                }),
                { when: { templateExp: expr.and(
                    // Check if step failed
                    expr.not(expr.equals(
                        expr.taskData("steps", "tryApply", "status"),
                        expr.literal("Succeeded")
                    )),
                    // Check if error message contains "Major Change Blocked"
                    expr.regexMatch(
                        expr.literal("Major Change Blocked"),
                        expr.taskData("steps", "tryApply", "message")
                    )
                )}}
            )

            // Step 3: After suspend resumes, recursively call self to retry
            // This creates the retry loop - keeps trying until success
            .addStep("retryLoop", INTERNAL, "deployKafkaNodePoolWithRetry", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    clusterConfig: b.inputs.clusterConfig,
                    suspendName: b.inputs.suspendName,
                }),
                { when: { templateExp: expr.equals(
                    expr.taskData("steps", "waitForFixOrApproval", "status"),
                    expr.literal("Succeeded")
                )}}
            )
        )
    )
```

**Alternative: Using retryStrategy**

If the above pattern is too complex, we could use Argo's built-in `retryStrategy` with a custom error pattern. However, this requires the VAP error to be surfaced in a way that matches the retry condition.

### Output Handling

For templates that produce outputs (like `deployKafkaClusterKraft` which outputs `brokers`), use `expr.ternary` instead of the non-existent `expr.coalesce`:

```typescript
.addExpressionOutput("brokers", c =>
    expr.ternary(
        expr.equals(
            expr.taskData("steps", "tryApply", "status"),
            expr.literal("Succeeded")
        ),
        c.steps.tryApply.outputs.brokers,
        c.steps.retryLoop.outputs.brokers
    )
)
```

---

## Expression Helpers Available

Based on `expression.ts`, the available helpers are:

| Helper | Purpose | Example |
|--------|---------|---------|
| `expr.ternary(cond, ifTrue, ifFalse)` | Conditional | `expr.ternary(expr.isEmpty(x), "default", x)` |
| `expr.equals(a, b)` | Equality check | `expr.equals(status, "Succeeded")` |
| `expr.and(a, b)` | Logical AND | `expr.and(isFailed, isVapError)` |
| `expr.or(a, b)` | Logical OR | - |
| `expr.not(x)` | Logical NOT | `expr.not(expr.isEmpty(x))` |
| `expr.regexMatch(pattern, text)` | Regex match | `expr.regexMatch("Major Change", errorMsg)` |
| `expr.isEmpty(x)` | Check empty | `expr.isEmpty(expr.asString(x))` |
| `expr.concat(...parts)` | String concat | `expr.concat(prefix, ".suffix")` |
| `expr.taskData(type, name, key)` | Access task data | `expr.taskData("steps", "tryApply", "status")` |

**Not available** (would need to be added if needed):
- `expr.coalesce()` - use `expr.ternary(expr.isEmpty(...), ..., ...)` instead
- `expr.contains()` - use `expr.regexMatch()` instead
- `expr.failed()` / `expr.succeeded()` - use `expr.equals(taskData(..., "status"), "Succeeded")` instead

---

## Verification Plan

### 1. Test CEL Expressions (Manual)

```bash
# Create a test KafkaNodePool
kubectl apply -f - <<EOF
apiVersion: kafka.strimzi.io/v1
kind: KafkaNodePool
metadata:
  name: test-pool
  labels:
    strimzi.io/cluster: test-cluster
spec:
  replicas: 1
  roles: [broker, controller]
  storage:
    type: ephemeral
EOF

# Try to change replicas (should be blocked)
kubectl patch kafkanodepool test-pool --type=merge -p '{"spec":{"replicas":3}}'
# Expected: Error "Major Change Blocked [replicas]..."

# Add specific approval annotation with the target value
kubectl annotate kafkanodepool test-pool migrations.opensearch.org/approved-replicas=3

# Retry (should succeed)
kubectl patch kafkanodepool test-pool --type=merge -p '{"spec":{"replicas":3}}'
```

### 2. Test Retry Loop Behavior

1. Deploy workflow with a VAP that will block
2. Verify workflow suspends at `waitForFixOrApproval` step
3. Click "Resume" in Argo UI
4. Verify workflow recursively calls `retryLoop` step
5. Without fixing the issue, verify it suspends again
6. Add approval annotation
7. Click "Resume" again
8. Verify workflow completes successfully

### 3. Edge Cases to Test

| Scenario | Expected Behavior |
|----------|-------------------|
| Fresh install (no existing resource) | Creates normally, VAP only checks UPDATEs |
| Re-run with same config | Applies successfully (no change detected) |
| Minor change only (e.g., config tuning) | Applies successfully |
| Major change without approval | Suspends, waits for user action |
| Multiple major changes | Each needs its own approval annotation |
| Immutable field change | Hard fail (no retry possible) |
| User adds approval, resumes | Retry loop succeeds |

---

## Summary

This design uses Kubernetes-native ValidatingAdmissionPolicies to enforce change safety at the API level, combined with Argo workflow suspend/resume with recursive retry loops for user approval.

Key implementation details:
1. **Distinct approval annotations** per check type (not a single blanket approval)
2. **Recursive template calls** for retry loops (since Argo continues after suspend)
3. **Use `expr.regexMatch()`** instead of non-existent `expr.contains()`
4. **Use `expr.ternary()`** instead of non-existent `expr.coalesce()`
5. **Need to add `continueOn`** support to TaskOpts in workflow builders

Key files to implement:
1. `validatingAdmissionPolicies.yaml` - NEW (VAPs + bindings with distinct approval annotations)
2. `workflowRbac.yaml` - ADD ClusterRole for VAPs
3. `taskBuilder.ts` - ADD `continueOn` option to TaskOpts
4. `setupKafka.ts` - ADD reconciliation templates with recursive retry loops
