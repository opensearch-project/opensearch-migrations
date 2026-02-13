import { submitProbe } from "../infra/probeHelper";

describe("String Operations Contract Tests", () => {
  test("string concatenation with + operator", async () => {
    const result = await submitProbe({
      inputs: { a: "hello", b: "world" },
      expression: "inputs.parameters.a + ' ' + inputs.parameters.b",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("hello world");
  });

  test("split string", async () => {
    const result = await submitProbe({
      inputs: { text: "a,b,c" },
      expression: "toJson(split(inputs.parameters.text, ','))",
    });
    
    expect(result.phase).toBe("Succeeded");
    const parsed = JSON.parse(result.globalOutputs.result);
    expect(parsed).toEqual(["a", "b", "c"]);
  });

  test("toLowerCase", async () => {
    const result = await submitProbe({
      inputs: { text: "HELLO" },
      expression: "lower(inputs.parameters.text)",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("hello");
  });

  test("toUpperCase", async () => {
    const result = await submitProbe({
      inputs: { text: "hello" },
      expression: "upper(inputs.parameters.text)",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("HELLO");
  });

  test("string length", async () => {
    const result = await submitProbe({
      inputs: { text: "hello" },
      expression: "string(len(inputs.parameters.text))",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("5");
  });

  test.skip("regexMatch returns boolean", async () => {
    const result = await submitProbe({
      inputs: { text: "hello123" },
      expression: "string(regexMatch('[0-9]+', inputs.parameters.text))",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("true");
  });

  test.skip("regexFind extracts match", async () => {
    const result = await submitProbe({
      inputs: { text: "hello123world" },
      expression: "regexFind('[0-9]+', inputs.parameters.text)",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("123");
  });

  test.skip("regexReplaceAll", async () => {
    const result = await submitProbe({
      inputs: { text: "hello123world456" },
      expression: "regexReplaceAll('[0-9]+', 'X', inputs.parameters.text)",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("helloXworldX");
  });

  test("empty string length is zero", async () => {
    const result = await submitProbe({
      inputs: { text: "" },
      expression: "string(len(inputs.parameters.text))",
    });
    
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("0");
  });
});
