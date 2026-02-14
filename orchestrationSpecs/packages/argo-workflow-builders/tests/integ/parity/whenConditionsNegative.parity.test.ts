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
        { name: "conditional", inputs: { parameters: [{ name: "val" }] }, steps: [[]] },
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
    category: "When Conditions Negative",
    name,
    inputs: { input },
    argoExpression: whenCondition,
  };
}

describe("When Conditions Negative - false condition not succeeded", () => {
  const s = spec("false condition not succeeded", "no", "{{workflow.parameters.input}} == yes");
  describe("ArgoYaml", () => {
    test("raw conditional workflow does not execute step", async () => {
      const r = await submitConditionalProbe("no", s.argoExpression);
      expect(r.phase).toBe("Succeeded");
      expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Succeeded");
      expect(r.nodeOutputs["conditional-step"].phase).toBe("Skipped");
      reportContractResult(s, r);
    });
  });
});

describe("When Conditions Negative - true condition not skipped", () => {
  const s = spec("true condition not skipped", "yes", "{{workflow.parameters.input}} == yes");
  describe("ArgoYaml", () => {
    test("raw conditional workflow executes step", async () => {
      const r = await submitConditionalProbe("yes", s.argoExpression);
      expect(r.phase).toBe("Succeeded");
      expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Skipped");
      expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Omitted");
      expect(r.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
      reportContractResult(s, r);
    });
  });
});

describe("When Conditions Negative - expression false not succeeded", () => {
  const s = spec("expression false not succeeded", "1", "{{= asInt(workflow.parameters.input) > 3 }}");
  describe("ArgoYaml", () => {
    test("raw conditional workflow does not execute step", async () => {
      const r = await submitConditionalProbe("1", s.argoExpression);
      expect(r.phase).toBe("Succeeded");
      expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Succeeded");
      expect(r.nodeOutputs["conditional-step"].phase).toBe("Skipped");
      reportContractResult(s, r);
    });
  });
});

describe("When Conditions Negative - expression true not skipped", () => {
  const s = spec("expression true not skipped", "5", "{{= asInt(workflow.parameters.input) > 3 }}");
  describe("ArgoYaml", () => {
    test("raw conditional workflow executes step", async () => {
      const r = await submitConditionalProbe("5", s.argoExpression);
      expect(r.phase).toBe("Succeeded");
      expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Skipped");
      expect(r.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
      reportContractResult(s, r);
    });
  });
});

describe("When Conditions Negative - wrong comparison value skips", () => {
  const s = spec("wrong comparison value skips", "maybe", "{{workflow.parameters.input}} == yes");
  describe("ArgoYaml", () => {
    test("raw conditional workflow skips step", async () => {
      const r = await submitConditionalProbe("maybe", s.argoExpression);
      expect(r.phase).toBe("Succeeded");
      expect(r.nodeOutputs["conditional-step"].phase).not.toBe("Succeeded");
      expect(r.nodeOutputs["conditional-step"].phase).toBe("Skipped");
      reportContractResult(s, r);
    });
  });
});
