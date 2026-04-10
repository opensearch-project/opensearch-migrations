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

For the end-to-end validation and transformation flow, including how the
console entrypoint, `MigrationConfigTransformer`, unified schema loading, and
Kafka/Strimzi validation fit together, see
[ConfigValidationFile.md](./ConfigValidationFlow.md).

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
[packages/schemas/src/unifiedSchemaBuilder.ts](packages/schemas/src/unifiedSchemaBuilder.ts).
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
5. Use that same schema artifact for validation in environments that target
   that Strimzi deployment.

That gives one schema that matches the Strimzi version actually present in the
environment, instead of assuming that older generated schema artifacts are
still correct.

#### Where the runtime schema comes from

The initializer and validator now prefer:

1. Live Strimzi CRD/OpenAPI schema fetched from the target cluster
2. A file path from `MIGRATION_UNIFIED_SCHEMA_PATH`
3. A generated local fallback artifact only when
   `MIGRATION_ALLOW_FALLBACK_UNIFIED_SCHEMA=true` is set

The default initialization path no longer generates or applies a schema
ConfigMap during workflow submission. Instead, it validates directly against
the live cluster schema when available and falls back only when explicitly
configured to do so.

#### How the fallback is maintained

The fallback schema is not meant to be hand-edited. It should be regenerated
with the unified schema builder as a local or release artifact. In practice:

- release automation should prefer a schema generated from live Strimzi CRDs
- local development can regenerate a fallback artifact from a local Strimzi
  cluster or another compatible OpenAPI source
- if the orchestration config model changes, regenerate the fallback artifact
  before using it again

Today a local fallback artifact can be generated with:

```shell
npm run -w @opensearch-migrations/schemas build-unified-schema -- \
  --strimzi-openapi /path/to/kafka.strimzi.io-v1-schema.json
```

That command writes a local fallback artifact. Release automation should still
prefer generating the schema directly from the live CRDs of the deployed
Strimzi version.

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

The runtime validator can be pointed at a schema artifact via:

- `MIGRATION_UNIFIED_SCHEMA_PATH`
- `MIGRATION_ALLOW_FALLBACK_UNIFIED_SCHEMA`

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

`kafkaClusterConfiguration.<cluster>.autoCreate` is intended to behave like the
other compound resource settings in migration config: the workflow provides
baseline defaults, the user specifies only the Kafka settings they care about,
and the final Kafka configuration is a deep merge of defaults plus overrides.

The baseline model is intended to track Strimzi `0.50`.  The primary user
inputs are:

- `auth` for the workflow-owned managed Kafka auth contract
- `clusterSpecOverrides` for `Kafka.spec`
- `nodePoolSpecOverrides` for `KafkaNodePool.spec`
- `topicSpecOverrides` for `KafkaTopic.spec`

These sections are validated by the unified JSON Schema after defaults and user
overrides are merged.  The preferred schema uses Strimzi-derived definitions
from the live or release-built target environment.  The workflow still owns the
resource names and Kafka access contract.  In particular, workflow-managed
listeners, auth wiring, and other invariants may be overwritten so that proxy,
replayer, and console connectivity remains stable.

For `kafkaClusterConfiguration.<cluster>.existing`, the user should provide the
explicit connection and auth material that migration applications must use.  In
particular, existing Kafka clusters should use an explicit auth block rather
than relying on the workflow to infer secret names.

For workflow-managed Kafka clusters, the orchestration layer now also carries a
normalized Kafka connection profile that includes runtime-resolved listener and
auth metadata, the workflow creates deterministic `KafkaUser` resources for
managed SCRAM clusters, and proxy/replayer now consume secret-backed SCRAM
client configuration from mounted Strimzi secrets and generated Kafka client
property files. The console services config in this repo now uses the shared
Kafka client schema as well, and the current Kafka manifest render path has
already been fixed to avoid the earlier quoted `Kafka.spec` serialization bug.
The remaining cleanup is around making initialization-time Kafka defaults the
single source of truth all the way through rendering.

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
./packages/config-processor/bundled/createMigrationWorkflowFromUserConfiguration.sh ./packages/config-processor/scripts/samples/fullMigrationWithTraffic.wf.yaml
```

I'll add something to handle `kc create -f createMigration.yaml` once I wire up
the config processor script.
