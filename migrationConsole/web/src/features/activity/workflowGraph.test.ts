import { expect, test } from "vitest";

import type { ManageSnapshot } from "../../api/client";
import { manageSnapshot } from "../../test/fixtures";
import { buildWorkflowGraph } from "./workflowGraph";


function dependencySnapshot(
  definitions: {
    id: string;
    requires?: string[];
  }[],
): ManageSnapshot {
  const snapshot = structuredClone(manageSnapshot);
  const section = snapshot.nodes["section:Sources"];
  const group = snapshot.nodes["group:Sources:Sources"];
  const template = snapshot.nodes["resource:captureproxies:capture"];
  const resources = definitions.map(({ id, requires = [] }) => ({
    ...template,
    id,
    revision: `${id}-revision`,
    parentId: group.id,
    childIds: [],
    label: id,
    resourceName: id,
    resourcePlural: "testresources",
    relationships: requires.map((targetId) => ({
      kind: "runtime-dependency" as const,
      direction: "requires" as const,
      targetId,
      targetName: targetId,
      targetPlural: "testresources",
      targetPhase: "Ready",
      targetStatus: "ok",
    })),
  }));
  section.childIds = [group.id];
  group.childIds = resources.map((resource) => resource.id);
  snapshot.rootIds = [section.id];
  snapshot.nodes = {
    [section.id]: section,
    [group.id]: group,
    ...Object.fromEntries(resources.map((resource) => [resource.id, resource])),
  };
  return snapshot;
}


test("builds the full graph from prerequisite roots to dependents", () => {
  const graph = buildWorkflowGraph(manageSnapshot);

  expect(graph.levels.map((level) => level.map((node) => node.label))).toEqual([
    ["capture"],
    ["replay"],
  ]);
  expect(graph.edges).toEqual([{
    sourceId: "resource:captureproxies:capture",
    targetId: "resource:trafficreplays:replay",
  }]);
  expect(graph.levels[1][0].steps).toEqual([
    expect.objectContaining({
      id: "workflow-step:resource:trafficreplays:replay:deploy",
      label: "Deploy replay",
      phase: "Running",
      status: "running",
      depth: 0,
    }),
  ]);
});


test("keeps dependency subtrees together instead of crossing parent links", () => {
  const graph = buildWorkflowGraph(dependencySnapshot([
    { id: "root-a" },
    { id: "root-b" },
    { id: "b-child", requires: ["root-b"] },
    { id: "a-child", requires: ["root-a"] },
    { id: "b-grandchild", requires: ["b-child"] },
    { id: "a-grandchild-2", requires: ["a-child"] },
    { id: "a-grandchild-1", requires: ["a-child"] },
  ]));

  expect(graph.nodes.map((node) => node.id)).toEqual([
    "root-a",
    "a-child",
    "a-grandchild-2",
    "a-grandchild-1",
    "root-b",
    "b-child",
    "b-grandchild",
  ]);
  expect(graph.levels.map((level) => level.map((node) => node.id))).toEqual([
    ["root-a", "root-b"],
    ["a-child", "b-child"],
    ["a-grandchild-2", "a-grandchild-1", "b-grandchild"],
  ]);
});


test("keeps joins after every parent while closing the active subtree", () => {
  const graph = buildWorkflowGraph(dependencySnapshot([
    { id: "root-a" },
    { id: "root-b" },
    { id: "a-child", requires: ["root-a"] },
    { id: "joined", requires: ["a-child", "root-b"] },
  ]));

  expect(graph.nodes.map((node) => node.id)).toEqual([
    "root-a",
    "a-child",
    "root-b",
    "joined",
  ]);
});


test("keeps disconnected resources as roots", () => {
  const snapshot = structuredClone(manageSnapshot);
  const source = snapshot.nodes["resource:captureproxies:capture"];
  snapshot.nodes["resource:sourceclusters:source"] = {
    ...source,
    id: "resource:sourceclusters:source",
    revision: "source-1",
    label: "source",
    resourcePlural: "sourceclusters",
    resourceName: "source",
    parentId: "group:Sources:Sources",
    relationships: [],
  };
  snapshot.nodes["group:Sources:Sources"].childIds = [
    "resource:sourceclusters:source",
  ];

  const graph = buildWorkflowGraph(snapshot);

  expect(graph.levels[0].map((node) => node.label)).toEqual([
    "source",
    "capture",
  ]);
  expect(graph.levels[1].map((node) => node.label)).toEqual(["replay"]);
});


test("omits source and target configuration nodes without workflow steps", () => {
  const snapshot = structuredClone(manageSnapshot);
  const template = snapshot.nodes["resource:captureproxies:capture"];
  const sourceId = "resource:sourceconfigs:source";
  const targetId = "resource:targetconfigs:target";
  snapshot.nodes[sourceId] = {
    ...template,
    id: sourceId,
    revision: "source-1",
    parentId: "group:Sources:Sources",
    childIds: [],
    label: "source",
    resourceName: "source",
    resourcePlural: "sourceconfigs",
    relationships: [],
  };
  snapshot.nodes[targetId] = {
    ...template,
    id: targetId,
    revision: "target-1",
    parentId: "group:Sources:Sources",
    childIds: [],
    label: "target",
    resourceName: "target",
    resourcePlural: "targetconfigs",
    relationships: [],
  };
  snapshot.nodes["group:Sources:Sources"].childIds = [sourceId, targetId];
  snapshot.nodes["resource:trafficreplays:replay"].relationships = [{
    kind: "runtime-dependency",
    direction: "requires",
    targetId: sourceId,
    targetName: "source",
    targetPlural: "sourceconfigs",
    targetPhase: "Ready",
    targetStatus: "ok",
  }];

  const graph = buildWorkflowGraph(snapshot);

  expect(graph.nodes.map((node) => node.id)).not.toContain(sourceId);
  expect(graph.nodes.map((node) => node.id)).not.toContain(targetId);
  expect(graph.edges).not.toContainEqual(expect.objectContaining({
    sourceId,
  }));
});


test("keeps source configuration nodes that own workflow steps", () => {
  const snapshot = structuredClone(manageSnapshot);
  const sourceId = "resource:sourceconfigs:source";
  const stepId = "workflow-step:resource:sourceconfigs:source:prepare";
  snapshot.nodes[sourceId] = {
    ...snapshot.nodes["resource:captureproxies:capture"],
    id: sourceId,
    revision: "source-1",
    parentId: "group:Sources:Sources",
    childIds: [stepId],
    label: "source",
    resourceName: "source",
    resourcePlural: "sourceconfigs",
    relationships: [],
  };
  snapshot.nodes[stepId] = {
    ...snapshot.nodes["workflow-step:resource:trafficreplays:replay:deploy"],
    id: stepId,
    revision: "prepare-1",
    parentId: sourceId,
    childIds: [],
    label: "Prepare source",
  };
  snapshot.nodes["group:Sources:Sources"].childIds = [sourceId];

  const graph = buildWorkflowGraph(snapshot);

  expect(graph.nodes.map((node) => node.id)).toContain(sourceId);
});


test("shows unresolved prerequisites instead of hiding broken edges", () => {
  const snapshot = structuredClone(manageSnapshot);
  const replay = snapshot.nodes["resource:trafficreplays:replay"];
  replay.relationships = [{
    kind: "runtime-dependency",
    direction: "requires",
    targetId: null,
    targetName: "missing-buffer",
    targetPlural: "capturedtraffics",
    targetPhase: null,
    targetStatus: "unknown",
  }];

  const graph = buildWorkflowGraph(snapshot);

  expect(graph.levels[0].map((node) => node.label)).toEqual([
    "missing-buffer",
    "capture",
  ]);
  expect(graph.levels[0][0].unresolved).toBe(true);
  expect(graph.edges).toContainEqual({
    sourceId: "unresolved:capturedtraffics:missing-buffer",
    targetId: replay.id,
  });
});


test("omits dependencies whose targets are known configuration definitions", () => {
  const snapshot = structuredClone(manageSnapshot);
  const snapshotResource = snapshot.nodes["resource:captureproxies:capture"];
  const definitionId = "definition:edit:source.snapshotInfo.repos.repo";
  snapshot.nodes[definitionId] = {
    ...snapshot.nodes["group:Sources:Sources"],
    id: definitionId,
    revision: "repo-1",
    kind: "config-definition",
    parentId: "group:Sources:Sources",
    childIds: [],
    label: "repo",
    resourceType: "Snapshot repository",
  };
  snapshotResource.relationships = [{
    kind: "runtime-dependency",
    direction: "requires",
    targetId: definitionId,
    targetName: "repo",
    targetPlural: null,
    targetPhase: null,
    targetStatus: "unknown",
  }];

  const graph = buildWorkflowGraph(snapshot);

  expect(graph.nodes.map((node) => node.label)).not.toContain("repo");
  expect(graph.edges).not.toContainEqual(expect.objectContaining({
    sourceId: definitionId,
  }));
});
