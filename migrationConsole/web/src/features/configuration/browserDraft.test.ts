import { describe, expect, it } from "vitest";

import type { ConfigurationDocument } from "../../api/client";
import type { EditNode, JsonSchema } from "@opensearch-migrations/config-edit-core";
import {
  acknowledgeSavedBrowserConfigDraft,
  applyBrowserEditOperation,
  createBrowserConfigDraft,
  markBrowserConfigDraftStale,
  replaceBrowserConfigYaml,
  revertedBrowserConfigDraft,
} from "./browserDraft";


const document: ConfigurationDocument = {
  modelVersion: "1",
  persistedRevision: "saved-1",
  rawYaml: `
sourceClusters:
  source:
    endpoint: https://source.example.com:9200
    version: ES 7.10
    allowInsecure: false
targetClusters:
  target:
    endpoint: https://target.example.com:9200
snapshotMigrationConfigs: []
`,
};


function findNode(nodes: EditNode[], id: string): EditNode | undefined {
  const pending = [...nodes];
  while (pending.length > 0) {
    const node = pending.pop()!;
    if (node.id === id) return node;
    pending.push(...(node.children ?? []));
  }
  return undefined;
}


const kafkaTopicSchema: JsonSchema = {
  type: "object",
  properties: {
    traffic: {
      type: "object",
      properties: {
        kafkaClusters: {
          type: "object",
          additionalProperties: {
            anyOf: [{
              type: "object",
              properties: {
                autoCreate: { type: "object" },
                topics: {
                  type: "object",
                  additionalProperties: {
                    type: "object",
                    properties: {
                      specOverrides: {
                        $ref: "#/$defs/StrimziKafkaTopicSpec",
                      },
                    },
                  },
                },
              },
            }],
          },
        },
      },
    },
  },
  $defs: {
    StrimziKafkaTopicSpec: {
      type: "object",
      properties: {
        partitions: {
          type: "integer",
          minimum: 1,
          default: 1,
          description: "Number of topic partitions.",
          "x-essential": true,
          "x-effective-default": {
            label: "1",
            value: 1,
            description: "New topics use one partition unless overridden.",
          },
        },
        replicas: {
          type: "integer",
          minimum: 1,
          default: 3,
          description: "Number of topic replicas.",
          "x-essential": true,
          "x-effective-default": {
            label: "3",
            value: 3,
            description: "New topics use three replicas unless overridden.",
          },
        },
        config: {
          type: "object",
          "x-expert": true,
        },
      },
    },
  },
};


describe("browser configuration drafts", () => {
  it("applies ordinary edits without a server-owned revision", () => {
    const draft = createBrowserConfigDraft(document);
    const updated = applyBrowserEditOperation(draft, {
      op: "set",
      path: ["sourceClusters", "source", "allowInsecure"],
      value: true,
    });

    expect(updated.dirty).toBe(true);
    expect(updated.draftRevision).toMatch(/^browser:saved-1:/);
    expect(updated.rawDocument).toContain("allowInsecure: true");
    expect(updated.editState.nodes).not.toBe(draft.editState.nodes);
  });

  it("keeps the cluster-enriched schema available for local topic edits", () => {
    const topicDocument: ConfigurationDocument = {
      modelVersion: "1",
      persistedRevision: "saved-topic",
      rawYaml: `
snapshotMigrationConfigs: []
traffic:
  kafkaClusters:
    kafka:
      autoCreate: {}
      topics:
        capture: {}
`,
    };
    const draft = createBrowserConfigDraft(
      topicDocument,
      undefined,
      kafkaTopicSchema,
    );
    const specOverrides = findNode(
      draft.editState.nodes,
      "edit:traffic.kafkaClusters.kafka.topics.capture.specOverrides",
    );

    expect(specOverrides).toMatchObject({
      valueKind: "object",
      presence: "optional",
    });
    expect(findNode(
      draft.editState.nodes,
      "edit:traffic.kafkaClusters.kafka.topics.capture.specOverrides.partitions",
    )).toMatchObject({
      valueKind: "scalar",
      valueType: "number",
      value: 1,
      valueDefaulted: true,
      expert: false,
      essential: true,
      validation: {
        minimum: 1,
        integer: true,
      },
    });
    expect(findNode(
      draft.editState.nodes,
      "edit:traffic.kafkaClusters.kafka.topics.capture.specOverrides.replicas",
    )).toMatchObject({
      valueKind: "scalar",
      valueType: "number",
      value: 3,
      valueDefaulted: true,
      expert: false,
      essential: true,
      validation: {
        minimum: 1,
        integer: true,
      },
    });
    expect(findNode(
      draft.editState.nodes,
      "edit:traffic.kafkaClusters.kafka.topics.capture.specOverrides.config",
    )).toMatchObject({
      expert: true,
    });

    const updated = applyBrowserEditOperation(draft, {
      op: "set",
      path: [
        "traffic",
        "kafkaClusters",
        "kafka",
        "topics",
        "capture",
        "specOverrides",
        "partitions",
      ],
      value: 3,
    });
    expect(updated.rawDocument).toContain("partitions: 3");
    expect(updated.unifiedSchema).toBe(kafkaTopicSchema);
  });

  it("keeps malformed YAML in repair mode and reverts it in memory", () => {
    const draft = createBrowserConfigDraft(document);
    const malformed = replaceBrowserConfigYaml(
      draft,
      "sourceClusters: [",
    );

    expect(malformed.rawYaml).toBe("sourceClusters: [");
    expect(malformed.editState.provenance.mode).toBe("raw");
    expect(malformed.editState.validation.valid).toBe(false);

    const reverted = revertedBrowserConfigDraft(malformed);
    expect(reverted.dirty).toBe(false);
    expect(reverted.rawYaml).toBeUndefined();
    expect(reverted.rawDocument).toBe(document.rawYaml);
  });

  it("retains dirty local work when its saved base changes remotely", () => {
    const draft = applyBrowserEditOperation(
      createBrowserConfigDraft(document),
      {
        op: "set",
        path: ["sourceClusters", "source", "allowInsecure"],
        value: true,
      },
    );

    const stale = markBrowserConfigDraftStale(draft, "saved-2");

    expect(stale.dirty).toBe(true);
    expect(stale.baseStale).toBe(true);
    expect(stale.persistedRevision).toBe("saved-1");
    expect(stale.remotePersistedRevision).toBe("saved-2");
    expect(stale.rawDocument).toContain("allowInsecure: true");
  });

  it("keeps edits made after a save snapshot was sent", () => {
    const savedSnapshot = applyBrowserEditOperation(
      createBrowserConfigDraft(document),
      {
        op: "set",
        path: ["sourceClusters", "source", "allowInsecure"],
        value: true,
      },
    );
    const current = applyBrowserEditOperation(savedSnapshot, {
      op: "set",
      path: ["sourceClusters", "source", "allowInsecure"],
      value: false,
    });

    const acknowledged = acknowledgeSavedBrowserConfigDraft({
      modelVersion: "1",
      persistedRevision: "saved-2",
      rawYaml: savedSnapshot.rawDocument,
    }, savedSnapshot, current);

    expect(acknowledged.persistedRevision).toBe("saved-2");
    expect(acknowledged.savedRawDocument).toContain("allowInsecure: true");
    expect(acknowledged.rawDocument).toContain("allowInsecure: false");
    expect(acknowledged.dirty).toBe(true);
  });
});
