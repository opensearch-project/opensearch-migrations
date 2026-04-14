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
3. Delete KafkaCluster CRDs → Kafka dies, proxy switches to non-capture mode
4. Delete CapturedTraffic CRDs → proxy pods die

Result: zero "connection refused", "coordinator unavailable", or "upstream gone" errors in any component's logs.

---

## Proxy Lifecycle: State Transitions

The capture proxy has three states:

| State | CRD phase | Proxy behavior | Kafka alive? |
|-------|-----------|---------------|-------------|
| **Capturing** | `Ready` | Routes traffic to source AND writes to Kafka topic | Yes |
| **Non-Capture** | `Ready` | Routes traffic to source only — transparent pass-through | No |
| **Deleted** | CRD gone | Proxy Deployment + Service + Pods all removed via ownership cascade | N/A |

### Transitions

1. **Created → Capturing**: Workflow deploys proxy with `kafkaConnection` parameters, patches CRD to `Ready`.

2. **Capturing → Non-Capture** (Kafka deleted, proxy stays alive): When KafkaCluster CRD is deleted, the workflow's `kafkaGonePath` detects it and redeploys the proxy without Kafka parameters. Client traffic continues flowing to the source cluster.

3. **Non-Capture → Deleted**: Deleting the CapturedTraffic CRD cascades to the Proxy Deployment and pods via ownerReferences.

4. **Capturing → Deleted** (direct): If CapturedTraffic CRD is deleted while Kafka is alive, the proxy is deleted immediately — no intermediate non-capture state.

### Workflow dual-mode parallel teardown

The `setupSingleProxy` template races two teardown signals:

```
[parallel — first signal wins]:
  a. kafkaGonePath:
     → deleteCrd(KafkaCluster)
     → redeployProxyNoCapture (rolling update)
     → deleteCrd(CapturedTraffic)
  b. directTeardown:
     → deleteCrd(CapturedTraffic)
```

- **Path (a) wins** when Kafka is deleted first → proxy transitions to non-capture, then waits for full teardown
- **Path (b) wins** when CapturedTraffic is deleted directly → proxy deleted immediately, path (a) cancelled

---

## Reconfiguration: Disable/Enable Capture

```bash
workflow proxy disable-capture source-proxy   # switch to non-capture
workflow proxy enable-capture source-proxy    # restore capture
```

The command reads the **running workflow's denormalized config** from Argo (not the user's saved ConfigMap), sets `noCapture=true`, patches the CRD's `dependsOn` to remove the Kafka dependency, then resubmits the workflow. The user's saved config is untouched — a fresh `workflow submit` restores the original configuration.

---

## CLI: `workflow reset`

### Usage

```bash
workflow reset                              # list resources and status
workflow reset my-kafka                     # delete kafka (auto-disables proxy capture)
workflow reset 'snap*'                      # glob matching
workflow reset --all                        # delete everything except proxies
workflow reset --all --include-proxies      # delete everything including proxies
workflow reset my-kafka --cascade           # also delete dependents
```

### Proxy protection

By default, proxies are **not deleted** — they are switched to non-capture mode instead. This keeps live traffic flowing through the proxy while the Kafka/replay pipeline is torn down. Use `--include-proxies` to override.

### Dependency-aware deletion

Resources declare dependencies via `spec.dependsOn` on their CRDs. The CLI builds a DAG and deletes resources in parallel, respecting the constraint that a resource is only deleted after all its dependents are gone. Independent branches proceed concurrently.

### `--all` ordering

```
Phase 1: Delete TrafficReplay CRDs    → replayer dies
Phase 2: Delete SnapshotMigration CRDs → RFS dies, coordinator dies
Phase 3: Delete KafkaCluster CRDs     → Kafka dies, proxy → non-capture
Phase 4: Delete CapturedTraffic CRDs  → proxy dies (only with --include-proxies)
Phase 5: Delete ApprovalGate CRDs
Phase 6: argo stop + delete workflow
```

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
