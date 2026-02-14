import { renderWorkflowTemplate, expr, typeToken } from "../../../src/index.js";
import { submitProbe, submitRenderedWorkflow } from "../infra/probeHelper.js";
import { ParitySpec, BuilderVariant, reportContractResult, reportParityResult } from "../infra/parityHelper.js";
import { makeTestWorkflow } from "../infra/testWorkflowHelper.js";

describe("Expression Evaluation - ternary true branch", () => {
  const spec: ParitySpec = {
    category: "Expression Evaluation",
    name: "ternary true branch",
    inputs: { count: "5" },
    argoExpression: "asInt(inputs.parameters.count) > 3 ? 'high' : 'low'",
    expectedResult: "high",
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
      code: 'expr.ternary(expr.greaterThan(expr.deserializeRecord(ctx.inputs.count), expr.literal(3)), expr.literal("high"), expr.literal("low"))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("count", typeToken<number>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", (ctx) =>
          expr.ternary(
            expr.greaterThan(expr.deserializeRecord(ctx.inputs.count), expr.literal(3)),
            expr.literal("high"),
            expr.literal("low")
          )
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, { count: spec.inputs!.count });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Expression Evaluation - ternary false branch", () => {
  const spec: ParitySpec = {
    category: "Expression Evaluation",
    name: "ternary false branch",
    inputs: { count: "1" },
    argoExpression: "asInt(inputs.parameters.count) > 3 ? 'high' : 'low'",
    expectedResult: "low",
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
      code: 'expr.ternary(expr.greaterThan(expr.deserializeRecord(ctx.inputs.count), expr.literal(3)), expr.literal("high"), expr.literal("low"))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("count", typeToken<number>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", (ctx) =>
          expr.ternary(
            expr.greaterThan(expr.deserializeRecord(ctx.inputs.count), expr.literal(3)),
            expr.literal("high"),
            expr.literal("low")
          )
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, { count: spec.inputs!.count });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Expression Evaluation - string equality", () => {
  const spec: ParitySpec = {
    category: "Expression Evaluation",
    name: "string equality",
    inputs: { status: "ready" },
    argoExpression: "inputs.parameters.status == 'ready' ? 'go' : 'wait'",
    expectedResult: "go",
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

  describe("Builder - ternary/equals", () => {
    const builderVariant: BuilderVariant = {
      name: "ternary/equals",
      code: 'expr.ternary(expr.equals(ctx.inputs.status, expr.literal("ready")), expr.literal("go"), expr.literal("wait"))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addOptionalInput("status", () => "ready")
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", (ctx) =>
          expr.ternary(
            expr.equals(ctx.inputs.status, expr.literal("ready")),
            expr.literal("go"),
            expr.literal("wait")
          )
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

describe("Expression Evaluation - string concatenation", () => {
  const spec: ParitySpec = {
    category: "Expression Evaluation",
    name: "string concatenation",
    inputs: { a: "hello", b: "world" },
    argoExpression: "inputs.parameters.a + ' ' + inputs.parameters.b",
    expectedResult: "hello world",
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

  describe("Builder - concat", () => {
    const builderVariant: BuilderVariant = {
      name: "concat",
      code: 'expr.concat(ctx.inputs.a, expr.literal(" "), ctx.inputs.b)',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addOptionalInput("a", () => "hello")
        .addOptionalInput("b", () => "world")
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", (ctx) =>
          expr.concat(ctx.inputs.a, expr.literal(" "), ctx.inputs.b)
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

describe("Expression Evaluation - asInt then arithmetic", () => {
  const spec: ParitySpec = {
    category: "Expression Evaluation",
    name: "asInt then arithmetic",
    inputs: { x: "10" },
    argoExpression: "string(asInt(inputs.parameters.x) * 2)",
    expectedResult: "20",
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

describe("Expression Evaluation - asFloat", () => {
  const spec: ParitySpec = {
    category: "Expression Evaluation",
    name: "asFloat",
    inputs: { x: "3.14" },
    argoExpression: "string(asFloat(inputs.parameters.x))",
    expectedResult: "3.14",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({
        inputs: spec.inputs,
        expression: spec.argoExpression,
      });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toMatch(/3\.14/);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - cast", () => {
    const builderVariant: BuilderVariant = {
      name: "cast",
      code: 'expr.toString(expr.cast(ctx.inputs.x).to<number>())',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addOptionalInput("x", () => "3.14")
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", (ctx) =>
          expr.toString(expr.cast(ctx.inputs.x).to<number>())
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toMatch(/3\.14/);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Expression Evaluation - boolean expressions", () => {
  const spec: ParitySpec = {
    category: "Expression Evaluation",
    name: "boolean expressions",
    inputs: {},
    argoExpression: "true ? 'yes' : 'no'",
    expectedResult: "yes",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({
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
      code: 'expr.ternary(expr.literal(true), expr.literal("yes"), expr.literal("no"))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", () =>
          expr.ternary(expr.literal(true), expr.literal("yes"), expr.literal("no"))
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

describe("Expression Evaluation - nested ternary", () => {
  const spec: ParitySpec = {
    category: "Expression Evaluation",
    name: "nested ternary",
    inputs: { x: "5" },
    argoExpression: "asInt(inputs.parameters.x) > 10 ? 'big' : asInt(inputs.parameters.x) > 3 ? 'medium' : 'small'",
    expectedResult: "medium",
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
      code: 'expr.ternary(expr.greaterThan(expr.deserializeRecord(ctx.inputs.x), expr.literal(10)), expr.literal("big"), expr.ternary(expr.greaterThan(expr.deserializeRecord(ctx.inputs.x), expr.literal(3)), expr.literal("medium"), expr.literal("small")))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("x", typeToken<number>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", (ctx) =>
          expr.ternary(
            expr.greaterThan(expr.deserializeRecord(ctx.inputs.x), expr.literal(10)),
            expr.literal("big"),
            expr.ternary(
              expr.greaterThan(expr.deserializeRecord(ctx.inputs.x), expr.literal(3)),
              expr.literal("medium"),
              expr.literal("small")
            )
          )
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, { x: spec.inputs!.x });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});
