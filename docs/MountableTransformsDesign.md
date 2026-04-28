# Mountable Transforms — Design Document

## Overview

Users define transform sources once in a top-level `transformsSources` map (aliased by name), then reference them by key in per-tool options. The workflow mounts `/transforms/` into every transform-eligible pod (MetadataMigration, RFS, Replayer). Each program has a default entrypoint filename it looks for, overridable by the user.

Two sources are supported:

| Source | Volume type | Version identity |
|---|---|---|
| `image` | K8s native `image` volume (1.35+ enabled by default, GA in 1.36) | Image digest in the reference |
| `configMap` | K8s ConfigMap volume | ConfigMap name (user-managed) |

Images are the preferred path. ConfigMap support is a convenience for users who already manage ConfigMaps in their cluster.

---

## Part 1: Schema

### 1.1 Transform source definition — `userSchemas.ts`

New top-level aliased map, following the same pattern as `kafkaClusterConfiguration`, `sourceClusters`, and `targetClusters`:

```typescript
const TRANSFORMS_IMAGE_SOURCE = z.object({
    image: z.string()
        .describe("OCI image reference (preferably with digest) containing transform files at /transforms/. " +
            "Build with deployment/k8s/package-transforms.sh.")
});

const TRANSFORMS_CONFIGMAP_SOURCE = z.object({
    configMap: z.string()
        .describe("Name of a pre-existing Kubernetes ConfigMap. Each key becomes a file in /transforms/.")
});

const TRANSFORMS_SOURCE = z.union([
    TRANSFORMS_IMAGE_SOURCE,
    TRANSFORMS_CONFIGMAP_SOURCE
]).describe("Source for user-defined transform files. Exactly one of 'image' or 'configMap'.");

const TRANSFORMS_SOURCES_MAP = z.record(
    z.string().regex(K8S_NAMING_PATTERN),
    TRANSFORMS_SOURCE
).describe("Map of transform source names to their definitions.");
```

Added to `OVERALL_MIGRATION_CONFIG`:

```typescript
transformsSources: TRANSFORMS_SOURCES_MAP.default({}).optional()
    .describe("Named transform sources. Define once, reference by key in tool options."),
```

### 1.2 Per-tool fields

Added to `USER_METADATA_PROCESS_OPTIONS`, `USER_RFS_PROCESS_OPTIONS`, and `USER_REPLAYER_PROCESS_OPTIONS`:

```typescript
transformsSource: z.string().optional()
    .describe("Key into top-level transformsSources. Mounts /transforms/ into this program's pod.")
    .checksumFor('snapshot', 'replayer')
    .changeRestriction('impossible'),

transformsEntrypoint: z.string().optional()
    .describe("Override the default entrypoint filename within /transforms/. " +
        "Defaults: 'metadata-transforms.json' (metadata), 'doc-transforms.json' (RFS), " +
        "'replayer-transforms.json' (replayer).")
    .changeRestriction('impossible'),
```

These flow automatically through `ARGO_*_OPTIONS` via `makeOptionalDefaultedFieldsRequired()`, and into `---INLINE-JSON` via `expr.omit(options, ...WORKFLOW_OPTION_KEYS)` — no manual parameter threading needed.

### 1.3 Cross-field validation

In `OVERALL_MIGRATION_CONFIG.superRefine()`, validate that every `transformsSource` reference points to a defined key in `transformsSources`.

### 1.4 Default entrypoints

Each program has a default entrypoint filename. When `transformsSource` is set but no explicit `transformerConfigFile` / `docTransformerConfigFile` is provided, the workflow injects the default path automatically.

| Program | Default filename | Contents |
|---|---|---|
| Metadata Migration | `metadata-transforms.json` | JSON config for `IJsonTransformerProvider` chain |
| RFS (document backfill) | `doc-transforms.json` | JSON config for `IJsonTransformerProvider` chain |
| Traffic Replayer | `replayer-transforms.json` | JSON config for `IJsonTransformerProvider` chain |

One image can contain all three entrypoint JSONs plus shared script files. Each program picks up its own config automatically. Example transforms image contents:

```
/transforms/
├── metadata-transforms.json
├── doc-transforms.json
├── replayer-transforms.json
├── shared-logic.js
└── field-mappings.py
```

Inside each JSON, users can reference other files in `/transforms/` via the existing `initializationScriptFile` key:

```json
[{"JsonJSTransformerProvider": {
    "initializationScriptFile": "/transforms/shared-logic.js",
    "bindingsObject": "{}"
}}]
```

### 1.5 Memoization

Both `transformsSource` and `transformsEntrypoint` have `.checksumFor('snapshot', 'replayer').changeRestriction('impossible')`. Changing the transforms image reference (e.g. new digest) changes the config checksum, triggering re-execution of dependent steps. For ConfigMaps, the name is the only identity tracked — content changes without a name change are invisible to memoization.

---

## Part 2: Config Processor

`migrationConfigTransformer.ts` resolves each `transformsSource` key to its full definition from the `transformsSources` map. The resolved image ref or ConfigMap name is made available to the workflow templates that build pod specs.

Two workflow-level parameters carry the resolved source:

- `transformsImage` — the OCI image reference (empty string when not applicable)
- `transformsConfigMap` — the ConfigMap name (empty string when not applicable)

These are resolved from the aliased map during config transformation and passed as workflow parameters alongside the existing image config.

---

## Part 3: Workflow / Pod Changes

All changes are in TypeScript, fully type-checked, no casts.

### 3.1 Volume helper — `containerFragments.ts`

New composable function following the `setupLog4jConfigForContainer()` / `setupTestCredsForContainer()` pattern:

```typescript
export const TRANSFORMS_MOUNT_PATH = "/transforms";
const TRANSFORMS_VOLUME_NAME = "user-transforms";

export function setupTransformsForContainer(
    transformsImage: BaseExpression<string>,
    transformsConfigMap: BaseExpression<string>,
    def: ContainerVolumePair
): ContainerVolumePair {
    // When image is set: add K8s image volume source
    // When configMap is set: add configMap volume source
    // When neither: return unchanged
    // Volume mount is the same either way
}
```

For the **image source**, the volume uses the native K8s image volume type (available in K8s 1.35+):

```yaml
volumes:
  - name: user-transforms
    image:
      reference: "<transformsImage>"
      pullPolicy: IfNotPresent
volumeMounts:
  - name: user-transforms
    mountPath: /transforms
    readOnly: true
```

No init containers needed. The kubelet mounts the image layers directly.

For the **ConfigMap source**:

```yaml
volumes:
  - name: user-transforms
    configMap:
      name: "<transformsConfigMap>"
volumeMounts:
  - name: user-transforms
    mountPath: /transforms
    readOnly: true
```

### 3.2 RFS — `documentBulkLoad.ts`

`getRfsDeploymentManifest()` gets `transformsImage` and `transformsConfigMap` parameters. The final `ContainerVolumePair` is wrapped through `setupTransformsForContainer()`.

When `transformsSource` is set and no explicit `docTransformerConfigFile` is in the options, `makeParamsDict()` injects the default entrypoint:

```typescript
expr.ternary(
    expr.and(hasTransforms, expr.isEmpty(expr.dig(options, ["docTransformerConfigFile"], ""))),
    expr.makeDict({ docTransformerConfigFile: entrypointPath }),
    expr.makeDict({})
)
```

### 3.3 Replayer — `replayer.ts`

Same pattern as RFS. `getReplayerDeploymentManifest()` gets transforms parameters. Default entrypoint: `/transforms/replayer-transforms.json`.

### 3.4 MetadataMigration — `metadataMigration.ts`

Uses `addContainer()` → `addVolumesFromRecord()`. The `image` volume type requires the K8s types to include the image volume source (K8s 1.31+ API). If the generated `k8s-types` package doesn't include it yet, the types need regenerating from the K8s 1.35 OpenAPI spec.

For the ConfigMap source, it follows the existing `test-creds` pattern with `addVolumesFromRecord()`.

The conditional (image vs configMap vs neither) is handled with Argo expressions in the volume definition.

### 3.5 Default entrypoint injection

Each program's `makeParamsDict()` merges in the default `*ConfigFile` path when transforms are mounted but no explicit file config is set:

```
/transforms/<transformsEntrypoint or program-specific default>
```

### 3.6 No transforms case

When `transformsSource` is not set, `setupTransformsForContainer()` returns the input unchanged. No volume, no mount. The existing `*ConfigFile` fields remain empty, and the Java programs run without custom transforms.

---

## Part 4: Packaging

### `deployment/k8s/Dockerfile.transforms`

```dockerfile
FROM scratch
COPY . /transforms/
```

Works because K8s image volumes mount image layers directly — no runtime binary needed in the image.

### `deployment/k8s/package-transforms.sh`

Build and push script placed alongside `aws-bootstrap.sh` and other deployment scripts:

```bash
#!/usr/bin/env bash
# package-transforms.sh — build and push a transforms OCI image
#
# Usage: ./package-transforms.sh <transforms-dir> <registry-base> [tag]
#
# Example:
#   ./package-transforms.sh ./my-transforms \
#       123456789012.dkr.ecr.us-east-1.amazonaws.com/opensearch-migrations-transforms
#
# The printed reference (registry@sha256:…) is what you set in transformsSources.

set -euo pipefail

TRANSFORMS_DIR="${1:?Usage: $0 <transforms-dir> <registry-base> [tag]}"
REGISTRY_BASE="${2:?}"
TAG="${3:-latest}"
DOCKERFILE_DIR="$(cd "$(dirname "$0")" && pwd)"
IMAGE_REF="${REGISTRY_BASE}:${TAG}"

echo "Building transforms image from: ${TRANSFORMS_DIR}"
docker build -f "${DOCKERFILE_DIR}/Dockerfile.transforms" -t "${IMAGE_REF}" "${TRANSFORMS_DIR}"

echo "Pushing ${IMAGE_REF}..."
PUSH_OUTPUT=$(docker push "${IMAGE_REF}")

DIGEST=$(echo "${PUSH_OUTPUT}" | grep -oE 'sha256:[a-f0-9]{64}' | head -1)
if [[ -z "${DIGEST}" ]]; then
  DIGEST=$(docker inspect --format='{{index .RepoDigests 0}}' "${IMAGE_REF}" \
    | grep -oE 'sha256:[a-f0-9]{64}')
fi

PINNED_REF="${REGISTRY_BASE}@${DIGEST}"

echo ""
echo "✓ Transforms image pushed successfully."
echo ""
echo "  Set this in your transformsSources:"
echo "    transformsSources:"
echo "      my-transforms:"
echo "        image: \"${PINNED_REF}\""
```

---

## Part 5: User Experience Example

```yaml
transformsSources:
  my-transforms:
    image: "123456789012.dkr.ecr.us-east-1.amazonaws.com/transforms@sha256:abc123..."

sourceClusters:
  prod-es:
    version: "ES_7_10"
    endpoint: "https://source:9200"
    snapshotInfo:
      repos:
        main-repo: { s3RepoPathUri: "s3://bucket/path", awsRegion: "us-east-1" }
      snapshots:
        main-snapshot: { repoName: main-repo, config: { createSnapshotConfig: {} } }

targetClusters:
  prod-os:
    endpoint: "https://target:9200"

snapshotMigrationConfigs:
  - fromSource: prod-es
    toTarget: prod-os
    perSnapshotConfig:
      main-snapshot:
        - metadataMigrationConfig:
            transformsSource: my-transforms
            # uses default entrypoint: /transforms/metadata-transforms.json
          documentBackfillConfig:
            transformsSource: my-transforms
            transformsEntrypoint: "custom-doc-config.json"  # override default

traffic:
  proxies:
    capture:
      source: prod-es
  replayers:
    replay:
      fromProxy: capture
      toTarget: prod-os
      replayerConfig:
        transformsSource: my-transforms
        # uses default entrypoint: /transforms/replayer-transforms.json
```

---

## Part 6: Implementation Order

1. **Schema** (`userSchemas.ts`) — `TRANSFORMS_SOURCE`, map, per-tool fields, validation
2. **Argo schemas** (`argoSchemas.ts`) — ensure fields flow through via `makeOptionalDefaultedFieldsRequired()`
3. **Config processor** (`migrationConfigTransformer.ts`) — resolve aliases, add `transformsImage`/`transformsConfigMap` to workflow params
4. **K8s types** — verify/update `k8s-types` to include `image` volume source from K8s 1.35 API
5. **Container fragments** (`containerFragments.ts`) — `setupTransformsForContainer()`
6. **RFS** (`documentBulkLoad.ts`) — wire transforms into Deployment manifest + default entrypoint injection
7. **Replayer** (`replayer.ts`) — same
8. **MetadataMigration** (`metadataMigration.ts`) — wire transforms into container builder + default entrypoint injection
9. **Full migration** (`fullMigration.ts`) — thread resolved transforms image/configMap to sub-workflows (may be partially automatic via process options rollup; the resolved source itself needs explicit threading since it comes from the top-level config, not per-tool options)
10. **Packaging** (`deployment/k8s/Dockerfile.transforms`, `deployment/k8s/package-transforms.sh`)
11. **Docs** (`docs/Transforms.md` user-facing guide, update `docs/MigrationAsAWorkflow.md`, update `EXPERT_FILE_SUFFIX` text)
12. **Tests** — schema validation (mutual exclusion, key references), workflow rendering snapshots, integration tests

---

## Key Architectural Decisions

### Why K8s image volumes instead of init containers?

The project targets EKS with K8s 1.35, where the `ImageVolume` feature gate is enabled by default. Image volumes mount OCI image layers directly as read-only volumes — no init container, no `cp` binary, no `busybox` base image. The transforms image can be `FROM scratch` (just files, no OS). This is simpler, faster, and has no moving parts at runtime.

### Why an aliased map instead of inline image references?

OCI image references with digests are long (`123456789012.dkr.ecr.us-east-1.amazonaws.com/transforms@sha256:abc123...`). Defining them once in `transformsSources` and referencing by short key (`my-transforms`) keeps per-tool config readable. This follows the existing pattern used by `kafkaClusterConfiguration`, `sourceClusters`, and `targetClusters`.

### Why default entrypoints?

One transforms image serves all three programs (metadata, RFS, replayer). Each program has a different transform signature, so each needs its own config file. Default filenames (`metadata-transforms.json`, `doc-transforms.json`, `replayer-transforms.json`) let users build a single image and have each program find its config automatically. The `transformsEntrypoint` override allows non-standard filenames when needed.

### Why not auto-discover scripts by convention?

The codebase already has a mature transform resolution system: `TransformerConfigUtils` → `ScriptTransformerProvider` with `initializationScriptFile`. The entrypoint JSON files configure this existing SPI chain. The mounted volume provides files on disk; the existing Java infrastructure consumes them. No new discovery mechanism is needed.

### Why process options rollup handles parameter threading?

The `USER_*_PROCESS_OPTIONS` schemas are rolled up into `ARGO_*_OPTIONS` via `makeOptionalDefaultedFieldsRequired()`. Process option fields that aren't in `*_WORKFLOW_OPTION_KEYS` are automatically included in the `---INLINE-JSON` dict via `expr.omit(options, ...WORKFLOW_OPTION_KEYS)`. Adding `transformsSource` and `transformsEntrypoint` to the process options means they flow to the Java programs automatically. The resolved volume source (image ref or ConfigMap name) still needs explicit threading since it comes from the top-level config.

### Why extend ContainerBuilder for MetadataMigration?

MetadataMigration uses `addContainer()` (Argo-managed pods) while RFS and Replayer use `addResourceTask()` (K8s Deployments). The image volume type needs to be supported in `addVolumesFromRecord()`. Converting MetadataMigration to a Job-based approach would be cleaner long-term but is a larger lift and not critical for this feature.
