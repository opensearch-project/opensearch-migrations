import { submitProbe } from "../infra/probeHelper";

describe("Array Operations Contract Tests", () => {
  test("array indexing with literal index", async () => {
    const result = await submitProbe({
      inputs: { arr: '["a","b","c"]' },
      expression: "fromJSON(inputs.parameters.arr)[1]",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("b");
  });

  test("array length", async () => {
    const result = await submitProbe({
      inputs: { arr: '["a","b","c"]' },
      expression: "string(len(fromJSON(inputs.parameters.arr)))",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("3");
  });

  test("last element of array", async () => {
    const result = await submitProbe({
      inputs: { arr: '["first","middle","last"]' },
      expression: "last(fromJSON(inputs.parameters.arr))",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("last");
  });

  test("array with mixed types", async () => {
    const result = await submitProbe({
      inputs: { arr: '[1,"two",true]' },
      expression: "toJson(fromJSON(inputs.parameters.arr))",
    });
    
    expect(result.phase).toBe("Succeeded");
    const parsed = JSON.parse(result.globalOutputs.result);
    expect(parsed).toEqual([1, "two", true]);
  });

  test("empty array length", async () => {
    const result = await submitProbe({
      inputs: { arr: '[]' },
      expression: "string(len(fromJSON(inputs.parameters.arr)))",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("0");
  });

  test("nested array access", async () => {
    const result = await submitProbe({
      inputs: { data: '{"items":[[1,2],[3,4]]}' },
      expression: "string(jsonpath(inputs.parameters.data, '$.items[1][0]'))",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("3");
  });
});
