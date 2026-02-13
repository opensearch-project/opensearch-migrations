import { submitProbe } from "../infra/probeHelper";

describe("Type Coercion and Marshaling Contract Tests", () => {
  describe("toJSON/fromJSON coercion", () => {
    test("toJSON on string parameter double-serializes", async () => {
      const input = '{"a":1}';
      const result = await submitProbe({
        inputs: { data: input },
        expression: "toJson(inputs.parameters.data)",
      });

      expect(result.phase).toBe("Succeeded");
      // toJson on a string parameter serializes it (escapes quotes)
      expect(result.globalOutputs.result).toBe('"{\\\"a\\\":1}"');
    });
  });

  test("fromJSON then toJSON preserves structure", async () => {
    const input = '{"a":1,"b":"two","c":true}';
    const result = await submitProbe({
      inputs: { data: input },
      expression: "toJson(fromJSON(inputs.parameters.data))",
    });

    expect(result.phase).toBe("Succeeded");
    expect(JSON.parse(result.globalOutputs.result)).toEqual(JSON.parse(input));
  });

  test("nested fromJSON extracts then re-serializes", async () => {
    const result = await submitProbe({
      inputs: { data: '{"nested":{"value":42}}' },
      expression: "toJson(fromJSON(inputs.parameters.data).nested)",
    });

    expect(result.phase).toBe("Succeeded");
    expect(JSON.parse(result.globalOutputs.result)).toEqual({ value: 42 });
  });

  test("fromJSON on number field returns number type", async () => {
    const result = await submitProbe({
      inputs: { data: '{"num":42}' },
      expression: "string(fromJSON(inputs.parameters.data).num + 10)",
    });

    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("52");
  });

  test("fromJSON boolean can be used in conditionals", async () => {
    const result = await submitProbe({
      inputs: { data: '{"flag":true}' },
      expression: "fromJSON(inputs.parameters.data).flag ? 'yes' : 'no'",
    });

    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("yes");
  });
});

describe("string() coercion", () => {
  test("string() on number", async () => {
    const result = await submitProbe({
      expression: "string(42)",
    });

    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("42");
  });

  test("string() on boolean true", async () => {
    const result = await submitProbe({
      expression: "string(true)",
    });

    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("true");
  });

  test("string() on boolean false", async () => {
    const result = await submitProbe({
      expression: "string(false)",
    });

    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("false");
  });

  test("string() on arithmetic result", async () => {
    const result = await submitProbe({
      expression: "string(10 * 5)",
    });

    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("50");
  });
});

describe("asInt/asFloat coercion", () => {
  test("asInt on string number", async () => {
    const result = await submitProbe({
      inputs: { num: "42" },
      expression: "string(asInt(inputs.parameters.num))",
    });

    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("42");
  });

  test("asInt enables arithmetic", async () => {
    const result = await submitProbe({
      inputs: { a: "10", b: "5" },
      expression: "string(asInt(inputs.parameters.a) + asInt(inputs.parameters.b))",
    });

    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("15");
  });

  test("asFloat on string with decimal", async () => {
    const result = await submitProbe({
      inputs: { num: "3.14" },
      expression: "string(asFloat(inputs.parameters.num))",
    });

    expect(result.phase).toBe("Succeeded");
    // Document actual float representation
    expect(result.globalOutputs.result).toMatch(/3\.14/);
  });

  test.skip("asInt truncates float string - NOT SUPPORTED", async () => {
    // asInt doesn't handle decimal strings - use asFloat first
    const result = await submitProbe({
      inputs: { num: "3.9" },
      expression: "string(asInt(inputs.parameters.num))",
    });

    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("3");
  });
});

describe("Implicit coercion in operations", () => {
  test("number + string concatenates", async () => {
    const result = await submitProbe({
      inputs: { num: "42", text: "items" },
      expression: "inputs.parameters.num + ' ' + inputs.parameters.text",
    });

    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("42 items");
  });

  test("comparison coerces types", async () => {
    const result = await submitProbe({
      inputs: { num: "42" },
      expression: "inputs.parameters.num == '42' ? 'match' : 'no match'",
    });

    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("match");
  });

  test("boolean converted to string for concatenation", async () => {
    const result = await submitProbe({
      expression: "string(true) + ' value'",
    });

    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("true value");
  });
});

describe("Base64 encoding/decoding", () => {
  test("toBase64 encodes string", async () => {
    const result = await submitProbe({
      inputs: { text: "hello" },
      expression: "toBase64(inputs.parameters.text)",
    });

    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("aGVsbG8=");
  });

  test("fromBase64 decodes string", async () => {
    const result = await submitProbe({
      inputs: { encoded: "aGVsbG8=" },
      expression: "fromBase64(inputs.parameters.encoded)",
    });

    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("hello");
  });

  test("base64 round-trip preserves data", async () => {
    const result = await submitProbe({
      inputs: { text: "test data 123" },
      expression: "fromBase64(toBase64(inputs.parameters.text))",
    });

    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("test data 123");
  });
});
