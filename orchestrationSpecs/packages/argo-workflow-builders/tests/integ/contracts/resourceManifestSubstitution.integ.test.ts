import { getKubeConfig, getServiceAccountName, getTestNamespace } from "../infra/argoCluster";
import { getK8sClient } from "../infra/k8sClient";
import { submitAndWait, WorkflowResult } from "../infra/workflowRunner";

function workflowMessages(result: WorkflowResult): string {
  return [
    result.message,
    ...Object.values(result.nodeOutputs).map(node => node.message),
  ]
    .filter((message): message is string => typeof message === "string")
    .join("\n");
}

describe("resource manifest parameter substitution", () => {
  const namespace = getTestNamespace();
  const runId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const podExpressionPodName = `rm-pod-expr-pod-${runId}`;
  const kafkaExpressionPodName = `rm-kafka-expr-pod-${runId}`;
  const literalExpressionPodName = `rm-literal-expr-pod-${runId}`;
  let coreApi: any;

  beforeAll(async () => {
    const k8s = await getK8sClient();
    const kc = new k8s.KubeConfig();
    kc.loadFromString(getKubeConfig());
    coreApi = kc.makeApiClient(k8s.CoreV1Api);

    // Resource templates load the default artifact repository even when they do
    // not use artifacts. The Testcontainers Argo quick-start manifest points at
    // a plugin named "test", so resource-template tests need the empty driver.
    await coreApi.createNamespacedConfigMap({
      namespace,
      body: {
        metadata: {
          name: "artifact-repositories",
          annotations: { "workflows.argoproj.io/default-artifact-repository": "empty" },
        },
        data: { empty: "" },
      },
    }).catch(() => { /* may already exist */ });
  });

  afterAll(async () => {
    if (coreApi) {
      await Promise.allSettled([
        coreApi.deleteNamespacedPod({ name: podExpressionPodName, namespace }),
        coreApi.deleteNamespacedPod({ name: kafkaExpressionPodName, namespace }),
        coreApi.deleteNamespacedPod({ name: literalExpressionPodName, namespace }),
      ]);
    }
  });

  test("evaluates a Pod-shaped nested expression in a Pod manifest", async () => {
    const clusterConfig = JSON.stringify({
      clusterSpecOverrides: {
        podAffinity: {
          podAntiAffinity: {
            preferredDuringSchedulingIgnoredDuringExecution: [{
              weight: 1,
              podAffinityTerm: {
                labelSelector: {
                  matchExpressions: [{
                    key: "app",
                    operator: "In",
                    values: ["strimzi"],
                  }],
                },
                topologyKey: "kubernetes.io/hostname",
              },
            }],
          },
        },
      },
    });

    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: { generateName: "rm-pod-expr-pod-", namespace },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: getServiceAccountName(),
        templates: [
          {
            name: "main",
            steps: [[{
              name: "apply",
              template: "apply-pod",
              arguments: {
                parameters: [
                  { name: "name", value: podExpressionPodName },
                  { name: "clusterConfig", value: clusterConfig },
                ],
              },
            }]],
          },
          {
            name: "apply-pod",
            inputs: {
              parameters: [
                { name: "name" },
                { name: "clusterConfig" },
              ],
            },
            resource: {
              action: "apply",
              manifest: `apiVersion: v1
kind: Pod
metadata:
  name: "{{inputs.parameters.name}}"
spec:
  restartPolicy: Never
  containers:
    - name: main
      image: busybox
      command: ["sh", "-c", "echo ok"]
  affinity: {{=toJson(sprig.merge(sprig.dict("nodeAffinity", sprig.dict("requiredDuringSchedulingIgnoredDuringExecution", sprig.dict("nodeSelectorTerms", [sprig.dict("matchExpressions", [sprig.dict("key", "kubernetes.io/os", "operator", "In", "values", ["linux"])])]))), sprig.dig('clusterSpecOverrides', 'podAffinity', {}, fromJSON(inputs.parameters.clusterConfig))))}}
`,
            },
          },
        ],
      },
    };

    const result = await submitAndWait(workflow);

    expect(result.phase).toBe("Succeeded");
  });

  test("fails to parse a Pod-shaped nested object literal expression in a Pod manifest", async () => {
    const clusterConfig = JSON.stringify({
      clusterSpecOverrides: {
        podAffinity: {
          podAntiAffinity: {
            preferredDuringSchedulingIgnoredDuringExecution: [{
              weight: 1,
              podAffinityTerm: {
                labelSelector: {
                  matchExpressions: [{
                    key: "app",
                    operator: "In",
                    values: ["literal"],
                  }],
                },
                topologyKey: "kubernetes.io/hostname",
              },
            }],
          },
        },
      },
    });

    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: { generateName: "rm-literal-expr-pod-", namespace },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: getServiceAccountName(),
        templates: [
          {
            name: "main",
            steps: [[{
              name: "apply",
              template: "apply-pod",
              arguments: {
                parameters: [
                  { name: "name", value: literalExpressionPodName },
                  { name: "clusterConfig", value: clusterConfig },
                ],
              },
            }]],
          },
          {
            name: "apply-pod",
            inputs: {
              parameters: [
                { name: "name" },
                { name: "clusterConfig" },
              ],
            },
            resource: {
              action: "apply",
              manifest: `apiVersion: v1
kind: Pod
metadata:
  name: "{{inputs.parameters.name}}"
spec:
  restartPolicy: Never
  containers:
    - name: main
      image: busybox
      command: ["sh", "-c", "echo ok"]
  affinity: {{=toJson(sprig.merge({"nodeAffinity":{"requiredDuringSchedulingIgnoredDuringExecution":{"nodeSelectorTerms":[{"matchExpressions":[{"key":"kubernetes.io/os","operator":"In","values":["linux"]}]}]}}}, sprig.dig('clusterSpecOverrides', 'podAffinity', {}, fromJSON(inputs.parameters.clusterConfig))))}}
`,
            },
          },
        ],
      },
    };

    const result = await submitAndWait(workflow);

    expect(result.phase).toBe("Failed");
    expect(workflowMessages(result)).toMatch(/error parsing \/tmp\/manifest\.yaml|failed to evaluate expression/);
  });

  test("evaluates a Kafka-lookup expression that still produces a Pod-shaped nested object", async () => {
    const clusterConfig = JSON.stringify({
      clusterSpecOverrides: {
        kafka: {
          podAntiAffinity: {
            preferredDuringSchedulingIgnoredDuringExecution: [{
              weight: 1,
              podAffinityTerm: {
                labelSelector: {
                  matchExpressions: [{
                    key: "app",
                    operator: "In",
                    values: ["kafka"],
                  }],
                },
                topologyKey: "kubernetes.io/hostname",
              },
            }],
          },
        },
      },
    });

    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: { generateName: "rm-kafka-expr-pod-", namespace },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: getServiceAccountName(),
        templates: [
          {
            name: "main",
            steps: [[{
              name: "apply",
              template: "apply-pod",
              arguments: {
                parameters: [
                  { name: "name", value: kafkaExpressionPodName },
                  { name: "clusterConfig", value: clusterConfig },
                ],
              },
            }]],
          },
          {
            name: "apply-pod",
            inputs: {
              parameters: [
                { name: "name" },
                { name: "clusterConfig" },
              ],
            },
            resource: {
              action: "apply",
              manifest: `apiVersion: v1
kind: Pod
metadata:
  name: "{{inputs.parameters.name}}"
spec:
  restartPolicy: Never
  containers:
    - name: main
      image: busybox
      command: ["sh", "-c", "echo ok"]
  affinity: {{=toJson(sprig.merge(sprig.dict("nodeAffinity", sprig.dict("requiredDuringSchedulingIgnoredDuringExecution", sprig.dict("nodeSelectorTerms", [sprig.dict("matchExpressions", [sprig.dict("key", "kubernetes.io/os", "operator", "In", "values", ["linux"])])]))), sprig.dig('clusterSpecOverrides', 'kafka', {}, fromJSON(inputs.parameters.clusterConfig))))}}
`,
            },
          },
        ],
      },
    };

    const result = await submitAndWait(workflow);

    expect(result.phase).toBe("Succeeded");
  });
});
