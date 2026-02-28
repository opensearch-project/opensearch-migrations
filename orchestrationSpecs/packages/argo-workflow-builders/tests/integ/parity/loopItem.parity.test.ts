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
import { getTestNamespace } from "../infra/argoCluster.js";
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
        metadata: { generateName: "loop-strings-", namespace },
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
      const wf = WorkflowBuilder.create({ k8sResourceName: "loop-strings-builder" })
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
        metadata: { generateName: "loop-numbers-", namespace },
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
      const wf = WorkflowBuilder.create({ k8sResourceName: "loop-numbers-builder" })
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
        metadata: { generateName: "loop-objects-", namespace },
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
      const wf = WorkflowBuilder.create({ k8sResourceName: "loop-objects-builder" })
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
        metadata: { generateName: "loop-expr-", namespace },
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
      const wf = WorkflowBuilder.create({ k8sResourceName: "loop-expr-builder" })
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
        metadata: { generateName: "loop-param-", namespace },
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
      const wf = WorkflowBuilder.create({ k8sResourceName: "loop-param-builder" })
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
        metadata: { generateName: "loop-coerce-", namespace },
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
      const wf = WorkflowBuilder.create({ k8sResourceName: "loop-coerce-builder" })
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
