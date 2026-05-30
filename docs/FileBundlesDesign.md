# Mountable File Sources - Design Document

## Overview

`MountableTransformsDesign.md` introduced the important runtime pattern: a user can provide files through Kubernetes mounts, and the workflow can mount those files read-only into the pods that need them. This document generalizes that pattern beyond transform code.

The user should be able to declare file-bearing inputs directly where those inputs are used:

- transform entry-point files
- transform context files
- Solr source-cluster configuration
- capture proxy mTLS client trust roots
- future component-specific configuration files

There is no top-level `fileBundles` alias block in this design, and there is no compatibility layer for `transformsSources` or per-component `transformsSource`. ConfigMaps and OCI image volumes are short enough to declare at the use site, and repeated declarations are deduped by the config processor.

Builder-owned config-processor extensions, such as injecting a default Solr replay transform from image-baked extension code, are covered separately in `ConfigProcessorExtensionsDesign.md`.

## Goals

- Let users provide read-only files from ConfigMaps or mountable OCI images.
- Let any compute component mount multiple file sources.
- Keep file-source syntax generic enough for transforms, source-cluster config, mTLS roots, and later file-backed settings.
- Add generic source-cluster configuration files that Solr can use first without making the workflow know Solr file semantics.
- Make transform specs support either a script entry point or an existing transformer provider name.
- Let transform context be a plain string, explicit values, file-backed values, or directory-loaded values.
- Keep transformer providers focused on logical config objects, not Kubernetes mounts, env var scanning, or file parsing.
- Push generic file-source volume arrays through the Argo schema so workflow templates do not need transform- or Solr-specific mount logic.

## Non-Goals

- This does not replace Kubernetes Secrets for private keys or credentials.
- This does not materialize inline user strings into mounted files. Inline strings should use explicit value/script fields; fields that require file paths should use ConfigMaps, OCI images, or workflow-produced resources.
- This does not require the config processor to read user-provided ConfigMaps or OCI images. Those files are read by runtime components after Kubernetes mounts them.
- This does not define Solr-specific file semantics in the workflow schema. Solr docs and Solr-aware components define which named files they understand.
- This does not define builder-owned config-processor extension hooks. Those are trusted application code and stay out of the user-facing file-source model.

## Core Model

**File source**: a direct declaration of read-only files. Initial source types are ConfigMap and OCI image volume.

**File ref**: a reference to one file inside a file source. A `FILE_REF` always resolves to a runtime file path.

**Config value source**: a provider setting. It can be a literal value or it can be loaded from a file. A file-backed config value is explicit about being read and parsed.

**Runtime mount**: the Kubernetes volume and mount path generated for one file source in one compute component.

**Generated source**: a deterministic resource created by the workflow for files produced by earlier workflow steps.

The important distinction is that file refs are paths, while config values are values. The schema should not leave a user guessing whether a file reference is passed through as a path or inlined into a JSON object.

## User Schema

### File Refs

Use one shared source shape anywhere the user needs a file path:

```typescript
const FILE_RELATIVE_PATH = z.string()
    .regex(/^(?!\/)(?!.*(?:^|\/)\.\.(?:\/|$)).+$/);

const CONFIGMAP_FILE_KEY = z.string()
    .regex(/^(?!\.{1,2}$)(?!\.\.)[A-Za-z0-9._-]+$/);

const FILE_REF_FROM_IMAGE = z.object({
    image: z.string().min(1),
    pullPolicy: K8S_IMAGE_PULL_POLICY.default("IfNotPresent").optional(),
    path: FILE_RELATIVE_PATH
}).strict();

const FILE_REF_FROM_CONFIGMAP = z.object({
    configMap: z.string().min(1),
    path: CONFIGMAP_FILE_KEY
}).strict();

const FILE_REF = z.union([
    FILE_REF_FROM_IMAGE,
    FILE_REF_FROM_CONFIGMAP
]);
```

`FILE_REF` intentionally does not include inline contents. Inline content belongs in fields whose semantics are already values, such as `entryPoint.javascript` or `context.values.<name>.value`. If a field needs a path, the user should provide a ConfigMap, an OCI image, or a workflow-produced resource with a known name.

### Transform Entry Points

Script entry points and named providers are mutually exclusive. A named Java provider is not a script entry point.

```typescript
const SCRIPT_TRANSFORM_ENTRY_POINT = z.union([
    z.object({
        javascript: z.string().min(1)
    }).strict(),
    z.object({
        javascriptFile: FILE_REF
    }).strict(),
    z.object({
        python: z.string().min(1)
    }).strict(),
    z.object({
        pythonFile: FILE_REF
    }).strict()
]);

const TRANSFORM_SPEC_V2 = z.object({
    entryPoint: SCRIPT_TRANSFORM_ENTRY_POINT.optional(),
    transformName: z.string().optional(),
    context: TRANSFORM_CONTEXT.optional()
}).strict().superRefine((value, ctx) => {
    const selectorCount = [
        value.entryPoint !== undefined,
        value.transformName !== undefined
    ].filter(Boolean).length;

    if (selectorCount !== 1) {
        ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: "Exactly one of entryPoint or transformName is required"
        });
    }
});
```

`entryPoint.javascript` is an inline script and translates to `initializationScript`. `entryPoint.javascriptFile` translates to `initializationScriptFile`. `entryPoint.python` and `entryPoint.pythonFile` select `JsonPythonTransformerProvider` and translate to the same script keys. The file forms use a ConfigMap or image file.

Do not overload `pythonModulePath` as the Python entry-point selector. In the current Java runtime, `JsonPythonTransformerProvider` uses `pythonModulePath` as an optional local directory or `.tar.gz` virtual environment path in addition to `initializationScriptFile`. If mounted Python dependency bundles are needed in v1, add them as an optional Python-entry-point field with semantics that can represent a directory or tarball; otherwise leave that as a follow-up.

### Transform Context

Context is shared by both selector forms, so it lives next to `entryPoint | transformName`.

```typescript
const TRANSFORM_CONTEXT_READ_AS = z.enum([
    "json",
    "text",
    "bytes",
    "base64",
    "path"
]);

const TRANSFORM_CONTEXT_VALUE_DIRECTORY = z.union([
    z.object({
        configMap: z.string().min(1),
        defaultReadAs: TRANSFORM_CONTEXT_READ_AS.optional()
    }).strict(),
    z.object({
        image: z.string().min(1),
        pullPolicy: K8S_IMAGE_PULL_POLICY.default("IfNotPresent").optional(),
        path: FILE_RELATIVE_PATH.optional(),
        defaultReadAs: TRANSFORM_CONTEXT_READ_AS.optional()
    }).strict()
]);

const CONFIG_VALUE_FROM_FILE = z.object({
    fromFile: FILE_REF,
    readAs: TRANSFORM_CONTEXT_READ_AS.optional()
}).strict();

const TRANSFORM_CONTEXT_VALUE = z.union([
    z.object({ value: z.any() }).strict(),
    CONFIG_VALUE_FROM_FILE
]);

const TRANSFORM_CONTEXT = z.union([
    z.string(),
    z.object({
        valueDirectories: z.array(TRANSFORM_CONTEXT_VALUE_DIRECTORY).default([]).optional(),
        values: z.record(z.string(), TRANSFORM_CONTEXT_VALUE).default({}).optional()
    }).strict()
]);
```

The wrapper keys are intentional:

- `value` means "this is already the value."
- `fromFile` means "mount this file, read it at runtime, parse it, and use the parsed result as the value."
- `readAs` is a transformation-layer type hint. It tells the loader whether the value should be JSON, text, bytes, base64-encoded bytes, or a path string. It is not used by the workflow mount logic.

`valueDirectories` is intentionally not a list of file refs. Each entry identifies one mounted directory:

- A ConfigMap entry uses the ConfigMap volume root as the directory.
- An image entry uses `path` as the directory inside the image volume, or the image root when `path` is omitted.
- Literal `value` entries are not allowed here because `valueDirectories` represents a directory of named values.

At runtime, each immediate file in the directory becomes one context key. The key is derived from the file name, and the value is materialized according to provider metadata or `defaultReadAs`; `path` values remain runtime path strings instead of being read.

For named providers, the preferred source of expected types is provider-owned metadata. A provider can declare that `regexMappings` is JSON, `certificateChain` is bytes, or `dictionaryPath` should remain a path. Users should only need `readAs` when a provider does not declare a type, when a script transform needs the value, or when a directory uses a default type for unknown keys.

Avoid accepting bare scalars or objects inside `context.values` in v1. Requiring `value` keeps the file-vs-value distinction explicit.

## File And Value Semantics

The schema has three distinct concepts:

- Script source, such as `entryPoint.javascript` or `entryPoint.javascriptFile`.
- Runtime file paths derived from file-bearing fields, such as `clusterConfiguration.files.<name>` and `clientAuth.trustRoots`.
- Context values, which become provider config values or script `bindingsObject` values.

The config processor can embed inline values directly into generated JSON, but it cannot inline files from ConfigMaps or images because it cannot read them while transforming the user config. File refs become component-level volume arrays plus resolved runtime paths. File-backed context values become resolver instructions that Java reads after the files are mounted.

Non-context file and script fields translate directly:

- `entryPoint.javascript` selects `JsonJSTransformerProvider` and sets `initializationScript`.
- `entryPoint.javascriptFile` selects `JsonJSTransformerProvider` and sets `initializationScriptFile` to the resolved path.
- `entryPoint.python` selects `JsonPythonTransformerProvider` and sets `initializationScript`.
- `entryPoint.pythonFile` selects `JsonPythonTransformerProvider` and sets `initializationScriptFile` to the resolved path.
- `transformName` selects the named provider used by `TransformationLoader`.
- `clusterConfiguration.files.<name>` is a named source-cluster file declaration; a later projection can turn it into a resolved path plus mounts for each consuming component.
- `clientAuth.trustRoots` becomes `sslTrustCertFile` with the resolved path.

Context translation has three stages:

1. The user-facing `context` field.
2. The generated transformer config JSON passed to the replayer.
3. The resolved provider config object passed to `IJsonTransformerProvider.createTransformer()`.

For a named provider, context is attached to the provider config:

```json
{
  "TypeMappingSanitizationTransformerProvider": {
    "...": "..."
  }
}
```

For a script entry point, context is attached to the script provider's binding fields:

```json
{
  "JsonJSTransformerProvider": {
    "initializationScriptFile": "/file-sources/image-9d105c/request.js",
    "...": "..."
  }
}
```

Context translation rules:

| User context form | Named provider generated config | Script provider generated config | Final provider sees |
| --- | --- | --- | --- |
| no `context` | no context keys | only entry-point keys | provider default config |
| `context: "name"` | provider config is the string `"name"` | `bindingsObject: "name"` | the string value |
| `context.valueDirectories[]` | `providerConfigDirs: ["<resolved dir>"]` | `bindingsObjectDirs: ["<resolved dir>"]` | each immediate file becomes one key/value |
| `context.values.foo.value` | `foo: <value>` | `bindingsObject.foo: <value>` | `foo` is the literal value |
| `context.values.foo.fromFile` | `providerConfigFiles.foo: {path, readAs?}` | `bindingsObjectFiles.foo: {path, readAs?}` | `foo` is read according to provider metadata or `readAs` |

Environment variables are not part of the v1 transform-context model. They can still be used by field-specific runtime features, such as inline proxy trust roots, but ConfigMap-backed transform context should use mounted files or directories. Existing component-specific env vars, such as auth secrets, JVM/logging flags, local test credentials, and config-processor input paths, remain explicit workflow plumbing rather than a generic user-facing context transport.

`ProviderConfigResolver` performs final flattening inside the Java runtime:

- It reads `providerConfigDirs` and `bindingsObjectDirs`.
- It reads files named by `providerConfigFiles` and `bindingsObjectFiles`.
- It resolves each value's expected type from provider metadata, explicit `readAs`, or directory `defaultReadAs`.
- It parses or materializes values according to that expected type.
- It removes the reserved resolver keys before the provider receives its config.

Expected type resolution:

1. For named providers, ask the provider for an expected type for the specific config key.
2. If the user supplied `readAs` and the provider also supplied metadata, they must agree; otherwise fail before creating the transformer.
3. If only one of provider metadata or `readAs` is available, use that.
4. For `valueDirectories`, use provider metadata for the derived key when available. Otherwise use the directory entry's `defaultReadAs`.
5. For script providers, use explicit `readAs` or `defaultReadAs`; script entry points do not have provider-specific context metadata.
6. If no expected type can be determined for a file-backed value, fail rather than guessing from the file extension.

Supported value types:

- `json`: read UTF-8 text and parse JSON into maps, lists, strings, numbers, booleans, or null.
- `text`: read UTF-8 text and pass a string.
- `bytes`: read raw bytes and pass a Java byte array or equivalent host object. This is mainly for Java providers.
- `base64`: read raw bytes and pass a base64 string. This is the portable binary form for script bindings.
- `path`: do not read the file. Pass the resolved runtime path string.

Merge precedence is deterministic:

1. Value directories are loaded first, in the order listed. Later directories override earlier directories for the same key.
2. Named file values from `fromFile` override directory-loaded values.
3. Literal `value` entries override all loaded values.
4. For script providers, explicitly supplied `bindingsObject` values win over anything loaded into the same binding key.

For named providers, final flattening produces the provider config itself. For script providers, final flattening produces the script provider config with a fully materialized `bindingsObject`, while entry-point fields such as `initializationScriptFile` remain paths because the script provider owns loading the script source.

Example user context for a named provider:

```yaml
context:
  valueDirectories:
    - configMap: type-mappings-settings
  values:
    sourceProperties:
      value:
        version:
          major: 7
          minor: 10
    regexMappings:
      fromFile:
        image: "repo/type-mapping-context@sha256:abc123"
        path: regexMappings.json
        readAs: json
    featureFlags:
      fromFile:
        configMap: type-mapping-flags
        path: featureFlags.json
        readAs: json
```

Generated transformer config:

```json
{
  "TypeMappingSanitizationTransformerProvider": {
    "providerConfigDirs": [
      "/file-sources/configmap-type-mappings-settings"
    ],
    "providerConfigFiles": {
      "regexMappings": {
        "path": "/file-sources/image-a81c2f/regexMappings.json",
        "readAs": "json"
      },
      "featureFlags": {
        "path": "/file-sources/configmap-type-mapping-flags/featureFlags.json",
        "readAs": "json"
      }
    },
    "sourceProperties": {
      "version": {
        "major": 7,
        "minor": 10
      }
    }
  }
}
```

Final config passed to the named provider:

```json
{
  "directoryKey": "value loaded from a value directory",
  "regexMappings": "JSON value parsed from regexMappings.json",
  "featureFlags": "JSON value parsed from featureFlags.json",
  "sourceProperties": {
    "version": {
      "major": 7,
      "minor": 10
    }
  }
}
```

The same context attached to a script entry point uses `bindingsObjectDirs`, `bindingsObjectFiles`, and `bindingsObject` instead. The resolver flattens those into a single `bindingsObject` before the script provider initializes the script.

## Mount Translation And Deduplication

The config processor collects file refs from:

- source-cluster configuration files
- transform entry points
- transform context value directories and file-backed context values
- capture proxy mTLS trust roots
- future component-specific file-bearing fields

It then canonicalizes and dedupes file sources within each compute component.

Canonical identities:

```text
ConfigMap: { type: "configMap", name }
Image:     { type: "image", reference, pullPolicy }
```

For example, if one replayer uses the same ConfigMap for source-cluster configuration and a transform context file:

```yaml
sourceClusters:
  prod-solr:
    version: "SOLR 8.11.4"
    endpoint: "https://solr.example.com:8983"
    clusterConfiguration:
      files:
        clusterConfig:
          configMap: prod-solr-config
          path: solrconfig.xml

traffic:
  replayers:
    replay:
      replayerConfig:
        requestTransforms:
          - transformName: TypeMappingSanitizationTransformerProvider
            context:
              values:
                sourceProperties:
                  value:
                    version:
                      major: 7
                      minor: 10
                    distribution: elasticsearch
                regexMappings:
                  fromFile:
                    configMap: prod-solr-config
                    path: regexMappings.json
                    readAs: json
                defaults:
                  fromFile:
                    configMap: prod-solr-config
                    path: defaults.json
                    readAs: json
```

the replayer pod should get one mount root:

```text
/file-sources/configmap-prod-solr-config/solrconfig.xml
/file-sources/configmap-prod-solr-config/defaults.json
```

The repeated ConfigMap reference is intentional. This design does not automatically wire `sourceClusters.<source>.clusterConfiguration.files.clusterConfig` into transform context. If a transform provider needs source-cluster config, the user config or a later config-processor extension must place that value into the transform's `context`.

The same transform context could also be implemented with `valueDirectories` if the mounted directory's file names match the provider's expected context keys, such as `regexMappings` and `featureFlags`. In that case the resolver would load each immediate file as one context value, using provider metadata or `defaultReadAs` to decide whether each value is a path, JSON, text, bytes, or base64.

This dedupe is per component. If MetadataMigration and Replayer both use `prod-solr-config`, each pod gets one mount. There is no global shared mount.

Image references should be treated literally in v1. A tag and digest string are different identities unless the processor later adds explicit normalization. Different pull policies are also different identities because they produce different Kubernetes volume specs.

Generated Kubernetes volume names and mount roots should come from the canonical source identity, not from a user-provided alias. They must be sanitized for Kubernetes volume-name rules and should include a short stable hash to avoid collisions and length issues. Examples in this document use readable names such as `file-source-configmap-prod-solr-config`, but implementation should not rely on raw ConfigMap names, image tags, or YAML keys being valid volume names.

## Argo Workflow Model

The config processor should translate user file refs into generic Kubernetes volume arrays carried in the transformed Argo config. The templates should append these arrays to their static `volumes` and `volumeMounts`; they should not know why a mount exists.

Suggested transformed shape:

```typescript
const ARGO_FILE_SOURCE_VOLUME_SOURCE = z.union([
    z.object({
        configMap: z.object({
            name: z.string().min(1)
        }).strict()
    }).strict(),
    z.object({
        image: z.object({
            reference: z.string().min(1),
            pullPolicy: K8S_IMAGE_PULL_POLICY.default("IfNotPresent").optional()
        }).strict()
    }).strict(),
]);

const ARGO_FILE_SOURCE_VOLUME = z.object({
    name: z.string().min(1),
}).and(ARGO_FILE_SOURCE_VOLUME_SOURCE);

const ARGO_FILE_SOURCE_VOLUME_MOUNT = z.object({
    name: z.string().min(1),
    mountPath: z.string().min(1),
    readOnly: z.literal(true).default(true).optional()
}).strict();

const ARGO_FILE_SOURCE_MOUNTS = z.object({
    fileSourceVolumes: z.array(ARGO_FILE_SOURCE_VOLUME).default([]).optional(),
    fileSourceVolumeMounts: z.array(ARGO_FILE_SOURCE_VOLUME_MOUNT).default([]).optional()
}).strict();
```

Each compute resource should receive its own translated volumes and mounts:

```json
{
  "trafficReplays": {
    "replay": {
      "fileSourceVolumes": [
        {
          "name": "file-source-configmap-prod-solr-config",
          "configMap": {
            "name": "prod-solr-config"
          }
        }
      ],
      "fileSourceVolumeMounts": [
        {
          "name": "file-source-configmap-prod-solr-config",
          "mountPath": "/file-sources/configmap-prod-solr-config",
          "readOnly": true
        }
      ]
    }
  }
}
```

The config processor derives both arrays from the same per-component file-source registry. This avoids asking workflow templates to map descriptor objects into Kubernetes volumes at Argo runtime; the current Argo TS helpers do not have a convenient runtime array-map primitive.

The Argo TS model still needs one generic array-concat/rendering helper so templates can append runtime-provided arrays to static container fields. This is needed for the current template styles:

- Replayer and RFS build raw Kubernetes Deployment manifests.
- Capture Proxy builds a raw Kubernetes Deployment manifest.
- Metadata migration uses `ContainerBuilder.addVolumesFromRecord`.

The helper should support the same data model in all three places: append `fileSourceVolumes` to `volumes` and append `fileSourceVolumeMounts` to `container.volumeMounts`. These fields are workflow-only and must be included in each component's workflow-option key list so they are omitted from the Java process JSON.

The generated Kubernetes pieces are already fully rendered:

```yaml
volumeMounts:
  - name: file-source-configmap-prod-solr-config
    mountPath: /file-sources/configmap-prod-solr-config
    readOnly: true
volumes:
  - name: file-source-configmap-prod-solr-config
    configMap:
      name: prod-solr-config
```

Templates for MetadataMigration, RFS, Replayer, and Capture Proxy should all use the same helper. The transformed component config should already contain resolved paths such as `/file-sources/configmap-prod-solr-config/solrconfig.xml`.

## Runtime-Generated File Sources

Some future flows may extract configuration from a snapshot and feed that file into a downstream component. The preferred workflow shape is a deterministic generated resource name:

```text
extract-source-config -> validate-source-config-configmap -> deploy-replayer
```

The config processor can precompute the expected ConfigMap name and mount path:

```text
/file-sources/generated-source-config/cluster-config.xml
```

The producer step creates the ConfigMap with that known name. The validator step checks that the ConfigMap exists and contains required keys. The downstream deployment depends on the validator.

Kubernetes ConfigMaps do not have a useful readiness status by themselves, so readiness is expressed through workflow dependencies and validation steps. The named resource is the handoff point; the workflow determines whether that handoff is complete.

This flow is for workflow-produced files, not user inline contents. Inline user content should go through value/script fields because it does not need Argo to create, mount, and clean up an intermediate Kubernetes resource.

If the resource name cannot be known until runtime, the producer step can emit a small JSON object containing `fileSourceVolumes` and `fileSourceVolumeMounts`, and the deploy step can append those arrays through the same generic path. Prefer deterministic names where possible because the downstream config can then contain resolved paths before the workflow starts.

For large generated content, use a PVC. For immutable heavy content that should be shared across runs, package it as an OCI artifact.

## Inline Convenience Values

Removing generic `FILE_REF.contents` does not mean every convenience path must be a ConfigMap or image. It means inline content should be added only where the consuming runtime can use a value directly.

For example:

- Inline JavaScript uses `entryPoint.javascript`.
- Inline transform settings use `context.values.<name>.value`.
- Inline mTLS trust roots can be a proxy TLS field because the proxy owns TLS initialization.
- Inline source-cluster configuration can be a source-cluster value because later config-processor work can decide how to pass that logical value to transform providers.

This avoids a generic Argo resource factory for arbitrary strings. If a later component truly needs a path, the config processor extension for that component can materialize a managed ConfigMap or require the user to provide a file source.

## Source-Cluster Configuration Files

The current `CLUSTER_VERSION_STRING` already accepts `SOLR 6`, `SOLR 7`, `SOLR 8`, and `SOLR 9` version strings. Keep that support and add a generic configuration section to `SOURCE_CLUSTER_CONFIG`:

```typescript
const SOURCE_CLUSTER_INLINE_VALUE = z.object({
    value: z.string().min(1),
    readAs: TRANSFORM_CONTEXT_READ_AS.default("text").optional()
}).strict();

const SOURCE_CLUSTER_CONFIGURATION = z.object({
    files: z.record(z.string().min(1), FILE_REF).default({}).optional(),
    values: z.record(z.string().min(1), SOURCE_CLUSTER_INLINE_VALUE).default({}).optional()
}).strict();

export const SOURCE_CLUSTER_CONFIG = CLUSTER_CONFIG.extend({
    version: CLUSTER_VERSION_STRING,
    clusterConfiguration: SOURCE_CLUSTER_CONFIGURATION.optional(),
    snapshotInfo: SNAPSHOT_INFO.optional()
});
```

Validation rules:

- The mount and workflow layers do not interpret file names.
- Solr documentation and Solr-aware components define which keys inside `clusterConfiguration.files` and `clusterConfiguration.values` they understand.
- Initial validation can allow `clusterConfiguration` only for `SOLR ...` sources because that is the first concrete consumer. The schema shape itself should stay generic so other source engines can reuse it later.
- Components that receive this source config must also receive the corresponding `fileSourceVolumes` and `fileSourceVolumeMounts` for file-backed entries.
- Relative path validation rejects absolute paths and `..` traversal.

The user-facing source-cluster block is the source of truth. It carries both the file source and the relative path for every file-backed entry:

```yaml
sourceClusters:
  prod-solr:
    version: "SOLR 8.11.4"
    endpoint: "https://solr.example.com:8983"
    clusterConfiguration:
      files:
        clusterConfig:
          configMap: prod-solr-config
          path: solrconfig.xml
        defaults:
          image: "123456789012.dkr.ecr.us-east-1.amazonaws.com/source-config@sha256:abc123"
          pullPolicy: IfNotPresent
          path: solr/defaults.json
      values:
        clusterConfigText:
          value: |
            <config>...</config>
          readAs: text
```

File entry rules:

- Each key under `clusterConfiguration.files` is a logical source-cluster config name.
- Each file entry includes exactly one file source, such as `configMap` or `image`.
- `path` is always relative to that file source.
- For a ConfigMap source, `path` names one ConfigMap key. In v1 this is a single key/file name, not a nested path.
- For an image source, `path` names the file inside the mounted image filesystem.
- Inline entries use `clusterConfiguration.values`, not `clusterConfiguration.files`.

A later projection step turns these declarations into component-specific resolved paths plus `fileSourceVolumes` and `fileSourceVolumeMounts`. For example, a consumer that needs `prod-solr.clusterConfiguration.files.clusterConfig` would receive both a resolved path under its local mount root and generated Kubernetes volume entries that identify `prod-solr-config` as the backing ConfigMap. That projected shape is not the source-cluster schema; it is derived from this block.

The same pattern applies to image-backed source config: the source-cluster block carries the image reference, optional pull policy, and relative path. The projection chooses the component-local mount root and constructs the resolved path. Inline `clusterConfiguration.values` do not create mounts.

For Solr, the first documented source-cluster keys can be `clusterConfig` and `defaults` if those are the source-cluster documentation contracts we settle on. The workflow does not need to know that `clusterConfig` is a Solr config file or that `defaults` has Solr request-handler meaning. It only preserves named file declarations and named inline values under the source cluster.

Transform provider config keys are separate from source-cluster documentation keys. For example, `TypeMappingSanitizationTransformerProvider` consumes provider keys such as `regexMappings`, `staticMappings`, `featureFlags`, and `sourceProperties`; those names do not define or constrain source-cluster keys such as `clusterConfig` or `defaults`.

This design does not automatically copy `clusterConfiguration.values` into transform context. A follow-up config-processor extension can do that explicitly. If a provider accepts source config as text or JSON, the extension can inject it as `context.values.<name>.value`. If a provider requires a path, that extension can materialize a managed ConfigMap and inject `context.values.<name>.fromFile`.

Snapshot-based metadata and RFS can continue reading schema/configset material from Solr backup data when it is present. If a future runtime component needs another Solr file, that should be a Solr documentation/provider concern: add another named entry under `clusterConfiguration.files`, not a new workflow field.

## Transformer Runtime

The transformer config string remains the outer Java contract. Existing code resolves `--transformerConfig`, `--transformerConfigEncoded`, and `--transformerConfigFile` into one JSON string. The new behavior starts after `TransformationLoader` parses that JSON.

For each provider config:

```java
var rawConfiguration = c.get(providerName);
var configuration = ProviderConfigResolver.resolve(provider, rawConfiguration);
return provider.createTransformer(configuration);
```

If `rawConfiguration` is not an object, such as a string context for a named provider, the resolver returns it unchanged.

The resolver must run in `TransformationLoader`, before provider-specific `createTransformer()` logic. That keeps existing providers from reading files directly and lets script providers receive a normal `bindingsObject`. Current `ScriptTransformerProvider` already accepts `bindingsObject` as either a JSON object or a JSON string, so v2 lowering can produce an object after resolving file-backed bindings while old configs that pass a string continue to work.

The resolver should understand reserved config-loading keys:

- `providerConfigDirs`: directories loaded as shallow key-value bags.
- `providerConfigFiles`: map of provider config key to `{path, readAs?}`.
- `bindingsObjectDirs`: script-provider equivalent that merges directory values into `bindingsObject`.
- `bindingsObjectFiles`: script-provider equivalent that merges named files into `bindingsObject`.

Named providers can optionally expose expected config value types through a transformation-layer interface:

```java
public interface TypedTransformerConfigProvider extends IJsonTransformerProvider {
    Map<String, ConfigValueSpec> getConfigValueSpecs();
}

public record ConfigValueSpec(ConfigValueType readAs, boolean required) {}

public enum ConfigValueType {
    JSON,
    TEXT,
    BYTES,
    BASE64,
    PATH
}
```

`TransformationLoader` should pass the provider's specs into `ProviderConfigResolver`. This keeps type interpretation in the transformation library, where provider contracts are known, and keeps workflow rendering limited to mounts, paths, and resolver metadata.

Merge order:

1. Load value directories.
2. Load named file values.
3. Apply explicit provider config keys.
4. For script providers, merge resolved binding values into any existing `bindingsObject`, with explicit `bindingsObject` values winning.

Directory loading should be constrained in v1:

- Load only immediate files.
- Reject nested directories unless a later provider needs a manifest format.
- Determine each file's type from provider metadata or `defaultReadAs`.
- Remove known extensions such as `.json` and `.txt` when deriving config keys.
- Fail if no expected type is available for a file-backed value.

This keeps providers such as `TypeMappingSanitizationTransformerProvider` focused on typed config consumption. They should not read env vars or files directly.

Example generated transformer config:

```json
[
  {
    "JsonJSTransformerProvider": {
      "initializationScriptFile": "/file-sources/image-9d105c/request.js",
      "bindingsObject": {
        "mode": "strict"
      },
      "bindingsObjectFiles": {
        "sourceDefaults": {
          "path": "/file-sources/configmap-prod-solr-config/defaults.json",
          "readAs": "json"
        }
      }
    }
  },
  {
    "TypeMappingSanitizationTransformerProvider": {
      "providerConfigDirs": [
        "/file-sources/configmap-type-mappings-settings"
      ],
      "providerConfigFiles": {
        "regexMappings": {
          "path": "/file-sources/image-a81c2f/regexMappings.json",
          "readAs": "json"
        }
      },
      "sourceProperties": {
        "version": {
          "major": 7,
          "minor": 10
        }
      }
    }
  }
]
```

## Capture Proxy mTLS Trust Roots

The capture proxy Java process already has runtime knobs for front-side mTLS:

- `--sslTrustCertFile`
- `--requireClientAuth`

The file-backed path needs schema/workflow support for mounting client root CAs and passing the resolved file path. Inline PEM roots should not use generic file materialization; the proxy runtime should accept the PEM value directly through an environment variable.

Extend TLS variants that terminate HTTPS:

```typescript
const PROXY_TRUST_ROOTS_CONFIG = z.union([
    FILE_REF,
    z.object({
        pem: z.string().min(1)
    }).strict()
]);

const PROXY_CLIENT_AUTH_CONFIG = z.object({
    mode: z.enum(["require"]).default("require").optional(),
    trustRoots: PROXY_TRUST_ROOTS_CONFIG
}).strict();
```

Example:

```yaml
traffic:
  proxies:
    capture:
      source: prod-solr
      proxyConfig:
        tls:
          mode: certManager
          issuerRef:
            name: migrations-ca
          dnsNames:
            - capture.ma.svc.cluster.local
          clientAuth:
            mode: require
            trustRoots:
              configMap: prod-client-root-cas
              path: client-ca.pem
```

Inline PEM example:

```yaml
traffic:
  proxies:
    capture:
      source: prod-solr
      proxyConfig:
        tls:
          mode: certManager
          issuerRef:
            name: migrations-ca
          dnsNames:
            - capture.ma.svc.cluster.local
          clientAuth:
            mode: require
            trustRoots:
              pem: |
                -----BEGIN CERTIFICATE-----
                ...
                -----END CERTIFICATE-----
```

Workflow translation:

- Mount `prod-client-root-cas` into the proxy pod.
- Add `sslTrustCertFile: /file-sources/configmap-prod-client-root-cas/client-ca.pem` to the proxy config.
- For inline PEM, set a generated environment variable such as `CAPTURE_PROXY_CLIENT_CA_PEM` and pass a proxy config field such as `sslTrustCertPemEnvVar: CAPTURE_PROXY_CLIENT_CA_PEM`.
- Add `requireClientAuth: true` when `clientAuth.mode` is `require`.
- Reject `clientAuth` when `tls.mode` is `plaintext`.

Capture Proxy currently accepts `--sslTrustCertFile`, so inline PEM needs a small runtime addition such as `--sslTrustCertPemEnvVar`. The runtime should read the PEM from the named environment variable and build the trust manager from memory. Passing the PEM through an env var is reasonable here because client root CAs are public trust material. Do not use this pattern for server private keys; those should remain on the existing TLS Secret path.

Public CA roots can live in a ConfigMap, OCI image, or inline PEM. Server private keys should remain in Kubernetes Secrets.

## End-To-End Example

```yaml
sourceClusters:
  prod-solr:
    version: "SOLR 8.11.4"
    endpoint: "https://solr.example.com:8983"
    clusterConfiguration:
      files:
        clusterConfig:
          configMap: prod-solr-config
          path: solrconfig.xml
        defaults:
          configMap: prod-solr-config
          path: defaults.json

targetClusters:
  prod-os:
    endpoint: "https://target.example.com:9200"

traffic:
  proxies:
    capture:
      source: prod-solr
      proxyConfig:
        tls:
          mode: certManager
          issuerRef:
            name: migrations-ca
          dnsNames:
            - capture.ma.svc.cluster.local
          clientAuth:
            trustRoots:
              configMap: prod-client-root-cas
              path: client-ca.pem
  replayers:
    replay:
      fromProxy: capture
      toTarget: prod-os
      replayerConfig:
        requestTransforms:
          - transformName: TypeMappingSanitizationTransformerProvider
            context:
              valueDirectories:
                - configMap: type-mappings-settings
              values:
                sourceProperties:
                  value:
                    version:
                      major: 7
                      minor: 10
                regexMappings:
                  fromFile:
                    image: "123456789012.dkr.ecr.us-east-1.amazonaws.com/type-mapping-context@sha256:abc123"
                    path: regexMappings.json
          - entryPoint:
              javascript: |
                function transformJson(obj) {
                  return obj;
                }
            context:
              values:
                mode:
                  value: strict
          - entryPoint:
              javascriptFile:
                image: "123456789012.dkr.ecr.us-east-1.amazonaws.com/replay-transforms@sha256:abc123"
                path: request.js
            context:
              values:
                sourceDefaults:
                  fromFile:
                    configMap: prod-solr-config
                    path: defaults.json
```

This example intentionally repeats `prod-solr-config`. The config processor dedupes that source in the replayer and still preserves distinct resolved paths for `solrconfig.xml` and `defaults.json`.

## Implementation Order

1. Add direct `FILE_REF` schemas to `orchestrationSpecs/packages/schemas/src/userSchemas.ts`.
2. Remove `transformsSources` and per-component `transformsSource` from the accepted user schema.
3. Add transform spec v2 with mutually exclusive `entryPoint | transformName` and shared optional `context`.
4. Add config transformation for inline scripts, file-backed scripts, value directories, literal values, and `fromFile` values.
5. Add a file-source registry in the config processor that canonicalizes and dedupes mounts per compute component.
6. Add transformed Argo fields `fileSourceVolumes` and `fileSourceVolumeMounts` to MetadataMigration, RFS, Replayer, and Capture Proxy configs.
7. Add those fields to each component's workflow-option key list so they are omitted from Java process parameter JSON.
8. Add the Argo TS array-concat/rendering helper that appends `fileSourceVolumes` to static `volumes` and `fileSourceVolumeMounts` to static `volumeMounts`.
9. Add deterministic runtime-produced resource support for workflow-generated files.
10. Add generic `clusterConfiguration.files` and `clusterConfiguration.values` to source clusters. `CLUSTER_VERSION_STRING` already accepts Solr versions in this tree; preserve that validation.
11. Add Java provider config value metadata so named providers can declare expected `readAs` types.
12. Add Java `ProviderConfigResolver` support for provider config files, directories, and typed value materialization.
13. Add capture proxy `clientAuth.trustRoots` schema and workflow translation for both file-backed roots and inline PEM roots.
14. Add capture proxy runtime support for `sslTrustCertPemEnvVar` or equivalent.
15. Update samples and docs from mountable transforms to direct file-source declarations.

## Testing Plan

Add focused tests for schema, transformation, workflow rendering, and Java runtime resolution:

- Schema accepts `FILE_REF` `configMap` and `image` forms everywhere a file-bearing field is allowed.
- Schema rejects absolute paths and `..` path traversal.
- Schema rejects ConfigMap file refs whose `path` contains `/` or reserved dot paths; ConfigMap file refs address one key.
- Schema rejects transform specs with both `entryPoint` and `transformName`, or neither.
- Transform translation maps inline JavaScript to `initializationScript`.
- Transform translation maps `javascriptFile` to `initializationScriptFile`.
- Transform translation maps inline Python and `pythonFile` to `JsonPythonTransformerProvider` with the same `initializationScript` / `initializationScriptFile` keys.
- Transform translation maps `context.values.foo.value` into a provider config value.
- Transform translation maps `context.values.foo.fromFile` into a resolver instruction plus a resolved mounted path and optional `readAs`, not an inlined value.
- Java transformer runtime test verifies reserved resolver keys are removed before provider creation and that providers receive the final flattened config object.
- Java transformer runtime test verifies provider-declared expected types are used when `readAs` is omitted.
- Java transformer runtime test verifies an explicit `readAs` conflict with provider metadata fails before provider creation.
- Java transformer runtime test verifies `json`, `text`, `bytes`, `base64`, and `path` value types.
- Java transformer runtime test verifies file-backed values without provider metadata or explicit `readAs` fail rather than being guessed from extension.
- Config transformation dedupes identical ConfigMap refs into one component-level `fileSourceVolumes` / `fileSourceVolumeMounts` pair while preserving each resolved file path.
- Config transformation dedupes identical image refs and treats different references or pull policies as separate mounts.
- Component process JSON omits `fileSourceVolumes` and `fileSourceVolumeMounts`; they are workflow-only fields.
- Replayer workflow snapshot with source-cluster config and transform context from the same ConfigMap renders one volume and one mount for that ConfigMap.
- Workflow snapshot with two distinct ConfigMaps renders two volumes and two mounts.
- Cross-component workflow snapshot verifies dedupe is per pod.
- Argo helper tests verify file-source volume arrays append correctly for raw Deployment templates and `ContainerBuilder` templates.
- Generated ConfigMap flow test verifies an extraction step creates a deterministic ConfigMap, a validator gates readiness, and the downstream replayer mounts the expected name.
- Capture proxy mTLS rendering test verifies `clientAuth.trustRoots` from a ConfigMap sets `sslTrustCertFile` and `requireClientAuth`.
- Capture proxy mTLS rendering test verifies inline PEM trust roots set an env var, pass `sslTrustCertPemEnvVar`, and do not create a mount.
- Capture proxy runtime test verifies inline PEM trust roots are loaded from the env var and enable required client auth.
- Source-cluster config test verifies inline `clusterConfiguration.values` are preserved in transformed source config and do not create mounts.
- Java transformer runtime test verifies `ProviderConfigResolver` loads JSON files, text files, and shallow directories in the defined merge order.
- Naked-container transformer test verifies inline JavaScript and inline context values work with no Kubernetes mounts.

## Migration From Mountable Transforms

Existing transform configs should move directly to the file-source shape:

```yaml
traffic:
  replayers:
    replay:
      replayerConfig:
        requestTransforms:
          - entryPoint:
              javascriptFile:
                image: "repo/transforms@sha256:..."
                path: request.js
```

If the same image is reused, users can repeat the direct image reference or use YAML anchors. The config processor dedupes identical sources within each component, so users do not need to invent aliases solely to avoid duplicate mounts.

## Resolved Decisions

**Value directory key derivation**: Keep v1 simple. `context.valueDirectories` derives each context key from the immediate file name, with known extensions such as `.json` or `.txt` removed. Users who need more control can cherry-pick individual values with `context.values.<name>.fromFile` or `context.values.<name>.value`.

**ConfigMap usage**: ConfigMaps can be used in two ways in this design. A `FILE_REF` can point to one ConfigMap key/file and produce a mounted path; in v1 this is one key name, not a nested path. `context.valueDirectories[]` can mount a ConfigMap as a directory where each key/file becomes one context value. Env-var-based ConfigMap key reads are intentionally not part of the v1 transform-context model.

**Environment variables**: Do not add a generic env-var value source for file bundles or transform context in v1. The current workflows already model the needed env vars directly: credentials from Secrets, runtime JVM/logging/localstack settings, and config-processor invocation settings. New env vars should be added as explicit component fields when a runtime actually needs them.

**Inline file materialization**: Generic `FILE_REF.contents` remains out of scope. Inline content should stay value/script-only through fields such as `entryPoint.javascript`, `context.values.<name>.value`, `clusterConfiguration.values`, and inline proxy trust-root PEM. If a runtime truly needs a path, use a ConfigMap, OCI image, workflow-produced resource, or builder-owned config-processor extension.

**Image pull policy**: Image file sources should accept `pullPolicy` and default to `IfNotPresent`. Pull policy remains part of the canonical image source identity because it changes the Kubernetes volume spec. Two otherwise identical image references with different pull policies should therefore produce distinct component mounts.
