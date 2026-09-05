import { expect, test } from "vitest";

import {
  connectedPath,
  dependencyAnchorY,
  edgeId,
  groupDependencyRoutes,
} from "./workflowRouting";


test("places incoming and outgoing anchors on the left edge thirds", () => {
  expect(dependencyAnchorY(100, 60, "incoming")).toBe(120);
  expect(dependencyAnchorY(100, 60, "outgoing")).toBe(140);
});


test("routes a split through one shared source lane", () => {
  const routes = groupDependencyRoutes([
    {
      sourceId: "parent",
      targetId: "left",
      sourceY: 10,
      targetDepth: 2,
      targetY: 80,
    },
    {
      sourceId: "parent",
      targetId: "right",
      sourceY: 10,
      targetDepth: 2,
      targetY: 120,
    },
  ]);

  expect(routes).toEqual([{
    sourceId: "parent",
    targetIds: ["left", "right"],
    startY: 10,
    endY: 120,
    depth: 2,
  }]);
});


test("assigns a stable route column from dependency depth", () => {
  const routes = groupDependencyRoutes([
    {
      sourceId: "a",
      targetId: "a-child",
      sourceY: 10,
      targetDepth: 1,
      targetY: 100,
    },
    {
      sourceId: "b",
      targetId: "b-child",
      sourceY: 40,
      targetDepth: 2,
      targetY: 130,
    },
    {
      sourceId: "c",
      targetId: "c-child",
      sourceY: 140,
      targetDepth: 3,
      targetY: 180,
    },
  ]);

  expect(routes.map((route) => [route.sourceId, route.depth])).toEqual([
    ["a", 1],
    ["b", 2],
    ["c", 3],
  ]);
});


test("keeps different target depths on separate routes after a split", () => {
  const routes = groupDependencyRoutes([
    {
      sourceId: "root",
      targetId: "direct",
      sourceY: 10,
      targetDepth: 1,
      targetY: 80,
    },
    {
      sourceId: "root",
      targetId: "deep",
      sourceY: 10,
      targetDepth: 3,
      targetY: 180,
    },
  ]);

  expect(routes.map((route) => [route.depth, route.targetIds])).toEqual([
    [1, ["direct"]],
    [3, ["deep"]],
  ]);
});


test("finds the complete upstream and downstream path", () => {
  const edges = [
    { sourceId: "root", targetId: "middle" },
    { sourceId: "middle", targetId: "selected" },
    { sourceId: "selected", targetId: "child-a" },
    { sourceId: "selected", targetId: "child-b" },
    { sourceId: "unrelated", targetId: "other" },
  ];

  const path = connectedPath(edges, "selected");

  expect([...path.nodeIds]).toEqual([
    "selected",
    "middle",
    "root",
    "child-a",
    "child-b",
  ]);
  expect(path.edgeIds).toEqual(new Set(edges.slice(0, 4).map(edgeId)));
});
