# Mountable Transforms — Design Document

## Overview

Users define transform sources once in a top-level `transformsSources` map (aliased by name), then reference them by key in per-tool options. For example, `transformsSource: my-transforms` selects `transformsSources.my-transforms` as the volume source; it is not a file path. The workflow mounts `/transforms/` into every transform-eligible pod (MetadataMigration, RFS, Replayer). Per-program options define one or more script transforms as ordered pipelines. Users provide paths relative to the mounted transform source; the workflow expands those into full `/transforms/...` paths before passing config to Java.

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
        .describe("OCI image reference (preferably with digest) whose root contains transform files. " +
            "The workflow mounts the image at /transforms/. Build with deployment/k8s/package-transforms.sh.")
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

### 1.2 Transform pipeline fields

Add a shared user-facing transform shape to `userSchemas.ts`. A single transform object is normalized to a one-item list so users do not need list syntax for the common case. Multiple items run in list order.

```typescript
const TRANSFORM_LANGUAGE = z.enum(["javascript", "python"]);

const TRANSFORM_RELATIVE_FILE = z.string()
    .regex(/^(?!\/)(?!.*(?:^|\/)\.\.(?:\/|$)).+$/)
    .describe("Script path relative to /transforms, e.g. 'request.js' or 'lib/request.py'.");

const TRANSFORM_SPEC = z.object({
    language: TRANSFORM_LANGUAGE
        .describe("Runtime for this transform script."),
    file: TRANSFORM_RELATIVE_FILE.optional()
        .describe("Script path relative to /transforms. Defaults by transform kind and language."),
    bindingsObject: GENERIC_JSON_OBJECT.default({}).optional()
        .describe("Optional JSON object passed to the transform at initialization.")
});

const TRANSFORM_PIPELINE = z.preprocess(
    v => v === undefined || Array.isArray(v) ? v : [v],
    z.array(TRANSFORM_SPEC)
).describe("Ordered transform pipeline. Each item is run in sequence.");
```

Added to `USER_METADATA_PROCESS_OPTIONS`, `USER_RFS_PROCESS_OPTIONS`, and `USER_REPLAYER_PROCESS_OPTIONS`:

```typescript
transformsSource: z.string().optional()
    .describe("Key into top-level transformsSources. Mounts /transforms/ into this program's pod.")
    .checksumFor('snapshot', 'replayer')
    .changeRestriction('impossible'),
```

Added to each program's process options:

```typescript
// USER_METADATA_PROCESS_OPTIONS
metadataTransforms: TRANSFORM_PIPELINE.optional()
    .describe("Metadata transform pipeline. Generates the existing transformerConfig value.")
    .checksumFor('snapshot', 'replayer')
    .changeRestriction('impossible'),

// USER_RFS_PROCESS_OPTIONS
documentTransforms: TRANSFORM_PIPELINE.optional()
    .describe("Document transform pipeline. Generates the existing docTransformerConfig value.")
    .checksumFor('replayer')
    .changeRestriction('impossible'),

// USER_REPLAYER_PROCESS_OPTIONS
requestTransforms: TRANSFORM_PIPELINE.optional()
    .describe("Request transform pipeline. Generates the existing transformerConfig value.")
    .changeRestriction('gated'),

tupleTransforms: TRANSFORM_PIPELINE.optional()
    .describe("Tuple transform pipeline. Generates the existing tupleTransformerConfig value.")
    .changeRestriction('gated'),
```

The legacy raw config fields remain available for expert use:

- Metadata transforms: `transformerConfig`, `transformerConfigBase64`, `transformerConfigFile`
- Replayer request transforms: `transformerConfig`, `transformerConfigEncoded`, `transformerConfigFile`
- RFS document transforms: `docTransformerConfig`, `docTransformerConfigBase64`, `docTransformerConfigFile`
- Replayer tuple transforms: `tupleTransformerConfig`, `tupleTransformerConfigBase64`, `tupleTransformerConfigFile`

Validation should reject configuring both the new pipeline field and any legacy raw config field for the same transform surface.

### 1.3 Cross-field validation

In `OVERALL_MIGRATION_CONFIG.superRefine()` and per-tool refinements:

- Validate that every `transformsSource` reference points to a defined key in `transformsSources`.
- Require `transformsSource` when a pipeline field is set, because relative script files need a mounted `/transforms` source.
- Reject absolute user script paths and paths containing `..`.
- Reject pipeline fields when the corresponding legacy raw config field is also set.

No duplicate-item validation is required. Pipeline order is authoritative; repeated transform entries are allowed.

### 1.4 Default script files and generated Java config

Each transform item must define its `language`. If `file` is omitted, the workflow chooses a default relative script filename from the transform kind and language:

| Program | User pipeline field | Inline JSON option populated | Default JavaScript file | Default Python file |
|---|---|---|---|---|
| Metadata Migration | `metadataTransforms` | `transformerConfig` | `metadata.js` | `metadata.py` |
| RFS document backfill | `documentTransforms` | `docTransformerConfig` | `document.js` | `document.py` |
| Traffic Replayer request | `requestTransforms` | `transformerConfig` | `request.js` | `request.py` |
| Traffic Replayer tuple | `tupleTransforms` | `tupleTransformerConfig` | `tuple.js` | `tuple.py` |

One image can contain scripts for all transform surfaces plus shared helper files. Example transforms image contents:

```
/transforms/
├── metadata.js
├── document.py
├── request.js
├── tuple.py
├── lib/
│   └── shared.py
└── shared-logic.js
```

The config processor lowers user-facing transform specs into the existing `IJsonTransformerProvider` JSON list. User paths are relative; generated Java config uses full paths under `/transforms`.

```json
[
  {
    "JsonJSTransformerProvider": {
      "initializationScriptFile": "/transforms/request.js",
      "bindingsObject": "{\"mode\":\"strict\"}"
    }
  },
  {
    "JsonPythonTransformerProvider": {
      "initializationScriptFile": "/transforms/lib/normalize.py",
      "bindingsObject": "{}"
    }
  }
]
```

`ScriptTransformerProvider` accepts omitted `bindingsObject`, object-valued `bindingsObject`, and the legacy JSON-string form. The lowering step still serializes the object to the existing JSON-string form so generated configs remain compatible with older providers.

### 1.5 Memoization

`transformsSource` and the new pipeline fields use the same checksum/change-restriction behavior as the raw transform config fields they replace. Changing the transforms image reference (e.g. new digest) changes the config checksum, triggering re-execution of dependent steps. For ConfigMaps, the name is the only identity tracked — content changes without a name change are invisible to memoization.

---

## Part 2: Config Processor

`migrationConfigTransformer.ts` resolves each `transformsSource` key to its full definition from the `transformsSources` map. The resolved image ref or ConfigMap name is made available to the workflow templates that build pod specs.

It also lowers pipeline fields into the existing inline JSON process options:

- `metadataTransforms` -> `transformerConfig`
- `documentTransforms` -> `docTransformerConfig`
- Replayer `requestTransforms` -> `transformerConfig`
- Replayer `tupleTransforms` -> `tupleTransformerConfig`

Lowering rules:

- Convert each user transform item into one provider map in the existing top-level JSON list.
- Preserve list order exactly.
- Map `language: javascript` to `JsonJSTransformerProvider`.
- Map `language: python` to `JsonPythonTransformerProvider`.
- Expand user-relative `file` values to `/transforms/<file>` for `initializationScriptFile`.
- When `file` is omitted, use the default relative script filename for the transform kind and language, then expand it to `/transforms/...`.
- Serialize `bindingsObject` to the existing JSON-string form unless the Java provider contract is changed to accept object values directly.

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

When `documentTransforms` is set, `makeParamsDict()` injects the generated `docTransformerConfig` JSON unless a legacy document transformer config source is already configured. The schema should normally reject that conflict before rendering.

```typescript
expr.ternary(
    expr.and(hasDocumentTransforms, noLegacyDocTransformerConfig),
    expr.makeDict({ docTransformerConfig: generatedDocumentTransformerConfig }),
    expr.makeDict({})
)
```

### 3.3 Replayer — `replayer.ts`

Same volume-mount pattern as RFS. `getReplayerDeploymentManifest()` gets transforms parameters.

Replayer has two independent transform surfaces that use the same transformer APIs but different inline JSON options:

- `requestTransforms` generates `transformerConfig`.
- `tupleTransforms` generates `tupleTransformerConfig`.

Each surface is lowered independently, with default files `request.js` / `request.py` and `tuple.js` / `tuple.py`.

### 3.4 MetadataMigration — `metadataMigration.ts`

Uses `addContainer()` → `addVolumesFromRecord()`. The `image` volume type requires the K8s types to include the image volume source (K8s 1.31+ API). If the generated `k8s-types` package doesn't include it yet, the types need regenerating from the K8s 1.35 OpenAPI spec.

For the ConfigMap source, it follows the existing `test-creds` pattern with `addVolumesFromRecord()`.

The conditional (image vs configMap vs neither) is handled with Argo expressions in the volume definition.

### 3.5 Generated transform config injection

Each program's `makeParamsDict()` merges generated inline transformer config into the existing inline JSON option when a pipeline field is present:

- Metadata Migration: generated `transformerConfig`
- RFS: generated `docTransformerConfig`
- Replayer request transforms: generated `transformerConfig`
- Replayer tuple transforms: generated `tupleTransformerConfig`

The generated Java config always uses full paths such as `/transforms/tuple.py`, even though users provide relative paths such as `tuple.py`.

### 3.6 No transforms case

When `transformsSource` is not set, `setupTransformsForContainer()` returns the input unchanged. No volume, no mount. When no pipeline or legacy raw transform config is set, the Java programs run without custom transforms.

If `transformsSource` is set without any pipeline fields, the volume is still mounted but no transform options are generated. This allows users to mount helper files for legacy raw configs or future use.

### 3.7 Python script import path

For Python transforms loaded from `initializationScriptFile`, add the script's parent directory to `sys.path` before evaluating the script. This lets `/transforms/tuple.py` import sibling modules from `/transforms/`, and lets `/transforms/lib/tuple.py` import from `/transforms/lib/`.

The current `ScriptTransformerProvider` resolves scripts to source text only. To support this cleanly, update script resolution to preserve source metadata, for example:

```java
record ResolvedScript(String source, Path sourceFile) {}
```

`JsonPythonTransformerProvider` can then pass `sourceFile.getParent()` to `PythonTransformer`, and `PythonTransformer` can insert that directory into `sys.path` during context initialization. JavaScript does not need this behavior for the current provider contract.

---

## Part 4: Packaging

### `deployment/k8s/Dockerfile.transforms`

```dockerfile
FROM scratch
COPY . /
```

Works because K8s image volumes mount image layers directly — no runtime binary needed in the image.
Copying the transform directory contents to the image root makes those files appear directly under `/transforms/` when the image is mounted.

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

SCRIPT_NAME="$(basename "${BASH_SOURCE[0]}")"

usage() {
  cat >&2 <<USAGE
Usage:
  ${SCRIPT_NAME} <transforms-dir> <registry-base> [tag]

Arguments:
  transforms-dir   Directory containing transform files to copy into the image root.
                   May be a relative or absolute path.
  registry-base    Registry/repository name without a tag.
  tag              Optional image tag. Defaults to latest.

Example:
  ${SCRIPT_NAME} ./my-transforms \\
      123456789012.dkr.ecr.us-east-1.amazonaws.com/opensearch-migrations-transforms
USAGE
}

die_with_usage() {
  echo "Error: $1" >&2
  echo >&2
  usage
  exit 2
}

if [[ $# -lt 2 ]]; then
  die_with_usage "missing required argument(s)."
fi

if [[ $# -gt 3 ]]; then
  die_with_usage "unexpected argument(s): ${*:4}"
fi

TRANSFORMS_DIR_ARG="$1"
REGISTRY_BASE="$2"
TAG="${3:-latest}"

if [[ -z "${TRANSFORMS_DIR_ARG}" ]]; then
  die_with_usage "transforms-dir must not be empty."
fi

if [[ -z "${REGISTRY_BASE}" ]]; then
  die_with_usage "registry-base must not be empty."
fi

if [[ $# -eq 3 && -z "${TAG}" ]]; then
  die_with_usage "tag must not be empty when provided."
fi

if [[ ! -d "${TRANSFORMS_DIR_ARG}" ]]; then
  die_with_usage "transforms-dir does not exist or is not a directory: ${TRANSFORMS_DIR_ARG}"
fi

TRANSFORMS_DIR="$(cd -- "${TRANSFORMS_DIR_ARG}" && pwd -P)"
DOCKERFILE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
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
            metadataTransforms:
              language: javascript
              # file omitted; uses metadata.js and passes /transforms/metadata.js to Java
          documentBackfillConfig:
            transformsSource: my-transforms
            documentTransforms:
              - language: python
                file: rfs/document.py
                bindingsObject:
                  indexPrefix: migrated-

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
        requestTransforms:
          - language: javascript
            file: request.js
            bindingsObject:
              mode: strict
          - language: python
            file: request-normalize.py
        tupleTransforms:
          language: python
          # file omitted; uses tuple.py and passes /transforms/tuple.py to Java
```

---

## Part 6: Implementation Order

1. **Schema** (`userSchemas.ts`) — `TRANSFORMS_SOURCE`, map, pipeline transform fields, relative path validation, legacy-field conflict validation
2. **Argo schemas** (`argoSchemas.ts`) — ensure fields flow through via `makeOptionalDefaultedFieldsRequired()`
3. **Config processor** (`migrationConfigTransformer.ts`) — resolve aliases, lower pipeline fields to existing Java transformer config JSON, add `transformsImage`/`transformsConfigMap` to workflow params
4. **K8s types** — verify/update `k8s-types` to include `image` volume source from K8s 1.35 API
5. **Container fragments** (`containerFragments.ts`) — `setupTransformsForContainer()`
6. **RFS** (`documentBulkLoad.ts`) — wire transforms into Deployment manifest + generated `docTransformerConfig` injection
7. **Replayer** (`replayer.ts`) — wire request and tuple transform pipelines separately
8. **MetadataMigration** (`metadataMigration.ts`) — wire transforms into container builder + generated `transformerConfig` injection
9. **Full migration** (`fullMigration.ts`) — thread resolved transforms image/configMap to sub-workflows (may be partially automatic via process options rollup; the resolved source itself needs explicit threading since it comes from the top-level config, not per-tool options)
10. **Packaging** (`deployment/k8s/Dockerfile.transforms`, `deployment/k8s/package-transforms.sh`)
11. **Docs** (`docs/Transforms.md` user-facing guide, update `docs/MigrationAsAWorkflow.md`, update `EXPERT_FILE_SUFFIX` text)
12. **Java provider support** — allow optional object-valued `bindingsObject` or keep serialization in the config processor; add Python script parent directory to `sys.path`
13. **Tests** — schema validation (source references, relative paths, legacy config conflicts), workflow rendering snapshots, generated transformer config tests, Python sibling import test, integration tests

---

## Key Architectural Decisions

### Why K8s image volumes instead of init containers?

The project targets EKS with K8s 1.35, where the `ImageVolume` feature gate is enabled by default. Image volumes mount OCI image layers directly as read-only volumes — no init container, no `cp` binary, no `busybox` base image. The transforms image can be `FROM scratch` (just files, no OS). This is simpler, faster, and has no moving parts at runtime.

### Why an aliased map instead of inline image references?

OCI image references with digests are long (`123456789012.dkr.ecr.us-east-1.amazonaws.com/transforms@sha256:abc123...`). Defining them once in `transformsSources` and referencing by short key (`my-transforms`) keeps per-tool config readable. This follows the existing pattern used by `kafkaClusterConfiguration`, `sourceClusters`, and `targetClusters`.

### Why default script filenames?

One transforms image can serve all transform-eligible programs. Each transform surface has a different input shape, so each pipeline item identifies both the language and the script. Default script filenames (`metadata.js`, `metadata.py`, `document.js`, `document.py`, `request.js`, `request.py`, `tuple.js`, `tuple.py`) keep the common case concise while still allowing explicit relative file paths.

### Why generate existing transformer config instead of inventing a new Java API?

The codebase already has a mature transform resolution system: `TransformerConfigUtils` -> `TransformationLoader` -> `ScriptTransformerProvider` with `initializationScriptFile`. The new user schema is friendlier, but it lowers to the existing SPI chain. The mounted volume provides files on disk; the generated config gives Java full `/transforms/...` paths.

### Why process options rollup handles parameter threading?

The `USER_*_PROCESS_OPTIONS` schemas are rolled up into `ARGO_*_OPTIONS` via `makeOptionalDefaultedFieldsRequired()`. Process option fields that aren't in `*_WORKFLOW_OPTION_KEYS` are automatically included in the `---INLINE-JSON` dict via `expr.omit(options, ...WORKFLOW_OPTION_KEYS)`. Adding `transformsSource` and pipeline fields to the process options means the user-facing fields flow through automatically. The generated raw transformer config values and resolved volume source (image ref or ConfigMap name) still need explicit handling because they are derived values.

### Why extend ContainerBuilder for MetadataMigration?

MetadataMigration uses `addContainer()` (Argo-managed pods) while RFS and Replayer use `addResourceTask()` (K8s Deployments). The image volume type needs to be supported in `addVolumesFromRecord()`. Converting MetadataMigration to a Job-based approach would be cleaner long-term but is a larger lift and not critical for this feature.
