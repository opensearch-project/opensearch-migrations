import { submitChainProbe, submitProbe } from "../infra/probeHelper";

describe("Parameter Pass-through Contract Tests", () => {
  test("JSON survives 1 hop", async () => {
    const input = '{"a":1,"b":"two"}';
    const result = await submitChainProbe({
      input,
      steps: [{ expression: "inputs.parameters.input" }],
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe(input);
  });

  test("JSON survives 2 hops", async () => {
    const input = '{"a":1,"b":"two"}';
    const result = await submitChainProbe({
      input,
      steps: [
        { expression: "inputs.parameters.input" },
        { expression: "inputs.parameters.input" },
      ],
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe(input);
  });

  test("empty string preserved", async () => {
    const result = await submitChainProbe({
      input: "",
      steps: [{ expression: "inputs.parameters.input" }],
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("");
  });

  test("whitespace-only preserved", async () => {
    const result = await submitChainProbe({
      input: "  ",
      steps: [{ expression: "inputs.parameters.input" }],
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("  ");
  });

  test("YAML-special chars survive", async () => {
    const input = '{"colon:key": "val: ue"}';
    const result = await submitChainProbe({
      input,
      steps: [{ expression: "inputs.parameters.input" }],
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe(input);
  });

  test("newlines in JSON", async () => {
    const input = '{"msg":"line1\\nline2"}';
    const result = await submitChainProbe({
      input,
      steps: [{ expression: "inputs.parameters.input" }],
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe(input);
  });

  test("very long value (4KB)", async () => {
    const input = "x".repeat(4096);
    const result = await submitChainProbe({
      input,
      steps: [{ expression: "inputs.parameters.input" }],
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe(input);
  });

  test("unicode characters", async () => {
    const input = '{"emoji":"🎉","jp":"日本語"}';
    const result = await submitChainProbe({
      input,
      steps: [{ expression: "inputs.parameters.input" }],
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe(input);
  });
});
