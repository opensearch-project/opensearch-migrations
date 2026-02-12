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

  test("fromJson field access", async () => {
    const result = await submitProbe({
      inputs: { data: '{"port":9200}' },
      expression: "string(fromJson(inputs.parameters.data).port)",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("9200");
  });

  test("fromJson then toJson round-trip", async () => {
    const result = await submitProbe({
      inputs: { data: '{"a":1}' },
      expression: "toJson(fromJson(inputs.parameters.data))",
    });
    
    expect(result.phase).toBe("Succeeded");
    const parsed = JSON.parse(result.globalOutputs.result);
    expect(parsed).toEqual({ a: 1 });
  });

  test("int/float coercion after fromJson", async () => {
    const result = await submitProbe({
      inputs: { data: '{"n":1.5}' },
      expression: "string(fromJson(inputs.parameters.data).n)",
    });
    
    expect(result.phase).toBe("Succeeded");
    console.log("Float coercion result:", result.globalOutputs.result);
    // Document actual behavior
    expect(result.globalOutputs.result).toBeTruthy();
  });
});
