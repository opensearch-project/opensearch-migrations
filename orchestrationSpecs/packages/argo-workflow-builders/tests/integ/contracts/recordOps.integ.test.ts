import { submitProbe } from "../infra/probeHelper";

describe("Record/Dict Operations Contract Tests", () => {
  test("sprig.dict creates object", async () => {
    const result = await submitProbe({
      expression: "toJson(sprig.dict('a', '1', 'b', '2'))",
    });
    
    expect(result.phase).toBe("Succeeded");
    const parsed = JSON.parse(result.globalOutputs.result);
    expect(parsed).toEqual({ a: "1", b: "2" });
  });

  test.skip("sprig.merge combines objects", async () => {
    const result = await submitProbe({
      inputs: { 
        obj1: '{"a":1,"b":2}',
        obj2: '{"b":3,"c":4}'
      },
      expression: "toJson(sprig.merge(fromJSON(inputs.parameters.obj1), fromJSON(inputs.parameters.obj2)))",
    });
    
    expect(result.phase).toBe("Succeeded");
    const parsed = JSON.parse(result.globalOutputs.result);
    // Merge should have obj2 values override obj1
    expect(parsed.a).toBe(1);
    expect(parsed.b).toBe(3); // obj2 wins
    expect(parsed.c).toBe(4);
  });

  test.skip("sprig.omit removes keys", async () => {
    const result = await submitProbe({
      inputs: { obj: '{"a":1,"b":2,"c":3}' },
      expression: "toJson(sprig.omit(fromJSON(inputs.parameters.obj), 'b'))",
    });
    
    expect(result.phase).toBe("Succeeded");
    const parsed = JSON.parse(result.globalOutputs.result);
    expect(parsed).toEqual({ a: 1, c: 3 });
    expect(parsed.b).toBeUndefined();
  });

  test.skip("sprig.dig with default for missing key", async () => {
    const result = await submitProbe({
      inputs: { obj: '{"a":{"b":"value"}}' },
      expression: "sprig.dig('a', 'missing', 'default', fromJSON(inputs.parameters.obj))",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("default");
  });

  test.skip("sprig.dig extracts nested value", async () => {
    const result = await submitProbe({
      inputs: { obj: '{"a":{"b":"found"}}' },
      expression: "sprig.dig('a', 'b', 'default', fromJSON(inputs.parameters.obj))",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("found");
  });

  test.skip("keys returns array of keys", async () => {
    const result = await submitProbe({
      inputs: { obj: '{"a":1,"b":2,"c":3}' },
      expression: "toJson(keys(fromJSON(inputs.parameters.obj)))",
    });
    
    expect(result.phase).toBe("Succeeded");
    const parsed = JSON.parse(result.globalOutputs.result);
    expect(parsed.sort()).toEqual(["a", "b", "c"]);
  });

  test.skip("'in' operator checks key existence - true", async () => {
    const result = await submitProbe({
      inputs: { obj: '{"a":1,"b":2}' },
      expression: "string('a' in fromJSON(inputs.parameters.obj))",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("true");
  });

  test.skip("'in' operator checks key existence - false", async () => {
    const result = await submitProbe({
      inputs: { obj: '{"a":1,"b":2}' },
      expression: "string('c' in fromJSON(inputs.parameters.obj))",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("false");
  });

  test.skip("bracket notation for key access", async () => {
    const result = await submitProbe({
      inputs: { obj: '{"key":"value"}' },
      expression: "fromJSON(inputs.parameters.obj)['key']",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("value");
  });

  test.skip("bracket notation with special characters in key", async () => {
    const result = await submitProbe({
      inputs: { obj: '{"key-with-dash":"value"}' },
      expression: "fromJSON(inputs.parameters.obj)['key-with-dash']",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("value");
  });
});
