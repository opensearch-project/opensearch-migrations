import { renderWorkflowTemplate, expr, typeToken } from "../../../src/index.js";
import { submitProbe, submitRenderedWorkflow } from "../infra/probeHelper.js";
import { ParitySpec, BuilderVariant, reportContractResult, reportParityResult } from "../infra/parityHelper.js";
import { makeTestWorkflow } from "../infra/testWorkflowHelper.js";

type NestedPayload = { nested: { value: number } };
type FlagPayload = { flag: boolean };
type StructuredPayload = { a: number; b: string; c: boolean };

describe("Type Coercion - toJSON on string parameter double-serializes", () => {
  const spec: ParitySpec = {
    category: "Type Coercion",
    name: "toJSON on string parameter double-serializes",
    inputs: { data: '{"a":1}' },
    argoExpression: "toJson(inputs.parameters.data)",
    expectedResult: '"{\\\"a\\\":1}"',
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

  describe.skip("Builder - serialize", () => {
    const builderVariant: BuilderVariant = {
      name: "serialize",
      code: 'expr.serialize(ctx.inputs.data)',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addOptionalInput("data", () => '{"a":1}')
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", (ctx) =>
          expr.serialize(ctx.inputs.data)
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Type Coercion - fromJSON then toJSON preserves structure", () => {
  const spec: ParitySpec = {
    category: "Type Coercion",
    name: "fromJSON then toJSON preserves structure",
    inputs: { data: '{"a":1,"b":"two","c":true}' },
    argoExpression: "toJson(fromJSON(inputs.parameters.data))",
    expectedResult: '{"a":1,"b":"two","c":true}',
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({
        inputs: spec.inputs,
        expression: spec.argoExpression,
      });
      expect(result.phase).toBe("Succeeded");
      expect(JSON.parse(result.globalOutputs.result)).toEqual(JSON.parse(spec.inputs!.data));
      reportContractResult(spec, result);
    });
  });

  describe("Builder - serialize/deserializeRecord", () => {
    const builderVariant: BuilderVariant = {
      name: "serialize/deserializeRecord",
      code: 'expr.serialize(expr.deserializeRecord(ctx.inputs.data))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
          .addRequiredInput("data", typeToken<StructuredPayload>())
          .addSteps(s => s.addStepGroup(c => c))
          .addExpressionOutput("result", (ctx) =>
            expr.serialize(expr.deserializeRecord(ctx.inputs.data))
          )
        )
        
        ;

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, { data: spec.inputs!.data });
      expect(result.phase).toBe("Succeeded");
      expect(JSON.parse(result.globalOutputs.result)).toEqual(JSON.parse(spec.inputs!.data));
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Type Coercion - nested fromJSON extracts then re-serializes", () => {
  const spec: ParitySpec = {
    category: "Type Coercion",
    name: "nested fromJSON extracts then re-serializes",
    inputs: { data: '{"nested":{"value":42}}' },
    argoExpression: "toJson(fromJSON(inputs.parameters.data).nested)",
    expectedResult: '{"value":42}',
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({
        inputs: spec.inputs,
        expression: spec.argoExpression,
      });
      expect(result.phase).toBe("Succeeded");
      expect(JSON.parse(result.globalOutputs.result)).toEqual({ value: 42 });
      reportContractResult(spec, result);
    });
  });

  describe("Builder - get/serialize", () => {
    const builderVariant: BuilderVariant = {
      name: "get/serialize",
      code: 'expr.serialize(expr.get(expr.deserializeRecord(ctx.inputs.data), "nested"))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
          .addRequiredInput("data", typeToken<NestedPayload>())
          .addSteps(s => s.addStepGroup(c => c))
          .addExpressionOutput("result", (ctx) =>
            expr.serialize(
              expr.get(expr.deserializeRecord(ctx.inputs.data), "nested")
            )
          )
        )
        
        ;

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, { data: spec.inputs!.data });
      expect(result.phase).toBe("Succeeded");
      expect(JSON.parse(result.globalOutputs.result)).toEqual({ value: 42 });
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Type Coercion - fromJSON on number field returns number type", () => {
  const spec: ParitySpec = {
    category: "Type Coercion",
    name: "fromJSON on number field returns number type",
    inputs: { data: '{"num":42}' },
    argoExpression: "string(fromJSON(inputs.parameters.data).num + 10)",
    expectedResult: "52",
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

describe("Type Coercion - fromJSON boolean can be used in conditionals", () => {
  const spec: ParitySpec = {
    category: "Type Coercion",
    name: "fromJSON boolean can be used in conditionals",
    inputs: { data: '{"flag":true}' },
    argoExpression: "fromJSON(inputs.parameters.data).flag ? 'yes' : 'no'",
    expectedResult: "yes",
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

  describe("Builder - ternary", () => {
    const builderVariant: BuilderVariant = {
      name: "ternary",
      code: 'expr.ternary(expr.get(expr.deserializeRecord(ctx.inputs.data), "flag"), expr.literal("yes"), expr.literal("no"))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
          .addRequiredInput("data", typeToken<FlagPayload>())
          .addSteps(s => s.addStepGroup(c => c))
          .addExpressionOutput("result", (ctx) =>
            expr.ternary(
              expr.get(expr.deserializeRecord(ctx.inputs.data), "flag"),
              expr.literal("yes"),
              expr.literal("no")
            )
          )
        )
        
        ;

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, { data: spec.inputs!.data });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Type Coercion - string() on number", () => {
  const spec: ParitySpec = {
    category: "Type Coercion",
    name: "string() on number",
    inputs: {},
    argoExpression: "string(42)",
    expectedResult: "42",
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

  describe("Builder - toString", () => {
    const builderVariant: BuilderVariant = {
      name: "toString",
      code: 'expr.toString(42)',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
          .addSteps(s => s.addStepGroup(c => c))
          .addExpressionOutput("result", () => expr.toString(42))
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
