# Quick Reference: Extending Integration Tests

This guide provides quick examples for common test extension scenarios.

## Verified Argo Features

All features below are tested and working. See [CONTRACT_TEST_RESULTS.md](CONTRACT_TEST_RESULTS.md) for full results.

### Regex Functions (Sprig)

```typescript
// Pattern matching
sprig.regexMatch('^[A-Za-z0-9._%+-]+@', email)

// Extract first match
sprig.regexFind('[a-zA-Z][1-9]', 'abcd1234')  // → "d1"

// Find all matches
sprig.regexFindAll('[2,4,6,8]', '123456789', -1)  // → ["2","4","6","8"]

// Replace with capture groups
sprig.regexReplaceAll('a(x*)b', '-ab-axxb-', '${1}W')  // → "-W-xxW-"

// Split by pattern
sprig.regexSplit('z+', 'pizza', -1)  // → ["pi","a"]
```

### Advanced Sprig Functions

```typescript
// Merge dictionaries (first takes precedence)
sprig.merge(sprig.dict('a','1','b','2'), sprig.dict('b','3','c','4'))
// → {a:"1", b:"2", c:"4"}

// Remove keys
sprig.omit(sprig.dict('a','1','b','2','c','3'), 'b')  // → {a:"1", c:"3"}

// Navigate nested with default
sprig.dig('user', 'role', 'name', 'guest', obj)  // Returns 'guest' if path doesn't exist

// Get keys
keys({a:1, b:2})  // → ["a","b"]
```

### Bracket Notation & 'in' Operator

```typescript
// Access keys with special characters
fromJSON('{"my-key":"value"}')['my-key']  // → "value"

// Array access
array[1]    // Second element
array[-1]   // Last element

// Membership checks
'John' in ['John', 'Jane']  // → true
'name' in {name: 'John'}    // → true

// Combined with ternary
role in ['admin', 'superuser'] ? 'allowed' : 'denied'
```

### Type Conversion

```typescript
// ⚠️ asInt() errors on decimals
asInt("42")     // ✅ Works → 42
asInt("42.7")   // ❌ Errors

// Use this for decimals:
int(asFloat("42.7"))  // ✅ Works → 42

// asFloat works on both
asFloat("42")    // → 42.0
asFloat("3.14")  // → 3.14
```

## Adding a New Contract Test

Contract tests document Argo's runtime behavior using raw expressions.

```typescript
// tests/integ/contracts/myFeature.integ.test.ts
import { submitProbe } from "../infra/probeHelper";

describe("My Feature Contract Tests", () => {
  test("describes specific behavior", async () => {
    const result = await submitProbe({
      inputs: { myParam: "test-value" },
      expression: "someArgoFunction(inputs.parameters.myParam)",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("expected-output");
  });
});
```

**When to add**: Testing new Argo expressions, functions, or runtime behaviors.

## Adding a New Model Validation Test

Model validation tests verify the builder API produces correct workflows.

```typescript
// tests/integ/modelValidation/myFeature.integ.test.ts
import { WorkflowBuilder } from "../../../src/models/workflowBuilder";
import { SuspendTemplateBuilder } from "../../../src/models/suspendTemplateBuilder";
import { renderWorkflowTemplate } from "../../../src/renderers/argoResourceRenderer";
import { submitRenderedWorkflow } from "../infra/probeHelper";
import { expr } from "../../../src/models/expression";

describe("My Feature Validation", () => {
  test("builder feature works end-to-end", async () => {
    const builder = new WorkflowBuilder("test-my-feature");
    
    // Configure builder
    builder.addParams({
      input: defineParam({ expression: "test" }),
    });
    
    const template = new SuspendTemplateBuilder("my-template");
    template.addInputParam("value");
    template.setDuration(0);
    template.addExpressionOutput("result", expr.inputs.parameters.value);
    
    builder.addTemplate(template);
    builder.setEntrypoint("my-template");
    
    // Render and submit
    const rendered = renderWorkflowTemplate(builder);
    const result = await submitRenderedWorkflow(rendered, {
      input: "custom-value",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("custom-value");
  });
});
```

**When to add**: Testing new builder features, template types, or parameter handling.

## Testing Multi-Step Workflows

For workflows with multiple steps:

```typescript
test("multi-step workflow", async () => {
  const builder = new WorkflowBuilder("multi-step");
  
  builder.addParams({
    input: defineParam({ expression: "start" }),
  });
  
  // Step 1: Transform input
  const step1 = new SuspendTemplateBuilder("step1");
  step1.addInputParam("val");
  step1.setDuration(0);
  step1.addExpressionOutput("out", "inputs.parameters.val + '-step1'");
  
  // Step 2: Transform again
  const step2 = new SuspendTemplateBuilder("step2");
  step2.addInputParam("val");
  step2.setDuration(0);
  step2.addExpressionOutput("out", "inputs.parameters.val + '-step2'");
  
  // Main steps template
  const main = builder.addStepsTemplate("main");
  const s1 = main.addStep("s1", "step1");
  s1.addInputParam("val", expr.workflow.parameters.input);
  
  const s2 = main.addStep("s2", "step2");
  s2.addInputParam("val", expr.steps.s1.outputs.parameters.out);
  
  main.addOutputFromStep("final", "s2", "out");
  
  builder.addTemplate(step1);
  builder.addTemplate(step2);
  builder.setEntrypoint("main");
  
  const rendered = renderWorkflowTemplate(builder);
  const result = await submitRenderedWorkflow(rendered);
  
  expect(result.phase).toBe("Succeeded");
  expect(result.globalOutputs.final).toBe("start-step1-step2");
});
```

## Testing DAG Workflows

For DAG-based workflows:

```typescript
test("DAG workflow", async () => {
  const builder = new WorkflowBuilder("dag-test");
  
  builder.addParams({
    input: defineParam({ expression: "value" }),
  });
  
  // Task template
  const task = new SuspendTemplateBuilder("task");
  task.addInputParam("val");
  task.setDuration(0);
  task.addExpressionOutput("result", "inputs.parameters.val");
  
  // DAG template
  const dag = builder.addDagTemplate("main");
  const t1 = dag.addTask("task1", "task");
  t1.addInputParam("val", expr.workflow.parameters.input);
  
  dag.addOutputFromTask("final", "task1", "result");
  
  builder.addTemplate(task);
  builder.setEntrypoint("main");
  
  const rendered = renderWorkflowTemplate(builder);
  const result = await submitRenderedWorkflow(rendered);
  
  expect(result.phase).toBe("Succeeded");
  expect(result.globalOutputs.final).toBe("value");
});
```

## Testing Conditional Execution

For workflows with `when` conditions:

```typescript
import { submitAndWait } from "../infra/workflowRunner";
import { getTestNamespace } from "../infra/argoCluster";

test("conditional step execution", async () => {
  const workflow = {
    apiVersion: "argoproj.io/v1alpha1",
    kind: "Workflow",
    metadata: {
      generateName: "conditional-",
      namespace: getTestNamespace(),
    },
    spec: {
      entrypoint: "main",
      activeDeadlineSeconds: 30,
      serviceAccountName: "test-runner",
      arguments: {
        parameters: [{ name: "shouldRun", value: "true" }],
      },
      templates: [
        {
          name: "conditional-task",
          suspend: { duration: "0" },
        },
        {
          name: "main",
          steps: [
            [
              {
                name: "maybe-run",
                template: "conditional-task",
                when: "{{workflow.parameters.shouldRun}} == true",
              },
            ],
          ],
        },
      ],
    },
  };
  
  const result = await submitAndWait(workflow);
  
  expect(result.phase).toBe("Succeeded");
  expect(result.nodeOutputs["maybe-run"].phase).toBe("Succeeded");
});
```

## Testing Parameter Pass-Through

For testing parameter fidelity across steps:

```typescript
import { submitChainProbe } from "../infra/probeHelper";

test("complex JSON survives multiple hops", async () => {
  const complexJson = JSON.stringify({
    nested: { array: [1, 2, 3] },
    special: "chars: \"quotes\" and 'apostrophes'",
  });
  
  const result = await submitChainProbe({
    input: complexJson,
    steps: [
      { expression: "inputs.parameters.input" },
      { expression: "inputs.parameters.input" },
      { expression: "inputs.parameters.input" },
    ],
  });
  
  expect(result.phase).toBe("Succeeded");
  expect(result.globalOutputs.result).toBe(complexJson);
});
```

## Testing Expression Transformations

For testing expression evaluation:

```typescript
test("expression transforms data", async () => {
  const result = await submitProbe({
    inputs: {
      data: '{"count": 5, "multiplier": 3}',
    },
    expression: `
      string(
        asInt(jsonpath(inputs.parameters.data, '$.count')) *
        asInt(jsonpath(inputs.parameters.data, '$.multiplier'))
      )
    `,
  });
  
  expect(result.phase).toBe("Succeeded");
  expect(result.globalOutputs.result).toBe("15");
});
```

## Debugging Failed Tests

### View Full Workflow Status

```typescript
test("my test", async () => {
  const result = await submitProbe({ /* ... */ });
  
  if (result.phase !== "Succeeded") {
    console.log("Workflow failed!");
    console.log("Phase:", result.phase);
    console.log("Message:", result.message);
    console.log("Full status:", JSON.stringify(result.raw.status, null, 2));
  }
  
  expect(result.phase).toBe("Succeeded");
});
```

### View Node-Specific Outputs

```typescript
test("check specific step", async () => {
  const result = await submitRenderedWorkflow(rendered);
  
  console.log("All node outputs:", result.nodeOutputs);
  console.log("Step 'my-step' output:", result.nodeOutputs["my-step"]);
  
  expect(result.nodeOutputs["my-step"].phase).toBe("Succeeded");
  expect(result.nodeOutputs["my-step"].parameters.result).toBe("expected");
});
```

## Common Patterns

### Writing Negative Tests

Always complement positive tests with negative tests to ensure validation is working correctly.

```typescript
describe("My Feature", () => {
  test("positive: correct input produces expected output", async () => {
    const result = await submitProbe({
      inputs: { data: '{"key":"value"}' },
      expression: "jsonpath(inputs.parameters.data, '$.key')",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("value");
  });
  
  test("negative: wrong value does not match", async () => {
    const result = await submitProbe({
      inputs: { data: '{"key":"value"}' },
      expression: "jsonpath(inputs.parameters.data, '$.key')",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).not.toBe("wrong");
    expect(result.globalOutputs.result).not.toBe("key");
    expect(result.globalOutputs.result).toBe("value");
  });
  
  test("negative: invalid expression fails", async () => {
    try {
      const result = await submitProbe({
        inputs: { data: '{"key":"value"}' },
        expression: "jsonpath(inputs.parameters.data, 'invalid[[[')",
      });
      
      expect(result.phase).not.toBe("Succeeded");
    } catch (err) {
      // Expected - workflow should fail
      expect(err.message).toMatch(/timed out|Failed|Error/);
    }
  });
});
```

### Testing Error Handling

```typescript
test("workflow handles invalid input", async () => {
  try {
    const result = await submitProbe({
      inputs: { data: "not-json" },
      expression: "fromJson(inputs.parameters.data)",
    });
    
    // If it succeeds, document the behavior
    console.log("Unexpected success:", result.globalOutputs.result);
    fail("Expected workflow to fail");
  } catch (err) {
    // Expected - workflow should fail
    expect(err.message).toContain("timed out");
  }
});
```

### Testing with Input Overrides

```typescript
test("workflow accepts runtime inputs", async () => {
  const builder = new WorkflowBuilder("test");
  builder.addParams({
    configurable: defineParam({ expression: "default" }),
  });
  // ... configure workflow ...
  
  const rendered = renderWorkflowTemplate(builder);
  const result = await submitRenderedWorkflow(rendered, {
    configurable: "overridden-value",
  });
  
  expect(result.phase).toBe("Succeeded");
  // Verify the override was used
});
```

## File Organization

- **Contract tests**: `tests/integ/contracts/` - Group by Argo feature
- **Model validation**: `tests/integ/modelValidation/` - Group by builder feature
- **Custom workflows**: Create new directories as needed

## Running Tests

```bash
# All integration tests
npm run test:integ

# Specific file
npm run test:integ -- myFeature.integ.test.ts

# Specific test
npm run test:integ -- -t "test name pattern"

# With verbose output
npm run test:integ -- --verbose
```

## Tips

1. **Start simple**: Use `submitProbe()` for single-expression tests
2. **Use chain probes**: For parameter pass-through testing
3. **Always check phase**: Assert `result.phase === "Succeeded"` first
4. **Log unknown behavior**: Use `console.log()` to discover Argo's behavior
5. **Keep tests focused**: One feature per test
6. **Use descriptive names**: Test names should explain what's being verified
7. **Check both structure and runtime**: For model validation, assert on rendered structure AND Argo results
