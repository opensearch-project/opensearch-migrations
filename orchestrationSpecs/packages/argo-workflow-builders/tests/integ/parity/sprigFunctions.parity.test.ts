import { submitProbe } from "../infra/probeHelper.js";
import { ParitySpec, reportContractResult } from "../infra/parityHelper.js";

function makeSpec(
  name: string,
  argoExpression: string,
  expectedResult?: string,
  inputs?: Record<string, string>
): ParitySpec {
  return { category: "Sprig Functions", name, inputs, argoExpression, expectedResult };
}

describe("Sprig Functions - dict + toJson", () => {
  const spec = makeSpec("dict + toJson", "toJson(sprig.dict('name', 'test', 'count', '42'))");
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(JSON.parse(result.globalOutputs.result)).toEqual({ name: "test", count: "42" });
      reportContractResult(spec, result);
    });
  });
});

describe("Sprig Functions - fromJSON field access", () => {
  const spec = makeSpec(
    "fromJSON field access",
    "string(fromJSON(inputs.parameters.data).port)",
    "9200",
    { data: '{"port":9200}' }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Sprig Functions - fromJSON then toJson round-trip", () => {
  const spec = makeSpec(
    "fromJSON then toJson round-trip",
    "toJson(fromJSON(inputs.parameters.data))",
    undefined,
    { data: '{"a":1}' }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(JSON.parse(result.globalOutputs.result)).toEqual({ a: 1 });
      reportContractResult(spec, result);
    });
  });
});

describe("Sprig Functions - fromJSON with nested object", () => {
  const spec = makeSpec(
    "fromJSON with nested object",
    "string(fromJSON(inputs.parameters.data).outer.inner)",
    "value",
    { data: '{"outer":{"inner":"value"}}' }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Sprig Functions - fromJSON with array access", () => {
  const spec = makeSpec(
    "fromJSON with array access",
    "string(fromJSON(inputs.parameters.data).items[1])",
    "b",
    { data: '{"items":["a","b","c"]}' }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Sprig Functions - fromJSON number coercion", () => {
  const spec = makeSpec(
    "fromJSON number coercion",
    "string(fromJSON(inputs.parameters.data).n)",
    undefined,
    { data: '{"n":1.5}' }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBeTruthy();
      reportContractResult(spec, result);
    });
  });
});

describe("Sprig Functions - fromJSON boolean value", () => {
  const spec = makeSpec(
    "fromJSON boolean value",
    "string(fromJSON(inputs.parameters.data).flag)",
    "true",
    { data: '{"flag":true}' }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Sprig Functions - fromJSON null value", () => {
  const spec = makeSpec(
    "fromJSON null value",
    "string(fromJSON(inputs.parameters.data).value)",
    undefined,
    { data: '{"value":null}' }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBeTruthy();
      reportContractResult(spec, result);
    });
  });
});

describe("Sprig Functions - merge dictionaries", () => {
  const spec = makeSpec(
    "merge dictionaries",
    "toJson(sprig.merge(sprig.dict('a', '1', 'b', '2'), sprig.dict('b', '3', 'c', '4')))"
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      const parsed = JSON.parse(result.globalOutputs.result);
      expect(parsed.a).toBe("1");
      expect(parsed.c).toBe("4");
      reportContractResult(spec, result);
    });
  });
});

describe("Sprig Functions - omit keys from dict", () => {
  const spec = makeSpec(
    "omit keys from dict",
    "toJson(sprig.omit(sprig.dict('a', '1', 'b', '2', 'c', '3'), 'b'))"
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      const parsed = JSON.parse(result.globalOutputs.result);
      expect(parsed.a).toBe("1");
      expect(parsed.c).toBe("3");
      expect(parsed.b).toBeUndefined();
      reportContractResult(spec, result);
    });
  });
});

describe("Sprig Functions - dig nested values", () => {
  const spec = makeSpec(
    "dig nested values",
    "sprig.dig('user', 'role', 'name', 'guest', fromJSON(inputs.parameters.data))",
    "admin",
    { data: '{"user":{"role":{"name":"admin"}}}' }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Sprig Functions - dig with default value", () => {
  const spec = makeSpec(
    "dig with default value",
    "sprig.dig('user', 'role', 'name', 'guest', fromJSON(inputs.parameters.data))",
    "guest",
    { data: '{"user":{}}' }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });
});

describe("Sprig Functions - keys function", () => {
  const spec = makeSpec(
    "keys function",
    "toJson(keys(fromJSON(inputs.parameters.data)))",
    undefined,
    { data: '{"a":1,"b":2,"c":3}' }
  );
  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ inputs: spec.inputs, expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      const parsed = JSON.parse(result.globalOutputs.result);
      expect(parsed.sort()).toEqual(["a", "b", "c"]);
      reportContractResult(spec, result);
    });
  });
});
