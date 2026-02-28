import { submitProbe, submitChainProbe } from "../infra/probeHelper";

describe("Negative Contract Tests", () => {
  describe("Invalid expressions should fail", () => {
    test("invalid JSONPath syntax fails", async () => {
      try {
        const result = await submitProbe({
          inputs: { data: '{"key":"value"}' },
          expression: "jsonpath(inputs.parameters.data, 'invalid[[[syntax')",
        });
        
        // If it somehow succeeds, that's wrong
        expect(result.phase).not.toBe("Succeeded");
      } catch (err: any) {
        // Expected - workflow should timeout or fail
        expect(err.message).toMatch(/timed out|Failed|Error/);
      }
    });

    test("invalid parameter reference returns <nil>", async () => {
      const result = await submitProbe({
        inputs: { data: "test" },
        expression: "inputs.parameters.nonexistent",
      });
      
      // Argo returns "<nil>" for nonexistent parameters, workflow succeeds
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("<nil>");
    });

    test("invalid function name fails", async () => {
      try {
        const result = await submitProbe({
          inputs: { data: '{"key":"value"}' },
          expression: "invalidFunction(inputs.parameters.data)",
        });
        
        expect(result.phase).not.toBe("Succeeded");
      } catch (err: any) {
        // Expected - workflow should fail or timeout
        expect(err.message).toMatch(/timed out|Failed|Error/);
      }
    });

    test("type mismatch in arithmetic fails", async () => {
      try {
        const result = await submitProbe({
          inputs: { text: "not-a-number" },
          expression: "asInt(inputs.parameters.text) * 2",
        });
        
        expect(result.phase).not.toBe("Succeeded");
      } catch (err: any) {
        expect(err.message).toMatch(/timed out|Failed|Error/);
      }
    });

    test("malformed JSON in fromJson fails", async () => {
      try {
        const result = await submitProbe({
          inputs: { data: "{invalid json}" },
          expression: "fromJSON(inputs.parameters.data)",
        });
        
        expect(result.phase).not.toBe("Succeeded");
      } catch (err: any) {
        expect(err.message).toMatch(/timed out|Failed|Error/);
      }
    });
  });

  describe("Wrong values should not match", () => {
    test("wrong JSONPath result", async () => {
      const result = await submitProbe({
        inputs: { data: '{"key":"value"}' },
        expression: "jsonpath(inputs.parameters.data, '$.key')",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).not.toBe("wrong-value");
      expect(result.globalOutputs.result).not.toBe("key");
      expect(result.globalOutputs.result).toBe("value");
    });

    test("ternary evaluates correct branch", async () => {
      const result = await submitProbe({
        inputs: { count: "5" },
        expression: "asInt(inputs.parameters.count) > 3 ? 'high' : 'low'",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).not.toBe("low");
      expect(result.globalOutputs.result).toBe("high");
    });

    test("string concatenation order matters", async () => {
      const result = await submitProbe({
        inputs: { a: "hello", b: "world" },
        expression: "inputs.parameters.a + ' ' + inputs.parameters.b",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).not.toBe("world hello");
      expect(result.globalOutputs.result).toBe("hello world");
    });
  });

  describe("Parameter corruption detection", () => {
    test("modified JSON is detected", async () => {
      const original = '{"a":1,"b":"two"}';
      const result = await submitChainProbe({
        input: original,
        steps: [{ expression: "inputs.parameters.input" }],
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).not.toBe('{"a":2,"b":"two"}');
      expect(result.globalOutputs.result).not.toBe('{"b":"two","a":1}'); // Key order might differ
      expect(JSON.parse(result.globalOutputs.result)).toEqual(JSON.parse(original));
    });

    test("empty string is not null or undefined", async () => {
      const result = await submitChainProbe({
        input: "",
        steps: [{ expression: "inputs.parameters.input" }],
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).not.toBe("null");
      expect(result.globalOutputs.result).not.toBe("undefined");
      expect(result.globalOutputs.result).toBe("");
    });

    test("whitespace is preserved exactly", async () => {
      const result = await submitChainProbe({
        input: "  ",
        steps: [{ expression: "inputs.parameters.input" }],
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).not.toBe(" ");
      expect(result.globalOutputs.result).not.toBe("   ");
      expect(result.globalOutputs.result).toBe("  ");
    });
  });

  describe("Type coercion boundaries", () => {
    test("number is not stringified with quotes", async () => {
      const result = await submitProbe({
        inputs: { data: '{"num":42}' },
        expression: "jsonpath(inputs.parameters.data, '$.num')",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).not.toBe('"42"');
      expect(result.globalOutputs.result).not.toBe("42.0");
      expect(result.globalOutputs.result).toBe("42");
    });

    test("boolean is lowercase not capitalized", async () => {
      const result = await submitProbe({
        inputs: { data: '{"flag":true}' },
        expression: "jsonpath(inputs.parameters.data, '$.flag')",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).not.toBe("True");
      expect(result.globalOutputs.result).not.toBe("TRUE");
      expect(result.globalOutputs.result).toBe("true");
    });

    test("arithmetic result is exact", async () => {
      const result = await submitProbe({
        inputs: { x: "10" },
        expression: "string(asInt(inputs.parameters.x) * 2)",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).not.toBe("19");
      expect(result.globalOutputs.result).not.toBe("21");
      expect(result.globalOutputs.result).not.toBe("20.0");
      expect(result.globalOutputs.result).toBe("20");
    });
  });
});
