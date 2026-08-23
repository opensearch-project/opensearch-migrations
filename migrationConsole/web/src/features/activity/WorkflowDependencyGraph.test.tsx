import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test } from "vitest";

import { manageSnapshot } from "../../test/fixtures";
import { WorkflowDependencyGraph } from "./WorkflowDependencyGraph";


test("dims unrelated paths only while a graph node is hovered", async () => {
  const snapshot = structuredClone(manageSnapshot);
  const capture = snapshot.nodes["resource:captureproxies:capture"];
  const sourceId = "resource:sourceconfigs:source";
  snapshot.nodes[sourceId] = {
    ...capture,
    id: sourceId,
    revision: "source-1",
    label: "source",
    parentId: "group:Sources:Sources",
    relationships: [],
    resourceName: "source",
    resourcePlural: "sourceconfigs",
  };
  snapshot.nodes["group:Sources:Sources"].childIds = [sourceId];
  const { container } = render(
    <WorkflowDependencyGraph
      approvals={[]}
      onReviewApproval={() => undefined}
      onSelectNode={() => undefined}
      operations={[]}
      selectedNodeId="resource:captureproxies:capture"
      snapshot={snapshot}
    />,
  );

  expect(container.querySelectorAll(".workflow-graph-node.path-muted"))
    .toHaveLength(0);

  await userEvent.hover(screen.getByRole("button", {
    name: "Open replay, Running",
  }));
  expect(screen.getByRole("button", {
    name: "Open source, Ready",
  }).closest(".workflow-graph-node")).toHaveClass("path-muted");

  await userEvent.unhover(screen.getByRole("button", {
    name: "Open replay, Running",
  }));
  expect(container.querySelectorAll(".workflow-graph-node.path-muted"))
    .toHaveLength(0);
});
