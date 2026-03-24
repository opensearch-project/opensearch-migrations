import { submitAndWait } from "../infra/workflowRunner";
import { getTestNamespace } from "../infra/argoCluster";

describe("Negative When Conditions Tests", () => {
  async function submitConditionalProbe(input: string, whenCondition: string) {
    const namespace = getTestNamespace();
    
    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: {
        generateName: "cwcn-when-direct-",
        namespace,
      },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: "test-runner",
        arguments: {
          parameters: [{ name: "input", value: input }],
        },
        templates: [
          {
            name: "conditional",
            inputs: {
              parameters: [{ name: "val" }],
            },
            steps: [[]],
          },
          {
            name: "main",
            steps: [
              [
                {
                  name: "conditional-step",
                  template: "conditional",
                  when: whenCondition,
                  arguments: {
                    parameters: [
                      {
                        name: "val",
                        value: "{{workflow.parameters.input}}",
                      },
                    ],
                  },
                },
              ],
            ],
          },
        ],
      },
    };
    
    return submitAndWait(workflow);
  }

  test("false condition does not execute (not Succeeded)", async () => {
    const result = await submitConditionalProbe("no", "{{workflow.parameters.input}} == yes");
    
    expect(result.phase).toBe("Succeeded");
    expect(result.nodeOutputs["conditional-step"].phase).not.toBe("Succeeded");
    expect(result.nodeOutputs["conditional-step"].phase).toBe("Skipped");
  });

  test("true condition does not skip (not Skipped)", async () => {
    const result = await submitConditionalProbe("yes", "{{workflow.parameters.input}} == yes");
    
    expect(result.phase).toBe("Succeeded");
    expect(result.nodeOutputs["conditional-step"].phase).not.toBe("Skipped");
    expect(result.nodeOutputs["conditional-step"].phase).not.toBe("Omitted");
    expect(result.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
  });

  test("expression false does not execute", async () => {
    const result = await submitConditionalProbe("1", "{{= asInt(workflow.parameters.input) > 3 }}");
    
    expect(result.phase).toBe("Succeeded");
    expect(result.nodeOutputs["conditional-step"].phase).not.toBe("Succeeded");
    expect(result.nodeOutputs["conditional-step"].phase).toBe("Skipped");
  });

  test("expression true does not skip", async () => {
    const result = await submitConditionalProbe("5", "{{= asInt(workflow.parameters.input) > 3 }}");
    
    expect(result.phase).toBe("Succeeded");
    expect(result.nodeOutputs["conditional-step"].phase).not.toBe("Skipped");
    expect(result.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
  });

  test("wrong comparison value skips", async () => {
    const result = await submitConditionalProbe("maybe", "{{workflow.parameters.input}} == yes");
    
    expect(result.phase).toBe("Succeeded");
    expect(result.nodeOutputs["conditional-step"].phase).not.toBe("Succeeded");
    expect(result.nodeOutputs["conditional-step"].phase).toBe("Skipped");
  });
});
