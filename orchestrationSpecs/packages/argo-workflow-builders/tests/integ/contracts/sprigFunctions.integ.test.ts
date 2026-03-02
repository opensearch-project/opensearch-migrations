import { submitProbe } from "../infra/probeHelper";

describe("Sprig Functions Contract Tests", () => {
  test("dict + toJson", async () => {
    const result = await submitProbe({
      expression: "toJson(sprig.dict('name', 'test', 'count', '42'))",
    });
    
    expect(result.phase).toBe("Succeeded");
    const parsed = JSON.parse(result.globalOutputs.result);
    expect(parsed).toEqual({ name: "test", count: "42" });
  });

  test("fromJSON field access", async () => {
    const result = await submitProbe({
      inputs: { data: '{"port":9200}' },
      expression: "string(fromJSON(inputs.parameters.data).port)",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("9200");
  });

  test("fromJSON then toJson round-trip", async () => {
    const result = await submitProbe({
      inputs: { data: '{"a":1}' },
      expression: "toJson(fromJSON(inputs.parameters.data))",
    });
    
    expect(result.phase).toBe("Succeeded");
    const parsed = JSON.parse(result.globalOutputs.result);
    expect(parsed).toEqual({ a: 1 });
  });

  test("fromJSON with nested object", async () => {
    const result = await submitProbe({
      inputs: { data: '{"outer":{"inner":"value"}}' },
      expression: "string(fromJSON(inputs.parameters.data).outer.inner)",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("value");
  });

  test("fromJSON with array access", async () => {
    const result = await submitProbe({
      inputs: { data: '{"items":["a","b","c"]}' },
      expression: "string(fromJSON(inputs.parameters.data).items[1])",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("b");
  });

  test("fromJSON number coercion", async () => {
    const result = await submitProbe({
      inputs: { data: '{"n":1.5}' },
      expression: "string(fromJSON(inputs.parameters.data).n)",
    });
    
    expect(result.phase).toBe("Succeeded");
    console.log("Float from fromJSON:", result.globalOutputs.result);
    expect(result.globalOutputs.result).toBeTruthy();
  });

  test("fromJSON boolean value", async () => {
    const result = await submitProbe({
      inputs: { data: '{"flag":true}' },
      expression: "string(fromJSON(inputs.parameters.data).flag)",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("true");
  });

  test("fromJSON null value", async () => {
    const result = await submitProbe({
      inputs: { data: '{"value":null}' },
      expression: "string(fromJSON(inputs.parameters.data).value)",
    });
    
    expect(result.phase).toBe("Succeeded");
    console.log("Null from fromJSON:", result.globalOutputs.result);
    expect(result.globalOutputs.result).toBeTruthy();
  });

  test("merge dictionaries", async () => {
    const result = await submitProbe({
      expression: "toJson(sprig.merge(sprig.dict('a', '1', 'b', '2'), sprig.dict('b', '3', 'c', '4')))",
    });
    
    expect(result.phase).toBe("Succeeded");
    const parsed = JSON.parse(result.globalOutputs.result);
    expect(parsed.a).toBe("1");
    expect(parsed.c).toBe("4");
  });

  test("omit keys from dict", async () => {
    const result = await submitProbe({
      expression: "toJson(sprig.omit(sprig.dict('a', '1', 'b', '2', 'c', '3'), 'b'))",
    });
    
    expect(result.phase).toBe("Succeeded");
    const parsed = JSON.parse(result.globalOutputs.result);
    expect(parsed.a).toBe("1");
    expect(parsed.c).toBe("3");
    expect(parsed.b).toBeUndefined();
  });

  test("dig nested values", async () => {
    const result = await submitProbe({
      inputs: { data: '{"user":{"role":{"name":"admin"}}}' },
      expression: "sprig.dig('user', 'role', 'name', 'guest', fromJSON(inputs.parameters.data))",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("admin");
  });

  test("dig with default value", async () => {
    const result = await submitProbe({
      inputs: { data: '{"user":{}}' },
      expression: "sprig.dig('user', 'role', 'name', 'guest', fromJSON(inputs.parameters.data))",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("guest");
  });

  test("keys function", async () => {
    const result = await submitProbe({
      inputs: { data: '{"a":1,"b":2,"c":3}' },
      expression: "toJson(keys(fromJSON(inputs.parameters.data)))",
    });
    
    expect(result.phase).toBe("Succeeded");
    const parsed = JSON.parse(result.globalOutputs.result);
    expect(parsed.sort()).toEqual(["a", "b", "c"]);
  });
});

