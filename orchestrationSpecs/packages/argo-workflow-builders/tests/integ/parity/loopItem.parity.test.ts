import { getTestNamespace } from "../infra/argoCluster.js";
import { submitAndWait } from "../infra/workflowRunner.js";
import { ParitySpec, reportContractResult } from "../infra/parityHelper.js";

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
});
