import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, test } from "vitest";

import { getHealth } from "../api/client";
import { manageSnapshot } from "../test/fixtures";
import { server } from "../test/server";
import { App } from "./App";


function renderApp() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });
  const view = render(
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>,
  );
  return { ...view, client };
}


test("renders real manage state with exact-node details and capabilities", async () => {
  await expect(getHealth()).resolves.toEqual({
    status: "ok",
    apiVersion: "v1",
  });
  renderApp();

  expect(
    screen.getByRole("heading", { name: "Workflow Manage" }),
  ).toBeInTheDocument();
  expect(screen.getByText("Connecting to server")).toBeInTheDocument();
  expect(await screen.findByText("Server ready")).toBeInTheDocument();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  expect(
    within(tree).getByRole("treeitem", { name: /^capture, Ready$/ }),
  ).toBeInTheDocument();
  await userEvent.click(
    within(tree).getByRole("treeitem", { name: /^capture, Ready$/ }),
  );

  expect(
    screen.getByRole("heading", { name: "capture" }),
  ).toBeInTheDocument();
  expect(screen.getByText("Load balancer is unavailable in this cluster"))
    .toBeInTheDocument();
  expect(screen.getAllByRole("cell", { name: "LoadBalancer" })).toHaveLength(2);
  expect(screen.getByRole("cell", { name: "ClusterIP" }))
    .toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Edit capture" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Logs for capture" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Reset capture" })).toBeDisabled();
});


test("filters without destroying selection and preserves row focus across refresh", async () => {
  let response = manageSnapshot;
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(response)),
  );
  const { client } = renderApp();
  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const replay = within(tree).getByRole(
    "treeitem",
    { name: /^replay, Running$/ },
  );
  await userEvent.click(replay);
  replay.focus();
  const treeScroller = screen.getByTestId("tree-scroller");
  fireEvent.scroll(treeScroller, { target: { scrollTop: 37 } });

  const filter = screen.getByRole("searchbox", { name: "Filter resources" });
  await userEvent.type(filter, "replay");
  expect(
    within(tree).queryByRole("treeitem", { name: /^capture, Ready$/ }),
  ).toBeNull();
  expect(screen.getByRole("heading", { name: "replay" })).toBeInTheDocument();
  await userEvent.clear(filter);

  const replayBeforeRefresh = within(tree).getByRole(
    "treeitem",
    { name: /^replay, Running$/ },
  );
  replayBeforeRefresh.focus();
  response = {
    ...manageSnapshot,
    revision: "snapshot-2",
    nodes: {
      ...manageSnapshot.nodes,
      "resource:captureproxies:capture": {
        ...manageSnapshot.nodes["resource:captureproxies:capture"],
        revision: "capture-2",
        phase: "Failed",
        status: "error",
      },
    },
  };
  await client.invalidateQueries({ queryKey: ["manage-state"] });
  await screen.findByText("snapshot-2");

  expect(
    within(tree).getByRole("treeitem", { name: /^replay, Running$/ }),
  ).toBe(replayBeforeRefresh);
  expect(replayBeforeRefresh).toHaveFocus();
  expect(replayBeforeRefresh).toHaveAttribute("aria-selected", "true");
  expect(treeScroller.scrollTop).toBe(37);
});


test("supports coherent tree keyboard navigation and exact-node selection", async () => {
  renderApp();
  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const capture = within(tree).getByRole(
    "treeitem",
    { name: /^capture, Ready$/ },
  );
  capture.focus();

  await userEvent.keyboard("{ArrowDown}{ArrowDown}");
  const replay = within(tree).getByRole(
    "treeitem",
    { name: /^replay, Running$/ },
  );
  expect(replay).toHaveFocus();
  expect(capture).toHaveAttribute("aria-selected", "true");

  await userEvent.keyboard("{Enter}");
  expect(replay).toHaveAttribute("aria-selected", "true");
  expect(
    screen.getByRole("heading", { name: "replay" }),
  ).toBeInTheDocument();

  await userEvent.keyboard("{ArrowLeft}");
  expect(
    within(tree).getByRole("treeitem", { name: /^Replay, running$/ }),
  ).toHaveFocus();
});


test("marks only newly inserted rows without remounting existing rows", async () => {
  let response = manageSnapshot;
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(response)),
  );
  const { client } = renderApp();
  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const capture = within(tree).getByRole(
    "treeitem",
    { name: /^capture, Ready$/ },
  );
  const insertedId = "resource:captureproxies:capture-next";
  response = {
    ...manageSnapshot,
    revision: "snapshot-with-insertion",
    nodes: {
      ...manageSnapshot.nodes,
      "group:Live Traffic Migration:Capture": {
        ...manageSnapshot.nodes["group:Live Traffic Migration:Capture"],
        revision: "capture-group-2",
        childIds: [
          ...manageSnapshot.nodes["group:Live Traffic Migration:Capture"].childIds,
          insertedId,
        ],
      },
      [insertedId]: {
        ...manageSnapshot.nodes["resource:captureproxies:capture"],
        id: insertedId,
        revision: "capture-next-1",
        label: "capture-next",
        parentId: "group:Live Traffic Migration:Capture",
      },
    },
  };

  await client.invalidateQueries({ queryKey: ["manage-state"] });
  await screen.findByText("snapshot-with-insertion");
  const inserted = within(tree).getByRole(
    "treeitem",
    { name: /^capture-next, Ready$/ },
  );

  expect(inserted).toHaveClass("inserted");
  expect(
    within(tree).getByRole("treeitem", { name: /^capture, Ready$/ }),
  ).toBe(capture);
  expect(capture).not.toHaveClass("inserted");
});


test("shows stale and partial-observation problems without hiding last good data", async () => {
  server.use(
    http.get("*/api/v1/manage/state", () =>
      HttpResponse.json({
        ...manageSnapshot,
        stale: true,
        refreshError: {
          source: "observation",
          message: "Cluster refresh timed out",
          retryable: true,
        },
        problems: [
          {
            source: "configuration",
            message: "Pending configuration is unavailable",
            retryable: true,
          },
        ],
      }),
    ),
  );

  renderApp();

  expect(await screen.findByText("Showing last known cluster state"))
    .toBeInTheDocument();
  expect(screen.getByText("Cluster refresh timed out")).toBeInTheDocument();
  expect(screen.getByText("Pending configuration is unavailable"))
    .toBeInTheDocument();
  expect(
    screen.getByRole("tree", { name: "Workflow resources" }),
  ).toBeInTheDocument();
});
