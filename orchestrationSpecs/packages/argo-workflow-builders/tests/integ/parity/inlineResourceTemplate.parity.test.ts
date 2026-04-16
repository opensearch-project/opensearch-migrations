import { INLINE, renderWorkflowTemplate, typeToken, WorkflowBuilder } from "../../../src/index.js";
import { getKubeConfig, getServiceAccountName, getTestNamespace } from "../infra/argoCluster.js";
import { BuilderVariant, ParitySpec, reportContractResult, reportParityResult } from "../infra/parityHelper.js";
import { submitRenderedWorkflow } from "../infra/probeHelper.js";
import { submitAndWait } from "../infra/workflowRunner.js";
import { CoreV1Api, KubeConfig } from "@kubernetes/client-node";

describe("Inline Resource Template - patch ConfigMap", () => {
  const spec: ParitySpec = {
    category: "Inline Resource Template",
    name: "patch ConfigMap",
    inputs: { configMapName: "" },
    argoExpression: "inline resource patch",
    expectedPhase: "Succeeded",
  };
  const namespace = getTestNamespace();
  const workflowName = `inline-resource-patch-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const configMapName = `parity-inline-patch-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
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
        data: { state: "before" },
      },
    });
  });

  afterAll(async () => {
    await coreApi.deleteNamespacedConfigMap({ name: configMapName, namespace }).catch(() => {
      /* cleanup best-effort */
    });
  });

  describe("ArgoYaml", () => {
    test("raw inline resource patch succeeds", async () => {
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "parity-inline-resource-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: getServiceAccountName(),
          templates: [
            {
              name: "main",
              steps: [[{
                name: "patch",
                inline: {
                  inputs: {
                    parameters: [{ name: "name" }],
                  },
                  resource: {
                    action: "patch",
                    flags: ["--type", "merge"],
                    manifest: `apiVersion: v1
kind: ConfigMap
metadata:
  name: "{{inputs.parameters.name}}"
data:
  state: "after"
`,
                  },
                },
                arguments: {
                  parameters: [{ name: "name", value: configMapName }],
                },
              }]],
            },
          ],
        },
      };

      const result = await submitAndWait(workflow);
      expect(result.phase).toBe(spec.expectedPhase);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - INLINE", () => {
    const builderVariant: BuilderVariant = {
      name: "INLINE",
      code: "addStep(..., INLINE, b => b.addResourceTask(...))",
    };

    test("builder renders equivalent inline resource patch behavior", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: "inline-resource-patch" })
        .addTemplate("main", t => t
          .addSteps(sb => sb
            .addStep("patch", INLINE, b => b
                .addRequiredInput("name", typeToken<string>())
                .addResourceTask(rb => rb.setDefinition({
                  action: "patch",
                  flags: ["--type", "merge"],
                  manifest: {
                    apiVersion: "v1",
                    kind: "ConfigMap",
                    metadata: {
                      name: rb.inputs.name,
                    },
                    data: {
                      state: "after",
                    },
                  },
                })),
              ctx => ctx.register({ name: configMapName }),
            ),
          ),
        )
        .setEntrypoint("main")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      rendered.metadata.name = workflowName;
      const result = await submitRenderedWorkflow(rendered);
      expect(result.phase).toBe(spec.expectedPhase);
      reportParityResult(spec, builderVariant, result);
    });
  });
});
