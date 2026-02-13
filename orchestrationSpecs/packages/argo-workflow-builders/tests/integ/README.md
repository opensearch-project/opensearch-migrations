# Argo Workflow Builder Integration Tests

This directory contains integration tests that validate the `@opensearch-migrations/argo-workflow-builders` library against a real Argo Workflows instance.

## Overview

The integration test suite spins up a K3s Kubernetes cluster with Argo Workflows installed via testcontainers, submits workflows built by the library, and verifies that the rendered expressions produce expected output values at runtime.

**Key design**: Tests use templates with empty `steps: [[]]` to evaluate expressions without container overhead. This allows fast expression evaluation while properly propagating outputs to the workflow level.

## Architecture

### Directory Structure

```
tests/
├── unit/                          # Unit tests (existing tests)
└── integ/                         # Integration tests
    ├── infra/                     # Test infrastructure
    │   ├── argoCluster.ts         # K3s + Argo lifecycle management
    │   ├── workflowRunner.ts      # Submit workflows and poll for results
    │   ├── probeHelper.ts         # Build minimal probe workflows
    │   ├── setup.ts               # Jest global setup
    │   └── teardown.ts            # Jest global teardown
    ├── contracts/                 # Contract tests (Argo behavior documentation)
    │   ├── jsonpath.integ.test.ts
    │   ├── sprigFunctions.integ.test.ts
    │   ├── expressionEval.integ.test.ts
    │   ├── paramPassthrough.integ.test.ts
    │   └── whenConditions.integ.test.ts
    ├── modelValidation/           # Builder API validation
    │   ├── serializationRoundtrip.integ.test.ts
    │   └── rendererToArgo.integ.test.ts
    ├── containers/                # Container-specific tests (optional)
    │   └── containerOutputs.integ.test.ts
    └── fixtures/
        └── quick-start-minimal.yaml  # Argo installation manifest
```

### Test Categories

#### 1. Contract Tests (`contracts/`)

These tests establish ground truth about Argo's runtime behavior. They use raw Argo expressions (not the builder API) to document what Argo actually returns for various patterns.

**Purpose**: Document Argo's behavior for:
- JSONPath extraction and type coercion
- Sprig function return values
- Expression evaluation semantics
- Parameter pass-through fidelity
- Conditional execution (`when` clauses)

**Key characteristic**: Tests use `submitProbe()` with raw expressions to isolate Argo's behavior from the builder library.

**Negative tests**: Each category includes negative tests that verify:
- Invalid expressions fail as expected
- Wrong values don't match
- Type boundaries are enforced
- Failures occur when they should

#### 2. Model Validation Tests (`modelValidation/`)

These tests validate that the builder API's type system and renderer produce workflows that behave correctly in Argo.

**Purpose**: Verify:
- Parameter serialization (objects → JSON strings, numbers, booleans)
- Expression output rendering
- Template composition (steps, DAG)
- Parameter forwarding between templates

**Key characteristic**: Tests use the builder API (`WorkflowBuilder`, `SuspendTemplateBuilder`, etc.) and verify end-to-end behavior.

**Negative tests**: Verify that incorrect configurations (wrong parameter names, missing references) fail or return empty values rather than succeeding incorrectly.

#### 3. Container Tests (`containers/`)

Optional tests for container-specific features like `valueFrom.path` and stdout capture. These require pulling container images and are slower.

## Running Tests

### Prerequisites

- Docker installed and running
- Docker must support privileged containers (required by K3s)
- Node.js and npm

### Commands

```bash
# Run unit tests only
npm test

# Run integration tests
npm run test:integ

# Run a specific integration test file
npm run test:integ -- jsonpath.integ.test.ts
```

### First Run

The first run will:
1. Pull the K3s container image (~200MB)
2. Start K3s (~15-30 seconds)
3. Install Argo Workflows (~30-60 seconds)
4. Run tests

Total setup time: ~2-3 minutes. Subsequent runs reuse the images and are faster.

### Timeouts

- Global setup: 180 seconds
- Per-test: 120 seconds
- Workflow deadline: 30 seconds (inside Argo)

## Infrastructure Components

### `argoCluster.ts`

Manages the K3s container and Argo installation lifecycle.

**Key functions**:
- `startCluster()`: Starts K3s, installs Argo, creates namespaces and RBAC
- `stopCluster()`: Stops K3s and cleans up temp files
- `getKubeConfig()`: Returns kubeconfig for connecting to the cluster
- `getTestNamespace()`: Returns the namespace for test workflows (`integ-test`)

**Implementation details**:
- Uses `rancher/k3s:v1.31.6-k3s1` image
- Installs Argo v4.0.0 from the quick-start-minimal manifest
- Creates `integ-test` namespace with `test-runner` ServiceAccount
- Writes kubeconfig to `/tmp/integ-test-kubeconfig.yaml` for cross-process access

### `workflowRunner.ts`

Submits workflows and polls for completion.

**Key types**:
```typescript
interface WorkflowResult {
  phase: string;                              // "Succeeded", "Failed", "Error"
  message?: string;                           // Error message if failed
  globalOutputs: Record<string, string>;      // Workflow-level outputs
  nodeOutputs: Record<string, NodeOutput>;    // Per-step outputs (keyed by displayName)
  duration: number;                           // Wall-clock milliseconds
  raw: any;                                   // Full workflow object for debugging
}
```

**Key functions**:
- `submitAndWait(workflowObject, timeoutMs)`: Submits a workflow and polls until terminal phase
- Returns structured results with both global and per-node outputs

### `probeHelper.ts`

Builds minimal "probe" workflows for testing specific expressions without container overhead.

**Key functions**:

#### `submitProbe(config)`
Creates a workflow with a single suspend template that evaluates an expression.

```typescript
const result = await submitProbe({
  inputs: { data: '{"key":"value"}' },
  expression: "jsonpath(inputs.parameters.data, '$.key')",
});

expect(result.globalOutputs.result).toBe("value");
```

#### `submitChainProbe(config)`
Creates a workflow with multiple suspend steps chained in sequence, for testing parameter pass-through.

```typescript
const result = await submitChainProbe({
  input: '{"a":1}',
  steps: [
    { expression: "inputs.parameters.input" },
    { expression: "inputs.parameters.input" },
  ],
});

expect(result.globalOutputs.result).toBe('{"a":1}');
```

#### `submitRenderedWorkflow(rendered, inputOverrides?)`
Submits a workflow built with the builder API.

```typescript
const builder = new WorkflowBuilder("test");
// ... configure builder ...
const rendered = renderWorkflowTemplate(builder);
const result = await submitRenderedWorkflow(rendered);
```

## Writing New Tests

### Adding a Contract Test

Contract tests document Argo's behavior for a specific feature. Use raw expressions.

```typescript
// tests/integ/contracts/myFeature.integ.test.ts
import { submitProbe } from "../infra/probeHelper";

describe("My Feature Contract Tests", () => {
  test("feature behavior", async () => {
    const result = await submitProbe({
      inputs: { myInput: "test" },
      expression: "someArgoFunction(inputs.parameters.myInput)",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("expected-value");
  });
});
```

**Best practices**:
- Always assert on `result.phase === "Succeeded"` first
- Use `console.log()` to discover unknown behavior, then write assertions
- Add comments documenting discovered behavior
- Keep expressions simple and focused on one feature

### Adding a Model Validation Test

Model validation tests verify the builder API produces correct workflows.

```typescript
// tests/integ/modelValidation/myFeature.integ.test.ts
import { WorkflowBuilder } from "../../../src/models/workflowBuilder";
import { renderWorkflowTemplate } from "../../../src/renderers/argoResourceRenderer";
import { submitRenderedWorkflow } from "../infra/probeHelper";

describe("My Feature Validation", () => {
  test("builder produces correct workflow", async () => {
    const builder = new WorkflowBuilder("test");
    // ... configure builder ...
    
    const rendered = renderWorkflowTemplate(builder);
    
    // Assert on rendered structure
    expect(rendered.spec.templates).toHaveLength(2);
    
    // Verify runtime behavior
    const result = await submitRenderedWorkflow(rendered);
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.myOutput).toBe("expected");
  });
});
```

**Best practices**:
- Test both the rendered structure and runtime behavior
- Use the builder API (don't construct raw workflow objects)
- Focus on realistic workflow patterns
- Verify parameter forwarding and output wiring

### Adding Validations to Existing Tests

To add a new assertion to an existing test category:

1. **For JSONPath behavior**: Add to `contracts/jsonpath.integ.test.ts`
2. **For Sprig functions**: Add to `contracts/sprigFunctions.integ.test.ts`
3. **For expression evaluation**: Add to `contracts/expressionEval.integ.test.ts`
4. **For parameter fidelity**: Add to `contracts/paramPassthrough.integ.test.ts`
5. **For conditional execution**: Add to `contracts/whenConditions.integ.test.ts`

### Testing Custom Workflow Structures

For complex workflows that don't fit the probe pattern:

```typescript
import { submitAndWait } from "../infra/workflowRunner";
import { getTestNamespace } from "../infra/argoCluster";

test("custom workflow structure", async () => {
  const workflow = {
    apiVersion: "argoproj.io/v1alpha1",
    kind: "Workflow",
    metadata: {
      generateName: "custom-",
      namespace: getTestNamespace(),
    },
    spec: {
      entrypoint: "main",
      activeDeadlineSeconds: 30,
      serviceAccountName: "test-runner",
      templates: [
        // ... your templates ...
      ],
    },
  };
  
  const result = await submitAndWait(workflow);
  expect(result.phase).toBe("Succeeded");
});
```

## Debugging Failed Tests

### Viewing Workflow Status

When a test fails, the `WorkflowResult.raw` field contains the full workflow object:

```typescript
test("my test", async () => {
  const result = await submitProbe({ /* ... */ });
  
  if (result.phase !== "Succeeded") {
    console.log("Workflow status:", JSON.stringify(result.raw.status, null, 2));
  }
  
  expect(result.phase).toBe("Succeeded");
});
```

### Common Issues

#### Workflow times out
- Check `result.raw.status.message` for Argo error messages
- Verify expression syntax is correct
- Ensure input parameters are properly formatted

#### Workflow fails with "expression evaluation error"
- The expression syntax is invalid or references non-existent parameters
- Check Argo expression documentation for correct syntax
- Use simpler expressions to isolate the issue

#### K3s fails to start
- Ensure Docker is running
- Verify Docker supports privileged containers
- Check available disk space and memory

#### Argo controller not ready
- Increase timeout in `argoCluster.ts` if on slow hardware
- Check Docker logs: `docker logs <container-id>`

### Accessing the Cluster Manually

During test development, you can access the cluster:

```bash
# Get kubeconfig
export KUBECONFIG=/tmp/integ-test-kubeconfig.yaml

# List workflows
kubectl get workflows -n integ-test

# Describe a workflow
kubectl describe workflow <workflow-name> -n integ-test

# View workflow logs
kubectl logs -n integ-test <pod-name>
```

## Performance Considerations

### Why Empty Steps?

The tests use templates with empty `steps: [[]]` instead of container-based templates because:
- No container image pulls required
- No pod scheduling overhead  
- Tests run in <1 second each
- **Outputs propagate to workflow level** (unlike suspend templates)
- Focus on expression evaluation, not container execution

### Test Isolation

- All tests share one K3s cluster (started in global setup)
- Each workflow uses `generateName` for unique names
- No cleanup between tests (workflows are tiny and have no side effects)
- Tests run sequentially (`maxWorkers: 1`) to avoid resource contention

### CI Considerations

- K3s requires privileged containers (works in GitHub Actions, most CI systems)
- Memory requirement: ~512MB-1GB for K3s + Argo
- First run pulls images (~200MB)
- Subsequent runs reuse images

## Extending the Test Suite

### Adding a New Test Category

1. Create a new directory under `tests/integ/`
2. Add test files with `.integ.test.ts` suffix
3. Import helpers from `../infra/`
4. Follow existing patterns for assertions

### Testing New Builder Features

When adding a new feature to the builder library:

1. **Add a contract test** if the feature uses new Argo expressions or functions
2. **Add a model validation test** to verify the builder produces correct workflows
3. **Update this README** with examples of the new feature

### Upgrading Argo Version

To test against a newer Argo version:

1. Download the new quick-start-minimal manifest:
   ```bash
   curl -sL https://github.com/argoproj/argo-workflows/releases/download/vX.Y.Z/quick-start-minimal.yaml \
     -o tests/integ/fixtures/quick-start-minimal.yaml
   ```

2. Update the version comment in `argoCluster.ts`

3. Run the full test suite to verify compatibility

## Troubleshooting

### Tests hang during setup

- Check Docker daemon is running: `docker ps`
- Verify network connectivity (K3s pulls images)
- Increase timeout in `argoCluster.ts` if on slow hardware

### "Kubeconfig not available" error

- The global setup failed to complete
- Check Jest output for setup errors
- Verify `/tmp/integ-test-kubeconfig.yaml` exists during test run

### Workflows fail with RBAC errors

- The `test-runner` ServiceAccount may not have been created
- Check `argoCluster.ts` RBAC setup
- Verify ClusterRoleBinding exists: `kubectl get clusterrolebinding test-runner-admin`

## Additional Resources

- [Argo Workflows Documentation](https://argo-workflows.readthedocs.io/)
- [Argo Expression Syntax](https://argo-workflows.readthedocs.io/en/latest/variables/)
- [Testcontainers Documentation](https://testcontainers.com/)
- [K3s Documentation](https://k3s.io/)
