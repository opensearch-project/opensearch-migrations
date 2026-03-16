import { submitAndWait } from "../infra/workflowRunner";
import { getServiceAccountName, getTestNamespace } from "../infra/argoCluster";

const DEPS_JSON = JSON.stringify([
  { source: "s1", snapshot: "a" },
  { source: "s2", snapshot: "b" },
]);

const TRAFFIC_REPLAYS_JSON = JSON.stringify([
  {
    fromProxy: "p1",
    kafkaClusterName: "k1",
    kafkaConfig: { label: "kafka", kafkaConnection: "a:9092", kafkaTopic: "t1", enableMSKAuth: false },
    toTarget: { label: "target", endpoint: "https://x:9200", allowInsecure: true },
    dependsOnSnapshotMigrations: [
      { source: "s1", snapshot: "a" },
      { source: "s2", snapshot: "b" },
    ],
  },
]);

function loopTemplate(name: string) {
  return {
    name,
    inputs: { parameters: [{ name: "deps" }] },
    steps: [
      [
        {
          name: "iter",
          template: "sink",
          arguments: {
            parameters: [
              { name: "value", value: "{{=item['source']+'-'+item['snapshot']}}" },
            ],
          },
          withParam: "{{inputs.parameters.deps}}",
        },
      ],
    ],
  };
}

function sinkTemplate() {
  return {
    name: "sink",
    inputs: { parameters: [{ name: "value" }] },
    suspend: { duration: "0" },
  };
}

describe("withParam boundary evaluation behavior", () => {
  test("control: direct JSON-list parameter works", async () => {
    const wf = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: { generateName: "withparam-boundary-direct-", namespace: getTestNamespace() },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: getServiceAccountName(),
        arguments: { parameters: [{ name: "deps", value: DEPS_JSON }] },
        templates: [
          sinkTemplate(),
          {
            name: "main",
            steps: [[{ name: "loop", template: "loop", arguments: { parameters: [{ name: "deps", value: "{{workflow.parameters.deps}}" }] } }]],
          },
          loopTemplate("loop"),
        ],
      },
    };

    const result = await submitAndWait(wf as any);
    expect(result.phase).toBe("Succeeded");
  });

  test("control: one-hop pass-through of JSON-list parameter works", async () => {
    const wf = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: { generateName: "withparam-boundary-onehop-", namespace: getTestNamespace() },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: getServiceAccountName(),
        arguments: { parameters: [{ name: "deps", value: DEPS_JSON }] },
        templates: [
          sinkTemplate(),
          loopTemplate("loop"),
          {
            name: "pass",
            inputs: { parameters: [{ name: "deps" }] },
            steps: [[{ name: "call-loop", template: "loop", arguments: { parameters: [{ name: "deps", value: "{{inputs.parameters.deps}}" }] } }]],
          },
          {
            name: "main",
            steps: [[{ name: "pass", template: "pass", arguments: { parameters: [{ name: "deps", value: "{{workflow.parameters.deps}}" }] } }]],
          },
        ],
      },
    };

    const result = await submitAndWait(wf as any);
    expect(result.phase).toBe("Succeeded");
  });

  test("explore: parent withParam-item toJSON(dict(...)) handoff to replay loop", async () => {
    const wf = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: { generateName: "withparam-boundary-exprcfg-", namespace: getTestNamespace() },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: getServiceAccountName(),
        arguments: { parameters: [{ name: "trafficReplays", value: TRAFFIC_REPLAYS_JSON }] },
        templates: [
          sinkTemplate(),
          {
            name: "loop-replay",
            inputs: { parameters: [{ name: "replayConfig" }] },
            steps: [
              [
                {
                  name: "iter",
                  template: "sink",
                  arguments: {
                    parameters: [
                      { name: "value", value: "{{=item['source']+'-'+item['snapshot']}}" },
                    ],
                  },
                  withParam: "{{=fromJSON(inputs.parameters.replayConfig)['dependsOnSnapshotMigrations']}}",
                },
              ],
            ],
          },
          {
            name: "main",
            steps: [
              [
                {
                  name: "outer",
                  template: "outer-loop",
                },
              ],
            ],
          },
          {
            name: "outer-loop",
            steps: [
              [
                {
                  name: "call",
                  template: "loop-replay",
                  arguments: {
                    parameters: [
                      {
                        name: "replayConfig",
                        // Mirror fullMigration-style item normalization across a loop boundary.
                        value: "{{=toJSON(sprig.dict(\"fromProxy\", item['fromProxy'], \"kafkaClusterName\", item['kafkaClusterName'], \"kafkaConfig\", fromJSON(item['kafkaConfig']), \"toTarget\", fromJSON(item['toTarget']), \"dependsOnSnapshotMigrations\", fromJSON(toJSON(item))['dependsOnSnapshotMigrations']))}}",
                      },
                    ],
                  },
                  withParam: "{{workflow.parameters.trafficReplays}}",
                },
              ],
            ],
          },
        ],
      },
    };

    const result = await submitAndWait(wf as any);
    // Document actual cluster behavior for this exact composition.
    expect(result.phase).toMatch(/Failed|Error/);
    const nodeMessages = Object.values(result.raw?.status?.nodes || {})
      .map((n: any) => String(n?.message || ""))
      .join("\n");
    expect(nodeMessages).toContain("withParam value could not be parsed as a JSON list");
  });
});
