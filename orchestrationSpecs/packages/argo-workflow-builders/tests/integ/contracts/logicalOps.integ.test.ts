import { submitProbe } from "../infra/probeHelper";

describe("Logical and Comparison Operations Contract Tests", () => {
  describe("Comparison operators", () => {
    test("== with numbers", async () => {
      const result = await submitProbe({
        inputs: { a: "5", b: "5" },
        expression: "string(asInt(inputs.parameters.a) == asInt(inputs.parameters.b))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("true");
    });

    test("!= with numbers", async () => {
      const result = await submitProbe({
        inputs: { a: "5", b: "3" },
        expression: "string(asInt(inputs.parameters.a) != asInt(inputs.parameters.b))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("true");
    });

    test("< less than", async () => {
      const result = await submitProbe({
        inputs: { a: "3", b: "5" },
        expression: "string(asInt(inputs.parameters.a) < asInt(inputs.parameters.b))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("true");
    });

    test("> greater than", async () => {
      const result = await submitProbe({
        inputs: { a: "10", b: "5" },
        expression: "string(asInt(inputs.parameters.a) > asInt(inputs.parameters.b))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("true");
    });

    test("<= less than or equal", async () => {
      const result = await submitProbe({
        inputs: { a: "5", b: "5" },
        expression: "string(asInt(inputs.parameters.a) <= asInt(inputs.parameters.b))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("true");
    });

    test(">= greater than or equal", async () => {
      const result = await submitProbe({
        inputs: { a: "5", b: "5" },
        expression: "string(asInt(inputs.parameters.a) >= asInt(inputs.parameters.b))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("true");
    });

    test("string equality", async () => {
      const result = await submitProbe({
        inputs: { a: "hello", b: "hello" },
        expression: "string(inputs.parameters.a == inputs.parameters.b)",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("true");
    });

    test("string inequality", async () => {
      const result = await submitProbe({
        inputs: { a: "hello", b: "world" },
        expression: "string(inputs.parameters.a != inputs.parameters.b)",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("true");
    });
  });

  describe("Logical operators", () => {
    test("&& both true", async () => {
      const result = await submitProbe({
        expression: "string(true && true)",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("true");
    });

    test("&& one false", async () => {
      const result = await submitProbe({
        expression: "string(true && false)",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("false");
    });

    test("|| both false", async () => {
      const result = await submitProbe({
        expression: "string(false || false)",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("false");
    });

    test("|| one true", async () => {
      const result = await submitProbe({
        expression: "string(false || true)",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("true");
    });

    test("! negation of true", async () => {
      const result = await submitProbe({
        expression: "string(!true)",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("false");
    });

    test("! negation of false", async () => {
      const result = await submitProbe({
        expression: "string(!false)",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("true");
    });

    test("complex logical expression", async () => {
      const result = await submitProbe({
        inputs: { a: "5", b: "10", c: "3" },
        expression: "string((asInt(inputs.parameters.a) > asInt(inputs.parameters.c)) && (asInt(inputs.parameters.b) > asInt(inputs.parameters.a)))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("true");
    });
  });

  describe("Arithmetic operators", () => {
    test("addition", async () => {
      const result = await submitProbe({
        inputs: { a: "5", b: "3" },
        expression: "string(asInt(inputs.parameters.a) + asInt(inputs.parameters.b))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("8");
    });

    test("subtraction", async () => {
      const result = await submitProbe({
        inputs: { a: "10", b: "3" },
        expression: "string(asInt(inputs.parameters.a) - asInt(inputs.parameters.b))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("7");
    });

    test("multiplication", async () => {
      const result = await submitProbe({
        inputs: { a: "5", b: "3" },
        expression: "string(asInt(inputs.parameters.a) * asInt(inputs.parameters.b))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("15");
    });

    test("division", async () => {
      const result = await submitProbe({
        inputs: { a: "10", b: "2" },
        expression: "string(asInt(inputs.parameters.a) / asInt(inputs.parameters.b))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("5");
    });

    test("modulo", async () => {
      const result = await submitProbe({
        inputs: { a: "10", b: "3" },
        expression: "string(asInt(inputs.parameters.a) % asInt(inputs.parameters.b))",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("1");
    });

    test("operator precedence - multiplication before addition", async () => {
      const result = await submitProbe({
        expression: "string(2 + 3 * 4)",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("14");
    });

    test("parentheses override precedence", async () => {
      const result = await submitProbe({
        expression: "string((2 + 3) * 4)",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("20");
    });
  });

  describe("Nested ternary and complex conditions", () => {
    test("nested ternary with multiple conditions", async () => {
      const result = await submitProbe({
        inputs: { score: "75" },
        expression: "asInt(inputs.parameters.score) >= 90 ? 'A' : asInt(inputs.parameters.score) >= 80 ? 'B' : asInt(inputs.parameters.score) >= 70 ? 'C' : 'F'",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("C");
    });

    test("ternary with logical AND in condition", async () => {
      const result = await submitProbe({
        inputs: { a: "5", b: "10" },
        expression: "(asInt(inputs.parameters.a) > 3 && asInt(inputs.parameters.b) > 8) ? 'both' : 'not both'",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("both");
    });

    test("ternary with logical OR in condition", async () => {
      const result = await submitProbe({
        inputs: { a: "5", b: "3" },
        expression: "(asInt(inputs.parameters.a) > 10 || asInt(inputs.parameters.b) > 2) ? 'at least one' : 'neither'",
      });
      
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe("at least one");
    });
  });
});
