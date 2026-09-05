# Workflow CRD Lifecycle & Kubernetes-Native Teardown

## Core Insight

Teardown ordering belongs in **Kubernetes**, not Argo. Argo exit handlers don't survive `argo stop`, and Argo has no concept of "wait for pods to drain." Kubernetes already solves this with owner references and foreground cascading deletion.

## Architecture

For how workflow configuration is projected into these migration CR specs, and
how the matching CRDs and ValidatingAdmissionPolicies are generated and staged,
see
[resolvingMigrationParametersFromConfigs.md](resolvingMigrationParametersFromConfigs.md).

### The ownership graph (set up at creation time in orchestration specs)

Ownership is **chained** so that dependents die before their dependencies. This
ensures zero spurious error logs (e.g., RFS never sees "coordinator unavailable"
because RFS pods are already gone before the coordinator starts draining).

```
Argo Workflow
├── owns → KafkaCluster CRD
│   ├── owns → Kafka CR (Strimzi)
│   ├── owns → KafkaNodePool
│   └── owns → KafkaTopics
├── owns → SnapshotMigration CRD
│   ├── owns → Coordinator StatefulSet + Service + Secret
│   │   └── owns → Coordinator Pods
│   └── owns (blockOwnerDeletion) → RFS Deployment
│       └── owns → RFS Pods
├── runs transiently → Metadata migration pods
├── owns → CapturedTraffic CRD
│   └── owns → Proxy Deployment + Service
│       └── owns → Proxy Pods
├── owns → TrafficReplay CRD
│   └── owns → Replayer Deployment
│       └── owns → Replayer Pods
├── owns → ApprovalGate CRD (no owned resources)
└── owns → DataSnapshot CRD (no owned resources, immutable)
```

**Chained deletion order for SnapshotMigration:**
1. CLI deletes SnapshotMigration CRD (foreground)
2. k8s deletes Coordinator StatefulSet (foreground, blocked by RFS)
3. k8s deletes RFS Deployment → RFS pods drain and terminate
4. RFS fully gone → Coordinator pods drain and terminate
5. Coordinator fully gone → CRD deleted

**Cross-CRD ordering (CLI-managed):**
1. Delete TrafficReplay CRDs → replayer pods die (no more traffic to proxy)
2. Delete SnapshotMigration CRDs → RFS dies, then coordinator dies
3. Delete KafkaCluster CRDs → Kafka dies
4. Delete CapturedTraffic CRDs → proxy pods die

Result: zero "connection refused", "coordinator unavailable", or "upstream gone" errors in any component's logs.

### Why metadata migration is not owned by `SnapshotMigration`

Metadata migration remains an Argo-managed container step on purpose.

Reasons:

- it is transient compute, not durable runtime infrastructure
- there is only one `migration-workflow` at a time
- replacing or deleting the Argo workflow also deletes the metadata migration pod
- that means there is no separate long-lived metadata process that can outlive workflow replacement and race with a later workflow

This is different from the RFS deployment and coordinator resources, which are intentionally long-lived enough during snapshot migration execution that they benefit from CR ownership and explicit cleanup.

So the boundary is:

- `SnapshotMigration` owns durable / long-running execution infrastructure:
  - RFS Deployment
  - coordinator StatefulSet + Service + Secret
- Argo workflow owns short-lived metadata migration pods

This is operationally simpler and still safe because workflow replacement already tears down metadata migration execution.

---

## Proxy Lifecycle

Proxies are **protected by default** during reset. They are not deleted unless
`--include-proxies` is explicitly passed. There is no automatic mode-switching
during reset — proxies simply remain running as-is.

Proxy mode switching (`disable-capture` / `enable-capture`) is planned but not
yet integrated into the CLI. When implemented, it will allow switching a proxy
between capture and pass-through modes without deleting it.

| State | CRD phase | Proxy behavior | Kafka alive? |
|-------|-----------|---------------|-------------|
| **Capturing** | `Ready` | Routes traffic to source AND writes to Kafka topic | Yes |
| **Deleted** | CRD gone | Proxy Deployment + Service + Pods all removed via ownership cascade | N/A |

### Transitions

1. **Created → Capturing**: Workflow deploys proxy with `kafkaConnection` parameters, patches CRD to `Ready`.

2. **Capturing → Deleted**: Deleting the CapturedTraffic CRD cascades to the Proxy Deployment and pods via ownerReferences.

---

## CLI: `workflow reset`

### Usage

```bash
workflow reset                              # list resources and status
workflow reset my-kafka                     # delete a named resource
workflow reset 'snap*'                      # glob matching
workflow reset --all                        # delete everything except proxies
workflow reset --all --include-proxies      # delete everything including proxies
workflow reset my-kafka --cascade           # also delete dependents
```

### Proxy protection

By default, proxies are **not deleted** during reset. Their upstream
dependencies (e.g., Kafka clusters) are also preserved when they are required by
a protected proxy. Use `--include-proxies` to override this protection and
delete proxies and their dependencies.

### Dependency-aware deletion

Resources declare dependencies via `spec.dependsOn` on their CRDs. The CLI
builds a DAG and deletes in dependency-safe order: a resource is only deleted
after all its dependents are gone. Independent branches proceed in parallel.

All migration resources populate `spec.dependsOn`, including the terminal
`DataSnapshot` and `SnapshotMigration` (a DataSnapshot points at its proxy setups; a
SnapshotMigration points at its DataSnapshot, or nothing for an externally-managed ES/OS
snapshot). The workflow's `tryApply` step is the sole writer of this field on the live CR;
the initializer intentionally omits it from the terminal-resource bootstrap spec so the
graph reflects only edges the workflow has actually established (see
[reconfiguringWorkflows.md](reconfiguringWorkflows.md)).

### Relationship to workflow submission

Reset does **not** stop or delete the Argo workflow. It only deletes migration
CRDs and their owned Kubernetes resources.

To replace a running workflow, use `workflow submit`, which stops and deletes the
existing Argo workflow before resubmitting. This separation keeps reset focused
on the CR graph and submit focused on the workflow lifecycle.

Workflow Manage preserves this boundary while composing the two operations for impossible
VAP recovery. After the user confirms the exact reset plan, the server executes reset and
then calls the normal submit service. It never approves the old workflow's retry gate:
initialization of the replacement workflow recreates the deleted roots and captures their
new UIDs.

---

## CLI: `workflow submit`

Submit prepares and admission-checks a replacement before deleting an existing
Argo workflow. It does **not** delete migration CRDs; those survive across
ordinary workflow resubmissions.

The submission path:

1. runs the initializer once to prepare the complete bundle and run number;
2. server-dry-runs final desired resources and writes a structured report;
3. blocks before mutation for proven impossible/sealed or schema-invalid changes;
4. stops and deletes the old Argo workflow only after preflight succeeds;
5. cleans up stale ApprovalGates and creates or reuses migration resources,
   including one immutable `MigrationRun` history record;
6. enriches workflow config with server-assigned CR UIDs; and
7. submits the Argo workflow with workflow-name/run-number labels and a
   MigrationRun-name annotation.

Steps 5-7 commit the prepared bundle; they do not rerun initialization.

After the Argo workflow starts, its first bookkeeping step, `initializeRunMetadata`,
patches the matching `MigrationRun` with `status.workflowUid`,
`status.workflowCreationTimestamp`, and a one-time workflow UID label. That keeps
the durable run history inside Kubernetes without relying on the submitting shell
process to survive after `kubectl create`.

---

## Why ownerReferences, not finalizers?

Finalizers require a controller to remove them. We'd need either the Argo workflow (killed by `argo stop`), a separate controller (operational complexity), or the CLI (defeats the purpose). Owner references with foreground deletion give the same ordering guarantee with zero controllers and zero finalizers — Kubernetes GC does all the work.

---

## CRD Types

| Kind | Plural | Owns |
|------|--------|------|
| KafkaCluster | kafkaclusters | Strimzi Kafka CR, KafkaNodePool, KafkaTopics |
| CapturedTraffic | capturedtraffics | Proxy Deployment + Service |
| DataSnapshot | datasnapshots | (none — immutable reference) |
| SnapshotMigration | snapshotmigrations | Coordinator StatefulSet + Service + Secret, RFS Deployment |
| TrafficReplay | trafficreplays | Replayer Deployment |
| ApprovalGate | approvalgates | (none — approval mechanism) |
| MigrationRun | migrationruns | (none — immutable run history) |
