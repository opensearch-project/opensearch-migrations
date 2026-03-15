# Migration Orchestration Workflow Template Specifications

## Package Structure

```
orchestrationSpecs/packages/
├── crd-type-generator/          devDependency only — shared script for generating TS types from CRD schemas
├── k8s-types/                   Generated plain TS interfaces for core K8s resources (Pod, ConfigMap, etc.)
├── argo-types/                  Generated plain TS interfaces for Argo Workflows CRDs (Workflow, WorkflowTemplate, etc.)
├── strimzi-types/               Generated plain TS interfaces for Strimzi Kafka CRDs (Kafka, KafkaTopic, etc.)
├── schemas/                     Zod schemas for user-facing migration config; wraps strimzi-types in Zod
├── argo-workflow-builders/      Builder library for constructing Argo Workflows; depends on k8s-types + argo-types
├── migration-workflow-templates/ Full migration workflow definitions; depends on argo-workflow-builders + schemas + strimzi-types
└── config-processor/            CLI tool that transforms user config → K8s resources and submits the workflow
```

### Dependency graph

```
crd-type-generator (devDep only)
    ↑ devDep          ↑ devDep        ↑ devDep
 k8s-types         argo-types      strimzi-types
    ↑                  ↑               ↑          ↑
argo-workflow-builders            schemas    migration-workflow-templates
         ↑                           ↑
         └──────── migration-workflow-templates
                            ↑
                    config-processor
```

The three CRD type packages (`k8s-types`, `argo-types`, `strimzi-types`) contain only generated TypeScript interfaces — no Zod, no runtime dependencies. Run `npm run rebuild` inside any of them (requires `kubectl` pointed at a cluster with the relevant operators) to refresh types after an upgrade.

## Usage

### Templates

To build and view the workflow templates without posting them to the kubernetes cluster, run

`npm run make-templates -- --createResources false --show`

To build the workflow templates AND create those templates on the kubernetes cluster, run

`npm run make-templates -- --createResources true`

`--show` will dump the contents informally to the console, which can be used 
either when createResources is true or false.

### Schema

To generate the user-facing configuration that's used throughout the 
full-migration workflow, run

`npm run schema`

The migration config is validated against a single unified JSON Schema.  That
schema starts with the orchestration config shape from
`packages/schemas/src/userSchemas.ts`, then splices in selected Strimzi schema
fragments for the Kafka pass-through sections under
`kafkaClusterConfiguration.<name>.autoCreate`.

The merge intentionally pulls in only Strimzi `spec` fragments that users are
allowed to control:

- `Kafka.spec` via `clusterSpecOverrides`
- `KafkaNodePool.spec` via `nodePoolSpecOverrides`
- `KafkaTopic.spec` via `topicSpecOverrides`

The workflow still owns names, labels, annotations, and connectivity/auth
requirements that the proxy, replayer, and console components depend on.  The
goal is "real Strimzi-shaped validation where users are allowed to configure
Strimzi" without letting user config drift into fields that would break the
rest of the migration stack.

#### How the merge works

The unified schema builder lives in
[packages/schemas/src/unifiedSchemaBuilder.ts](/Users/schohn/dev/m2/orchestrationSpecs/packages/schemas/src/unifiedSchemaBuilder.ts).
At a high level it does the following:

1. Generates the base migration configuration JSON Schema from the
   orchestration config model.
2. Loads Strimzi schema fragments from an OpenAPI/JSON Schema input, if one is
   provided.
3. Extracts the specific Strimzi definitions that correspond to
   `Kafka.spec`, `KafkaNodePool.spec`, and `KafkaTopic.spec`.
4. Re-homes those definitions under the unified schema's `$defs`.
5. Rewrites the orchestration config properties for the Kafka pass-through
   sections so that they reference those Strimzi-derived definitions.
6. Emits one JSON Schema artifact that tools, editors, agents, and runtime
   validation can all use.

That builder is exposed through:

```shell
npm run -w @opensearch-migrations/schemas build-unified-schema -- \
  --output /path/to/workflowMigration.schema.json
```

When a live Strimzi schema is available, pass it explicitly:

```shell
npm run -w @opensearch-migrations/schemas build-unified-schema -- \
  --strimzi-openapi /path/to/kafka.strimzi.io-v1-schema.json \
  --output /path/to/workflowMigration.schema.json
```

#### Live CRDs vs fallback schema

The preferred source of truth for Strimzi-backed sections is the schema from
the actual Strimzi deployment that the release or environment is targeting.
The intended release/deployment flow is:

1. Deploy or upgrade the Helm charts that install Strimzi.
2. Read the live Kafka CRD/OpenAPI schema from that cluster.
3. Run `build-unified-schema` against those live definitions.
4. Save the resulting `workflowMigration.schema.json` as a release artifact.
5. Publish the same schema into the cluster in a ConfigMap so that runtime
   tools validate against the same contract.

That gives one schema that matches the Strimzi version actually present in the
environment, instead of assuming that the checked-in TypeScript types or an
older generated schema are still correct.

For local development and bootstrapping, there is also a checked-in fallback
artifact at
[packages/schemas/generated/workflowMigration.schema.json](/Users/schohn/dev/m2/orchestrationSpecs/packages/schemas/generated/workflowMigration.schema.json).
That fallback is intentionally generic in the Strimzi sections.  It exists so
that developers can:

- inspect the overall config shape
- generate samples
- run editor tooling before a cluster is available
- bootstrap tests that do not require full Strimzi fidelity

Because that generic fallback does not provide full CRD-level validation,
runtime validation treats it as a degraded mode.  By default, the runtime
validator errors when only the generic schema is available.  Developers can
opt into that degraded mode by setting:

- `ALLOW_GENERIC_UNIFIED_SCHEMA=true`

That environment variable should be used only for local development, tests, or
early bootstrapping.  It is not the desired release path.

#### Where the runtime schema comes from

The runtime validator looks for the unified schema in this order:

1. A file path from `MIGRATION_UNIFIED_SCHEMA_PATH`
2. A ConfigMap reference from:
   - `MIGRATION_UNIFIED_SCHEMA_CONFIGMAP`
   - `MIGRATION_UNIFIED_SCHEMA_NAMESPACE`
   - `MIGRATION_UNIFIED_SCHEMA_KEY`
3. The checked-in fallback artifact in `packages/schemas/generated`

The config processor writes that schema into the migration bundle as a
ConfigMap named `migration-configuration-schema`, and
`createMigrationWorkflowFromUserConfiguration.sh` applies it alongside the
other generated resources.  The intent is for `migrationInitializer`,
`migrationConfigTransformer`, CLI-driven validation, and any future editor or
agent integrations to all consume the same unified artifact.

#### How the fallback is maintained

The fallback schema is not meant to be hand-edited.  It should be regenerated
with the unified schema builder and checked in as an artifact.  In practice:

- release automation should prefer a schema generated from live Strimzi CRDs
- local development can regenerate the generic artifact with `--generic`
- if the orchestration config model changes, regenerate the fallback artifact
  so the checked-in schema continues to match the current config shape

Today the generic fallback can be refreshed with:

```shell
npm run -w @opensearch-migrations/schemas build-unified-schema -- --generic
```

That command updates the checked-in fallback artifact but does not replace the
preferred release-time step of generating a schema from live CRDs.

#### Why this is one merged schema instead of bespoke validation logic

The reason for building one merged schema is that it becomes a portable
contract:

- IDEs can use it for completion and validation
- CI can validate configs against it directly
- release tooling can publish it as an artifact
- agents can read the same schema users validate against
- runtime tools can fail fast before partially applying invalid Kafka config

That is more useful than "some fields validated by Zod, some fields validated
by custom code, some fields validated only by Strimzi after deployment."

The runtime validator can be pointed at a schema artifact or cluster ConfigMap
via:

- `MIGRATION_UNIFIED_SCHEMA_PATH`
- `MIGRATION_UNIFIED_SCHEMA_CONFIGMAP`
- `MIGRATION_UNIFIED_SCHEMA_NAMESPACE`
- `MIGRATION_UNIFIED_SCHEMA_KEY`

To generate a sample yaml file from the schema, including descriptions
of fields and the types of the scalars, run

```shell
npm run make-sample
```

That will create something that looks like 
```
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
      ##  The AWS region that the bucket reside in (us-east-2, etc)
      #awsRegion: string
      ##  Override the default S3 endpoint for clients to connect to.  Necessary for testing, when S3 isn't used, or when it's only accessible via another endpoint
      #endpoint: string
      ##  s3:///BUCKETNAME/PATH
      #s3RepoPathUri: string
    proxy:
      #loggingConfigurationOverrideConfigMap: string
      #otelCollectorEndpoint: http://otel-collector:4317
targetClusters:
  <NAME>:
...
```

### Kafka Settings

`kafkaClusterConfiguration.<cluster>.autoCreate` accepts Strimzi-shaped
pass-through sections that are merged into the generated resources:

- `clusterSpecOverrides` for `Kafka.spec`
- `nodePoolSpecOverrides` for `KafkaNodePool.spec`
- `topicSpecOverrides` for `KafkaTopic.spec`

These sections are validated by the unified JSON Schema, using Strimzi-derived
definitions when a live or release-built Strimzi schema is available.  The
workflow still owns the resource names and Kafka access contract.  In
particular, workflow-managed listeners, auth wiring, and other invariants may
be overwritten so that proxy, replayer, and console connectivity remains
stable.

For `kafkaClusterConfiguration.<cluster>.existing`, the user should provide the
explicit connection and auth material that migration applications must use.  In
particular, existing Kafka clusters should use an explicit auth block rather
than relying on the workflow to infer secret names.

### Updating template and configuration models

In addition to the commands above to generate the templates and schema,
one should run type checking with npm.

`npm run type-check`

Notice that the type-check script uses `tsgo`, a new TypeScript compiler 
implementation that was written in Go.  It's about 200x faster and can go
much deeper into recursive template resolution stacks.  Without that, 
type-checking may yield errors and complaints because it wasn't able to 
fully define all the types, causing some to become `any` and cause other 
type controls to fail.

`npx jest` will run the (limited) tests that we have at the moment.

## Argo-Workflows Builder 

For more information on how to use the builder library to construct 
Argo Workflows, see the [Argo Builder Library README](./packages/argo-workflow-builders/README.md).

## Quick Loading Workflows into Argo

from the root orchestrationSpecs directory

```shell
rm k8sResources/*yaml ; \
npm run make-config-processor-bundle && \
npm run make-templates -- --outputDirectory ${PWD}/k8sResources && \
for file in k8sResources/*.yaml; do kc delete -f "$file" --ignore-not-found=true; done && \
kubectl create -f k8sResources && \
kubectl delete workflow migration-workflow ; \
./packages/config-processor/bundled/createMigrationWorkflowFromUserConfiguration.sh ./packages/config-processor/scripts/samples/proxyWithoutTls.wf.yaml
```

I'll add something to handle `kc create -f createMigration.yaml` once I wire up
the config processor script.
