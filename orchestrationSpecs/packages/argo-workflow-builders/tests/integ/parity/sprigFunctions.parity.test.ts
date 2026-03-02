import { expr, renderWorkflowTemplate, typeToken } from "../../../src/index.js";
import { submitProbe } from "../infra/probeHelper.js";
import { submitRenderedWorkflow } from "../infra/probeHelper.js";
import { BuilderVariant, ParitySpec, reportContractResult, reportParityResult } from "../infra/parityHelper.js";
import { makeTestWorkflow } from "../infra/testWorkflowHelper.js";

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

  describe("Builder - makeDict/recordToString", () => {
    const builderVariant: BuilderVariant = {
      name: "makeDict/recordToString",
      code: 'expr.recordToString(expr.makeDict({ name: expr.literal("test"), count: expr.literal("42") }))',
    };

    test("builder API produces equivalent dictionary json", async () => {
      const wf = makeTestWorkflow(t => t
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", () =>
          expr.recordToString(
            expr.makeDict({
              name: expr.literal("test"),
              count: expr.literal("42"),
            })
          )
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      expect(JSON.parse(result.globalOutputs.result)).toEqual({ name: "test", count: "42" });
      reportParityResult(spec, builderVariant, result);
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

  describe("Builder - deserialize/get", () => {
    const builderVariant: BuilderVariant = {
      name: "deserialize/get",
      code: 'expr.toString(expr.get(expr.deserializeRecord(ctx.inputs.data), "port"))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("data", typeToken<{ port: number }>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.toString(expr.get(expr.deserializeRecord(ctx.inputs.data), "port"))
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });

  describe("Builder - stringToRecord/getLoose", () => {
    const builderVariant: BuilderVariant = {
      name: "stringToRecord/getLoose",
      code: 'expr.toString(expr.getLoose(expr.stringToRecord(typeToken<{ port: number }>(), ctx.inputs.data), "port"))',
    };

    test("builder API produces same result with loose access", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("data", typeToken<string>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.toString(
            expr.getLoose(
              expr.stringToRecord(typeToken<{ port: number }>(), ctx.inputs.data),
              "port"
            )
          )
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
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

  describe("Builder - deserialize/recordToString", () => {
    const builderVariant: BuilderVariant = {
      name: "deserialize/recordToString",
      code: "expr.recordToString(expr.deserializeRecord(ctx.inputs.data))",
    };

    test("builder API produces same structured json result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("data", typeToken<{ a: number }>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.recordToString(expr.deserializeRecord(ctx.inputs.data))
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(result.phase).toBe("Succeeded");
      expect(JSON.parse(result.globalOutputs.result)).toEqual({ a: 1 });
      reportParityResult(spec, builderVariant, result);
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

  describe("Builder - deserialize nested get", () => {
    const builderVariant: BuilderVariant = {
      name: "deserialize nested get",
      code: 'expr.toString(expr.get(expr.get(expr.deserializeRecord(ctx.inputs.data), "outer"), "inner"))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("data", typeToken<{ outer: { inner: string } }>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.toString(
            expr.get(
              expr.get(expr.deserializeRecord(ctx.inputs.data), "outer"),
              "inner"
            )
          )
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
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

  describe("Builder - deserialize/index", () => {
    const builderVariant: BuilderVariant = {
      name: "deserialize/index",
      code: 'expr.toString(expr.index(expr.get(expr.deserializeRecord(ctx.inputs.data), "items"), expr.literal(1)))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("data", typeToken<{ items: string[] }>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.toString(
            expr.index(
              expr.get(expr.deserializeRecord(ctx.inputs.data), "items"),
              expr.literal(1)
            )
          )
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
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

  describe("Builder - deserialize number", () => {
    const builderVariant: BuilderVariant = {
      name: "deserialize number",
      code: 'expr.toString(expr.get(expr.deserializeRecord(ctx.inputs.data), "n"))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("data", typeToken<{ n: number }>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.toString(expr.get(expr.deserializeRecord(ctx.inputs.data), "n"))
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBeTruthy();
      reportParityResult(spec, builderVariant, result);
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

  describe("Builder - deserialize boolean", () => {
    const builderVariant: BuilderVariant = {
      name: "deserialize boolean",
      code: 'expr.toString(expr.get(expr.deserializeRecord(ctx.inputs.data), "flag"))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("data", typeToken<{ flag: boolean }>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.toString(expr.get(expr.deserializeRecord(ctx.inputs.data), "flag"))
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
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

  describe("Builder - mergeDicts/makeDict", () => {
    const builderVariant: BuilderVariant = {
      name: "mergeDicts/makeDict",
      code: 'expr.recordToString(expr.mergeDicts(expr.makeDict({ a: expr.literal("1"), b: expr.literal("2") }), expr.makeDict({ b: expr.literal("3"), c: expr.literal("4") })))',
    };

    test("builder API produces equivalent merged dictionary json", async () => {
      const wf = makeTestWorkflow(t => t
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", () =>
          expr.recordToString(
            expr.mergeDicts(
              expr.makeDict({ a: expr.literal("1"), b: expr.literal("2") }),
              expr.makeDict({ b: expr.literal("3"), c: expr.literal("4") })
            )
          )
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      const parsed = JSON.parse(result.globalOutputs.result);
      expect(parsed.a).toBe("1");
      expect(parsed.c).toBe("4");
      reportParityResult(spec, builderVariant, result);
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

  describe("Builder - omit/makeDict", () => {
    const builderVariant: BuilderVariant = {
      name: "omit/makeDict",
      code: 'expr.recordToString(expr.omit(expr.makeDict({ a: expr.literal("1"), b: expr.literal("2"), c: expr.literal("3") }), "b"))',
    };

    test("builder API produces equivalent omitted dictionary json", async () => {
      const wf = makeTestWorkflow(t => t
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", () =>
          expr.recordToString(
            expr.omit(
              expr.makeDict({
                a: expr.literal("1"),
                b: expr.literal("2"),
                c: expr.literal("3"),
              }),
              "b"
            )
          )
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      const parsed = JSON.parse(result.globalOutputs.result);
      expect(parsed.a).toBe("1");
      expect(parsed.c).toBe("3");
      expect(parsed.b).toBeUndefined();
      reportParityResult(spec, builderVariant, result);
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

  describe("Builder - dig", () => {
    const builderVariant: BuilderVariant = {
      name: "dig",
      code: 'expr.dig(expr.deserializeRecord(ctx.inputs.data), ["user","role","name"], expr.literal("guest"))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("data", typeToken<{ user: { role: { name: string } } }>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.dig(
            expr.deserializeRecord(ctx.inputs.data),
            ["user", "role", "name"] as const,
            "guest"
          )
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
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

  describe("Builder - dig", () => {
    const builderVariant: BuilderVariant = {
      name: "dig",
      code: 'expr.dig(expr.deserializeRecord(ctx.inputs.data), ["user","role","name"], expr.literal("guest"))',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("data", typeToken<{ user: { role?: { name?: string } } }>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.dig(
            expr.deserializeRecord(ctx.inputs.data),
            ["user", "role", "name"] as const,
            "guest"
          )
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
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

  describe("Builder - keys", () => {
    const builderVariant: BuilderVariant = {
      name: "keys",
      code: "expr.recordToString(expr.keys(expr.deserializeRecord(ctx.inputs.data)))",
    };

    test("builder API produces same key set", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("data", typeToken<{ a: number; b: number; c: number }>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.recordToString(expr.keys(expr.deserializeRecord(ctx.inputs.data)))
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(result.phase).toBe("Succeeded");
      const parsed = JSON.parse(result.globalOutputs.result);
      expect(parsed.sort()).toEqual(["a", "b", "c"]);
      reportParityResult(spec, builderVariant, result);
    });
  });
});
