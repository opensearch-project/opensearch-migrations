import { submitProbe } from "../infra/probeHelper";

describe("JSONPath Contract Tests", () => {
  test("extracts number as bare string", async () => {
    const result = await submitProbe({
      inputs: { data: '{"key":1}' },
      expression: "jsonpath(inputs.parameters.data, '$.key')",
    });
    
    console.log("Result:", JSON.stringify({ 
      phase: result.phase, 
      globalOutputs: result.globalOutputs, 
      evalNodeOutput: result.nodeOutputs["eval"]
    }, null, 2));
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("1");
  });

  test("extracts string without extra quotes", async () => {
    const result = await submitProbe({
      inputs: { data: '{"key":"hello"}' },
      expression: "jsonpath(inputs.parameters.data, '$.key')",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("hello");
  });

  test("extracts boolean as lowercase", async () => {
    const result = await submitProbe({
      inputs: { data: '{"flag":true}' },
      expression: "jsonpath(inputs.parameters.data, '$.flag')",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("true");
  });

  test("extracts nested object as JSON", async () => {
    const result = await submitProbe({
      inputs: { data: '{"outer":{"inner":"val"}}' },
      expression: "jsonpath(inputs.parameters.data, '$.outer')",
    });
    
    expect(result.phase).toBe("Succeeded");
    // Argo returns Go map format, not JSON
    expect(result.globalOutputs.result).toBe("map[inner:val]");
  });

  test("extracts array as JSON", async () => {
    const result = await submitProbe({
      inputs: { data: '{"items":[1,2,3]}' },
      expression: "jsonpath(inputs.parameters.data, '$.items')",
    });
    
    expect(result.phase).toBe("Succeeded");
    // Argo returns Go slice format, not JSON
    expect(result.globalOutputs.result).toBe("[1 2 3]");
  });

  test("extracts null", async () => {
    const result = await submitProbe({
      inputs: { data: '{"key":null}' },
      expression: "jsonpath(inputs.parameters.data, '$.key')",
    });
    
    expect(result.phase).toBe("Succeeded");
    // Argo returns Go nil representation
    expect(result.globalOutputs.result).toBe("<nil>");
  });

  test("nested path extraction", async () => {
    const result = await submitProbe({
      inputs: { data: '{"a":{"b":{"c":"deep"}}}' },
      expression: "jsonpath(inputs.parameters.data, '$.a.b.c')",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("deep");
  });

  test("array index extraction", async () => {
    const result = await submitProbe({
      inputs: { data: '{"items":["a","b","c"]}' },
      expression: "jsonpath(inputs.parameters.data, '$.items[1]')",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("b");
  });

  test("missing path behavior", async () => {
    const result = await submitProbe({
      inputs: { data: '{"key":"val"}' },
      expression: "jsonpath(inputs.parameters.data, '$.missing')",
    });
    
    // Missing path returns empty string, workflow succeeds
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("");
  });
});
