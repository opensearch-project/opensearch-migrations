# Plan: CRD Type Packages

## 1. Original Goals

1. **CRD pre-initialization** — Before the Argo workflow starts, create `CapturedTraffic`,
   `DataSnapshot`, and `SnapshotMigration` resources with `status.phase = "Initialized"` so the
   workflow's `waitFor` steps can find them and patch them to `"Ready"` when done.

2. **Pull Argo, Strimzi, and K8s CRD types into the repo without entangling runtime dependencies**
   — Type-safe TypeScript interfaces for CRDs, generated from a live cluster, committed as plain TS
   (no Zod), with a single `npm run rebuild` per package to refresh when versions change.

---

## 2. Current State

- **CRD pre-initialization**: ✅ Already done on this branch. `generateCRDResources()` in
  `migrationInitializer.ts` emits the three CRD manifests; the shell script applies them before
  creating the workflow.

- **K8s type generation**: `argo-workflow-builders` has `scripts/fetchK8sSchemas.sh` +
  `scripts/generateK8sTypes.ts` that fetch core K8s schemas from a live cluster and generate
  `src/kubernetesResourceTypes/kubernetesTypes.ts`. This logic needs to move to a shared utility
  and the generated output to dedicated packages.

- **`packages/k8s-types`**: Directory skeleton exists, completely empty.

- **Strimzi/Argo types**: Not yet present anywhere.

---

## 3. Package Structure

```
packages/
  crd-type-generator/     devDependency only — owns the shared generate script
  k8s-types/              pure TS interfaces, no deps — K8s core types
  argo-types/             pure TS interfaces, no deps — Argo CRD types
  strimzi-types/          pure TS interfaces, no deps — Strimzi CRD types
  argo-workflow-builders/ depends on k8s-types + argo-types (drops its own scripts/kubernetesResourceTypes)
  schemas/                depends on strimzi-types (wraps in Zod for userSchemas.ts)
  migration-workflow-templates/ depends on strimzi-types (types setupKafka.ts manifests)
```

Each type package has:
- `scripts/fetchSchemas.sh` — `kubectl get --raw /openapi/v3/apis/<group>/<version>` for its CRDs
- `src/index.ts` — generated, committed
- `package.json` with `"rebuild": "bash scripts/fetchSchemas.sh && tsx ../crd-type-generator/src/generateTypes.ts"`

---

## 4. Implementation Steps

### Step 1 — `crd-type-generator` package

Move `generateK8sTypes.ts` from `argo-workflow-builders/scripts/` into
`packages/crd-type-generator/src/generateTypes.ts`, making the input schema dir and output file
path CLI arguments. No dependencies beyond `json-schema-to-typescript` and `@types/json-schema`.

### Step 2 — `k8s-types` package

- `scripts/fetchSchemas.sh`: fetch `api/v1`, `apis/apps/v1`, `apis/batch/v1` (same as current
  `argo-workflow-builders` script)
- `src/index.ts`: generated output (move existing `kubernetesTypes.ts` content here as baseline)
- Wire `rebuild` script via `crd-type-generator`
- Update `argo-workflow-builders/package.json` to depend on `@opensearch-migrations/k8s-types`
  and remove its own `scripts/` and `src/kubernetesResourceTypes/`

### Step 3 — `argo-types` package

- `scripts/fetchSchemas.sh`: fetch `/openapi/v3/apis/argoproj.io/v1alpha1`
- Generates interfaces for `Workflow`, `WorkflowTemplate`, `CronWorkflow`
- `src/index.ts`: generated output
- Update `argo-workflow-builders/package.json` to depend on `@opensearch-migrations/argo-types`
- Type the return of `renderWorkflowTemplate` in `argoResourceRenderer.ts` against `WorkflowTemplate`

### Step 4 — `strimzi-types` package

- `scripts/fetchSchemas.sh`: fetch `/openapi/v3/apis/kafka.strimzi.io/v1beta2`
- Generates interfaces for `Kafka`, `KafkaNodePool`, `KafkaTopic`
- `src/index.ts`: generated output
- Update `schemas/package.json` and `migration-workflow-templates/package.json` to depend on
  `@opensearch-migrations/strimzi-types`
- Type the manifest-building functions in `setupKafka.ts`
- `schemas` wraps relevant types in Zod for new `userSchemas.ts` Strimzi config options

---

## 5. What Is NOT Being Tackled

- **Runtime CRD validation** — generated types are TypeScript-only; no Zod schemas inside the
  type packages themselves.
- **Offline schema refresh** — `rebuild` still requires a live cluster with the relevant operators
  installed. Making it fully offline is a future improvement.
- **`argoTsIntegTests` branch** — updating that branch's test infra to use `argo-types` is
  deferred until after these packages exist.
- **New `userSchemas.ts` Strimzi config** — Step 4 lays the groundwork; the actual new schema
  fields are written separately once `strimzi-types` exists.
