# Parity Tests

This directory contains parity tests that verify both Argo Workflows behavior (contract tests) and the builder API's ability to produce equivalent results.

## Test Structure

Each test follows a nested pattern:

```typescript
describe("Feature - specific behavior", () => {
  const spec: ParitySpec = {
    category: "Feature",
    name: "specific behavior",
    inputs: { /* test inputs */ },
    argoExpression: "argo expression here",
    expectedResult: "expected output",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({
        inputs: spec.inputs,
        expression: spec.argoExpression,
      });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - variantName", () => {
    const builderVariant: BuilderVariant = {
      name: "variantName",
      code: 'expr.someMethod(...)',
    };

    test("builder API produces same result", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: "unique-name" })
        .addParams({ /* workflow params */ })
        .addTemplate("main", t => t
          .addSteps(s => s.addStepGroup(c => c))  // empty step group
          .addExpressionOutput("result", () => /* builder expression */)
        )
        .setEntrypoint("main")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});
```

## Key Patterns

### Empty Steps
Use `.addSteps(s => s.addStepGroup(c => c))` to create an empty step group (equivalent to Argo's `steps: [[]]`).

### Workflow Parameters
Reference workflow parameters in expressions using:
```typescript
expr.literal("{{workflow.parameters.paramName}}")
```

### Type Casting
When passing string parameters to methods expecting specific types:
```typescript
expr.cast(expr.literal("{{workflow.parameters.data}}")).to<any>()
```

## Running Tests

```bash
npm run test:parity
```

This generates a parity catalog at `tests/integ/artifacts/parity-catalog.md` showing:
- Which Argo features are tested
- Which have builder API equivalents
- Side-by-side comparison of results
