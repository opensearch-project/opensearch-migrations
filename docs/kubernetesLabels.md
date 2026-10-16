# Kubernetes labels and selectors on migration resources

Read this before adding a workload, a workflow step that creates pods, or a tool that finds
resources by label.

## The rule in one paragraph

Every pod that Migration Assistant creates for a migration CR gets the same **shared labels**: what
task it belongs to, which piece of that task it is, which CR owns it, which migration objects it
relates to, and which workflow created it. Selectors (Deployment, StatefulSet, Service, PDB, zone
spread) use exactly two of those labels, `component` and `migration-resource-uid`. Never invent a
label just so a selector has something to match.

## Kinds of pods

- **Workload pods** are long-running and managed by a Deployment or StatefulSet: the capture proxy,
  the traffic replayer, the RFS workers and the RFS coordinator.
- **Step pods** are pods that Argo starts to run one workflow step and that then exit: metadata
  migration, snapshot creation and the S3 traffic loader.
- **Monitor pods** are started by the monitor CronJobs (`resources/scripts/apply*MonitorCronJob.sh`)
  that track snapshot and RFS progress.
- **Orchestration pods** are Argo pods that only apply a manifest, wait, check status or patch a CR,
  plus workflow-level pods such as `initializeRunMetadata` and `cleanupApprovalGates` that belong
  to no CR. They need only `workflows.argoproj.io/workflow` (Argo adds it). They may carry
  relationship labels to make log filters useful, and no selector targets them.

The first three kinds do work for one migration CR. In this doc they are **resource-backed pods**.

## Labels on pods

All keys are under `migrations.opensearch.org/` except the Argo one.

| Label | Meaning | Example | Required on |
|---|---|---|---|
| `task` | What the work is | `trafficReplayer`, `reindexFromSnapshot` | every resource-backed pod |
| `component` | Which piece of the task. Unique within the owning CR | `traffic-replayer`, `rfs-worker` | every resource-backed pod |
| `migration-resource-uid` | UID of the owning CR (`CaptureProxy`, `TrafficReplay`, `SnapshotMigration`, …) | UID | every resource-backed pod |
| `source`, `target`, `snapshot`, `from-snapshot-migration` | The migration objects this pod works on (labels from the user's config) | `source1` | every pod where they apply |
| `workflows.argoproj.io/workflow` | Workflow that created the pod | workflow name | every pod (Argo adds it to step pods; templates and CronJob scripts must add it themselves) |

Keep the label sets of all pods in one task the same, so that a filter like
`workflow log filter --from-snapshot-migration <m>` returns the whole task.

`component` values are constants in `commonUtils/resourceLabels.ts` (`COMPONENTS`):

| Component | Kind | Owning CR |
|---|---|---|
| `capture-proxy` | workload | `CaptureProxy` |
| `traffic-replayer` | workload | `TrafficReplay` |
| `rfs-worker`, `rfs-coordinator` | workload | `SnapshotMigration` |
| `metadata-migration` | step | `SnapshotMigration` |
| `rfs-monitor` | monitor | `SnapshotMigration` |
| `snapshot-create` | step | `DataSnapshot` |
| `snapshot-monitor` | monitor | `DataSnapshot` |
| `s3-traffic-loader` | step | `CapturedTraffic` |

## Selectors

Every selector is `{component, migration-resource-uid}`, built with the helpers in
`commonUtils/resourceLabels.ts`:

- **Deployment / StatefulSet** `spec.selector.matchLabels`, **Service** `spec.selector` and
  **PDB** `spec.selector.matchLabels`: `workloadSelectorLabels(COMPONENTS.X, ownerUid)`.
- **Zone spread**: `workloadZoneSpreadConstraints(..., WORKLOAD_SELECTOR_LABEL_KEYS)`. These are
  `matchLabelKeys`, so the values come from the pod itself.

Why both labels:

- **UID alone is not enough.** One CR can own several kinds of pods. The `SnapshotMigration` UID is
  on RFS workers, the RFS coordinator, metadata-migration step pods and RFS monitor pods. A UID-only
  PDB or zone spread would count all of them.
- **`component` alone is not enough.** Two fleets of the same kind can run in one namespace (one
  replayer per target, for example), and their selectors would overlap.
- **Get it right the first time.** Workloads are written with `kubectl apply`, and Kubernetes
  rejects a change to a Deployment's or StatefulSet's selector ("field is immutable"). A workload
  created with an older selector has to be deleted, for example with `workflow reset`, before the
  next run can apply the new one.

Within one component, every object (Deployment, Service, PDB, zone spread) must select the same set
of pods. The replayer once had a per-fleet PDB and a namespace-wide Deployment selector, and two
fleets claimed each other's pods. `tests/workloadSelectors.test.ts` checks this for every workload.

## Checklist: adding a workload (Deployment or StatefulSet)

1. Add a `component` value to `COMPONENTS` in `commonUtils/resourceLabels.ts`: lowercase and
   hyphenated, and unique within its owning CR.
2. Put all the shared labels on the pod template. Put them on the Deployment/StatefulSet metadata
   too, so tools can find the workload itself by label.
3. Build every selector with `workloadSelectorLabels` and every zone spread with
   `WORKLOAD_SELECTOR_LABEL_KEYS`. Don't write selectors out by hand.
4. Set `ownerReferences` to the same CR whose UID is in `migration-resource-uid`.
5. Add the workload to `tests/workloadSelectors.test.ts`, and update the snapshot tests
   (`tests/__snapshots__/`).

## Checklist: adding a step pod or a monitor CronJob

1. **Step pods:** add the shared labels with `.addPodMetadata(...)`, including a `component`. Argo
   adds `workflows.argoproj.io/workflow`. Use the owning CR's UID, so the pods match
   `workflow log resource <name>`.
2. **CronJobs rendered as YAML** (such as the RFS and snapshot monitors in `resources/scripts/`)
   don't go through `.addPodMetadata`. Pass label keys and values in as environment variables, and
   put the shared labels on all three levels: the CronJob metadata, `spec.jobTemplate.metadata` and
   `spec.jobTemplate.spec.template.metadata`. Only the last one reaches the pods.
3. Add the new pods to `tests/resourceLogOwnership.test.ts`.
4. No selector change is needed. The selectors of other components still don't match these pods,
   because their `component` differs.

## Checklist: a tool, script or doc that finds resources

- Find pods by `component` (type-level: "all replayers") or `component` + UID (one instance). Don't
  use `app=...` or other ad hoc labels.
- To find a specific Deployment's pods, read its `spec.selector` instead of hardcoding the selector
  (as `models/kubectl_runner.py` does).
- For log filters, prefer the relationship labels (`--source`, `--from-snapshot-migration`) and
  `--component` over pod names. `--label 'component!=rfs-coordinator'` excludes a component.

## Labels that are not on pods

- **CRs, ApprovalGates and Workflows** carry their own labels, set in
  `config-processor/src/migrationInitializer.ts` (`resource-kind`, `resource-name`, `workflow-name`,
  `run-number`, …). Don't reuse them as pod selectors.
- `migrations.opensearch.org/workflow` (on ApprovalGates) is **not** the same as
  `workflows.argoproj.io/workflow` (on pods). The first names the user-level migration workflow that
  a gate belongs to, which stale-gate cleanup and `workflow approve` use. The second names the Argo
  Workflow object that created a pod.
- Pods that other operators manage keep those operators' labels and selectors. Strimzi's Kafka pods
  get only `migration-resource-uid`, through Strimzi's pod templates. Helm-installed charts, the k6
  load test (`app=k6-load-test`) and console-launched pods such as `kafka-dump` aren't migration CR
  pods at all.

## Constraints on label values

Kubernetes label values are at most 63 characters and must match
`[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?`. UIDs (36 characters) and `component` values are safe.
Values that come from config labels or workflow names can be longer, so validate them where they are
produced, not where they are used.
