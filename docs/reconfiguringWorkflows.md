# State-Aware Resource Management for Migration Workflows

> **Status:** Implementation Plan (Ready for Review)

## Context

The migration workflow currently operates in an "always create" mode using `kubectl apply`. This works well for initial deployments but creates risks and inefficiencies during subsequent runs:

1. **Re-running workflows:** Should skip creation if a resource exists with the exact same configuration (Idempotency).
2. **Configuration drift:** Some changes (e.g., replica counts) require careful handling; others (e.g., storage type) must be blocked entirely.
3. **Protecting production:** Dangerous or disruptive changes should never silently apply; they must be gated by human approval.

**Implementation Targets:**

* Phase 1 (Completed): Kafka cluster (`Kafka`), `KafkaNodePool`, and `KafkaTopic` from Strimzi.
* Phase 2 (Current): `CapturedTraffic` (long-lived) and `DataSnapshot` / `SnapshotMigration` (finite/terminal).

### CRD Design

Each migration component is represented by a single CRD that holds both the desired configuration (`.spec`) and the lifecycle state (`.status.phase`). This follows the standard Kubernetes pattern — there are no separate "config" and "resource" CRDs. The three resource types are:

* **`CapturedTraffic`** — capture proxy configuration and lifecycle (long-lived, transitions to `Ready`)
* **`DataSnapshot`** — snapshot operation parameters and lifecycle (terminal, transitions to `Completed`)
* **`SnapshotMigration`** — metadata migration + document backfill (RFS) config and lifecycle (terminal, transitions to `Completed`)

### Goals

* Use Kubernetes ValidatingAdmissionPolicies (CEL) to enforce change rules at the API level.
* Route policy rejections to a suspend gate in the Argo UI, allowing users to safely approve changes without dropping into a CLI.
* Retain native K8s behavior for "stacked rollouts" on long-lived infrastructure.
* Ensure strict provenance: the parameters in a CRD's `.spec` must *always* perfectly match the actual deployed infrastructure or historical artifact.

---

## Architecture Overview: The Argo Retry Model

hen a gated change is attempted on a specific resource, the workflow relies on a "Catch, Suspend, Auto-Patch, and Retry" loop orchestrated entirely within Argo Workflows.

```mermaid
flowchart TB
    subgraph K8s["Kubernetes API Server"]
        API[API Server]
        VAP[ValidatingAdmissionPolicy]
        API -->|intercept| VAP
    end

    subgraph Workflow["Argo Workflow"]
        Start(( )) --> A
        A[Submit kubectl apply] -.->|request| API
        VAP -->|allowed| R[Apply Succeeds]
        VAP -->|"403 Forbidden"| C{Parse Error}
        R --> B[Wait for Ready/Complete]
        C -->|"Gated Change"| D[Suspend & Wait in Argo-Workflows]
        C -->|"Impossible / Lock Violation"| E[Fail workflow - unrecoverable]
        C -->|"Other Error"| F[Fail - real error]
        
        D -->|"User clicks 'Resume/Approve'"| G[Auto-Patch Resource with Workflow UID]
        G --> A
    end
```

**Key Concept: The API Rejection is Absolute.** If a VAP rejects a change, the `kubectl apply` request for that resource is aborted. The existing resource, including its state and status, remains 100% unchanged.

---

## Field Classification

Changes to resources fall into three categories.

1. **Impossible:** Cannot be done — the user must explicitly delete & recreate the resource. This branch of the workflow cannot be advanced without .
2. **Gated:** Requires explicit approval annotation (injected via the Workflow) to proceed.
3. **Safe:** Low-risk, allowed dynamically without approval. Safe fields require no VAP expressions — they are included in the classification tables for coverage tracking only.

For terminal resources in `Completed` state, the [Lock-on-Complete](#the-lock-on-complete-pattern-terminal-resources-only) pattern overrides all categories — every spec change becomes Impossible.

### Checksum Materiality — Schema-Driven Per-Dependency Checksums

Each field classification table includes columns showing whether a field is **material to the checksum** for each downstream dependency. A field is material if changing it would alter the behavior or correctness of the downstream consumer — meaning the downstream should block and re-evaluate when this field changes.

Fields that are purely operational (replica counts, resource limits, logging, observability endpoints) are not material — downstream consumers don't care if the proxy scaled from 2 to 4 replicas.

The dependency graph for checksum propagation:

```
Kafka ──→ Proxy (CapturedTraffic) ──→ DataSnapshot ──→ SnapshotMigration ──→ TrafficReplay
  │                    │                                        │
  │                    └────────────────────────────────────────→│
  └─────────────────────────────────────────────────────────────→│
```

Each arrow represents a "waits for" relationship. Downstream waiters check a **per-dependency checksum** on the upstream resource — not a single monolithic hash.

#### Schema annotations as the source of truth

Checksum materiality and change restriction categories are encoded directly on the Zod schema using `.meta()`:

```typescript
listenPort: z.number()
    .describe("TCP port the capture proxy listens on...")
    .meta({
        checksumFor: ['snapshot', 'replayer'],
        changeRestriction: 'impossible'
    })

podReplicas: z.number().default(1).optional()
    .describe("Number of proxy pod replicas...")
    .meta({
        // no checksumFor — purely operational
        changeRestriction: 'safe'
    })
```

The `checksumFor` array names the downstream dependencies whose checksum includes this field. The `changeRestriction` classifies the field for VAP generation (`'impossible'`, `'gated'`, or `'safe'`/omitted).

These annotations drive:
1. **Config transformer** — computes per-dependency checksums by selecting only fields tagged for each dependency
2. **Doc generation** — populates the checksum columns in the field classification tables
3. **VAP generation** — (future) generates CEL expressions from the restriction categories

#### Per-dependency checksums in CRD status

Each CRD carries its self-checksum plus named downstream checksums as flat fields in `.status`:

```yaml
status:
  phase: Ready
  configChecksum: "abc123"           # self — all spec fields, used for lifecycle skip
  checksumForSnapshot: "def456"      # subset of fields that affect snapshots
  checksumForReplayer: "def456"      # subset of fields that affect the replayer
```

Downstream waiters check the checksum relevant to them:

```yaml
# Snapshot waiter checking the proxy:
successCondition: status.phase == Ready, status.checksumForSnapshot == <expected>

# Replayer waiter checking the proxy:
successCondition: status.phase == Ready, status.checksumForReplayer == <expected>
```

This means a change to `podReplicas` (not in any `checksumFor`) changes the self-checksum (triggering a re-deploy of the proxy) but does **not** change the downstream checksums — so the snapshot and replayer waiters are unaffected and don't re-run.

### CaptureProxy (`migrations.opensearch.org/CapturedTraffic`)

Downstream dependencies that consume the proxy's checksum:
- **Snapshot** — waits for the proxy to be Ready before creating a snapshot of the source cluster
- **Replayer** — replays the captured traffic; sensitive to anything that changes what traffic is captured or how it's encoded

| Field | Category   | Rationale | Restart Required? | In Checksum For: Snapshot | In Checksum For: Replayer |
| --- |------------| --- | --- | --- | --- |
| `spec.listenPort` | Impossible | Changing breaks all client connections | N/A | ✅ | ✅ |
| `spec.noCapture` | **Gated**  | Fundamentally changes proxy behavior | Yes (rolling) | ✅ | ✅ |
| `spec.enableMSKAuth` | **Gated**  | Auth mode change is destructive | Yes (rolling) | ❌ | ❌ |
| `spec.kafkaClusterName` | **Gated** | Changes which Kafka cluster receives captured traffic | Yes (rolling) | ❌ | ❌ |
| `spec.kafkaTopicName` | **Gated** | Changes which topic receives captured traffic | Yes (rolling) | ❌ | ❌ |
| `spec.tls.mode` | **Gated**  | TLS mode switch requires cert/secret changes | Yes (rolling) | ❌ | ❌ |
| `spec.podReplicas` | Safe       | Scaling is safe, Deployment handles rolling | No | ❌ | ❌ |
| `spec.resources` | Safe       | Resource limits/requests | Yes (rolling) | ❌ | ❌ |
| `spec.internetFacing` | Impossible | Changes load balancer scheme; recreate Service | N/A | ❌ | ❌ |
| `spec.loggingConfigurationOverrideConfigMap` | Safe       | Logging config swap | Yes (rolling) | ❌ | ❌ |
| `spec.otelCollectorEndpoint` | Safe       | Observability config | Yes (rolling) | ❌ | ❌ |
| `spec.setHeader` | Gated      | Header injection tweaks | Yes (rolling) | ✅ | ✅ |
| `spec.destinationConnectionPoolSize` | Safe       | Connection tuning | Yes (rolling) | ❌ | ❌ |
| `spec.destinationConnectionPoolTimeout` | Safe       | Connection tuning | Yes (rolling) | ❌ | ❌ |
| `spec.kafkaClientId` | Safe       | Client identity change | Yes (rolling) | ❌ | ❌ |
| `spec.maxTrafficBufferSize` | Gated       | Performance tuning | Yes (rolling) | ❌ | ❌ |
| `spec.numThreads` | Safe       | Performance tuning | Yes (rolling) | ❌ | ❌ |
| `spec.sslConfigFile` | Safe       | Legacy SSL config path | Yes (rolling) | ❌ | ❌ |
| `spec.suppressCaptureForHeaderMatch` | **Gated**  | Traffic filtering changes | Yes (rolling) | ✅ | ✅ |
| `spec.suppressCaptureForMethod` | **Gated**  | Traffic filtering changes | Yes (rolling) | ✅ | ✅ |
| `spec.suppressCaptureForUriPath` | **Gated**  | Traffic filtering changes | Yes (rolling) | ✅ | ✅ |
| `spec.suppressMethodAndPath` | **Gated**  | Traffic filtering changes | Yes (rolling) | ✅ | ✅ |

*(Note: Kafka and KafkaNodePool resources follow similar matrices established in Phase 1).*

### TrafficReplay (`migrations.opensearch.org/TrafficReplay`)

The replayer has no downstream dependencies — nothing waits on it. The checksum column here tracks whether a field is material to the replayer's *own* checksum (i.e., would a change require re-evaluation of the replayer's correctness, or is it purely operational).

| Field | Category | Rationale | Restart Required? | In Own Checksum |
| --- | --- | --- | --- | --- |
| `spec.kafkaTrafficEnableMSKAuth` | Impossible | Kafka auth mode — changing breaks the consumer connection | N/A | ✅ |
| `spec.kafkaTrafficPropertyFile` | Impossible | Kafka connection properties — fundamental to consumer setup | N/A | ✅ |
| `spec.authHeaderValue` | **Gated** | Changes auth sent to target cluster | Yes (rolling) | ✅ |
| `spec.removeAuthHeader` | **Gated** | Toggles auth header stripping | Yes (rolling) | ✅ |
| `spec.transformerConfig` | **Gated** | Changes traffic transformation — could corrupt replay | Yes (rolling) | ✅ |
| `spec.transformerConfigEncoded` | **Gated** | Same as above, different encoding | Yes (rolling) | ✅ |
| `spec.transformerConfigFile` | **Gated** | Same as above, file reference | Yes (rolling) | ✅ |
| `spec.tupleTransformerConfig` | **Gated** | Tuple-level transformation | Yes (rolling) | ✅ |
| `spec.tupleTransformerConfigBase64` | **Gated** | Same | Yes (rolling) | ✅ |
| `spec.tupleTransformerConfigFile` | **Gated** | Same | Yes (rolling) | ✅ |
| `spec.podReplicas` | Safe | Scaling is safe, Deployment handles rolling | No | ❌ |
| `spec.resources` | Safe | Resource limits/requests | Yes (rolling) | ❌ |
| `spec.jvmArgs` | Safe | JVM tuning | Yes (rolling) | ❌ |
| `spec.loggingConfigurationOverrideConfigMap` | Safe | Logging config swap | Yes (rolling) | ❌ |
| `spec.otelCollectorEndpoint` | Safe | Observability config | Yes (rolling) | ❌ |
| `spec.speedupFactor` | Safe | Replay rate tuning | Yes (rolling) | ❌ |
| `spec.lookaheadTimeSeconds` | Safe | Buffer tuning | Yes (rolling) | ❌ |
| `spec.maxConcurrentRequests` | Safe | Performance tuning | Yes (rolling) | ❌ |
| `spec.numClientThreads` | Safe | Performance tuning | Yes (rolling) | ❌ |
| `spec.observedPacketConnectionTimeout` | Safe | Timeout tuning | Yes (rolling) | ❌ |
| `spec.quiescentPeriodMs` | Safe | Timing tuning | Yes (rolling) | ❌ |
| `spec.targetServerResponseTimeoutSeconds` | Safe | Timeout tuning | Yes (rolling) | ❌ |
| `spec.userAgent` | Safe | Cosmetic | Yes (rolling) | ❌ |

### DataSnapshot (`migrations.opensearch.org/DataSnapshot`)

Terminal resource that is created with what is effectively a job.
This transitions to `Completed`. 
Lock-on-Complete freezes the entire spec once done.
All fields here are impossible to edit.  If a user wanted to change a snapshot
in-progress, they would need to delete the existing snapshot and redrive.

Downstream dependencies that consume the snapshot's checksum:
- **SnapshotMigration** — waits for the snapshot to be Completed before running metadata migration and/or document backfill

Since all spec fields are frozen on completion, every field is inherently material to the checksum. The distinction here is whether a field change would invalidate the downstream SnapshotMigration's correctness (requiring it to re-run) vs. being purely operational to the snapshot job itself.

| Field | Category | In Checksum For: SnapshotMigration |
| --- | --- | --- |
| Snapshot config (compression, global state, allowlist, etc.) | Impossible (Lock-on-Complete) | ✅ |
| Repo config (S3 path, region, etc.) | Impossible (Lock-on-Complete) | ✅ |

### SnapshotMigration (`migrations.opensearch.org/SnapshotMigration`)

Terminal resource — transitions to `Completed`. Lock-on-Complete freezes the entire spec once done. A SnapshotMigration contains one or more sub-tasks, each optionally including metadata migration and/or document backfill (RFS).

Downstream dependencies that consume the migration's checksum:
- **Replayer** — waits for the migration to be Completed before starting replay (ensures the target cluster has the expected index mappings and backfilled data)

**Metadata migration fields:**

This transitions to 'Completed'.
Lock-on-Complete freezes the entire spec once done.
All fields here are impossible to edit.  If a user wanted to change a snapshot
in-progress, they would need to delete the existing snapshot and redrive.

**Document backfill (RFS) fields:**

| Field | Category   | Rationale | In Checksum For: Replayer |
| --- |------------| --- | --- |
| `spec.documentBackfillConfig.indexAllowlist` | Impossible | | ✅ |
| `spec.documentBackfillConfig.podReplicas` | Safe       | | ❌ |
| `spec.documentBackfillConfig.allowLooseVersionMatching` | Impossible | | ✅ |
| `spec.documentBackfillConfig.docTransformerConfigBase64` | Impossible | | ✅ |
| `spec.documentBackfillConfig.documentsPerBulkRequest` | Safe       | | ❌ |
| `spec.documentBackfillConfig.initialLeaseDuration` | Gated      | | ❌ |
| `spec.documentBackfillConfig.maxConnections` | Gated      | | ❌ |
| `spec.documentBackfillConfig.maxShardSizeBytes` | Gated      | | ❌ |
| `spec.documentBackfillConfig.otelCollectorEndpoint` | Safe       | | ❌ |
| `spec.documentBackfillConfig.useTargetClusterForWorkCoordination` |  Safe          | | ❌ |
| `spec.documentBackfillConfig.jvmArgs` |  Safe          | | ❌ |
| `spec.documentBackfillConfig.loggingConfigurationOverrideConfigMap` |   Safe         | | ❌ |
| `spec.documentBackfillConfig.resources` |    Safe        | | ❌ |

---

## Resource Lifecycle & State Machine

Migration CRD resources follow a common lifecycle tracked natively in the `.status.phase` subresource.

### Terminal vs. Long-Lived Resources

`Ready` and `Completed` are sibling states; a resource will transition to one or the other based on its operational lifespan.

| State | Meaning | Used By |
| --- | --- | --- |
| `Initialized` | Placeholder — created by the initialization process but not yet acted upon. | All |
| `Running` | Work is in progress (deployment rolling out, snapshot copying). | All |
| `Ready` | Infrastructure is healthy, operational, and serving traffic. | **Long-Lived** (Proxy, Kafka) |
| `Completed` | A finite task has finished successfully. The output is immutable. | **Terminal** (Snapshots) |
| `Error` | Execution failed. The resource is "poisoned". | All |

Empty placeholder resources are created during the migration initialization step (before the Argo workflow starts) so that downstream `waitFor` steps can find them. The initializer creates each CRD resource with an empty `spec: {}` and `status.phase: Initialized`. The workflow's first apply populates the spec with real parameters.

### The "Fail Forward / Poison Resource" Principle

If infrastructure deployment fails, **we do not attempt a rollback**. The workflow simply updates the CRD's `.status.phase` to `Error` and halts. The resource is considered "poisoned." It is the user's responsibility to push a new, valid configuration through the workflow to overwrite the poisoned state. This guarantees the CRD `.spec` is never artificially manipulated behind the scenes, ensuring strict provenance.

---

## The Workflow UID Approval Pattern

We tie approvals directly to the specific Argo Workflow execution requesting the change.

**The Flow:**

1. **Try Apply:** Argo attempts the update. The incoming manifest natively includes an Argo label: `workflows.argoproj.io/run-uid: {{workflow.uid}}`.
2. **The Block:** The VAP sees a Gated change, looks for a matching approval annotation, doesn't find it, and returns a `403 Forbidden`.
3. **The Suspend:** Argo catches the 403 and enters a `Suspend` node with a UI message: *"Gated changes detected. Review and click Resume to approve."*
4. **Auto-Patch:** When the user clicks Resume, the very next step in Argo runs a targeted patch:
   `kubectl patch <resource> <name> --type=merge -p '{"metadata":{"annotations":{"migrations.opensearch.org/approved-by-run": "{{workflow.uid}}"}}}'`
5. **The Retry:** The workflow loops back and attempts the exact same `kubectl apply`.
6. **The Pass:** The VAP sees the gated change, but evaluates `object.metadata.annotations['...approved-by-run'] == object.metadata.labels['workflows.argoproj.io/run-uid']`. The change is allowed.

**CEL Implementation Example** *(abbreviated — full policy covers all Gated fields from the classification table)*:

```yaml
validations:
  - expression: |
      # Condition 1: No Gated fields changed
      (object.spec.enableMSKAuth == oldObject.spec.enableMSKAuth &&
       object.spec.noCapture == oldObject.spec.noCapture) 
      ||
      # Condition 2: Workflow UID matches the approval annotation
      (has(object.metadata.annotations) &&
       has(object.metadata.annotations['migrations.opensearch.org/approved-by-run']) &&
       has(object.metadata.labels['workflows.argoproj.io/run-uid']) &&
       object.metadata.annotations['migrations.opensearch.org/approved-by-run'] == object.metadata.labels['workflows.argoproj.io/run-uid'])
    message: "Gated changes detected. Workflow UI approval is required to proceed."

```

---

## Advanced Patterns: Provenance & Idempotency

### The "Lock-on-Complete" Pattern (Terminal Resources Only)

This pattern strictly applies to **completed work products** (e.g., `DataSnapshot`). It freezes the resource's `.spec` to guarantee provenance and enables safe subgraph skipping in Argo.

* **Idempotent Run:** If a user re-runs a workflow against a completed snapshot with the exact same parameters, K8s treats it as a `200 OK` No-Op. Argo sees `status.phase == Completed` and safely skips the subgraph.
* **Changed Parameters:** If a user changes *any* parameter (even a "Safe" one) and re-runs, the VAP rejects the update with a 403. Silently accepting the change would break provenance — the `.spec` would no longer reflect the historical execution. The user must delete the stale artifact to run a new job with different parameters.

*(Note: Because the Kubernetes API passes the fully populated `oldObject` to the VAP during a spec update, we read `.status` directly. No dual-metadata tracking or annotations are required for state locking).*

**CEL Implementation:**

```yaml
validations:
  # Lock-on-Complete: Freeze spec for finished work products natively via status
  - expression: |
      !has(oldObject.status) ||
      !has(oldObject.status.phase) ||
      oldObject.status.phase != 'Completed' ||
      (object.spec == oldObject.spec)
    message: "Consistency Guard: This resource is 'Completed'. The specification is permanently sealed to maintain provenance. Delete the resource to run a new job with these parameters."

```

*(Note: There is explicitly **no** "Running Guard" in this architecture. Long-lived infrastructure supports native K8s stacked rollouts. If a Proxy is `Running`, the user is free to push a corrective workflow over it immediately, governed purely by the standard Gated/Impossible field checks).*

### CRD Upgrade "In-Flight" Handling

To prevent VAPs from breaking when a CRD is upgraded (e.g., a new optional field is added), always use the CEL `has()` operator for new fields.

```yaml
- expression: |
    !has(object.spec.newFeature) || 
    object.spec.newFeature == oldObject.spec.newFeature

```

---

## Efficient Argo Execution: Config Checksum Chaining

### Problem

Resources have cross-branch dependencies (e.g., the replayer waits for the proxy). Previously, waiters checked `status.phase == Ready`, but this has a race condition: on re-run, the phase is already `Ready` from the prior run, so downstream waiters proceed immediately — before the current workflow has verified whether the upstream config changed.

### Solution: Config Checksums

Each resource carries a `status.configChecksum` — a SHA-256 hash of its effective configuration. Downstream waiters check the checksum instead of the phase, ensuring they only proceed when the upstream resource's config matches what the current workflow expects.

**Checksum computation** happens in the config processor at generation time:

1. For each resource, hash all spec fields that will be applied to the CRD
2. For resources with upstream dependencies, fold the upstream checksums into the hash
3. Emit the checksum alongside the denormalized config

**Chaining example:**

```
KafkaCluster checksum = sha256(kafkaClusterSpec)
CapturedTraffic checksum = sha256(proxySpec + KafkaCluster.checksum)
TrafficReplay checksum = sha256(replayerSpec + CapturedTraffic.checksum)
```

If the Kafka cluster config changes, its checksum changes, which cascades through the proxy and replayer checksums — even if their own spec fields didn't change.

**Runtime flow:**

1. Config processor computes all checksums and includes them in the workflow config
2. The initializer creates placeholder CRDs with `status.phase: Initialized` and `status.configChecksum` already set
3. The initializer also emits a `patchCrdStatus.sh` helper because CRD status must be patched through the `/status` subresource separately from the initial create/apply
4. Lifecycle templates update `status.phase` and, on success, restamp `status.configChecksum`
5. Waiters compare both lifecycle state and checksum for CRDs, and compare Strimzi readiness plus a checksum annotation for Kafka

**Re-run scenarios:**

| Scenario | What happens |
|----------|-------------|
| Nothing changed | All checksums match from prior run. Waiters resolve instantly. Lifecycle templates skip via `checkPhase`. |
| Proxy config changed | Proxy checksum changes → replayer expected checksum changes. Replayer waiter blocks until proxy lifecycle completes and stamps new checksum. |
| Kafka config changed | Kafka checksum changes → proxy checksum changes → replayer checksum changes. Full cascade, all waiters block until upstream completes. |
| Only replayer config changed | Proxy/Kafka checksums unchanged, waiters resolve instantly. Only replayer lifecycle runs. |

### Lifecycle Template Pattern

```
checkPhase
  → if not already terminal/ready for this run:
      applyCRD → markRunning → deploy → markReady/markError
```

For CRDs we own, the checksum is carried in `status.configChecksum` and is written alongside the phase transition to `Ready` / `Completed`.

For Kafka, we do not own the `status` subresource, so the workflow patches `metadata.annotations["migration-configChecksum"]` after the Strimzi resource has been applied.

### Waiter Pattern

```yaml
CapturedTraffic:
successCondition: status.phase == Ready, status.configChecksum == <expected-checksum>

DataSnapshot / SnapshotMigration:
successCondition: status.phase == Completed, status.configChecksum == <expected-checksum>

Kafka:
successCondition: status.listeners, metadata.annotations.migration-configChecksum == <expected-checksum>
```

The checksum is not used alone in the CRD waiters; it is paired with the expected terminal lifecycle state so the workflow still distinguishes "not done yet" from "done with the wrong config."





---

## Adding a New Managed Resource

When adding a new migration component (e.g., a traffic replayer), follow this checklist. Each step has a concrete file and pattern to follow.

### 1. Define the CRD

Add to `migrationCrds.yaml`. Each resource is a single CRD with configuration in `.spec` and lifecycle in `.status.phase`. Choose the lifecycle type:

* **Long-lived** (proxy, replayer): phases `[Initialized, Running, Ready, Error]`
* **Terminal** (snapshot, migration job): phases `[Initialized, Running, Completed, Error]`

Include a typed schema for every spec field — don't use `x-kubernetes-preserve-unknown-fields` on the spec root. This enables CEL field-level comparisons in VAPs.

### 2. Add RBAC

Add the resource and its `/status` subresource to the `workflow-deployer-role` in `workflowRbac.yaml`:

```yaml
resources: ["trafficreplays", "trafficreplays/status"]
```

### 3. Classify fields

For every spec field, decide:

| Category | Meaning | VAP action |
|----------|---------|------------|
| **Impossible** | Cannot change without delete/recreate (e.g., listen port, index allowlist) | Hard block, no escape hatch |
| **Gated** | Risky but allowed with explicit approval (e.g., auth mode, TLS config) | Block unless UID annotation matches |
| **Safe** | Low-risk, always allowed (e.g., replica count, logging config) | No VAP expression needed |

For terminal resources, Lock-on-Complete overrides everything once `status.phase == Completed`.

### 4. Write the VAP

Add a `ValidatingAdmissionPolicy` + `ValidatingAdmissionPolicyBinding` to `validatingAdmissionPolicies.yaml`.

Pattern for Impossible fields:
```yaml
- expression: |
    !has(oldObject.spec.fieldName) || !has(object.spec.fieldName) ||
    object.spec.fieldName == oldObject.spec.fieldName
  message: "Impossible: fieldName cannot be changed. Delete and recreate."
```

Pattern for Gated fields (all share a single UID check):
```yaml
- expression: |
    (field1 unchanged && field2 unchanged && ...)
    ||
    (has(object.metadata.annotations) &&
     'migrations.opensearch.org/approved-by-run' in object.metadata.annotations &&
     has(object.metadata.labels) &&
     'workflows.argoproj.io/run-uid' in object.metadata.labels &&
     object.metadata.annotations['migrations.opensearch.org/approved-by-run'] ==
       object.metadata.labels['workflows.argoproj.io/run-uid'])
  message: "Gated changes detected. Approve via the Argo Workflow UI."
```

Use `has()` on every field to handle CRD upgrades where the field didn't previously exist.

For terminal resources, also add them to the `lock-on-complete-policy` resource list.

### 5. Add initializer placeholder

In `migrationInitializer.ts`, create the resource with `spec: {}` and `status: { phase: "Initialized", configChecksum: "<precomputed>" }` during the initialization step. This lets downstream `waitFor` steps find the resource before the workflow populates it.

If the resource is a CRD with a status subresource, make sure the initializer also emits the corresponding status patch command into `patchCrdStatus.sh`.

### 6. Build the manifest builder function

In the appropriate workflow template file (e.g., `setupReplay.ts`), create a function that maps config fields to the CRD spec field-by-field:

```typescript
function makeTrafficReplayManifest(config, name) {
    return {
        apiVersion: "migrations.opensearch.org/v1alpha1",
        kind: "TrafficReplay",
        metadata: {
            name: name,
            labels: {
                "workflows.argoproj.io/run-uid": makeStringTypeProxy(expr.getWorkflowValue("uid"))
            }
        },
        spec: {
            // Each field individually via expr.get()/expr.dig()
        }
    };
}
```

### 7. Thread checksum dependencies through the workflow config

If the new resource depends on other resources, include their checksum(s) in the denormalized config emitted by the config processor and fold them into the new resource's checksum.

Examples from the current implementation:

* Proxy checksum includes the Kafka checksum
* Snapshot checksum includes dependent proxy checksums
* Replay checksum includes the upstream proxy checksum

This is what makes re-runs block on the correct upstream resource version instead of merely on a historical `Ready` / `Completed` phase.

### 8. Add checksum-aware waiters

In `resourceManagement.ts`, define the waiter so it checks both:

* the resource has reached the correct lifecycle state
* the resource's checksum matches the expected checksum for this workflow run

For external resources whose status we do not own, store the checksum in metadata annotations instead of status.

The `run-uid` label is required for the Workflow UID Approval Pattern.

### 7. Add apply + retry templates

Three templates, layered:

1. **Leaf apply** — `action: "apply"` with the manifest. Includes `K8S_RESOURCE_RETRY_STRATEGY`.
2. **Retry wrapper** — catches VAP rejections with `continueOn: {failed: true}`, suspends for user review, auto-patches the UID approval annotation via `patchApprovalAnnotation`, then recursively retries via `addStepToSelf`.
3. **Lifecycle wrapper** — calls the retry template, then transitions phases:
   * `patchResourcePhase("Running")` after the CRD spec is accepted
   * Deploy the actual infrastructure
   * `patchResourcePhase("Ready")` on success, `patchResourcePhase("Error")` on failure

Use `continueOn: {failed: true}` on the deploy step so the workflow can still mark `Error` on failure (Fail Forward / Poison Resource principle).

### 8. Wire into the parent workflow

Call the lifecycle template from `fullMigration.ts` (or the appropriate orchestration template). The parent workflow should not manage phases — that's encapsulated in the lifecycle template.

### 9. Add a Helm test

Create a test pod in `templates/tests/` modeled on `test-vap-kafka.yaml`. The test should:

1. Create the resource
2. Apply a spec (should succeed on `Initialized`)
3. Try an Impossible field change (should fail)
4. Try a Gated field change without approval (should fail)
5. Add matching UID label + annotation, retry the Gated change (should succeed)
6. Try with mismatched UIDs (should fail)
7. For terminal resources: patch to `Completed`, try any spec change (should fail via Lock-on-Complete)

### File summary

| File | What to add |
|------|-------------|
| `migrationCrds.yaml` | CRD with typed schema + status phases |
| `workflowRbac.yaml` | Resource + `/status` in deployer role |
| `validatingAdmissionPolicies.yaml` | Policy + binding (Impossible, Gated, optionally Lock-on-Complete) |
| `migrationInitializer.ts` | Placeholder creation |
| `setup<Component>.ts` | Manifest builder + apply/retry/lifecycle templates |
| `fullMigration.ts` | Call the lifecycle template |
| `templates/tests/test-vap-<component>.yaml` | Helm test for VAP rules |
