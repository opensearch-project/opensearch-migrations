# Migration Orchestration Workflow Template Specifications

This package contains the TypeScript-based source for Argo workflow templates, the user-facing workflow schema, and the config-processing utilities that turn migration configuration into runnable workflow inputs.

## What Lives Here

- `packages/migration-workflow-templates/`: workflow template definitions
- `packages/argo-workflow-builders/`: typed DSL for constructing Argo resources
- `packages/schemas/`: user-facing config schema and defaults
- `packages/config-processor/`: transforms user config into workflow-ready config and helper ConfigMaps

If you are an agent or developer trying to understand “what actually runs,” start here before editing workflow docs elsewhere in the repo.

## Usage

### Templates

To build and view the workflow templates without posting them to the Kubernetes cluster:

```bash
npm run make-templates -- --createResources false --show
```

To build the workflow templates and create those templates on the Kubernetes cluster:

```bash
npm run make-templates -- --createResources true
```

`--show` dumps the rendered templates to stdout and can be used with either mode.

### Schema

To generate the user-facing configuration used throughout the `full-migration` workflow:

```bash
npm run schema
```

To generate a sample YAML file from the schema, including descriptions and scalar types:

```bash
npm run make-sample
```

That produces a sample shaped like:

```yaml
sourceClusters:
  <NAME>:
    endpoint: string
    allowInsecure: boolean
    version: string
    authConfig:
      # Option 1 (object):
      ##basic:
        ##username: string
        ##password: string
      # Option 2 (object):
      ##sigv4:
        ##region: string
        ##service: string
      # Option 3 (object):
      ##mtls:
        ##caCert: string
        ##clientSecretName: string
    snapshotRepo:
      ## The AWS region that the bucket resides in (us-east-2, etc)
      #awsRegion: string
      ## Override the default S3 endpoint for testing or non-S3 object stores
      #endpoint: string
      ## s3:///BUCKETNAME/PATH
      #s3RepoPathUri: string
    proxy:
      #loggingConfigurationOverrideConfigMap: string
      #otelCollectorEndpoint: http://otel-collector:4317
targetClusters:
  <NAME>:
...
```

## Updating Templates and Configuration Models

After changing templates or schema definitions, run type-checking and tests:

```bash
npm run type-check
npx jest
```

`type-check` uses `tsgo`, a Go-based TypeScript compiler implementation that can resolve the deep type stacks in the workflow DSL more reliably than standard `tsc` for this repo.

## Agent / Operator Quick Path

These are the highest-signal entrypoints when you need to inspect or produce runnable workflow artifacts.

### Render templates locally

```bash
npm run make-templates -- --createResources false --outputDirectory ${PWD}/k8sResources
```

### Generate sample config

```bash
npm run make-sample
```

### Turn a user config into a runnable workflow bundle

```bash
./packages/config-processor/scripts/createMigrationWorkflowFromUserConfiguration.sh \
  ./packages/config-processor/scripts/sampleMigration.wf.yaml \
  --etcd-endpoints http://localhost:2379
```

That helper script:

1. generates a unique `uniqueRunNonce`
2. writes transformed workflow input into a temp directory
3. applies approval/concurrency ConfigMaps if present
4. creates an Argo `Workflow` that references the `full-migration` template

### Workflow naming behavior

By default, the helper creates a fixed workflow name:

```yaml
metadata:
  name: migration-workflow
```

If `USE_GENERATE_NAME=true`, it switches to:

```yaml
metadata:
  generateName: full-migration-<nonce>- 
```

Agent-facing docs and automation should call out this distinction explicitly, because monitoring and cleanup logic differ depending on whether the workflow name is fixed or generated.

## Quick Loading Workflows into Argo

From the root `orchestrationSpecs` directory:

```bash
rm k8sResources/*yaml ; \
npm run make-templates -- --outputDirectory ${PWD}/k8sResources && \
for file in k8sResources/*.yaml; do kc delete -f "$file" --ignore-not-found=true; done && \
kc create -f k8sResources && \
export USE_GENERATE_NAME=true && \
./packages/config-processor/bundled/createMigrationWorkflowFromUserConfiguration.sh ./packages/config-processor/scripts/sampleMigration.wf.yaml --etcd-endpoints http://localhost:2379
```

## Argo-Workflow Builder

For more information on the builder library used to construct these workflows, see the [Argo Builder Library README](./packages/argo-workflow-builders/README.md).
