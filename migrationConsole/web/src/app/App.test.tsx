import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
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


async function enterEditMode() {
  await userEvent.click(
    await screen.findByRole("button", { name: "Edit configuration" }),
  );
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
  expect(screen.getByRole("button", {
    name: "Edit configuration",
  })).toBeEnabled();
  expect(screen.queryByRole("button", { name: "Edit capture" })).toBeNull();
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
  await enterEditMode();

  expect(
    await screen.findByRole("heading", { name: "Edit capture" }),
  ).toBeInTheDocument();
  const configTree = screen.getByRole("table", {
    name: "Configuration fields",
  });
  expect(
    within(configTree).queryByRole("row", { name: /Timeout/ }),
  ).toBeNull();

  await userEvent.click(
    screen.getByRole("checkbox", { name: "Show optional fields" }),
  );
  await userEvent.click(
    within(configTree).getByRole("row", { name: /Timeout/ }),
  );

  expect(screen.getByText("Generated value")).toBeInTheDocument();
  expect(screen.getByText("runtime timeout")).toBeInTheDocument();
  expect(
    screen.getByText("Generated from the standard runtime profile."),
  ).toBeInTheDocument();

  expect(
    within(configTree).queryByRole("row", {
      name: /Advanced setting/,
    }),
  ).toBeNull();
  await userEvent.click(
    screen.getByRole("checkbox", { name: "Show expert fields" }),
  );
  expect(
    within(configTree).getByRole("row", {
      name: /Advanced setting/,
    }),
  ).toBeInTheDocument();
});


test("keeps resource context while scoping edit mode to the selected resource", async () => {
  const scopedSnapshot = structuredClone(manageSnapshot);
  const capture = scopedSnapshot.nodes["resource:captureproxies:capture"];
  const replay = scopedSnapshot.nodes["resource:trafficreplays:replay"];
  capture.capabilities = capture.capabilities.map((capability) => (
    capability.kind === "edit"
      ? {
        ...capability,
        editTargetId: "edit:sourceClusters.legacy.endpoint",
      }
      : capability
  ));
  replay.capabilities = replay.capabilities.map((capability) => (
    capability.kind === "edit"
      ? {
        ...capability,
        editTargetId: "edit:traffic.transform.configMap",
      }
      : capability
  ));
  server.use(
    http.get("*/api/v1/manage/state", () =>
      HttpResponse.json(scopedSnapshot),
    ),
  );
  renderApp();

  await enterEditMode();

  const resources = screen.getByRole("tree", {
    name: "Workflow resources",
  });
  expect(resources).toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "Activity" }))
    .toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "Edit capture" }))
    .toBeInTheDocument();

  const config = screen.getByRole("table", {
    name: "Configuration fields",
  });
  expect(await within(config).findByRole("row", {
    name: /Endpoint/,
  })).toBeInTheDocument();
  expect(within(config).queryByRole("row", {
    name: /ConfigMap/,
  })).toBeNull();

  await userEvent.click(within(resources).getByRole("treeitem", {
    name: /^replay, Running$/,
  }));

  expect(await screen.findByRole("heading", { name: "Edit replay" }))
    .toBeInTheDocument();
  expect(await within(config).findByRole("row", {
    name: /^ConfigMap Authored value/,
  })).toBeInTheDocument();
  expect(within(config).queryByRole("row", {
    name: /Endpoint/,
  })).toBeNull();
});


test("offers top-level add actions in navigation during scoped editing", async () => {
  const scopedSnapshot = structuredClone(manageSnapshot);
  const capture = scopedSnapshot.nodes["resource:captureproxies:capture"];
  capture.capabilities = capture.capabilities.map((capability) => (
    capability.kind === "edit"
      ? {
        ...capability,
        editTargetId: "edit:sourceClusters.legacy.endpoint",
      }
      : capability
  ));
  const operations: unknown[] = [];
  server.use(
    http.get("*/api/v1/manage/state", () =>
      HttpResponse.json(scopedSnapshot),
    ),
    http.post("*/api/v1/config/operations", async ({ request }) => {
      const body = await request.json() as { operation: unknown };
      operations.push(body.operation);
      return HttpResponse.json({
        ...configDraft,
        dirty: true,
        draftRevision: "config-draft-added-from-scope",
      });
    }),
  );
  renderApp();

  await enterEditMode();
  const config = await screen.findByRole("table", {
    name: "Configuration fields",
  });
  expect(within(config).queryByRole("row", {
    name: /^Source clusters/,
  })).toBeNull();

  const resourceNavigation = screen.getByRole("region", {
    name: "Resource navigation",
  });
  await userEvent.click(within(resourceNavigation).getByRole("button", {
    name: "Add source cluster",
  }));
  await userEvent.type(
    screen.getByRole("textbox", { name: "source cluster name" }),
    "next-source",
  );
  await userEvent.click(screen.getByRole("button", {
    name: "Create source cluster",
  }));

  await waitFor(() => expect(operations).toEqual([{
    op: "add",
    path: ["sourceClusters"],
    value: { name: "next-source" },
  }]));
});


test("shows the server reason when configuration cannot be opened", async () => {
  server.use(
    http.get("*/api/v1/config", () =>
      HttpResponse.json(
        {
          detail: {
            code: "configuration_unavailable",
            message: "CONFIG_PROCESSOR_DIR is not configured",
          },
        },
        { status: 503 },
      ),
    ),
  );
  renderApp();

  await enterEditMode();

  expect(
    await screen.findByRole("heading", {
      name: "Configuration is unavailable",
    }),
  ).toBeInTheDocument();
  expect(screen.getByText("CONFIG_PROCESSOR_DIR is not configured"))
    .toBeInTheDocument();
  expect(screen.getByRole("tree", { name: "Workflow resources" }))
    .toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "Activity" }))
    .toBeInTheDocument();
});


test("guards browser back navigation before leaving workflow manage", async () => {
  const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
  renderApp();
  await screen.findByRole("tree", { name: "Workflow resources" });

  fireEvent.popState(window);

  await waitFor(() => expect(confirm).toHaveBeenCalledWith(
    "Leave Workflow Manage? Active operations will continue in the cluster.",
  ));
  expect(
    screen.getByRole("heading", { name: "Workflow Manage" }),
  ).toBeInTheDocument();
});


test("changes a union inline and inserts its variant fields directly below", async () => {
  const operations: unknown[] = [];
  server.use(
    http.post("*/api/v1/config/operations", async ({ request }) => {
      const body = await request.json() as { operation: unknown };
      operations.push(body.operation);
      const updated = structuredClone(configDraft);
      const sourceClusters = updated.editState.nodes.find(
        (node) => node.id === "edit:sourceClusters",
      );
      const legacy = sourceClusters?.children.find(
        (node) => node.id === "edit:sourceClusters.legacy",
      );
      const auth = legacy?.children.find(
        (node) => node.id === "edit:sourceClusters.legacy.authConfig",
      );
      if (!auth) throw new Error("Missing authentication fixture");
      auth.value = "sigv4";
      auth.label = "Authentication: < sigv4 >";
      auth.children = [{
        id: "edit:sourceClusters.legacy.authConfig.sigv4.region",
        path: [
          "sourceClusters",
          "legacy",
          "authConfig",
          "sigv4",
          "region",
        ],
        label: "Signing region: us-east-1",
        value: "us-east-1",
        valueAuthored: true,
        valueKind: "scalar",
        valueType: "string",
        presence: "required",
        required: true,
        status: "ok",
        statusCounts: {
          errors: 0,
          warnings: 0,
          required: 0,
          gated: 0,
          blocked: 0,
        },
        diagnostics: [],
        children: [],
      }];
      return HttpResponse.json({
        ...updated,
        dirty: true,
        draftRevision: "config-draft-sigv4",
      });
    }),
  );
  renderApp();
  await enterEditMode();
  const configTable = await screen.findByRole("table", {
    name: "Configuration fields",
  });
  const authRow = within(configTable).getByRole("row", {
    name: /Authentication/,
  });
  const authType = within(authRow).getByRole("combobox", {
    name: "Authentication",
  });

  await userEvent.selectOptions(authType, "sigv4");

  const regionRow = await within(configTable).findByRole("row", {
    name: /Signing region/,
  });
  expect(authRow.nextElementSibling).toBe(regionRow);
  expect(operations).toEqual([{
    op: "set",
    path: ["sourceClusters", "legacy", "authConfig"],
    value: "sigv4",
  }]);
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
  await enterEditMode();
  const configTree = await screen.findByRole("table", {
    name: "Configuration fields",
  });

  const endpointRow = within(configTree).getByRole("row", {
    name: /Endpoint/,
  });
  const valueInput = screen.getByRole("textbox", { name: "Endpoint" });
  await userEvent.clear(valueInput);
  await userEvent.type(valueInput, "https://next.example.com:9200");
  expect(
    within(endpointRow).queryByRole("button", { name: "Apply" }),
  ).toBeNull();
  await userEvent.keyboard("{Enter}");
  await waitFor(() =>
    expect(operations).toContainEqual({
      op: "set",
      path: ["sourceClusters", "legacy", "endpoint"],
      value: "https://next.example.com:9200",
    }),
  );

  await userEvent.click(
    within(configTree).getByRole("row", { name: /^legacy/ }),
  );
  await userEvent.click(screen.getByRole("button", { name: "Rename legacy" }));
  const nameInput = screen.getByRole("textbox", { name: "Configuration name" });
  expect(nameInput).toHaveAttribute("pattern", "^[a-z0-9-]+$");
  await userEvent.clear(nameInput);
  await userEvent.type(nameInput, "modern");
  await userEvent.click(screen.getByRole("button", { name: "Apply rename" }));

  await userEvent.selectOptions(
    screen.getByRole("combobox", { name: "Authentication" }),
    "sigv4",
  );

  const snapshotChoice = screen.getByRole("combobox", { name: "Snapshot" });
  expect(
    within(snapshotChoice).getByRole("option", { name: "weekly" }),
  ).toBeInTheDocument();
  await userEvent.selectOptions(snapshotChoice, "weekly");
  expect(
    screen.getByText("Generated from the source snapshot definitions."),
  ).toBeInTheDocument();

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


test("saves a focused text edit as one resource-level action", async () => {
  const calls: string[] = [];
  let saveRequest: unknown;
  server.use(
    http.post("*/api/v1/config/operations", async () => {
      calls.push("update-draft");
      await new Promise((resolve) => window.setTimeout(resolve, 20));
      return HttpResponse.json({
        ...configDraft,
        dirty: true,
        draftRevision: "config-draft-after-blur",
      });
    }),
    http.post("*/api/v1/config/save", async ({ request }) => {
      calls.push("save-resource");
      saveRequest = await request.json();
      return HttpResponse.json(configDraft);
    }),
  );
  renderApp();
  await enterEditMode();

  const endpoint = await screen.findByRole("textbox", { name: "Endpoint" });
  await userEvent.clear(endpoint);
  await userEvent.type(endpoint, "https://saved.example.com:9200");
  expect(screen.getByRole("button", {
    name: "Save configuration",
  })).toBeEnabled();

  await userEvent.click(screen.getByRole("button", {
    name: "Save configuration",
  }));

  await waitFor(() => expect(saveRequest).toEqual({
    expectedDraftRevision: "config-draft-after-blur",
  }));
  expect(calls).toEqual(["update-draft", "save-resource"]);
  expect(screen.queryByRole("button", { name: "Apply" })).toBeNull();
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
  await enterEditMode();
  const configTree = await screen.findByRole("table", {
    name: "Configuration fields",
  });
  await userEvent.click(
    within(configTree).getByRole("row", {
      name: /ConfigMap/,
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
  await enterEditMode();
  const configTree = await screen.findByRole("table", {
    name: "Configuration fields",
  });
  await userEvent.click(
    within(configTree).getByRole("row", {
      name: /ConfigMap/,
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


test("promotes add commands to collection actions and keeps exact deletion", async () => {
  const operations: unknown[] = [];
  server.use(
    http.post("*/api/v1/config/operations", async ({ request }) => {
      const body = await request.json() as { operation: unknown };
      operations.push(body.operation);
      return HttpResponse.json({
        ...configDraft,
        dirty: true,
        draftRevision: `config-draft-command-${operations.length}`,
      });
    }),
  );
  renderApp();
  await enterEditMode();
  const config = await screen.findByRole("table", {
    name: "Configuration fields",
  });
  expect(within(config).queryByRole("row", {
    name: /Add source cluster/,
  })).toBeNull();

  const sourceClusters = within(config).getByRole("row", {
    name: /^Source clusters/,
  });
  await userEvent.click(within(sourceClusters).getByRole("button", {
    name: "Add source cluster",
  }));
  await userEvent.type(
    screen.getByRole("textbox", { name: "source cluster name" }),
    "modern",
  );
  await userEvent.click(screen.getByRole("button", {
    name: "Create source cluster",
  }));
  await waitFor(() => expect(operations).toHaveLength(1));

  await userEvent.click(screen.getByRole("button", { name: "Remove legacy" }));
  expect(await screen.findByRole("dialog", {
    name: "Remove legacy?",
  })).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", {
    name: "Confirm removal",
  }));
  await waitFor(() => expect(operations).toHaveLength(2));

  expect(operations).toEqual([{
    op: "add",
    path: ["sourceClusters"],
    value: { name: "modern" },
  }, {
    op: "removeConfig",
    path: ["sourceClusters", "legacy"],
  }]);
});


test("keeps a deleted source selected as a tombstone and previews dependents", async () => {
  const snapshot = structuredClone(manageSnapshot);
  const source = snapshot.nodes["resource:captureproxies:capture"];
  source.label = "source";
  source.resourcePlural = "sourceconfigs";
  source.resourceName = "source";
  source.valueSummary = "Deployed";
  source.capabilities = [{
    kind: "edit",
    editTargetId: "edit:sourceClusters.legacy",
    label: "Edit source",
  }];
  const removedDraft = structuredClone(configDraft);
  const sources = removedDraft.editState.nodes.find(
    (node) => node.id === "edit:sourceClusters",
  );
  if (!sources) throw new Error("Missing source collection fixture");
  sources.children = [];
  removedDraft.dirty = true;
  removedDraft.draftRevision = "draft-source-removed";

  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
    http.post("*/api/v1/config/removal-impact", () =>
      HttpResponse.json({
        targetPath: ["sourceClusters", "legacy"],
        targetLabel: "legacy",
        affected: [{
          path: ["traffic", "proxies", "capture"],
          fieldPath: ["traffic", "proxies", "capture", "source"],
          reason: "source=legacy",
        }, {
          path: ["traffic", "replayers", "replay"],
          fieldPath: [
            "traffic",
            "replayers",
            "replay",
            "fromCapturedTraffic",
          ],
          reason: "fromCapturedTraffic=capture",
        }],
      }),
    ),
    http.post("*/api/v1/config/operations", () =>
      HttpResponse.json(removedDraft),
    ),
  );
  renderApp();

  await enterEditMode();
  await userEvent.click(screen.getByRole("button", { name: "Remove legacy" }));

  const dialog = await screen.findByRole("dialog", {
    name: "Remove legacy?",
  });
  expect(within(dialog).getByText("traffic.proxies.capture"))
    .toBeInTheDocument();
  expect(within(dialog).getByText("traffic.replayers.replay"))
    .toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole("button", {
    name: "Confirm removal",
  }));

  const tree = screen.getByRole("tree", { name: "Workflow resources" });
  expect(await within(tree).findByRole("treeitem", {
    name: /^source, Marked for removal$/,
  })).toHaveAttribute("aria-selected", "true");
  expect(screen.getByRole("heading", { name: "source" })).toBeInTheDocument();
  expect(screen.getByText(
    "This source is marked for removal from the configuration.",
  )).toBeInTheDocument();
  expect(screen.queryByText("Workflow configuration")).toBeNull();
});


test("opens a pending removal with a resource fallback target as a tombstone", async () => {
  const snapshot = structuredClone(manageSnapshot);
  const source = snapshot.nodes["resource:captureproxies:capture"];
  source.label = "source";
  source.resourcePlural = "sourceconfigs";
  source.resourceName = "source";
  source.valueSummary = "Removal pending submission";
  source.configPresence = {
    deployed: true,
    pending: false,
  };
  source.capabilities = [{
    kind: "edit",
    editTargetId: "edit:sourceconfigs:source",
    label: "Edit source",
  }];
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
  );
  renderApp();

  await enterEditMode();

  const tree = screen.getByRole("tree", { name: "Workflow resources" });
  expect(await within(tree).findByRole("treeitem", {
    name: /^source, Removal pending submission$/,
  })).toHaveAttribute("aria-selected", "true");
  expect(screen.getByText(
    "This source is marked for removal from the configuration.",
  )).toBeInTheDocument();
  expect(screen.queryByRole("table", {
    name: "Configuration fields",
  })).toBeNull();
  const resourceNavigation = screen.getByRole("region", {
    name: "Resource navigation",
  });
  expect(within(resourceNavigation).getByRole("button", {
    name: "Add source cluster",
  })).toBeInTheDocument();
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
  await enterEditMode();
  const configTree = await screen.findByRole("table", {
    name: "Configuration fields",
  });
  const transforms = within(configTree).getByRole("row", {
    name: /^Transforms/,
  });
  await userEvent.click(within(transforms).getByRole("button", {
    name: "Add transform",
  }));

  expect(
    await within(configTree).findByRole("row", {
      name: /^transform 1 Authored value/,
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
  await enterEditMode();
  const configTree = await screen.findByRole("table", {
    name: "Configuration fields",
  });
  await userEvent.click(
    within(configTree).getByRole("row", {
      name: /ConfigMap/,
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
  const { client } = renderApp();
  await enterEditMode();

  await userEvent.click(screen.getByRole("button", {
    name: "Save configuration",
  }));
  expect(saved).toBe(true);

  client.setQueryData(["config-draft"], {
    ...configDraft,
    dirty: true,
    draftRevision: "dirty-again",
  });
  await userEvent.click(screen.getByRole("button", {
    name: "Revert unsaved changes",
  }));
  expect(discarded).toBe(true);
  expect(screen.getByText("Editing configuration")).toBeInTheDocument();
});


test("exiting a dirty edit session discards the draft before leaving", async () => {
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
  await enterEditMode();

  await userEvent.click(
    screen.getByRole("button", { name: "Exit editing" }),
  );

  expect(discardCalls).toBe(1);
  expect(confirm).toHaveBeenCalledOnce();
  expect(await screen.findByRole("button", { name: "Edit configuration" }))
    .toBeInTheDocument();
  confirm.mockRestore();
});


test("submitting saves the current draft and leaves edit mode", async () => {
  let submitRequest: unknown;
  const validDraft = structuredClone(configDraft);
  validDraft.dirty = true;
  validDraft.draftRevision = "dirty-to-submit";
  validDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.get("*/api/v1/config", () => HttpResponse.json(validDraft)),
    http.post("*/api/v1/config/submit", async ({ request }) => {
      submitRequest = await request.json();
      return HttpResponse.json({
        draft: {
          ...validDraft,
          dirty: false,
          baseRevision: validDraft.draftRevision,
        },
        workflowName: "migration",
        message: "Workflow submitted: migration",
      });
    }),
  );
  renderApp();
  await enterEditMode();

  await userEvent.click(screen.getByRole("button", {
    name: "Save and submit",
  }));
  const dialog = await screen.findByRole("dialog", {
    name: "Submit configuration?",
  });
  await userEvent.click(within(dialog).getByRole("button", {
    name: "Confirm submit",
  }));

  await waitFor(() => expect(submitRequest).toEqual({
    expectedDraftRevision: "dirty-to-submit",
  }));
  expect(await screen.findByRole("button", { name: "Edit configuration" }))
    .toBeInTheDocument();
  expect(screen.queryByText("Editing configuration")).toBeNull();
});
