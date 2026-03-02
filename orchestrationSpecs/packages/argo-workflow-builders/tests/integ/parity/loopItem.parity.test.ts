import {
  INTERNAL,
  WorkflowBuilder,
  expr,
  makeItemsLoop,
  makeParameterLoop,
  renderWorkflowTemplate,
  Serialized,
  typeToken,
} from "../../../src/index.js";
import { getTestNamespace, getServiceAccountName } from "../infra/argoCluster.js";
import { submitRenderedWorkflow } from "../infra/probeHelper.js";
import { submitAndWait } from "../infra/workflowRunner.js";
import { BuilderVariant, ParitySpec, reportContractResult, reportParityResult } from "../infra/parityHelper.js";
import { LowercaseOnly } from "../../../src/models/workflowTypes.js";

function countLoopNodes(result: any, prefix = "loop-step(") {
  return Object.values(result.raw.status.nodes).filter(
    (n: any) => n.displayName && n.displayName.startsWith(prefix)
  );
}

function uniqueWorkflowName<const P extends string>(
  prefix: LowercaseOnly<P>
): LowercaseOnly<`${P}-${number}-${string}`> {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}` as LowercaseOnly<`${P}-${number}-${string}`>;
}

describe("Loop Item - withItems iterates over array strings", () => {
  const spec: ParitySpec = {
    category: "Loop Item",
    name: "withItems iterates over array strings",
    argoExpression: "withItems: ['a','b','c']",
  };
  describe("ArgoYaml", () => {
    test("raw workflow loops 3 times and passes item", async () => {
      const namespace = getTestNamespace();
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "pli-items-strings-direct-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: "test-runner",
          templates: [
            { name: "process-item", inputs: { parameters: [{ name: "value" }] }, suspend: { duration: "0" } },
            {
              name: "main",
              steps: [[{
                name: "loop-step",
                template: "process-item",
                arguments: { parameters: [{ name: "value", value: "{{item}}" }] },
                withItems: ["a", "b", "c"],
              }]],
            },
          ],
        },
      };
      const result = await submitAndWait(workflow);
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      expect(loopNodes.length).toBe(3);
      const items = loopNodes.map((n: any) => n.inputs?.parameters?.find((p: any) => p.name === "value")?.value).sort();
      expect(items).toEqual(["a", "b", "c"]);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - loopWith items", () => {
    const builderVariant: BuilderVariant = {
      name: "loopWith items",
      code: "addStep(..., c => c.register({ value: c.item }), { loopWith: makeItemsLoop(['a','b','c']) })",
    };

    test("builder workflow loops 3 times and passes item", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: uniqueWorkflowName("pli-items-strings-builder") })
        .addTemplate("process-item", t => t
          .addRequiredInput("value", typeToken<string>())
          .addSuspend(0)
        )
        .addTemplate("main", t => t
          .addSteps(s => s.addStep(
            "loop-step",
            INTERNAL,
            "process-item",
            c => c.register({ value: c.item }),
            { loopWith: makeItemsLoop(["a", "b", "c"]) }
          ))
        )
        .setEntrypoint("main")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      expect(loopNodes.length).toBe(3);
      const items = loopNodes.map((n: any) => n.inputs?.parameters?.find((p: any) => p.name === "value")?.value).sort();
      expect(items).toEqual(["a", "b", "c"]);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Loop Item - withItems iterates over numbers", () => {
  const spec: ParitySpec = {
    category: "Loop Item",
    name: "withItems iterates over numbers",
    argoExpression: "withItems: [1,2,3]",
  };
  describe("ArgoYaml", () => {
    test("raw workflow loops and coerces numbers to strings", async () => {
      const namespace = getTestNamespace();
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "pli-items-numbers-direct-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: "test-runner",
          templates: [
            { name: "process-item", inputs: { parameters: [{ name: "value" }] }, suspend: { duration: "0" } },
            {
              name: "main",
              steps: [[{
                name: "loop-step",
                template: "process-item",
                arguments: { parameters: [{ name: "value", value: "{{item}}" }] },
                withItems: [1, 2, 3],
              }]],
            },
          ],
        },
      };
      const result = await submitAndWait(workflow);
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      const items = loopNodes.map((n: any) => n.inputs?.parameters?.find((p: any) => p.name === "value")?.value).sort();
      expect(items).toEqual(["1", "2", "3"]);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - loopWith items", () => {
    const builderVariant: BuilderVariant = {
      name: "loopWith items",
      code: "addStep(..., c => c.register({ value: c.item }), { loopWith: makeItemsLoop([1,2,3]) })",
    };

    test("builder workflow loops and coerces numbers to strings", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: uniqueWorkflowName("pli-items-numbers-builder") })
        .addTemplate("process-item", t => t
          .addRequiredInput("value", typeToken<string>())
          .addSuspend(0)
        )
        .addTemplate("main", t => t
          .addSteps(s => s.addStep(
            "loop-step",
            INTERNAL,
            "process-item",
            c => c.register({ value: c.item }),
            { loopWith: makeItemsLoop([1, 2, 3]) }
          ))
        )
        .setEntrypoint("main")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      const items = loopNodes.map((n: any) => n.inputs?.parameters?.find((p: any) => p.name === "value")?.value).sort();
      expect(items).toEqual(["1", "2", "3"]);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Loop Item - withItems JSON objects are serialized", () => {
  const spec: ParitySpec = {
    category: "Loop Item",
    name: "withItems JSON objects are serialized",
    argoExpression: "withItems: [{name:'alice'...}, {name:'bob'...}]",
  };
  describe("ArgoYaml", () => {
    test("raw workflow loops objects as serialized values", async () => {
      const namespace = getTestNamespace();
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "pli-items-objects-direct-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: "test-runner",
          templates: [
            { name: "process-item", inputs: { parameters: [{ name: "obj" }] }, suspend: { duration: "0" } },
            {
              name: "main",
              steps: [[{
                name: "loop-step",
                template: "process-item",
                arguments: { parameters: [{ name: "obj", value: "{{item}}" }] },
                withItems: [{ name: "alice", age: 30 }, { name: "bob", age: 25 }],
              }]],
            },
          ],
        },
      };
      const result = await submitAndWait(workflow);
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      const items = loopNodes
        .map((n: any) => JSON.parse(n.inputs?.parameters?.find((p: any) => p.name === "obj")?.value).name)
        .sort();
      expect(items).toEqual(["alice", "bob"]);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - loopWith items objects", () => {
    const builderVariant: BuilderVariant = {
      name: "loopWith items objects",
      code: "addStep(..., c => c.register({ obj: expr.serialize(c.item) }), { loopWith: makeItemsLoop([{name:'alice',age:30},{name:'bob',age:25}]) })",
    };

    test("builder workflow loops objects as serialized values", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: uniqueWorkflowName("pli-items-objects-builder") })
        .addTemplate("process-item", t => t
          .addRequiredInput("obj", typeToken<Serialized<{ name: string; age: number }>>())
          .addSuspend(0)
        )
        .addTemplate("main", t => t
          .addSteps(s => s.addStep(
            "loop-step",
            INTERNAL,
            "process-item",
            c => c.register({ obj: expr.serialize(c.item) }),
            { loopWith: makeItemsLoop([{ name: "alice", age: 30 }, { name: "bob", age: 25 }]) }
          ))
        )
        .setEntrypoint("main")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      const items = loopNodes
        .map((n: any) => JSON.parse(n.inputs?.parameters?.find((p: any) => p.name === "obj")?.value).name)
        .sort();
      expect(items).toEqual(["alice", "bob"]);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Loop Item - item used in expression directly", () => {
  const spec: ParitySpec = {
    category: "Loop Item",
    name: "item used in expression directly",
    argoExpression: "{{=item + '-processed'}}",
  };
  describe("ArgoYaml", () => {
    test("raw workflow computes per-item expression values", async () => {
      const namespace = getTestNamespace();
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "pli-items-expr-direct-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: "test-runner",
          templates: [
            { name: "process-item", inputs: { parameters: [{ name: "computed" }] }, suspend: { duration: "0" } },
            {
              name: "main",
              steps: [[{
                name: "loop-step",
                template: "process-item",
                arguments: { parameters: [{ name: "computed", value: "{{=item + '-processed'}}" }] },
                withItems: ["x", "y"],
              }]],
            },
          ],
        },
      };
      const result = await submitAndWait(workflow);
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      const items = loopNodes.map((n: any) => n.inputs?.parameters?.find((p: any) => p.name === "computed")?.value).sort();
      expect(items).toEqual(["x-processed", "y-processed"]);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - loop item expression", () => {
    const builderVariant: BuilderVariant = {
      name: "item expression",
      code: "addStep(..., c => c.register({ computed: expr.concat(c.item, expr.literal('-processed')) }), { loopWith: makeItemsLoop(['x','y']) })",
    };

    test("builder workflow computes per-item expression values", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: uniqueWorkflowName("pli-items-expr-builder") })
        .addTemplate("process-item", t => t
          .addRequiredInput("computed", typeToken<string>())
          .addSuspend(0)
        )
        .addTemplate("main", t => t
          .addSteps(s => s.addStep(
            "loop-step",
            INTERNAL,
            "process-item",
            c => c.register({
              computed: expr.concat(c.item, expr.literal("-processed")),
            }),
            { loopWith: makeItemsLoop(["x", "y"]) }
          ))
        )
        .setEntrypoint("main")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      const items = loopNodes.map((n: any) => n.inputs?.parameters?.find((p: any) => p.name === "computed")?.value).sort();
      expect(items).toEqual(["x-processed", "y-processed"]);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Loop Item - withParam from JSON array", () => {
  const spec: ParitySpec = {
    category: "Loop Item",
    name: "withParam from JSON array",
    argoExpression: "withParam: {{workflow.parameters.items}}",
    inputs: { items: '["one","two","three"]' },
  };
  describe("ArgoYaml", () => {
    test("raw workflow loops over parameter array", async () => {
      const namespace = getTestNamespace();
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "pli-param-json-array-direct-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: "test-runner",
          arguments: { parameters: [{ name: "items", value: '["one","two","three"]' }] },
          templates: [
            { name: "process-item", inputs: { parameters: [{ name: "value" }] }, suspend: { duration: "0" } },
            {
              name: "main",
              steps: [[{
                name: "loop-step",
                template: "process-item",
                arguments: { parameters: [{ name: "value", value: "{{item}}" }] },
                withParam: "{{workflow.parameters.items}}",
              }]],
            },
          ],
        },
      };
      const result = await submitAndWait(workflow);
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      const items = loopNodes.map((n: any) => n.inputs?.parameters?.find((p: any) => p.name === "value")?.value).sort();
      expect(items).toEqual(["one", "three", "two"]);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - loopWith param", () => {
    const builderVariant: BuilderVariant = {
      name: "loopWith param",
      code: "addStep(..., c => c.register({ value: c.item }), { loopWith: makeParameterLoop(ctx.inputs.items) })",
    };

    test("builder workflow loops over parameter array", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: uniqueWorkflowName("pli-param-json-array-builder") })
        .addTemplate("process-item", t => t
          .addRequiredInput("value", typeToken<string>())
          .addSuspend(0)
        )
        .addTemplate("main", t => t
          .addRequiredInput("items", typeToken<string[]>())
          .addSteps(s => s.addStep(
            "loop-step",
            INTERNAL,
            "process-item",
            c => c.register({ value: c.item }),
            { loopWith: makeParameterLoop(expr.deserializeRecord(s.inputs.items)) }
          ))
        )
        .setEntrypoint("main")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, { items: spec.inputs!.items });
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      const items = loopNodes.map((n: any) => n.inputs?.parameters?.find((p: any) => p.name === "value")?.value).sort();
      expect(items).toEqual(["one", "three", "two"]);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Loop Item - item number coerced with string()", () => {
  const spec: ParitySpec = {
    category: "Loop Item",
    name: "item number coerced with string()",
    argoExpression: "{{='value-' + string(item)}}",
  };
  describe("ArgoYaml", () => {
    test("raw workflow computes value-prefixed numeric items", async () => {
      const namespace = getTestNamespace();
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "pli-items-coerce-direct-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: "test-runner",
          templates: [
            { name: "process-item", inputs: { parameters: [{ name: "computed" }] }, suspend: { duration: "0" } },
            {
              name: "main",
              steps: [[{
                name: "loop-step",
                template: "process-item",
                arguments: { parameters: [{ name: "computed", value: "{{='value-' + string(item)}}" }] },
                withItems: [10, 20, 30],
              }]],
            },
          ],
        },
      };
      const result = await submitAndWait(workflow);
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      const items = loopNodes.map((n: any) => n.inputs?.parameters?.find((p: any) => p.name === "computed")?.value).sort();
      expect(items).toEqual(["value-10", "value-20", "value-30"]);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - item number coercion", () => {
    const builderVariant: BuilderVariant = {
      name: "item number coercion",
      code: "addStep(..., c => c.register({ computed: expr.concat(expr.literal('value-'), c.item) }), { loopWith: makeItemsLoop([10,20,30]) })",
    };

    test("builder workflow computes value-prefixed numeric items", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: uniqueWorkflowName("pli-items-coerce-builder") })
        .addTemplate("process-item", t => t
          .addRequiredInput("computed", typeToken<string>())
          .addSuspend(0)
        )
        .addTemplate("main", t => t
          .addSteps(s => s.addStep(
            "loop-step",
            INTERNAL,
            "process-item",
            c => c.register({
              computed: expr.concat(expr.literal("value-"), c.item),
            }),
            { loopWith: makeItemsLoop([10, 20, 30]) }
          ))
        )
        .setEntrypoint("main")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      const items = loopNodes.map((n: any) => n.inputs?.parameters?.find((p: any) => p.name === "computed")?.value).sort();
      expect(items).toEqual(["value-10", "value-20", "value-30"]);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Loop Item - withParam over objects delivers parsed objects", () => {
  const spec: ParitySpec = {
    category: "Loop Item",
    name: "withParam over objects delivers parsed objects",
    argoExpression: "withParam: {{=toJSON(workflow.parameters.items)}}",
    inputs: { items: JSON.stringify([{ name: "alice", age: 30 }, { name: "bob", age: 25 }]) },
  };

  describe("ArgoYaml", () => {
    test("raw workflow: each item is a parsed object, nested fields accessible via item['field']", async () => {
      const namespace = getTestNamespace();
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "pli-param-objects-direct-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: "test-runner",
          arguments: { parameters: [{ name: "items", value: spec.inputs!.items }] },
          templates: [
            { name: "process-item", inputs: { parameters: [{ name: "name" }] }, suspend: { duration: "0" } },
            {
              name: "main",
              steps: [[{
                name: "loop-step",
                template: "process-item",
                arguments: { parameters: [
                  // item is a parsed object in withParam — access fields directly
                  { name: "name", value: "{{=item['name']}}" },
                ]},
                withParam: "{{workflow.parameters.items}}",
              }]],
            },
          ],
        },
      };
      const result = await submitAndWait(workflow);
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      const names = loopNodes.map((n: any) => n.inputs?.parameters?.find((p: any) => p.name === "name")?.value).sort();
      expect(names).toEqual(["alice", "bob"]);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - withParam over objects: c.item is a parsed object at runtime", () => {
    const builderVariant: BuilderVariant = {
      name: "withParam over objects",
      code: "c.item is T at runtime in withParam; use expr.get(c.item, 'field') for field access, expr.serialize(c.item) to pass as Serialized<T>",
    };

    test("builder: c.item is a parsed object; expr.get accesses fields; expr.serialize passes to typed inputs", async () => {
      type Person = { name: string; age: number };
      const items: Person[] = [{ name: "alice", age: 30 }, { name: "bob", age: 25 }];

      const wf = WorkflowBuilder.create({ k8sResourceName: uniqueWorkflowName("pli-param-objects-builder") })
        .addTemplate("process-item", t => t
          .addRequiredInput("name", typeToken<string>())
          .addSuspend(0)
        )
        .addTemplate("main", t => t
          .addRequiredInput("items", typeToken<Person[]>())
          .addSteps(s => s.addStep(
            "loop-step",
            INTERNAL,
            "process-item",
            c => c.register({
              // c.item is a parsed object at runtime — expr.get works directly
              name: expr.get(c.item, "name"),
            }),
            { loopWith: makeParameterLoop(expr.deserializeRecord(s.inputs.items)) }
          ))
        )
        .setEntrypoint("main")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, { items: JSON.stringify(items) });
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      const names = loopNodes.map((n: any) => n.inputs?.parameters?.find((p: any) => p.name === "name")?.value).sort();
      expect(names).toEqual(["alice", "bob"]);
      reportParityResult(spec, builderVariant, result);
    });
  });
});



describe("Loop Item - withParam: nested loop over sub-array", () => {
  // Rule: to loop over a sub-array inside a template, use withParam + toJson(fromJson(param).subarray)
  const spec: ParitySpec = {
    category: "Loop Item",
    name: "nested loop over sub-array extracted from parameter",
    argoExpression: "withParam: toJson(fromJson(inputs.parameters.obj).tags)",
    inputs: { config: JSON.stringify({ env: "prod", tags: ["tag-a", "tag-b", "tag-c"] }) },
  };

  describe("ArgoYaml", () => {
    test("raw: loop over sub-array extracted from JSON parameter using toJson(fromJson(param).field)", async () => {
      const namespace = getTestNamespace();
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "pli-param-nested-loop-direct-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: getServiceAccountName(),
          arguments: { parameters: [{ name: "config", value: spec.inputs!.config }] },
          templates: [
            { name: "handle-tag", inputs: { parameters: [{ name: "tag" }] }, suspend: { duration: "0" } },
            {
              name: "main",
              steps: [[{
                name: "tag-steps",
                template: "handle-tag",
                arguments: { parameters: [{ name: "tag", value: "{{item}}" }] },
                // Extract sub-array from JSON param: fromJson gives native array, toJson converts back to JSON string for withParam
                withParam: "{{= toJSON(fromJSON(workflow.parameters.config)['tags']) }}",
              }]],
            },
          ],
        },
      };
      const result = await submitAndWait(workflow);
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result, "tag-steps(");
      const tags = loopNodes
        .map((n: any) => n.inputs?.parameters?.find((p: any) => p.name === "tag")?.value)
        .sort();
      expect(tags).toEqual(["tag-a", "tag-b", "tag-c"]);
      reportContractResult(spec, result);
    });
  });
});


describe("Loop Item - toJson to build JSON param inline from scalars", () => {
  // Rule: use toJson({...}) to construct a JSON object from multiple scalar params
  const spec: ParitySpec = {
    category: "Loop Item",
    name: "toJson builds JSON object from scalar params",
    argoExpression: "toJson({\"env\": inputs.parameters.env, \"count\": inputs.parameters.count})",
  };

  describe("ArgoYaml", () => {
    test("raw: toJson constructs JSON object from scalar inputs, fromJson reads it back", async () => {
      const namespace = getTestNamespace();
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "pli-param-tojson-build-direct-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: getServiceAccountName(),
          arguments: { parameters: [{ name: "env", value: "prod" }, { name: "count", value: "3" }] },
          templates: [
            {
              name: "consumer",
              inputs: { parameters: [{ name: "config" }] },
              outputs: {
                parameters: [
                  { name: "env",   valueFrom: { expression: "fromJSON(inputs.parameters.config)['env']" } },
                  { name: "count", valueFrom: { expression: "string(fromJSON(inputs.parameters.config)['count'])" } },
                ]
              },
              suspend: { duration: "0" }
            },
            {
              name: "main",
              steps: [[{
                name: "step",
                template: "consumer",
                arguments: { parameters: [{
                  name: "config",
                  // Build JSON object inline from scalar workflow params
                  value: "{{= toJSON({\"env\": workflow.parameters.env, \"count\": int(workflow.parameters.count)}) }}",
                }]},
              }]],
            },
          ],
        },
      };
      const result = await submitAndWait(workflow);
      expect(result.phase).toBe("Succeeded");
      const step = Object.values(result.nodeOutputs).find((n: any) =>
        n.parameters?.env !== undefined
      ) as any;
      expect(step?.parameters?.env).toBe("prod");
      expect(step?.parameters?.count).toBe("3");
      reportContractResult(spec, result);
    });
  });
});
