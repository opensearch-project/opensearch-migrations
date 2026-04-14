import { expr, renderWorkflowTemplate, typeToken, WorkflowBuilder } from "../../../src/index.js";
import { submitAndWait } from "../infra/workflowRunner.js";
import { getKubeConfig, getServiceAccountName, getTestNamespace } from "../infra/argoCluster.js";
import { submitRenderedWorkflow } from "../infra/probeHelper.js";
import { BuilderVariant, ParitySpec, reportContractResult, reportParityResult } from "../infra/parityHelper.js";
import { CoreV1Api, KubeConfig } from "@kubernetes/client-node";

describe("Resource Conditions - successCondition expression", () => {
  const spec: ParitySpec = {
    category: "Resource Conditions",
    name: "successCondition expression",
    inputs: { expectedState: "ready" },
    argoExpression: `{{="data.state == " + inputs.parameters.expectedState}}`,
    expectedPhase: "Succeeded",
  };
  const namespace = getTestNamespace();
  const configMapName = `parity-success-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  let coreApi: CoreV1Api;

  beforeAll(async () => {
    const kc = new KubeConfig();
    kc.loadFromString(getKubeConfig());
    coreApi = kc.makeApiClient(CoreV1Api);

    await coreApi.createNamespacedConfigMap({
      namespace,
      body: {
        metadata: {
          name: "artifact-repositories",
          annotations: { "workflows.argoproj.io/default-artifact-repository": "empty" },
        },
        data: { empty: "" },
      },
    }).catch(() => {
      /* may already exist */
    });

    await coreApi.createNamespacedConfigMap({
      namespace,
      body: {
        metadata: { name: configMapName },
        data: { state: "ready" },
      },
    });
  });

  afterAll(async () => {
    await coreApi.deleteNamespacedConfigMap({ name: configMapName, namespace }).catch(() => {
      /* cleanup best-effort */
    });
  });

  describe("ArgoYaml", () => {
    test("raw resource condition expression succeeds", async () => {
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "parity-resource-success-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: getServiceAccountName(),
          templates: [
            {
              name: "main",
              steps: [[{
                name: "check",
                template: "check-config-map",
                arguments: {
                  parameters: [
                    { name: "name", value: configMapName },
                    { name: "expectedState", value: spec.inputs!.expectedState },
                  ],
                },
              }]],
            },
            {
              name: "check-config-map",
              inputs: {
                parameters: [
                  { name: "name" },
                  { name: "expectedState" },
                ],
              },
              resource: {
                action: "get",
                manifest: `apiVersion: v1
kind: ConfigMap
metadata:
  name: "{{inputs.parameters.name}}"
`,
                successCondition: spec.argoExpression,
              },
            },
          ],
        },
      };

      const result = await submitAndWait(workflow);
      expect(result.phase).toBe(spec.expectedPhase);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - expr.concat", () => {
    const builderVariant: BuilderVariant = {
      name: "expr.concat",
      code: `expr.concat(expr.literal("data.state == "), ctx.inputs.expectedState)`,
    };

    test("builder renders equivalent successCondition behavior", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: "resource-condition-success" })
        .addTemplate("check", t => t
          .addRequiredInput("name", typeToken<string>())
          .addRequiredInput("expectedState", typeToken<string>())
          .addResourceTask(b => b.setDefinition({
            action: "get",
            manifest: {
              apiVersion: "v1",
              kind: "ConfigMap",
              metadata: {
                name: b.inputs.name,
              },
            },
            successCondition: expr.concat(
              expr.literal("data.state == "),
              b.inputs.expectedState,
            ),
          }))
        )
        .setEntrypoint("check")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, {
        name: configMapName,
        expectedState: spec.inputs!.expectedState,
      });

      expect(result.phase).toBe(spec.expectedPhase);
      reportParityResult(spec, builderVariant, result);
    });
  });
});

describe("Resource Conditions - failureCondition expression", () => {
  const spec: ParitySpec = {
    category: "Resource Conditions",
    name: "failureCondition expression",
    inputs: { blockedState: "blocked" },
    argoExpression: `{{="data.state == " + inputs.parameters.blockedState}}`,
    expectedPhase: "Failed",
  };
  const namespace = getTestNamespace();
  const configMapName = `parity-failure-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  let coreApi: CoreV1Api;

  beforeAll(async () => {
    const kc = new KubeConfig();
    kc.loadFromString(getKubeConfig());
    coreApi = kc.makeApiClient(CoreV1Api);

    await coreApi.createNamespacedConfigMap({
      namespace,
      body: {
        metadata: {
          name: "artifact-repositories",
          annotations: { "workflows.argoproj.io/default-artifact-repository": "empty" },
        },
        data: { empty: "" },
      },
    }).catch(() => {
      /* may already exist */
    });

    await coreApi.createNamespacedConfigMap({
      namespace,
      body: {
        metadata: { name: configMapName },
        data: { state: "blocked" },
      },
    });
  });

  afterAll(async () => {
    await coreApi.deleteNamespacedConfigMap({ name: configMapName, namespace }).catch(() => {
      /* cleanup best-effort */
    });
  });

  describe("ArgoYaml", () => {
    test("raw resource condition expression fails", async () => {
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "parity-resource-failure-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: getServiceAccountName(),
          templates: [
            {
              name: "main",
              steps: [[{
                name: "check",
                template: "check-config-map",
                arguments: {
                  parameters: [
                    { name: "name", value: configMapName },
                    { name: "blockedState", value: spec.inputs!.blockedState },
                  ],
                },
              }]],
            },
            {
              name: "check-config-map",
              inputs: {
                parameters: [
                  { name: "name" },
                  { name: "blockedState" },
                ],
              },
              resource: {
                action: "get",
                manifest: `apiVersion: v1
kind: ConfigMap
metadata:
  name: "{{inputs.parameters.name}}"
`,
                failureCondition: spec.argoExpression,
              },
            },
          ],
        },
      };

      const result = await submitAndWait(workflow);
      expect(result.phase).toBe(spec.expectedPhase);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - expr.concat", () => {
    const builderVariant: BuilderVariant = {
      name: "expr.concat",
      code: `expr.concat(expr.literal("data.state == "), ctx.inputs.blockedState)`,
    };

    test("builder renders equivalent failureCondition behavior", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: "resource-condition-failure" })
        .addTemplate("check", t => t
          .addRequiredInput("name", typeToken<string>())
          .addRequiredInput("blockedState", typeToken<string>())
          .addResourceTask(b => b.setDefinition({
            action: "get",
            manifest: {
              apiVersion: "v1",
              kind: "ConfigMap",
              metadata: {
                name: b.inputs.name,
              },
            },
            failureCondition: expr.concat(
              expr.literal("data.state == "),
              b.inputs.blockedState,
            ),
          }))
        )
        .setEntrypoint("check")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, {
        name: configMapName,
        blockedState: spec.inputs!.blockedState,
      });

      expect(result.phase).toBe(spec.expectedPhase);
      reportParityResult(spec, builderVariant, result);
    });
  });
});
