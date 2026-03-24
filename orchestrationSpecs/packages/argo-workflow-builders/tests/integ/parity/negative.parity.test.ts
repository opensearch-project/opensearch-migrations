import { submitChainProbe, submitProbe } from "../infra/probeHelper.js";
import { ParitySpec, reportContractResult } from "../infra/parityHelper.js";
import { WorkflowResult } from "../infra/workflowRunner.js";

async function submitProbeAllowError(config: { inputs?: Record<string, string>; expression: string }): Promise<WorkflowResult> {
  try {
    return await submitProbe(config);
  } catch (err: any) {
    return {
      phase: "Error",
      message: err?.message || String(err),
      globalOutputs: {},
      nodeOutputs: {},
      duration: 0,
      raw: {},
    };
  }
}

describe("Negative - invalid JSONPath syntax fails", () => {
  const spec: ParitySpec = {
    category: "Negative",
    name: "invalid JSONPath syntax fails",
    inputs: { data: '{"key":"value"}' },
    argoExpression: "jsonpath(inputs.parameters.data, 'invalid[[[syntax')",
    expectedPhase: "Error",
  };
  describe("ArgoYaml", () => {
    test("raw expression fails as expected", async () => {
      const result = await submitProbeAllowError({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).not.toBe("Succeeded");
      reportContractResult(spec, result);
    });
  });
});

describe("Negative - invalid parameter reference returns nil", () => {
  const spec: ParitySpec = {
    category: "Negative",
    name: "invalid parameter reference returns nil",
    inputs: { data: "test" },
    argoExpression: "inputs.parameters.nonexistent",
    expectedResult: "<nil>",
  };
  describe("ArgoYaml", () => {
    test("raw expression preserves Argo nil behavior", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Negative - invalid function name fails", () => {
  const spec: ParitySpec = {
    category: "Negative",
    name: "invalid function name fails",
    inputs: { data: '{"key":"value"}' },
    argoExpression: "invalidFunction(inputs.parameters.data)",
    expectedPhase: "Error",
  };
  describe("ArgoYaml", () => {
    test("raw expression fails as expected", async () => {
      const result = await submitProbeAllowError({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).not.toBe("Succeeded");
      reportContractResult(spec, result);
    });
  });
});

describe("Negative - type mismatch in arithmetic fails", () => {
  const spec: ParitySpec = {
    category: "Negative",
    name: "type mismatch in arithmetic fails",
    inputs: { text: "not-a-number" },
    argoExpression: "asInt(inputs.parameters.text) * 2",
    expectedPhase: "Error",
  };
  describe("ArgoYaml", () => {
    test("raw expression fails as expected", async () => {
      const result = await submitProbeAllowError({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).not.toBe("Succeeded");
      reportContractResult(spec, result);
    });
  });
});

describe("Negative - malformed JSON fromJSON fails", () => {
  const spec: ParitySpec = {
    category: "Negative",
    name: "malformed JSON fromJSON fails",
    inputs: { data: "{invalid json}" },
    argoExpression: "fromJSON(inputs.parameters.data)",
    expectedPhase: "Error",
  };
  describe("ArgoYaml", () => {
    test("raw expression fails as expected", async () => {
      const result = await submitProbeAllowError({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).not.toBe("Succeeded");
      reportContractResult(spec, result);
    });
  });
});

describe("Negative - wrong JSONPath result does not match", () => {
  const spec: ParitySpec = {
    category: "Negative",
    name: "wrong JSONPath result does not match",
    inputs: { data: '{"key":"value"}' },
    argoExpression: "jsonpath(inputs.parameters.data, '$.key')",
    expectedResult: "value",
  };
  describe("ArgoYaml", () => {
    test("raw expression returns expected not wrong value", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).not.toBe("wrong-value");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Negative - ternary evaluates correct branch", () => {
  const spec: ParitySpec = {
    category: "Negative",
    name: "ternary evaluates correct branch",
    inputs: { count: "5" },
    argoExpression: "asInt(inputs.parameters.count) > 3 ? 'high' : 'low'",
    expectedResult: "high",
  };
  describe("ArgoYaml", () => {
    test("raw expression returns high branch", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).not.toBe("low");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Negative - string concatenation order matters", () => {
  const spec: ParitySpec = {
    category: "Negative",
    name: "string concatenation order matters",
    inputs: { a: "hello", b: "world" },
    argoExpression: "inputs.parameters.a + ' ' + inputs.parameters.b",
    expectedResult: "hello world",
  };
  describe("ArgoYaml", () => {
    test("raw expression preserves order", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).not.toBe("world hello");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Negative - modified JSON detected", () => {
  const original = '{"a":1,"b":"two"}';
  const spec: ParitySpec = {
    category: "Negative",
    name: "modified JSON detected",
    inputs: { input: original },
    argoExpression: "inputs.parameters.input (chain passthrough)",
    expectedResult: original,
  };
  describe("ArgoYaml", () => {
    test("raw chain probe preserves exact JSON structure", async () => {
      const result = await submitChainProbe({ input: original, steps: [{ expression: "inputs.parameters.input" }] });
      expect(result.phase).toBe("Succeeded");
      expect(JSON.parse(result.globalOutputs.result)).toEqual(JSON.parse(original));
      reportContractResult(spec, result);
    });
  });
});

describe("Negative - empty string not null or undefined", () => {
  const spec: ParitySpec = {
    category: "Negative",
    name: "empty string not null or undefined",
    inputs: { input: "" },
    argoExpression: "inputs.parameters.input (chain passthrough)",
    expectedResult: "",
  };
  describe("ArgoYaml", () => {
    test("raw chain probe preserves empty string", async () => {
      const result = await submitChainProbe({ input: "", steps: [{ expression: "inputs.parameters.input" }] });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("");
      reportContractResult(spec, result);
    });
  });
});

describe("Negative - whitespace preserved exactly", () => {
  const spec: ParitySpec = {
    category: "Negative",
    name: "whitespace preserved exactly",
    inputs: { input: "  " },
    argoExpression: "inputs.parameters.input (chain passthrough)",
    expectedResult: "  ",
  };
  describe("ArgoYaml", () => {
    test("raw chain probe preserves two spaces", async () => {
      const result = await submitChainProbe({ input: "  ", steps: [{ expression: "inputs.parameters.input" }] });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("  ");
      reportContractResult(spec, result);
    });
  });
});

describe("Negative - number not stringified with quotes", () => {
  const spec: ParitySpec = {
    category: "Negative",
    name: "number not stringified with quotes",
    inputs: { data: '{"num":42}' },
    argoExpression: "jsonpath(inputs.parameters.data, '$.num')",
    expectedResult: "42",
  };
  describe("ArgoYaml", () => {
    test("raw expression returns bare numeric string", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).not.toBe('"42"');
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Negative - boolean lowercase not capitalized", () => {
  const spec: ParitySpec = {
    category: "Negative",
    name: "boolean lowercase not capitalized",
    inputs: { data: '{"flag":true}' },
    argoExpression: "jsonpath(inputs.parameters.data, '$.flag')",
    expectedResult: "true",
  };
  describe("ArgoYaml", () => {
    test("raw expression returns lowercase boolean", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).not.toBe("True");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Negative - arithmetic result exact", () => {
  const spec: ParitySpec = {
    category: "Negative",
    name: "arithmetic result exact",
    inputs: { x: "10" },
    argoExpression: "string(asInt(inputs.parameters.x) * 2)",
    expectedResult: "20",
  };
  describe("ArgoYaml", () => {
    test("raw expression returns exact arithmetic result", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});
