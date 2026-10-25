import { describe, expect, it } from "vitest";

import type { ManageNode, ManageSnapshot } from "../../api/client";
import { projectResourceView } from "./resourceView";


function node(
  id: string,
  kind: string,
  label: string,
  overrides: Partial<ManageNode> = {},
): ManageNode {
  return {
    id,
    revision: `${id}-1`,
    parentId: null,
    childIds: [],
    kind,
    label,
    description: null,
    status: "ok",
    phase: null,
    valueSummary: null,
    activityAt: null,
    diagnostics: [],
    capabilities: [],
    details: [],
    relationships: [],
    comparisons: [],
    resourcePlural: null,
    resourceName: null,
    resourceType: null,
    configPresence: {},
    configState: null,
    navigationKey: [],
    ...overrides,
  };
}


function topicTransition(): ManageSnapshot {
  const sectionId = "section:Live Traffic Migration";
  const bufferId = "group:Live Traffic Migration:Buffer";
  const clustersId = `${bufferId}:Kafka Clusters`;
  const oldClusterId = "resource:kafkaclusters:old";
  const newClusterId = "resource:kafkaclusters:new";
  const newTopicsId =
    "definition-group:edit:traffic.kafkaClusters.new.topics";
  const topicId = "resource:capturedtraffics:capture-topic";
  return {
    formatVersion: 1,
    revision: "snapshot-1",
    observedAt: "2026-09-14T00:00:00Z",
    namespace: "ma",
    workflowName: "migration-workflow",
    workflow: null,
    rootIds: [sectionId],
    nodes: {
      [sectionId]: node(sectionId, "section", "Live Traffic Migration", {
        childIds: [bufferId],
      }),
      [bufferId]: node(bufferId, "group", "Buffer", {
        parentId: sectionId,
        childIds: [clustersId],
      }),
      [clustersId]: node(clustersId, "group", "Kafka Clusters", {
        parentId: bufferId,
        childIds: [oldClusterId, newClusterId],
      }),
      [oldClusterId]: node(oldClusterId, "resource", "old", {
        parentId: clustersId,
        resourcePlural: "kafkaclusters",
        resourceName: "old",
        resourceType: "Kafka cluster",
        configPresence: {
          deployed: true,
          submitted: true,
          pending: false,
        },
      }),
      [newClusterId]: node(newClusterId, "resource", "new", {
        parentId: clustersId,
        childIds: [newTopicsId],
        resourcePlural: "kafkaclusters",
        resourceName: "new",
        resourceType: "Kafka cluster",
        configPresence: {
          deployed: false,
          submitted: false,
          pending: true,
        },
      }),
      [newTopicsId]: node(newTopicsId, "group", "Topics", {
        parentId: newClusterId,
        childIds: [topicId],
      }),
      [topicId]: node(topicId, "resource", "c", {
        parentId: newTopicsId,
        resourcePlural: "capturedtraffics",
        resourceName: "capture-topic",
        resourceType: "Kafka topic",
        configPresence: {
          deployed: true,
          submitted: true,
          pending: true,
        },
        comparisons: [
          {
            path: "kafkaClusterName",
            label: "kafkaClusterName",
            deployed: { present: true, value: "old", provenance: null },
            submitted: { present: true, value: "old", provenance: null },
            pending: { present: true, value: "new", provenance: null },
            submittedChanged: false,
            pendingChanged: true,
          },
          {
            path: "topicName",
            label: "topicName",
            deployed: { present: true, value: "t", provenance: null },
            submitted: { present: true, value: "t", provenance: null },
            pending: { present: true, value: "c", provenance: null },
            submittedChanged: false,
            pendingChanged: true,
          },
        ],
      }),
    },
    problems: [],
    stale: false,
    refreshError: null,
  };
}


describe("resource state navigation", () => {
  it("keeps the combined and saved views aligned with the edit hierarchy", () => {
    for (const mode of ["all", "pending"] as const) {
      const projected = projectResourceView(topicTransition(), mode);
      const topic = projected.nodes[
        "resource:capturedtraffics:capture-topic"
      ];
      expect(topic).toMatchObject({
        label: "c",
        parentId:
          "definition-group:edit:traffic.kafkaClusters.new.topics",
      });
    }
  });

  it("rehomes and relabels the topic for deployed and submitted views", () => {
    for (const mode of ["deployed", "submitted"] as const) {
      const projected = projectResourceView(topicTransition(), mode);
      const topic = projected.nodes[
        "resource:capturedtraffics:capture-topic"
      ];
      const groupId =
        "definition-group:edit:traffic.kafkaClusters.old.topics";
      expect(topic).toMatchObject({ label: "t", parentId: groupId });
      expect(projected.nodes[groupId]).toMatchObject({
        label: "Topics",
        parentId: "resource:kafkaclusters:old",
        childIds: ["resource:capturedtraffics:capture-topic"],
      });
      expect(projected.nodes[
        "definition-group:edit:traffic.kafkaClusters.new.topics"
      ]).toBeUndefined();
    }
  });

  it("uses the newest state where an orphaned topic is still present", () => {
    const snapshot = topicTransition();
    const topic = snapshot.nodes[
      "resource:capturedtraffics:capture-topic"
    ];
    topic.configPresence = {
      deployed: true,
      submitted: true,
      pending: false,
    };

    const projected = projectResourceView(snapshot, "all");

    expect(projected.nodes[topic.id]).toMatchObject({
      label: "t",
      parentId:
        "definition-group:edit:traffic.kafkaClusters.old.topics",
    });
  });
});
