import { submitChainProbe } from "../infra/probeHelper.js";
import { ParitySpec, reportContractResult } from "../infra/parityHelper.js";

describe("Param Passthrough - JSON survives 1 hop", () => {
  const input = '{"a":1,"b":"two"}';
  const spec: ParitySpec = {
    category: "Param Passthrough",
    name: "JSON survives 1 hop",
    inputs: { input },
    argoExpression: "inputs.parameters.input (1 hop)",
    expectedResult: input,
  };

  describe("ArgoYaml", () => {
    test("raw chain probe preserves JSON", async () => {
      const result = await submitChainProbe({
        input,
        steps: [{ expression: "inputs.parameters.input" }],
      });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Param Passthrough - JSON survives 2 hops", () => {
  const input = '{"a":1,"b":"two"}';
  const spec: ParitySpec = {
    category: "Param Passthrough",
    name: "JSON survives 2 hops",
    inputs: { input },
    argoExpression: "inputs.parameters.input (2 hops)",
    expectedResult: input,
  };

  describe("ArgoYaml", () => {
    test("raw chain probe preserves JSON", async () => {
      const result = await submitChainProbe({
        input,
        steps: [
          { expression: "inputs.parameters.input" },
          { expression: "inputs.parameters.input" },
        ],
      });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Param Passthrough - empty string preserved", () => {
  const spec: ParitySpec = {
    category: "Param Passthrough",
    name: "empty string preserved",
    inputs: { input: "" },
    argoExpression: "inputs.parameters.input",
    expectedResult: "",
  };

  describe("ArgoYaml", () => {
    test("raw chain probe preserves empty string", async () => {
      const result = await submitChainProbe({
        input: "",
        steps: [{ expression: "inputs.parameters.input" }],
      });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Param Passthrough - whitespace-only preserved", () => {
  const spec: ParitySpec = {
    category: "Param Passthrough",
    name: "whitespace-only preserved",
    inputs: { input: "  " },
    argoExpression: "inputs.parameters.input",
    expectedResult: "  ",
  };

  describe("ArgoYaml", () => {
    test("raw chain probe preserves whitespace", async () => {
      const result = await submitChainProbe({
        input: "  ",
        steps: [{ expression: "inputs.parameters.input" }],
      });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Param Passthrough - YAML-special chars survive", () => {
  const input = '{"colon:key": "val: ue"}';
  const spec: ParitySpec = {
    category: "Param Passthrough",
    name: "YAML-special chars survive",
    inputs: { input },
    argoExpression: "inputs.parameters.input",
    expectedResult: input,
  };

  describe("ArgoYaml", () => {
    test("raw chain probe preserves YAML-special chars", async () => {
      const result = await submitChainProbe({
        input,
        steps: [{ expression: "inputs.parameters.input" }],
      });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Param Passthrough - newlines in JSON", () => {
  const input = '{"msg":"line1\\nline2"}';
  const spec: ParitySpec = {
    category: "Param Passthrough",
    name: "newlines in JSON",
    inputs: { input },
    argoExpression: "inputs.parameters.input",
    expectedResult: input,
  };

  describe("ArgoYaml", () => {
    test("raw chain probe preserves newlines", async () => {
      const result = await submitChainProbe({
        input,
        steps: [{ expression: "inputs.parameters.input" }],
      });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Param Passthrough - very long value (4KB)", () => {
  const input = "x".repeat(4096);
  const spec: ParitySpec = {
    category: "Param Passthrough",
    name: "very long value (4KB)",
    inputs: { input },
    argoExpression: "inputs.parameters.input",
    expectedResult: input,
  };

  describe("ArgoYaml", () => {
    test("raw chain probe preserves long value", async () => {
      const result = await submitChainProbe({
        input,
        steps: [{ expression: "inputs.parameters.input" }],
      });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Param Passthrough - unicode characters", () => {
  const input = '{"emoji":"🎉","jp":"日本語"}';
  const spec: ParitySpec = {
    category: "Param Passthrough",
    name: "unicode characters",
    inputs: { input },
    argoExpression: "inputs.parameters.input",
    expectedResult: input,
  };

  describe("ArgoYaml", () => {
    test("raw chain probe preserves unicode", async () => {
      const result = await submitChainProbe({
        input,
        steps: [{ expression: "inputs.parameters.input" }],
      });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});
