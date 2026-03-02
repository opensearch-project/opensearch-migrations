import { submitAndWait } from "../infra/workflowRunner";
import { getTestNamespace } from "../infra/argoCluster";

describe("Loop and Item Contract Tests", () => {
  test("withItems iterates over array - item is string", async () => {
    const namespace = getTestNamespace();
    
    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: {
        generateName: "cli-items-strings-direct-",
        namespace,
      },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: "test-runner",
        templates: [
          {
            name: "process-item",
            inputs: {
              parameters: [{ name: "value" }],
            },
            suspend: {
              duration: "0",
            },
          },
          {
            name: "main",
            steps: [
              [
                {
                  name: "loop-step",
                  template: "process-item",
                  arguments: {
                    parameters: [
                      {
                        name: "value",
                        value: "{{item}}",
                      },
                    ],
                  },
                  withItems: ["a", "b", "c"],
                },
              ],
            ],
          },
        ],
      },
    };
    
    const result = await submitAndWait(workflow);
    
    expect(result.phase).toBe("Succeeded");
    
    // Check that we have 3 loop iterations by counting nodes with displayName containing loop-step
    const loopNodes = Object.values(result.raw.status.nodes).filter(
      (n: any) => n.displayName && n.displayName.startsWith("loop-step(")
    );
    expect(loopNodes.length).toBe(3);
    
    // Verify the items were passed correctly by checking inputs
    const items = loopNodes.map((n: any) => 
      n.inputs?.parameters?.find((p: any) => p.name === "value")?.value
    ).sort();
    expect(items).toEqual(["a", "b", "c"]);
  });

  test("withItems iterates over numbers", async () => {
    const namespace = getTestNamespace();
    
    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: {
        generateName: "cli-items-numbers-direct-",
        namespace,
      },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: "test-runner",
        templates: [
          {
            name: "process-item",
            inputs: {
              parameters: [{ name: "value" }],
            },
            suspend: {
              duration: "0",
            },
          },
          {
            name: "main",
            steps: [
              [
                {
                  name: "loop-step",
                  template: "process-item",
                  arguments: {
                    parameters: [
                      {
                        name: "value",
                        value: "{{item}}",
                      },
                    ],
                  },
                  withItems: [1, 2, 3],
                },
              ],
            ],
          },
        ],
      },
    };
    
    const result = await submitAndWait(workflow);
    
    expect(result.phase).toBe("Succeeded");
    
    const loopNodes = Object.values(result.raw.status.nodes).filter(
      (n: any) => n.displayName && n.displayName.startsWith("loop-step(")
    );
    
    // Verify numbers are passed as strings
    const items = loopNodes.map((n: any) => 
      n.inputs?.parameters?.find((p: any) => p.name === "value")?.value
    ).sort();
    expect(items).toEqual(["1", "2", "3"]);
  });

  test("withItems with JSON objects - item is serialized", async () => {
    const namespace = getTestNamespace();
    
    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: {
        generateName: "cli-items-objects-direct-",
        namespace,
      },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: "test-runner",
        templates: [
          {
            name: "process-item",
            inputs: {
              parameters: [{ name: "obj" }],
            },
            suspend: {
              duration: "0",
            },
          },
          {
            name: "main",
            steps: [
              [
                {
                  name: "loop-step",
                  template: "process-item",
                  arguments: {
                    parameters: [
                      {
                        name: "obj",
                        value: "{{item}}",
                      },
                    ],
                  },
                  withItems: [
                    { name: "alice", age: 30 },
                    { name: "bob", age: 25 },
                  ],
                },
              ],
            ],
          },
        ],
      },
    };
    
    const result = await submitAndWait(workflow);
    
    expect(result.phase).toBe("Succeeded");
    
    const loopNodes = Object.values(result.raw.status.nodes).filter(
      (n: any) => n.displayName && n.displayName.startsWith("loop-step(")
    );
    
    // Verify objects are serialized as JSON strings
    const items = loopNodes.map((n: any) => {
      const objStr = n.inputs?.parameters?.find((p: any) => p.name === "obj")?.value;
      return JSON.parse(objStr).name;
    }).sort();
    expect(items).toEqual(["alice", "bob"]);
  });

  test("item can be used in expressions directly", async () => {
    const namespace = getTestNamespace();
    
    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: {
        generateName: "cli-items-expr-direct-",
        namespace,
      },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: "test-runner",
        templates: [
          {
            name: "process-item",
            inputs: {
              parameters: [{ name: "computed" }],
            },
            suspend: {
              duration: "0",
            },
          },
          {
            name: "main",
            steps: [
              [
                {
                  name: "loop-step",
                  template: "process-item",
                  arguments: {
                    parameters: [
                      {
                        name: "computed",
                        value: "{{=item + '-processed'}}",
                      },
                    ],
                  },
                  withItems: ["x", "y"],
                },
              ],
            ],
          },
        ],
      },
    };
    
    const result = await submitAndWait(workflow);
    
    expect(result.phase).toBe("Succeeded");
    
    const loopNodes = Object.values(result.raw.status.nodes).filter(
      (n: any) => n.displayName && n.displayName.startsWith("loop-step(")
    );
    
    const items = loopNodes.map((n: any) => 
      n.inputs?.parameters?.find((p: any) => p.name === "computed")?.value
    ).sort();
    expect(items).toEqual(["x-processed", "y-processed"]);
  });

  test("withParam from JSON array", async () => {
    const namespace = getTestNamespace();
    
    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: {
        generateName: "cli-param-json-array-direct-",
        namespace,
      },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: "test-runner",
        arguments: {
          parameters: [
            {
              name: "items",
              value: '["one","two","three"]',
            },
          ],
        },
        templates: [
          {
            name: "process-item",
            inputs: {
              parameters: [{ name: "value" }],
            },
            suspend: {
              duration: "0",
            },
          },
          {
            name: "main",
            steps: [
              [
                {
                  name: "loop-step",
                  template: "process-item",
                  arguments: {
                    parameters: [
                      {
                        name: "value",
                        value: "{{item}}",
                      },
                    ],
                  },
                  withParam: "{{workflow.parameters.items}}",
                },
              ],
            ],
          },
        ],
      },
    };
    
    const result = await submitAndWait(workflow);
    
    expect(result.phase).toBe("Succeeded");
    
    const loopNodes = Object.values(result.raw.status.nodes).filter(
      (n: any) => n.displayName && n.displayName.startsWith("loop-step(")
    );
    
    const items = loopNodes.map((n: any) => 
      n.inputs?.parameters?.find((p: any) => p.name === "value")?.value
    ).sort();
    expect(items).toEqual(["one", "three", "two"]);
  });

  test("item type coercion - number to string", async () => {
    const namespace = getTestNamespace();
    
    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: {
        generateName: "cli-items-coerce-direct-",
        namespace,
      },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: "test-runner",
        templates: [
          {
            name: "process-item",
            inputs: {
              parameters: [{ name: "computed" }],
            },
            suspend: {
              duration: "0",
            },
          },
          {
            name: "main",
            steps: [
              [
                {
                  name: "loop-step",
                  template: "process-item",
                  arguments: {
                    parameters: [
                      {
                        name: "computed",
                        value: "{{='value-' + string(item)}}",
                      },
                    ],
                  },
                  withItems: [10, 20, 30],
                },
              ],
            ],
          },
        ],
      },
    };
    
    const result = await submitAndWait(workflow);
    
    expect(result.phase).toBe("Succeeded");
    
    const loopNodes = Object.values(result.raw.status.nodes).filter(
      (n: any) => n.displayName && n.displayName.startsWith("loop-step(")
    );
    
    const items = loopNodes.map((n: any) => 
      n.inputs?.parameters?.find((p: any) => p.name === "computed")?.value
    ).sort();
    expect(items).toEqual(["value-10", "value-20", "value-30"]);
  });
});
