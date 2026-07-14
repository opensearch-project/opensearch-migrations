# Migration Resource Contract Cleanup Plan

This document is the runbook for a focused follow-up to
[vapCrdChecksumHardeningPlan.md](vapCrdChecksumHardeningPlan.md). The hardening
plan promoted hidden checksum material into projected spec fields and unified the
projection table that drives CRD schemas, VAP rules, and dry-run modeling. Review
of the resulting PR surfaced a small set of concrete correctness/consistency gaps
that the hardening effort did not close. This plan fixes them.

It is intentionally scoped to changes we have already agreed on. A separate,
larger effort — generating the workflow apply manifests from the projection table
so the two hand-maintained lists can never drift — is described only as a
follow-on and is called out explicitly in [Implementation Order](#implementation-order).

## Scope

| # | Change | Type | Risk |
| --- | --- | --- | --- |
| 1 | Write `dependsOn` in the `DataSnapshot` and `SnapshotMigration` apply manifests (`tryApply`), and stop stamping it in the initializer. | Correctness (reset DAG) | Low |
| 2 | Give `reconcileDataSnapshotResource` the same VAP-retry `ApprovalGate` recovery loop the other resources have. | Correctness (recoverability) | Medium |
| 3 | Remove `solrCollections` from the user-facing `USER_CREATE_SNAPSHOT_OPTIONS`; re-add it in the internal `ARGO_CREATE_SNAPSHOT_OPTIONS`. | Schema hygiene / naming | Low |
| 4 | Align default-less string field emission between the apply manifests and resolved resources (the `""` divergence). | Consistency | Low |
| 5 | Make the create-path reported `snapshotName` output match the lowercased name that is actually created. | Correctness (latent) | Low |
| 6 | Cheap documentation wins: `.describe()` on the internal `mode` and `snapshotSourceType` enums. | Docs | Trivial |

Everything here is verified against the current code on branch `pr-3132`. File and
line references are anchors, not exact contracts — confirm during implementation.

---

## Hardening Commits Review — Outcome

A focused correctness/appropriateness review of the two hardening commits
(`663064734` "Harden DataSnapshot resource contract" and `d1d97290c` "Harden
migration resource contracts") found the hardening **correct and appropriate**.
The identity helpers (`clusterConnectionIdentity`, `authIdentity`, `repoIdentity`,
`identityHash` in `migrationConfigTransformer.ts:1440-1525`) are well-structured;
every field promoted to spec is written by the apply manifest and emitted by
resolved resources; and the checksum inputs map to visible spec fields or
dependency checksums with no hidden material. Two candidate findings were
investigated and dismissed:

- **`targetVersion` not projected (dismissed — not a defect).** `targetConnectionIdentity`
  is folded into SnapshotMigration/TrafficReplay checksums and includes `version`,
  while `targetVersion` is intentionally not a projected spec field. This looks like
  spec/checksum asymmetry, but `TARGET_CLUSTER_CONFIG` (`userSchemas.ts:1177`) has no
  `version` field — targets never carry a version — so `clusterConnectionIdentity`
  yields `version: ""` for every target. The value is a constant, never user-settable,
  so it is not hidden material. `sourceVersion` is projected everywhere sources are
  used, which is correct. No change needed.

- **`authMtlsCaCertHash` marked `impossible` (affirmed — keep as-is; security).** CA
  rotation on a long-running CaptureProxy/TrafficReplay changes the hash and, under
  `impossible`, requires delete/recreate rather than an in-place update. This is
  **intentional and correct**: `authMtlsCaCertHash` gates the trust anchor for what
  the proxy captures. Allowing the update — even `gated`/approved — would let the
  capture buffer hold records collected under two different trust identities
  (tainted records). There is no safe "approve and continue" for an auth-trust
  change; delete/recreate is the only correct recovery, which `impossible` enforces.
  **Decision: keep `impossible` on `authMtlsCaCertHash` for all resources.**

### Deferred follow-on (separate PR): buffer taint propagation

Ideally a change to `authMtlsCaCertHash` would also **taint the downstream buffer
resource** (the captured-traffic/Kafka buffer), so that a trust-identity change
forces a reset of the buffer itself — making it impossible to reuse a buffer that
mixes records from two trust identities. This is out of scope here; capture it as a
future PR. It extends the "one representation per material input" model with a
"material change taints dependent work products" rule for auth-trust fields.

---

## Background: the four representations

The hardening plan established that every material input to a migration resource
must be represented in exactly one of: same-resource spec, upstream dependency
checksum, same-resource spec hash, or a workflow-only trace annotation. Four code
surfaces must agree on the resulting contract:

| Surface | File |
| --- | --- |
| Projected fields (drives CRD schema + VAP CEL + dry-run) | `orchestrationSpecs/packages/schemas/src/migrationResourceProjections.ts` |
| Workflow apply manifests (`tryApply`, writes the live CR spec) | `orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/resourceManagement.ts` |
| Resolved resources (MigrationRun history + dry-run input) | `orchestrationSpecs/packages/config-processor/src/resolvedMigrationResources.ts` |
| Checksum computation | `orchestrationSpecs/packages/config-processor/src/migrationConfigTransformer.ts` |

The apply manifests are the one surface that does **not** read the projection
table; it is hand-transcribed. The changes below fix the specific divergences
found; the structural fix (generate the manifests from the table) is the separate
follow-on.

---

## Change 1 — `dependsOn` belongs in `tryApply`, not the initializer

### Problem

`spec.dependsOn` is a live dependency-graph edge consumed by the `workflow reset`
CLI. `resource_tree.py:111` reads `item['spec']['dependsOn']` from live CRs to
build a deletion DAG; per `WorkflowCrdDesign.md:124-128`, reset deletes in
dependency-safe order (a resource is deleted only after its dependents are gone),
and the `--tree` view renders "Depends on: …" from the same field.

Today:

- The projection table adds `dependsOn` to every non-approvalGate resource
  (`migrationResourceProjections.ts` `commonDependsOnFields`, ~lines 101-107).
- `resolvedMigrationResources.ts` emits it for `DataSnapshot` (line ~371, from
  `dependsOnProxySetups`) and `SnapshotMigration` (line ~389, from the resolved
  DataSnapshot name).
- The four long-running resources write it in their apply manifests
  (`KafkaCluster` ~197, `CapturedTraffic` ~260, `CaptureProxy` ~291,
  `TrafficReplay` via `makeTrafficReplayManifest` ~490).
- **`DataSnapshot` (`upsertDataSnapshotResource`) and `SnapshotMigration`
  (`makeSnapshotMigrationManifest`) do not write it at all.**

The initializer *does* stamp it, because `specFor` returns the full resolved
`parameters` (`migrationInitializer.ts:505`). But the initializer applies root CRs
with `kubectl create` and, on `AlreadyExists`, reuses the existing CR **without
updating its spec** (`migrationInitializer.ts:246-256`). So `dependsOn` is stamped
only on a CR's first creation and is then silently stripped on the workflow's first
`tryApply` (which re-applies a manifest that omits it).

Net effect: after the first reconcile, `reset` sees `DataSnapshot` and
`SnapshotMigration` as dependency-free and can delete them out of dependency-safe
order, or mis-render the tree.

### Why the fix is `tryApply`-only (not "both")

`dependsOn` is not a user-schema field and is not derived from a user option; its
value is synthesized by the config transformer per run (e.g. TrafficReplay's
`dependsOn` at `migrationConfigTransformer.ts:973` includes the current run's
snapshot-migration dependencies). It changes run-to-run.

Consider a replay that depends on multiple snapshots this run but depended on none
last run. The CR already exists, so the initializer's `create` no-ops and cannot
update the stale value — only `tryApply` (which uses `action: "apply"`) can. More
importantly, `dependsOn` should reflect **established** edges: advertising a
dependency before the workflow has actually wired it up is a reset-safety hazard,
because a user may `reset` mid-run and the reset DAG acts on whatever
`spec.dependsOn` is live. Therefore the workflow-owned `tryApply` — which runs
after the dependency is real — is the correct and only place to write it.

This matches the existing design guidance in `reconfiguringWorkflows.md:646`:
the initializer should carry only minimal bootstrap fields needed by waiters;
`tryApply` is the canonical writer of the full root spec.

### Work

1. In `resourceManagement.ts`, add `dependsOn` to the spec written by
   `upsertDataSnapshotResource` and `makeSnapshotMigrationManifest`.
   - `SnapshotMigration`'s dependency is its `DataSnapshot` (present only when
     `snapshotNameResolution` has a `dataSnapshotResourceName`); emit `[]` when
     there is none, mirroring `resolvedMigrationResources.ts:389`.
   - `DataSnapshot`'s dependencies are its `dependsOnProxySetups`; emit their
     names, mirroring `resolvedMigrationResources.ts:371`. This requires threading
     the dependency names into `upsertDataSnapshotResource` (it currently takes
     only `resourceName`, `snapshotItemConfig`, `sourceLabel`) — the names are
     available on `snapshotItemConfig.dependsOnProxySetups`.
2. Strip `dependsOn` from the initializer's bootstrap spec. Because `specFor`
   returns the whole resolved `parameters` object (`migrationInitializer.ts:505`),
   `dependsOn` currently rides along and is stamped on first creation. Remove it
   from the initializer-stamped spec so we never advertise an edge before the
   workflow has established it. This makes the reset-safety invariant true at all
   times: `spec.dependsOn` on a live CR is written only by `tryApply`, after the
   dependency is real. Implementation options: filter `dependsOn` out of the
   object `specFor` returns for these kinds, or have `specFor`/the initializer emit
   a bootstrap subset that excludes it. Whichever is chosen, `tryApply` remains the
   sole writer of `dependsOn` on the live CR.
3. Regenerate affected snapshots (resolved-resource and workflow snapshots) and
   verify no unexpected diffs beyond the added/removed `dependsOn`.

### Verification

- Unit: assert `upsertDataSnapshotResource` / `makeSnapshotMigrationManifest`
  render `spec.dependsOn` matching the resolved projection for a config with a
  proxy→snapshot→migration chain.
- Behavioral: build a config where a `TrafficReplay` (or `SnapshotMigration`)
  gains a dependency it did not have in a prior run; assert the re-applied CR spec
  reflects the new `dependsOn` (proves `tryApply` is authoritative, initializer is
  not relied upon).

---

## Change 2 — `DataSnapshot` needs the VAP-retry recovery loop

### Problem

Every root-CR reconcile except `DataSnapshot` implements the
`tryApply → waitForFix → patchApproval → resetGate → retryLoop` recovery loop
against an `ApprovalGate` (see `reconcileSnapshotMigrationResource`,
`resourceManagement.ts:1069-1117`). When a `tryApply` is rejected by a VAP
(`continueOn: {failed: true}` keeps the workflow alive), the workflow waits on the
`ApprovalGate`; the user fixes the underlying issue and approves; `retryLoop`
re-enters the reconcile and re-applies.

`reconcileDataSnapshotResource` (`resourceManagement.ts:1027-1060`) has **only**
`tryApply` + `markPending`. No `retryGateName`, no `waitForFix`, no `retryLoop`.
On a VAP rejection it swallows the failure and emits an empty checksum with no
wait and no retry.

Direct consequence: if a `DataSnapshot` apply is VAP-rejected, the running
workflow will not retry it — even if the user resets the snapshot. Recovery
requires resubmitting the whole workflow. `SnapshotMigration` in the same
situation parks in a user-visible `ApprovalGate` and retries after approval. This
is the sharp edge to remove.

Note: `DataSnapshot` has no `gated` fields today (all internal fields are
`impossible`, and user-schema fields are `impossible`/`safe`), so this loop would
not fire during normal reconfiguration. It exists purely as the recovery path for
a rejected apply — e.g. the terminal lock-on-complete rejecting a re-apply of a
pre-existing `Completed` CR after an in-place upgrade, or any future `gated`
DataSnapshot field. It is future-proofing plus dead-end removal.

### Work

1. Add to `reconcileDataSnapshotResource` the same steps
   `reconcileSnapshotMigrationResource` uses: a `retryGateName` required input, and
   the `waitForFix` (`waitForUserApproval`) → `patchApproval`
   (`patchApprovalAnnotation` with `resourceKind: "DataSnapshot"`) → `resetGate`
   (`patchApprovalGatePhase`) → `retryLoop` (`addStepToSelf`) chain, gated exactly
   as SnapshotMigration's is (`waitForFix` when `tryApply.status == Failed`, etc.).
2. Thread the `retryGateName` from `fullMigration.ts` where
   `reconcileDataSnapshotResource` is invoked (mirror how SnapshotMigration builds
   `snapshotmigration.<name>.vapretry`, e.g. `datasnapshot.<name>.vapretry`).
3. In `migrationInitializer.ts`, create the matching `DataSnapshot` `vapretry`
   `ApprovalGate` (the initializer already scaffolds these for the other five
   resources; add DataSnapshot symmetrically near the DataSnapshot item loop
   ~line 628).
4. Regenerate workflow snapshots and the initializer test fixtures.

### Verification

- Unit/snapshot: `reconcileDataSnapshotResource` renders the full retry loop; the
  initializer emits a DataSnapshot `vapretry` ApprovalGate.
- Behavioral: simulate a VAP rejection on a DataSnapshot `tryApply` and assert the
  workflow reaches `waitForFix` (parity with SnapshotMigration) rather than
  silently emitting an empty checksum.

---

## Change 3 — `solrCollections` leaves the user schema, stays internal

### Problem

`solrCollections` lives in `USER_CREATE_SNAPSHOT_PROCESS_OPTIONS` →
`USER_CREATE_SNAPSHOT_OPTIONS` (`userSchemas.ts:810`), which is used **only** by
`ELASTICSEARCH_GENERATE_SNAPSHOT` (`userSchemas.ts:1311`). So it is reachable only
by ES/OS users, for whom it is meaningless and silently ignored. Actual Solr users
configure `collectionAllowlist` in the separate `SOLR_*` schemas
(`SOLR_BACKUP_PROCESS_OPTIONS`, `userSchemas.ts:1346`) and never see
`solrCollections`. The config transformer folds Solr backups into the ES-shaped
snapshot map and maps `collectionAllowlist` → `solrCollections`
(`migrationConfigTransformer.ts:572`). `solrCollections` is therefore an internal
field name that leaked into the ES/OS user surface.

Going forward, "SNAPSHOT" in the user-facing schema refers only to ES/OS cluster
operations. The Solr↔ES/OS unification is intentional at the internal
(`ARGO_CREATE_SNAPSHOT_OPTIONS`) layer, which is glue between user experience and
implementation, so the field should live there, not in the user schema.

### Work

1. Remove `solrCollections` from `USER_CREATE_SNAPSHOT_PROCESS_OPTIONS`
   (`userSchemas.ts`).
2. Re-add it in the internal `ARGO_CREATE_SNAPSHOT_OPTIONS` `.extend({...})`
   (`argoSchemas.ts:175-179`), which currently inherits it from the user schema.
   Keep the name `solrCollections` — renaming it ripples through the transformer,
   the apply manifest, the projection, and snapshots for no functional gain.
   Update its `.describe()` to state it is the internal collection-allowlist
   populated by the Solr backup fold-in (glue-layer, not user-set).
3. **Critical coupling — do not let `solrCollections` silently drop out of the
   CRD/VAP.** The DataSnapshot `SCHEMA_PROJECTIONS` entry sources its fields from
   `USER_CREATE_SNAPSHOT_OPTIONS` (`migrationResourceProjections.ts:79-82`), so
   removing `solrCollections` from the user schema will also remove it from the
   DataSnapshot projection (and thus the generated CRD schema and VAP), even though
   the transformer still populates it. Fix by pointing the DataSnapshot projection
   source at `ARGO_CREATE_SNAPSHOT_OPTIONS` (where the field now lives), or by
   adding `solrCollections` as an explicit internal projected field for DataSnapshot
   in `INTERNAL_PROJECTED_FIELDS` (as `impossible`, matching its current
   restriction). Verify via the CRD/VAP snapshot that the field survives.
4. Verify ES/OS configs that previously set `solrCollections` now fail
   `validateNoExtraKeys` (intended), and add a migration note if any sample/test
   fixtures set it.

### Verification

- Unit: an ES/OS user config with `solrCollections` is rejected as an extra key;
  a Solr config with `collectionAllowlist` still resolves and the transformer
  still populates the internal `solrCollections`.
- Snapshot: DataSnapshot CRD/VAP still contains the `solrCollections` field
  (populated internally), confirming step 3 did not drop it.

---

## Change 4 — align default-less string emission (`""` divergence)

### Problem

The apply manifests write explicit `""` defaults for default-less string fields
via `expr.dig(..., "")` (e.g. `otelTraceCollectorEndpoint`, `transformerConfig`,
`transformerConfigFile` in `upsertDataSnapshotResource` ~660 and
`makeSnapshotMigrationManifest` ~426). The resolved projection spreads the raw
config (`...snapshotConfig` / prefixed config) and omits keys the user did not set.
So `resolvedMigrationResources` (which backs MigrationRun history and the dry-run
preview) can show a different spec than the live CR.

These particular fields are not projected, so there is no VAP/checksum consequence
today — this is a consistency and least-surprise fix. The output for a resolved
resource should match reality.

### Work

Make the two sides agree, defaulting toward what is actually applied: give the
resolved projection the same `""` defaults so it emits what `tryApply` writes.
(Alternatively, drop the `""` fallback in the apply manifest so unset fields stay
absent on the live CR too — but matching reality is the safer direction since the
CR is the source of truth.) Prefer aligning resolved → apply.

### Verification

Unit/snapshot: for a config that omits these fields, the resolved DataSnapshot /
SnapshotMigration parameters contain the same keys and values the apply manifest
writes.

---

## Change 5 — create-path `snapshotName` output should match reality

### Problem

In `createOrGetSnapshot.ts`, the snapshot that is actually created uses a
lowercased name (`expr.toLowerCase(...)`, ~line 113), but the template's reported
output `snapshotConfig.snapshotName` on the create path returns the non-lowercased
name (~lines 130-133). It is inert today because that output has no consumers (the
migration side reads the real name from the DataSnapshot CR status via
`readDataSnapshotName`), but it is a latent trap: if the output is ever wired into
a downstream step, an uppercase source label would report a name that differs from
the stored snapshot, causing a "snapshot not found" downstream.

### Work

Wrap the create-path reported name in the same `expr.toLowerCase(...)` used for the
created name (~line 113), so the output can never diverge from reality. If the
output is confirmed unused and undesired, delete it instead; prefer fixing it.

### Verification

Unit: for a source label containing uppercase, the reported create-path
`snapshotName` equals the lowercased created name.

---

## Change 6 — cheap documentation wins

Add `.describe()` to the two internal, generically-named enums so future
maintainers understand their Solr semantics:

- `ARGO_CREATE_SNAPSHOT_OPTIONS.mode` (`argoSchemas.ts:177`): note `import` is used
  only for Solr external-backup prepare; `create` is the default snapshot/backup
  path.
- `SnapshotMigration.snapshotSourceType` enum in
  `migrationResourceProjections.ts:190`: note that `externalPrepared` is the Solr
  external-backup-prepare case (a DataSnapshot CR exists but the resolved name is
  the external backup name), `dataSnapshot` is a workflow-generated snapshot, and
  `external` is an ES/OS externally-managed snapshot with no workflow preparation.

Neither is user-typed (`mode` is internal-only; `snapshotSourceType` is a derived
projected field), so this is documentation only — no schema shape change.

---

## Implementation Order

| Step | Work | Notes |
| --- | --- | --- |
| 1 | Change 6 (enum descriptions) | Trivial, no behavior change; land first to reduce noise in later diffs. |
| 2 | Change 3 (`solrCollections` relocation) | Do before Change 4; both touch snapshot option schemas, and this one changes the user-facing surface. Watch the projection-source coupling (step 3.3). |
| 3 | Change 1 (`dependsOn` in `tryApply`) | Correctness fix for reset; self-contained. |
| 4 | Change 2 (DataSnapshot recovery loop) | Larger workflow-template change; touches `resourceManagement.ts`, `fullMigration.ts`, and the initializer. |
| 5 | Change 4 (`""` alignment) and Change 5 (lowercase output) | Small consistency fixes; batch together. |
| 6 | Regenerate all snapshots; run the three package test suites (`schemas`, `config-processor`, `migration-workflow-templates`) green. | Mechanical fallout. |
| 7 | **Fold the resulting behavior into the existing docs** (see next section). | Keep docs current before merge. |
| 8 | **Kick off the separate "generate manifests from the projection table" design doc for Codex.** | This is where the structural follow-on begins — see [Follow-on](#follow-on-generate-apply-manifests-from-the-projection-table). Do this work as its own doc + PR, after the above ships. |

**When to start the Codex TS-modelling doc:** at **Step 8**, once Changes 1–6 have
landed and the docs are current. Doing the cleanup first means the follow-on
starts from a consistent baseline, and Changes 1, 4, and 5 become regression cases
the generated-manifest approach must preserve.

---

## Documentation Updates (Step 7)

These existing docs describe the model this plan changes. Update them so they
match the shipped behavior. Fold the relevant conclusions in; do not leave this
plan as the only source of truth.

### `reconfiguringWorkflows.md`

| Update | Reason |
| --- | --- |
| Correct the `DataSnapshot` section (~lines 290-303): it currently implies the generic ApprovalGate retry model applies uniformly. State that DataSnapshot now has the `tryApply → waitForFix → patchApproval → resetGate → retryLoop` recovery loop (Change 2), and that it exists for rejected applies even though DataSnapshot has no `gated` fields. | The doc describes a retry model the code did not implement for DataSnapshot until this plan. |
| Add a short subsection under the dependency-graph material (~lines 151-168) noting that `spec.dependsOn` is written by `tryApply` (not the initializer) and is consumed by `workflow reset`; explain the reset-safety reason it must reflect established edges only. | `dependsOn`'s role and ownership were undocumented. |
| Where it lists the ApprovalGate loop resources, ensure DataSnapshot is included. | Parity after Change 2. |

### `resolvingMigrationParametersFromConfigs.md`

| Update | Reason |
| --- | --- |
| Update the projection description (~line 72) that "`USER_CREATE_SNAPSHOT_OPTIONS` projects into `DataSnapshot.spec`" to reflect that `solrCollections` now comes from the internal ARGO schema, not the user schema (Change 3). | The projection source for that field moves. |
| In "Resolved Migration Resources" (~line 258) / "Dry-Run Policy Evaluation" (~line 288), note that resolved output now matches the applied CR for default-less string fields and for `dependsOn` on terminal resources (Changes 1, 4). | Resolved output ≠ applied spec was a real gap this plan closes. |
| Add `dependsOn` to the list of fields present on resolved terminal resources and clarify it is authoritative on the live CR only after `tryApply`. | Consistency with Change 1. |

### `WorkflowCrdDesign.md`

| Update | Reason |
| --- | --- |
| In "Dependency-aware deletion" (~lines 124-128), add that `DataSnapshot` and `SnapshotMigration` now populate `spec.dependsOn` via the workflow (previously absent), so their edges appear in the reset DAG. | The reset DAG was missing these edges before Change 1. |

### `vapCrdChecksumHardeningPlan.md`

| Update | Reason |
| --- | --- |
| Add a short "Follow-up: resource contract cleanup" note linking to this document, and check off the items this plan implements that the hardening plan listed as future work (e.g. "Write all projected DataSnapshot fields in `upsertDataSnapshotResource`"). | Keep the parent plan's status accurate. |
| Note that the "Projection/apply parity" test it proposed is superseded by the generate-from-table follow-on (below) rather than added as a standalone test, if that path is chosen. | Avoid implying a test that the follow-on makes unnecessary. |

### Cross-links

There is no `docs/` index file today. Ensure this document and
`vapCrdChecksumHardeningPlan.md` reference each other (done in the Background and
Follow-on sections here; add the reciprocal link in the hardening plan per the
table above) so the two are discoverable together.

---

## Follow-on: generate apply manifests from the projection table

This is a **separate** effort, started at Step 8, with its own design doc aimed at
a Codex hand-off (Codex has handled the strongly-typed `argo-workflow-builders`
expression code well).

The root cause behind Changes 1, 4, and 5 is that the apply manifests in
`resourceManagement.ts` are a fourth, hand-maintained transcription of the
per-resource contract, independent of the projection table. The follow-on makes the
manifests **consume** a single per-field descriptor
(`{specPath, sourcePath, schema, changeRestriction, default}`) so drift becomes
structurally impossible.

The design doc should cover:

- The descriptor shape and how it drives all four surfaces (CRD schema, VAP CEL,
  resolved resources, and a generic manifest builder that emits
  `expr.dig(sourceExpr, sourcePath, default)`).
- The hard part: bridging plain-data descriptors to the strongly-typed
  `expr.*` proxy-expression builders without losing type safety.
- Which fields stay special-cased: `dependsOn` remains a threaded workflow value
  (not projection-derived), and derived fields like `snapshotSourceType` stay
  computed — the doc must enumerate these escape hatches.
- Test strategy: if manifests are fully generated, the "projection/apply parity"
  test the hardening plan proposed is unnecessary (drift is impossible by
  construction); the checksum-visibility mutation sweep and the terminal-lock
  behavioral test are still needed and should be added regardless.
- Migration path: land the descriptor + generator behind the existing hand-written
  manifests, prove byte-identical output against current snapshots, then switch
  over resource-by-resource.

Changes 1, 4, and 5 from this plan should be preserved as regression cases the
generated output must reproduce.
