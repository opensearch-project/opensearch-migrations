import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, test, vi } from "vitest";

import { getHealth } from "../api/client";
import { configDraft, manageSnapshot } from "../test/fixtures";
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
  expect(screen.getByRole("button", { name: "Edit capture" })).toBeEnabled();
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


test("opens a generic configuration editor and explains generated values", async () => {
  renderApp();
  await userEvent.click(
    await screen.findByRole("button", { name: "Edit capture" }),
  );

  expect(
    await screen.findByRole("heading", { name: "Configuration" }),
  ).toBeInTheDocument();
  const configTree = screen.getByRole("tree", {
    name: "Configuration fields",
  });
  expect(
    within(configTree).queryByRole("treeitem", { name: /Timeout: 30/ }),
  ).toBeNull();

  await userEvent.click(
    screen.getByRole("checkbox", { name: "Show optional fields" }),
  );
  await userEvent.click(
    within(configTree).getByRole("treeitem", { name: /Timeout: 30/ }),
  );

  expect(screen.getByText("Generated value")).toBeInTheDocument();
  expect(screen.getByText("runtime timeout")).toBeInTheDocument();
  expect(
    screen.getByText("Generated from the standard runtime profile."),
  ).toBeInTheDocument();

  expect(
    within(configTree).queryByRole("treeitem", {
      name: /Advanced setting: quiet/,
    }),
  ).toBeNull();
  await userEvent.click(
    screen.getByRole("checkbox", { name: "Show expert fields" }),
  );
  expect(
    within(configTree).getByRole("treeitem", {
      name: /Advanced setting: quiet/,
    }),
  ).toBeInTheDocument();
});


test("navigates configuration rows without changing selection until activation", async () => {
  renderApp();
  await userEvent.click(
    await screen.findByRole("button", { name: "Edit capture" }),
  );
  const configTree = await screen.findByRole("tree", {
    name: "Configuration fields",
  });
  const endpoint = within(configTree).getByRole("treeitem", {
    name: /Endpoint: https:\/\/legacy.example.com:9200/,
  });
  await userEvent.click(endpoint);
  endpoint.focus();

  await userEvent.keyboard("{ArrowDown}");
  const allowInsecure = within(configTree).getByRole("treeitem", {
    name: /Allow insecure: false/,
  });
  expect(allowInsecure).toHaveFocus();
  expect(endpoint).toHaveAttribute("aria-selected", "true");
  expect(allowInsecure).toHaveAttribute("aria-selected", "false");

  await userEvent.keyboard("{Enter}");
  expect(allowInsecure).toHaveAttribute("aria-selected", "true");
  expect(screen.getByRole("checkbox", { name: "Allow insecure" }))
    .toBeInTheDocument();
});


test("submits scalar, exact-node rename, union, and add operations", async () => {
  const operations: unknown[] = [];
  server.use(
    http.post("*/api/v1/config/operations", async ({ request }) => {
      const body = await request.json() as { operation: unknown };
      operations.push(body.operation);
      return HttpResponse.json({
        ...configDraft,
        dirty: true,
        draftRevision: `config-draft-${operations.length + 1}`,
      });
    }),
  );
  renderApp();
  await userEvent.click(
    await screen.findByRole("button", { name: "Edit capture" }),
  );
  const configTree = await screen.findByRole("tree", {
    name: "Configuration fields",
  });

  await userEvent.click(
    within(configTree).getByRole("treeitem", {
      name: /Endpoint: https:\/\/legacy.example.com:9200/,
    }),
  );
  const valueInput = screen.getByRole("textbox", { name: "Endpoint" });
  await userEvent.clear(valueInput);
  await userEvent.type(valueInput, "https://next.example.com:9200");
  await userEvent.click(screen.getByRole("button", { name: "Apply value" }));

  await userEvent.click(
    within(configTree).getByRole("treeitem", { name: /^legacy/ }),
  );
  await userEvent.click(screen.getByRole("button", { name: "Rename legacy" }));
  const nameInput = screen.getByRole("textbox", { name: "Configuration name" });
  expect(nameInput).toHaveAttribute("pattern", "^[a-z0-9-]+$");
  await userEvent.clear(nameInput);
  await userEvent.type(nameInput, "modern");
  await userEvent.click(screen.getByRole("button", { name: "Apply rename" }));

  await userEvent.click(
    within(configTree).getByRole("treeitem", { name: /Authentication/ }),
  );
  await userEvent.selectOptions(
    screen.getByRole("combobox", { name: "Authentication" }),
    "sigv4",
  );
  await userEvent.click(screen.getByRole("button", { name: "Apply option" }));

  await userEvent.click(
    within(configTree).getByRole("treeitem", { name: /Snapshot: nightly/ }),
  );
  const snapshotChoice = screen.getByRole("combobox", { name: "Snapshot" });
  expect(
    within(snapshotChoice).getByRole("option", { name: "weekly" }),
  ).toBeInTheDocument();
  await userEvent.selectOptions(snapshotChoice, "weekly");
  expect(
    screen.getByText("Generated from the source snapshot definitions."),
  ).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Apply value" }));

  await userEvent.click(
    within(configTree).getByRole("treeitem", { name: /\+ Add transform/ }),
  );
  await userEvent.click(screen.getByRole("button", { name: "Add transform" }));

  expect(operations).toEqual([
    {
      op: "set",
      path: ["sourceClusters", "legacy", "endpoint"],
      value: "https://next.example.com:9200",
    },
    {
      op: "renameConfig",
      path: ["sourceClusters", "legacy"],
      newName: "modern",
    },
    {
      op: "set",
      path: ["sourceClusters", "legacy", "authConfig"],
      value: "sigv4",
    },
    {
      op: "set",
      path: ["sourceClusters", "legacy", "snapshotName"],
      value: "weekly",
    },
    {
      op: "add",
      path: ["traffic", "transforms"],
      value: {},
    },
  ]);
});


test("shows ConfigMap keys and selects the map plus key together", async () => {
  let selection: unknown;
  server.use(
    http.post("*/api/v1/external-resources/select", async ({ request }) => {
      selection = await request.json();
      return HttpResponse.json({
        ...configDraft,
        dirty: true,
        draftRevision: "config-draft-selected",
      });
    }),
  );
  renderApp();
  await userEvent.click(
    await screen.findByRole("button", { name: "Edit capture" }),
  );
  const configTree = await screen.findByRole("tree", {
    name: "Configuration fields",
  });
  await userEvent.click(
    within(configTree).getByRole("treeitem", {
      name: /ConfigMap: transform-code/,
    }),
  );
  await userEvent.click(
    screen.getByRole("button", { name: "Browse Kubernetes resources" }),
  );

  expect(await screen.findByText("main.js")).toBeInTheDocument();
  expect(screen.getByText("settings.json")).toBeInTheDocument();
  await userEvent.click(
    screen.getByRole("button", {
      name: "Use transform-code and key main.js",
    }),
  );

  expect(selection).toEqual({
    expectedDraftRevision: "config-draft-1",
    nodeId: "edit:traffic.transform.configMap",
    name: "transform-code",
    kind: "ConfigMap",
    group: "",
    key: "main.js",
    acceptWarning: false,
    manual: false,
  });
});


test("allows an explicit ConfigMap and key when inventory is unavailable", async () => {
  let selection: unknown;
  server.use(
    http.post("*/api/v1/external-resources/select", async ({ request }) => {
      selection = await request.json();
      return HttpResponse.json({
        ...configDraft,
        dirty: true,
        draftRevision: "config-draft-manual-selection",
      });
    }),
  );
  renderApp();
  await userEvent.click(
    await screen.findByRole("button", { name: "Edit capture" }),
  );
  const configTree = await screen.findByRole("tree", {
    name: "Configuration fields",
  });
  await userEvent.click(
    within(configTree).getByRole("treeitem", {
      name: /ConfigMap: transform-code/,
    }),
  );
  await userEvent.click(
    screen.getByRole("button", { name: "Enter reference manually" }),
  );
  await userEvent.type(
    screen.getByRole("textbox", { name: "Resource name" }),
    "private-transform",
  );
  await userEvent.type(
    screen.getByRole("textbox", { name: "ConfigMap key" }),
    "transform.js",
  );
  await userEvent.click(
    screen.getByRole("button", { name: "Use unverified reference" }),
  );

  expect(selection).toEqual({
    expectedDraftRevision: "config-draft-1",
    nodeId: "edit:traffic.transform.configMap",
    name: "private-transform",
    kind: "ConfigMap",
    group: "",
    key: "transform.js",
    acceptWarning: true,
    manual: true,
  });
});


test("focuses a newly added array item when command metadata requests it", async () => {
  server.use(
    http.post("*/api/v1/config/operations", () => {
      const updated = structuredClone(configDraft);
      const traffic = updated.editState.nodes.find(
        (node) => node.id === "edit:traffic",
      );
      const transforms = traffic?.children.find(
        (node) => node.id === "edit:traffic.transforms",
      );
      if (!transforms) throw new Error("Missing transforms fixture");
      transforms.children = [{
        id: "edit:traffic.transforms.0",
        path: ["traffic", "transforms", "0"],
        label: "transform 1: configured",
        value: {},
        valueAuthored: true,
        valueKind: "object",
        presence: "required",
        removable: true,
        status: "required",
        diagnostics: [{
          severity: "required",
          message: "entryPoint is required.",
          path: ["traffic", "transforms", "0", "entryPoint"],
        }],
        children: [],
      }, ...transforms.children];
      return HttpResponse.json({
        ...updated,
        dirty: true,
        draftRevision: "config-draft-added",
      });
    }),
  );
  renderApp();
  await userEvent.click(
    await screen.findByRole("button", { name: "Edit capture" }),
  );
  const configTree = await screen.findByRole("tree", {
    name: "Configuration fields",
  });
  await userEvent.click(
    within(configTree).getByRole("treeitem", { name: /\+ Add transform/ }),
  );
  await userEvent.click(screen.getByRole("button", { name: "Add transform" }));

  expect(
    await within(configTree).findByRole("treeitem", {
      name: "transform 1: configured",
    }),
  ).toHaveAttribute("aria-selected", "true");
});


test("views and creates descriptor-driven ConfigMaps without raw YAML", async () => {
  let saveRequest: unknown;
  server.use(
    http.post("*/api/v1/external-resources/save", async ({ request }) => {
      saveRequest = await request.json();
      return HttpResponse.json({
        draft: {
          ...configDraft,
          dirty: true,
          draftRevision: "config-draft-created",
        },
        name: "next-transform",
        kind: "ConfigMap",
        message: "ConfigMap created: next-transform",
      });
    }),
  );
  renderApp();
  await userEvent.click(
    await screen.findByRole("button", { name: "Edit capture" }),
  );
  const configTree = await screen.findByRole("tree", {
    name: "Configuration fields",
  });
  await userEvent.click(
    within(configTree).getByRole("treeitem", {
      name: /ConfigMap: transform-code/,
    }),
  );
  await userEvent.click(
    screen.getByRole("button", { name: "Browse Kubernetes resources" }),
  );

  await userEvent.click(
    await screen.findByRole("button", { name: "Inspect transform-code" }),
  );
  expect(await screen.findByText("export default () => true;"))
    .toBeInTheDocument();
  expect(screen.queryByText(/raw YAML/i)).toBeNull();
  await userEvent.click(screen.getByRole("button", { name: "Back to resources" }));

  await userEvent.click(
    screen.getByRole("button", { name: "Create Transform ConfigMap" }),
  );
  await userEvent.type(
    screen.getByRole("textbox", { name: "ConfigMap name" }),
    "next-transform",
  );
  const key = screen.getByRole("textbox", { name: "Key" });
  expect(key).toHaveValue("transform.js");
  await userEvent.type(
    screen.getByRole("textbox", { name: "JavaScript" }),
    "export default () => false;",
  );
  await userEvent.click(screen.getByRole("button", { name: "Create resource" }));

  expect(saveRequest).toEqual({
    expectedDraftRevision: "config-draft-1",
    nodeId: "edit:traffic.transform.configMap",
    values: {
      name: "next-transform",
      key: "transform.js",
      contents: "export default () => false;",
    },
    confirmations: {},
    existingName: null,
  });
});


test("saves and discards explicit dirty drafts", async () => {
  let saved = false;
  let discarded = false;
  server.use(
    http.get("*/api/v1/config", () =>
      HttpResponse.json({
        ...configDraft,
        dirty: true,
        draftRevision: "dirty-draft",
      }),
    ),
    http.post("*/api/v1/config/save", () => {
      saved = true;
      return HttpResponse.json(configDraft);
    }),
    http.post("*/api/v1/config/discard", () => {
      discarded = true;
      return HttpResponse.json(configDraft);
    }),
  );
  renderApp();
  await userEvent.click(
    await screen.findByRole("button", { name: "Edit capture" }),
  );

  await userEvent.click(screen.getByRole("button", { name: "Save changes" }));
  expect(saved).toBe(true);

  server.use(
    http.get("*/api/v1/config", () =>
      HttpResponse.json({
        ...configDraft,
        dirty: true,
        draftRevision: "dirty-again",
      }),
    ),
  );
  await userEvent.click(screen.getByRole("button", { name: "Reload draft" }));
  await userEvent.click(screen.getByRole("button", { name: "Discard changes" }));
  expect(discarded).toBe(true);
});


test("closing a dirty editor discards the process-local draft before leaving", async () => {
  let discardCalls = 0;
  server.use(
    http.get("*/api/v1/config", () =>
      HttpResponse.json({
        ...configDraft,
        dirty: true,
        draftRevision: "dirty-close",
      }),
    ),
    http.post("*/api/v1/config/discard", () => {
      discardCalls += 1;
      return HttpResponse.json(configDraft);
    }),
  );
  const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
  renderApp();
  await userEvent.click(
    await screen.findByRole("button", { name: "Edit capture" }),
  );

  await userEvent.click(
    screen.getByRole("button", { name: "Close configuration" }),
  );

  expect(discardCalls).toBe(1);
  expect(confirm).toHaveBeenCalledOnce();
  expect(await screen.findByRole("tree", { name: "Workflow resources" }))
    .toBeInTheDocument();
  confirm.mockRestore();
});
