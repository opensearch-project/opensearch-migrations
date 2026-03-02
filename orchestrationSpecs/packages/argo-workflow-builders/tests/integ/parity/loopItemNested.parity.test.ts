/**
 * Tests for the realistic fullMigration pattern:
 * - A JSON array of complex objects passed as a workflow parameter
 * - Loop over items, accessing nested fields
 * - Goal: find the cast-free builder pattern that matches the raw Argo YAML behavior
 */
import {
  INTERNAL,
  WorkflowBuilder,
  expr,
  makeParameterLoop,
  renderWorkflowTemplate,
  typeToken,
} from "../../../src/index.js";
import { getTestNamespace, getServiceAccountName } from "../infra/argoCluster.js";
import { submitRenderedWorkflow } from "../infra/probeHelper.js";
import { submitAndWait } from "../infra/workflowRunner.js";
import { BuilderVariant, ParitySpec, reportContractResult, reportParityResult } from "../infra/parityHelper.js";
import { LowercaseOnly } from "../../../src/models/workflowTypes.js";

function countLoopNodes(result: any, prefix = "loop-step(") {
  return Object.values(result.raw.status.nodes).filter(
    (n: any) => n.displayName && n.displayName.startsWith(prefix)
  );
}

function uniqueWorkflowName<const P extends string>(
  prefix: LowercaseOnly<P>
): LowercaseOnly<`${P}-${number}-${string}`> {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}` as LowercaseOnly<`${P}-${number}-${string}`>;
}

type KafkaConfig = { connection: string; topic: string };
type ProxyConfig = { name: string; kafkaConfig: KafkaConfig; listenPort: number };

const proxies: ProxyConfig[] = [
  { name: "proxy-a", kafkaConfig: { connection: "kafka-a:9092", topic: "topic-a" }, listenPort: 9200 },
  { name: "proxy-b", kafkaConfig: { connection: "kafka-b:9092", topic: "topic-b" }, listenPort: 9201 },
];

const spec: ParitySpec = {
  category: "Loop Item",
  name: "withParam over nested objects (fullMigration pattern)",
  argoExpression: "withParam over complex objects, accessing nested fields",
  inputs: { proxies: JSON.stringify(proxies) },
};

describe("Loop Item - withParam over nested objects (fullMigration pattern)", () => {

  describe("ArgoYaml", () => {
    test("raw: top-level via item.name; nested via fromJSON(item.kafkaConfig).connection", async () => {
      const namespace = getTestNamespace();
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "pli-param-nested-direct-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: getServiceAccountName(),
          arguments: { parameters: [{ name: "proxies", value: spec.inputs!.proxies }] },
          templates: [
            {
              name: "process-proxy",
              inputs: { parameters: [{ name: "name" }, { name: "connection" }] },
              suspend: { duration: "0" }
            },
            {
              name: "main",
              steps: [[{
                name: "loop-step",
                template: "process-proxy",
                arguments: { parameters: [
                  // top-level field: dot notation works in loop args
                  { name: "name",       value: "{{item.name}}" },
                  // nested field: item.kafkaConfig is a JSON string (nested objects are serialized)
                  // must use fromJSON to parse it before accessing fields
                  { name: "connection", value: "{{=fromJSON(item['kafkaConfig'])['connection']}}" },
                ]},
                withParam: "{{workflow.parameters.proxies}}",
              }]],
            },
          ],
        },
      };
      const result = await submitAndWait(workflow);
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      const connections = loopNodes
        .map((n: any) => n.inputs?.parameters?.find((p: any) => p.name === "connection")?.value)
        .sort();
      expect(connections).toEqual(["kafka-a:9092", "kafka-b:9092"]);
      reportContractResult(spec, result);
    });
  });

  describe("Builder - withParam over nested objects", () => {
    const builderVariant: BuilderVariant = {
      name: "withParam nested objects",
      code: [
        "// c.item is a parsed object at runtime (top-level fields accessible)",
        "// nested aggregate fields are serialized at loop boundary; deserializeRecord unwraps them",
        "name:       expr.get(c.item, 'name')",
        "connection: expr.get(expr.deserializeRecord(expr.get(c.item, 'kafkaConfig')), 'connection')",
      ].join("\n"),
    };

    test("builder: top-level via expr.get; nested via deserializeRecord(expr.get(c.item, nestedKey))", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: uniqueWorkflowName("pli-param-nested-builder") })
        .addTemplate("process-proxy", t => t
          .addRequiredInput("name",       typeToken<string>())
          .addRequiredInput("connection", typeToken<string>())
          .addSuspend(0)
        )
        .addTemplate("main", t => t
          .addRequiredInput("proxies", typeToken<ProxyConfig[]>())
          .addSteps(s => s.addStep(
            "loop-step",
            INTERNAL,
            "process-proxy",
            c => c.register({
              // top-level: expr.get works directly
              name:       expr.get(c.item, "name"),
              // nested aggregate field is serialized at runtime for withParam items
              connection: expr.get(
                expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                "connection"
              ),
            }),
            { loopWith: makeParameterLoop(expr.deserializeRecord(s.inputs.proxies)) }
          ))
        )
        .setEntrypoint("main")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, { proxies: JSON.stringify(proxies) });
      expect(result.phase).toBe("Succeeded");
      const loopNodes = countLoopNodes(result);
      const connections = loopNodes
        .map((n: any) => n.inputs?.parameters?.find((p: any) => p.name === "connection")?.value)
        .sort();
      expect(connections).toEqual(["kafka-a:9092", "kafka-b:9092"]);
      reportParityResult(spec, builderVariant, result);
    });
  });

});

const passThroughSpec: ParitySpec = {
  category: "Loop Item",
  name: "withParam nested field passed to downstream template",
  argoExpression: "pass item['kafkaConfig'] through templates and parse from input",
  inputs: { proxies: JSON.stringify(proxies) },
};

describe("Loop Item - withParam nested field passthrough", () => {
  describe("ArgoYaml", () => {
    test("raw: item['kafkaConfig'] is passed as JSON string and received as plain input string", async () => {
      const namespace = getTestNamespace();
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "pli-param-nested-pass-direct-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: getServiceAccountName(),
          arguments: { parameters: [{ name: "proxies", value: passThroughSpec.inputs!.proxies }] },
          templates: [
            {
              name: "consume-kafka-config",
              inputs: { parameters: [{ name: "kafkaConfig" }] },
              suspend: { duration: "0" },
            },
            {
              name: "forward-kafka-config",
              inputs: { parameters: [{ name: "kafkaConfig" }] },
              steps: [[{
                name: "forward",
                template: "consume-kafka-config",
                arguments: {
                  parameters: [
                    { name: "kafkaConfig", value: "{{inputs.parameters.kafkaConfig}}" },
                  ],
                },
              }]],
            },
            {
              name: "main",
              steps: [[{
                name: "loop-step",
                template: "forward-kafka-config",
                arguments: {
                  parameters: [
                    { name: "kafkaConfig", value: "{{=item['kafkaConfig']}}" },
                  ],
                },
                withParam: "{{workflow.parameters.proxies}}",
              }]],
            },
          ],
        },
      };
      const result = await submitAndWait(workflow);
      expect(result.phase).toBe("Succeeded");

      const consumedNodes = Object.values(result.raw.status.nodes).filter(
        (n: any) => n.templateName === "consume-kafka-config"
      ) as any[];

      const rows = consumedNodes
        .map((n: any) => {
          const p = n.inputs?.parameters ?? [];
          const kafkaConfig = p.find((x: any) => x.name === "kafkaConfig")?.value;
          return kafkaConfig;
        })
        .sort();

      expect(rows).toEqual([
        '{"connection":"kafka-a:9092","topic":"topic-a"}',
        '{"connection":"kafka-b:9092","topic":"topic-b"}',
      ]);
      reportContractResult(passThroughSpec, result);
    });
  });

  describe("Builder - nested field passthrough", () => {
    const builderVariant: BuilderVariant = {
      name: "withParam nested passthrough",
      code: [
        "Loop args: kafkaConfig: expr.get(c.item, 'kafkaConfig')",
        "Intermediate and downstream templates are typed as KafkaConfig; caller passes serialized boundary form without parse/type-hint expressions",
      ].join("\n"),
    };

    test("builder: nested field passes as JSON string across template boundary", async () => {
      const wf = WorkflowBuilder.create({ k8sResourceName: uniqueWorkflowName("pli-param-nested-pass-builder") })
        .addTemplate("consume-kafka-config", t => t
          .addRequiredInput("kafkaConfig", typeToken<KafkaConfig>())
          .addSuspend(0)
        )
        .addTemplate("forward-kafka-config", t => t
          .addRequiredInput("kafkaConfig", typeToken<KafkaConfig>())
          .addSteps(s => s.addStep(
            "forward",
            INTERNAL,
            "consume-kafka-config",
            b => b.register({
              kafkaConfig: s.inputs.kafkaConfig,
            })
          ))
        )
        .addTemplate("main", t => t
          .addRequiredInput("proxies", typeToken<ProxyConfig[]>())
          .addSteps(s => s.addStep(
            "loop-step",
            INTERNAL,
            "forward-kafka-config",
            c => c.register({
              kafkaConfig: expr.get(c.item, "kafkaConfig"),
            }),
            { loopWith: makeParameterLoop(expr.deserializeRecord(s.inputs.proxies)) }
          ))
        )
        .setEntrypoint("main")
        .getFullScope();

      const rendered = renderWorkflowTemplate(wf);
      const result = await submitRenderedWorkflow(rendered, { proxies: JSON.stringify(proxies) });
      expect(result.phase).toBe("Succeeded");

      const consumedNodes = Object.values(result.raw.status.nodes).filter(
        (n: any) => n.templateName === "consume-kafka-config"
      ) as any[];
      const rows = consumedNodes
        .map((n: any) => {
          const p = n.inputs?.parameters ?? [];
          const kafkaConfig = p.find((x: any) => x.name === "kafkaConfig")?.value;
          return kafkaConfig;
        })
        .sort();

      expect(rows).toEqual([
        '{"connection":"kafka-a:9092","topic":"topic-a"}',
        '{"connection":"kafka-b:9092","topic":"topic-b"}',
      ]);
      reportParityResult(passThroughSpec, builderVariant, result);
    });
  });
});

type DeepTransport = {
  brokers: { seed: string[] };
  security: { tls: { enabled: boolean; secret: { name: string } } };
};
type DeepRoute = { path: string; methods: string[] };
type DeepItem = { name: string; transport: DeepTransport; routes: DeepRoute[] };

const deepItems: DeepItem[] = [
  {
    name: "proxy-a",
    transport: {
      brokers: { seed: ["kafka-a:9092", "kafka-a:9093"] },
      security: { tls: { enabled: true, secret: { name: "tls-a" } } },
    },
    routes: [
      { path: "/ingest", methods: ["POST", "PUT"] },
      { path: "/status", methods: ["GET"] },
    ],
  },
  {
    name: "proxy-b",
    transport: {
      brokers: { seed: ["kafka-b:9092", "kafka-b:9093"] },
      security: { tls: { enabled: true, secret: { name: "tls-b" } } },
    },
    routes: [
      { path: "/ingest", methods: ["POST", "PATCH"] },
      { path: "/status", methods: ["GET"] },
    ],
  },
];

describe("Loop Item - withParam deep nested objects and arrays", () => {
  const deepSpec: ParitySpec = {
    category: "Loop Item",
    name: "withParam deep nested objects/arrays require parsing top-level aggregate fields",
    argoExpression: "fromJSON(item['transport']) and fromJSON(item['routes']) for deep access",
    inputs: { items: JSON.stringify(deepItems) },
  };

  describe("ArgoYaml", () => {
    test("raw: deep object/array fields resolve after fromJSON on top-level aggregate fields", async () => {
      const namespace = getTestNamespace();
      const workflow = {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "Workflow",
        metadata: { generateName: "pli-param-deep-nested-direct-", namespace },
        spec: {
          entrypoint: "main",
          activeDeadlineSeconds: 30,
          serviceAccountName: getServiceAccountName(),
          arguments: { parameters: [{ name: "items", value: deepSpec.inputs!.items }] },
          templates: [
            {
              name: "process-item",
              inputs: { parameters: [{ name: "name" }, { name: "firstBroker" }, { name: "tlsSecret" }, { name: "secondMethod" }] },
              suspend: { duration: "0" },
            },
            {
              name: "main",
              steps: [[{
                name: "loop-step",
                template: "process-item",
                arguments: {
                  parameters: [
                    { name: "name", value: "{{item.name}}" },
                    { name: "firstBroker", value: "{{=fromJSON(item['transport'])['brokers']['seed'][0]}}" },
                    { name: "tlsSecret", value: "{{=fromJSON(item['transport'])['security']['tls']['secret']['name']}}" },
                    { name: "secondMethod", value: "{{=fromJSON(item['routes'])[0]['methods'][1]}}" },
                  ],
                },
                withParam: "{{workflow.parameters.items}}",
              }]],
            },
          ],
        },
      };
      const result = await submitAndWait(workflow);
      expect(result.phase).toBe("Succeeded");

      const loopNodes = countLoopNodes(result);
      const rows = loopNodes
        .map((n: any) => {
          const p = n.inputs?.parameters ?? [];
          const get = (name: string) => p.find((x: any) => x.name === name)?.value;
          return [get("name"), get("firstBroker"), get("tlsSecret"), get("secondMethod")].join("|");
        })
        .sort();

      expect(rows).toEqual([
        "proxy-a|kafka-a:9092|tls-a|PUT",
        "proxy-b|kafka-b:9092|tls-b|PATCH",
      ]);
      reportContractResult(deepSpec, result);
    });
  });
});
