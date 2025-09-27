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
Argo Workflows, see the [Argo Builder Library README](./src/argoWorkflowBuilders/README.md) 