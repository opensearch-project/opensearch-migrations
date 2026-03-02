# crd-type-generator

Shared utility for generating TypeScript type definitions from Kubernetes CRD OpenAPI schemas fetched from a live cluster.

## What it does

Exports a single `generateTypes()` function that:
1. Reads OpenAPI v3 JSON schema files from a local directory (fetched via `kubectl get --raw`)
2. Compiles them to TypeScript interfaces using `json-schema-to-typescript`
3. Appends clean type aliases for the requested resource kinds

## Usage

This package is a **devDependency only** — it is never a runtime dependency. Each CRD type package (`k8s-types`, `argo-types`, `strimzi-types`) has a thin `scripts/generate.ts` that imports and calls `generateTypes()` with its own resource list.

To regenerate types in a type package, run from that package's directory:

```shell
npm run rebuild
```

This runs `fetchSchemas.sh` (which requires `kubectl` pointed at a cluster with the relevant operators installed) followed by `generate.ts`.

## API

```typescript
import { generateTypes } from '@opensearch-migrations/crd-type-generator';

await generateTypes({
    schemaDir: './k8sSchemas',       // directory containing fetched JSON schema files
    outputFile: './src/index.ts',    // where to write the generated TypeScript
    bannerComment: '/* ... */',      // optional, defaults to '/* Generated CRD type definitions */'
    resources: [
        { apiVersion: 'apps/v1', kind: 'Deployment' },
    ],
    additionalTypes: [               // optional sub-types to alias
        { name: 'PodSpec', schemaKey: 'io.k8s.api.core.v1.PodSpec' },
    ],
});
```

Schema files are named by convention: `v1` → `core-v1-schema.json`, `apps/v1` → `apps-v1-schema.json`, `argoproj.io/v1alpha1` → `argoproj.io-v1alpha1-schema.json`, etc.
