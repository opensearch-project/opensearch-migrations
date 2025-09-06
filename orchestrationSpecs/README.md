
# Type-Safe Argo Workflows with the TypeScript Builder Library

This HOWTO shows **how to write Argo Workflows in TypeScript** using the builder schema library (in `src/schemas`) and real, working examples from `src/workflowTemplates`. It’s aimed at both humans and LLMs so you can reuse it to generate new workflows or refactor/port existing Argo YAML.

---

## Why this library?

- **Type-safety end-to-end** — inputs/outputs, parameter shapes, and expressions are checked at compile time.
- **Ergonomic builders** — write workflows with chained builders (`WorkflowBuilder`, `TemplateBuilder`, `StepsBuilder`, etc.).
- **Zod integration** — define complex user schemas for parameters with `zod`, then reference those types directly.
- **Composable templates** — build re‑usable templates and stitch them into bigger workflows.

---

## Project layout (relevant parts)

```
src/
  schemas/                 # The builder library (strongly typed)
    expression.ts          # Expression DSL (govaluate/template), e.g., equals(), literal()
    parameterSchemas.ts    # Input/output parameter defs and helpers
    workflowBuilder.ts     # WorkflowBuilder + scopes, metadata
    templateBuilder.ts     # TemplateBuilder for container/steps/dag/k8s resources
    stepsBuilder.ts        # StepsBuilder (addStep, step groups, outputs)
    dagBuilder.ts          # DAG-specific template body builder
    containerBuilder.ts    # Container template builder (image/args/env/etc.)
    k8sResourceBuilder.ts  # K8s resource template builder for raw manifests
    scopeConstraints.ts    # Uniqueness/name constraints & types
    workflowTypes.ts       # Core type shapes, loops, AllowLiteralOrExpression, etc.

  workflowTemplates/       # Example workflows built with the library
    commonWorkflowTemplates.ts  # Shared workflow-level parameters (with defaults)
    userSchemas.ts              # Zod schemas used by templates
    targetLatchHelpers.ts       # Reusable helper templates (init, decrement, cleanup)
    setupKafka.ts               # Strimzi Kafka cluster setup example
    fullMigration.ts            # “Pipeline”/end-to-end example using loops & expressions
```

If you prefer to cross-check with plain Argo YAML, look in the `argoTemplates` bundle (e.g., `fullMigration.yaml`, `setupKafka.yaml`, `targetLatchHelpers.yaml`).

---

## Core concepts

### 1) Create a workflow
```ts
import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";

export const MyWorkflow = WorkflowBuilder.create({
  k8sResourceName: "MyWorkflow",
  serviceAccountName: "argo-workflow-executor",
  parallelism: 50,
})
  .addParams(CommonWorkflowParameters) // shared workflow-level params with defaults
  // ... add templates here ...
  .setEntrypoint("main")
  .getFullScope();
```

- `k8sResourceName` is the name for the `WorkflowTemplate` (or Workflow) resource.
- `.addParams()` lets you attach shared parameters that any template can reference.

`CommonWorkflowParameters` (see `commonWorkflowTemplates.ts`) is a simple object of `defineParam({...})` calls with defaults, e.g.:
```ts
export const CommonWorkflowParameters = {
  etcdEndpoints:       defineParam({ defaultValue: "http://etcd.ma.svc.cluster.local:2379" }),
  etcdUser:            defineParam({ defaultValue: "root" }),
  etcdPassword:        defineParam({ defaultValue: "password" }),
  etcdImage:           defineParam({ defaultValue: "migrations/migration_console:latest" }),
  s3SnapshotConfigMap: defineParam({ defaultValue: "s3-snapshot-config" }),
} as const;
```

### 2) Add templates
Use `addTemplate(name, builder => ...)` and pick a **body type**:
- `.addSteps(...)` – sequential **steps** templates
- `.addDag(...)` – **DAG** templates
- `.addContainer(...)` – single **container** template (classic Argo container template)
- `.addK8sResource(...)` – apply arbitrary K8s resources (e.g., Strimzi CRDs)

Each template can declare **inputs** and **outputs** with full type safety.

#### Example: a steps template with inputs & outputs
```ts
.addTemplate("main", t => t
  .addOptionalInput("simpleString", s => "hello") // defaulted
  .addRequiredInput("sourceMigrationConfigs", typeToken<z.infer<typeof SOURCE_MIGRATION_CONFIG>[]>(),
                    "List of source migration configs")
  .addSteps(b => b
    // add steps here
  )
)
```

- `.addRequiredInput(name, typeToken<T>())` binds the parameter to a concrete type.
- `.addOptionalInput(name, defaultOrResolver)` assigns an optional parameter with a default value.
- `zod` schemas in `userSchemas.ts` (e.g., `SOURCE_MIGRATION_CONFIG`, `CLUSTER_CONFIG`) are used with `z.infer<typeof ...>` to get compile‑time Typescript types.

### 3) Add steps (re-using existing templates)
With `StepsBuilder.addStep`, you call into another template (either in the **same** workflow or a referenced **external** one).

Signature (simplified):
```ts
.addStep("<stepName>", <templateSource>, "<templateKey>", (stepScope, register) => register({ /* caller params */ }))
```

- `stepScope` exposes outputs from prior steps (strongly typed).
- `register(...)` returns the **caller parameters** object; the library uses this pattern to validate what you pass.
- All passed fields are validated — **spurious fields are rejected** by the type system.

Example from `targetLatchHelpers.ts` (init → decrement → cleanup pattern):
```ts
.addSteps(b => b
  .addStep("init", TargetLatchHelpers, "init", (s, register) => register({
    prefix: s.inputs.prefix,
    etcdUtilsImagePullPolicy: "IF_NOT_PRESENT",
  }))
  .addStep("decrement", TargetLatchHelpers, "decrement", (s, register) => register({
    prefix: s.tasks.init.prefix,
  }))
  .addStep("cleanup", TargetLatchHelpers, "cleanup", (s, register) => register({
    prefix: s.tasks.init.prefix,
    etcdUtilsImagePullPolicy: "IF_NOT_PRESENT",
  }))
)
```

### 4) Expressions & conditions
Use helpers from `expression.ts` to build **govaluate/template** expressions with compile‑time result typing:
```ts
import {BaseExpression, equals, literal} from "@/schemas/expression";

const left:  BaseExpression<string, "govaluate"> = literal("a");
const right: BaseExpression<string, "govaluate"> = literal("a");
const cond:  BaseExpression<boolean, "govaluate"> = equals(left, right);
```
You can use expressions in places like `when`/`loopWith` or for computed outputs (see `stepsBuilder.ts`).

### 5) Loops
Use `makeParameterLoop` (from `workflowTypes.ts`) to iterate over arrays of inputs:
```ts
.addTemplate("main", t => t
  .addRequiredInput("sourceMigrationConfigs", typeToken<z.infer<typeof SOURCE_MIGRATION_CONFIG>[]>())
  .addSteps(b => b
    .addStep("pipelineSourceMigration", CurrentWorkflow, "pipelineSourceMigration",
      (s, register) => register({
        sourceMigrationConfig: s.inputs.sourceMigrationConfigs, // single item if looped
      }),
      { loopWith: makeParameterLoop(s => s.inputs.sourceMigrationConfigs) }
    )
  )
)
```

### 6) K8s resources (CRDs like Strimzi)
`setupKafka.ts` demonstrates emitting **raw K8s manifests** via the `K8sResourceBuilder`. Example pattern:
```ts
.addTemplate("setupKafka", t => t
  .addRequiredInput("kafkaName", typeToken<string>())
  .addK8sResource(rb => rb
    .setManifest(makeDeployKafkaClusterZookeeperManifest({ kafkaName: s.inputs.kafkaName }))
  )
)
```
Where `makeDeployKafkaClusterZookeeperManifest(...)` returns a plain object representing a valid K8s/Strimzi manifest. Types are enforced with `AllowLiteralOrExpression<string>` for values that can be literal or expression-driven.

### 7) Containers
For templates that run a **container** (`containerBuilder.ts`), specify image, args, env, and policies. From `targetLatchHelpers.ts`:
```ts
.addContainer(cb => cb
  .setImage(s => s.workflowParameters.etcdImage)
  .setImagePullPolicy("IF_NOT_PRESENT") // IMAGE_PULL_POLICY type is checked
  .addEnv({ name: "ETCD_ENDPOINTS", value: s => s.inputs.etcdEndpoints })
  // ...
)
```

---

## End-to-end examples

### A) Target Latch Helpers
- File: `src/workflowTemplates/targetLatchHelpers.ts`
- Templates:
  - `init` — initializes a latch (container template)
  - `decrement` — decrements the latch
  - `cleanup` — cleanup behavior
- Patterns to notice:
  - Uses `CommonWorkflowParameters` for image and etcd credentials.
  - Inputs are standardized via a small helper `addCommonTargetLatchInputs(...)` for repeatability.
  - Step chaining reuses outputs (e.g., `s.tasks.init.prefix`).

See the analogous YAML in `argoTemplates/targetLatchHelpers.yaml` for a 1:1 mapping.

### B) Setup Kafka (Strimzi)
- File: `src/workflowTemplates/setupKafka.ts`
- Shows how to author **K8s resource** templates by returning Strimzi Kafka manifests (`apiVersion: kafka.strimzi.io/v1beta2`, etc.).
- Demonstrates parameterizing the Kafka name (`kafkaName`) and using `AllowLiteralOrExpression<string>` to flexibly set spec fields.
- Mirrors `argoTemplates/setupKafka.yaml`.

### C) Full Migration pipeline
- File: `src/workflowTemplates/fullMigration.ts`
- Introduces **loops** over `sourceMigrationConfigs` (from `userSchemas.ts`), and **expressions** (e.g., `equals(literal("a"), literal("a"))`).
- Demonstrates composing the reusable `TargetLatchHelpers` templates as steps inside a larger pipeline.
- Mirrors `argoTemplates/fullMigration.yaml`.

---

## Authoring checklist

1. **Define inputs** with `typeToken<T>()` and/or defaults. Prefer `zod` schemas + `z.infer` for complex shapes.
2. Use `CommonWorkflowParameters` for shared settings (images, endpoints, configmaps).
3. Build templates with the correct **body** (`.addSteps`, `.addDag`, `.addContainer`, `.addK8sResource`).
4. **Add steps** by referencing other templates; pass caller params through the `register({...})` callback.
5. Compose **expressions** via `expression.ts` helpers when you need `when` conditions, computed outputs, or comparisons.
6. Use **loops** (`makeParameterLoop`) for per-item processing.
7. Use **strong names** — name uniqueness is enforced (`scopeConstraints.ts`), and spurious parameter fields are rejected.
8. Finish with `.setEntrypoint("main")` and `.getFullScope()`.

> **Note on rendering to YAML:** `getFullScope()` returns a typed structure for the whole workflow. A small transformer (not shown here) should serialize it into a valid Argo `WorkflowTemplate`/`Workflow` YAML. See the YAML in `argoTemplates` for the expected shape.

---

## Minimal starter template

```ts
import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {TemplateBuilder} from "@/schemas/templateBuilder";
import {typeToken} from "@/schemas/parameterSchemas";

export const Hello = WorkflowBuilder.create({
  k8sResourceName: "hello",
  serviceAccountName: "argo-workflow-executor",
})
  .addTemplate("main", (t: TemplateBuilder<any, {}, {}, {}>) => t
    .addOptionalInput("name", s => "world")
    .addSteps(b => b /* add steps here */)
  )
  .setEntrypoint("main")
  .getFullScope();
```

---

## Tips for LLM-powered migrations

- Use this README as a **system prompt** to teach the model:
  - Expected folder structure and where to place new templates.
  - The **register(...) pattern** for passing caller parameters to `addStep`.
  - The preference for **zod** types + `z.infer` for all complex inputs.
  - The **loop** primitive `makeParameterLoop` when porting over lists in YAML.
- When converting YAML → TS:
  1. Turn YAML `arguments.parameters` into `CommonWorkflowParameters` or per-template inputs.
  2. Convert each YAML `template` into its corresponding builder style (`steps`, `dag`, `container`, or `k8sResource`).
  3. Map YAML `templateRef` or `template` calls to `.addStep("<name>", <source>, "<key>", ...)`.
  4. Carry over `when`, `withParam`, `withItems`, etc., using **expressions** and **loops**.

---

## Appendix: quick API reference (selected)

- **WorkflowBuilder**
  - `create({ k8sResourceName, serviceAccountName?, parallelism? })`
  - `.addParams(<record from defineParam(...)>)`
  - `.addTemplate(name, tb => tb.<body builder>())`
  - `.setEntrypoint(name)` → `.getFullScope()`

- **TemplateBuilder**
  - `.addRequiredInput(name, typeToken<T>(), description?)`
  - `.addOptionalInput(name, default | (s) => default, description?)`
  - Bodies:
    - `.addSteps(b => ...)`
    - `.addDag(d => ...)`
    - `.addContainer(c => ...)`
    - `.addK8sResource(k => ...)`
  - `.addExpressionOutput(name, expr | (s)=>expr, description?)`

- **StepsBuilder**
  - `.addStep(name, source, key, (stepScope, register) => register({...}), opts?)`
  - `.addOutput(name, expr | (s)=>expr, description?)`

- **Expressions** (`expression.ts`):
  - `literal(v)`, `equals(a, b)`, `concat(...)`, arithmetic/ternary helpers
  - Types: `BaseExpression<T, "govaluate" | "template">`, `SimpleExpression<T>`

- **Looping**
  - `makeParameterLoop(s => s.inputs.someArray)`

- **K8s Resources**
  - Provide a plain object manifest; use `AllowLiteralOrExpression<T>` for fields that may be expressions.

---

## Where to look in the repo

- Patterns to copy/paste:
  - `workflowTemplates/targetLatchHelpers.ts` — container steps and standard inputs
  - `workflowTemplates/setupKafka.ts` — K8s CRD authoring
  - `workflowTemplates/fullMigration.ts` — loops and expressions

If you want me to tailor this README with **specific code excerpts** from your current templates or extend it with a **YAML renderer stub**, just say the word.
