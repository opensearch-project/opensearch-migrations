import { getTestNamespace } from "../infra/argoCluster.js";
import { submitAndWait } from "../infra/workflowRunner.js";
import { ParitySpec, reportContractResult } from "../infra/parityHelper.js";

async function submitConditionalProbe(input: string, whenCondition: string) {
  const namespace = getTestNamespace();
  const workflow = {
    apiVersion: "argoproj.io/v1alpha1",
    kind: "Workflow",
    metadata: { generateName: "when-probe-", namespace },
    spec: {
      entrypoint: "main",
      activeDeadlineSeconds: 30,
      serviceAccountName: "test-runner",
      arguments: { parameters: [{ name: "input", value: input }] },
      templates: [
        {
          name: "conditional",
          inputs: { parameters: [{ name: "val" }] },
          steps: [[]],
        },
        {
          name: "main",
          steps: [[{
            name: "conditional-step",
            template: "conditional",
            when: whenCondition,
            arguments: { parameters: [{ name: "val", value: "{{workflow.parameters.input}}" }] },
          }]],
        },
      ],
    },
  };
  return submitAndWait(workflow);
}

function spec(name: string, input: string, whenCondition: string): ParitySpec {
  return {
    category: "When Conditions",
    name,
    inputs: { input },
    argoExpression: whenCondition,
  };
}

describe("When Conditions - simple string match true", () => {
  const s = spec("simple string match true", "yes", "{{workflow.parameters.input}} == yes");
  describe("ArgoYaml", () => {
    test("raw conditional workflow executes step", async () => {
      const r = await submitConditionalProbe("yes", s.argoExpression);
      expect(r.phase).toBe("Succeeded");
      expect(r.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
      reportContractResult(s, r);
    });
  });
});

describe("When Conditions - simple string match false", () => {
  const s = spec("simple string match false", "no", "{{workflow.parameters.input}} == yes");
  describe("ArgoYaml", () => {
    test("raw conditional workflow skips step", async () => {
      const r = await submitConditionalProbe("no", s.argoExpression);
      expect(r.phase).toBe("Succeeded");
      expect(r.nodeOutputs["conditional-step"].phase).toBe("Skipped");
      reportContractResult(s, r);
    });
  });
});

describe("When Conditions - expression based true", () => {
  const s = spec("expression based true", "5", "{{= asInt(workflow.parameters.input) > 3 }}");
  describe("ArgoYaml", () => {
    test("raw conditional workflow executes step", async () => {
      const r = await submitConditionalProbe("5", s.argoExpression);
      expect(r.phase).toBe("Succeeded");
      expect(r.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
      reportContractResult(s, r);
    });
  });
});

describe("When Conditions - expression based false", () => {
  const s = spec("expression based false", "1", "{{= asInt(workflow.parameters.input) > 3 }}");
  describe("ArgoYaml", () => {
    test("raw conditional workflow skips step", async () => {
      const r = await submitConditionalProbe("1", s.argoExpression);
      expect(r.phase).toBe("Succeeded");
      expect(r.nodeOutputs["conditional-step"].phase).toBe("Skipped");
      reportContractResult(s, r);
    });
  });
});
