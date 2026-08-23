import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test, vi } from "vitest";

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


test("shows active steps and discloses completed steps on their resource", async () => {
  const snapshot = structuredClone(manageSnapshot);
  const replayId = "resource:trafficreplays:replay";
  const completedId = `workflow-step:${replayId}:prepare`;
  const replay = snapshot.nodes[replayId];
  replay.childIds = [completedId, ...replay.childIds];
  snapshot.nodes[completedId] = {
    ...snapshot.nodes["workflow-step:resource:trafficreplays:replay:deploy"],
    id: completedId,
    revision: "prepare-1",
    parentId: replayId,
    label: "Prepare replay",
    status: "ok",
    phase: "Checked",
  };
  const onSelectNode = vi.fn();
  render(
    <WorkflowDependencyGraph
      approvals={[]}
      onReviewApproval={() => undefined}
      onSelectNode={onSelectNode}
      operations={[]}
      selectedNodeId={null}
      snapshot={snapshot}
    />,
  );

  expect(screen.getByRole("button", {
    name: "Open workflow step Deploy replay, Running",
  })).toBeInTheDocument();
  expect(screen.queryByRole("button", {
    name: "Open workflow step Prepare replay, Checked",
  })).toBeNull();

  await userEvent.click(screen.getByRole("button", {
    name: "Show 1 completed step",
  }));
  await userEvent.click(screen.getByRole("button", {
    name: "Open workflow step Prepare replay, Checked",
  }));

  expect(onSelectNode).toHaveBeenCalledWith(completedId);
});
