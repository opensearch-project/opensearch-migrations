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

function countLoopNodes(result: any, prefix = "loop-step(") {
  return Object.values(result.raw.status.nodes).filter(
    (n: any) => n.displayName && n.displayName.startsWith(prefix)
  );
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
      code: "addStep(..., c => c.register({ value: expr.asString(c.item) }), { loopWith: makeItemsLoop(['a','b','c']) })",
    };

    test("builder workflow loops 3 times and passes item", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: "pli-items-strings-builder" })
        .addTemplate("process-item", t => t
          .addRequiredInput("value", typeToken<string>())
          .addSuspend(0)
        )
        .addTemplate("main", t => t
          .addSteps(s => s.addStep(
            "loop-step",
            INTERNAL,
            "process-item",
            c => c.register({ value: expr.asString(c.item) }),
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
      code: "addStep(..., c => c.register({ value: expr.asString(c.item) }), { loopWith: makeItemsLoop([1,2,3]) })",
    };

    test("builder workflow loops and coerces numbers to strings", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: "pli-items-numbers-builder" })
        .addTemplate("process-item", t => t
          .addRequiredInput("value", typeToken<string>())
          .addSuspend(0)
        )
        .addTemplate("main", t => t
          .addSteps(s => s.addStep(
            "loop-step",
            INTERNAL,
            "process-item",
            c => c.register({ value: expr.asString(c.item) }),
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
      const wf = WorkflowBuilder.create({ k8sResourceName: "pli-items-objects-builder" })
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
      code: "addStep(..., c => c.register({ computed: expr.concat(expr.asString(c.item), expr.literal('-processed')) }), { loopWith: makeItemsLoop(['x','y']) })",
    };

    test("builder workflow computes per-item expression values", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: "pli-items-expr-builder" })
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
              computed: expr.concat(expr.asString(c.item), expr.literal("-processed")),
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
      code: "addStep(..., c => c.register({ value: expr.asString(c.item) }), { loopWith: makeParameterLoop(ctx.inputs.items) })",
    };

    test("builder workflow loops over parameter array", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: "pli-param-json-array-builder" })
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
            c => c.register({ value: expr.asString(c.item) }),
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
      code: "addStep(..., c => c.register({ computed: expr.concat(expr.literal('value-'), expr.asString(c.item)) }), { loopWith: makeItemsLoop([10,20,30]) })",
    };

    test("builder workflow computes value-prefixed numeric items", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: "pli-items-coerce-builder" })
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
              computed: expr.concat(expr.literal("value-"), expr.asString(c.item)),
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

      const wf = WorkflowBuilder.create({ k8sResourceName: "pli-param-objects-builder" })
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
              name: expr.asString(expr.get(c.item, "name")),
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

describe("Loop Item - withParam over objects with nested fields (realistic fullMigration pattern)", () => {
  // This mirrors how fullMigration passes proxies/snapshots/replays:
  // - A JSON string of an array of complex objects is passed as a workflow parameter
  // - One deserializeRecord hydrates it into an array (no nested serialization)
  // - The loop iterates over the items, accessing nested fields
  type KafkaConfig = { connection: string; topic: string };
  type ProxyConfig = { name: string; kafkaConfig: KafkaConfig; listenPort: number };

  const proxies: ProxyConfig[] = [
    { name: "proxy-a", kafkaConfig: { connection: "kafka-a:9092", topic: "topic-a" }, listenPort: 9200 },
    { name: "proxy-b", kafkaConfig: { connection: "kafka-b:9092", topic: "topic-b" }, listenPort: 9201 },
  ];

  const spec: ParitySpec = {
    category: "Loop Item",
    name: "withParam over nested objects (fullMigration pattern)",
    argoExpression: "withParam over complex objects, accessing nested fields",
    inputs: { proxies: JSON.stringify(proxies) },
  };

  describe("ArgoYaml", () => {
    test("raw: nested fields accessible via item['kafkaConfig']['connection']", async () => {
      const namespace = getTestNamespace();
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "pli-param-nested-direct-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: getServiceAccountName(),
          arguments: { parameters: [{ name: "proxies", value: spec.inputs!.proxies }] },
          templates: [
            {
              name: "process-proxy",
              inputs: { parameters: [{ name: "name" }, { name: "connection" }, { name: "topic" }] },
              suspend: { duration: "0" }
            },
            {
              name: "main",
              steps: [[{
                name: "loop-step",
                template: "process-proxy",
                arguments: { parameters: [
                  { name: "name",       value: "{{=item['name']}}" },
                  // nested object: item['kafkaConfig'] is a JSON string, need fromJSON to parse it
                  { name: "connection", value: "{{=fromJSON(item['kafkaConfig'])['connection']}}" },
                  { name: "topic",      value: "{{=fromJSON(item['kafkaConfig'])['topic']}}" },
                ]},
                withParam: "{{workflow.parameters.proxies}}",
              }]],
            },
          ],
        },
      };
      const result = await submitAndWait(workflow);
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      const connections = loopNodes
        .map((n: any) => n.inputs?.parameters?.find((p: any) => p.name === "connection")?.value)
        .sort();
      expect(connections).toEqual(["kafka-a:9092", "kafka-b:9092"]);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - withParam over nested objects without casts", () => {
    const builderVariant: BuilderVariant = {
      name: "withParam nested objects no-cast",
      code: "TODO: find the cast-free pattern for passing c.item to typed inputs and accessing nested fields",
    };

    test.skip("builder: pass c.item to typed input and access nested fields without cast", async () => {
      // This test documents the GOAL: no expr.cast() needed.
      // Currently fullMigration uses expr.cast(c.item).to<Serialized<T>>() as a workaround.
      // The ideal would be:
      //   proxyConfig: c.item  (directly, type-safe)
      //   kafkaConnection: expr.get(expr.get(c.item, "kafkaConfig"), "connection")
      //
      // Blocked by: c.item is typed as T but withParam delivers it as a parsed object,
      // and the register() call expects Serialized<T> for complex object inputs.
      // See: taskBuilder.ts ParamProviderCallbackObject item type.
      reportParityResult(spec, builderVariant, { phase: "Skipped" } as any);
    });
  });
});
