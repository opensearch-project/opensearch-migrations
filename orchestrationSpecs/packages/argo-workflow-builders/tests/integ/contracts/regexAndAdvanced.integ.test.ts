import { submitProbe } from "../infra/probeHelper";

describe("Regex and Advanced Expression Tests", () => {
  describe("Sprig Regex Functions", () => {
    test("regexMatch - valid email", async () => {
      const result = await submitProbe({
        inputs: { email: "test@example.com" },
        expression: "string(sprig.regexMatch('^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\\\.[A-Za-z]{2,}$', inputs.parameters.email))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("true");
    });

    test("regexMatch - invalid email", async () => {
      const result = await submitProbe({
        inputs: { email: "not-an-email" },
        expression: "string(sprig.regexMatch('^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\\\.[A-Za-z]{2,}$', inputs.parameters.email))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("false");
    });

    test("regexFind - extract pattern", async () => {
      const result = await submitProbe({
        inputs: { text: "abcd1234" },
        expression: "sprig.regexFind('[a-zA-Z][1-9]', inputs.parameters.text)",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("d1");
    });

    test("regexFindAll - find all matches", async () => {
      const result = await submitProbe({
        inputs: { text: "123456789" },
        expression: "toJson(sprig.regexFindAll('[2,4,6,8]', inputs.parameters.text, -1))",
      });
      
      expect(result.phase).toBe("Succeeded");
      const parsed = JSON.parse(result.globalOutputs.result);
      expect(parsed).toEqual(["2", "4", "6", "8"]);
    });

    test("regexReplaceAll - replace with capture groups", async () => {
      const result = await submitProbe({
        inputs: { text: "-ab-axxb-" },
        expression: "sprig.regexReplaceAll('a(x*)b', inputs.parameters.text, '${1}W')",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("-W-xxW-");
    });

    test("regexSplit - split by pattern", async () => {
      const result = await submitProbe({
        inputs: { text: "pizza" },
        expression: "toJson(sprig.regexSplit('z+', inputs.parameters.text, -1))",
      });
      
      expect(result.phase).toBe("Succeeded");
      const parsed = JSON.parse(result.globalOutputs.result);
      expect(parsed).toEqual(["pi", "a"]);
    });
  });

  describe("Bracket Notation and 'in' Operator", () => {
    test("bracket notation for map access", async () => {
      const result = await submitProbe({
        inputs: { data: '{"my-key":"value"}' },
        expression: "fromJSON(inputs.parameters.data)['my-key']",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("value");
    });

    test("bracket notation for array access", async () => {
      const result = await submitProbe({
        inputs: { data: '["a","b","c"]' },
        expression: "fromJSON(inputs.parameters.data)[1]",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("b");
    });

    test("bracket notation with negative index", async () => {
      const result = await submitProbe({
        inputs: { data: '["a","b","c"]' },
        expression: "fromJSON(inputs.parameters.data)[-1]",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("c");
    });

    test("'in' operator for array membership", async () => {
      const result = await submitProbe({
        inputs: { value: "John" },
        expression: "string(inputs.parameters.value in ['John', 'Jane', 'Bob'])",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("true");
    });

    test("'in' operator for array non-membership", async () => {
      const result = await submitProbe({
        inputs: { value: "Alice" },
        expression: "string(inputs.parameters.value in ['John', 'Jane', 'Bob'])",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("false");
    });

    test("'in' operator for map key check", async () => {
      const result = await submitProbe({
        inputs: { data: '{"name":"John","age":30}' },
        expression: "string('name' in fromJSON(inputs.parameters.data))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("true");
    });

    test("'in' operator for missing map key", async () => {
      const result = await submitProbe({
        inputs: { data: '{"name":"John","age":30}' },
        expression: "string('email' in fromJSON(inputs.parameters.data))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("false");
    });
  });

  describe("Advanced Expression Features", () => {
    test("ternary with 'in' operator", async () => {
      const result = await submitProbe({
        inputs: { role: "admin" },
        expression: "inputs.parameters.role in ['admin', 'superuser'] ? 'allowed' : 'denied'",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("allowed");
    });

    test("filter with bracket notation", async () => {
      const result = await submitProbe({
        inputs: { data: '[{"name":"John","age":30},{"name":"Jane","age":25}]' },
        expression: "toJson(filter(fromJSON(inputs.parameters.data), {#['age'] > 26}))",
      });
      
      expect(result.phase).toBe("Succeeded");
      const parsed = JSON.parse(result.globalOutputs.result);
      expect(parsed).toHaveLength(1);
      expect(parsed[0].name).toBe("John");
    });

    test("map with bracket notation", async () => {
      const result = await submitProbe({
        inputs: { data: '[{"value":1},{"value":2},{"value":3}]' },
        expression: "toJson(map(fromJSON(inputs.parameters.data), {#['value'] * 2}))",
      });
      
      expect(result.phase).toBe("Succeeded");
      const parsed = JSON.parse(result.globalOutputs.result);
      expect(parsed).toEqual([2, 4, 6]);
    });

    test("asInt on decimal string", async () => {
      const result = await submitProbe({
        inputs: { value: "42.7" },
        expression: "string(asInt(inputs.parameters.value))",
      });
      
      if (result.phase !== "Succeeded") {
        console.log("Workflow failed:", result.message);
        console.log("Node outputs:", result.nodeOutputs);
      }
      
      // asInt doesn't work on decimal strings - it expects integers
      // This test documents that behavior
      expect(result.phase).toBe("Error");
    });

    test("asFloat on integer string", async () => {
      const result = await submitProbe({
        inputs: { value: "42" },
        expression: "string(asFloat(inputs.parameters.value))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toMatch(/^42(\.0)?$/);
    });
  });
});
