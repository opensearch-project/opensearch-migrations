import { submitProbe } from "../infra/probeHelper.js";
import { ParitySpec, reportContractResult } from "../infra/parityHelper.js";

function spec(
  name: string,
  argoExpression: string,
  expectedResult?: string,
  inputs?: Record<string, string>,
  expectedPhase?: string
): ParitySpec {
  return { category: "Regex And Advanced", name, inputs, argoExpression, expectedResult, expectedPhase };
}

describe("Regex And Advanced - regexMatch valid email", () => {
  const s = spec(
    "regexMatch valid email",
    "string(sprig.regexMatch('^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\\\.[A-Za-z]{2,}$', inputs.parameters.email))",
    "true",
    { email: "test@example.com" }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(s.expectedResult);
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - regexMatch invalid email", () => {
  const s = spec(
    "regexMatch invalid email",
    "string(sprig.regexMatch('^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\\\.[A-Za-z]{2,}$', inputs.parameters.email))",
    "false",
    { email: "not-an-email" }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(s.expectedResult);
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - regexFind extract pattern", () => {
  const s = spec("regexFind extract pattern", "sprig.regexFind('[a-zA-Z][1-9]', inputs.parameters.text)", "d1", { text: "abcd1234" });
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(s.expectedResult);
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - regexFindAll find all matches", () => {
  const s = spec(
    "regexFindAll find all matches",
    "toJson(sprig.regexFindAll('[2,4,6,8]', inputs.parameters.text, -1))",
    undefined,
    { text: "123456789" }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(JSON.parse(r.globalOutputs.result)).toEqual(["2", "4", "6", "8"]);
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - regexReplaceAll capture groups", () => {
  const s = spec(
    "regexReplaceAll capture groups",
    "sprig.regexReplaceAll('a(x*)b', inputs.parameters.text, '${1}W')",
    "-W-xxW-",
    { text: "-ab-axxb-" }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(s.expectedResult);
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - regexSplit by pattern", () => {
  const s = spec(
    "regexSplit by pattern",
    "toJson(sprig.regexSplit('z+', inputs.parameters.text, -1))",
    undefined,
    { text: "pizza" }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(JSON.parse(r.globalOutputs.result)).toEqual(["pi", "a"]);
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - bracket notation map access", () => {
  const s = spec("bracket notation map access", "fromJSON(inputs.parameters.data)['my-key']", "value", {
    data: '{"my-key":"value"}',
  });
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(s.expectedResult);
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - bracket notation array access", () => {
  const s = spec("bracket notation array access", "fromJSON(inputs.parameters.data)[1]", "b", {
    data: '["a","b","c"]',
  });
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(s.expectedResult);
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - bracket notation negative index", () => {
  const s = spec("bracket notation negative index", "fromJSON(inputs.parameters.data)[-1]", "c", {
    data: '["a","b","c"]',
  });
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(s.expectedResult);
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - in operator array membership true", () => {
  const s = spec(
    "in operator array membership true",
    "string(inputs.parameters.value in ['John', 'Jane', 'Bob'])",
    "true",
    { value: "John" }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(s.expectedResult);
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - in operator array membership false", () => {
  const s = spec(
    "in operator array membership false",
    "string(inputs.parameters.value in ['John', 'Jane', 'Bob'])",
    "false",
    { value: "Alice" }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(s.expectedResult);
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - in operator map key true", () => {
  const s = spec(
    "in operator map key true",
    "string('name' in fromJSON(inputs.parameters.data))",
    "true",
    { data: '{"name":"John","age":30}' }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(s.expectedResult);
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - in operator map key false", () => {
  const s = spec(
    "in operator map key false",
    "string('email' in fromJSON(inputs.parameters.data))",
    "false",
    { data: '{"name":"John","age":30}' }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(s.expectedResult);
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - ternary with in operator", () => {
  const s = spec(
    "ternary with in operator",
    "inputs.parameters.role in ['admin', 'superuser'] ? 'allowed' : 'denied'",
    "allowed",
    { role: "admin" }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toBe(s.expectedResult);
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - filter with bracket notation", () => {
  const s = spec(
    "filter with bracket notation",
    "toJson(filter(fromJSON(inputs.parameters.data), {#['age'] > 26}))",
    undefined,
    { data: '[{"name":"John","age":30},{"name":"Jane","age":25}]' }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      const parsed = JSON.parse(r.globalOutputs.result);
      expect(parsed).toHaveLength(1);
      expect(parsed[0].name).toBe("John");
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - map with bracket notation", () => {
  const s = spec(
    "map with bracket notation",
    "toJson(map(fromJSON(inputs.parameters.data), {#['value'] * 2}))",
    undefined,
    { data: '[{"value":1},{"value":2},{"value":3}]' }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(JSON.parse(r.globalOutputs.result)).toEqual([2, 4, 6]);
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - asInt on decimal string errors", () => {
  const s = spec(
    "asInt on decimal string errors",
    "string(asInt(inputs.parameters.value))",
    undefined,
    { value: "42.7" },
    "Error"
  );
  describe("ArgoYaml", () => {
    test("raw expression fails as expected", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Error");
      reportContractResult(s, r);
    });
  });
});

describe("Regex And Advanced - asFloat on integer string", () => {
  const s = spec(
    "asFloat on integer string",
    "string(asFloat(inputs.parameters.value))",
    undefined,
    { value: "42" }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const r = await submitProbe({ inputs: s.inputs, expression: s.argoExpression });
      expect(r.phase).toBe("Succeeded");
      expect(r.globalOutputs.result).toMatch(/^42(\.0)?$/);
      reportContractResult(s, r);
    });
  });
});
