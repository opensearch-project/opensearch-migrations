import { submitAndWait } from "../infra/workflowRunner";
import { getTestNamespace, getServiceAccountName } from "../infra/argoCluster";

type Variant = {
  name: string;
  cfgValueExpr: string;
  expectSucceeded: boolean;
  expectedShape: "mode" | "config";
};

const INPUT_JSON = JSON.stringify({
  config: {
    mode: "safe",
    nested: { value: 7 }
  },
  other: "x"
});

const variants: Variant[] = [
  {
    name: "toJSON(fromJSON(...)[\"config\"])",
    cfgValueExpr: "{{=toJSON(fromJSON(workflow.parameters.snapshotConfig)['config'])}}",
    expectSucceeded: true,
    expectedShape: "mode",
  },
  {
    name: "toJson(fromJSON(...).config)",
    cfgValueExpr: "{{=toJson(fromJSON(workflow.parameters.snapshotConfig).config)}}",
    expectSucceeded: true,
    expectedShape: "mode",
  },
  {
    name: "jsonpath(snapshotConfig, '$.config')",
    cfgValueExpr: "{{=jsonpath(workflow.parameters.snapshotConfig, '$.config')}}",
    expectSucceeded: true,
    expectedShape: "mode",
  },
  {
    name: "toJSON(fromJSON(jsonpath(...)))",
    cfgValueExpr: "{{=toJSON(fromJSON(jsonpath(workflow.parameters.snapshotConfig, '$.config')))}}",
    expectSucceeded: false,
    expectedShape: "mode",
  },
  {
    name: "toJSON(sprig.dict + fromJSON(root)['config'])",
    cfgValueExpr: "{{=toJSON(sprig.dict(\"config\", fromJSON(workflow.parameters.snapshotConfig)['config'], \"label\", fromJSON(workflow.parameters.snapshotConfig)['other']))}}",
    expectSucceeded: true,
    expectedShape: "config",
  },
  {
    name: "toJSON(sprig.dict + jsonpath(root,'$.config'))",
    cfgValueExpr: "{{=toJSON(sprig.dict(\"config\", jsonpath(workflow.parameters.snapshotConfig, '$.config'), \"label\", fromJSON(workflow.parameters.snapshotConfig)['other']))}}",
    expectSucceeded: true,
    expectedShape: "config",
  },
  {
    name: "toJSON(sprig.dict + fromJSON(jsonpath(...)))",
    cfgValueExpr: "{{=toJSON(sprig.dict(\"config\", fromJSON(jsonpath(workflow.parameters.snapshotConfig, '$.config')), \"label\", fromJSON(workflow.parameters.snapshotConfig)['other']))}}",
    expectSucceeded: false,
    expectedShape: "config",
  },
  {
    name: "fullMigration-style toJSON(sprig.dict(...))",
    cfgValueExpr: "{{=toJSON(sprig.dict(\"config\", sprig.dict(\"createSnapshotConfig\", fromJSON(workflow.parameters.snapshotConfig)['config']), \"repoConfig\", fromJSON(jsonpath(workflow.parameters.snapshotConfig, '$.config')), \"label\", fromJSON(workflow.parameters.snapshotConfig)['other']))}}",
    expectSucceeded: false,
    expectedShape: "config",
  },
];

describe("Step argument serialization contract", () => {
  test.each(variants)("$name", async ({ cfgValueExpr, expectSucceeded, expectedShape }) => {
    const namespace = getTestNamespace();

    const workflow = {
      apiVersion: "argoproj.io/v1alpha1",
      kind: "Workflow",
      metadata: {
        generateName: "arg-serialize-contract-",
        namespace,
      },
      spec: {
        entrypoint: "main",
        activeDeadlineSeconds: 30,
        serviceAccountName: getServiceAccountName(),
        arguments: {
          parameters: [
            { name: "snapshotConfig", value: INPUT_JSON },
          ],
        },
        templates: [
          {
            name: "receiver",
            inputs: {
              parameters: [{ name: "cfg" }],
            },
            steps: [[]],
            outputs: {
              parameters: [
                {
                  name: "mode",
                  valueFrom: {
                    expression: "fromJSON(inputs.parameters.cfg).mode",
                  },
                },
              ],
            },
          },
          {
            name: "main",
            steps: [
              [
                {
                  name: "send",
                  template: "receiver",
                  arguments: {
                    parameters: [
                      {
                        name: "cfg",
                        value: cfgValueExpr,
                      },
                    ],
                  },
                },
              ],
            ],
          },
        ],
      },
    };

    const result = await submitAndWait(workflow as any);

    const nodes = Object.values(result.raw.status?.nodes || {}) as any[];
    const receiverNode = nodes.find((n: any) => n.displayName === "send");
    const cfgInput = receiverNode?.inputs?.parameters?.find((p: any) => p.name === "cfg")?.value;

    if (expectSucceeded) {
      expect(result.phase).toBe("Succeeded");
      expect(typeof cfgInput).toBe("string");
      expect(cfgInput).not.toContain("{{=");
      const parsed = JSON.parse(cfgInput);
      if (expectedShape === "mode") {
        expect(parsed.mode).toBe("safe");
      } else {
        expect(parsed.config.mode).toBe("safe");
        expect(parsed.label).toBe("x");
      }
    } else {
      expect(result.phase).toMatch(/Failed|Error/);
      expect(cfgInput).toContain("{{=");
    }
  });
});
