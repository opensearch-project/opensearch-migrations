import { CoreV1Api, KubeConfig } from "@kubernetes/client-node";
import { getKubeConfig, getServiceAccountName, getTestNamespace } from "../infra/argoCluster";
import { submitAndWait } from "../infra/workflowRunner";

describe("resource template conditions", () => {
  const namespace = getTestNamespace();
  const runId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const successConfigMapName = `rc-success-${runId}`;
  const failureConfigMapName = `rc-failure-${runId}`;
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
  });

  afterAll(async () => {
    await Promise.allSettled([
      coreApi.deleteNamespacedConfigMap({ name: successConfigMapName, namespace }),
      coreApi.deleteNamespacedConfigMap({ name: failureConfigMapName, namespace }),
    ]);
  });

  test("successCondition accepts an Argo expression when it renders selector-compatible syntax", async () => {
    await coreApi.createNamespacedConfigMap({
      namespace,
      body: {
        metadata: { name: successConfigMapName },
        data: { state: "ready" },
      },
    });

    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: { generateName: "resource-success-condition-", namespace },
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
                  { name: "name", value: successConfigMapName },
                  { name: "expectedState", value: "ready" },
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
              successCondition: "{{=\"data.state == \" + inputs.parameters.expectedState}}",
            },
          },
        ],
      },
    };

    const result = await submitAndWait(workflow);

    expect(result.phase).toBe("Succeeded");
  });

  test("failureCondition accepts an Argo expression when it renders selector-compatible syntax", async () => {
    await coreApi.createNamespacedConfigMap({
      namespace,
      body: {
        metadata: { name: failureConfigMapName },
        data: { state: "blocked" },
      },
    });

    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: { generateName: "resource-failure-condition-", namespace },
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
                  { name: "name", value: failureConfigMapName },
                  { name: "blockedState", value: "blocked" },
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
              failureCondition: "{{=\"data.state == \" + inputs.parameters.blockedState}}",
            },
          },
        ],
      },
    };

    const result = await submitAndWait(workflow);

    expect(result.phase).toBe("Failed");
  });
});
