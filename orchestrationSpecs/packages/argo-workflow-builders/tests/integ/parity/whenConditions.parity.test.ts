import { INTERNAL, WorkflowBuilder, expr, renderWorkflowTemplate, typeToken } from "../../../src/index.js";
import { getTestNamespace } from "../infra/argoCluster.js";
import { submitRenderedWorkflow } from "../infra/probeHelper.js";
import { submitAndWait } from "../infra/workflowRunner.js";
import { BuilderVariant, ParitySpec, reportContractResult, reportParityResult } from "../infra/parityHelper.js";

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

  describe("Builder - when equals", () => {
    const builderVariant: BuilderVariant = {
      name: "when equals",
      code: "addStep(..., { when: { templateExp: expr.equals(expr.length(ctx.inputs.input), expr.literal(3)) } })",
    };

    test("builder API executes step", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: "when-conditions-builder-true" })
        .addTemplate("conditional", t => t
          .addSteps(s => s.addStepGroup(c => c))
        )
        .addTemplate("main", t => t
          .addRequiredInput("input", typeToken<string>())
          .addSteps(s => s.addStep(
            "conditional-step",
            INTERNAL,
            "conditional",
            {
              when: {
                templateExp: expr.equals(expr.length(s.inputs.input), expr.literal(3)),
              },
            }
          ))
        )
        .setEntrypoint("main")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const r = await submitRenderedWorkflow(rendered, { input: s.inputs!.input });
      expect(r.phase).toBe("Succeeded");
      expect(r.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
      reportParityResult(s, builderVariant, r);
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

  describe("Builder - when equals", () => {
    const builderVariant: BuilderVariant = {
      name: "when equals",
      code: "addStep(..., { when: { templateExp: expr.equals(expr.length(ctx.inputs.input), expr.literal(3)) } })",
    };

    test("builder API skips step", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: "when-conditions-builder-false" })
        .addTemplate("conditional", t => t
          .addSteps(s => s.addStepGroup(c => c))
        )
        .addTemplate("main", t => t
          .addRequiredInput("input", typeToken<string>())
          .addSteps(s => s.addStep(
            "conditional-step",
            INTERNAL,
            "conditional",
            {
              when: {
                templateExp: expr.equals(expr.length(s.inputs.input), expr.literal(3)),
              },
            }
          ))
        )
        .setEntrypoint("main")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const r = await submitRenderedWorkflow(rendered, { input: s.inputs!.input });
      expect(r.phase).toBe("Succeeded");
      expect(r.nodeOutputs["conditional-step"].phase).toBe("Skipped");
      reportParityResult(s, builderVariant, r);
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

  describe("Builder - when numeric comparison", () => {
    const builderVariant: BuilderVariant = {
      name: "when numeric comparison",
      code: "addStep(..., { when: { templateExp: expr.greaterThan(expr.deserializeRecord(ctx.inputs.input), expr.literal(3)) } })",
    };

    test("builder API executes step", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: "when-conditions-builder-expr-true" })
        .addTemplate("conditional", t => t
          .addSteps(s => s.addStepGroup(c => c))
        )
        .addTemplate("main", t => t
          .addRequiredInput("input", typeToken<number>())
          .addSteps(s => s.addStep(
            "conditional-step",
            INTERNAL,
            "conditional",
            {
              when: {
                templateExp: expr.greaterThan(
                  expr.deserializeRecord(s.inputs.input),
                  expr.literal(3)
                ),
              },
            }
          ))
        )
        .setEntrypoint("main")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const r = await submitRenderedWorkflow(rendered, { input: s.inputs!.input });
      expect(r.phase).toBe("Succeeded");
      expect(r.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
      reportParityResult(s, builderVariant, r);
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

  describe("Builder - when numeric comparison", () => {
    const builderVariant: BuilderVariant = {
      name: "when numeric comparison",
      code: "addStep(..., { when: { templateExp: expr.greaterThan(expr.deserializeRecord(ctx.inputs.input), expr.literal(3)) } })",
    };

    test("builder API skips step", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: "when-conditions-builder-expr-false" })
        .addTemplate("conditional", t => t
          .addSteps(s => s.addStepGroup(c => c))
        )
        .addTemplate("main", t => t
          .addRequiredInput("input", typeToken<number>())
          .addSteps(s => s.addStep(
            "conditional-step",
            INTERNAL,
            "conditional",
            {
              when: {
                templateExp: expr.greaterThan(
                  expr.deserializeRecord(s.inputs.input),
                  expr.literal(3)
                ),
              },
            }
          ))
        )
        .setEntrypoint("main")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const r = await submitRenderedWorkflow(rendered, { input: s.inputs!.input });
      expect(r.phase).toBe("Succeeded");
      expect(r.nodeOutputs["conditional-step"].phase).toBe("Skipped");
      reportParityResult(s, builderVariant, r);
    });
  });
});
