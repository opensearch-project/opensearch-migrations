import { submitAndWait } from "../infra/workflowRunner";
import { getTestNamespace, getServiceAccountName } from "../infra/argoCluster";

/**
 * Tests that serialized boolean parameters ("false"/"true" strings) work
 * correctly in when clauses via simple substitution — the pattern produced
 * by expr.not(b.inputs.someBooleanParam).
 *
 * This validates that Argo's govaluate engine natively coerces boolean
 * strings without needing fromJSON, which is critical because fromJSON
 * in when clauses fails on computed default expressions in Argo v4.0.
 */
describe("Serialized boolean negation in when clauses", () => {
  const namespace = getTestNamespace();

  async function submitBooleanWhenProbe(boolValue: string, whenCondition: string) {
    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: { generateName: "sbool-when-", namespace },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: getServiceAccountName(),
        templates: [
          {
            name: "noop",
            steps: [[]],
          },
          {
            name: "main",
            inputs: {
              parameters: [{ name: "flag", value: boolValue }],
            },
            steps: [[{
              name: "conditional-step",
              template: "noop",
              when: whenCondition,
            }]],
          },
        ],
      },
    };
    return submitAndWait(workflow);
  }

  // Pattern: !({{inputs.parameters.flag}}) — what expr.not(serializedBool) renders
  test("!(false) runs the step", async () => {
    const result = await submitBooleanWhenProbe("false", "!({{inputs.parameters.flag}})");
    expect(result.phase).toBe("Succeeded");
    expect(result.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
  });

  test("!(true) skips the step", async () => {
    const result = await submitBooleanWhenProbe("true", "!({{inputs.parameters.flag}})");
    expect(result.phase).toBe("Succeeded");
    expect(result.nodeOutputs["conditional-step"].phase).toMatch(/Skipped|Omitted/);
  });

  // Computed default: sprig.dig returns "false"/"true" as a string
  test("!(sprig.dig result) works via simple substitution", async () => {
    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: { generateName: "sbool-dig-", namespace },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: getServiceAccountName(),
        templates: [
          {
            name: "noop",
            steps: [[]],
          },
          {
            name: "main",
            inputs: {
              parameters: [
                { name: "map", value: "{}" },
                {
                  // Computed default: sprig.dig into empty map returns false
                  name: "skipFlag",
                  value: "{{=sprig.dig('a', 'b', false, fromJSON(inputs.parameters.map))}}",
                },
              ],
            },
            steps: [[{
              name: "conditional-step",
              template: "noop",
              // Simple substitution of the computed boolean — no fromJSON needed
              when: "!({{inputs.parameters.skipFlag}})",
            }]],
          },
        ],
      },
    };

    const result = await submitAndWait(workflow);
    expect(result.phase).toBe("Succeeded");
    // sprig.dig into {} returns false, !(false) = true, so step runs
    expect(result.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
  });
});
