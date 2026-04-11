/**
 * Producer/Consumer workflow demonstrating dynamic successCondition.
 *
 * This mirrors the per-dependency checksum pattern:
 * - A "producer" creates a resource with multiple checksum-like fields
 * - A "consumer" waits on a specific field, chosen by parameter
 *
 * Workflow structure:
 *
 *   ┌──────────┐     ┌──────────┐
 *   │ producer  │     │ consumer │
 *   │ (create   │────▶│ (wait on │
 *   │  config)  │     │  field)  │
 *   └──────────┘     └──────────┘
 *
 * The consumer's successCondition is:
 *   data.<fieldName> == <expectedValue>
 * where fieldName and expectedValue are passed as parameters.
 */
import { CoreV1Api, KubeConfig } from "@kubernetes/client-node";
import { getKubeConfig, getServiceAccountName, getTestNamespace } from "../infra/argoCluster";
import { submitAndWait } from "../infra/workflowRunner";

describe("producer/consumer with dynamic successCondition", () => {
  const namespace = getTestNamespace();
  const runId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const configMapName = `checksum-test-${runId}`;
  let coreApi: CoreV1Api;

  beforeAll(async () => {
    const kc = new KubeConfig();
    kc.loadFromString(getKubeConfig());
    coreApi = kc.makeApiClient(CoreV1Api);
  });

  afterAll(async () => {
    await coreApi.deleteNamespacedConfigMap({ name: configMapName, namespace }).catch(() => {});
  });

  function makeWorkflow(checksumField: string, expectedValue: string, shouldSucceed: boolean) {
    return {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: { generateName: `dyn-condition-${shouldSucceed ? "ok" : "fail"}-`, namespace },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: shouldSucceed ? 30 : 10,
        serviceAccountName: getServiceAccountName(),
        templates: [
          {
            name: "main",
            dag: {
              tasks: [
                {
                  name: "producer",
                  template: "create-resource",
                  arguments: { parameters: [{ name: "configMapName", value: configMapName }] },
                },
                {
                  name: "consumer",
                  template: "wait-for-field",
                  dependencies: ["producer"],
                  arguments: {
                    parameters: [
                      { name: "configMapName", value: configMapName },
                      { name: "checksumField", value: checksumField },
                      { name: "expectedValue", value: expectedValue },
                    ],
                  },
                },
              ],
            },
          },
          {
            name: "create-resource",
            inputs: { parameters: [{ name: "configMapName" }] },
            resource: {
              action: "apply",
              manifest: `apiVersion: v1
kind: ConfigMap
metadata:
  name: "{{inputs.parameters.configMapName}}"
data:
  checksumForSnapshot: "abc123"
  checksumForReplayer: "def456"
  phase: "Ready"
`,
            },
          },
          {
            name: "wait-for-field",
            inputs: {
              parameters: [
                { name: "configMapName" },
                { name: "checksumField" },
                { name: "expectedValue" },
              ],
            },
            resource: {
              action: "get",
              manifest: `apiVersion: v1
kind: ConfigMap
metadata:
  name: "{{inputs.parameters.configMapName}}"
`,
              // This is the key pattern: dynamic field name in the successCondition
              successCondition:
                '{{="data." + inputs.parameters.checksumField + " == " + inputs.parameters.expectedValue}}',
            },
          },
        ],
      },
    };
  }

  test("consumer succeeds when checking the correct field with the correct value", async () => {
    const result = await submitAndWait(makeWorkflow("checksumForSnapshot", "abc123", true));
    expect(result.phase).toBe("Succeeded");
  });

  test("consumer succeeds when checking a different field with its correct value", async () => {
    const result = await submitAndWait(makeWorkflow("checksumForReplayer", "def456", true));
    expect(result.phase).toBe("Succeeded");
  });

  test("consumer times out when the expected value doesn't match", async () => {
    // Pre-create the ConfigMap so the workflow doesn't need to wait for the producer
    await coreApi.createNamespacedConfigMap({
      namespace,
      body: {
        metadata: { name: `${configMapName}-mismatch` },
        data: { checksumForSnapshot: "abc123", phase: "Ready" },
      },
    }).catch(() => {});

    const workflow = makeWorkflow("checksumForSnapshot", "WRONG", false);
    // Point at the pre-created ConfigMap
    const producerTask = workflow.spec.templates[0].dag.tasks.find((t: any) => t.name === "producer");
    producerTask!.arguments.parameters[0].value = `${configMapName}-mismatch`;
    const consumerTask = workflow.spec.templates[0].dag.tasks.find((t: any) => t.name === "consumer");
    consumerTask!.arguments.parameters[0].value = `${configMapName}-mismatch`;

    try {
      const result = await submitAndWait(workflow);
      // If it completes, it should have failed/errored (timeout)
      expect(result.phase).not.toBe("Succeeded");
    } catch (e: any) {
      // Timeout is expected
      expect(e.message).toMatch(/timed out/i);
    }

    await coreApi.deleteNamespacedConfigMap({ name: `${configMapName}-mismatch`, namespace }).catch(() => {});
  });
});
