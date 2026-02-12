import { submitProbe } from "../infra/probeHelper";

describe("JSONPath Contract Tests", () => {
  test("extracts number as bare string", async () => {
    const result = await submitProbe({
      inputs: { data: '{"key":1}' },
      expression: "jsonpath(inputs.parameters.data, '$.key')",
    });
    
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
    const parsed = JSON.parse(result.globalOutputs.result);
    expect(parsed).toEqual({ inner: "val" });
  });

  test("extracts array as JSON", async () => {
    const result = await submitProbe({
      inputs: { data: '{"items":[1,2,3]}' },
      expression: "jsonpath(inputs.parameters.data, '$.items')",
    });
    
    expect(result.phase).toBe("Succeeded");
    const parsed = JSON.parse(result.globalOutputs.result);
    expect(parsed).toEqual([1, 2, 3]);
  });

  test("extracts null", async () => {
    const result = await submitProbe({
      inputs: { data: '{"key":null}' },
      expression: "jsonpath(inputs.parameters.data, '$.key')",
    });
    
    expect(result.phase).toBe("Succeeded");
    // Document actual behavior
    console.log("Null extraction result:", result.globalOutputs.result);
    expect(result.globalOutputs.result).toBe("null");
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
    try {
      const result = await submitProbe({
        inputs: { data: '{"key":"val"}' },
        expression: "jsonpath(inputs.parameters.data, '$.missing')",
      });
      
      // If it succeeds, document what it returns
      console.log("Missing path result:", result.globalOutputs.result);
      expect(result.phase).toBe("Succeeded");
    } catch (err) {
      // If it fails, that's also valid behavior - document it
      console.log("Missing path causes workflow failure:", err);
      throw err;
    }
  });
});
