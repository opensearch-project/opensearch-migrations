import { submitProbe } from "../infra/probeHelper";

describe("Expression Evaluation Contract Tests", () => {
  test("ternary true branch", async () => {
    const result = await submitProbe({
      inputs: { count: "5" },
      expression: "asInt(inputs.parameters.count) > 3 ? 'high' : 'low'",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("high");
  });

  test("ternary false branch", async () => {
    const result = await submitProbe({
      inputs: { count: "1" },
      expression: "asInt(inputs.parameters.count) > 3 ? 'high' : 'low'",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("low");
  });

  test("string equality", async () => {
    const result = await submitProbe({
      inputs: { status: "ready" },
      expression: "inputs.parameters.status == 'ready' ? 'go' : 'wait'",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("go");
  });

  test("string concatenation", async () => {
    const result = await submitProbe({
      inputs: { a: "hello", b: "world" },
      expression: "inputs.parameters.a + ' ' + inputs.parameters.b",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("hello world");
  });

  test("asInt then arithmetic", async () => {
    const result = await submitProbe({
      inputs: { x: "10" },
      expression: "string(asInt(inputs.parameters.x) * 2)",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("20");
  });

  test("asFloat", async () => {
    const result = await submitProbe({
      inputs: { x: "3.14" },
      expression: "string(asFloat(inputs.parameters.x))",
    });
    
    expect(result.phase).toBe("Succeeded");
    console.log("asFloat result:", result.globalOutputs.result);
    expect(result.globalOutputs.result).toBeTruthy();
  });

  test("boolean expressions", async () => {
    const result = await submitProbe({
      expression: "true ? 'yes' : 'no'",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("yes");
  });

  test("nested ternary", async () => {
    const result = await submitProbe({
      inputs: { x: "5" },
      expression: "asInt(inputs.parameters.x) > 10 ? 'big' : asInt(inputs.parameters.x) > 3 ? 'medium' : 'small'",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("medium");
  });
});
