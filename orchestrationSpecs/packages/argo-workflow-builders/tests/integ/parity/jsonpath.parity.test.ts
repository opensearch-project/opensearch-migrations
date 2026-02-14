import { renderWorkflowTemplate, expr } from "../../../src/index.js";
import { submitProbe, submitRenderedWorkflow } from "../infra/probeHelper.js";
import { ParitySpec, BuilderVariant, reportContractResult, reportParityResult } from "../infra/parityHelper.js";

describe("JSONPath - extract number as bare string", () => {
  const spec: ParitySpec = {
    category: "JSONPath",
    name: "extract number as bare string",
    inputs: { data: '{"key":1}' },
    argoExpression: "jsonpath(inputs.parameters.data, '$.key')",
    expectedResult: "1",
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

  describe("Builder - jsonPathStrict", () => {
    const builderVariant: BuilderVariant = {
      name: "jsonPathStrict",
      code: 'expr.jsonPathStrict(expr.cast(expr.literal("{{workflow.parameters.data}}")).to<any>(), "key")',
    };

    test("builder API produces same result", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: "jp-num" })
        .addParams({ data: defineParam({ expression: spec.inputs!.data }) })
        .addTemplate("main", t => t
          .addSteps(s => s.addStepGroup(c => c))
          .addExpressionOutput("result", () =>
            expr.jsonPathStrict(expr.cast(expr.literal("{{workflow.parameters.data}}")).to<any>(), "key")
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

describe("JSONPath - extract string without extra quotes", () => {
  const spec: ParitySpec = {
    category: "JSONPath",
    name: "extract string without extra quotes",
    inputs: { data: '{"key":"hello"}' },
    argoExpression: "jsonpath(inputs.parameters.data, '$.key')",
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

  describe("Builder - jsonPathStrict", () => {
    const builderVariant: BuilderVariant = {
      name: "jsonPathStrict",
      code: 'expr.jsonPathStrict(expr.cast(expr.literal("{{workflow.parameters.data}}")).to<any>(), "key")',
    };

    test("builder API produces same result", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: "jp-str" })
        .addParams({ data: defineParam({ expression: spec.inputs!.data }) })
        .addTemplate("main", t => t
          .addSteps(s => s.addStepGroup(c => c))
          .addExpressionOutput("result", () =>
            expr.jsonPathStrict(expr.cast(expr.literal("{{workflow.parameters.data}}")).to<any>(), "key")
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

describe("JSONPath - extract boolean as lowercase", () => {
  const spec: ParitySpec = {
    category: "JSONPath",
    name: "extract boolean as lowercase",
    inputs: { data: '{"flag":true}' },
    argoExpression: "jsonpath(inputs.parameters.data, '$.flag')",
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
});

describe("JSONPath - extract nested object as JSON", () => {
  const spec: ParitySpec = {
    category: "JSONPath",
    name: "extract nested object as JSON",
    inputs: { data: '{"outer":{"inner":"val"}}' },
    argoExpression: "jsonpath(inputs.parameters.data, '$.outer')",
    expectedResult: "map[inner:val]",
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

describe("JSONPath - extract array as JSON", () => {
  const spec: ParitySpec = {
    category: "JSONPath",
    name: "extract array as JSON",
    inputs: { data: '{"items":[1,2,3]}' },
    argoExpression: "jsonpath(inputs.parameters.data, '$.items')",
    expectedResult: "[1 2 3]",
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

describe("JSONPath - extract null", () => {
  const spec: ParitySpec = {
    category: "JSONPath",
    name: "extract null",
    inputs: { data: '{"key":null}' },
    argoExpression: "jsonpath(inputs.parameters.data, '$.key')",
    expectedResult: "<nil>",
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

describe("JSONPath - nested path extraction", () => {
  const spec: ParitySpec = {
    category: "JSONPath",
    name: "nested path extraction",
    inputs: { data: '{"a":{"b":{"c":"deep"}}}' },
    argoExpression: "jsonpath(inputs.parameters.data, '$.a.b.c')",
    expectedResult: "deep",
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

describe("JSONPath - array index extraction", () => {
  const spec: ParitySpec = {
    category: "JSONPath",
    name: "array index extraction",
    inputs: { data: '{"items":["a","b","c"]}' },
    argoExpression: "jsonpath(inputs.parameters.data, '$.items[1]')",
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
});

describe("JSONPath - missing path causes Error", () => {
  const spec: ParitySpec = {
    category: "JSONPath",
    name: "missing path causes Error",
    inputs: { data: '{"key":"val"}' },
    argoExpression: "jsonpath(inputs.parameters.data, '$.missing')",
    expectedPhase: "Error",
  };

  describe("ArgoYaml", () => {
    test("raw expression fails as expected", async () => {
      const result = await submitProbe({
        inputs: spec.inputs,
        expression: spec.argoExpression,
      });
      expect(result.phase).toBe("Error");
      reportContractResult(spec, result);
    });
  });
});
