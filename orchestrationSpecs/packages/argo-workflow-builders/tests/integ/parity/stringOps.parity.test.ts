import { renderWorkflowTemplate, expr } from "../../../src/index.js";
import { submitProbe, submitRenderedWorkflow } from "../infra/probeHelper.js";
import { ParitySpec, BuilderVariant, reportContractResult, reportKnownBroken, reportParityResult } from "../infra/parityHelper.js";
import { makeTestWorkflow } from "../infra/testWorkflowHelper.js";
import { describeBroken } from "../infra/brokenTestControl.js";

describe("String Operations - concatenation with + operator", () => {
  const spec: ParitySpec = {
    category: "String Operations",
    name: "concatenation with + operator",
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

describe("String Operations - split string", () => {
  const spec: ParitySpec = {
    category: "String Operations",
    name: "split string",
    inputs: { text: "a,b,c" },
    argoExpression: "toJson(split(inputs.parameters.text, ','))",
    expectedResult: '["a","b","c"]',
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({
        inputs: spec.inputs,
        expression: spec.argoExpression,
      });
      expect(result.phase).toBe("Succeeded");
      const parsed = JSON.parse(result.globalOutputs.result);
      expect(parsed).toEqual(["a", "b", "c"]);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - split", () => {
    const builderVariant: BuilderVariant = {
      name: "split",
      code: 'expr.serialize(expr.split(ctx.inputs.text, ","))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addOptionalInput("text", () => "a,b,c")
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", (ctx) =>
          expr.serialize(expr.split(ctx.inputs.text, ","))
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      const parsed = JSON.parse(result.globalOutputs.result);
      expect(parsed).toEqual(["a", "b", "c"]);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("String Operations - toLowerCase", () => {
  const spec: ParitySpec = {
    category: "String Operations",
    name: "toLowerCase",
    inputs: { text: "HELLO" },
    argoExpression: "lower(inputs.parameters.text)",
    expectedResult: "hello",
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

  describe("Builder - toLowerCase", () => {
    const builderVariant: BuilderVariant = {
      name: "toLowerCase",
      code: 'expr.toLowerCase(ctx.inputs.text)',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addOptionalInput("text", () => "HELLO")
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", (ctx) =>
          expr.toLowerCase(ctx.inputs.text)
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

describe("String Operations - toUpperCase", () => {
  const spec: ParitySpec = {
    category: "String Operations",
    name: "toUpperCase",
    inputs: { text: "hello" },
    argoExpression: "upper(inputs.parameters.text)",
    expectedResult: "HELLO",
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

  describe("Builder - toUpperCase", () => {
    const builderVariant: BuilderVariant = {
      name: "toUpperCase",
      code: 'expr.toUpperCase(ctx.inputs.text)',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addOptionalInput("text", () => "hello")
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", (ctx) =>
          expr.toUpperCase(ctx.inputs.text)
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

describe("String Operations - string length", () => {
  const spec: ParitySpec = {
    category: "String Operations",
    name: "string length",
    inputs: { text: "hello" },
    argoExpression: "string(len(inputs.parameters.text))",
    expectedResult: "5",
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
      code: 'expr.toString(expr.length(ctx.inputs.text))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addOptionalInput("text", () => "hello")
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", (ctx) =>
          expr.toString(expr.length(ctx.inputs.text))
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

describeBroken("String Operations - regexMatch returns boolean", () => {
  const spec: ParitySpec = {
    category: "String Operations",
    name: "regexMatch returns boolean",
    inputs: { text: "hello123" },
    argoExpression: "string(regexMatch('[0-9]+', inputs.parameters.text))",
    expectedResult: "true",
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

  describe("Builder - regexMatch", () => {
    const builderVariant: BuilderVariant = {
      name: "regexMatch",
      code: 'expr.toString(expr.regexMatch("[0-9]+", ctx.inputs.text))',
    };
    reportKnownBroken(spec, builderVariant, "Runtime Error: regex helper mapping mismatch between builder expressions and Argo runtime.");

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addOptionalInput("text", () => "hello123")
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", (ctx) =>
          expr.toString(expr.regexMatch("[0-9]+", ctx.inputs.text))
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

describeBroken("String Operations - regexFind extracts match", () => {
  const spec: ParitySpec = {
    category: "String Operations",
    name: "regexFind extracts match",
    inputs: { text: "hello123world" },
    argoExpression: "regexFind('[0-9]+', inputs.parameters.text)",
    expectedResult: "123",
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

  describe("Builder - regexFind", () => {
    const builderVariant: BuilderVariant = {
      name: "regexFind",
      code: 'expr.regexFind("[0-9]+", ctx.inputs.text)',
    };
    reportKnownBroken(spec, builderVariant, "Runtime Error: regex helper mapping mismatch between builder expressions and Argo runtime.");

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addOptionalInput("text", () => "hello123world")
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", (ctx) =>
          expr.regexFind("[0-9]+", ctx.inputs.text)
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

describeBroken("String Operations - regexReplaceAll", () => {
  const spec: ParitySpec = {
    category: "String Operations",
    name: "regexReplaceAll",
    inputs: { text: "hello123world456" },
    argoExpression: "regexReplaceAll('[0-9]+', 'X', inputs.parameters.text)",
    expectedResult: "helloXworldX",
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

  describe("Builder - regexReplaceAll", () => {
    const builderVariant: BuilderVariant = {
      name: "regexReplaceAll",
      code: 'expr.regexReplaceAll("[0-9]+", "X", ctx.inputs.text)',
    };
    reportKnownBroken(spec, builderVariant, "Runtime Error: regex helper mapping mismatch between builder expressions and Argo runtime.");

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addOptionalInput("text", () => "hello123world456")
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", (ctx) =>
          expr.regexReplaceAll("[0-9]+", "X", ctx.inputs.text)
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

describe("String Operations - empty string length is zero", () => {
  const spec: ParitySpec = {
    category: "String Operations",
    name: "empty string length is zero",
    inputs: { text: "" },
    argoExpression: "string(len(inputs.parameters.text))",
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
      code: 'expr.toString(expr.length(ctx.inputs.text))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addOptionalInput("text", () => "")
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", (ctx) =>
          expr.toString(expr.length(ctx.inputs.text))
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
