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
npm run -w packages/config-processor bundle && \
npm run make-templates -- --outputDirectory ${PWD}/k8sResources && \
for file in k8sResources/*.yaml; do kc delete -f "$file" --ignore-not-found=true; done && \
kubectl create -f k8sResources && \ 
kubectl delete workflow migration-workflow ; \
./packages/config-processor/bundled/createMigrationWorkflowFromUserConfiguration.sh ./packages/config-processor/scripts/sampleMigration.wf.yaml
```

I'll add something to handle `kc create -f createMigration.yaml` once I wire up
the config processor script.