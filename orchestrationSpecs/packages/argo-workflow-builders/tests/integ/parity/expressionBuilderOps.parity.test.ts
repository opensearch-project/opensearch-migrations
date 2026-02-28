import { expr, renderWorkflowTemplate, typeToken } from "../../../src/index.js";
import { submitProbe, submitRenderedWorkflow } from "../infra/probeHelper.js";
import { BuilderVariant, ParitySpec, reportContractResult, reportParityResult } from "../infra/parityHelper.js";
import { makeTestWorkflow } from "../infra/testWorkflowHelper.js";

describe("Expression Builder Ops - concatWith separator", () => {
  const spec: ParitySpec = {
    category: "Expression Builder Ops",
    name: "concatWith separator",
    inputs: { a: "one", b: "two", c: "three" },
    argoExpression: "inputs.parameters.a + '-' + inputs.parameters.b + '-' + inputs.parameters.c",
    expectedResult: "one-two-three",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, r);
    });
  });

  describe("Builder - concatWith", () => {
    const builderVariant: BuilderVariant = {
      name: "concatWith",
      code: 'expr.concatWith("-", ctx.inputs.a, ctx.inputs.b, ctx.inputs.c)',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("a", typeToken<string>())
        .addRequiredInput("b", typeToken<string>())
        .addRequiredInput("c", typeToken<string>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.concatWith("-", ctx.inputs.a, ctx.inputs.b, ctx.inputs.c)
        )
      );
      const rendered = renderWorkflowTemplate(wf);
      const r = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, r);
    });
  });
});

describe("Expression Builder Ops - isEmpty true", () => {
  const spec: ParitySpec = {
    category: "Expression Builder Ops",
    name: "isEmpty true",
    inputs: { x: "" },
    argoExpression: "string(len(inputs.parameters.x) == 0)",
    expectedResult: "true",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, r);
    });
  });

  describe("Builder - isEmpty", () => {
    const builderVariant: BuilderVariant = {
      name: "isEmpty",
      code: "expr.toString(expr.isEmpty(ctx.inputs.x))",
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("x", typeToken<string>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.toString(expr.isEmpty(ctx.inputs.x))
        )
      );
      const rendered = renderWorkflowTemplate(wf);
      const r = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, r);
    });
  });
});

describe("Expression Builder Ops - toArray literals", () => {
  const spec: ParitySpec = {
    category: "Expression Builder Ops",
    name: "toArray literals",
    inputs: {},
    argoExpression: "toJson(['x', 'y', 'z'])",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ expression: spec.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(JSON.parse(r.globalOutputs.result)).toEqual(["x", "y", "z"]);
      reportContractResult(spec, r);
    });
  });

  describe("Builder - toArray/recordToString", () => {
    const builderVariant: BuilderVariant = {
      name: "toArray/recordToString",
      code: 'expr.recordToString(expr.toArray("x", "y", "z"))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", () =>
          expr.recordToString(expr.toArray("x", "y", "z"))
        )
      );
      const rendered = renderWorkflowTemplate(wf);
      const r = await submitRenderedWorkflow(rendered);
      expect(r.phase).toBe("Succeeded");
      expect(JSON.parse(r.globalOutputs.result)).toEqual(["x", "y", "z"]);
      reportParityResult(spec, builderVariant, r);
    });
  });
});

describe("Expression Builder Ops - join split values", () => {
  const spec: ParitySpec = {
    category: "Expression Builder Ops",
    name: "join split values",
    inputs: { text: "a,b,c" },
    argoExpression: "join(split(inputs.parameters.text, ','))",
    expectedResult: "abc",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, r);
    });
  });

  describe("Builder - join/split", () => {
    const builderVariant: BuilderVariant = {
      name: "join/split",
      code: 'expr.join(expr.split(ctx.inputs.text, ","))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("text", typeToken<string>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.join(expr.split(ctx.inputs.text, ","))
        )
      );
      const rendered = renderWorkflowTemplate(wf);
      const r = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, r);
    });
  });
});
