import { expr, renderWorkflowTemplate, typeToken } from "../../../src/index.js";
import { submitProbe, submitRenderedWorkflow } from "../infra/probeHelper.js";
import {
  BuilderVariant,
  ParitySpec,
  reportContractResult,
  reportParityResult,
} from "../infra/parityHelper.js";
import { makeTestWorkflow } from "../infra/testWorkflowHelper.js";

describe("Logical Operations - == with numbers", () => {
  const spec: ParitySpec = {
    category: "Logical Operations",
    name: "== with numbers",
    inputs: { a: "5", b: "5" },
    argoExpression: "string(asInt(inputs.parameters.a) == asInt(inputs.parameters.b))",
    expectedResult: "true",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - equals", () => {
    const builderVariant: BuilderVariant = {
      name: "equals",
      code: "expr.toString(expr.equals(expr.deserializeRecord(ctx.inputs.a), expr.deserializeRecord(ctx.inputs.b)))",
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("a", typeToken<number>())
        .addRequiredInput("b", typeToken<number>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.toString(
            expr.equals(expr.deserializeRecord(ctx.inputs.a), expr.deserializeRecord(ctx.inputs.b))
          )
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Logical Operations - less than", () => {
  const spec: ParitySpec = {
    category: "Logical Operations",
    name: "less than",
    inputs: { a: "3", b: "5" },
    argoExpression: "string(asInt(inputs.parameters.a) < asInt(inputs.parameters.b))",
    expectedResult: "true",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - lessThan", () => {
    const builderVariant: BuilderVariant = {
      name: "lessThan",
      code: "expr.toString(expr.lessThan(expr.deserializeRecord(ctx.inputs.a), expr.deserializeRecord(ctx.inputs.b)))",
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("a", typeToken<number>())
        .addRequiredInput("b", typeToken<number>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.toString(
            expr.lessThan(expr.deserializeRecord(ctx.inputs.a), expr.deserializeRecord(ctx.inputs.b))
          )
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Logical Operations - greater than", () => {
  const spec: ParitySpec = {
    category: "Logical Operations",
    name: "greater than",
    inputs: { a: "10", b: "5" },
    argoExpression: "string(asInt(inputs.parameters.a) > asInt(inputs.parameters.b))",
    expectedResult: "true",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - greaterThan", () => {
    const builderVariant: BuilderVariant = {
      name: "greaterThan",
      code: "expr.toString(expr.greaterThan(expr.deserializeRecord(ctx.inputs.a), expr.deserializeRecord(ctx.inputs.b)))",
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("a", typeToken<number>())
        .addRequiredInput("b", typeToken<number>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.toString(
            expr.greaterThan(expr.deserializeRecord(ctx.inputs.a), expr.deserializeRecord(ctx.inputs.b))
          )
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Logical Operations - logical and true", () => {
  const spec: ParitySpec = {
    category: "Logical Operations",
    name: "logical and true",
    argoExpression: "string(true && true)",
    expectedResult: "true",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - and", () => {
    const builderVariant: BuilderVariant = {
      name: "and",
      code: "expr.toString(expr.and(expr.literal(true), expr.literal(true)))",
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", () =>
          expr.toString(expr.and(expr.literal(true), expr.literal(true)))
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

describe("Logical Operations - logical or true", () => {
  const spec: ParitySpec = {
    category: "Logical Operations",
    name: "logical or true",
    argoExpression: "string(false || true)",
    expectedResult: "true",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - or", () => {
    const builderVariant: BuilderVariant = {
      name: "or",
      code: "expr.toString(expr.or(expr.literal(false), expr.literal(true)))",
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", () =>
          expr.toString(expr.or(expr.literal(false), expr.literal(true)))
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

describe("Logical Operations - logical not true", () => {
  const spec: ParitySpec = {
    category: "Logical Operations",
    name: "logical not true",
    argoExpression: "string(!true)",
    expectedResult: "false",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - not", () => {
    const builderVariant: BuilderVariant = {
      name: "not",
      code: "expr.toString(expr.not(expr.literal(true)))",
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", () =>
          expr.toString(expr.not(expr.literal(true)))
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

describe("Logical Operations - complex logical expression", () => {
  const spec: ParitySpec = {
    category: "Logical Operations",
    name: "complex logical expression",
    inputs: { a: "5", b: "10", c: "3" },
    argoExpression:
      "string((asInt(inputs.parameters.a) > asInt(inputs.parameters.c)) && (asInt(inputs.parameters.b) > asInt(inputs.parameters.a)))",
    expectedResult: "true",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - and/greaterThan", () => {
    const builderVariant: BuilderVariant = {
      name: "and/greaterThan",
      code: "expr.toString(expr.and(expr.greaterThan(expr.deserializeRecord(ctx.inputs.a), expr.deserializeRecord(ctx.inputs.c)), expr.greaterThan(expr.deserializeRecord(ctx.inputs.b), expr.deserializeRecord(ctx.inputs.a))))",
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("a", typeToken<number>())
        .addRequiredInput("b", typeToken<number>())
        .addRequiredInput("c", typeToken<number>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.toString(
            expr.and(
              expr.greaterThan(expr.deserializeRecord(ctx.inputs.a), expr.deserializeRecord(ctx.inputs.c)),
              expr.greaterThan(expr.deserializeRecord(ctx.inputs.b), expr.deserializeRecord(ctx.inputs.a))
            )
          )
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});
