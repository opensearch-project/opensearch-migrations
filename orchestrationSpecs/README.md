# Migration Orchestration Workflow Template Specifications

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
kc delete workflows `kc get workflow 2>&1 | tail -n +2  | grep -v "No resources"  | cut -f 1 -d \  ` ; \
kc delete workflowtemplates `kc get workflowtemplates 2>&1 | tail -n +2  | grep -v "No resources"  | cut -f 1 -d \  ` ; \
npm run make-templates -- --outputDirectory ${PWD}/k8sResources && \
kc create -f k8sResources && 
./packages/config-processor/bundled/createMigrationWorkflowFromUserConfiguration.sh ./packages/config-processor/scripts/sampleMigration.wf.yaml --etcd-endpoints http://localhost:2379
```

I'll add something to handle `kc create -f createMigration.yaml` once I wire up
the config processor script.