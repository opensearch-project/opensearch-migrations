# Approval Gates

Approval gates are named checkpoints in the migration pipeline that pause workflow
execution and wait for explicit human confirmation before proceeding.  They serve two
distinct purposes:

- **Step gates** — intentional user-controlled breakpoints at key migration milestones
  (proxy setup, backfill start, metadata evaluation).  The operator reviews the current
  state and decides whether to continue.
- **Runtime gates** — automatic pauses triggered when a change to a live migration
  resource is detected or rejected.  Kubernetes ValidatingAdmissionPolicies (VAPs) deny
  the update, the workflow holds, and the operator either approves the change or
  performs a manual recovery.

---

## ApprovalGate CRD

```
apiVersion: migrations.opensearch.org/v1alpha1
kind: ApprovalGate
```

| Field | Values | Meaning |
|-------|--------|---------|
| `status.phase` | `Created` | Gate exists but workflow has not yet reached it |
| | `Pending` | Workflow is actively blocked — waiting for approval |
| | `Approved` | User approved — workflow unblocked |
| | `Error` | Gate entered an error state |

`spec` is always empty; only `status.phase` carries state.

---

## Gate Categories

### Step gates

Named after the pipeline step they guard.  Determined by `_gate_category_from_name()`:
any gate whose name does **not** end in `.vapretry`.

| Gate name | Trigger |
|-----------|---------|
| `captureproxysetup.{proxyName}` | Before proxy deployment begins (config changed) |
| `evaluatemetadata{approvalNameSuffix}` | Before metadata evaluation runs |
| `documentbackfill.{crdName}` | Before document backfill starts |

### Runtime gates (`.vapretry`)

Created when a Kubernetes ValidatingAdmissionPolicy (VAP) denies an UPDATE to a
migration resource because a "gated" field was changed.  All runtime gate names end in
`.vapretry`.  Within that family, subcategory is derived from the VAP denial message:

| Subcategory | Condition | Description |
|-------------|-----------|-------------|
| `change` | denial message does **not** contain "Impossible" | Field change is allowed but requires approval.  Approving lets the workflow annotate the resource and retry the update. |
| `retry` | denial message contains "Impossible" | Field change cannot be applied to the live resource (e.g. storage size decrease).  Operator must manually reset the resource, then approve to retry. |

VAP-gated fields per resource type:

| Resource | Gated fields |
|----------|-------------|
| `KafkaCluster` | `version`, `nodePool.replicas`, `nodePool.storage.size` |
| `CapturedTraffic` | `partitions`, `replicas`, `topicConfig` |
| `CaptureProxy` | `setHeader`, `maxTrafficBufferSize`, `noCapture`, `tls`, `enableMSKAuth`, suppress* fields |
| `SnapshotMigration` | `documentBackfillInitialLeaseDuration`, `documentBackfillMaxConnections`, `documentBackfillMaxShardSizeBytes` |
| `TrafficReplay` | `removeAuthHeader`, transformer/tuple config fields |

---

## Gate Labels

Every gate carries labels for filtering and cleanup:

| Label | Purpose |
|-------|---------|
| `migrations.opensearch.org/workflow` | Workflow name — used for cleanup on exit |
| `migrations.opensearch.org/resource-kind` | e.g. `CaptureProxy`, `KafkaCluster` |
| `migrations.opensearch.org/resource-name` | Name of the associated migration resource |
| `migrations.opensearch.org/source` | Source cluster label |
| `migrations.opensearch.org/target` | Target cluster label |
| `migrations.opensearch.org/snapshot` | Snapshot label |
| `migrations.opensearch.org/migration` | Migration label |

---

## Normal Flow — Gate Creation and Processing

### Phase 1 — Pre-creation (before the workflow starts)

All step gates and all VAP retry gates are created upfront by `configProcessor/index.js`
(`MigrationInitializer.generateCustomMigrationResources()`).  They are applied to the
cluster as part of the migration resources install, before Argo starts the workflow.

```
User provides migration config YAML
            │
            ▼
 configProcessor/index.js
 MigrationInitializer.generateCustomMigrationResources()
            │
            ├─ For each CaptureProxy  ──► captureproxysetup.{proxyName}
            ├─ For each KafkaCluster  ──► kafkacluster.{name}.vapretry
            ├─ For each CapturedTraffic──► capturedtraffic.{name}.vapretry
            ├─ For each SnapshotMigration:
            │    ├─ evaluatemetadata{suffix}
            │    └─ documentbackfill.{crdName}
            └─ (other resource-type gates)
            │
            │  All created via makeApprovalGateResource():
            │    status.phase = Created
            │
            ▼
 install-migrations-resources Job
 (Helm post-install hook, weight=5)
 apply_migration_resources() in the migration-console container
            │
            ▼
 kubectl apply -f migrationCrds.yaml          ← CRD schemas
 kubectl apply -f validatingAdmissionPolicies.yaml ← VAP rules
 kubectl apply workflow templates
 kubectl apply [all generated ApprovalGate resources]
            │
            ▼
 Cluster state: all ApprovalGate CRDs exist, status.phase = Created
```

---

### Phase 2a — Step gate flow

Step gates (proxy setup, evaluate metadata, document backfill) follow this pattern.
The workflow reaches the gate, polls until Approved, and then proceeds.

```
 Argo Workflow
      │
      │  (upstream steps: kafka setup, CaptureProxy upsert, reconcile, etc.)
      │
      ▼
 ┌─────────────────────────────────────────────────────────┐
 │  waitforuserapproval                                     │
 │  resourceName: captureproxysetup.my-proxy               │  ◄─ step in
 │                                                          │     setup-capture.yaml
 │  action: get                                            │
 │  successCondition: status.phase == Approved             │
 │  retryStrategy: limit=20, backoff 15 s → 180 s (cap)   │
 └───────────────────┬─────────────────────────────────────┘
                     │
          ┌──────────┘
          │  GET ApprovalGate captureproxysetup.my-proxy
          │  status.phase = Created  ──► condition not met ──► retry
          │
          │  ┌──────────────────────────────────────────────┐
          │  │  TUI (tree_state_manager)                    │
          │  │  1. walks Argo workflow node tree            │
          │  │  2. finds node with template=waitforuserapproval │
          │  │  3. extracts resourceName parameter          │
          │  │  4. GET ApprovalGate from K8s               │
          │  │  5. overlays status.phase on the node        │
          │  │  → displays approval prompt to operator      │
          │  └──────────────────────────────────────────────┘
          │
          │  Operator: workflow approve step captureproxysetup.my-proxy
          │  (or approves via TUI)
          │
          │  approve_gate():
          │    PATCH approvalgates/captureproxysetup.my-proxy
          │      status.phase = Approved
          │
          │  GET ApprovalGate  ──► status.phase == Approved ✓
          │
          ▼
 Step succeeds → workflow continues to next step
 (patchCaptureProxyPending → setupproxy → ...)
```

---

### Phase 2b — Runtime (VAP) gate flow

When a field marked `changeRestriction("gated")` is changed on a live resource, the
VAP denies the update.  The workflow catches the failure and blocks on the `.vapretry`
gate until the operator reviews and approves (or resets the resource for `retry`-category
gates).

```
 Argo Workflow: reconcileXXXresource
      │
      ▼
 upsertXXXresource ── APPLY KafkaCluster (with changed gated field)
      │
      ├── VAP allows ──────────────────────────────────► apply succeeds
      │   (no gated field change)                        workflow continues
      │
      └── VAP DENIES
          "Gated changes detected on KafkaCluster fields:
           [version]. Approve the corresponding ApprovalGate to proceed."
          │
          │  (continueOn: failed: true)
          ▼
 patchkafkaclusterpending       ← when: tryApply.status == Succeeded
 (marks KafkaCluster.status.phase = Pending if checksum changed)
          │
          ▼
 patchapprovalgatephase         ← marks .vapretry gate Pending
   resourceName: kafkacluster.my-cluster.vapretry
   status.phase = Pending
          │
          ▼
 ┌─────────────────────────────────────────────────────────┐
 │  waitforuserapproval                                     │
 │  resourceName: kafkacluster.my-cluster.vapretry         │
 │  polls until status.phase == Approved                   │
 └───────────────────┬─────────────────────────────────────┘
                     │
          ┌──────────┘
          │  TUI shows gate as 'change' or 'retry':
          │  • change: "CHANGED: version"
          │  • retry:  "Requires reset. Incompatible fields: storage.size"
          │
          │  Operator: workflow approve change kafkacluster.my-cluster
          │  (subcategory determined by denial message — 'Impossible' → retry)
          │
          │  PATCH kafkacluster.my-cluster.vapretry
          │    status.phase = Approved
          │
          ▼
 patchapprovalannotation
   annotates KafkaCluster with migrations.opensearch.org/approved-during-run
          │
          ▼
 patchapprovalgatephase → phase = Pending   ← reset gate for next cycle
          │
          ▼
 retryLoop: upsertXXXresource again
 (now with approval annotation, VAP allows)
          │
          ▼
 apply succeeds → workflow continues
```

---

### Phase 3 — Cleanup

The workflow always runs `cleanupapprovalgates` as an `onExit` handler.

```
 Workflow exits (success, failure, or cancellation)
      │
      ▼
 onExit: cleanupapprovalgates
      │
      ▼
 cleanupApprovalGates.sh
 kubectl delete approvalgates \
   -l migrations.opensearch.org/workflow=${WORKFLOW_NAME} \
   --ignore-not-found
      │
      ▼
 All gates for this workflow are removed from the cluster
```

---

## TUI Discovery Mechanism

The TUI does **not** rely solely on the Argo workflow status.  It uses a two-layer
approach so the displayed phase is always authoritative:

```
 tree_state_manager (background refresh loop)
      │
      ├─ 1. fetch Argo Workflow from K8s
      │       └─ extract .status.nodes (or decompress .status.compressedNodes)
      │
      ├─ 2. walk nodes, find any whose templateName == "waitforuserapproval"
      │       └─ extract inputs.parameters.resourceName  → set of gate names
      │
      ├─ 3. for each gate name:
      │       GET ApprovalGate from K8s
      │       read status.phase
      │
      └─ 4. overlay_approval_gate_status():
              if phase == Approved  → set node phase = Succeeded immediately
              otherwise             → show gate as blocked with current phase
```

The overlay means the TUI reflects a gate approval instantly — it does not wait for the
Argo controller to notice the `successCondition` and reconcile the node's own status.

### Gate status vs. display status

The `approve.py` CLI layer adds two higher-level concepts on top of the raw CRD phase:

| CLI `status` | Meaning |
|---|---|
| `waiting` | Argo workflow node is currently `Running` on this gate — operator is actively blocked |
| `pending` | Gate CRD exists in the cluster but the workflow has not yet reached this step |

`waiting` gates are discovered by scanning the live workflow nodes.  `pending` gates are
discovered by listing all `ApprovalGate` CRDs in the namespace and checking which ones
are not currently active in the workflow.

---

## CLI Commands

```bash
# List all step gates (user-defined checkpoints) for the active workflow
workflow approve step --list

# Approve a named step gate
workflow approve step captureproxysetup.my-proxy
workflow approve step documentbackfill.snap1-migration-0

# Approve all waiting step gates at once
workflow approve step --all

# List runtime gates (VAP field-change approvals)
workflow approve change --list

# Approve a change gate (allows the gated field change to proceed)
workflow approve change kafkacluster.my-cluster

# List retry gates (impossible changes requiring manual resource reset)
workflow approve retry --list

# Approve after performing the required resource reset
workflow approve retry kafkacluster.my-cluster
```

Note: the CLI accepts gate names with or without the `.vapretry` suffix — `_resolve_gate_name()` matches either form.

---

## Testing Approval Gate Appearance

There is no built-in mock or skip mechanism for upstream pipeline steps.  The TUI
discovers gates from **running Argo Workflow nodes** (not just from CRDs), so testing
requires an actual workflow.

### Why a standalone test workflow is needed

The TUI's gate discovery (`tree_utils.py`) works in two passes:

1. Walk the Argo Workflow node tree for nodes whose `templateName` is `waitforuserapproval`.
2. Query the matching `ApprovalGate` CRDs from Kubernetes and overlay their status.

If you only create `ApprovalGate` CRDs with `kubectl apply` — without a running workflow
that has `waitforuserapproval` nodes — the TUI has nothing to display.

### `devApprovalGateTest.yaml`

`migrationConsole/lib/integ_test/testWorkflows/devApprovalGateTest.yaml` provides a
self-contained workflow that reaches both step approval gates in seconds.

**What it does:**

```
Workflow starts
      │
      ├─ create-proxy-gate  (parallel)  ┐
      ├─ create-backfill-gate           ┘  action: apply + status patch → Created
      │
      ├─ mock-proxy-setup   ← templateRef: document-bulk-load:donothing  (instant)
      │
      ├─ approve-proxy-setup
      │    templateRef: resource-management:waitforuserapproval
      │    resourceName: captureproxysetup.dev-test-proxy
      │    [TUI shows proxy setup approval gate]
      │
      ├─ mock-snapshot-and-metadata  ← donothing (instant)
      │
      └─ approve-backfill
           templateRef: resource-management:waitforuserapproval
           resourceName: documentbackfill.dev-test-backfill
           [TUI shows backfill approval gate]
```

**Design decisions:**

- Uses `templateRef: document-bulk-load:donothing` for all upstream steps — this template
  already exists in the workflow set and succeeds immediately with empty steps, requiring
  no container image.
- Uses `templateRef: resource-management:waitforuserapproval` for the gate steps — this
  is the **real** template, so the TUI recognises the node by template name exactly as it
  would in a production run.
- Creates `ApprovalGate` resources at the start of the workflow via Argo resource steps
  (`action: apply` + separate `action: patch --subresource=status`), mirroring what
  `configProcessor` does before a real run.  The two-step create-then-patch is necessary
  because Kubernetes strips the `status` field on a standard resource write; the status
  subresource must be written separately.
- Uses `argo-workflow-executor` service account — the same SA real workflows use, which
  already has `ApprovalGate` create/patch/get permissions.

**Usage:**

The `argo` CLI is not installed in the migration console pod.  `kubectl create -f`
submits a `Workflow` resource identically to `argo submit` and is available everywhere.

The workflow uses the fixed name `dev-approval-gate-test` so that `wf manage` can be
pointed at it with a predictable `--workflow-name` flag.  `wf manage` defaults to
looking for a workflow named `migration-workflow`; without the flag it will show
"Waiting for wf to be created" because no workflow with that default name exists.

**Submit — from the host:**

```bash
kubectl create -f \
  migrationConsole/lib/integ_test/testWorkflows/devApprovalGateTest.yaml
```

**Submit — from inside the migration console pod** (`kubectl exec -n ma -it migration-console-0 -- bash`):

The workflow YAML is not bundled in the pod image, so it must be made available first.
Two options:

```bash
# Option A — copy the file into the pod from the host, then submit from inside
kubectl cp \
  migrationConsole/lib/integ_test/testWorkflows/devApprovalGateTest.yaml \
  ma/migration-console-0:/tmp/devApprovalGateTest.yaml
# then inside the pod:
kubectl create -f /tmp/devApprovalGateTest.yaml

# Option B — pipe the file from the host directly into the pod's kubectl stdin
#            (no intermediate file needed)
kubectl exec -n ma migration-console-0 -- \
  kubectl create -f - \
  < migrationConsole/lib/integ_test/testWorkflows/devApprovalGateTest.yaml
```

**Open the TUI** (from inside the migration console pod):

```bash
workflow manage --workflow-name dev-approval-gate-test
```

**Approve the gates** (from inside the migration console pod, or while the TUI is open):

```bash
# The workflow reaches approve-proxy-setup within a few seconds
workflow approve step captureproxysetup.dev-test-proxy

# The workflow then reaches approve-backfill
workflow approve step documentbackfill.dev-test-backfill
```

**Resubmitting** — because the workflow uses a fixed name, delete it before resubmitting:

```bash
kubectl delete workflow -n ma dev-approval-gate-test
kubectl create -f \
  migrationConsole/lib/integ_test/testWorkflows/devApprovalGateTest.yaml
```

**Clean up:**

```bash
kubectl delete workflow -n ma dev-approval-gate-test
kubectl delete approvalgates -n ma \
  -l migrations.opensearch.org/dev-test=approval-gates
```
