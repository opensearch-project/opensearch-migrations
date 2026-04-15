# Workflow CRD Lifecycle & Kubernetes-Native Teardown

## Core Insight

Teardown ordering belongs in **Kubernetes**, not Argo. Argo exit handlers don't survive `argo stop`, and Argo has no concept of "wait for pods to drain." Kubernetes already solves this with owner references and foreground cascading deletion.

## Architecture

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

### Relationship to workflow submission

Reset does **not** stop or delete the Argo workflow. It only deletes migration
CRDs and their owned Kubernetes resources.

To replace a running workflow, use `workflow submit`, which stops and deletes the
existing Argo workflow before resubmitting. This separation keeps reset focused
on the CR graph and submit focused on the workflow lifecycle.

---

## CLI: `workflow submit`

Submit replaces any existing Argo workflow before creating a new one. It does
**not** delete migration CRDs — those survive across workflow resubmissions.

The initializer runs before each submission to:
1. Clean up stale ApprovalGate CRs (label-based deletion + per-name fallback)
2. Create fresh CRD resources
3. Enrich the workflow config with server-assigned CR UIDs
4. Submit the Argo workflow

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
