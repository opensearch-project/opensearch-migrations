# Workflow Tree Display — Design Document

## Problem Statement

The `workflow status` command displays workflow steps as a flat, disorganized list. Intermediate
orchestration nodes are stripped, duplicate step names appear without context, and retry
infrastructure noise clutters the output. Users cannot tell which phase a step belongs to or
how the workflow is structured.

---

## Current Behavior

### Pipeline

```
Argo workflow JSON
  → build_nested_workflow_tree()   # boundary-based parent/child tree
  → filter_tree_nodes()           # keep leaves + groupName containers, lift everything else
  → display_workflow_tree()       # Rich tree rendering
```

### Filter rules today

| Condition | Action |
|-----------|--------|
| `type` in Pod, Suspend, Skipped | **Keep** |
| Has `groupName` input parameter | **Keep** (with children) |
| Everything else | **Strip**, promote children to parent |

### What has `groupName` today

Only two template call-sites set it:

- `waitForCompletion` → `groupName=checks`
- `migrateFromSnapshot` → `groupName=migration-0`

Everything else — including the five top-level phases — has no `groupName` and gets stripped.

---

## Current Output Examples

### `workflow status` (migration-workflow — full pipeline)

```
[+] Workflow: migration-workflow
  Phase: Succeeded
  Started: 2026-03-11T17:34:44Z
  Finished: 2026-03-11T17:39:06Z

Workflow Steps
├── ✓  2026-03-11 17:35:09: tryApply (Succeeded)
├── ✓  2026-03-11 17:35:10: waitForCreation (Succeeded)
├── ✓  2026-03-11 17:37:16: waitForSnapshot (Succeeded)
├── ✓  2026-03-11 17:36:28: waitForProxy (Succeeded)
├── ~  2026-03-11 17:34:45: waitForSnapshotMigrationDeps (Skipped)
├── ✓  2026-03-11 17:36:29: waitForProxyDeps(0:capture-proxy) (Succeeded)
├── ✓  2026-03-11 17:35:27: waitForReady (Succeeded)
├── ~  2026-03-11 17:35:19: waitForFix (Skipped)
├── ~  2026-03-11 17:35:19: retryLoop (Skipped)
├── ✓  2026-03-11 17:35:27: tryApply (Succeeded)
├── ✓  2026-03-11 17:35:33: tryApply (Succeeded)
├── ~  2026-03-11 17:35:29: retryLoop (Skipped)
├── ~  2026-03-11 17:35:29: waitForFix (Skipped)
├── ~  2026-03-11 17:35:40: provisionCert (Skipped)
├── ✓  2026-03-11 17:35:49: deployService (Succeeded)
├── ~  2026-03-11 17:35:40: retryLoop (Skipped)
├── ~  2026-03-11 17:35:40: waitForFix (Skipped)
├── ✓  2026-03-11 17:36:08: deployProxyNoTls (Succeeded)
├── ~  2026-03-11 17:36:00: waitForCert (Skipped)
├── ~  2026-03-11 17:36:01: deployProxyWithTls (Skipped)
├── ✓  2026-03-11 17:36:28: patchCapturedTraffic (Succeeded)
├── ✓  2026-03-11 17:36:41: waitForCreation (Succeeded)
├── ✓  2026-03-11 17:36:49: createSnapshot (Succeeded)
├── ✓  2026-03-11 17:36:56: waitForReady (Succeeded)
├── ✓  2026-03-11 17:37:06: deployReplayer (Succeeded)
├── ✓  2026-03-11 17:37:12: Wait For Completion (checks) (Succeeded): Snapshot completed successfully
│   └── ✓  2026-03-11 17:37:10: checkSnapshotCompletion (Succeeded): Snapshot completed successfully
├── ✓  2026-03-11 17:37:16: patchDataSnapshot (Succeeded)
├── ✓  2026-03-11 17:37:25: readSnapshotName (Succeeded)
├── ✓  2026-03-11 17:38:56: Migrate From Snapshot (migration-0) (Succeeded)
│   ├── ✓  2026-03-11 17:37:42: evaluateMetadata (Succeeded)
│   ├── ✓  2026-03-11 17:38:01: migrateMetadata (Succeeded)
│   ├── ~  2026-03-11 17:37:52: approveEvaluate (Skipped)
│   ├── ~  2026-03-11 17:38:14: createRfsCoordinator (Skipped)
│   ├── ✓  2026-03-11 17:38:20: startHistoricalBackfill (Succeeded)
│   ├── ~  2026-03-11 17:38:14: approveMigrate (Skipped)
│   ├── ✓  2026-03-11 17:38:46: Wait For Completion (checks) (Succeeded): ...
│   │   └── ✓  2026-03-11 17:38:41: checkBackfillStatus (Succeeded): ...
│   ├── ✓  2026-03-11 17:38:49: stopHistoricalBackfill (Succeeded)
│   └── ~  2026-03-11 17:38:56: cleanupRfsCoordinator (Skipped)
└── ✓  2026-03-11 17:39:00: patchSnapshotMigration (Succeeded)
```

### `workflow status --workflow-name full-migration-69b1ca89gatz-7fg4j` (snapshot-only)

```
[+] Workflow: full-migration-69b1ca89gatz-7fg4j
  Phase: Succeeded
  Started: 2026-03-11T20:03:25Z
  Finished: 2026-03-11T20:06:51Z

Workflow Steps
├── ~  2026-03-11 20:03:25: runTrafficReplays (Skipped)
├── ✓  2026-03-11 20:04:22: waitForSnapshot (Succeeded)
├── ~  2026-03-11 20:03:25: setupKafkaClusters (Skipped)
├── ~  2026-03-11 20:03:25: waitForProxyDeps (Skipped)
├── ✓  2026-03-11 20:03:58: createSnapshot (Succeeded)
├── ~  2026-03-11 20:03:25: setupProxies (Skipped)
├── ✓  2026-03-11 20:04:18: Wait For Completion (checks) (Succeeded): Snapshot completed successfully
│   └── ✓  2026-03-11 20:04:15: checkSnapshotCompletion (Succeeded): Snapshot completed successfully
├── ✓  2026-03-11 20:04:22: patchDataSnapshot (Succeeded)
├── ✓  2026-03-11 20:04:34: readSnapshotName (Succeeded)
├── ✓  2026-03-11 20:06:41: Migrate From Snapshot (migration-0) (Succeeded)
│   ├── ✓  2026-03-11 20:04:56: evaluateMetadata (Succeeded)
│   ├── ✓  2026-03-11 20:05:18: migrateMetadata (Succeeded)
│   ├── ~  2026-03-11 20:05:06: approveEvaluate (Skipped)
│   ├── ~  2026-03-11 20:05:28: createRfsCoordinator (Skipped)
│   ├── ✓  2026-03-11 20:05:34: startHistoricalBackfill (Succeeded)
│   ├── ~  2026-03-11 20:05:28: approveMigrate (Skipped)
│   ├── ✓  2026-03-11 20:06:21: Wait For Completion (checks) (Succeeded): ...
│   │   └── ✓  2026-03-11 20:06:11: checkBackfillStatus (Succeeded): ...
│   ├── ✓  2026-03-11 20:06:29: stopHistoricalBackfill (Succeeded)
│   └── ~  2026-03-11 20:06:41: cleanupRfsCoordinator (Skipped)
└── ✓  2026-03-11 20:06:47: patchSnapshotMigration (Succeeded)
```

### Problems visible in both outputs

1. **No phase grouping** — `tryApply`, `waitForCreation`, `waitForReady` appear 2-3× with no
   indication of which phase they belong to.
2. **Retry noise** — `retryLoop` / `waitForFix` pairs appear 3× (one per retry-enabled step).
3. **Skipped alternative paths** — `provisionCert`, `waitForCert`, `deployProxyWithTls` are
   TLS-path steps that weren't taken. They add clutter without value.
4. **Internal plumbing visible** — `patchCapturedTraffic`, `patchDataSnapshot`,
   `patchSnapshotMigration`, `idGenerator`, `getConsoleConfig` are internal bookkeeping.
5. **Skipped phases mixed in** — In the snapshot-only workflow, `setupKafkaClusters (Skipped)`
   and `setupProxies (Skipped)` appear interleaved with active steps.

---

## Actual Workflow Structure (from Argo boundary tree)

The `main` template runs five top-level phases in parallel (all in step-group `[0]`):

```
migration-workflow (Steps, template=main)
├── setupKafkaClusters
│   └── deployCluster
│       └── deployPool
│           ├── tryApply (Pod)
│           ├── waitForFix (Skipped) ← retry
│           └── retryLoop (Skipped) ← retry
│       └── deployCluster (kraft)
│           ├── tryApply (Pod)
│           ├── waitForFix (Skipped) ← retry
│           └── retryLoop (Skipped) ← retry
│   └── waitForCreation (Pod)
│   └── waitForReady (Pod)
├── setupProxies
│   ├── waitForKafkaCluster
│   │   ├── waitForCreation (Pod)
│   │   └── waitForReady (Pod)
│   ├── setupProxy
│   │   ├── createKafkaTopic
│   │   │   ├── tryApply (Pod)
│   │   │   ├── waitForFix (Skipped) ← retry
│   │   │   └── retryLoop (Skipped) ← retry
│   │   ├── provisionCert (Skipped) ← TLS path
│   │   ├── deployService (Pod)
│   │   ├── deployProxyNoTls (Pod)
│   │   ├── waitForCert (Skipped) ← TLS path
│   │   └── deployProxyWithTls (Skipped) ← TLS path
│   └── patchCapturedTraffic (Pod)
├── createSnapshots
│   └── createSnapshot
│       ├── waitForProxyDeps (Pod)
│       └── createOrGetSnapshot
│           ├── getSnapshotName
│           └── createSnapshot
│               ├── createSnapshot (Pod)
│               ├── getConsoleConfig ← internal
│               ├── waitForCompletion [groupName=checks]
│               │   └── checkSnapshotCompletion (Pod)
│               └── patchDataSnapshot (Pod)
├── runSnapshotMigrations
│   ├── waitForSnapshot (Pod)
│   ├── readSnapshotName (Pod)
│   ├── migrateFromSnapshot [groupName=migration-0]
│   │   ├── idGenerator ← internal
│   │   ├── metadataMigrate
│   │   │   ├── evaluateMetadata (Pod)
│   │   │   ├── approveEvaluate (Skipped)
│   │   │   ├── migrateMetadata (Pod)
│   │   │   └── approveMigrate (Skipped)
│   │   └── bulkLoadDocuments
│   │       ├── createRfsCoordinator (Skipped)
│   │       └── runBulkLoad
│   │           ├── startHistoricalBackfillFromConfig
│   │           │   └── startHistoricalBackfill (Pod)
│   │           ├── setupWaitForCompletion ← internal
│   │           ├── waitForCompletion [groupName=checks]
│   │           │   └── checkBackfillStatus (Pod)
│   │           ├── stopHistoricalBackfill (Pod)
│   │           └── cleanupRfsCoordinator (Skipped)
│   └── patchSnapshotMigration (Pod)
└── runTrafficReplays
    ├── waitForSnapshotMigrationDeps (Skipped)
    ├── waitForProxy (Pod)
    ├── waitForKafkaCluster
    │   ├── waitForCreation (Pod)
    │   └── waitForReady (Pod)
    └── deployReplayer
        └── deployReplayer (Pod)
```

---

## Existing Metadata on Nodes

### `groupName` (used for display grouping today)

| Node | groupName |
|------|-----------|
| `waitForCompletion` (snapshot) | `checks` |
| `waitForCompletion` (backfill) | `checks` |
| `migrateFromSnapshot` | `migration-0` |

### K8s label parameters (used by `output --selector` today)

Pods carry `sourceK8sLabel`, `targetK8sLabel`, `snapshotK8sLabel`, `taskK8sLabel`,
`fromSnapshotMigrationK8sLabel`. These are used for log filtering via `output --selector`
and have tab-completion support via `autocomplete_k8s_labels.py`.

### What's missing

The five top-level phases (`setupKafkaClusters`, `setupProxies`, `createSnapshots`,
`runSnapshotMigrations`, `runTrafficReplays`) have **no `groupName`**. Intermediate
orchestration nodes (`deployCluster`, `setupProxy`, `metadataMigrate`, `bulkLoadDocuments`,
etc.) also lack it. There is no convention to mark nodes as "internal/hidden" or
"retry infrastructure".

---

## Desired Output

### Full pipeline workflow

```
Workflow Steps
├── ✓ Setup Kafka Clusters
│   ├── ✓ tryApply
│   ├── ✓ waitForCreation
│   └── ✓ waitForReady
├── ✓ Setup Proxies
│   ├── ✓ tryApply
│   ├── ✓ deployService
│   ├── ✓ deployProxyNoTls
│   └── ✓ patchCapturedTraffic
├── ✓ Create Snapshots
│   ├── ✓ createSnapshot
│   └── ✓ Wait For Completion (checks): Snapshot completed successfully
│       └── ✓ checkSnapshotCompletion
├── ✓ Run Snapshot Migrations
│   ├── ✓ Migrate From Snapshot (migration-0)
│   │   ├── ✓ evaluateMetadata
│   │   ├── ~ approveEvaluate (Not Required)
│   │   ├── ✓ migrateMetadata
│   │   ├── ~ approveMigrate (Not Required)
│   │   ├── ✓ startHistoricalBackfill
│   │   ├── ✓ Wait For Completion (checks): backfill 100% ...
│   │   │   └── ✓ checkBackfillStatus
│   │   └── ✓ stopHistoricalBackfill
│   └── ✓ patchSnapshotMigration
└── ✓ Run Traffic Replays
    └── ✓ deployReplayer
```

### Snapshot-only workflow (skipped phases collapsed)

```
Workflow Steps
├── ~ Setup Kafka Clusters (Skipped)
├── ~ Setup Proxies (Skipped)
├── ✓ Create Snapshots
│   ├── ✓ createSnapshot
│   └── ✓ Wait For Completion (checks): Snapshot completed successfully
│       └── ✓ checkSnapshotCompletion
├── ✓ Run Snapshot Migrations
│   ├── ✓ Migrate From Snapshot (migration-0)
│   │   ├── ✓ evaluateMetadata
│   │   ├── ~ approveEvaluate (Not Required)
│   │   ├── ✓ migrateMetadata
│   │   ├── ~ approveMigrate (Not Required)
│   │   ├── ✓ startHistoricalBackfill
│   │   ├── ✓ Wait For Completion (checks): backfill 100% ...
│   │   │   └── ✓ checkBackfillStatus
│   │   └── ✓ stopHistoricalBackfill
│   └── ✓ patchSnapshotMigration
└── ~ Run Traffic Replays (Skipped)
```

---

## Strategy 1: Top-Level Phase Grouping

### Goal

Preserve the five top-level phase Steps nodes as grouping containers instead of stripping them.

### Approach

Modify `filter_tree_nodes` to track whether we're still at the "root level" of the tree.
While stripping wrapper nodes (root Steps, StepGroups), pass an `at_root_level=True` flag
through. The first meaningful Steps nodes encountered at root level are kept as phase
containers. Their children are processed with `at_root_level=False` (normal flattening).

A "meaningful Steps node" is one whose `display_name` does NOT match the StepGroup index
pattern `[N]`.

### Workflow-side changes needed

Add `groupName` to the five top-level phase templates so they're self-describing:

| Template call | `groupName` to add |
|---------------|-------------------|
| `setupKafkaClusters` | Use `clusterName` param (e.g. `"default"`) |
| `setupProxies` | Use `proxyName` param (e.g. `"capture-proxy"`) |
| `createSnapshots` | Derive from source label (e.g. `"source-snap1"`) |
| `runSnapshotMigrations` | Use existing `resourceName` |
| `runTrafficReplays` | Use `fromCapturedTraffic` param |

With `groupName` on these nodes, the existing filter already keeps them — no `at_root_level`
logic needed. However, the `at_root_level` approach is more resilient as a fallback for
workflows that don't set `groupName` on phases.

**Recommendation**: Do both. Add `groupName` to workflow templates AND implement
`at_root_level` as a safety net.

---

## Strategy 2: Noise Filtering via Conventions

### Categories of noise

| Category | Examples | Convention |
|----------|----------|------------|
| Retry infrastructure | `retryLoop`, `waitForFix` | Mark with `displayCategory=retry` |
| Internal plumbing | `idGenerator`, `getConsoleConfig`, `setupWaitForCompletion`, `patchDataSnapshot`, `patchSnapshotMigration`, `patchCapturedTraffic` | Mark with `displayCategory=internal` |
| Alternative paths | `provisionCert`, `waitForCert`, `deployProxyWithTls` | Already Skipped; filter when entire branch is skipped |

### Option A: `displayCategory` input parameter (recommended)

Add a `displayCategory` input parameter to workflow templates. The filter checks this
parameter and hides nodes accordingly.

Values:
- `retry` — always hidden
- `internal` — hidden by default, shown with `--show-all` / `--verbose`
- (absent) — always shown

This is a **workflow-side convention** — the CLI just reads it. Any workflow template can
adopt it.

### Option B: Name-based heuristic (fragile, not recommended)

Hardcode names like `retryLoop`, `waitForFix` in the filter. Breaks when templates are
renamed. Not workflow-agnostic.

### Option C: Skipped-subtree pruning

If a Steps node and ALL its descendants are Skipped, collapse the entire subtree into a
single "Skipped" line. This handles alternative paths (TLS vs no-TLS) and skipped phases
without any workflow-side changes.

**Recommendation**: Implement Option A (`displayCategory`) for explicit control, plus
Option C (skipped-subtree pruning) for automatic cleanup.

---

## Strategy 3: Skipped-Subtree Pruning

### Rule

When a Steps/StepGroup container node has phase=Skipped, or when ALL leaf descendants of a
container are Skipped, collapse the entire subtree into a single node showing the container
name with "(Skipped)".

### Algorithm

```
def is_fully_skipped(node):
    if node is a leaf: return node.phase == 'Skipped'
    return all(is_fully_skipped(child) for child in node.children)
```

During filtering, if `is_fully_skipped(node)` is true, emit a single Skipped leaf node
with the container's display name and discard all children.

### Effect

- Retry branches where the retry wasn't triggered → collapsed
- TLS path when TLS wasn't configured → collapsed
- Entire skipped phases → single line (already works for Skipped-type nodes)

---

## Strategy 4: Label-Based Tree Filtering

### Goal

Allow `workflow status --selector source=source,target=target` to show only the subtree
relevant to a specific source/target/snapshot/migration combination.

### Current state

The `output` command already supports `--selector` for log filtering using K8s label
parameters (`sourceK8sLabel`, `targetK8sLabel`, etc.) with tab-completion. The `status`
command does not support this.

### Approach

1. Walk the tree and mark nodes whose K8s label parameters match the selector.
2. For each matched node, include all ancestors up to the root (so the tree structure is
   preserved).
3. For each matched node, include all descendants (so you see the full sub-operation).
4. Prune branches that have no matched nodes and no matched descendants.

### Label propagation convention

For this to work, intermediate Steps nodes need to carry label parameters too. Today,
only Pods and a few Steps nodes have `sourceK8sLabel`/`targetK8sLabel`. We should ensure
that every Steps node in the workflow propagates these labels from its inputs.

The existing `*K8sLabel` parameters are already partially propagated. The gap is in
top-level phase nodes and some intermediate orchestration nodes.

### CLI interface

```
workflow status --selector source=source,snapshot=snap1
workflow status --selector target=target,from-snapshot-migration=migration-0
```

Reuse the same `--selector` syntax and tab-completion from the `output` command.

---

## Strategy 5: Node Coalescing

### Goal

Merge intermediate wrapper nodes that add nesting without value.

### Candidates for coalescing

| Wrapper | Child | Coalesced display |
|---------|-------|-------------------|
| `deployReplayer` (Steps) | `deployReplayer` (Pod) | Single `deployReplayer` Pod |
| `startHistoricalBackfillFromConfig` | `startHistoricalBackfill` | Single `startHistoricalBackfill` Pod |
| `createOrGetSnapshot` → `createSnapshot` → `createSnapshot` (Pod) | Deep nesting | Single `createSnapshot` Pod |

### Rule

If a Steps node has exactly one meaningful child (after filtering), and the child is a Pod
with the same or similar display name, replace the Steps node with the Pod.

### Workflow-side alternative

Flatten these in the workflow templates themselves. For example, inline the
`startHistoricalBackfillFromConfig` template into `runBulkLoad`. This is cleaner but
requires workflow template changes.

**Recommendation**: Implement the coalescing rule in the filter for resilience, but also
simplify the workflow templates where possible.

---


## Alternative Output Visualizations

The examples below all use the same `migration-workflow` data. Each shows a different
rendering strategy so we can mix and match the ideas we like.

### Alt A: Keep All Interior Nodes (every non-leaf rendered)

Every Steps node with a real name is kept. StepGroups (`[0]`, `[1]`) are still stripped.
This preserves the full logical hierarchy.

```
Workflow Steps
├── ✓ Setup Kafka Clusters (default)
│   └── ✓ Deploy Cluster (default, v4.0.0)
│       ├── ✓ Deploy Pool (default)
│       │   └── ✓ tryApply
│       └── ✓ Deploy Cluster (default, v4.0.0)
│           └── ✓ tryApply
├── ✓ Setup Proxies (capture-proxy)
│   ├── ✓ Wait For Kafka Cluster (default)
│   │   ├── ✓ waitForCreation
│   │   └── ✓ waitForReady
│   ├── ✓ Setup Proxy (capture-proxy)
│   │   ├── ✓ Create Kafka Topic (capture-proxy)
│   │   │   └── ✓ tryApply
│   │   ├── ✓ deployService
│   │   └── ✓ deployProxyNoTls
│   └── ✓ patchCapturedTraffic
├── ✓ Create Snapshots
│   └── ✓ Create Snapshot (snap1)
│       └── ✓ Create Or Get Snapshot (snap1)
│           ├── ✓ Get Snapshot Name (source/snap1)
│           └── ✓ Create Snapshot (snap1)
│               ├── ✓ createSnapshot
│               ├── ✓ Wait For Completion (checks): Snapshot completed successfully
│               │   └── ✓ checkSnapshotCompletion
│               └── ✓ patchDataSnapshot
├── ✓ Run Snapshot Migrations (source-target-snap1)
│   ├── ✓ waitForSnapshot
│   ├── ✓ readSnapshotName
│   ├── ✓ Migrate From Snapshot (migration-0)
│   │   ├── ✓ Metadata Migrate (source, migration-0)
│   │   │   ├── ✓ evaluateMetadata
│   │   │   ├── ~ approveEvaluate (Not Required)
│   │   │   ├── ✓ migrateMetadata
│   │   │   └── ~ approveMigrate (Not Required)
│   │   └── ✓ Bulk Load Documents (source, migration-0)
│   │       └── ✓ Run Bulk Load (source, migration-0)
│   │           ├── ✓ startHistoricalBackfill
│   │           ├── ✓ Wait For Completion (checks): backfill 100% ...
│   │           │   └── ✓ checkBackfillStatus
│   │           └── ✓ stopHistoricalBackfill
│   └── ✓ patchSnapshotMigration
└── ✓ Run Traffic Replays (capture-proxy)
    ├── ✓ waitForProxy
    ├── ✓ Wait For Kafka Cluster (default)
    │   ├── ✓ waitForCreation
    │   └── ✓ waitForReady
    └── ✓ Deploy Replayer (capture-proxy-target-replayer)
        └── ✓ deployReplayer
```

**Pros**: Full context — you can see exactly what kafka cluster, topic, proxy, snapshot is
being acted on. Interior names like "Metadata Migrate" and "Bulk Load Documents" add clarity.

**Cons**: Deep nesting (6 levels). Some wrappers like `Deploy Cluster` → `Deploy Pool` →
`tryApply` are still noisy. `Create Or Get Snapshot` → `Create Snapshot` → `createSnapshot`
is three levels for one action.

---

### Alt B: Retry-Aware Rendering (tryApply/waitForFix/retryLoop → single smart node)

The retry pattern `tryApply → waitForFix → retryLoop` is a convention. Instead of showing
three nodes, render it as a single node whose display depends on state:

| Retry state | Rendered as |
|-------------|-------------|
| tryApply Succeeded, waitForFix Skipped | `✓ Apply kafka-nodepool (default)` |
| tryApply Failed, waitForFix Running | `⟳ Apply kafka-nodepool (default) — FAILED, waiting for fix` |
| tryApply Failed, retryLoop has children | `✗ Apply kafka-nodepool (default) — failed (retry 2/3)` |

Combined with Alt A's interior nodes:

```
Workflow Steps
├── ✓ Setup Kafka Clusters (default)
│   ├── ✓ Apply kafka-nodepool (default)
│   └── ✓ Apply kafka-cluster (default, v4.0.0)
├── ✓ Setup Proxies (capture-proxy)
│   ├── ✓ Wait For Kafka Cluster (default)
│   ├── ✓ Apply kafka-topic (capture-proxy)
│   ├── ✓ deployService (capture-proxy)
│   ├── ✓ deployProxyNoTls (capture-proxy)
│   └── ✓ patchCapturedTraffic
├── ✓ Create Snapshots (snap1)
│   ├── ✓ createSnapshot (snap1)
│   ├── ✓ Wait For Completion (checks): Snapshot completed successfully
│   │   └── ✓ checkSnapshotCompletion
│   └── ✓ patchDataSnapshot
├── ✓ Run Snapshot Migrations (source → target, snap1)
│   ├── ✓ Migrate From Snapshot (migration-0)
│   │   ├── ✓ evaluateMetadata
│   │   ├── ~ approveEvaluate (Not Required)
│   │   ├── ✓ migrateMetadata
│   │   ├── ~ approveMigrate (Not Required)
│   │   ├── ✓ startHistoricalBackfill
│   │   ├── ✓ Wait For Completion (checks): backfill 100% ...
│   │   │   └── ✓ checkBackfillStatus
│   │   └── ✓ stopHistoricalBackfill
│   └── ✓ patchSnapshotMigration
└── ✓ Run Traffic Replays (capture-proxy → target)
    └── ✓ deployReplayer (capture-proxy-target-replayer)
```

And when a retry is actually in progress:

```
├── ✗ Setup Kafka Clusters (default)
│   ├── ✓ Apply kafka-nodepool (default)
│   └── ⟳ Apply kafka-cluster (default) — FAILED, waiting for approval to retry
```

**Pros**: Retry pattern becomes a single meaningful line. Shows what resource is being
created. Much less noise.

**Cons**: Requires a workflow convention to identify retry groups (e.g. a `retryGroup`
parameter or a naming convention). The filter needs to understand the three-step pattern.

### Workflow convention for retry groups

Add a `retryGroupName` input parameter to the `*withretry` templates. The value describes
what's being applied (e.g. `kafka-nodepool/default`, `kafka-cluster/default`,
`kafka-topic/capture-proxy`). The filter recognizes `tryApply`+`waitForFix`+`retryLoop`
children and coalesces them using `retryGroupName` as the display label.

---

### Alt C: Skipped-Subtree Pruning + Fully-Skipped Phase Collapsing

When an entire subtree is skipped, collapse it. When a phase ran but some branches were
skipped (TLS path, coordinator), hide those branches entirely.

```
Workflow Steps
├── ✓ Setup Kafka Clusters (default)
│   ├── ✓ tryApply
│   ├── ✓ tryApply
│   ├── ✓ waitForCreation
│   └── ✓ waitForReady
├── ✓ Setup Proxies (capture-proxy)
│   ├── ✓ tryApply
│   ├── ✓ deployService
│   ├── ✓ deployProxyNoTls
│   └── ✓ patchCapturedTraffic
├── ✓ Create Snapshots (snap1)
│   ├── ✓ createSnapshot
│   ├── ✓ Wait For Completion (checks): Snapshot completed successfully
│   │   └── ✓ checkSnapshotCompletion
│   └── ✓ patchDataSnapshot
├── ✓ Run Snapshot Migrations (source-target-snap1)
│   ├── ✓ Migrate From Snapshot (migration-0)
│   │   ├── ✓ evaluateMetadata
│   │   ├── ~ approveEvaluate (Not Required)
│   │   ├── ✓ migrateMetadata
│   │   ├── ~ approveMigrate (Not Required)
│   │   ├── ✓ startHistoricalBackfill
│   │   ├── ✓ Wait For Completion (checks): backfill 100% ...
│   │   │   └── ✓ checkBackfillStatus
│   │   └── ✓ stopHistoricalBackfill
│   └── ✓ patchSnapshotMigration
└── ✓ Run Traffic Replays (capture-proxy)
    └── ✓ deployReplayer
```

Snapshot-only workflow — skipped phases are single lines, no children leaked:

```
Workflow Steps
├── ~ Setup Kafka Clusters (Skipped)
├── ~ Setup Proxies (Skipped)
├── ✓ Create Snapshots (snap1)
│   ...
├── ✓ Run Snapshot Migrations (source-target-snap1)
│   ...
└── ~ Run Traffic Replays (Skipped)
```

**Pros**: Cleanest output. No retry noise, no TLS-path noise, no skipped-phase children
leaking. Works without any new workflow conventions.

**Cons**: Still has duplicate `tryApply` without context. Hides information that might
matter during debugging (use `--verbose` to show).

---

### Alt D: Combined Best-Of (recommended baseline)

Combines: interior nodes shown + retry coalescing + skipped-subtree pruning +
resource labels on nodes.

```
Workflow Steps
├── ✓ Setup Kafka Clusters (default)
│   ├── ✓ Apply kafka-nodepool (default)
│   ├── ✓ Apply kafka-cluster (default, v4.0.0)
│   ├── ✓ waitForCreation (default)
│   └── ✓ waitForReady (default)
├── ✓ Setup Proxies (capture-proxy)
│   ├── ✓ Wait For Kafka Cluster (default)
│   ├── ✓ Apply kafka-topic (capture-proxy)
│   ├── ✓ deployService (capture-proxy)
│   ├── ✓ deployProxyNoTls (capture-proxy)
│   └── ✓ patchCapturedTraffic (capture-proxy)
├── ✓ Create Snapshots (snap1)
│   ├── ✓ createSnapshot (snap1)
│   ├── ✓ Wait For Completion (checks): Snapshot completed successfully
│   │   └── ✓ checkSnapshotCompletion
│   └── ✓ patchDataSnapshot (source-snap1)
├── ✓ Run Snapshot Migrations (source → target, snap1)
│   ├── ✓ Migrate From Snapshot (migration-0)
│   │   ├── ✓ Metadata Migrate
│   │   │   ├── ✓ evaluateMetadata
│   │   │   ├── ~ approveEvaluate (Not Required)
│   │   │   ├── ✓ migrateMetadata
│   │   │   └── ~ approveMigrate (Not Required)
│   │   └── ✓ Bulk Load Documents
│   │       ├── ✓ startHistoricalBackfill
│   │       ├── ✓ Wait For Completion (checks): backfill 100% ...
│   │       │   └── ✓ checkBackfillStatus
│   │       └── ✓ stopHistoricalBackfill
│   └── ✓ patchSnapshotMigration
└── ✓ Run Traffic Replays (capture-proxy → target)
    └── ✓ deployReplayer (capture-proxy-target-replayer)
```

During a failure with active retry:

```
├── ✗ Setup Kafka Clusters (default)
│   ├── ✓ Apply kafka-nodepool (default)
│   ├── ⟳ Apply kafka-cluster (default) — FAILED, waiting for approval to retry
│   ...
```

**Key conventions this requires in the workflow templates:**

1. `groupName` on every interior Steps node that should be rendered (phases, sub-phases)
2. `retryGroupName` on `*withretry` templates (e.g. `kafka-nodepool/default`)
3. `displayLabel` on leaf Pods — a short human-readable label for what resource is being
   acted on (pulled from `clusterName`, `proxyName`, `topicName`, `snapshotPrefix`, etc.)
4. Skipped-subtree pruning handles the rest automatically (no convention needed)

---

### Alt E: Flat Mode (for label-filtered views)

When filtering by `--selector source=source,snapshot=snap1`, switch to a flat timeline
view. Only matching nodes are shown, sorted chronologically. No tree nesting.

```
$ workflow status --selector source=source,snapshot=snap1 --flat

Workflow: migration-workflow (Succeeded)
Started: 2026-03-11T17:34:44Z → Finished: 2026-03-11T17:39:06Z

  ✓ 17:36:49  createSnapshot           snap1         source
  ✓ 17:37:10  checkSnapshotCompletion  snap1         source
  ✓ 17:37:42  evaluateMetadata         migration-0   source → target (snap1)
  ✓ 17:38:01  migrateMetadata          migration-0   source → target (snap1)
  ✓ 17:38:20  startHistoricalBackfill  migration-0   source → target (snap1)
  ✓ 17:38:41  checkBackfillStatus      migration-0   source → target (snap1)
  ✓ 17:38:49  stopHistoricalBackfill   migration-0   source → target (snap1)
```

Without `--flat`, the same `--selector` shows a pruned tree (ancestors + descendants of
matched nodes):

```
$ workflow status --selector source=source,snapshot=snap1

Workflow Steps
├── ✓ Create Snapshots (snap1)
│   ├── ✓ createSnapshot (snap1)
│   └── ✓ Wait For Completion (checks): Snapshot completed successfully
│       └── ✓ checkSnapshotCompletion
└── ✓ Run Snapshot Migrations (source → target, snap1)
    └── ✓ Migrate From Snapshot (migration-0)
        ├── ✓ Metadata Migrate
        │   ├── ✓ evaluateMetadata
        │   └── ✓ migrateMetadata
        └── ✓ Bulk Load Documents
            ├── ✓ startHistoricalBackfill
            ├── ✓ Wait For Completion (checks): backfill 100% ...
            │   └── ✓ checkBackfillStatus
            └── ✓ stopHistoricalBackfill
```

**Pros**: Flat mode is great for "what happened to my snapshot?" questions. Pruned tree
mode preserves structure while removing irrelevant branches.

**Cons**: Flat mode loses phase context. Need both modes available.

---

### Alt F: Retry Flattening with Count (recursive retries shown as one line)

When `retryLoop` recurses multiple times, instead of showing nested retry trees, show a
single line with the attempt count:

```
├── ✗ Setup Kafka Clusters (default)
│   ├── ✗ Apply kafka-cluster (default) — attempt 3/∞, last failed
│   ...
```

If the retry succeeded after multiple attempts:

```
├── ✓ Setup Kafka Clusters (default)
│   ├── ✓ Apply kafka-cluster (default) — succeeded on attempt 2
│   ...
```

The filter walks the recursive `retryLoop` chain, counts iterations, and takes the final
state. This works because `retryLoop` calls the same template recursively — each level
adds one `tryApply` child.

**Convention needed**: The `*withretry` templates already follow a consistent pattern
(`tryApply` → `waitForFix` → `retryLoop`). The filter can detect this by name convention
or by a `retryPattern=true` parameter on the template.

---

### Summary: Which ideas to combine?

| Technique | Source | Needs workflow changes? |
|-----------|--------|------------------------|
| Keep interior nodes with names | Alt A | Yes — add `groupName` |
| Retry coalescing (3 nodes → 1) | Alt B | Yes — add `retryGroupName` |
| Retry count flattening | Alt F | Minimal — detect by naming convention |
| Skipped-subtree pruning | Alt C | No |
| Resource labels on nodes | Alt D | Yes — add `displayLabel` |
| Flat mode for filtered views | Alt E | No |
| Pruned tree for filtered views | Alt E | No (uses existing K8s labels) |


## Alt A Implementation Plan: Keep All Interior Nodes

### How the workflow is built today

Workflow templates are TypeScript in `orchestrationSpecs/packages/migration-workflow-templates/`.
`fullMigration.ts` defines the `main` template using a builder DSL. The five top-level
phases are all in one step-group (parallel):

```typescript
.addTemplate("main", t => t
    .addSteps(b => b.addStepGroup(g => g
        .addStep("setupKafkaClusters",    INTERNAL, "setupSingleKafkaCluster", ...)
        .addStep("setupProxies",          INTERNAL, "setupSingleProxy", ...)
        .addStep("createSnapshots",       INTERNAL, "createSnapshotsForSource", ...)
        .addStep("runSnapshotMigrations", INTERNAL, "runSingleSnapshotMigration", ...)
        .addStep("runTrafficReplays",     INTERNAL, "runSingleReplay", ...)
    ))
)
```

`INTERNAL` means the step references a template in the same WorkflowBuilder (same file).
Each `addStep` becomes an Argo Steps node at runtime. `groupName` is currently set in only
three places: `migrateFromSnapshot` (from migration label), and two `waitForCompletion`
templates (hardcoded `"checks"`). The five top-level phases and all intermediate
orchestration templates have **no `groupName`**.

### Part 1: Workflow-side changes

Add `groupName` to every Steps template that should appear as a visible container.

**Top-level phases** (in `main`, `fullMigration.ts`) — these are loop steps, so each
iteration gets its `groupName` from the loop item:

| Step | `groupName` source | Example value |
|------|--------------------|---------------|
| `setupKafkaClusters` | `clusterName` param | `"default"` |
| `setupProxies` | `proxyName` param | `"capture-proxy"` |
| `createSnapshots` | derive from source config | `"source"` |
| `runSnapshotMigrations` | `resourceName` param | `"source-target-snap1"` |
| `runTrafficReplays` | `fromCapturedTraffic` param | `"capture-proxy"` |

Each requires: (1) the target template to accept `groupName` as an input, and (2) the
caller to pass it in `c.register({...})`.

**Sub-phase templates** that should also get `groupName`:

| Template | File | `groupName` source |
|----------|------|--------------------|
| `deployCluster` | `fullMigration.ts` | `clusterName` |
| `setupProxy` | `fullMigration.ts` | `proxyName` |
| `waitForKafkaCluster` | `fullMigration.ts` | `kafkaClusterName` |
| `createSnapshot` (per-source) | `fullMigration.ts` | `targetLabel` |
| `metadataMigrate` | `fullMigration.ts` | `migrationLabel` |
| `bulkLoadDocuments` | `fullMigration.ts` | `migrationLabel` |
| `createKafkaTopic` | `setupCapture.ts` | `topicName` |
| `startHistoricalBackfillFromConfig` | `documentBulkLoad.ts` | `migrationLabel` |

**Templates that should NOT get `groupName`** (internal plumbing, will be stripped):
`doNothing`/`idGenerator`, `getConsoleConfig`, `setupWaitForCompletion`,
`getSnapshotName`, `createOrGetSnapshot`, `deployPool`.

### Part 2: Python-side changes (`tree_utils.py`)

Once `groupName` is on interior nodes, the existing `has_group_name` check in
`filter_tree_nodes` keeps them automatically. No filter logic changes needed for the
primary path.

Add an `at_root_level` fallback so workflows without `groupName` on phases still get
reasonable grouping. In `filter_recursive`, pass `at_root_level=True` while stripping
wrappers. The first meaningful Steps nodes (display name not matching `[N]`) encountered
at root level are kept. Their children are processed with `at_root_level=False`.

Also update `get_step_rich_label` — no changes needed there. `clean_display_name` already
converts camelCase to Title Case and strips args. `group_name` is already appended. Once
the workflow sets `groupName`, labels like "Setup Kafka Clusters (default)" appear
automatically.

### Part 3: Test changes

**Existing fixtures affected by `at_root_level`:**
- `container_with_pods.json` — container Steps at root level now kept. Update expected.
- `parallel_snapshots.json` — `foreachMigrationPair` at root level now kept. Update expected.

**New fixtures to add:**
1. `top_level_phases.json` — 5 phase Steps with `groupName`, children nested
2. `top_level_phases_no_groupname.json` — same without `groupName`, validates fallback
3. `deep_interior_nodes.json` — multiple levels, validates only root-level gets fallback

### Execution order

1. Add `groupName` to the five top-level phase templates in `fullMigration.ts`
2. Add `groupName` to key sub-phase templates
3. Add `at_root_level` fallback to `filter_tree_nodes`
4. Update affected test fixtures, add new ones
5. Redeploy to kind, resubmit workflow, verify output

### What this does NOT address (deferred)

- Retry noise (`retryLoop`/`waitForFix`)
- Internal plumbing (`idGenerator`, `patchDataSnapshot`)
- Skipped alternative paths (`provisionCert`, `deployProxyWithTls`)
- Resource labels on leaf pods
- Label-based filtering


## Alt B Implementation Plan: Retry-Aware Rendering

### The retry pattern

Three templates follow the same recursive retry pattern:

```
*WithRetry (Steps template)
├── [0] tryApply (Pod) — continueOn.failed=true
├── [1] waitForFix (Suspend, name="KafkaNodePool"|"KafkaCluster"|"KafkaTopic")
│        — when: tryApply.status == "Failed"
└── [2] retryLoop (*WithRetry, recursive self-call)
         — when: waitForFix.status == "Succeeded"
```

States at runtime:
- **Success on first try**: tryApply=Succeeded, waitForFix=Skipped, retryLoop=Skipped
- **Failed, awaiting fix**: tryApply=Failed, waitForFix=Running (suspended)
- **After fix+resume**: waitForFix=Succeeded, retryLoop creates new tryApply/waitForFix/retryLoop
- **Success after N retries**: recursive chain of N `*WithRetry` nodes, last tryApply=Succeeded

### Desired rendering

| State | Display |
|-------|---------|
| Success first try | `✓ Apply KafkaNodePool` |
| Failed, waiting | `⟳ Apply KafkaNodePool — FAILED, waiting for approval to retry` |
| Success after 2 tries | `✓ Apply KafkaNodePool (attempt 2)` |
| Failed after 3 tries | `✗ Apply KafkaNodePool — failed (attempt 3)` |

### Workflow-side changes

Add a `retryGroupName` input parameter to each `*WithRetry` template. This is the
human-readable label for what's being applied. The callers pass it:

| Template | `retryGroupName` value |
|----------|----------------------|
| `deployKafkaNodePoolWithRetry` | `"KafkaNodePool: " + clusterName` |
| `deployKafkaClusterKraftWithRetry` | `"KafkaCluster: " + clusterName` |
| `createKafkaTopicWithRetry` | `"KafkaTopic: " + topicName` |

Each `*WithRetry` template gets:
```typescript
.addOptionalInput("retryGroupName", c => "Apply")
```

And the callers in `setupKafka.ts` / `fullMigration.ts` pass:
```typescript
retryGroupName: expr.concat(expr.literal("KafkaNodePool: "), b.inputs.clusterName)
```

The recursive `retryLoop` step passes `retryGroupName` through to itself.

### Python-side changes (`tree_utils.py`)

Add a new step in `filter_tree_nodes` that detects and collapses retry groups.

**Detection**: A Steps node is a retry group if it has children matching the pattern:
- A child named `tryApply` (Pod)
- A child named `waitForFix` (Suspend or Skipped)
- A child named `retryLoop` (Steps/Skipped, optional — may not exist yet if still on first attempt)

**Collapsing logic**:

```python
def _collapse_retry_group(node):
    """Collapse a *WithRetry Steps node into a single display node."""
    children = node.get('children', [])
    child_map = {c.get('display_name', '').split('(')[0]: c for c in children}

    try_apply = child_map.get('tryApply')
    wait_for_fix = child_map.get('waitForFix')
    retry_loop = child_map.get('retryLoop')

    if not try_apply:
        return None  # Not a retry group

    # Walk recursive retryLoop chain to find final attempt
    attempt = 1
    final_try = try_apply
    current_retry = retry_loop
    while current_retry and current_retry.get('phase') != 'Skipped':
        # retryLoop is another *WithRetry — recurse into its children
        retry_children = {c.get('display_name', '').split('(')[0]: c
                         for c in current_retry.get('children', [])}
        next_try = retry_children.get('tryApply')
        if next_try:
            attempt += 1
            final_try = next_try
            current_retry = retry_children.get('retryLoop')
        else:
            break

    # Build collapsed node
    group_name = get_node_input_parameter(node, 'retryGroupName') or 'Apply'
    collapsed = final_try.copy()
    collapsed['display_name'] = group_name
    collapsed['children'] = []

    # If retried, add attempt count to display
    if attempt > 1:
        collapsed['display_name'] = f"{group_name} (attempt {attempt})"

    # If tryApply failed and waitForFix is running, show waiting state
    if final_try.get('phase') == 'Failed' and wait_for_fix and wait_for_fix.get('phase') == 'Running':
        collapsed['phase'] = 'Running'
        collapsed['type'] = 'Suspend'  # So it renders with ⟳ symbol

    return collapsed
```

**Integration into `filter_recursive`**: Before the existing checks, test if the node
matches the retry pattern and collapse it:

```python
def filter_recursive(nodes):
    filtered = []
    for node in nodes:
        collapsed = _collapse_retry_group(node) if _is_retry_group(node) else None
        if collapsed:
            filtered.append(collapsed)
        elif node.get('type') == 'Retry' and _is_leaf_only_retry(node):
            filtered.append(_collapse_retry(node))
        elif should_keep_by_type(node) or has_group_name(node):
            ...
```

### Test fixtures needed

1. `retry_success_first_try.json` — tryApply Succeeded, waitForFix/retryLoop Skipped
   → collapses to single succeeded node
2. `retry_waiting_for_fix.json` — tryApply Failed, waitForFix Running
   → collapses to single running/suspend node
3. `retry_success_after_retries.json` — recursive chain, final tryApply Succeeded
   → collapses with "(attempt N)"
4. `retry_failed_after_retries.json` — recursive chain, final tryApply Failed, waitForFix Running
   → collapses with "(attempt N)" and waiting state

### Execution order

1. Add `retryGroupName` to the three `*WithRetry` templates in `setupKafka.ts`
2. Pass `retryGroupName` from callers (`deployKafkaClusterWithRetry`, `setupProxy`)
3. Add `_is_retry_group` and `_collapse_retry_group` to `tree_utils.py`
4. Integrate into `filter_recursive`
5. Add test fixtures
6. Update snapshots, deploy, submit full workflow, verify output

## Implementation Plan

### Phase 1: Workflow-side metadata (no CLI changes)

1. Add `groupName` to the five top-level phase template calls in `full-migration`.
2. Add `displayCategory=retry` to `retryLoop` and `waitForFix` steps.
3. Add `displayCategory=internal` to `idGenerator`, `getConsoleConfig`,
   `setupWaitForCompletion`, and patch steps.
4. Propagate `*K8sLabel` parameters to intermediate Steps nodes that lack them.

### Phase 2: Filter improvements in `tree_utils.py`

1. **Top-level phase preservation** — `at_root_level` flag in `filter_tree_nodes`.
2. **`displayCategory` filtering** — skip nodes with `displayCategory=retry`; skip
   `displayCategory=internal` unless `--verbose`.
3. **Skipped-subtree pruning** — collapse fully-skipped subtrees.
4. **Single-child coalescing** — merge wrapper Steps with their lone Pod child.

### Phase 3: Label-based filtering in `status` command

1. Add `--selector` option to `status_command` (reuse `output` command's syntax).
2. Implement tree-walk that marks matching nodes + ancestors + descendants.
3. Prune unmatched branches.
4. Wire up tab-completion from `autocomplete_k8s_labels.py`.

---

## Testing Strategy

### Current test infrastructure

Tests live in `tests/test_tree_filtering.py` and `tests/test_argo_conversion.py`. They use
JSON fixture files under `tests/workflows/testData/`:

```
tree_filtering/
  inputs/   — nested tree JSON (output of build_nested_workflow_tree)
  outputs/  — expected filtered tree JSON
argo_conversion/
  inputs/   — raw Argo workflow JSON
  outputs/  — expected nested tree JSON
```

Tests are snapshot-based: load input, run function, compare to expected output. New expected
outputs are auto-generated on first run.

### What needs to be added

| Test | Fixture needed | What it validates |
|------|---------------|-------------------|
| Top-level phase preservation | Tree with Steps nodes at root level, no `groupName` | Phases kept, children nested |
| `groupName` on phases | Tree with `groupName` on phase nodes | Same result via existing path |
| `displayCategory=retry` filtering | Tree with retry nodes carrying the parameter | Retry nodes removed |
| `displayCategory=internal` filtering | Tree with internal nodes | Hidden by default, shown with verbose flag |
| Skipped-subtree pruning | Tree with fully-skipped branch | Branch collapsed to single node |
| Single-child coalescing | Steps node wrapping one Pod | Merged into single Pod |
| Label-based filtering | Tree with `*K8sLabel` params | Only matching subtree shown |
| Mixed: phase + noise + skipped | Realistic tree combining all cases | End-to-end validation |

### Assessment of current test story

The fixture-based approach is solid and extensible. Each new behavior just needs a new
input/output JSON pair. The main gap is:

1. **No integration test against a real workflow** — The existing
   `test_workflow_integration.py` tests submit a hello-world workflow but don't validate
   tree display for a realistic migration workflow. We should add a fixture captured from
   a real `full-migration` workflow's Argo JSON and validate the full pipeline
   (build → filter → expected tree).

2. **No parameterized tests for filter flags** — When we add `--verbose` / `--selector`,
   we need tests that run the same input through different filter configurations and
   validate different outputs.

3. **Workflow template tests** — We should validate that workflow templates include the
   expected `groupName` and `displayCategory` parameters. This could be a simple test
   that loads the template YAML and checks for required parameters on key steps.

**Recommendation**: The current fixture approach is sufficient. Extend it with:
- A "realistic" fixture captured from an actual workflow run.
- Parameterized filter tests for verbose/selector modes.
- A template-level test asserting conventions are followed.

---

## Open Questions

1. Should `displayCategory=internal` nodes be hidden by default or shown dimmed?
2. For label-based filtering, should we show the full path to matched nodes or just the
   matched subtree?
3. Should coalescing happen before or after `displayCategory` filtering?
4. Do we need a `--show-skipped` flag, or is skipped-subtree pruning always desirable?
5. For the `manage` TUI, should these same filter rules apply, or does the interactive
   view need different defaults?
