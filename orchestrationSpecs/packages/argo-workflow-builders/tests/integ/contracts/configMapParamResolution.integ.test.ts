import { submitAndWait } from "../infra/workflowRunner";
import { getTestNamespace, getServiceAccountName, getKubeConfig } from "../infra/argoCluster";
import { getK8sClient } from "../infra/k8sClient";

/**
 * Tests for Argo v4.0 ConfigMap parameter resolution and computed default
 * parameter expressions in when clauses.
 *
 * These cover two breaking changes in Argo v4.0:
 * 1. ConfigMaps used in valueFrom.configMapKeyRef must have the label
 *    workflows.argoproj.io/configmap-type: Parameter
 * 2. When clauses that use fromJSON() on parameters whose defaults are
 *    computed expressions fail because the expression is not evaluated
 *    before the when clause runs
 */
describe("ConfigMap valueFrom and computed default parameters", () => {
  const CONFIGMAP_NAME = "integ-test-approval-config";
  let coreApi: any;
  let namespace: string;

  beforeAll(async () => {
    const k8s = await getK8sClient();
    const kc = new k8s.KubeConfig();
    kc.loadFromString(getKubeConfig());
    coreApi = kc.makeApiClient(k8s.CoreV1Api);
    namespace = getTestNamespace();

    // Clean up any leftover configmap
    try {
      await coreApi.deleteNamespacedConfigMap({ name: CONFIGMAP_NAME, namespace });
    } catch { /* ignore if not found */ }

    // Create a labeled configmap for tests
    await coreApi.createNamespacedConfigMap({
      namespace,
      body: {
        metadata: {
          name: CONFIGMAP_NAME,
          labels: { "workflows.argoproj.io/configmap-type": "Parameter" },
        },
        data: { autoApprove: "{}" },
      },
    });
  });

  afterAll(async () => {
    try {
      await coreApi.deleteNamespacedConfigMap({ name: CONFIGMAP_NAME, namespace });
    } catch { /* ignore */ }
  });

  test("parameter with valueFrom.configMapKeyRef resolves from labeled configmap", async () => {
    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: { generateName: "cm-param-", namespace },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: getServiceAccountName(),
        templates: [{
          name: "main",
          inputs: {
            parameters: [{
              name: "mapParam",
              value: '{"fallback":true}',
              valueFrom: {
                configMapKeyRef: {
                  name: CONFIGMAP_NAME,
                  key: "autoApprove",
                  optional: true,
                },
              },
            }],
          },
          steps: [[]],
          outputs: {
            parameters: [{
              name: "result",
              valueFrom: { expression: "inputs.parameters.mapParam" },
            }],
          },
        }],
      },
    };

    const result = await submitAndWait(workflow);
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.result).toBe("{}");
  });

  test("parameter with valueFrom.configMapKeyRef errors when key missing in Argo v4", async () => {
    // In Argo v4.0, a missing key in an existing configmap causes an error
    // even with optional: true. This documents the behavior.
    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: { generateName: "cm-fallback-", namespace },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: getServiceAccountName(),
        templates: [{
          name: "main",
          inputs: {
            parameters: [{
              name: "mapParam",
              value: '{"fallback":true}',
              valueFrom: {
                configMapKeyRef: {
                  name: CONFIGMAP_NAME,
                  key: "nonexistentKey",
                  optional: true,
                },
              },
            }],
          },
          steps: [[]],
          outputs: {
            parameters: [{
              name: "result",
              valueFrom: { expression: "inputs.parameters.mapParam" },
            }],
          },
        }],
      },
    };

    const result = await submitAndWait(workflow);
    expect(result.phase).toMatch(/Error/);
  });

  test("sprig.dig on fromJSON of configmap param produces boolean usable in when via substitution", async () => {
    // This is the correct pattern for metadata-migration's skipApprovalMap:
    // 1. skipApprovalMap comes from configmap (value: "{}")
    // 2. skipApproval = sprig.dig(... false, fromJSON(skipApprovalMap))  -> "false"
    // 3. when clause checks: !({{skipApproval}})  (simple substitution, NOT fromJSON)
    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: { generateName: "cm-dig-when-", namespace },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: getServiceAccountName(),
        templates: [
          {
            name: "main",
            inputs: {
              parameters: [
                {
                  name: "approvalMap",
                  value: "{}",
                  valueFrom: {
                    configMapKeyRef: {
                      name: CONFIGMAP_NAME,
                      key: "autoApprove",
                      optional: true,
                    },
                  },
                },
                {
                  name: "skipApproval",
                  value: "{{=sprig.dig('key1', 'key2', false, fromJSON(inputs.parameters.approvalMap))}}",
                },
              ],
            },
            steps: [
              [{
                name: "conditional-step",
                template: "noop",
                when: "!({{inputs.parameters.skipApproval}})",
              }],
            ],
            outputs: {
              parameters: [{
                name: "skipValue",
                valueFrom: { expression: "inputs.parameters.skipApproval" },
              }],
            },
          },
          {
            name: "noop",
            steps: [[]],
          },
        ],
      },
    };

    const result = await submitAndWait(workflow);
    expect(result.phase).toBe("Succeeded");
    expect(result.globalOutputs.skipValue).toBe("false");
    expect(result.nodeOutputs["conditional-step"].phase).toBe("Succeeded");
  });

  test("when clause with fromJSON on computed default expression fails in Argo v4", async () => {
    // Documents the Argo v4.0 breaking change: fromJSON() in a when clause
    // on a parameter whose default is a computed expression fails because
    // the expression string is passed literally instead of being evaluated.
    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: { generateName: "cm-fromjson-when-", namespace },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: getServiceAccountName(),
        templates: [
          {
            name: "main",
            inputs: {
              parameters: [
                {
                  name: "approvalMap",
                  value: "{}",
                  valueFrom: {
                    configMapKeyRef: {
                      name: CONFIGMAP_NAME,
                      key: "autoApprove",
                      optional: true,
                    },
                  },
                },
                {
                  name: "skipApproval",
                  value: "{{=sprig.dig('key1', 'key2', false, fromJSON(inputs.parameters.approvalMap))}}",
                },
              ],
            },
            steps: [
              [{
                name: "conditional-step",
                template: "noop",
                // BAD PATTERN: fromJSON on a computed default — fails in Argo v4.0
                when: "{{=!(fromJSON(inputs.parameters.skipApproval))}}",
              }],
            ],
          },
          {
            name: "noop",
            steps: [[]],
          },
        ],
      },
    };

    const result = await submitAndWait(workflow);
    expect(result.phase).toMatch(/Failed|Error/);
  });
});
