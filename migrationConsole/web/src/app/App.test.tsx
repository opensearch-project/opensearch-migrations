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
  expect(screen.getByRole("button", {
    name: "Review and submit",
  })).toBeDisabled();
  expect(screen.getByText("1 configuration error")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Edit capture" })).toBeNull();
  expect(screen.getByRole("button", { name: "Logs for capture" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Reset capture" })).toBeEnabled();
});


test("opens resource-owned managed output with context and download", async () => {
  const outputState = structuredClone(manageSnapshot);
  outputState.nodes["resource:captureproxies:capture"].capabilities.push({
    kind: "output",
    outputTargetId: (
      "output:snapshotmigrations:migration-0:metadataEvaluate"
    ),
    label: "View metadata evaluate",
  });
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(outputState)),
  );
  renderApp();

  await userEvent.click(await screen.findByRole("button", {
    name: "View metadata evaluate",
  }));

  expect(await screen.findByRole("region", { name: "Managed output" }))
    .toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "Evaluate" }))
    .toBeInTheDocument();
  expect(screen.getByText("migration-0")).toBeInTheDocument();
  expect(await screen.findByText(/"documents": 12/)).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Download" }))
    .toHaveAttribute("href", expect.stringContaining(
      "/api/v1/outputs/download?outputId=",
    ));
});


test("reviews exact approval and reset targets before starting operations", async () => {
  const actionState = structuredClone(manageSnapshot);
  actionState.nodes["resource:captureproxies:capture"].capabilities.push({
    kind: "approve",
    approvalTargetId: "approval:approval-node",
    label: "Approve metadata",
  });
  let approvalRequest: unknown;
  let resetRequest: unknown;
  const operation = (kind: string, label: string) => ({
    id: `operation-${kind}`,
    kind,
    label,
    status: "queued",
    targetIds: ["resource:captureproxies:capture"],
    createdAt: "2026-08-13T13:00:00Z",
    updatedAt: "2026-08-13T13:00:00Z",
    message: "Queued",
    detail: null,
    result: {},
  });
  server.use(
    http.get("*/api/v1/manage/state", () =>
      HttpResponse.json(actionState)),
    http.post("*/api/v1/approvals", async ({ request }) => {
      approvalRequest = await request.json();
      return HttpResponse.json(
        operation("approve", "Approve Metadata evaluation"),
        { status: 202 },
      );
    }),
    http.post("*/api/v1/resets", async ({ request }) => {
      resetRequest = await request.json();
      return HttpResponse.json(
        operation("reset", "Reset captureproxy.capture"),
        { status: 202 },
      );
    }),
  );
  renderApp();

  await userEvent.click(await screen.findByRole("button", {
    name: "Approve metadata",
  }));
  const approval = await screen.findByRole("dialog", {
    name: "Approve Metadata evaluation?",
  });
  expect(within(approval).getByText("migration-0")).toBeInTheDocument();
  expect(within(approval).getByText(/advances to metadata migration/))
    .toBeInTheDocument();
  await userEvent.click(within(approval).getByRole("button", {
    name: "Approve exact gate",
  }));
  await waitFor(() => expect(approvalRequest).toEqual({
    targetId: "approval:approval-node",
    expectedGateRevision: "11",
  }));

  await userEvent.click(screen.getByRole("button", {
    name: "Reset capture",
  }));
  const reset = await screen.findByRole("dialog", {
    name: "Review reset plan",
  });
  expect(within(reset).getByText("captureproxy.capture"))
    .toBeInTheDocument();
  await userEvent.click(within(reset).getByRole("button", {
    name: "Reset exact plan",
  }));
  await waitFor(() => expect(resetRequest).toEqual({
    planToken: "reset-token",
  }));
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
  expect(screen.getByRole("checkbox", {
    name: "Show field documentation",
  })).not.toBeChecked();
  const credentials = within(configTree).getByRole("row", {
    name: /Credentials secret/,
  });
  const credentialsLabel = within(credentials)
    .getByText("Credentials secret", { selector: "strong" })
    .closest(".property-label");
  expect(credentialsLabel).toHaveAttribute(
    "title",
    "Kubernetes Secret containing the HTTP credentials.",
  );
  expect(within(credentials).queryByText(
    "Kubernetes Secret containing the HTTP credentials.",
  )).toBeNull();
  expect(within(credentials).getByText("Authored"))
    .toBe(credentialsLabel?.querySelector(".property-flags span"));
  expect(configTree.querySelector(".status-dot")).toBeNull();
  expect(
    within(configTree).queryByRole("row", { name: /Timeout/ }),
  ).toBeNull();

  await userEvent.click(
    screen.getByRole("checkbox", { name: "Show optional fields" }),
  );
  const timeout = within(configTree).getByRole("row", { name: /Timeout/ });
  expect(timeout).toHaveClass("inserted");
  await userEvent.click(timeout);

  expect(within(timeout).getByText("Generated")).toBeInTheDocument();
  expect(screen.queryByText("runtime timeout")).toBeNull();
  expect(screen.queryByText(
    "Generated from the standard runtime profile.",
  )).toBeNull();
  const state = timeout.querySelector(".property-state-cell");
  expect(state?.querySelector(".property-state-content")).toBeInTheDocument();
  expect(within(state as HTMLElement).getByText("ok")).toBeInTheDocument();
  expect(within(state as HTMLElement).getByRole("button", {
    name: "Revert Timeout to default",
  })).toBeInTheDocument();

  await userEvent.click(screen.getByRole("checkbox", {
    name: "Show field documentation",
  }));
  expect(screen.getByText("runtime timeout")).toBeInTheDocument();
  expect(screen.getByText(
    "Generated from the standard runtime profile.",
  )).toBeInTheDocument();
  expect(within(credentials).getByText(
    "Kubernetes Secret containing the HTTP credentials.",
  )).toBeInTheDocument();
  expect(credentialsLabel).not.toHaveAttribute("title");

  expect(
    within(configTree).getByRole("row", {
      name: /Advanced setting/,
    }),
  ).toBeInTheDocument();

  await userEvent.click(
    screen.getByRole("checkbox", { name: "Show optional fields" }),
  );
  expect(within(configTree).getByRole("row", { name: /Timeout/ }))
    .toHaveClass("removing");
  await waitFor(() => expect(
    within(configTree).queryByRole("row", { name: /Timeout/ }),
  ).toBeNull());
});


test("expands authored expert sections and supports animated collapse and expand all", async () => {
  const expertDraft = structuredClone(configDraft);
  const sources = expertDraft.editState.nodes.find(
    (node) => node.id === "edit:sourceClusters",
  );
  const legacy = sources?.children.find(
    (node) => node.id === "edit:sourceClusters.legacy",
  );
  const advanced = legacy?.children.find(
    (node) => node.id === "edit:sourceClusters.legacy.advanced",
  );
  if (!advanced) throw new Error("Missing expert fixture");
  advanced.valueKind = "object";
  advanced.collapsed = true;
  advanced.children = [{
    id: "edit:sourceClusters.legacy.advanced.mode",
    path: ["sourceClusters", "legacy", "advanced", "mode"],
    label: "Expert mode: quiet",
    value: "quiet",
    valueAuthored: true,
    valueKind: "scalar",
    valueType: "string",
    presence: "optional",
    expert: true,
    status: "ok",
    diagnostics: [],
    children: [],
  }];
  legacy.children.push({
    id: "edit:sourceClusters.legacy.optionalGroup",
    path: ["sourceClusters", "legacy", "optionalGroup"],
    label: "Optional group",
    valueKind: "object",
    presence: "optional",
    collapsed: true,
    status: "ok",
    diagnostics: [],
    children: [{
      id: "edit:sourceClusters.legacy.optionalGroup.name",
      path: ["sourceClusters", "legacy", "optionalGroup", "name"],
      label: "Optional child: visible",
      value: "visible",
      valueKind: "scalar",
      valueType: "string",
      presence: "required",
      status: "ok",
      diagnostics: [],
      children: [],
    }],
  });
  server.use(
    http.get("*/api/v1/config", () => HttpResponse.json(expertDraft)),
  );
  renderApp();
  await enterEditMode();

  const config = await screen.findByRole("table", {
    name: "Configuration fields",
  });
  expect(screen.getByRole("checkbox", { name: "Show expert fields" }))
    .not.toBeChecked();
  expect(within(config).getByRole("row", { name: /Advanced setting/ }))
    .toBeInTheDocument();
  expect(within(config).getByRole("row", { name: /Expert mode/ }))
    .toBeInTheDocument();
  expect(document.querySelector(".config-scroll-space")).toBeInTheDocument();
  expect(within(config).getByRole("button", { name: "Expand Optional group" }))
    .toBeInTheDocument();
  expect(within(config).queryByRole("row", { name: /Optional child/ }))
    .toBeNull();

  await userEvent.click(screen.getByRole("checkbox", {
    name: "Show optional fields",
  }));
  expect(within(config).getByRole("row", { name: /Optional group/ }))
    .toBeInTheDocument();
  expect(within(config).getByRole("button", { name: "Collapse Optional group" }))
    .toBeInTheDocument();
  expect(within(config).getByRole("row", { name: /Optional child/ }))
    .toBeInTheDocument();

  const sourceClusters = within(config).getByRole("row", {
    name: /^Source clusters/,
  });
  await userEvent.click(within(sourceClusters).getByRole("button", {
    name: "Collapse Source clusters",
  }));

  expect(within(config).getByRole("row", { name: /^legacy/ }))
    .toHaveClass("removing");
  await waitFor(() => expect(
    within(config).queryByRole("row", { name: /^legacy/ }),
  ).toBeNull());

  await userEvent.click(screen.getByRole("button", { name: "Expand all" }));
  expect(await within(config).findByRole("row", { name: /^legacy/ }))
    .toHaveClass("inserted");
  expect(within(config).getByRole("row", { name: /Expert mode/ }))
    .toBeInTheDocument();
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
    name: /^legacy/,
  })).toBeNull();
  expect(screen.getByRole("button", { name: "Remove legacy" }))
    .toBeInTheDocument();
  expect(within(config).queryByRole("row", {
    name: /ConfigMap/,
  })).toBeNull();

  await userEvent.click(within(resources).getByRole("treeitem", {
    name: /^replay, Running$/,
  }));

  expect(await screen.findByRole("heading", { name: "Edit replay" }))
    .toBeInTheDocument();
  expect(await within(config).findByRole("row", {
    name: /^ConfigMap Authored/,
  })).toBeInTheDocument();
  expect(within(config).queryByRole("row", {
    name: /Endpoint/,
  })).toBeNull();
});


test("shows compact resource validation in navigation and hides valid detail", async () => {
  const snapshot = structuredClone(manageSnapshot);
  const source = snapshot.nodes["resource:captureproxies:capture"];
  source.label = "legacy";
  source.resourcePlural = "sourceconfigs";
  source.resourceName = "legacy";
  source.diagnostics = [];
  source.parentId = "group:Sources:Sources";
  source.capabilities = [{
    kind: "edit",
    editTargetId: "edit:sourceClusters.legacy",
    label: "Edit legacy",
  }];
  snapshot.nodes["group:Sources:Sources"].childIds = [source.id];
  snapshot.nodes["group:Live Traffic Migration:Capture"].childIds = [];
  const validDraft = structuredClone(configDraft);
  const sourceCollection = validDraft.editState.nodes.find(
    (node) => node.id === "edit:sourceClusters",
  );
  const sourceEdit = sourceCollection?.children.find(
    (node) => node.id === "edit:sourceClusters.legacy",
  );
  if (!sourceEdit) throw new Error("Missing source fixture");
  sourceEdit.status = "ok";
  sourceEdit.statusCounts = {
    errors: 0,
    warnings: 0,
    required: 0,
    changed: 0,
    gated: 0,
    blocked: 0,
  };
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
    http.get("*/api/v1/config", () => HttpResponse.json(validDraft)),
  );
  renderApp();

  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const sourceRow = within(tree).getByRole("treeitem", {
    name: /^legacy, Ready$/,
  });
  expect(within(sourceRow).getByLabelText("Configuration valid"))
    .toBeInTheDocument();
  expect(screen.queryByRole("heading", { name: "Validation" })).toBeNull();
  expect(screen.queryByText("This configuration is valid")).toBeNull();
});


test("keeps warning detail inline without error taint or a validation section", async () => {
  const snapshot = structuredClone(manageSnapshot);
  const replay = snapshot.nodes["resource:trafficreplays:replay"];
  replay.capabilities = [{
    kind: "edit",
    editTargetId: "edit:traffic.transform.configMap",
    label: "Edit replay",
  }];
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
  );
  renderApp();

  await enterEditMode();
  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const replayRow = within(tree).getByRole("treeitem", {
    name: /^replay, Running$/,
  });
  await userEvent.click(replayRow);

  expect(await within(replayRow).findByLabelText("1 validation warning"))
    .toBeInTheDocument();
  expect(replayRow).not.toHaveClass("validation-error-item");
  expect(replayRow).not.toHaveClass("validation-error-ancestor");
  expect(screen.queryByRole("heading", { name: "Validation" })).toBeNull();
  expect(screen.getByText("Selected key is not present.")).toBeInTheDocument();
});


test("taints validation errors and their configuration and navigation parents", async () => {
  const snapshot = structuredClone(manageSnapshot);
  const source = snapshot.nodes["resource:captureproxies:capture"];
  source.label = "legacy";
  source.resourcePlural = "sourceconfigs";
  source.resourceName = "legacy";
  source.diagnostics = [];
  source.parentId = "group:Sources:Sources";
  source.capabilities = [{
    kind: "edit",
    editTargetId: "edit:sourceClusters.legacy",
    label: "Edit legacy",
  }];
  snapshot.nodes["group:Sources:Sources"].childIds = [source.id];
  snapshot.nodes["group:Live Traffic Migration:Capture"].childIds = [];
  const invalidDraft = structuredClone(configDraft);
  const sourceCollection = invalidDraft.editState.nodes.find(
    (node) => node.id === "edit:sourceClusters",
  );
  const sourceEdit = sourceCollection?.children.find(
    (node) => node.id === "edit:sourceClusters.legacy",
  );
  const authentication = sourceEdit?.children.find(
    (node) => node.id === "edit:sourceClusters.legacy.authConfig",
  );
  const secret = authentication?.children.find(
    (node) => (
      node.id
      === "edit:sourceClusters.legacy.authConfig.basic.secretName"
    ),
  );
  if (!sourceCollection || !sourceEdit || !authentication || !secret) {
    throw new Error("Missing nested source fixture");
  }
  [sourceCollection, sourceEdit, authentication].forEach((node) => {
    node.status = "ok";
    node.statusCounts = {
      errors: 0,
      warnings: 0,
      required: 0,
      changed: 0,
      gated: 0,
      blocked: 0,
    };
  });
  secret.status = "required";
  secret.statusCounts = {
    errors: 0,
    warnings: 0,
    required: 1,
    changed: 0,
    gated: 0,
    blocked: 0,
  };
  secret.diagnostics = [{
    severity: "required",
    message: "Credentials secret is required.",
    path: secret.path,
  }];
  secret.label = "Credentials secret";
  secret.value = "";
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
    http.get("*/api/v1/config", () => HttpResponse.json(invalidDraft)),
  );
  renderApp();

  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const sourceSection = within(tree).getAllByRole("treeitem", {
    name: /^Sources,/,
  }).find((item) => item.getAttribute("aria-level") === "1");
  const sourceGroup = within(tree).getAllByRole("treeitem", {
    name: /^Sources,/,
  }).find((item) => item.getAttribute("aria-level") === "2");
  const sourceRow = within(tree).getByRole("treeitem", {
    name: /^legacy, Ready$/,
  });
  expect(sourceSection).toHaveClass("validation-error-ancestor");
  expect(sourceGroup).toHaveClass("validation-error-ancestor");
  expect(sourceRow).toHaveClass("validation-error-item");
  expect(within(sourceRow).getByLabelText("1 validation issue"))
    .toBeInTheDocument();

  const config = screen.getByRole("table", {
    name: "Configuration fields",
  });
  expect(within(config).getByRole("row", { name: /^Authentication/ }))
    .toHaveClass("validation-error-ancestor");
  expect(within(config).getByRole("row", { name: /^Credentials secret/ }))
    .toHaveClass("validation-error-item");
  expect(within(config).getByRole("row", { name: /^Endpoint/ }))
    .not.toHaveClass("validation-error-item", "validation-error-ancestor");
  expect(screen.queryByRole("heading", { name: "Validation" })).toBeNull();
});


test("identifies resources within a mixed-type navigation group", async () => {
  const snapshot = structuredClone(manageSnapshot);
  const section = snapshot.nodes["section:Live Traffic Migration"];
  const captureGroup = snapshot.nodes["group:Live Traffic Migration:Capture"];
  const capture = snapshot.nodes["resource:captureproxies:capture"];
  const bufferGroupId = "group:Live Traffic Migration:Buffer";
  const kafkaId = "resource:kafkaclusters:default";
  const s3Id = "resource:capturedtraffics:proxy-topic";
  section.childIds = [bufferGroupId, ...section.childIds];
  snapshot.nodes[bufferGroupId] = {
    ...captureGroup,
    id: bufferGroupId,
    revision: "buffer-group-1",
    childIds: [kafkaId, s3Id],
    label: "Buffer",
  };
  snapshot.nodes[kafkaId] = {
    ...capture,
    id: kafkaId,
    revision: "kafka-1",
    parentId: bufferGroupId,
    label: "default",
    valueSummary: "Configured",
    diagnostics: [],
    capabilities: [],
    resourcePlural: "kafkaclusters",
    resourceName: "default",
  };
  snapshot.nodes[s3Id] = {
    ...capture,
    id: s3Id,
    revision: "s3-1",
    parentId: bufferGroupId,
    label: "proxy-topic",
    valueSummary: "Configured",
    diagnostics: [],
    capabilities: [],
    resourcePlural: "capturedtraffics",
    resourceName: "proxy-topic",
  };
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
  );
  renderApp();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const kafka = within(tree).getByRole("treeitem", {
    name: /^default, Kafka cluster, Ready$/,
  });
  const s3 = within(tree).getByRole("treeitem", {
    name: /^proxy-topic, S3 source, Ready$/,
  });
  expect(within(kafka).getByText("Kafka cluster - Configured"))
    .toBeInTheDocument();
  expect(within(s3).getByText("S3 source - Configured"))
    .toBeInTheDocument();
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
  const sourceGroup = within(resourceNavigation)
    .getAllByRole("treeitem", { name: /^Sources,/ })
    .find((item) => item.getAttribute("aria-level") === "2");
  expect(sourceGroup).toBeDefined();
  await userEvent.click(await within(sourceGroup!).findByRole("button", {
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


test("adds a snapshot migration from its section without naming it first", async () => {
  const snapshot = structuredClone(manageSnapshot);
  const sectionId = "section:Snapshot Migration";
  const snapshotGroupId = "group:Snapshot Migration:Snapshot";
  const backfillGroupId = "group:Snapshot Migration:Backfill";
  snapshot.rootIds.splice(1, 0, sectionId);
  snapshot.nodes[sectionId] = {
    ...snapshot.nodes["section:Live Traffic Migration"],
    id: sectionId,
    revision: "snapshot-migration-section-1",
    parentId: null,
    childIds: [snapshotGroupId, backfillGroupId],
    label: "Snapshot Migration",
    status: "warning",
  };
  snapshot.nodes[snapshotGroupId] = {
    ...snapshot.nodes["group:Live Traffic Migration:Capture"],
    id: snapshotGroupId,
    revision: "snapshot-group-1",
    parentId: sectionId,
    childIds: [],
    label: "Snapshot",
    status: "warning",
  };
  snapshot.nodes[backfillGroupId] = {
    ...snapshot.nodes["group:Live Traffic Migration:Capture"],
    id: backfillGroupId,
    revision: "backfill-group-1",
    parentId: sectionId,
    childIds: [],
    label: "Backfill",
    status: "warning",
  };

  const initialDraft = structuredClone(configDraft);
  const counts = {
    errors: 0,
    warnings: 0,
    required: 0,
    changed: 0,
    gated: 0,
    blocked: 0,
  };
  const addCommand = {
    id: "edit:snapshotMigrationConfigs:add",
    path: ["snapshotMigrationConfigs"],
    label: "+ Add snapshot migration",
    valueKind: "command" as const,
    status: "ok",
    statusCounts: counts,
    command: {
      requiresName: false,
      editAdded: false,
      autoEditAdded: true,
    },
    diagnostics: [],
    children: [],
  };
  const collection = {
    id: "edit:snapshotMigrationConfigs",
    path: ["snapshotMigrationConfigs"],
    label: "Backfill",
    valueKind: "array" as const,
    status: "warning",
    statusCounts: { ...counts, warnings: 1 },
    inputHint: {
      kind: "array" as const,
      addLabel: "snapshot migration",
    },
    diagnostics: [{
      severity: "warning" as const,
      message: "Define a source snapshot before configuring migration passes.",
      path: ["snapshotMigrationConfigs"],
    }],
    children: [addCommand],
  };
  initialDraft.editState.nodes.push({
    id: "edit:snapshotMigration",
    path: ["snapshotMigration"],
    label: "Snapshot Migration",
    valueKind: "object",
    status: "warning",
    statusCounts: { ...counts, warnings: 1 },
    diagnostics: [],
    children: [collection],
  });

  const updatedDraft = structuredClone(initialDraft);
  const updatedCollection = updatedDraft.editState.nodes.at(-1)?.children[0];
  if (!updatedCollection) throw new Error("Missing snapshot collection");
  updatedCollection.children = [{
    id: "edit:snapshotMigrationConfigs.0",
    path: ["snapshotMigrationConfigs", "0"],
    label: "snapshot migration: <source> -> <target>",
    valueKind: "object",
    removable: true,
    status: "required",
    statusCounts: { ...counts, required: 2 },
    diagnostics: [],
    children: [{
      id: "edit:snapshotMigrationConfigs.0.fromSource",
      path: ["snapshotMigrationConfigs", "0", "fromSource"],
      label: "From source",
      value: "",
      valueKind: "scalar",
      valueType: "string",
      required: true,
      status: "required",
      statusCounts: { ...counts, required: 1 },
      inputHint: {
        kind: "reference",
        options: [{
          label: "foo",
          value: "foo",
          description: "No snapshots defined",
        }],
      },
      diagnostics: [{
        severity: "required",
        message: "fromSource is required.",
        path: ["snapshotMigrationConfigs", "0", "fromSource"],
      }],
      children: [],
    }, {
      id: "edit:snapshotMigrationConfigs.0.toTarget",
      path: ["snapshotMigrationConfigs", "0", "toTarget"],
      label: "To target",
      value: "",
      valueKind: "scalar",
      valueType: "string",
      required: true,
      status: "required",
      statusCounts: { ...counts, required: 1 },
      diagnostics: [{
        severity: "required",
        message: "toTarget is required.",
        path: ["snapshotMigrationConfigs", "0", "toTarget"],
      }],
      children: [],
    }, {
      id: "edit:snapshotMigrationConfigs.0.perSnapshotConfig",
      path: ["snapshotMigrationConfigs", "0", "perSnapshotConfig"],
      label: "perSnapshotConfig: 1 configured, 0 unconfigured",
      valueKind: "record",
      status: "required",
      statusCounts: { ...counts, required: 1 },
      diagnostics: [],
      children: [{
        id: "edit:snapshotMigrationConfigs.0.perSnapshotConfig.snap1",
        path: [
          "snapshotMigrationConfigs",
          "0",
          "perSnapshotConfig",
          "snap1",
        ],
        label: "snap1: 1 item",
        valueKind: "array",
        status: "required",
        statusCounts: { ...counts, required: 1 },
        diagnostics: [],
        children: [{
          id: "edit:snapshotMigrationConfigs.0.perSnapshotConfig.snap1.0",
          path: [
            "snapshotMigrationConfigs",
            "0",
            "perSnapshotConfig",
            "snap1",
            "0",
          ],
          label: "migration pass 1: choose metadata and/or document backfill",
          valueKind: "object",
          status: "required",
          statusCounts: { ...counts, required: 1 },
          diagnostics: [{
            severity: "required",
            message: "Add metadata migration, document backfill, or both.",
            path: [
              "snapshotMigrationConfigs",
              "0",
              "perSnapshotConfig",
              "snap1",
              "0",
            ],
          }],
          children: [{
            id: "edit:snapshotMigrationConfigs.0.perSnapshotConfig.snap1.0.metadataMigrationConfig:add",
            path: [
              "snapshotMigrationConfigs",
              "0",
              "perSnapshotConfig",
              "snap1",
              "0",
              "metadataMigrationConfig",
            ],
            label: "+ Add metadata migration",
            valueKind: "command",
            status: "ok",
            statusCounts: counts,
            command: {
              requiresName: false,
              editAdded: false,
              autoEditAdded: false,
            },
            diagnostics: [],
            children: [],
          }, {
            id: "edit:snapshotMigrationConfigs.0.perSnapshotConfig.snap1.0.documentBackfillConfig:add",
            path: [
              "snapshotMigrationConfigs",
              "0",
              "perSnapshotConfig",
              "snap1",
              "0",
              "documentBackfillConfig",
            ],
            label: "+ Add document backfill",
            valueKind: "command",
            status: "ok",
            statusCounts: counts,
            command: {
              requiresName: false,
              editAdded: false,
              autoEditAdded: false,
            },
            diagnostics: [],
            children: [],
          }],
        }],
      }],
    }],
  }, addCommand];
  updatedDraft.dirty = true;
  updatedDraft.draftRevision = "config-draft-snapshot-added";

  const configuredDraft = structuredClone(updatedDraft);
  const configuredPass = configuredDraft.editState.nodes.at(-1)
    ?.children[0]
    ?.children[0]
    ?.children[2]
    ?.children[0]
    ?.children[0];
  if (!configuredPass) throw new Error("Missing configured migration pass");
  const passPath = [
    "snapshotMigrationConfigs",
    "0",
    "perSnapshotConfig",
    "snap1",
    "0",
  ];
  configuredPass.children = [{
    id: `edit:${[...passPath, "metadataMigrationConfig"].join(".")}`,
    path: [...passPath, "metadataMigrationConfig"],
    label: "Metadata migration config: 0 settings",
    value: {},
    valueAuthored: true,
    valueKind: "object",
    presence: "optional",
    essential: true,
    removable: true,
    status: "ok",
    statusCounts: counts,
    diagnostics: [],
    children: [],
  }, {
    id: `edit:${[...passPath, "documentBackfillConfig"].join(".")}`,
    path: [...passPath, "documentBackfillConfig"],
    label: "Document backfill config: 0 settings",
    value: {},
    valueAuthored: true,
    valueKind: "object",
    presence: "optional",
    essential: true,
    removable: true,
    status: "ok",
    statusCounts: counts,
    diagnostics: [],
    children: [],
  }];
  configuredDraft.draftRevision = "config-draft-snapshot-configured";

  const operations: unknown[] = [];
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
    http.get("*/api/v1/config", () => HttpResponse.json(initialDraft)),
    http.post("*/api/v1/config/removal-impact", async ({ request }) => {
      const body = await request.json() as { path: string[] };
      return HttpResponse.json({
        targetPath: body.path,
        targetLabel: body.path.at(-1) ?? "",
        affected: [],
      });
    }),
    http.post("*/api/v1/config/operations", async ({ request }) => {
      operations.push(
        (await request.json() as { operation: unknown }).operation,
      );
      return HttpResponse.json(
        operations.length >= 3 ? configuredDraft : updatedDraft,
      );
    }),
  );
  renderApp();
  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const section = within(tree).getByRole("treeitem", {
    name: /^Snapshot Migration,/,
  });
  expect(within(section).getByRole("button", {
    name: "Add snapshot migration",
  })).toBeInTheDocument();
  expect(within(tree).queryByRole("textbox", {
    name: "snapshot migration name",
  })).toBeNull();

  await userEvent.click(within(section).getByRole("button", {
    name: "Add snapshot migration",
  }));

  await waitFor(() => expect(operations).toEqual([{
    op: "add",
    path: ["snapshotMigrationConfigs"],
    value: {},
  }]));
  expect(await within(tree).findByRole("treeitem", {
    name: /^migration-1, Addition pending submission$/,
  })).toHaveAttribute("aria-selected", "true");
  expect(await screen.findByRole("heading", {
    name: "Edit migration-1",
  })).toBeInTheDocument();
  expect(screen.queryByRole("textbox", { name: "From source" }))
    .not.toBeInTheDocument();
  expect(screen.getByRole("combobox", { name: "From source" }))
    .toHaveDisplayValue("Select a value");
  expect(screen.getByRole("option", { name: "foo" })).toBeInTheDocument();
  expect(screen.getByRole("textbox", { name: "To target" }))
    .toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Add metadata migration" }))
    .toBeVisible();
  expect(screen.getByRole("button", { name: "Add document backfill" }))
    .toBeVisible();

  await userEvent.click(screen.getByRole("button", {
    name: "Add metadata migration",
  }));
  await waitFor(() => expect(operations.at(-1)).toEqual({
    op: "add",
    path: [
      "snapshotMigrationConfigs",
      "0",
      "perSnapshotConfig",
      "snap1",
      "0",
      "metadataMigrationConfig",
    ],
    value: {},
  }));

  await userEvent.click(screen.getByRole("button", {
    name: "Add document backfill",
  }));
  await waitFor(() => expect(operations.at(-1)).toEqual({
    op: "add",
    path: [
      "snapshotMigrationConfigs",
      "0",
      "perSnapshotConfig",
      "snap1",
      "0",
      "documentBackfillConfig",
    ],
    value: {},
  }));

  await userEvent.click(screen.getByRole("button", {
    name: "Remove Metadata migration config",
  }));
  await userEvent.click(await screen.findByRole("button", {
    name: "Confirm removal",
  }));
  await waitFor(() => expect(operations.at(-1)).toEqual({
    op: "removeConfig",
    path: [...passPath, "metadataMigrationConfig"],
  }));

  await userEvent.click(screen.getByRole("button", {
    name: "Remove Document backfill config",
  }));
  await userEvent.click(await screen.findByRole("button", {
    name: "Confirm removal",
  }));
  await waitFor(() => expect(operations.at(-1)).toEqual({
    op: "removeConfig",
    path: [...passPath, "documentBackfillConfig"],
  }));
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

  const legacyRow = within(configTree).getByRole("row", { name: /^legacy/ });
  await userEvent.click(legacyRow);
  await userEvent.click(within(legacyRow).getByRole("button", {
    name: "Rename legacy",
  }));
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
  expect(screen.queryByText(
    "Generated from the source snapshot definitions.",
  )).toBeNull();
  await userEvent.click(screen.getByRole("checkbox", {
    name: "Show field documentation",
  }));
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


test("shows a newly added resource while the server operation is pending", async () => {
  let releaseOperation: (() => void) | null = null;
  const operationStarted = new Promise<void>((resolve) => {
    releaseOperation = resolve;
  });
  const updatedDraft = structuredClone(configDraft);
  const sources = updatedDraft.editState.nodes.find(
    (node) => node.id === "edit:sourceClusters",
  );
  if (!sources) throw new Error("Missing source collection fixture");
  const addCommand = sources.children.find(
    (node) => node.id === "edit:sourceClusters:add",
  );
  if (!addCommand) throw new Error("Missing source add command fixture");
  sources.children = [
    ...sources.children.filter((node) => node !== addCommand),
    {
      id: "edit:sourceClusters.immediate",
      path: ["sourceClusters", "immediate"],
      label: "immediate",
      valueKind: "object",
      status: "required",
      statusCounts: {
        required: 1,
        errors: 0,
        warnings: 0,
        changed: 0,
        gated: 0,
        blocked: 0,
      },
      diagnostics: [],
      children: [{
        id: "edit:sourceClusters.immediate.endpoint",
        path: ["sourceClusters", "immediate", "endpoint"],
        label: "Endpoint",
        valueKind: "scalar",
        valueType: "string",
        presence: "required",
        required: true,
        status: "required",
        statusCounts: {
          required: 1,
          errors: 0,
          warnings: 0,
          changed: 0,
          gated: 0,
          blocked: 0,
        },
        diagnostics: [{
          severity: "required",
          message: "endpoint is required.",
          path: ["sourceClusters", "immediate", "endpoint"],
        }],
        children: [],
      }],
    },
    addCommand,
  ];
  updatedDraft.dirty = true;
  updatedDraft.draftRevision = "config-draft-immediate";

  server.use(
    http.post("*/api/v1/config/operations", async () => {
      await operationStarted;
      return HttpResponse.json(updatedDraft);
    }),
  );
  renderApp();
  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const sourceGroup = screen.getAllByRole("treeitem", {
    name: /^Sources,/,
  }).find((item) => item.getAttribute("aria-level") === "2");
  expect(sourceGroup).toBeDefined();
  const previousSelection = screen.getByRole("treeitem", {
    name: /^capture, Ready$/,
  });
  await userEvent.click(await within(sourceGroup!).findByRole("button", {
    name: "Add source cluster",
  }));
  const nameInput = within(tree).getByRole("textbox", {
    name: "source cluster name",
  });
  expect(nameInput).toHaveFocus();
  expect(previousSelection).toHaveAttribute("aria-selected", "false");
  await userEvent.type(
    nameInput,
    "immediate",
  );
  await userEvent.keyboard("{Enter}");

  expect(await within(tree).findByRole("treeitem", {
    name: /^immediate, Syncing configuration$/,
  })).toHaveAttribute("aria-selected", "true");
  expect(screen.getByText("Preparing immediate configuration")).toBeVisible();
  expect(screen.queryByRole("textbox", {
    name: "source cluster name",
  })).toBeNull();

  releaseOperation?.();
  expect(await within(tree).findByRole("treeitem", {
    name: /^immediate, Addition pending submission$/,
  })).toHaveAttribute("aria-selected", "true");
  expect(await screen.findByRole("heading", {
    name: "Edit immediate",
  })).toBeInTheDocument();
  expect(screen.getByRole("textbox", { name: "Endpoint" }))
    .toBeInTheDocument();
});


test("cancels inline resource naming and restores tree selection and focus", async () => {
  renderApp();
  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const capture = within(tree).getByRole("treeitem", {
    name: /^capture, Ready$/,
  });
  capture.focus();
  const sourceGroup = screen.getAllByRole("treeitem", {
    name: /^Sources,/,
  }).find((item) => item.getAttribute("aria-level") === "2");
  expect(sourceGroup).toBeDefined();

  await userEvent.click(within(sourceGroup!).getByRole("button", {
    name: "Add source cluster",
  }));
  const nameInput = within(tree).getByRole("textbox", {
    name: "source cluster name",
  });
  expect(nameInput).toHaveFocus();
  const existingSource = within(tree).getByRole("treeitem", {
    name: /^legacy, Addition pending submission$/,
  });
  expect(existingSource.compareDocumentPosition(
    nameInput.closest('[role="treeitem"]'),
  )).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  expect(capture).toHaveAttribute("aria-selected", "false");

  await userEvent.keyboard("{Escape}");

  expect(within(tree).queryByRole("textbox", {
    name: "source cluster name",
  })).toBeNull();
  expect(capture).toHaveAttribute("aria-selected", "true");
  expect(capture).toHaveFocus();
  expect(screen.getByRole("heading", { name: "Edit capture" }))
    .toBeInTheDocument();
});


test("abandons inline resource naming when focus moves elsewhere", async () => {
  const operations: unknown[] = [];
  const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
  server.use(
    http.post("*/api/v1/config/operations", async ({ request }) => {
      const body = await request.json() as { operation: unknown };
      operations.push(body.operation);
      return HttpResponse.json(configDraft);
    }),
  );
  renderApp();
  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const sourceGroup = screen.getAllByRole("treeitem", {
    name: /^Sources,/,
  }).find((item) => item.getAttribute("aria-level") === "2");
  expect(sourceGroup).toBeDefined();

  await userEvent.click(within(sourceGroup!).getByRole("button", {
    name: "Add source cluster",
  }));
  await userEvent.type(within(tree).getByRole("textbox", {
    name: "source cluster name",
  }), "abandoned");
  const optional = screen.getByRole("checkbox", {
    name: "Show optional fields",
  });
  await userEvent.click(optional);

  expect(optional).toBeChecked();
  expect(within(tree).queryByRole("textbox", {
    name: "source cluster name",
  })).toBeNull();
  expect(operations).toEqual([]);

  await userEvent.click(within(sourceGroup!).getByRole("button", {
    name: "Add source cluster",
  }));
  await userEvent.type(within(tree).getByRole("textbox", {
    name: "source cluster name",
  }), "also-abandoned");
  await userEvent.click(screen.getByRole("button", {
    name: "Exit editing",
  }));

  expect(confirm).not.toHaveBeenCalled();
  expect(operations).toEqual([]);
  expect(screen.getByRole("button", { name: "Edit configuration" }))
    .toBeInTheDocument();
  confirm.mockRestore();
});


test("renames a named resource from the tree and follows its new identity", async () => {
  let operation: unknown;
  const renamedDraft = structuredClone(configDraft);
  const sourceCollection = renamedDraft.editState.nodes.find(
    (node) => node.id === "edit:sourceClusters",
  );
  const source = sourceCollection?.children.find(
    (node) => node.id === "edit:sourceClusters.legacy",
  );
  if (!source) throw new Error("Missing source fixture");
  const rewritePath = (node: typeof source) => {
    node.id = node.id.replace(
      "edit:sourceClusters.legacy",
      "edit:sourceClusters.modern",
    );
    node.path = node.path.map((part, index) => (
      index === 1 && part === "legacy" ? "modern" : part
    ));
    node.children?.forEach(rewritePath);
  };
  rewritePath(source);
  source.label = "modern";
  renamedDraft.dirty = true;
  renamedDraft.draftRevision = "config-draft-modern";

  server.use(
    http.post("*/api/v1/config/operations", async ({ request }) => {
      const body = await request.json() as { operation: unknown };
      operation = body.operation;
      await new Promise((resolve) => window.setTimeout(resolve, 20));
      return HttpResponse.json(renamedDraft);
    }),
  );
  renderApp();
  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const legacy = within(tree).getByRole("treeitem", {
    name: /^legacy, Addition pending submission$/,
  });
  await userEvent.click(within(legacy).getByRole("button", {
    name: "Rename legacy",
  }));
  const name = within(tree).getByRole("textbox", {
    name: "New name for legacy",
  });
  expect(name).toHaveValue("legacy");
  expect(name).toHaveAttribute("pattern", "^[a-z0-9-]+$");
  expect(name).toHaveFocus();
  await userEvent.clear(name);
  await userEvent.type(name, "modern{Enter}");

  expect(await within(tree).findByRole("treeitem", {
    name: /^modern, Syncing configuration$/,
  })).toHaveAttribute("aria-selected", "true");
  expect(screen.getByText("Preparing modern configuration")).toBeVisible();
  expect(operation).toEqual({
    op: "renameConfig",
    path: ["sourceClusters", "legacy"],
    newName: "modern",
  });

  expect(await within(tree).findByRole("treeitem", {
    name: /^modern, Rename pending submission$/,
  })).toHaveAttribute("aria-selected", "true");
  expect(within(tree).queryByRole("treeitem", {
    name: /^legacy,/,
  })).toBeNull();
  expect(await screen.findByRole("heading", {
    name: "Edit modern",
  })).toBeInTheDocument();
  expect(screen.getByRole("textbox", { name: "Endpoint" }))
    .toHaveValue("https://legacy.example.com:9200");
});


test("restores a resource after a tree rename is rejected", async () => {
  server.use(
    http.post("*/api/v1/config/operations", () =>
      HttpResponse.json(
        { detail: "Config entry already exists at sourceClusters.modern" },
        { status: 409 },
      ),
    ),
  );
  renderApp();
  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const legacy = within(tree).getByRole("treeitem", {
    name: /^legacy, Addition pending submission$/,
  });
  await userEvent.click(within(legacy).getByRole("button", {
    name: "Rename legacy",
  }));
  const name = within(tree).getByRole("textbox", {
    name: "New name for legacy",
  });
  await userEvent.clear(name);
  await userEvent.type(name, "modern{Enter}");

  expect(await within(tree).findByRole("treeitem", {
    name: /^legacy, Addition pending submission$/,
  })).toHaveAttribute("aria-selected", "true");
  expect(screen.getByRole("alert")).toHaveTextContent(
    "Config entry already exists at sourceClusters.modern",
  );
  expect(screen.getByRole("heading", { name: "Edit legacy" }))
    .toBeInTheDocument();
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
      name: /^transform 1 Authored/,
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


test("reviews and tracks submission while leaving edit mode", async () => {
  let submitRequest: unknown;
  let submitAccepted = false;
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
    http.post("*/api/v1/config/review", () => HttpResponse.json({
      draftRevision: validDraft.draftRevision,
      baseRevision: validDraft.baseRevision,
      dirty: true,
      valid: true,
      validationMessages: [],
      changes: [{
        resourceId: "resource:captureproxies:capture",
        resourceLabel: "capture",
        path: "serviceType",
        label: "Service type",
        kind: "field",
      }],
    })),
    http.post("*/api/v1/config/submit", async ({ request }) => {
      submitRequest = await request.json();
      submitAccepted = true;
      return HttpResponse.json({
        id: "operation-submit",
        kind: "submit",
        label: "Submit workflow configuration",
        status: "waiting",
        targetIds: ["resource:captureproxies:capture"],
        createdAt: "2026-08-13T13:00:00Z",
        updatedAt: "2026-08-13T13:00:01Z",
        message: "Workflow accepted; waiting for refreshed cluster state",
        detail: null,
        result: { workflowName: "migration" },
      }, { status: 202 });
    }),
    http.get("*/api/v1/operations", () => HttpResponse.json({
      operations: submitAccepted ? [{
        id: "operation-submit",
        kind: "submit",
        label: "Submit workflow configuration",
        status: "waiting",
        targetIds: ["resource:captureproxies:capture"],
        createdAt: "2026-08-13T13:00:00Z",
        updatedAt: "2026-08-13T13:00:01Z",
        message: "Workflow accepted; waiting for refreshed cluster state",
        detail: null,
        result: { workflowName: "migration" },
      }] : [],
    })),
  );
  renderApp();
  await enterEditMode();

  await userEvent.click(screen.getByRole("button", {
    name: "Save and submit",
  }));
  const dialog = await screen.findByRole("dialog", {
    name: "Submit configuration?",
  });
  expect(within(dialog).getByText("capture")).toBeInTheDocument();
  expect(within(dialog).getByText("Service type")).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole("button", {
    name: "Confirm submit",
  }));

  await waitFor(() => expect(submitRequest).toEqual({
    expectedDraftRevision: "dirty-to-submit",
  }));
  expect(await screen.findByRole("button", { name: "Edit configuration" }))
    .toBeInTheDocument();
  expect(screen.queryByText("Editing configuration")).toBeNull();
  expect(await screen.findByText(
    "Workflow accepted; waiting for refreshed cluster state",
  )).toBeInTheDocument();
});


test("reviews and submits saved pending changes without entering edit mode", async () => {
  let submitRequest: unknown;
  let submitAccepted = false;
  const savedDraft = structuredClone(configDraft);
  savedDraft.dirty = false;
  savedDraft.draftRevision = "saved-pending-revision";
  savedDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.get("*/api/v1/config", () => HttpResponse.json(savedDraft)),
    http.post("*/api/v1/config/review", () => HttpResponse.json({
      draftRevision: savedDraft.draftRevision,
      baseRevision: savedDraft.baseRevision,
      dirty: false,
      valid: true,
      validationMessages: [],
      changes: [{
        resourceId: "resource:captureproxies:capture",
        resourceLabel: "capture",
        path: "serviceType",
        label: "Service type",
        kind: "field",
      }],
    })),
    http.post("*/api/v1/config/submit", async ({ request }) => {
      submitRequest = await request.json();
      submitAccepted = true;
      return HttpResponse.json({
        id: "operation-submit-read-only",
        kind: "submit",
        label: "Submit workflow configuration",
        status: "waiting",
        targetIds: ["resource:captureproxies:capture"],
        createdAt: "2026-08-13T14:00:00Z",
        updatedAt: "2026-08-13T14:00:01Z",
        message: "Workflow accepted; waiting for refreshed cluster state",
        detail: null,
        result: { workflowName: "migration" },
      }, { status: 202 });
    }),
    http.get("*/api/v1/operations", () => HttpResponse.json({
      operations: submitAccepted ? [{
        id: "operation-submit-read-only",
        kind: "submit",
        label: "Submit workflow configuration",
        status: "waiting",
        targetIds: ["resource:captureproxies:capture"],
        createdAt: "2026-08-13T14:00:00Z",
        updatedAt: "2026-08-13T14:00:01Z",
        message: "Workflow accepted; waiting for refreshed cluster state",
        detail: null,
        result: { workflowName: "migration" },
      }] : [],
    })),
  );
  renderApp();

  await userEvent.click(await screen.findByRole("button", {
    name: "Review and submit",
  }));
  const dialog = await screen.findByRole("dialog", {
    name: "Submit configuration?",
  });
  expect(within(dialog).getByText("capture")).toBeInTheDocument();
  expect(within(dialog).getByText("Service type")).toBeInTheDocument();
  expect(screen.queryByText("Editing configuration")).toBeNull();
  await userEvent.click(within(dialog).getByRole("button", {
    name: "Confirm submit",
  }));

  await waitFor(() => expect(submitRequest).toEqual({
    expectedDraftRevision: "saved-pending-revision",
  }));
  expect(screen.queryByText("Editing configuration")).toBeNull();
  expect(await screen.findByText(
    "Workflow accepted; waiting for refreshed cluster state",
  )).toBeInTheDocument();
});


test("does not offer submission when the snapshot has no pending config", async () => {
  const currentState = structuredClone(manageSnapshot);
  const capture = currentState.nodes["resource:captureproxies:capture"];
  capture.valueSummary = "Deployed";
  capture.comparisons = capture.comparisons.map((comparison) => ({
    ...comparison,
    pending: comparison.submitted,
    pendingChanged: false,
  }));
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(currentState)),
  );
  renderApp();

  expect(await screen.findByRole("button", {
    name: "Edit configuration",
  })).toBeInTheDocument();
  expect(screen.queryByRole("button", {
    name: "Review and submit",
  })).toBeNull();
});


test("offers submission for a pending resource addition without field diffs", async () => {
  const pendingState = structuredClone(manageSnapshot);
  const capture = pendingState.nodes["resource:captureproxies:capture"];
  capture.valueSummary = "Addition pending submission";
  capture.comparisons = [];
  const validDraft = structuredClone(configDraft);
  validDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(pendingState)),
    http.get("*/api/v1/config", () => HttpResponse.json(validDraft)),
  );
  renderApp();

  await waitFor(() => expect(screen.getByRole("button", {
    name: "Review and submit",
  })).toBeEnabled());
});


test("blocks pending config submission with a visible validation reason", async () => {
  renderApp();

  const submit = await screen.findByRole("button", {
    name: "Review and submit",
  });
  await waitFor(() => expect(submit).toBeDisabled());
  expect(screen.getByText("1 configuration error")).toBeInTheDocument();
  expect(submit).toHaveAttribute(
    "title",
    "Resolve 1 configuration error before submitting",
  );
});
