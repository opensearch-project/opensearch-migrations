import { submitAndWait } from "../infra/workflowRunner";
import { getTestNamespace } from "../infra/argoCluster";

describe("When Conditions Contract Tests", () => {
  async function submitConditionalProbe(input: string, whenCondition: string) {
    const namespace = getTestNamespace();
    
    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: {
        generateName: "cwc-when-direct-",
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

  test("simple string match - true", async () => {
    const result = await submitConditionalProbe("yes", "{{workflow.parameters.input}} == yes");
    
    expect(result.phase).toBe("Succeeded");
    expect(result.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
  });

  test("simple string match - false", async () => {
    const result = await submitConditionalProbe("no", "{{workflow.parameters.input}} == yes");
    
    expect(result.phase).toBe("Succeeded");
    expect(result.nodeOutputs["conditional-step"].phase).toBe("Skipped");
  });

  test("expression-based when - true", async () => {
    const result = await submitConditionalProbe("5", "{{= asInt(workflow.parameters.input) > 3 }}");
    
    expect(result.phase).toBe("Succeeded");
    expect(result.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
  });

  test("expression-based when - false", async () => {
    const result = await submitConditionalProbe("1", "{{= asInt(workflow.parameters.input) > 3 }}");
    
    expect(result.phase).toBe("Succeeded");
    expect(result.nodeOutputs["conditional-step"].phase).toBe("Skipped");
  });
});
