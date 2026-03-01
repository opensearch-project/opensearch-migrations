import { INTERNAL, WorkflowBuilder, expr, renderWorkflowTemplate, typeToken } from "../../../src/index.js";
import { getTestNamespace } from "../infra/argoCluster.js";
import { submitProbe, submitRenderedWorkflow } from "../infra/probeHelper.js";
import { submitAndWait } from "../infra/workflowRunner.js";
import { BuilderVariant, ParitySpec, reportContractResult, reportKnownBroken, reportParityResult } from "../infra/parityHelper.js";
import { makeTestWorkflow } from "../infra/testWorkflowHelper.js";
import { describeBroken } from "../infra/brokenTestControl.js";

describe("Expression Utilities - fillTemplate", () => {
  const spec: ParitySpec = {
    category: "Expression Utilities",
    name: "fillTemplate",
    inputs: { first: "alpha", second: "beta" },
    argoExpression: "inputs.parameters.first + '-' + inputs.parameters.second",
    expectedResult: "alpha-beta",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({
        inputs: spec.inputs,
        expression: spec.argoExpression,
      });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });

  describeBroken("Builder - fillTemplate", () => {
    const builderVariant: BuilderVariant = {
      name: "fillTemplate",
      code: 'expr.fillTemplate("{{first}}-{{second}}", { first: ctx.inputs.first, second: ctx.inputs.second })',
    };
    reportKnownBroken(spec, builderVariant, "Runtime phase Error: fillTemplate builder output does not execute successfully in Argo.");

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addRequiredInput("first", typeToken<string>())
        .addRequiredInput("second", typeToken<string>())
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", ctx =>
          expr.fillTemplate("{{first}}-{{second}}", {
            first: ctx.inputs.first,
            second: ctx.inputs.second,
          })
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, spec.inputs);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describeBroken("Expression Utilities - workflow serviceAccountName value", () => {
  const spec: ParitySpec = {
    category: "Expression Utilities",
    name: "workflow serviceAccountName value",
    argoExpression: "workflow.serviceAccountName",
    expectedResult: "test-runner",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({
        expression: spec.argoExpression,
      });
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - getWorkflowValue", () => {
    const builderVariant: BuilderVariant = {
      name: "getWorkflowValue",
      code: 'expr.getWorkflowValue("serviceAccountName")',
    };

    test("builder API produces same result", async () => {
      const wf = makeTestWorkflow(t => t
        .addSteps(s => s.addStepGroup(c => c))
        .addExpressionOutput("result", () =>
          expr.getWorkflowValue("serviceAccountName")
        )
      );

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Expression Utilities - taskData steps output reference", () => {
  const spec: ParitySpec = {
    category: "Expression Utilities",
    name: "taskData steps output reference",
    argoExpression: "steps.produce.outputs.parameters.value",
    expectedResult: "hello",
  };

  describe("ArgoYaml", () => {
    test("raw workflow expression can read prior step output", async () => {
      const namespace = getTestNamespace();
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "peu-taskdata-raw-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: "test-runner",
          templates: [
            {
              name: "producer",
              steps: [[]],
              outputs: {
                parameters: [
                  {
                    name: "value",
                    valueFrom: {
                      expression: "'hello'",
                    },
                  },
                ],
              },
            },
            {
              name: "main",
              steps: [[{ name: "produce", template: "producer" }]],
              outputs: {
                parameters: [
                  {
                    name: "result",
                    valueFrom: {
                      expression: spec.argoExpression,
                    },
                  },
                ],
              },
            },
          ],
        },
      };

      const result = await submitAndWait(workflow);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportContractResult(spec, result);
    });
  });

  describeBroken("Builder - taskData", () => {
    const builderVariant: BuilderVariant = {
      name: "taskData",
      code: 'expr.taskData("steps", "produce", "outputs.parameters.value")',
    };
    reportKnownBroken(spec, builderVariant, "Runtime Failed: taskData reference rendering is not yet compatible for this steps output access.");

    test("builder API produces same result", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: "peu-taskdata-builder" })
        .addTemplate("producer", t => t
          .addSteps(s => s.addStepGroup(c => c))
          .addExpressionOutput("value", () => expr.literal("hello"))
        )
        .addTemplate("main", t => t
          .addSteps(s => s.addStep("produce", INTERNAL, "producer"))
          .addExpressionOutput("result", () =>
            expr.taskData<string>("steps", "produce", "outputs.parameters.value")
          )
        )
        .setEntrypoint("main")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe("Succeeded");
      expect(result.globalOutputs.result).toBe(spec.expectedResult);
      reportParityResult(spec, builderVariant, result);
    });
  });
});
