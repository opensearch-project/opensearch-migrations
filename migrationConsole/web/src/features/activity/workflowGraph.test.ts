import { expect, test } from "vitest";

import { manageSnapshot } from "../../test/fixtures";
import { buildWorkflowGraph } from "./workflowGraph";


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
