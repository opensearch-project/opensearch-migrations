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


test("anchors dependencies to the stable resource header", () => {
  const { container } = render(
    <WorkflowDependencyGraph
      approvals={[]}
      onReviewApproval={() => undefined}
      onSelectNode={() => undefined}
      operations={[]}
      selectedNodeId={null}
      snapshot={structuredClone(manageSnapshot)}
    />,
  );

  const nodes = container.querySelectorAll(".workflow-graph-node");
  expect(nodes.length).toBeGreaterThan(0);
  nodes.forEach((node) => {
    expect(node).not.toHaveAttribute("data-dependency-anchor");
    expect(node.querySelector(":scope > .workflow-graph-node-main"))
      .toHaveAttribute("data-dependency-anchor", "true");
  });
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


test("shows the latest known activity timestamp on a resource", () => {
  const snapshot = structuredClone(manageSnapshot);
  Object.assign(snapshot.nodes["resource:captureproxies:capture"], {
    activityAt: "2026-08-23T22:25:00Z",
  });

  render(
    <WorkflowDependencyGraph
      approvals={[]}
      onReviewApproval={() => undefined}
      onSelectNode={() => undefined}
      operations={[]}
      selectedNodeId={null}
      snapshot={snapshot}
    />,
  );

  expect(screen.getByText(/Last activity/).closest("time")).toHaveAttribute(
    "datetime",
    "2026-08-23T22:25:00Z",
  );
});


test("discloses failed operation details on the affected resource", async () => {
  render(
    <WorkflowDependencyGraph
      approvals={[]}
      onReviewApproval={() => undefined}
      onSelectNode={() => undefined}
      operations={[{
        id: "reset-capture",
        kind: "reset",
        label: "Reset captureproxies/capture",
        status: "failed",
        targetIds: ["resource:captureproxies:capture"],
        createdAt: "2026-08-24T04:20:00Z",
        updatedAt: "2026-08-24T04:20:01Z",
        message: "Operation failed",
        detail: "captureproxies.migrations.opensearch.org capture was not found",
        result: {},
      }]}
      selectedNodeId="resource:captureproxies:capture"
      snapshot={manageSnapshot}
    />,
  );

  await userEvent.click(screen.getByText("Operation failed"));

  expect(screen.getByText("Reset captureproxies/capture"))
    .toBeInTheDocument();
  expect(screen.getByText(
    "captureproxies.migrations.opensearch.org capture was not found",
  )).toBeInTheDocument();
});


test("shows blocking approval actions without output links", async () => {
  const onReviewApproval = vi.fn();
  render(
    <WorkflowDependencyGraph
      approvals={[{
        disabledReason: null,
        editTargetId: null,
        immutable: false,
        immutableReason: null,
        label: "Approve metadata",
        nodeId: "resource:captureproxies:capture",
        nodeLabel: "capture",
        outputTargetId: "output:migration:metadataEvaluate",
        resetTargetId: null,
        resourcePresent: true,
        targetId: "approval:metadataEvaluate",
      }]}
      onReviewApproval={onReviewApproval}
      onSelectNode={() => undefined}
      operations={[]}
      selectedNodeId={null}
      snapshot={manageSnapshot}
    />,
  );

  expect(screen.queryByRole("button", { name: "View output" })).toBeNull();
  const review = screen.getByRole("button", { name: "Review approval" });
  expect(review).toBeInTheDocument();
  await userEvent.click(review);
  expect(onReviewApproval).toHaveBeenCalledWith("approval:metadataEvaluate");
});
