import { renderWorkflowTemplate, expr, typeToken } from "../../../src/index.js";
import { makeTestWorkflow } from "../infra/testWorkflowHelper.js";import { submitProbe, submitRenderedWorkflow } from "../infra/probeHelper.js";
import { ParitySpec, BuilderVariant, reportContractResult, reportParityResult } from "../infra/parityHelper.js";

describe("Array Operations - array indexing with literal index", () => {
  const spec: ParitySpec = {
    category: "Array Operations",
    name: "array indexing with literal index",
    inputs: { arr: '["a","b","c"]' },
    argoExpression: "fromJSON(inputs.parameters.arr)[1]",
    expectedResult: "b",
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

  describe("Builder - index", () => {
    const builderVariant: BuilderVariant = {
      name: "index",
      code: 'expr.index(expr.deserializeRecord(ctx.inputs.arr), expr.literal(1))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
          .addOptionalInput("arr", () => expr.stringToRecord(typeToken<string[]>(), '["a","b","c"]'))
          .addSteps(s => s.addStepGroup(c => c))
          .addExpressionOutput("result", (ctx) =>
            expr.index(expr.deserializeRecord(ctx.inputs.arr), expr.literal(1))
          )
        )
        
        ;

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Array Operations - array length", () => {
  const spec: ParitySpec = {
    category: "Array Operations",
    name: "array length",
    inputs: { arr: '["a","b","c"]' },
    argoExpression: "string(len(fromJSON(inputs.parameters.arr)))",
    expectedResult: "3",
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

  describe("Builder - length", () => {
    const builderVariant: BuilderVariant = {
      name: "length",
      code: 'expr.toString(expr.length(expr.deserializeRecord(ctx.inputs.arr)))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
          .addOptionalInput("arr", () => expr.stringToRecord(typeToken<string[]>(), '["a","b","c"]'))
          .addSteps(s => s.addStepGroup(c => c))
          .addExpressionOutput("result", (ctx) =>
            expr.toString(expr.length(expr.deserializeRecord(ctx.inputs.arr)))
          )
        )
        
        ;

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Array Operations - last element of array", () => {
  const spec: ParitySpec = {
    category: "Array Operations",
    name: "last element of array",
    inputs: { arr: '["first","middle","last"]' },
    argoExpression: "last(fromJSON(inputs.parameters.arr))",
    expectedResult: "last",
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

  describe("Builder - last", () => {
    const builderVariant: BuilderVariant = {
      name: "last",
      code: 'expr.last(expr.deserializeRecord(ctx.inputs.arr))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
          .addOptionalInput("arr", () => expr.stringToRecord(typeToken<string[]>(), '["first","middle","last"]'))
          .addSteps(s => s.addStepGroup(c => c))
          .addExpressionOutput("result", (ctx) =>
            expr.last(expr.deserializeRecord(ctx.inputs.arr))
          )
        )
        
        ;

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Array Operations - array with mixed types", () => {
  const spec: ParitySpec = {
    category: "Array Operations",
    name: "array with mixed types",
    inputs: { arr: '[1,"two",true]' },
    argoExpression: "toJson(fromJSON(inputs.parameters.arr))",
    expectedResult: '[1,"two",true]',
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({
        inputs: spec.inputs,
        expression: spec.argoExpression,
      });
      expect(result.phase).toBe("Succeeded");
      const parsed = JSON.parse(result.globalOutputs.result);
      expect(parsed).toEqual([1, "two", true]);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - serialize", () => {
    const builderVariant: BuilderVariant = {
      name: "serialize",
      code: 'expr.serialize(expr.deserializeRecord(ctx.inputs.arr))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
          .addRequiredInput("arr", typeToken<(number | string | boolean)[]>())
          .addSteps(s => s.addStepGroup(c => c))
          .addExpressionOutput("result", (ctx) =>
            expr.serialize(expr.deserializeRecord(ctx.inputs.arr))
          )
        )
        
        ;

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, { arr: spec.inputs!.arr });
      expect(result.phase).toBe("Succeeded");
      const parsed = JSON.parse(result.globalOutputs.result);
      expect(parsed).toEqual([1, "two", true]);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Array Operations - empty array length", () => {
  const spec: ParitySpec = {
    category: "Array Operations",
    name: "empty array length",
    inputs: { arr: '[]' },
    argoExpression: "string(len(fromJSON(inputs.parameters.arr)))",
    expectedResult: "0",
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

  describe("Builder - length", () => {
    const builderVariant: BuilderVariant = {
      name: "length",
      code: 'expr.toString(expr.length(expr.deserializeRecord(ctx.inputs.arr)))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
          .addOptionalInput("arr", () => expr.stringToRecord(typeToken<string[]>(), '[]'))
          .addSteps(s => s.addStepGroup(c => c))
          .addExpressionOutput("result", (ctx) =>
            expr.toString(expr.length(expr.deserializeRecord(ctx.inputs.arr)))
          )
        )
        
        ;

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Array Operations - nested array access", () => {
  const spec: ParitySpec = {
    category: "Array Operations",
    name: "nested array access",
    inputs: { data: '{"items":[[1,2],[3,4]]}' },
    argoExpression: "string(jsonpath(inputs.parameters.data, '$.items[1][0]'))",
    expectedResult: "3",
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
});
