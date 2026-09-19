import { projectConfigYaml } from "@opensearch-migrations/config-edit-core";
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
import { parse } from "yaml";

import {
  getHealth,
  type ManageNode,
  type ManageSnapshot,
} from "../api/client";
import {
  configDraft,
  manageSnapshot,
  type ConfigDraft,
} from "../test/fixtures";
import { server } from "../test/server";
import {
  BROWSER_CONFIG_DRAFT_QUERY_KEY,
  markBrowserConfigDraftStale,
  type BrowserConfigDraft,
} from "../features/configuration/browserDraft";
import { App } from "./App";


function configFromEditNodes(draft: ConfigDraft): unknown {
  const config: Record<string, unknown> = {};
  const assign = (path: string[], value: unknown) => {
    let current: Record<string, unknown> | unknown[] = config;
    path.forEach((part, index) => {
      const last = index === path.length - 1;
      const numeric = /^\d+$/.test(part);
      if (last) {
        if (Array.isArray(current) && numeric) {
          current[Number(part)] = structuredClone(value);
        } else {
          (current as Record<string, unknown>)[part] = structuredClone(value);
        }
        return;
      }
      const nextNumeric = /^\d+$/.test(path[index + 1]);
      const container = nextNumeric ? [] : {};
      if (Array.isArray(current) && numeric) {
        current[Number(part)] ??= container;
        current = current[Number(part)] as Record<string, unknown> | unknown[];
      } else {
        const record = current as Record<string, unknown>;
        record[part] ??= container;
        current = record[part] as Record<string, unknown> | unknown[];
      }
    });
  };
  const visit = (node: ConfigDraft["editState"]["nodes"][number]) => {
    if (
      node.path.length > 0
      && node.value !== undefined
      && node.valueKind !== "command"
      && node.children.length === 0
    ) {
      assign(node.path, node.value);
    } else if (
      node.path.length > 0
      && node.valueKind === "object"
      && node.children.length === 0
    ) {
      assign(node.path, {});
    }
    node.children.forEach(visit);
  };
  draft.editState.nodes.forEach(visit);
  return config;
}


function browserDraftFixture(draft: ConfigDraft): BrowserConfigDraft {
  const config = (
    draft.rawYaml
    && draft.editState.provenance.mode === "structured"
  )
    ? parse(draft.rawYaml) as unknown
    : configFromEditNodes(draft);
  const rawDocument = JSON.stringify(config, null, 2) + "\n";
  return {
    baseRevision: draft.baseRevision,
    draftRevision: draft.draftRevision,
    dirty: draft.dirty,
    editState: structuredClone(draft.editState),
    navigation: structuredClone(draft.navigation ?? null),
    rawYaml: draft.editState.provenance.mode === "raw"
      ? draft.rawYaml ?? undefined
      : undefined,
    notices: [...(draft.notices ?? [])],
    baseStale: false,
    baseEditState: structuredClone(draft.editState),
    config,
    persistedRevision: draft.baseRevision,
    rawDocument: draft.rawYaml ?? rawDocument,
    savedRawDocument: rawDocument,
  };
}


function renderApp(initialDraft: ConfigDraft | null = configDraft) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });
  if (initialDraft) {
    client.setQueryData(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
      browserDraftFixture(initialDraft),
    );
  }
  const view = render(
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>,
  );
  return { ...view, client };
}


function configurationDocument(persistedRevision: string) {
  return {
    modelVersion: "1",
    persistedRevision,
    rawYaml: "{}\n",
  };
}


async function enterEditMode() {
  await userEvent.click(
    await screen.findByRole("button", { name: "Edit configuration" }),
  );
}


function configurationNavigation(snapshot: ManageSnapshot): ManageSnapshot {
  const navigation = structuredClone(snapshot);
  const stepIds = new Set(Object.values(navigation.nodes)
    .filter((node) => node.kind === "workflow-step")
    .map((node) => node.id));
  stepIds.forEach((nodeId) => delete navigation.nodes[nodeId]);
  Object.values(navigation.nodes).forEach((node) => {
    node.childIds = node.childIds.filter((nodeId) => !stepIds.has(nodeId));
  });
  navigation.rootIds = navigation.rootIds.filter(
    (nodeId) => !stepIds.has(nodeId),
  );
  return navigation;
}


function setNavigation(
  draft: ConfigDraft,
  snapshot: ManageSnapshot = manageSnapshot,
): ManageSnapshot {
  const navigation = configurationNavigation(snapshot);
  draft.navigation = navigation;
  return navigation;
}


function ensureNavigationGroup(
  navigation: ManageSnapshot,
  {
    sectionId,
    sectionLabel,
    groupId,
    groupLabel,
  }: {
    sectionId: string;
    sectionLabel: string;
    groupId: string;
    groupLabel: string;
  },
) {
  if (!navigation.nodes[sectionId]) {
    navigation.nodes[sectionId] = {
      id: sectionId,
      revision: `test:${sectionId}`,
      parentId: null,
      childIds: [],
      kind: "section",
      label: sectionLabel,
      description: null,
      status: "ok",
      phase: null,
      valueSummary: null,
      diagnostics: [],
      capabilities: [],
      details: [],
      relationships: [],
      comparisons: [],
      resourcePlural: null,
      resourceName: null,
      resourceType: null,
      configPresence: {},
    };
    navigation.rootIds.push(sectionId);
  }
  const section = navigation.nodes[sectionId];
  if (!navigation.nodes[groupId]) {
    navigation.nodes[groupId] = {
      id: groupId,
      revision: `test:${groupId}`,
      parentId: sectionId,
      childIds: [],
      kind: "group",
      label: groupLabel,
      description: null,
      status: "ok",
      phase: null,
      valueSummary: null,
      diagnostics: [],
      capabilities: [],
      details: [],
      relationships: [],
      comparisons: [],
      resourcePlural: null,
      resourceName: null,
      resourceType: null,
      configPresence: {},
    };
  }
  if (!section.childIds.includes(groupId)) section.childIds.push(groupId);
}


function addConfigNavigationResource(
  navigation: ManageSnapshot,
  {
    id,
    groupId,
    label,
    editTargetId,
    resourcePlural,
    resourceType,
    status = "changed",
    valueSummary = "Addition pending submission",
    diagnostics = [],
  }: {
    id: string;
    groupId: string;
    label: string;
    editTargetId: string;
    resourcePlural: string;
    resourceType: string;
    status?: string;
    valueSummary?: string;
    diagnostics?: ManageNode["diagnostics"];
  },
) {
  const group = navigation.nodes[groupId];
  if (!group) throw new Error(`Missing test navigation group ${groupId}`);
  navigation.nodes[id] = {
    id,
    revision: `test:${id}`,
    parentId: groupId,
    childIds: [],
    kind: "resource",
    label,
    description: `${resourcePlural}/${label}`,
    status,
    phase: "Pending Config",
    valueSummary,
    diagnostics,
    capabilities: [{
      kind: "edit",
      editTargetId,
      label: `Edit ${label}`,
    }],
    details: [{
      label: "Phase",
      value: "Pending Config",
      kind: "phase",
    }],
    relationships: [],
    comparisons: [],
    resourcePlural,
    resourceName: label,
    resourceType,
    configPresence: {
      deployed: false,
      pending: true,
    },
  };
  if (!group.childIds.includes(id)) group.childIds.push(id);
}


function addLegacySourceNavigation(draft: ConfigDraft): ConfigDraft {
  const navigation = setNavigation(draft);
  addConfigNavigationResource(navigation, {
    id: "resource:sourceconfigs:legacy",
    groupId: "group:Sources:Sources",
    label: "legacy",
    editTargetId: "edit:sourceClusters.legacy",
    resourcePlural: "sourceconfigs",
    resourceType: "Source cluster",
  });
  return draft;
}


function runtimeSourceSnapshot(): ManageSnapshot {
  const snapshot = structuredClone(manageSnapshot);
  const source = structuredClone(
    snapshot.nodes["resource:captureproxies:capture"],
  );
  Object.assign(source, {
    id: "resource:sourceconfigs:legacy",
    revision: "legacy-source-1",
    parentId: "group:Sources:Sources",
    childIds: [],
    label: "legacy",
    description: "sourceconfigs/legacy",
    status: "ok",
    phase: "Ready",
    valueSummary: null,
    diagnostics: [],
    capabilities: [{
      kind: "edit",
      editTargetId: "edit:sourceClusters.legacy",
      label: "Edit legacy",
    }],
    details: [],
    relationships: [],
    comparisons: [],
    resourcePlural: "sourceconfigs",
    resourceName: "legacy",
    resourceType: "Source cluster",
  });
  snapshot.nodes[source.id] = source;
  snapshot.nodes["group:Sources:Sources"].childIds = [source.id];
  return snapshot;
}


function addSourceDefinitionCollection(
  draft: ConfigDraft,
  {
    addLabel,
    collectionName,
    groupLabel,
    groupOrder,
    typeLabel,
  }: {
    addLabel: string;
    collectionName: string;
    groupLabel: string;
    groupOrder: number;
    typeLabel: string;
  },
) {
  const sourceEdit = draft.editState.nodes
    .flatMap((node) => node.children)
    .find((node) => node.id === "edit:sourceClusters.legacy");
  if (!sourceEdit) throw new Error("Missing source edit node");
  let snapshotInfo = sourceEdit.children.find(
    (node) => node.id === "edit:sourceClusters.legacy.snapshotInfo",
  );
  if (!snapshotInfo) {
    snapshotInfo = {
      id: "edit:sourceClusters.legacy.snapshotInfo",
      path: ["sourceClusters", "legacy", "snapshotInfo"],
      label: "Snapshot information",
      valueKind: "object",
      status: "ok",
      diagnostics: [],
      children: [],
    };
    sourceEdit.children.push(snapshotInfo);
  }
  const collectionTargetId = [
    "edit:sourceClusters.legacy.snapshotInfo",
    collectionName,
  ].join(".");
  const collectionPath = [
    "sourceClusters",
    "legacy",
    "snapshotInfo",
    collectionName,
  ];
  const groupId = `definition-group:${collectionTargetId}`;
  snapshotInfo.children.push({
    id: collectionTargetId,
    path: collectionPath,
    label: groupLabel,
    valueKind: "record",
    status: "ok",
    inputHint: {
      kind: "record",
      addLabel,
      definitionCollection: {
        ownerAncestorLevels: 2,
        navigation: {
          groupLabel,
          groupOrder,
          groupId,
        },
        definition: {
          typeLabel,
        },
      },
    },
    diagnostics: [],
    children: [{
      id: `${collectionTargetId}:add`,
      path: collectionPath,
      label: `+ Add ${addLabel}`,
      valueKind: "command",
      status: "ok",
      command: {
        requiresName: true,
        editAdded: false,
        autoEditAdded: true,
      },
      diagnostics: [],
      children: [],
    }],
  });

  const navigation = draft.navigation;
  if (!navigation) throw new Error("Missing configuration navigation");
  const sourceId = "resource:sourceconfigs:legacy";
  const source = navigation.nodes[sourceId];
  if (!source) throw new Error("Missing source navigation node");
  navigation.nodes[groupId] = {
    id: groupId,
    revision: `test:${groupId}`,
    parentId: sourceId,
    childIds: [],
    kind: "group",
    label: groupLabel,
    description: null,
    status: "ok",
    phase: null,
    valueSummary: null,
    diagnostics: [],
    capabilities: [],
    details: [],
    relationships: [],
    comparisons: [],
    resourcePlural: null,
    resourceName: null,
    resourceType: null,
    configPresence: {},
  };
  source.childIds.push(groupId);
}


function rawRepairDraft() {
  const draft = structuredClone(configDraft);
  draft.draftRevision = "raw-repair-1";
  draft.editState = {
    formatVersion: 1,
    provenance: {
      source: "pending-yaml",
      lossy: true,
      mode: "raw",
      warnings: [
        "The saved YAML must be repaired before the form editor can open it.",
      ],
    },
    nodes: [],
    validation: {
      valid: false,
      errors: ["Flow sequence in block collection must be closed"],
      diagnostics: [{
        severity: "error",
        message: "Flow sequence in block collection must be closed",
        path: [],
      }],
    },
  };
  draft.rawYaml = "sourceClusters:\n  source: [\n";
  return draft;
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
  expect(screen.queryByText("Server ready")).toBeNull();

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
  expect(screen.getByRole("button", { name: "Logs for capture" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "Delete resource" })).toBeEnabled();
});


test("opens the focused configuration deletion review from a runtime resource", async () => {
  const snapshot = runtimeSourceSnapshot();
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
  );

  renderApp();
  const tree = await screen.findByRole("tree", {
    name: "Workflow resources",
  });
  await userEvent.click(within(tree).getByRole("treeitem", {
    name: /^legacy, Ready$/,
  }));
  await userEvent.click(screen.getByRole("button", {
    name: "Remove legacy from configuration",
  }));

  expect(await screen.findByText("Editing configuration"))
    .toBeInTheDocument();
  expect(await screen.findByRole("heading", {
    name: "Remove legacy from configuration?",
  }))
    .toBeInTheDocument();
  expect(screen.getByRole("note")).toHaveTextContent(
    "Deployed resources are not deleted.",
  );
  expect(screen.getByRole("note")).toHaveTextContent(
    "deleting migrated indexes or restoring snapshots",
  );
  expect(screen.getByRole("button", {
    name: "Confirm configuration removal",
  }))
    .toBeEnabled();

  await userEvent.click(screen.getByRole("button", {
    name: "Cancel",
  }));
  expect(screen.queryByRole("heading", {
    name: "Remove legacy from configuration?",
  })).toBeNull();
  expect(screen.getByText("Edit legacy")).toBeInTheDocument();
});


test("shows runtime connectivity while its initial check is pending", async () => {
  const snapshot = runtimeSourceSnapshot();
  let resolveInventory: ((response: Response) => void) | undefined;
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
    http.post(
      "*/api/v1/config/connectivity/inventory",
      () => new Promise<Response>((resolve) => {
        resolveInventory = resolve;
      }),
    ),
  );

  renderApp();
  const tree = await screen.findByRole("tree", {
    name: "Workflow resources",
  });
  const source = within(tree).getByRole("treeitem", {
    name: /^legacy, Ready$/,
  });
  expect(within(source).getByLabelText("Connectivity pending"))
    .toBeInTheDocument();
  await userEvent.click(source);

  expect(await screen.findByRole("region", { name: "Resource checks" }))
    .toBeInTheDocument();
  expect(screen.getByRole("tab", {
    name: /legacy.*Source Cluster · Pending/i,
  }))
    .toBeInTheDocument();

  await userEvent.click(screen.getByRole("button", {
    name: "Edit configuration",
  }));
  expect(await screen.findByText("Editing configuration"))
    .toBeInTheDocument();
  const configurationChecks = screen.getByRole("region", {
    name: "Configuration checks",
  });
  expect(within(configurationChecks).getByRole("tab", {
    name: /legacy.*Source Cluster · Pending/i,
  })).toBeInTheDocument();

  await waitFor(() => expect(resolveInventory).toBeDefined());
  resolveInventory?.(HttpResponse.json({
    configNonce: configDraft.draftRevision,
    targets: [{
      id: "source:legacy",
      kind: "source",
      refName: "legacy",
      label: "Source legacy",
      editPath: ["sourceClusters", "legacy"],
    }],
  }));
});


test("shows resource runtime status and forces an explicit refresh", async () => {
  const forceValues: string[] = [];
  server.use(
    http.get(
      "*/api/v1/nodes/:nodeId/runtime-status",
      ({ params, request }) => {
        forceValues.push(
          new URL(request.url).searchParams.get("force") ?? "",
        );
        return HttpResponse.json({
          nodeId: params.nodeId,
          observedAt: "2026-08-30T14:00:00Z",
          pollAfterMs: null,
          sections: [
            {
              key: "snapshot",
              title: "Snapshot progress",
              state: "running",
              summary: "Snapshot is 50% complete",
              source: "console snapshot status watcher",
              content: {
                kind: "metrics",
                metrics: [
                  {
                    key: "shardsSuccessful",
                    label: "Shards successful",
                    value: 4,
                  },
                  {
                    key: "shardsTotal",
                    label: "Shards total",
                    value: 8,
                  },
                ],
              },
            },
            {
              key: "topics",
              title: "Kafka topics",
              state: "ok",
              summary: "2 topics.",
              source: "console kafka list-topics",
              content: {
                kind: "name-list",
                items: ["capture", "__consumer_offsets"],
              },
            },
            {
              key: "topic-records",
              title: "Captured topic records",
              state: "ok",
              summary: "125 records across 1 partition.",
              source: "console kafka describe-topic-records",
              content: {
                kind: "topic-partitions",
                partitions: [{
                  topic: "capture",
                  partition: 0,
                  records: 125,
                }],
              },
            },
          ],
        });
      },
    ),
  );
  renderApp();

  const tree = await screen.findByRole("tree", {
    name: "Workflow resources",
  });
  await userEvent.click(
    within(tree).getByRole("treeitem", { name: /^capture, Ready$/ }),
  );

  const runtime = await screen.findByRole("region", {
    name: "Runtime status",
  });
  expect(runtime).toBe(runtime.closest(".workspace")?.lastElementChild);
  expect(within(runtime).getByText("Snapshot is 50% complete"))
    .toBeInTheDocument();
  expect(within(runtime).getByText("Shards successful"))
    .toBeInTheDocument();
  expect(within(runtime).getByText("4")).toBeInTheDocument();
  expect(within(runtime).getByText("console snapshot status watcher"))
    .toBeInTheDocument();
  expect(within(runtime).getByRole("list", {
    name: "Kafka topics values",
  })).toHaveTextContent("capture");
  const partitions = within(runtime).getByRole("table", {
    name: "Captured topic records",
  });
  expect(within(partitions).getByRole("columnheader", { name: "Records" }))
    .toBeInTheDocument();
  expect(within(partitions).getByRole("cell", { name: "125" }))
    .toBeInTheDocument();
  expect(forceValues).toEqual(["false"]);

  await userEvent.click(within(runtime).getByRole("button", {
    name: "Refresh runtime status",
  }));

  await waitFor(() => expect(forceValues).toEqual(["false", "true"]));
});


test("does not expose the internal manage-state revision", async () => {
  renderApp();

  await screen.findByRole("tree", { name: "Workflow resources" });

  expect(screen.queryByText(manageSnapshot.revision)).not.toBeInTheDocument();
});


test("switches the resource overview between rollout snapshots", async () => {
  const rolloutSnapshot = structuredClone(manageSnapshot);
  rolloutSnapshot.nodes["resource:captureproxies:capture"].configPresence = {
    deployed: true,
    submitted: false,
    pending: false,
  };
  rolloutSnapshot.nodes["resource:trafficreplays:replay"].configPresence = {
    deployed: false,
    submitted: true,
    pending: true,
  };
  server.use(
    http.get(
      "*/api/v1/manage/state",
      () => HttpResponse.json(rolloutSnapshot),
    ),
  );
  renderApp();

  const views = await screen.findByRole("group", {
    name: "Resource state view",
  });
  const tree = screen.getByRole("tree", { name: "Workflow resources" });
  expect(within(tree).getByRole("treeitem", {
    name: /^capture, Ready$/,
  })).toBeInTheDocument();
  expect(within(tree).getByRole("treeitem", {
    name: /^replay, Running$/,
  })).toBeInTheDocument();

  await userEvent.click(within(views).getByRole("button", {
    name: "Deployed",
  }));
  expect(within(tree).getByRole("treeitem", {
    name: /^capture, Ready$/,
  })).toBeInTheDocument();
  expect(within(tree).queryByRole("treeitem", {
    name: /^replay, Running$/,
  })).toBeNull();

  await userEvent.click(within(views).getByRole("button", {
    name: "Submitted",
  }));
  expect(within(tree).queryByRole("treeitem", {
    name: /^capture, Ready$/,
  })).toBeNull();
  expect(within(tree).getByRole("treeitem", {
    name: /^replay, Running$/,
  })).toBeInTheDocument();

  await userEvent.click(within(views).getByRole("button", {
    name: "Saved config",
  }));
  expect(within(tree).queryByRole("treeitem", {
    name: /^capture, Ready$/,
  })).toBeNull();
  expect(within(tree).getByRole("treeitem", {
    name: /^replay, Running$/,
  })).toBeInTheDocument();

  await userEvent.click(within(views).getByRole("button", { name: "All" }));
  expect(within(tree).getByRole("treeitem", {
    name: /^capture, Ready$/,
  })).toBeInTheDocument();
  expect(within(tree).getByRole("treeitem", {
    name: /^replay, Running$/,
  })).toBeInTheDocument();
});


test("separates runtime state from configuration state in the resource tree", async () => {
  renderApp();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const capture = within(tree).getByRole(
    "treeitem",
    { name: /^capture, Ready$/ },
  );

  expect(within(capture).getByText("Ready")).toBeInTheDocument();
  expect(within(capture).getByText("Needs attention")).toBeInTheDocument();
  expect(within(capture).getByText("1 change to submit")).toBeInTheDocument();
  expect(capture.querySelector(".status-dot")).toBeNull();
});


test("keeps the full dependency graph stable while selection changes", async () => {
  renderApp();

  expect(await screen.findByRole("heading", {
    name: "Workflow dependencies",
  })).toBeInTheDocument();
  const graph = screen.getByRole("region", {
    name: "Workflow dependency graph",
  });
  expect(within(graph).getByRole("button", {
    name: "Open capture, Ready",
  })).toBeInTheDocument();
  expect(within(graph).getByRole("button", {
    name: "Open replay, Running",
  })).toBeInTheDocument();

  await userEvent.click(within(graph).getByRole("button", {
    name: "Open replay, Running",
  }));

  expect(within(graph).getByRole("button", {
    name: "Open capture, Ready",
  })).toBeInTheDocument();
  expect(within(graph).getByRole("button", {
    name: "Open replay, Running",
  })).toHaveAttribute("aria-current", "true");
  expect(within(graph).getByRole("button", {
    name: "Open workflow step Deploy replay, Running",
  })).toBeInTheDocument();
});


test("moves runtime workflow steps from resource navigation into activity", async () => {
  renderApp();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const replay = within(tree).getByRole("treeitem", {
    name: /^replay, Running$/,
  });
  expect(within(replay).queryByRole("button", {
    name: "Expand replay",
  })).toBeNull();
  expect(within(tree).queryByText("Deploy replay")).toBeNull();

  const graph = screen.getByRole("region", {
    name: "Workflow dependency graph",
  });
  await userEvent.click(within(graph).getByRole("button", {
    name: "Open workflow step Deploy replay, Running",
  }));
  expect(screen.getByRole("heading", { name: "Deploy replay" }))
    .toBeInTheDocument();
});


test("keeps workflow execution steps out of configuration navigation", async () => {
  renderApp();
  await enterEditMode();

  const tree = screen.getByRole("tree", { name: "Workflow resources" });
  const replay = within(tree).getByRole("treeitem", {
    name: /^replay/,
  });

  expect(within(replay).queryByText("Running")).toBeNull();
  expect(within(replay).queryByRole("button", {
    name: "Expand replay",
  })).toBeNull();
  expect(within(tree).queryByText("Deploy replay")).toBeNull();
});


test("shows navigable upstream and downstream runtime dependencies", async () => {
  renderApp();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  await userEvent.click(within(tree).getByRole(
    "treeitem",
    { name: /^capture, Ready$/ },
  ));

  expect(
    screen.getByRole("heading", { name: "Required by" }),
  ).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", {
    name: "Open dependent replay, Running",
  }));

  expect(screen.getByRole("heading", { name: "replay" })).toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "Requires" })).toBeInTheDocument();
  expect(screen.getByRole("button", {
    name: "Open prerequisite capture, Ready",
  })).toBeInTheDocument();

  await userEvent.click(screen.getByRole("button", {
    name: "Open prerequisite capture, Ready",
  }));
  expect(screen.getByRole("heading", { name: "capture" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Back to replay" }))
    .toBeInTheDocument();

  await userEvent.click(screen.getByRole("button", { name: "Back to replay" }));
  expect(screen.getByRole("heading", { name: "replay" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Back to capture" }))
    .toBeInTheDocument();

  await userEvent.click(screen.getByRole("button", { name: "Back to capture" }));
  expect(screen.getByRole("heading", { name: "capture" })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /^Back to/ })).toBeNull();
});


test("surfaces failed prerequisites in navigation and workflow activity", async () => {
  const blockedSnapshot = structuredClone(manageSnapshot);
  const captureId = "resource:captureproxies:capture";
  const replayId = "resource:trafficreplays:replay";
  const failedStepId = `workflow-step:${captureId}:endpoint`;
  blockedSnapshot.nodes[captureId] = {
    ...blockedSnapshot.nodes[captureId],
    childIds: [failedStepId],
    status: "error",
    phase: "Error",
    valueSummary: "Error",
    relationships: [{
      kind: "runtime-dependency",
      direction: "required-by",
      targetId: replayId,
      targetName: "replay",
      targetPlural: "trafficreplays",
      targetPhase: "Pending",
      targetStatus: "pending",
    }],
  };
  blockedSnapshot.nodes[replayId] = {
    ...blockedSnapshot.nodes[replayId],
    status: "pending",
    phase: "Pending",
    valueSummary: "Pending",
    relationships: [{
      kind: "runtime-dependency",
      direction: "requires",
      targetId: captureId,
      targetName: "capture",
      targetPlural: "captureproxies",
      targetPhase: "Error",
      targetStatus: "error",
    }],
  };
  blockedSnapshot.nodes[failedStepId] = {
    id: failedStepId,
    revision: "failed-endpoint-1",
    parentId: captureId,
    childIds: [],
    kind: "workflow-step",
    label: "waitForProxyEndpointReady",
    description: null,
    status: "error",
    phase: "Failed",
    valueSummary: null,
    diagnostics: [],
    capabilities: [],
    details: [{
      label: "Message",
      value: "LoadBalancer endpoint was not assigned",
      kind: "message",
    }],
    relationships: [],
    comparisons: [],
    resourcePlural: null,
    resourceName: null,
  };
  server.use(
    http.get(
      "*/api/v1/manage/state",
      () => HttpResponse.json(blockedSnapshot),
    ),
  );

  renderApp();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const replay = within(tree).getByRole(
    "treeitem",
    { name: /^replay, Pending, Blocked by capture$/ },
  );
  expect(within(replay).getByRole("button", {
    name: "View blocker capture",
  })).toHaveTextContent("Blocked by capture");
  expect(screen.getByText("1 action needs attention")).toBeInTheDocument();
  expect(screen.getByText("1 downstream resource is waiting"))
    .toBeInTheDocument();

  await userEvent.click(screen.getByRole("button", {
    name: "Open capture, Error",
  }));
  expect(screen.getByRole("heading", { name: "capture" })).toBeInTheDocument();
  expect(screen.queryByRole("heading", { name: "Diagnostics" })).toBeNull();
  expect(screen.queryByText("No diagnostics for this resource.")).toBeNull();

  const failedSteps = screen.getByRole("region", {
    name: "Failed workflow steps",
  });
  expect(within(failedSteps).getByText(
    "LoadBalancer endpoint was not assigned",
  )).toBeInTheDocument();
  await userEvent.click(within(failedSteps).getByRole("button", {
    name: (
      "Inspect failed workflow step waitForProxyEndpointReady, Failed"
    ),
  }));
  expect(screen.getByRole("heading", {
    name: "waitForProxyEndpointReady",
  })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Back to capture" }))
    .toBeInTheDocument();
});


test("lifts a VAP retry failure and requires reset before resubmitting", async () => {
  const blockedSnapshot = structuredClone(manageSnapshot);
  const captureId = "resource:captureproxies:capture";
  const applyStepId = `workflow-step:${captureId}:apply`;
  const failureMessage = (
    'main: Error (exit code 64): no more retries The captureproxies "capture" '
    + "is invalid: ValidatingAdmissionPolicy denied request: Impossible: "
    + "sourceLabel cannot be changed. Delete and recreate."
  );
  blockedSnapshot.nodes[captureId] = {
    ...blockedSnapshot.nodes[captureId],
    childIds: [applyStepId],
    status: "blocked",
    phase: "Ready",
    valueSummary: "Ready",
    diagnostics: [{
      severity: "error",
      message: (
        "Impossible: sourceLabel cannot be changed. Delete and recreate."
      ),
      path: [],
      source: "workflow-apply",
      code: "immutable-resource-update",
      title: "Apply failed; reset required",
      remedy: (
        "Reset capture to delete and recreate it, then retry the apply."
      ),
      technicalDetail: failureMessage,
    }],
    capabilities: [
      ...blockedSnapshot.nodes[captureId].capabilities,
      {
        kind: "approve",
        approvalTargetId: "approval:apply",
        label: "Retry apply",
        disabledReason: "Reset capture before retrying this apply.",
      },
    ],
  };
  blockedSnapshot.nodes[applyStepId] = {
    id: applyStepId,
    revision: "apply-blocked-1",
    parentId: captureId,
    childIds: [],
    kind: "workflow-step",
    label: "Apply failed",
    description: null,
    status: "blocked",
    phase: "Blocked",
    valueSummary: null,
    diagnostics: [],
    capabilities: [{
      kind: "approve",
      approvalTargetId: "approval:apply",
      label: "Retry apply",
      disabledReason: "Reset capture before retrying this apply.",
    }],
    details: [
      {
        label: "Reason",
        value: (
          "Impossible: sourceLabel cannot be changed. Delete and recreate."
        ),
        kind: "message",
      },
      {
        label: "Remedy",
        value: (
          "Reset capture to delete and recreate it, then retry the apply."
        ),
        kind: "remedy",
      },
      {
        label: "Technical details",
        value: failureMessage,
        kind: "technical",
      },
    ],
    relationships: [],
    comparisons: [],
    resourcePlural: null,
    resourceName: null,
  };
  server.use(
    http.get(
      "*/api/v1/manage/state",
      () => HttpResponse.json(blockedSnapshot),
    ),
  );

  renderApp();

  const requiredActions = await screen.findByRole("dialog", {
    name: "Review required actions",
  });
  expect(within(requiredActions).getByText(
    "Impossible update / resource deletion required",
  )).toBeInTheDocument();
  expect(await within(requiredActions).findByText(
    /deployed resource must be deleted/,
  )).toBeInTheDocument();
  expect(within(requiredActions).getByRole("button", {
    name: "Edit configuration",
  })).toBeEnabled();
  await waitFor(() => expect(within(requiredActions).getByRole("button", {
    name: "Delete resource and resubmit",
  })).toBeEnabled());

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const capture = within(tree).getByRole(
    "treeitem",
    { name: /^capture, Ready, Delete resource before approval/ },
  );
  expect(within(capture).getByText("Delete resource before approval"))
    .toBeInTheDocument();
  expect(within(capture).getByText(/sourceLabel cannot be changed/))
    .toBeInTheDocument();
  await userEvent.click(capture);

  expect(screen.getByRole("region", {
    name: "Resource deletion required before approval",
  })).toBeInTheDocument();
  const issue = screen.getByRole("alert", {
    name: "Apply failed; resource deletion required",
  });
  expect(within(issue).getByText(
    "Impossible: sourceLabel cannot be changed. Delete and recreate.",
  )).toBeInTheDocument();
  expect(within(issue).getByText(
    "Delete resource capture so it can be recreated, then retry the apply.",
  )).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Delete resource" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "Retry apply" }))
    .toBeDisabled();
  expect(screen.getByRole("button", { name: "Retry apply" }))
    .toHaveAttribute(
      "title",
      "Delete resource capture before retrying this apply.",
    );

  expect(screen.getByRole("button", {
    name: "Open workflow step Apply failed, Blocked",
  })).toBeInTheDocument();
  expect(within(tree).queryByText("Apply failed")).toBeNull();
});


test("does not offer old-workflow creation for an absent immutable resource", async () => {
  const blockedSnapshot = structuredClone(manageSnapshot);
  const captureId = "resource:captureproxies:capture";
  const capture = blockedSnapshot.nodes[captureId];
  capture.configPresence = { deployed: false };
  capture.diagnostics = [{
    severity: "error",
    message: "Impossible: sourceLabel cannot be changed. Delete and recreate.",
    path: [],
    source: "workflow-apply",
    code: "immutable-resource-update",
    title: "Apply failed; reset required",
    remedy: "Create the resource in a replacement workflow.",
  }];
  capture.capabilities = [
    ...capture.capabilities.filter((capability) => (
      capability.kind !== "approve" && capability.kind !== "reset"
    )),
    {
      kind: "approve",
      approvalTargetId: "approval:absent-capture",
      label: "Retry apply",
      disabledReason: null,
    },
  ];
  server.use(
    http.get(
      "*/api/v1/manage/state",
      () => HttpResponse.json(blockedSnapshot),
    ),
  );

  renderApp();

  const dialog = await screen.findByRole("dialog", {
    name: "Review required actions",
  });
  expect(within(dialog).getByText(
    "Impossible update / resource absent",
  )).toBeInTheDocument();
  expect(await within(dialog).findByText(
    "The resource is absent. A replacement workflow is required to recreate it.",
  )).toBeInTheDocument();
  expect(within(dialog).queryByRole("button", {
    name: "Retry create",
  })).not.toBeInTheDocument();
});


test("starts pauses and explicitly stops bounded resource logs", async () => {
  let startRequest: unknown;
  let stoppedStream: string | undefined;
  server.use(
    http.post("*/api/v1/log-streams", async ({ request }) => {
      startRequest = await request.json();
      return HttpResponse.json({
        id: "log-stream-test",
        target: {
          id: "log-target-all",
          label: "All matching containers",
          kind: "aggregate",
          podName: null,
          podUid: null,
          container: null,
          restartCount: null,
          previous: false,
          supportsFollow: true,
        },
        state: "following",
        page: {
          events: [{
            sequence: 4,
            receivedAt: "2026-08-13T20:00:01Z",
            timestamp: "2026-08-13T20:00:00Z",
            podName: "capture-0",
            podUid: "pod-uid",
            container: "capture-proxy",
            restartCount: 0,
            previous: false,
            message: "Timed out waiting for proxy endpoint readiness",
            kind: "error",
          }],
          beforeCursor: "cursor-4",
          afterCursor: "cursor-4",
          atAvailableStart: true,
          atBufferEnd: true,
          historyTruncated: true,
          state: "following",
        },
      }, { status: 201 });
    }),
    http.delete(
      "*/api/v1/log-streams/:streamId",
      ({ params }) => {
        stoppedStream = String(params.streamId);
        return HttpResponse.json({
          id: params.streamId,
          state: "stopped",
          message: null,
        });
      },
    ),
  );
  renderApp();

  await userEvent.click(await screen.findByRole("button", {
    name: "Logs for capture",
  }));
  expect(await screen.findByRole("region", { name: "Managed logs" }))
    .toBeInTheDocument();
  expect(screen.getByRole("link", {
    name: "Open logs in new tab",
  })).toHaveAttribute(
    "href",
    "/logs?nodeId=resource%3Acaptureproxies%3Acapture",
  );
  expect(screen.getByRole("link", {
    name: "Open logs in new tab",
  })).toHaveAttribute("target", "_blank");
  expect(screen.getByRole("combobox", { name: "Log target" }))
    .toHaveValue("log-target-all");
  const managedLogs = screen.getByRole("region", { name: "Managed logs" });
  expect(within(managedLogs).getByRole("heading", { name: "capture" }))
    .toBeInTheDocument();
  expect(screen.getByText("Managed logs for resource"))
    .toBeInTheDocument();
  expect(screen.getByText("Target: All matching containers"))
    .toBeInTheDocument();
  expect(screen.getByRole("note")).toHaveTextContent(
    "Kubernetes logs are temporary",
  );
  expect(screen.getByRole("link", {
    name: "Open CloudWatch log group",
  })).toHaveAttribute("target", "_blank");

  await userEvent.click(screen.getByRole("button", {
    name: "Start logs",
  }));
  const timeout = await screen.findByText(
    "Timed out waiting for proxy endpoint readiness",
  );
  expect(timeout.closest(".log-line")).toHaveClass("log-line-error");
  expect(screen.getByText("1 error")).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", {
    name: "Next highlighted line",
  }));
  expect(timeout.closest(".log-line")).toHaveClass("log-line-active");

  const sourceDivider = screen.getByRole("slider", {
    name: "Resize source column",
  });
  expect(sourceDivider).toHaveValue("420");
  fireEvent.keyDown(sourceDivider, { key: "ArrowRight" });
  expect(sourceDivider).toHaveValue("436");
  expect(startRequest).toEqual({
    targetId: "log-target-all",
    tailLines: 500,
    follow: true,
    pageSize: 500,
  });

  await userEvent.click(screen.getByRole("button", { name: "Pause" }));
  expect(screen.getByRole("button", { name: "Resume" }))
    .toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Stop" }));
  await waitFor(() => expect(stoppedStream).toBe("log-stream-test"));
  expect(screen.getByRole("button", { name: "Start logs" })).toBeEnabled();
});


test("clears the selected workspace and logs when resource deletion starts", async () => {
  let resetRequest: unknown;
  server.use(
    http.post("*/api/v1/resets", async ({ request }) => {
      resetRequest = await request.json();
      return HttpResponse.json({
        id: "operation-reset-capture",
        kind: "reset",
        label: "Delete captureproxy.capture",
        status: "queued",
        targetIds: ["resource:captureproxies:capture"],
        createdAt: "2026-09-14T12:00:00Z",
        updatedAt: "2026-09-14T12:00:00Z",
        message: "Queued",
        detail: null,
        result: {},
      }, { status: 202 });
    }),
  );
  renderApp();

  await userEvent.click(await screen.findByRole("button", {
    name: "Logs for capture",
  }));
  expect(await screen.findByRole("region", { name: "Managed logs" }))
    .toBeInTheDocument();

  await userEvent.click(screen.getByRole("button", {
    name: "Delete resource",
  }));
  const dialog = await screen.findByRole("dialog", {
    name: "Review resource deletion",
  });
  await userEvent.click(within(dialog).getByRole("button", {
    name: "Delete listed resources",
  }));

  await waitFor(() => expect(resetRequest).toEqual({
    planToken: "reset-token",
    resubmit: false,
  }));
  expect(await screen.findByRole("heading", { name: "Select a resource" }))
    .toBeInTheDocument();
  expect(screen.queryByRole("region", { name: "Managed logs" })).toBeNull();
});


test("renders managed logs as a dedicated full-window route", async () => {
  const startRequests: unknown[] = [];
  const stoppedStreams: string[] = [];
  server.use(
    http.post("*/api/v1/log-streams", async ({ request }) => {
      const startRequest = await request.json() as {
        follow: boolean;
      };
      startRequests.push(startRequest);
      return HttpResponse.json({
        id: "standalone-log-stream",
        target: {
          id: "log-target-all",
          label: "All matching containers",
          kind: "aggregate",
          podName: null,
          podUid: null,
          container: null,
          restartCount: null,
          previous: false,
          supportsFollow: true,
        },
        state: startRequest.follow ? "following" : "ended",
        page: {
          events: [{
            sequence: 1,
            receivedAt: "2026-08-13T20:00:01Z",
            timestamp: "2026-08-13T20:00:00Z",
            podName: "capture-0",
            podUid: "pod-uid",
            container: "capture-proxy",
            restartCount: 0,
            previous: false,
            message: "proxy is ready",
            kind: "log",
          }],
          beforeCursor: "cursor-1",
          afterCursor: "cursor-1",
          atAvailableStart: true,
          atBufferEnd: true,
          historyTruncated: false,
          state: startRequest.follow ? "following" : "ended",
        },
      }, { status: 201 });
    }),
    http.delete(
      "*/api/v1/log-streams/:streamId",
      ({ params }) => {
        stoppedStreams.push(String(params.streamId));
        return HttpResponse.json({
          id: params.streamId,
          state: "stopped",
          message: null,
        });
      },
    ),
  );
  globalThis.history.pushState(
    {},
    "",
    "/logs?nodeId=resource%3Acaptureproxies%3Acapture",
  );
  renderApp();

  expect(await screen.findByRole("region", { name: "Managed logs" }))
    .toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Close log window" }))
    .toBeInTheDocument();
  expect(screen.queryByRole("link", {
    name: "Open logs in new tab",
  })).toBeNull();
  expect(screen.queryByRole("heading", { name: "Workflow Manage" }))
    .toBeNull();
  expect(await screen.findByRole("heading", { name: "capture" }))
    .toBeInTheDocument();
  expect(screen.getByText("Target: All matching containers"))
    .toBeInTheDocument();
  expect(await screen.findByText("proxy is ready")).toBeInTheDocument();
  expect(startRequests[0]).toEqual({
    targetId: "log-target-all",
    tailLines: 500,
    follow: false,
    pageSize: 500,
  });
  expect(screen.getByRole("checkbox", { name: "Follow" })).toBeChecked();
  expect(screen.getByText("Follow starts in 3 seconds"))
    .toBeInTheDocument();
  await waitFor(() => expect(startRequests.at(-1)).toEqual({
    targetId: "log-target-all",
    tailLines: 500,
    follow: true,
    pageSize: 500,
  }), { timeout: 5000 });

  const follow = screen.getByRole("checkbox", { name: "Follow" });
  await userEvent.click(follow);
  await waitFor(() => expect(stoppedStreams).toContain(
    "standalone-log-stream",
  ));
  expect(follow).not.toBeChecked();

  await userEvent.click(follow);
  await waitFor(() => expect(startRequests).toHaveLength(3));
  expect(startRequests.at(-1)).toEqual({
    targetId: "log-target-all",
    tailLines: 500,
    follow: true,
    pageSize: 500,
  });
  expect(follow).toBeChecked();

  globalThis.history.replaceState({}, "", "/");
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


test("reviews managed output and approves from the required-action dialog", async () => {
  const outputState = structuredClone(manageSnapshot);
  outputState.nodes["resource:captureproxies:capture"].capabilities.push({
    kind: "approve",
    approvalTargetId: "approval:approval-node",
    label: "Approve metadata",
    outputTargetId: (
      "output:snapshotmigrations:migration-0:metadataEvaluate"
    ),
  });
  let approvalRequest: unknown;
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(outputState)),
    http.post("*/api/v1/approvals", async ({ request }) => {
      approvalRequest = await request.json();
      return HttpResponse.json({
        id: "operation-approve-output",
        kind: "approve",
        label: "Approve Metadata evaluation",
        status: "queued",
        targetIds: ["resource:captureproxies:capture"],
        createdAt: "2026-08-13T13:00:00Z",
        updatedAt: "2026-08-13T13:00:00Z",
        message: "Queued",
        detail: null,
        result: {},
      }, { status: 202 });
    }),
  );
  renderApp();

  const requiredActions = await screen.findByRole("dialog", {
    name: "Review required actions",
  });
  await userEvent.click(within(requiredActions).getByRole("button", {
    name: "View output",
  }));

  const outputReview = await screen.findByRole("dialog", {
    name: "Review output for capture",
  });
  expect(within(outputReview).getByRole("heading", { name: "Evaluate" }))
    .toBeInTheDocument();
  expect(await within(outputReview).findByText(/"documents": 12/))
    .toBeInTheDocument();
  expect(within(outputReview).getByText(
    "Review this output, then approve to continue the workflow.",
  )).toBeInTheDocument();

  await userEvent.click(within(outputReview).getByRole("button", {
    name: "Approve",
  }));
  await waitFor(() => expect(approvalRequest).toEqual({
    targetId: "approval:approval-node",
    expectedGateRevision: "11",
  }));
  expect(within(outputReview)
    .getByText("Approval accepted; the workflow is continuing"))
    .toBeInTheDocument();
});


test("resets and retries all impossible deployed updates in one action", async () => {
  const actionState = structuredClone(manageSnapshot);
  const immutableMessage = (
    "Impossible: sourceLabel cannot be changed. Delete and recreate."
  );
  const targets = [
    {
      nodeId: "resource:captureproxies:capture",
      approvalTargetId: "approval:capture-apply",
      resetTargetId: "reset:captureproxies:capture",
      name: "capture",
      revision: "11",
    },
    {
      nodeId: "resource:trafficreplays:replay",
      approvalTargetId: "approval:replay-apply",
      resetTargetId: "reset:trafficreplays:replay",
      name: "replay",
      revision: "12",
    },
  ];
  targets.forEach((target) => {
    const node = actionState.nodes[target.nodeId];
    node.status = "blocked";
    node.configPresence = { deployed: true };
    node.diagnostics = [{
      severity: "error",
      message: immutableMessage,
      path: [],
      source: "workflow-apply",
      code: "immutable-resource-update",
      title: "Apply failed; reset required",
      remedy: "Reset the resource, then retry the apply.",
    }];
    node.capabilities = [
      ...node.capabilities.filter((capability) => (
        capability.kind !== "reset"
        && capability.kind !== "approve"
      )),
      {
        kind: "reset",
        resetTargetId: target.resetTargetId,
        label: `Reset ${target.name}`,
      },
      {
        kind: "approve",
        approvalTargetId: target.approvalTargetId,
        label: "Retry apply",
        disabledReason: `Reset ${target.name} before retrying this apply.`,
      },
    ];
  });
  let resetPlanRequest: unknown;
  let resetRequest: unknown;
  server.use(
    http.get("*/api/v1/manage/state", () =>
      HttpResponse.json(actionState)),
    http.get("*/api/v1/approvals/review", ({ request }) => {
      const targetId = new URL(request.url).searchParams.get("targetId");
      const target = targets.find(
        (candidate) => candidate.approvalTargetId === targetId,
      ) as (typeof targets)[number];
      return HttpResponse.json({
        targetId,
        nodeId: targetId?.replace("approval:", ""),
        gateName: `${target.name}.vapretry`,
        gateRevision: target.revision,
        workflowName: "migration",
        resourceId: target.nodeId,
        resourceKind: "MigrationResource",
        resourceName: target.name,
        stage: "Resource reconciliation",
        effect: "Approving retries applying the resource configuration.",
        reason: immutableMessage,
        snapshotRevision: actionState.revision,
      });
    }),
    http.post("*/api/v1/resets/plan", async ({ request }) => {
      resetPlanRequest = await request.json();
      return HttpResponse.json({
        token: "combined-reset-token",
        requestTargetId: targets[0].resetTargetId,
        targets: targets.map((target) => ({
          plural: target.resetTargetId.split(":")[1],
          type: "migrationresource",
          name: target.name,
          path: `migrationresource.${target.name}`,
          phase: "Ready",
          dependsOn: [],
        })),
        messages: [],
        warnings: [],
      });
    }),
    http.post("*/api/v1/resets", async ({ request }) => {
      resetRequest = await request.json();
      return HttpResponse.json({
        id: "operation-reset-all",
        kind: "reset",
        label: "Reset and retry 2 resources",
        status: "queued",
        targetIds: targets.map((target) => target.nodeId),
        createdAt: "2026-08-15T12:00:00Z",
        updatedAt: "2026-08-15T12:00:00Z",
        message: "Queued",
        detail: null,
        result: {},
      }, { status: 202 });
    }),
  );

  renderApp();

  const dialog = await screen.findByRole("dialog", {
    name: "Review required actions",
  });
  expect(await within(dialog).findByText("2 resources to delete"))
    .toBeInTheDocument();
  const resetAll = within(dialog).getByRole("button", {
    name: "Delete resources and resubmit all (2)",
  });
  expect(resetAll).toBeEnabled();
  await userEvent.click(resetAll);

  await waitFor(() => expect(resetPlanRequest).toEqual({
    targetIds: targets.map((target) => target.resetTargetId),
  }));
  await waitFor(() => expect(resetRequest).toEqual({
    planToken: "combined-reset-token",
    resubmit: true,
  }));
  expect(within(dialog).getAllByText(
    "Action accepted. Waiting for workflow reconciliation.",
  )).toHaveLength(2);
});


test("reviews exact approval and reset targets before starting operations", async () => {
  const actionState = structuredClone(manageSnapshot);
  actionState.nodes["resource:captureproxies:capture"].capabilities.push({
    kind: "approve",
    approvalTargetId: "approval:approval-node",
    label: "Approve metadata",
  });
  actionState.nodes["resource:trafficreplays:replay"].capabilities.push({
    kind: "approve",
    approvalTargetId: "approval:replay-node",
    label: "Approve replay",
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

  const approval = await screen.findByRole("dialog", {
    name: "Review required actions",
  });
  expect(within(approval).getByText("2 waiting gates")).toBeInTheDocument();
  await waitFor(() => {
    expect(within(approval).getAllByText("migration-0")).toHaveLength(2);
    expect(within(approval).getAllByText(
      /advances to metadata migration/,
    )).toHaveLength(2);
  });
  expect(within(approval).getAllByText("Approval required")).toHaveLength(2);
  await userEvent.click(within(approval).getAllByRole("button", {
    name: "Approve",
  })[0]);
  await waitFor(() => expect(approvalRequest).toEqual({
    targetId: "approval:approval-node",
    expectedGateRevision: "11",
  }));
  expect(within(approval).getByText(
    "Action accepted. Waiting for workflow reconciliation.",
  )).toBeInTheDocument();
  expect(screen.getByRole("region", { name: "Approval required" }))
    .toBeInTheDocument();
  await userEvent.click(within(approval).getByRole("button", {
    name: "Close required actions",
  }));

  await userEvent.click(screen.getByRole("button", {
    name: "Delete resource",
  }));
  const reset = await screen.findByRole("dialog", {
    name: "Review resource deletion",
  });
  expect(within(reset).getByText("captureproxy.capture"))
    .toBeInTheDocument();
  await userEvent.click(within(reset).getByRole("button", {
    name: "Delete listed resources",
  }));
  await waitFor(() => expect(resetRequest).toEqual({
    planToken: "reset-token",
    resubmit: false,
  }));
});


test("preapproves upcoming resource checkpoints and inventories all gates", async () => {
  const gates = {
    workflowName: "migration",
    gates: [{
      name: "captureproxysetup.capture",
      gateRevision: "21",
      category: "checkpoint",
      state: "upcoming",
      phase: "Created",
      resourceId: "resource:captureproxies:capture",
      resourceKind: "CaptureProxy",
      resourceName: "capture",
      stage: "Capture proxy setup",
      effect: "Approving allows capture proxy deployment to begin.",
      reason: null,
      enabled: true,
      approved: false,
      toggleable: true,
      disabledReason: null,
      approvalTargetId: null,
      outputTargetId: null,
    }, {
      name: "evaluatemetadata.migration-0",
      gateRevision: "22",
      category: "checkpoint",
      state: "passed",
      phase: "Approved",
      resourceId: "resource:snapshotmigrations:migration-0",
      resourceKind: "SnapshotMigration",
      resourceName: "migration-0",
      stage: "Metadata evaluation",
      effect: "Metadata evaluation was approved.",
      reason: null,
      enabled: true,
      approved: true,
      toggleable: false,
      disabledReason: "The workflow already passed this approval checkpoint.",
      approvalTargetId: null,
      outputTargetId: null,
    }, {
      name: "documentbackfill.migration-0",
      gateRevision: "23",
      category: "checkpoint",
      state: "not-required",
      phase: "Created",
      resourceId: "resource:snapshotmigrations:migration-0",
      resourceKind: "SnapshotMigration",
      resourceName: "migration-0",
      stage: "Document backfill",
      effect: "Approving starts document backfill.",
      reason: null,
      enabled: false,
      approved: false,
      toggleable: false,
      disabledReason: (
        "The submitted configuration does not use this approval checkpoint."
      ),
      approvalTargetId: null,
      outputTargetId: null,
    }],
  };
  let preapprovalRequest: unknown;
  server.use(
    http.get(
      "*/api/v1/approval-gates",
      () => HttpResponse.json(gates),
    ),
    http.patch(
      "*/api/v1/approval-gates/:gateName",
      async ({ request }) => {
        preapprovalRequest = await request.json();
        return HttpResponse.json({
          gateName: "captureproxysetup.capture",
          preapproved: true,
        });
      },
    ),
  );
  renderApp();

  const resourceToggle = await screen.findByRole("checkbox", {
    name: "Preapprove upcoming checkpoints",
  });
  expect(resourceToggle).toHaveAttribute("aria-checked", "false");
  await userEvent.click(resourceToggle);
  await waitFor(() => expect(preapprovalRequest).toEqual({
    expectedGateRevision: "21",
    preapproved: true,
  }));

  await userEvent.click(screen.getByRole("button", { name: "Approvals" }));
  const center = await screen.findByRole("dialog", { name: "Approvals" });
  expect(within(center).getByRole("heading", { name: "Upcoming" }))
    .toBeInTheDocument();
  expect(within(center).getByRole("heading", { name: "Passed" }))
    .toBeInTheDocument();
  expect(within(center).getByText("Not required")).toBeInTheDocument();
  expect(within(center).getByRole("checkbox", {
    name: "Preapprove Document backfill",
  })).toBeDisabled();
  expect(within(center).getByRole("checkbox", {
    name: "Preapprove Document backfill",
  })).toHaveAttribute(
    "title",
    "The submitted configuration does not use this approval checkpoint.",
  );
});


test("approves blockers inline and preapproves all upcoming checkpoints", async () => {
  const gate = (
    name: string,
    revision: string,
    stage: string,
    overrides: Record<string, unknown> = {},
  ) => ({
    name,
    gateRevision: revision,
    category: "checkpoint",
    state: "upcoming",
    phase: "Created",
    resourceId: "resource:snapshotmigrations:migration-0",
    resourceKind: "SnapshotMigration",
    resourceName: "migration-0",
    stage,
    effect: `Approving advances ${stage}.`,
    reason: null,
    enabled: true,
    approved: false,
    toggleable: true,
    disabledReason: null,
    approvalTargetId: null,
    outputTargetId: null,
    ...overrides,
  });
  const gates = {
    workflowName: "migration",
    gates: [
      gate("evaluatemetadata.migration-0", "31", "Metadata evaluation", {
        state: "blocking",
        toggleable: false,
        disabledReason: (
          "This checkpoint is blocking now. Review and approve it directly."
        ),
        approvalTargetId: "approval:evaluate-node",
      }),
      gate("migratemetadata.migration-0", "32", "Metadata migration"),
      gate("documentbackfill.migration-0", "33", "Document backfill"),
      gate("unused.migration-0", "34", "Unused checkpoint", {
        state: "not-required",
        enabled: false,
        toggleable: false,
        disabledReason: (
          "The submitted configuration does not use this approval checkpoint."
        ),
      }),
    ],
  };
  let approvalRequest: unknown;
  const preapprovalRequests: Array<{
    gateName: string;
    body: unknown;
  }> = [];
  server.use(
    http.get(
      "*/api/v1/approval-gates",
      () => HttpResponse.json(gates),
    ),
    http.post("*/api/v1/approvals", async ({ request }) => {
      approvalRequest = await request.json();
      return HttpResponse.json({
        id: "operation-inline-approval",
        kind: "approve",
        label: "Approve Metadata evaluation",
        status: "queued",
        targetIds: ["resource:snapshotmigrations:migration-0"],
        createdAt: "2026-08-13T13:00:00Z",
        updatedAt: "2026-08-13T13:00:00Z",
        message: "Queued",
        detail: null,
        result: {},
      }, { status: 202 });
    }),
    http.patch(
      "*/api/v1/approval-gates/:gateName",
      async ({ params, request }) => {
        const body = await request.json();
        preapprovalRequests.push({
          gateName: String(params.gateName),
          body,
        });
        return HttpResponse.json({
          gateName: String(params.gateName),
          preapproved: true,
        });
      },
    ),
  );
  renderApp();

  await userEvent.click(await screen.findByRole("button", {
    name: "Approvals",
  }));
  const center = await screen.findByRole("dialog", { name: "Approvals" });
  await userEvent.click(within(center).getByRole("button", {
    name: "Approve Metadata evaluation",
  }));
  await waitFor(() => expect(approvalRequest).toEqual({
    targetId: "approval:evaluate-node",
    expectedGateRevision: "31",
  }));
  expect(screen.queryByRole("dialog", {
    name: "Review required actions",
  })).not.toBeInTheDocument();

  const allUpcoming = within(center).getByRole("checkbox", {
    name: "Preapprove all upcoming checkpoints",
  });
  expect(allUpcoming).toHaveAttribute("aria-checked", "false");
  await userEvent.click(allUpcoming);
  await waitFor(() => expect(preapprovalRequests).toEqual(
    expect.arrayContaining([
      {
        gateName: "migratemetadata.migration-0",
        body: {
          expectedGateRevision: "32",
          preapproved: true,
        },
      },
      {
        gateName: "documentbackfill.migration-0",
        body: {
          expectedGateRevision: "33",
          preapproved: true,
        },
      },
    ]),
  ));
  expect(preapprovalRequests).toHaveLength(2);
});


test("keeps a dismissed approval visible without repeatedly opening it", async () => {
  const actionState = structuredClone(manageSnapshot);
  actionState.nodes["resource:captureproxies:capture"].capabilities.push({
    kind: "approve",
    approvalTargetId: "approval:approval-node",
    label: "Approve metadata",
  });
  server.use(
    http.get("*/api/v1/manage/state", () =>
      HttpResponse.json(actionState)),
  );
  renderApp();

  const approval = await screen.findByRole("dialog", {
    name: "Review required actions",
  });
  await userEvent.click(within(approval).getByRole("button", {
    name: "Close required actions",
  }));
  expect(screen.queryByRole("dialog")).toBeNull();

  await userEvent.click(screen.getByRole("button", {
    name: "Refresh state",
  }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

  const notice = screen.getByRole("region", { name: "Approval required" });
  expect(within(notice).getByText("migration-0")).toBeInTheDocument();
  expect(within(notice).getByText("Metadata evaluation")).toBeInTheDocument();
  expect(within(notice).getByText(/advances to metadata migration/))
    .toBeInTheDocument();
  await userEvent.click(within(notice).getByRole("button", {
    name: "Review required actions",
  }));
  expect(await screen.findByRole("dialog", {
    name: "Review required actions",
  })).toBeInTheDocument();
});


test("labels orphan cleanup and active removal without implying automatic pruning", async () => {
  const orphanedState = structuredClone(manageSnapshot);
  const capture = orphanedState.nodes["resource:captureproxies:capture"];
  capture.valueSummary = "Orphaned; cleanup required";
  capture.configPresence = {
    deployed: true,
    submitted: false,
    pending: false,
  };
  let operations = { operations: [] as Array<{
    id: string;
    kind: string;
    label: string;
    status: "running";
    targetIds: string[];
    createdAt: string;
    updatedAt: string;
    message: string;
    detail: null;
    result: Record<string, never>;
  }> };
  server.use(
    http.get("*/api/v1/manage/state", () =>
      HttpResponse.json(orphanedState)),
    http.get("*/api/v1/operations", () => HttpResponse.json(operations)),
  );
  renderApp();

  const tree = await screen.findByRole("tree", {
    name: "Workflow resources",
  });
  expect(within(tree).getByRole("treeitem", {
    name: /^capture, Orphaned; cleanup required$/,
  })).toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "Resource deletion required" }))
    .toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Delete resource" }))
    .toHaveClass("primary-button");

  operations = {
    operations: [{
      id: "reset-capture",
      kind: "reset",
      label: "Reset captureproxy.capture",
      status: "running",
      targetIds: ["resource:captureproxies:capture"],
      createdAt: "2026-08-14T13:00:00Z",
      updatedAt: "2026-08-14T13:00:01Z",
      message: "Removing resource",
      detail: null,
      result: {},
    }],
  };
  await userEvent.click(screen.getByRole("button", {
    name: "Refresh state",
  }));
  await waitFor(() => expect(within(tree).getByRole("treeitem", {
    name: /^capture, Deleting$/,
  })).toBeInTheDocument());
  expect(screen.getAllByText("Deleting")).not.toHaveLength(0);
  expect(screen.getByRole("button", { name: "Delete resource" }))
    .toBeDisabled();
});


test("shows failed operation details with the selected resource", async () => {
  server.use(
    http.get("*/api/v1/operations", () => HttpResponse.json({
      operations: [{
        id: "reset-capture",
        kind: "reset",
        label: "Reset captureproxies/capture",
        status: "failed",
        targetIds: ["resource:captureproxies:capture"],
        createdAt: "2026-08-24T04:20:00Z",
        updatedAt: "2026-08-24T04:20:01Z",
        message: "Operation failed",
        detail: (
          "captureproxies.migrations.opensearch.org capture was not found"
        ),
        result: {},
      }],
    })),
  );

  renderApp();

  expect(await screen.findByRole("heading", {
    name: "Recent operation failed",
  })).toBeInTheDocument();
  expect(screen.getAllByText("Delete resource captureproxies/capture"))
    .not.toHaveLength(0);

  await userEvent.click(screen.getByText("Failure details"));

  expect(screen.getAllByText(
    "captureproxies.migrations.opensearch.org capture was not found",
  )).not.toHaveLength(0);
  expect(screen.queryByText("No diagnostics for this resource.")).toBeNull();
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
  await within(tree).findByRole("treeitem", {
    name: /^capture, Failed$/,
  });

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

  let observedInsertion = false;
  const insertionObserver = new MutationObserver(() => {
    observedInsertion ||= Boolean(
      tree.querySelector(
        `[data-node-id="${insertedId}"].inserted`,
      ),
    );
  });
  insertionObserver.observe(tree, {
    attributes: true,
    attributeFilter: ["class"],
    childList: true,
    subtree: true,
  });
  await client.invalidateQueries({ queryKey: ["manage-state"] });
  await within(tree).findByRole(
    "treeitem",
    { name: /^capture-next, Ready$/ },
  );

  await waitFor(() => expect(observedInsertion).toBe(true));
  insertionObserver.disconnect();
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


test("treats workflow absence during submit as tracked replacement progress", async () => {
  server.use(
    http.get("*/api/v1/manage/state", () =>
      HttpResponse.json({
        ...manageSnapshot,
        workflow: null,
        problems: [{
          source: "argo",
          message: (
            '404: workflows.argoproj.io "migration-workflow" not found'
          ),
          retryable: true,
        }, {
          source: "argo",
          message: "Argo permission check failed",
          retryable: true,
        }],
      }),
    ),
    http.get("*/api/v1/operations", () => HttpResponse.json({
      operations: [{
        id: "operation-submit-gap",
        kind: "submit",
        label: "Submit workflow configuration",
        status: "waiting",
        targetIds: [],
        createdAt: "2026-08-13T13:00:00Z",
        updatedAt: "2026-08-13T13:00:01Z",
        message: "Workflow accepted; waiting for refreshed cluster state",
        detail: null,
        result: { workflowName: "migration-workflow" },
      }],
    })),
  );

  renderApp();

  expect(await screen.findByText("Submit workflow configuration"))
    .toBeInTheDocument();
  expect(screen.getByText(
    "Workflow accepted; waiting for refreshed cluster state",
  )).toBeInTheDocument();
  expect(screen.queryByText(
    '404: workflows.argoproj.io "migration-workflow" not found',
  )).toBeNull();
  expect(screen.getByText("Argo permission check failed"))
    .toBeInTheDocument();
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
  expect(within(configTree).queryByRole("columnheader", { name: "State" }))
    .toBeNull();
  expect(within(configTree).getByRole("columnheader", { name: "Row actions" }))
    .toBeInTheDocument();
  expect(screen.getByRole("checkbox", {
    name: "Show field documentation",
  })).toBeChecked();
  expect(screen.getByRole("checkbox", {
    name: "Show optional fields",
  })).toBeChecked();
  const credentials = within(configTree).getByRole("row", {
    name: /Credentials secret/,
  });
  const credentialsLabel = within(credentials)
    .getByText("Credentials secret", { selector: "strong" })
    .closest(".property-label");
  expect(credentialsLabel).not.toHaveAttribute("title");
  expect(within(credentials).getByText(
    "Kubernetes Secret containing the HTTP credentials.",
  )).toBeInTheDocument();
  // Authored/Generated/optional badges were dropped as clutter; the
  // clear action and default hints carry that information now.
  expect(within(credentials).queryByText("Authored")).toBeNull();
  expect(configTree.querySelector(".status-dot")).toBeNull();
  const timeout = within(configTree).getByRole("row", { name: /Timeout/ });
  await userEvent.click(timeout);
  const timeoutInput = within(timeout).getByRole("spinbutton", {
    name: "Timeout",
  });
  expect(timeoutInput).toHaveValue(null);
  expect(timeoutInput).toHaveAttribute("placeholder", "30");
  await userEvent.click(timeoutInput);
  expect(timeoutInput).not.toHaveAttribute("placeholder");
  await userEvent.tab();
  expect(timeoutInput).toHaveAttribute("placeholder", "30");

  expect(within(timeout).queryByText("Generated")).toBeNull();
  expect(screen.getByText("runtime timeout")).toBeInTheDocument();
  expect(screen.getByText(
    "Generated from the standard runtime profile.",
  )).toBeInTheDocument();
  const state = timeout.querySelector(".property-action-cell");
  expect(state?.querySelector(".property-action-content")).toBeInTheDocument();
  // Healthy fields render no status chip and no per-field revert; both
  // added clutter to every row.
  expect(within(state as HTMLElement).queryByText("ok")).toBeNull();
  expect(within(state as HTMLElement).queryByRole("button", {
    name: "Revert Timeout to default",
  })).toBeNull();

  await userEvent.click(screen.getByRole("checkbox", {
    name: "Show field documentation",
  }));
  expect(screen.queryByText("runtime timeout")).toBeNull();
  expect(screen.queryByText(
    "Generated from the standard runtime profile.",
  )).toBeNull();
  expect(within(credentials).queryByText(
    "Kubernetes Secret containing the HTTP credentials.",
  )).toBeNull();
  expect(credentialsLabel).toHaveAttribute(
    "title",
    "Kubernetes Secret containing the HTTP credentials.",
  );

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
  await userEvent.click(
    await screen.findByRole("button", {
      name: /Clear Allow insecure and use the default/i,
    }),
  );
  await waitFor(() => expect(
    within(configTree).getByRole("row", { name: /Allow insecure/i }),
  ).toHaveTextContent("Uses default"));

  const optionalFields = screen.getByRole("checkbox", {
    name: "Show optional fields",
  });
  const expertFields = screen.getByRole("checkbox", {
    name: "Show expert fields",
  });
  expect(optionalFields).not.toBeChecked();
  await userEvent.click(expertFields);
  expect(expertFields).toBeChecked();
  expect(optionalFields).toBeChecked();

  await userEvent.click(optionalFields);
  expect(optionalFields).not.toBeChecked();
  expect(expertFields).not.toBeChecked();
});


test("opens a selected snapshot slice through the projected edit target", async () => {
  const snapshot = structuredClone(manageSnapshot);
  const sectionId = "section:Snapshot Migration";
  const groupId = "group:Snapshot Migration:Backfill";
  const migrationId =
    "resource:snapshotmigrations:source-target-snap-slice-n";
  const template = snapshot.nodes["resource:captureproxies:capture"];
  snapshot.rootIds.push(sectionId);
  snapshot.nodes[sectionId] = {
    ...snapshot.nodes["section:Sources"],
    id: sectionId,
    revision: "snapshot-migration-section-1",
    parentId: null,
    childIds: [groupId],
    label: "Snapshot Migration",
  };
  snapshot.nodes[groupId] = {
    ...snapshot.nodes["group:Sources:Sources"],
    id: groupId,
    revision: "snapshot-migration-group-1",
    parentId: sectionId,
    childIds: [migrationId],
    label: "Backfill",
  };
  snapshot.nodes[migrationId] = {
    ...template,
    id: migrationId,
    revision: "snapshot-migration-1",
    parentId: groupId,
    childIds: [],
    label: "source-target-snap-slice-n",
    status: "pending",
    phase: "Pending Config",
    valueSummary: "Addition pending submission",
    diagnostics: [],
    capabilities: [{
      kind: "edit",
      editTargetId: "edit:sourceClusters.source.version",
      label: "Edit source-target-snap-slice-n",
    }],
    relationships: [],
    comparisons: [],
    resourcePlural: "snapshotmigrations",
    resourceName: "source-target-snap-slice-n",
    resourceType: "Snapshot migration",
    configPresence: { deployed: false, pending: true },
    navigationKey: ["source", "target", "snap", "slice-n"],
  };
  const rawYaml = `sourceClusters:
  source:
    endpoint: https://source.example.com:9200
    version: ES 7.10
    snapshotInfo:
      repos:
        repo:
          repoPathUri: s3://bucket/
          awsRegion: us-east-2
      snapshots:
        snap:
          repoName: repo
          config:
            createSnapshotConfig: {}
snapshotMigrationConfigs:
  - fromSource: source
    toTarget: target
    fromSnapshot: snap
    slices:
      slice-n:
        metadataMigrationConfig: {}
targetClusters:
  target:
    endpoint: https://target.example.com:9200
`;
  const projection = projectConfigYaml(rawYaml);
  const draft: ConfigDraft = {
    baseRevision: "snapshot-slice-base",
    draftRevision: "snapshot-slice-draft",
    dirty: false,
    editState: projection.editState,
    rawYaml,
    notices: [],
  };
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
  );
  renderApp(draft);

  await userEvent.click(await screen.findByRole("button", {
    name: /Open source-target-snap-slice-n/,
  }));
  await userEvent.click(screen.getByRole("button", {
    name: "Edit configuration",
  }));

  expect(await screen.findByRole("heading", {
    name: "Edit source-target-snap-slice-n",
  })).toBeInTheDocument();
  const configTree = screen.getByRole("table", {
    name: "Configuration fields",
  });
  expect(within(configTree).getByRole("row", { name: /From Source/ }))
    .toBeInTheDocument();
  expect(within(configTree).queryByRole("row", { name: /^Endpoint/ }))
    .toBeNull();
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
  renderApp(expertDraft);
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
  expect(document.querySelector(".config-scroll-space")).not.toBeInTheDocument();
  expect(screen.getByRole("checkbox", {
    name: "Show optional fields",
  })).toBeChecked();
  expect(within(config).getByRole("row", { name: /Optional group/ }))
    .toBeInTheDocument();
  const expandOptional = within(config).getByRole("button", {
    name: "Expand Optional group",
  });
  expect(expandOptional)
    .toBeInTheDocument();
  expect(within(config).queryByRole("row", { name: /Optional child/ }))
    .toBeNull();
  await userEvent.click(expandOptional);
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

  fireEvent.click(screen.getByRole("button", { name: "Expand all" }));
  expect(within(config).getByRole("row", { name: /^legacy/ }))
    .toHaveClass("inserted");
  expect(within(config).getByRole("row", { name: /Expert mode/ }))
    .toBeInTheDocument();
});


test("starts dense subtrees collapsed and reopens one level at a time", async () => {
  const denseDraft = structuredClone(configDraft);
  const sources = denseDraft.editState.nodes.find(
    (node) => node.id === "edit:sourceClusters",
  );
  const legacy = sources?.children.find(
    (node) => node.id === "edit:sourceClusters.legacy",
  );
  if (!legacy) throw new Error("Missing source fixture");

  legacy.children.push({
    id: "edit:sourceClusters.legacy.largeSettings",
    path: ["sourceClusters", "legacy", "largeSettings"],
    label: "Large settings",
    valueKind: "object",
    presence: "required",
    status: "ok",
    diagnostics: [],
    children: [
      {
        id: "edit:sourceClusters.legacy.largeSettings.nested",
        path: ["sourceClusters", "legacy", "largeSettings", "nested"],
        label: "Nested settings",
        valueKind: "object",
        presence: "required",
        status: "ok",
        diagnostics: [],
        children: [{
          id: "edit:sourceClusters.legacy.largeSettings.nested.deep",
          path: [
            "sourceClusters",
            "legacy",
            "largeSettings",
            "nested",
            "deep",
          ],
          label: "Deep value: configured",
          value: "configured",
          valueAuthored: true,
          valueKind: "scalar",
          valueType: "string",
          presence: "required",
          status: "ok",
          diagnostics: [],
          children: [],
        }],
      },
      ...Array.from({ length: 12 }, (_, index) => ({
        id: `edit:sourceClusters.legacy.largeSettings.value${index}`,
        path: [
          "sourceClusters",
          "legacy",
          "largeSettings",
          `value${index}`,
        ],
        label: `Value ${index + 1}: configured`,
        value: "configured",
        valueAuthored: true,
        valueKind: "scalar" as const,
        valueType: "string" as const,
        presence: "required" as const,
        status: "ok" as const,
        diagnostics: [],
        children: [],
      })),
    ],
  });

  renderApp(denseDraft);
  await enterEditMode();

  const config = await screen.findByRole("table", {
    name: "Configuration fields",
  });
  const expandLarge = within(config).getByRole("button", {
    name: "Expand Large settings",
  });
  expect(within(config).queryByRole("row", { name: /Nested settings/ }))
    .toBeNull();

  await userEvent.click(expandLarge);
  const nestedRow = within(config).getByRole("row", {
    name: /Nested settings/,
  });
  expect(within(config).queryByRole("row", { name: /Deep value/ }))
    .toBeNull();

  await userEvent.click(within(nestedRow).getByRole("button", {
    name: "Expand Nested settings",
  }));
  expect(within(config).getByRole("row", { name: /Deep value/ }))
    .toBeInTheDocument();

  const largeRow = within(config).getByRole("row", {
    name: /Large settings/,
  });
  await userEvent.click(within(largeRow).getByRole("button", {
    name: "Collapse Large settings",
  }));
  await waitFor(() => expect(
    within(config).queryByRole("row", { name: /Nested settings/ }),
  ).toBeNull());

  await userEvent.click(within(largeRow).getByRole("button", {
    name: "Expand Large settings",
  }));
  expect(within(config).getByRole("row", { name: /Nested settings/ }))
    .toBeInTheDocument();
  expect(within(config).queryByRole("row", { name: /Deep value/ }))
    .toBeNull();
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
  expect(screen.getByRole("heading", {
    name: "Planned workflow dependencies",
  }))
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
    name: /Config Map/,
  })).toBeNull();

  await userEvent.click(within(resources).getByRole("treeitem", {
    name: /^replay$/,
  }));

  expect(await screen.findByRole("heading", { name: "Edit replay" }))
    .toBeInTheDocument();
  expect(await within(config).findByRole("row", {
    name: /^Config Map /,
  })).toBeInTheDocument();
  expect(within(config).queryByRole("row", {
    name: /Endpoint/,
  })).toBeNull();
});


test("clears a selected runtime group when entering workflow-level editing", async () => {
  renderApp();

  const runtimeTree = await screen.findByRole("tree", {
    name: "Workflow resources",
  });
  await userEvent.click(within(runtimeTree).getByRole("treeitem", {
    name: /^Replay, running$/,
  }));
  expect(within(runtimeTree).getByRole("treeitem", {
    name: /^Replay, running$/,
  })).toHaveAttribute("aria-selected", "true");

  await enterEditMode();

  const configTree = await screen.findByRole("tree", {
    name: "Workflow resources",
  });
  expect(await screen.findByText("Workflow configuration"))
    .toBeInTheDocument();
  expect(screen.getByRole("table", {
    name: "Configuration fields",
  })).toHaveTextContent("Source clusters");
  expect(within(configTree).queryByRole("treeitem", {
    selected: true,
  })).toBeNull();
});


test("renders an empty selected configuration group with its add action", async () => {
  const draft = structuredClone(configDraft);
  draft.editState.nodes.push({
    id: "edit:targetClusters",
    path: ["targetClusters"],
    label: "Targets",
    valueKind: "record",
    status: "ok",
    statusCounts: {
      required: 0,
      errors: 0,
      warnings: 0,
      changed: 0,
      gated: 0,
      blocked: 0,
    },
    diagnostics: [],
    inputHint: {
      kind: "record",
      addLabel: "target cluster",
      resourceCollection: {
        navigation: {
          sectionId: "section:Targets",
          sectionLabel: "Targets",
          sectionOrder: 1,
          groupId: "group:Targets:Targets",
          groupLabel: "Targets",
          groupOrder: 0,
        },
        resource: {
          kind: "TargetConfig",
          plural: "targetconfigs",
          typeLabel: "Target cluster",
          identity: { kind: "named" },
        },
      },
    },
    children: [{
      id: "edit:targetClusters:add",
      path: ["targetClusters"],
      label: "+ Add target cluster",
      valueKind: "command",
      status: "ok",
      statusCounts: {
        required: 0,
        errors: 0,
        warnings: 0,
        changed: 0,
        gated: 0,
        blocked: 0,
      },
      diagnostics: [],
      command: {
        requiresName: true,
        editAdded: true,
        autoEditAdded: true,
      },
      children: [],
    }],
  });
  const navigation = setNavigation(draft);
  ensureNavigationGroup(navigation, {
    sectionId: "section:Targets",
    sectionLabel: "Targets",
    groupId: "group:Targets:Targets",
    groupLabel: "Targets",
  });
  navigation.nodes["section:Targets"].capabilities = [{
    kind: "edit",
    editTargetId: "edit:targetClusters",
    label: "Edit Targets",
  }];
  navigation.nodes["group:Targets:Targets"].capabilities = [{
    kind: "edit",
    editTargetId: "edit:targetClusters",
    label: "Edit Targets",
  }];
  renderApp(draft);
  await enterEditMode();

  const tree = await screen.findByRole("tree", {
    name: "Workflow resources",
  });
  const targetGroup = within(tree).getAllByRole("treeitem", {
    name: /^Targets$/,
  }).find((item) => item.getAttribute("aria-level") === "1");
  if (!targetGroup) throw new Error("Missing target configuration group");
  await userEvent.click(targetGroup);

  const table = await screen.findByRole("table", {
    name: "Configuration fields",
  });
  expect(within(table).getByRole("row", { name: /^Targets/ }))
    .toBeInTheDocument();
  expect(within(table).getByRole("button", {
    name: "Add target cluster",
  })).toBeInTheDocument();
});


test("opens nested definitions from navigation and referenced fields", async () => {
  const draft = addLegacySourceNavigation(structuredClone(configDraft));
  const source = draft.editState.nodes[0]?.children?.[0];
  if (!source) throw new Error("Missing source edit node");
  source.children = [
    ...(source.children ?? []),
    {
      id: "edit:sourceClusters.legacy.snapshotInfo",
      path: ["sourceClusters", "legacy", "snapshotInfo"],
      label: "Snapshot information",
      valueKind: "object",
      status: "ok",
      diagnostics: [],
      children: [{
        id: "edit:sourceClusters.legacy.snapshotInfo.repos",
        path: ["sourceClusters", "legacy", "snapshotInfo", "repos"],
        label: "Repositories",
        valueKind: "record",
        status: "ok",
        inputHint: {
          kind: "record",
          addLabel: "snapshot repository",
          definitionCollection: {
            ownerAncestorLevels: 2,
            navigation: {
              groupLabel: "Repositories",
              groupOrder: 0,
            },
            definition: {
              typeLabel: "Snapshot repository",
            },
          },
        },
        diagnostics: [],
        children: [{
          id: "edit:sourceClusters.legacy.snapshotInfo.repos.repo1",
          path: [
            "sourceClusters",
            "legacy",
            "snapshotInfo",
            "repos",
            "repo1",
          ],
          label: "repo1",
          valueKind: "object",
          removable: true,
          status: "ok",
          diagnostics: [],
          children: [{
            id: "edit:sourceClusters.legacy.snapshotInfo.repos.repo1.repoPathUri",
            path: [
              "sourceClusters",
              "legacy",
              "snapshotInfo",
              "repos",
              "repo1",
              "repoPathUri",
            ],
            label: "Repository URI",
            value: "s3://snapshots/repo1",
            valueKind: "scalar",
            valueType: "string",
            status: "ok",
            diagnostics: [],
            children: [],
          }],
        }],
      }, {
        id: "edit:sourceClusters.legacy.snapshotInfo.snapshots",
        path: ["sourceClusters", "legacy", "snapshotInfo", "snapshots"],
        label: "Snapshots",
        valueKind: "record",
        status: "ok",
        inputHint: {
          kind: "record",
          addLabel: "source snapshot",
          definitionCollection: {
            ownerAncestorLevels: 2,
            navigation: {
              groupLabel: "Snapshots",
              groupOrder: 1,
            },
            definition: {
              typeLabel: "Source snapshot",
            },
          },
        },
        diagnostics: [],
        children: [{
          id: "edit:sourceClusters.legacy.snapshotInfo.snapshots.nightly",
          path: [
            "sourceClusters",
            "legacy",
            "snapshotInfo",
            "snapshots",
            "nightly",
          ],
          label: "nightly",
          valueKind: "object",
          removable: true,
          referenceTargetId:
            "edit:sourceClusters.legacy.snapshotInfo.snapshots.nightly",
          referenceLabel: "Source Snapshot 'nightly'",
          status: "ok",
          diagnostics: [],
          children: [{
            id: "edit:sourceClusters.legacy.snapshotInfo.snapshots.nightly.repoName",
            path: [
              "sourceClusters",
              "legacy",
              "snapshotInfo",
              "snapshots",
              "nightly",
              "repoName",
            ],
            label: "Repository",
            value: "repo1",
            valueKind: "scalar",
            valueType: "string",
            status: "ok",
            inputHint: {
              kind: "reference",
              options: [{
                label: "repo1",
                value: "repo1",
                editTargetId:
                  "edit:sourceClusters.legacy.snapshotInfo.repos.repo1",
              }],
            },
            diagnostics: [],
            children: [],
          }],
        }],
      }],
    },
  ];
  renderApp(draft);
  await enterEditMode();

  const tree = screen.getByRole("tree", { name: "Workflow resources" });
  const sourceItem = await within(tree).findByRole("treeitem", {
    name: /^legacy/,
  });
  // Containers with nested definitions auto-expand.
  expect(within(sourceItem).getByRole("button", {
    name: "Collapse legacy",
  })).toBeInTheDocument();
  await userEvent.click(await within(tree).findByRole("treeitem", {
    name: /^nightly/,
  }));

  expect(await screen.findByRole("heading", { name: "Edit nightly" }))
    .toBeInTheDocument();
  // A self-referential backlink says nothing; the definition is home.
  expect(screen.queryByRole("button", {
    name: "Defined in Source Snapshot 'nightly'",
  })).toBeNull();
  const config = screen.getByRole("table", {
    name: "Configuration fields",
  });
  expect(within(config).getByRole("row", { name: /Repository/ }))
    .toBeInTheDocument();
  expect(within(config).queryByRole("row", { name: /Endpoint/ })).toBeNull();

  await userEvent.click(sourceItem);
  expect(await screen.findByRole("heading", { name: "Edit legacy" }))
    .toBeInTheDocument();
  // The snapshot definition is the source's own content: inlined, with
  // no link row standing in for it.
  const snapshotRow = await within(config).findByRole("row", {
    name: /^nightly/,
  });
  expect(within(snapshotRow).queryByRole("button", {
    name: "Defined in Source Snapshot 'nightly'",
  })).toBeNull();
  expect(within(config).getAllByRole("row", { name: /Repository/ }).length)
    .toBeGreaterThan(1);

  await userEvent.click(within(config).getByRole("button", {
    name: "Defined in repo1",
  }));

  expect(await screen.findByRole("heading", { name: "Edit repo1" }))
    .toBeInTheDocument();
  expect(within(config).getByRole("row", { name: /Repository URI/ }))
    .toBeInTheDocument();
  expect(within(tree).getByRole("treeitem", { name: /^repo1/ }))
    .toHaveAttribute("aria-selected", "true");

  await userEvent.click(screen.getByRole("button", { name: "Back to legacy" }));
  expect(await screen.findByRole("heading", { name: "Edit legacy" }))
    .toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /^Back to/ })).toBeNull();
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
  const navigation = setNavigation(validDraft, snapshot);
  navigation.nodes[source.id].configState = {
    validationErrors: 0,
    validationWarnings: 0,
    draftChangeCount: 0,
  };
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
  );
  renderApp(validDraft);

  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const sourceRow = within(tree).getByRole("treeitem", {
    name: /^legacy$/,
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
  const warningDraft = structuredClone(configDraft);
  const navigation = setNavigation(warningDraft, snapshot);
  navigation.nodes[replay.id].configState = {
    validationErrors: 0,
    validationWarnings: 1,
    draftChangeCount: 0,
  };
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
  );
  renderApp(warningDraft);

  await enterEditMode();
  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const replayRow = within(tree).getByRole("treeitem", {
    name: /^replay$/,
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
  const navigation = setNavigation(invalidDraft, snapshot);
  navigation.nodes[source.id].configState = {
    validationErrors: 1,
    validationWarnings: 0,
    draftChangeCount: 0,
  };
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
  );
  renderApp(invalidDraft);

  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  // Sections whose only group repeats their name merge into one row.
  const sourceGroup = within(tree).getAllByRole("treeitem", {
    name: /^Sources$/,
  }).find((item) => item.getAttribute("aria-level") === "1");
  const sourceRow = within(tree).getByRole("treeitem", {
    name: /^legacy$/,
  });
  expect(sourceGroup).toHaveClass("validation-error-ancestor");
  expect(sourceRow).toHaveClass("validation-error-item");
  expect(within(sourceRow).getByLabelText("1 validation issue"))
    .toBeInTheDocument();

  const config = screen.getByRole("table", {
    name: "Configuration fields",
  });
  expect(config.closest(".config-table-panel"))
    .toHaveClass("scope-validation-error");
  expect(within(config).getByRole("row", { name: /^Authentication/ }))
    .toHaveClass("validation-error-ancestor");
  expect(within(config).getByRole("row", { name: /^Credentials secret/ }))
    .toHaveClass("validation-error-item");
  expect(within(config).getByRole("row", { name: /^Endpoint/ }))
    .not.toHaveClass("validation-error-item", "validation-error-ancestor");
  expect(screen.queryByRole("heading", { name: "Validation" })).toBeNull();
});


test("highlights unsaved resources and fields with previous values", async () => {
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
    disabledReason: null,
  }];
  snapshot.nodes["group:Sources:Sources"].childIds = [source.id];
  snapshot.nodes["group:Live Traffic Migration:Capture"].childIds = [];

  const dirtyDraft = structuredClone(configDraft);
  const sourceCollection = dirtyDraft.editState.nodes.find(
    (node) => node.id === "edit:sourceClusters",
  );
  const sourceEdit = sourceCollection?.children?.find(
    (node) => node.id === "edit:sourceClusters.legacy",
  );
  const endpoint = sourceEdit?.children?.find(
    (node) => node.id === "edit:sourceClusters.legacy.endpoint",
  );
  if (!sourceEdit || !endpoint) throw new Error("Missing source fixture");
  dirtyDraft.dirty = true;
  dirtyDraft.draftRevision = "dirty-highlight";
  sourceEdit.draftChangeCount = 1;
  endpoint.value = "https://next.example.com:9200";
  endpoint.label = "Endpoint: https://next.example.com:9200";
  endpoint.draftChangeCount = 1;
  endpoint.draftChange = {
    kind: "modified",
    previousValue: "https://legacy.example.com:9200",
    previousValuePresent: true,
  };
  const navigation = setNavigation(dirtyDraft, snapshot);
  navigation.nodes[source.id].configState = {
    validationErrors: 0,
    validationWarnings: 0,
    draftChangeCount: 1,
  };

  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
  );
  renderApp(dirtyDraft);
  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const sourceSection = within(tree).getAllByRole("treeitem", {
    name: /^Sources$/,
  }).find((item) => item.getAttribute("aria-level") === "1");
  const sourceRow = within(tree).getByRole("treeitem", {
    name: /^legacy, 1 unsaved change$/,
  });
  expect(sourceSection).toHaveClass("draft-change-ancestor");
  expect(sourceRow).toHaveClass("draft-change-item");
  // The count is announced and shown as shading, not repeated as text.
  expect(within(sourceRow).queryByText("1 unsaved change")).toBeNull();

  const config = screen.getByRole("table", { name: "Configuration fields" });
  const endpointRow = within(config).getByRole("row", { name: /^Endpoint/ });
  const expectedTitle = "Changed in this edit. Previous value: https://legacy.example.com:9200.";
  expect(endpointRow).toHaveClass("draft-change-item");
  expect(endpointRow.querySelector(".property-label"))
    .toHaveAttribute("title", expectedTitle);
  expect(within(endpointRow).getByText("Unsaved change"))
    .toHaveAttribute(
      "title",
      `Cyan highlighting marks an unsaved browser draft change. ${expectedTitle}`,
    );
});


test("groups Kafka clusters and topics without repeating resource types", async () => {
  const snapshot = structuredClone(manageSnapshot);
  const section = snapshot.nodes["section:Live Traffic Migration"];
  const captureGroup = snapshot.nodes["group:Live Traffic Migration:Capture"];
  const capture = snapshot.nodes["resource:captureproxies:capture"];
  const bufferGroupId = "group:Live Traffic Migration:Buffer";
  const clusterGroupId = `${bufferGroupId}:Kafka Clusters`;
  const topicGroupId = `${bufferGroupId}:Previously Captured Traffic`;
  const kafkaId = "resource:kafkaclusters:default";
  const s3Id = "resource:capturedtraffics:proxy-topic";
  section.childIds = [bufferGroupId, ...section.childIds];
  snapshot.nodes[bufferGroupId] = {
    ...captureGroup,
    id: bufferGroupId,
    revision: "buffer-group-1",
    childIds: [clusterGroupId, topicGroupId],
    label: "Buffer",
  };
  snapshot.nodes[clusterGroupId] = {
    ...captureGroup,
    id: clusterGroupId,
    revision: "cluster-group-1",
    parentId: bufferGroupId,
    childIds: [kafkaId],
    label: "Kafka Clusters",
  };
  snapshot.nodes[topicGroupId] = {
    ...captureGroup,
    id: topicGroupId,
    revision: "topic-group-1",
    parentId: bufferGroupId,
    childIds: [s3Id],
    label: "Previously Captured Traffic",
  };
  snapshot.nodes[kafkaId] = {
    ...capture,
    id: kafkaId,
    revision: "kafka-1",
    parentId: clusterGroupId,
    label: "default",
    valueSummary: "Configured",
    diagnostics: [],
    capabilities: [],
    resourcePlural: "kafkaclusters",
    resourceName: "default",
    resourceType: "Kafka cluster",
  };
  snapshot.nodes[s3Id] = {
    ...capture,
    id: s3Id,
    revision: "s3-1",
    parentId: topicGroupId,
    label: "proxy-topic",
    valueSummary: "Configured",
    diagnostics: [],
    capabilities: [],
    resourcePlural: "capturedtraffics",
    resourceName: "proxy-topic",
    resourceType: "Captured traffic",
  };
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
  );
  renderApp();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  expect(within(tree).getByRole("treeitem", {
    name: /^Kafka Clusters,/,
  })).toBeInTheDocument();
  expect(within(tree).getByRole("treeitem", {
    name: /^Previously Captured Traffic,/,
  })).toBeInTheDocument();
  const kafka = within(tree).getByRole("treeitem", {
    name: /^default, Ready$/,
  });
  const s3 = within(tree).getByRole("treeitem", {
    name: /^proxy-topic, Ready$/,
  });
  expect(within(kafka).queryByText("Kafka cluster")).toBeNull();
  expect(within(kafka).getByText("Ready")).toBeInTheDocument();
  expect(within(kafka).getByText("Configured")).toBeInTheDocument();
  expect(within(s3).queryByText("Captured traffic")).toBeNull();
  expect(within(s3).getByText("Ready")).toBeInTheDocument();
  expect(within(s3).getByText("Configured")).toBeInTheDocument();
});


test("creates and renames a provisional Kafka cluster from a reference", async () => {
  const rawYaml = `sourceClusters:
  source:
    endpoint: https://source.example.com:9200
    version: ES 7.10.2
traffic:
  kafkaClusters:
    main-k:
      autoCreate: {}
      topics:
        capture: {}
  proxies:
    capture:
      source: source
      kafka: main-k
      kafkaTopic: capture
      proxyConfig: {}
  replayers: {}
snapshotMigrationConfigs: []
`;
  const projection = projectConfigYaml(rawYaml);
  const draft: ConfigDraft = {
    ...structuredClone(configDraft),
    rawYaml,
    editState: projection.editState,
  };
  const { client } = renderApp(draft);
  await enterEditMode();
  const tree = await screen.findByRole("tree", {
    name: "Workflow resources",
  });
  await userEvent.click(within(tree).getByRole("treeitem", {
    name: "capture",
  }));

  const kafkaClusterRow = await screen.findByRole("row", {
    name: /Kafka cluster/i,
  });
  expect(within(kafkaClusterRow).getByRole("combobox", {
    name: "Kafka",
  })).toHaveValue("main-k");
  await userEvent.click(within(kafkaClusterRow).getByRole("button", {
    name: "Create new Kafka cluster",
  }));

  await waitFor(() => expect(
    client.getQueryData<BrowserConfigDraft>(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
    )?.config,
  ).toMatchObject({
    traffic: {
      kafkaClusters: {
        "kafka-cluster": { autoCreate: {} },
      },
      proxies: {
        capture: { kafka: "kafka-cluster" },
      },
    },
  }));
  expect(await within(tree).findByRole("treeitem", {
    name: /^kafka-cluster/,
  })).toBeInTheDocument();
  expect(await screen.findByRole("textbox", {
    name: "New name for kafka-cluster",
  })).toHaveFocus();
  expect(screen.getByRole("button", { name: "Back to capture" }))
    .toBeInTheDocument();
});


test("creates and selects a proxy topic under the selected Kafka cluster", async () => {
  const draft = structuredClone(configDraft);
  draft.rawYaml = JSON.stringify({
    traffic: {
      kafkaClusters: {
        shared: {
          autoCreate: {},
          topics: {},
        },
      },
      proxies: {
        capture: {
          source: "source",
          kafka: "shared",
          kafkaTopic: "",
          proxyConfig: { listenPort: 9201 },
        },
      },
    },
  }, null, 2);
  const traffic = draft.editState.nodes.find(
    (node) => node.id === "edit:traffic",
  );
  if (!traffic) throw new Error("Missing traffic edit node");
  traffic.children.unshift({
    id: "edit:traffic.proxies.capture.kafkaTopic",
    path: ["traffic", "proxies", "capture", "kafkaTopic"],
    label: "Kafka topic",
    valueKind: "scalar",
    valueType: "string",
    presence: "required",
    required: true,
    status: "required",
    inputHint: {
      kind: "reference",
      sourcePath: ["traffic", "kafkaClusters", "shared", "topics"],
      allowCustom: false,
      options: [],
      createReference: {
        label: "Create topic for this proxy",
        valueFromPathSegmentFromEnd: 2,
        description: "Create and select an explicit topic.",
      },
      message: "Choose a topic from the selected Kafka cluster.",
    },
    diagnostics: [],
    children: [],
  });
  const { client } = renderApp(draft);
  await enterEditMode();

  expect(screen.queryByLabelText("Kafka topic")).toBeNull();
  await userEvent.click(screen.getByRole("button", {
    name: "Create topic for this proxy",
  }));

  await waitFor(() => expect(
    client.getQueryData<BrowserConfigDraft>(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
    )?.config,
  ).toMatchObject({
    traffic: {
      kafkaClusters: {
        shared: {
          topics: {
            capture: {},
          },
        },
      },
      proxies: {
        capture: {
          kafkaTopic: "capture",
        },
      },
    },
  }));
  const topicName = await screen.findByRole("textbox", {
    name: "New name for capture",
  });
  expect(topicName).toHaveFocus();
  expect(screen.getByRole("button", { name: "Back to capture" }))
    .toBeInTheDocument();
});


test("offers topic creation beside a populated topic selector", async () => {
  const draft = structuredClone(configDraft);
  draft.rawYaml = JSON.stringify({
    traffic: {
      kafkaClusters: {
        shared: {
          autoCreate: {},
          topics: {
            existing: {},
          },
        },
      },
      proxies: {
        capture: {
          source: "source",
          kafka: "shared",
          kafkaTopic: "existing",
          proxyConfig: { listenPort: 9201 },
        },
      },
    },
  }, null, 2);
  const traffic = draft.editState.nodes.find(
    (node) => node.id === "edit:traffic",
  );
  if (!traffic) throw new Error("Missing traffic edit node");
  traffic.children.unshift({
    id: "edit:traffic.proxies.capture.kafkaTopic",
    path: ["traffic", "proxies", "capture", "kafkaTopic"],
    label: "Kafka topic",
    value: "existing",
    valueAuthored: "existing",
    valueKind: "scalar",
    valueType: "string",
    presence: "required",
    required: true,
    status: "ok",
    inputHint: {
      kind: "reference",
      sourcePath: ["traffic", "kafkaClusters", "shared", "topics"],
      allowCustom: false,
      options: [{
        label: "existing",
        value: "existing",
      }],
      createReference: {
        label: "Create topic for this proxy",
        valueFromPathSegmentFromEnd: 2,
        description: "Create and select an explicit topic.",
      },
      message: "Choose a topic from the selected Kafka cluster.",
    },
    diagnostics: [],
    children: [],
  });
  const { client } = renderApp(draft);
  await enterEditMode();

  const topic = screen.getByRole("combobox", { name: "Kafka topic" });
  expect(within(topic).queryByRole("option", {
    name: "Create topic for this proxy",
  })).toBeNull();
  await userEvent.click(screen.getByRole("button", {
    name: "Create topic for this proxy",
  }));

  await waitFor(() => expect(
    client.getQueryData<BrowserConfigDraft>(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
    )?.config,
  ).toMatchObject({
    traffic: {
      kafkaClusters: {
        shared: {
          topics: {
            existing: {},
            capture: {},
          },
        },
      },
      proxies: {
        capture: {
          kafkaTopic: "capture",
        },
      },
    },
  }));
  const topicName = await screen.findByRole("textbox", {
    name: "New name for capture",
  });
  expect(topicName).toHaveFocus();
  expect(screen.getByRole("button", { name: "Back to capture" }))
    .toBeInTheDocument();
});


test("keeps resource creation adjacent to an existing reference", async () => {
  const draft = structuredClone(configDraft);
  const traffic = draft.editState.nodes.find(
    (node) => node.id === "edit:traffic",
  );
  if (!traffic) throw new Error("Missing traffic edit node");
  traffic.children.unshift({
    id: "edit:traffic.source",
    path: ["traffic", "source"],
    label: "Source cluster",
    value: "legacy",
    valueAuthored: true,
    valueKind: "scalar",
    valueType: "string",
    presence: "required",
    required: true,
    status: "ok",
    inputHint: {
      kind: "reference",
      sourcePath: ["sourceClusters"],
      allowCustom: false,
      options: [{
        label: "legacy",
        value: "legacy",
        editTargetId: "edit:sourceClusters.legacy",
      }],
      message: "Choose a configured source cluster.",
    },
    diagnostics: [],
    children: [],
  });
  const { client } = renderApp(draft);
  await enterEditMode();

  const source = await screen.findByRole("combobox", {
    name: "Source cluster",
  });
  expect(source).toHaveValue("legacy");
  expect(screen.getByRole("button", {
    name: "Defined in legacy",
  })).toBeInTheDocument();
  expect(within(source).queryByRole("option", {
    name: /Create/i,
  })).toBeNull();

  await userEvent.click(screen.getByRole("button", {
    name: "Create new Source cluster",
  }));

  await waitFor(() => expect(
    client.getQueryData<BrowserConfigDraft>(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
    )?.config,
  ).toMatchObject({
    sourceClusters: {
      "source-cluster": {},
    },
    traffic: {
      source: "source-cluster",
    },
  }));
  const provisionalName = await screen.findByRole("textbox", {
    name: "New name for source-cluster",
  });
  expect(provisionalName).toHaveFocus();
});


test("returns to the current snapshot migration after creating a snapshot", async () => {
  const rawYaml = `sourceClusters:
  source:
    endpoint: https://source.example.com:9200
    version: ES 7.10.2
    snapshotInfo:
      repos:
        repo:
          repoPathUri: s3://bucket/
          awsRegion: us-east-2
      snapshots:
        snap:
          repoName: repo
          config:
            createSnapshotConfig: {}
targetClusters:
  target:
    endpoint: https://target.example.com:9200
snapshotMigrationConfigs:
  - fromSource: source
    toTarget: target
    fromSnapshot: snap
    slice: slice-n
    metadataMigrationConfig: {}
traffic:
  kafkaClusters: {}
  proxies: {}
  replayers: {}
`;
  const projection = projectConfigYaml(rawYaml);
  const draft: ConfigDraft = {
    ...structuredClone(configDraft),
    rawYaml,
    editState: projection.editState,
  };
  const { client } = renderApp(draft);
  await enterEditMode();
  const tree = await screen.findByRole("tree", {
    name: "Workflow resources",
  });
  await userEvent.click(await within(tree).findByRole("treeitem", {
    name: /^source-target-snap-slice-n/,
  }));

  await userEvent.click(screen.getByRole("button", {
    name: "Create new Source snapshot",
  }));
  await waitFor(() => expect(
    client.getQueryData<BrowserConfigDraft>(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
    )?.config,
  ).toMatchObject({
    sourceClusters: {
      source: {
        snapshotInfo: {
          snapshots: {
            "source-snapshot": {},
          },
        },
      },
    },
    snapshotMigrationConfigs: [{
      fromSnapshot: "source-snapshot",
      slice: "slice-n",
    }],
  }));
  expect(await within(tree).findByRole("treeitem", {
    name: /^source-snapshot/,
  })).toBeInTheDocument();
  expect(await screen.findByRole("textbox", {
    name: "New name for source-snapshot",
  })).toHaveFocus();

  const back = await screen.findByRole("button", {
    name: "Back to source-target-source-snapshot-slice-n",
  });
  await userEvent.click(back);
  expect(await screen.findByRole("heading", {
    name: "Edit source-target-source-snapshot-slice-n",
  })).toBeInTheDocument();
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
  server.use(
    http.get("*/api/v1/manage/state", () =>
      HttpResponse.json(scopedSnapshot),
    ),
  );
  const { client } = renderApp();

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
    .getAllByRole("treeitem", { name: /^Sources$/ })
    .find((item) => item.getAttribute("aria-level") === "1");
  expect(sourceGroup).toBeDefined();
  if (!sourceGroup) throw new Error("Source group was not rendered");
  await userEvent.click(await within(sourceGroup).findByRole("button", {
    name: "Add source cluster",
  }));
  await userEvent.type(
    screen.getByRole("textbox", { name: "source cluster name" }),
    "next-source",
  );
  await userEvent.click(screen.getByRole("button", {
    name: "Create source cluster",
  }));

  await waitFor(() => expect(
    (
      client.getQueryData<BrowserConfigDraft>(
        BROWSER_CONFIG_DRAFT_QUERY_KEY,
      )?.config as {
        sourceClusters?: Record<string, unknown>;
      }
    ).sourceClusters,
  ).toHaveProperty("next-source"));
});


test("adds nested definitions from their left-navigation groups", async () => {
  const draft = addLegacySourceNavigation(structuredClone(configDraft));
  addSourceDefinitionCollection(draft, {
    addLabel: "snapshot repository",
    collectionName: "repos",
    groupLabel: "Repositories",
    groupOrder: 0,
    typeLabel: "Snapshot repository",
  });
  addSourceDefinitionCollection(draft, {
    addLabel: "source snapshot",
    collectionName: "snapshots",
    groupLabel: "Snapshots",
    groupOrder: 1,
    typeLabel: "Source snapshot",
  });
  const { client } = renderApp(draft);
  await enterEditMode();

  const tree = await screen.findByRole("tree", {
    name: "Workflow resources",
  });
  const sourceItem = within(tree).getByRole("treeitem", {
    name: /^legacy/,
  });
  // Containers with nested definitions auto-expand.
  expect(within(sourceItem).getByRole("button", {
    name: "Collapse legacy",
  })).toBeInTheDocument();
  const repositories = within(tree).getByRole("treeitem", {
    name: /^Repositories$/,
  });
  expect(within(repositories).getByRole("button", {
    name: "Add snapshot repository",
  })).toBeInTheDocument();
  expect(within(tree).getByRole("button", {
    name: "Add source snapshot",
  })).toBeInTheDocument();

  await userEvent.click(within(repositories).getByRole("button", {
    name: "Add snapshot repository",
  }));
  await userEvent.type(within(tree).getByRole("textbox", {
    name: "snapshot repository name",
  }), "repo2");
  await userEvent.keyboard("{Enter}");

  expect(await within(tree).findByRole("treeitem", {
    name: /^repo2/,
  })).toHaveAttribute("aria-selected", "true");
  expect(
    (
      client.getQueryData<BrowserConfigDraft>(
        BROWSER_CONFIG_DRAFT_QUERY_KEY,
      )?.config as {
        sourceClusters?: {
          legacy?: {
            snapshotInfo?: {
              repos?: Record<string, unknown>;
            };
          };
        };
      }
    ).sourceClusters?.legacy?.snapshotInfo?.repos,
  ).toHaveProperty("repo2");
});



test("shows the server reason when configuration cannot be opened", async () => {
  server.use(
    http.get("*/api/v1/config/document", () =>
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
  renderApp(null);

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
  expect(screen.getByRole("heading", {
    name: "Planned workflow dependencies",
  }))
    .toBeInTheDocument();
});


test("repairs raw YAML and returns to the structured editor", async () => {
  const rawDraft = rawRepairDraft();
  const { client } = renderApp(rawDraft);
  await enterEditMode();

  const yaml = await screen.findByRole("textbox", { name: "Workflow YAML" });
  expect(yaml).toHaveValue(rawDraft.rawYaml);
  expect(screen.getByText(
    "Flow sequence in block collection must be closed",
  )).toBeInTheDocument();
  const tree = screen.getByRole("tree", { name: "Workflow resources" });
  const capture = within(tree).getByRole("treeitem", { name: /^capture$/ });
  expect(within(capture).queryByText(/remov/i)).toBeNull();

  const repairedYaml = [
    "sourceClusters:",
    "  source:",
    "    endpoint: https://source.example.com:9200",
    "    version: OS 2.19",
    "targetClusters: {}",
    "snapshotMigrationConfigs: []",
    "",
  ].join("\n");
  fireEvent.change(yaml, { target: { value: repairedYaml } });
  await userEvent.click(screen.getByRole("button", { name: "Check YAML" }));

  await waitFor(() => expect(
    client.getQueryData<BrowserConfigDraft>(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
    ),
  ).toMatchObject({
    dirty: true,
    rawDocument: repairedYaml,
  }));
  expect(await screen.findByRole("table", {
    name: "Configuration fields",
  })).toBeInTheDocument();
  expect(screen.queryByRole("textbox", { name: "Workflow YAML" })).toBeNull();
});


test("opens raw repair when runtime and configuration navigation are empty", async () => {
  const emptySnapshot = structuredClone(manageSnapshot);
  emptySnapshot.workflow = null;
  emptySnapshot.rootIds = [];
  emptySnapshot.nodes = {};
  const rawDraft = rawRepairDraft();
  rawDraft.navigation = {
    ...structuredClone(emptySnapshot),
    revision: "empty-raw-navigation",
  };
  server.use(
    http.get(
      "*/api/v1/manage/state",
      () => HttpResponse.json(emptySnapshot),
    ),
  );
  renderApp(rawDraft);

  expect(await screen.findByRole("heading", {
    name: "No migration resources found",
  })).toBeInTheDocument();
  await enterEditMode();

  expect(await screen.findByRole("textbox", {
    name: "Workflow YAML",
  })).toHaveValue(rawDraft.rawYaml);
  expect(screen.getByText(
    "Flow sequence in block collection must be closed",
  )).toBeInTheDocument();
  expect(screen.queryByRole("heading", {
    name: "No migration resources found",
  })).toBeNull();
});


test("protects and locally discards unsent raw YAML edits on exit", async () => {
  const rawDraft = rawRepairDraft();
  renderApp(rawDraft);
  await enterEditMode();

  fireEvent.change(
    await screen.findByRole("textbox", { name: "Workflow YAML" }),
    { target: { value: "sourceClusters: {}\n" } },
  );
  await userEvent.click(screen.getByRole("button", {
    name: "Exit editing",
  }));
  const firstPrompt = screen.getByRole("dialog", { name: "Leave editing?" });
  await userEvent.click(within(firstPrompt).getByRole("button", {
    name: "Continue editing",
  }));
  expect(screen.getByRole("textbox", { name: "Workflow YAML" }))
    .toHaveValue("sourceClusters: {}\n");

  await userEvent.click(screen.getByRole("button", {
    name: "Exit editing",
  }));
  await userEvent.click(screen.getByRole("button", {
    name: "Discard and exit",
  }));

  expect(await screen.findByRole("button", { name: "Edit configuration" }))
    .toBeInTheDocument();
});


test("restores config-only source and target navigation while editing", async () => {
  const draft = structuredClone(configDraft);
  draft.editState.nodes.push({
    id: "edit:targetClusters",
    path: ["targetClusters"],
    label: "Target clusters",
    valueKind: "record",
    presence: "required",
    essential: true,
    inputHint: {
      kind: "record",
      addLabel: "target cluster",
      resourceCollection: {
        navigation: {
          sectionId: "section:Targets",
          sectionLabel: "Targets",
          sectionOrder: 1,
          groupId: "group:Targets:Targets",
          groupLabel: "Targets",
          groupOrder: 0,
        },
        resource: {
          kind: "TargetConfig",
          plural: "targetconfigs",
          typeLabel: "Target cluster",
          identity: { kind: "named" },
        },
      },
    },
    status: "ok",
    diagnostics: [],
    children: [{
      id: "edit:targetClusters.modern",
      path: ["targetClusters", "modern"],
      label: "modern",
      valueKind: "object",
      presence: "required",
      removable: true,
      status: "ok",
      diagnostics: [],
      children: [{
        id: "edit:targetClusters.modern.endpoint",
        path: ["targetClusters", "modern", "endpoint"],
        label: "Endpoint: https://target.example.com:9200",
        value: "https://target.example.com:9200",
        valueAuthored: true,
        valueKind: "scalar",
        valueType: "string",
        presence: "required",
        required: true,
        status: "ok",
        diagnostics: [],
        children: [],
      }],
    }, {
      id: "edit:targetClusters:add",
      path: ["targetClusters"],
      label: "+ Add target cluster",
      valueKind: "command",
      status: "ok",
      diagnostics: [],
      command: {
        requiresName: true,
        editAdded: true,
        autoEditAdded: true,
      },
      children: [],
    }],
  });
  const navigation = setNavigation(draft);
  addConfigNavigationResource(navigation, {
    id: "resource:sourceconfigs:legacy",
    groupId: "group:Sources:Sources",
    label: "legacy",
    editTargetId: "edit:sourceClusters.legacy",
    resourcePlural: "sourceconfigs",
    resourceType: "Source cluster",
  });
  ensureNavigationGroup(navigation, {
    sectionId: "section:Targets",
    sectionLabel: "Targets",
    groupId: "group:Targets:Targets",
    groupLabel: "Targets",
  });
  addConfigNavigationResource(navigation, {
    id: "resource:targetconfigs:modern",
    groupId: "group:Targets:Targets",
    label: "modern",
    editTargetId: "edit:targetClusters.modern",
    resourcePlural: "targetconfigs",
    resourceType: "Target cluster",
  });
  renderApp(draft);
  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const legacy = within(tree).getByRole("treeitem", { name: /^legacy,/ });
  const modern = within(tree).getByRole("treeitem", { name: /^modern,/ });
  expect(legacy).toBeInTheDocument();
  expect(modern).toBeInTheDocument();

  await userEvent.click(modern);
  expect(await screen.findByRole("heading", { name: "Edit modern" }))
    .toBeInTheDocument();
  expect(screen.getByRole("textbox", { name: "Endpoint" }))
    .toHaveValue("https://target.example.com:9200");
});


test("guards browser back navigation before leaving workflow manage", async () => {
  const confirm = vi.spyOn(globalThis, "confirm").mockReturnValue(false);
  renderApp();
  await screen.findByRole("tree", { name: "Workflow resources" });

  fireEvent.popState(globalThis);

  await waitFor(() => expect(confirm).toHaveBeenCalledWith(
    "Leave Workflow Manage? Active operations will continue in the cluster.",
  ));
  expect(
    screen.getByRole("heading", { name: "Workflow Manage" }),
  ).toBeInTheDocument();
});


test("changes a union inline and inserts its variant fields directly below", async () => {
  const { client } = renderApp();
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
    name: /region/i,
  });
  const updatedAuthRow = within(configTable).getByRole("row", {
    name: /Authentication/,
  });
  const rows = within(configTable).getAllByRole("row");
  expect(rows.indexOf(regionRow)).toBe(rows.indexOf(updatedAuthRow) + 1);
  const authConfig = (
    client.getQueryData<BrowserConfigDraft>(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
    )?.config as {
      sourceClusters?: {
        legacy?: {
          authConfig?: Record<string, unknown>;
        };
      };
    }
  ).sourceClusters?.legacy?.authConfig;
  expect(authConfig).toMatchObject({ sigv4: {} });
  expect(authConfig).not.toHaveProperty("basic");
});


test("applies scalar and exact-node rename operations locally", async () => {
  const { client } = renderApp();
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
  await waitFor(() => expect(
    (
      client.getQueryData<BrowserConfigDraft>(
        BROWSER_CONFIG_DRAFT_QUERY_KEY,
      )?.config as {
        sourceClusters?: {
          legacy?: {
            endpoint?: string;
          };
        };
      }
    ).sourceClusters?.legacy?.endpoint,
  ).toBe("https://next.example.com:9200"));

  const legacyRow = within(configTree).getByRole("row", { name: /^legacy/ });
  await userEvent.click(legacyRow);
  await userEvent.click(within(legacyRow).getByRole("button", {
    name: "Rename legacy",
  }));
  const nameInput = screen.getByRole("textbox", { name: "Configuration name" });
  expect(nameInput).toHaveAttribute(
    "pattern",
    String.raw`^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$`,
  );
  await userEvent.clear(nameInput);
  await userEvent.type(nameInput, "modern");
  await userEvent.click(screen.getByRole("button", { name: "Apply rename" }));
  const resourceTree = screen.getByRole("tree", {
    name: "Workflow resources",
  });
  await userEvent.click(await within(resourceTree).findByRole("treeitem", {
    name: /^modern/,
  }));
  await screen.findByRole("heading", { name: "Edit modern" });

  const config = client.getQueryData<BrowserConfigDraft>(
    BROWSER_CONFIG_DRAFT_QUERY_KEY,
  )?.config as {
    sourceClusters?: Record<string, {
      endpoint?: string;
    }>;
  };
  expect(config.sourceClusters).not.toHaveProperty("legacy");
  expect(config.sourceClusters?.modern).toMatchObject({
    endpoint: "https://next.example.com:9200",
  });
});


test("saves a focused text edit as one resource-level action", async () => {
  let saveRequest: unknown;
  server.use(
    http.put("*/api/v1/config/document", async ({ request }) => {
      saveRequest = await request.json();
      const body = saveRequest as { rawYaml: string };
      return HttpResponse.json({
        modelVersion: "1",
        persistedRevision: "config-base-2",
        rawYaml: body.rawYaml,
      });
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

  await waitFor(() => expect(saveRequest).toMatchObject({
    expectedPersistedRevision: "config-base-1",
  }));
  expect((saveRequest as { rawYaml: string }).rawYaml)
    .toContain("https://saved.example.com:9200");
  expect(screen.queryByRole("button", { name: "Apply" })).toBeNull();
});


test("applies ordinary field edits in the browser without locking the UI", async () => {
  let savedDocument: unknown;
  server.use(
    http.get("*/api/v1/config/document", () => HttpResponse.json({
      modelVersion: "1",
      persistedRevision: "saved-browser-draft",
      rawYaml: `
sourceClusters:
  source:
    endpoint: https://source.example.com:9200
    version: ES 7.10
    allowInsecure: false
targetClusters:
  target:
    endpoint: https://target.example.com:9200
snapshotMigrationConfigs: []
`,
    })),
    http.put("*/api/v1/config/document", async ({ request }) => {
      savedDocument = await request.json();
      const body = savedDocument as {
        rawYaml: string;
      };
      return HttpResponse.json({
        modelVersion: "1",
        persistedRevision: "saved-browser-draft-2",
        rawYaml: body.rawYaml,
      });
    }),
  );
  renderApp(null);
  await enterEditMode();

  const [allowInsecure] = await screen.findAllByRole("checkbox", {
    name: /allow insecure/i,
  });
  await userEvent.click(allowInsecure);

  expect(allowInsecure).toBeChecked();
  expect(screen.getByRole("button", {
    name: "Save configuration",
  })).toBeEnabled();
  expect(screen.queryByText("Updating configuration")).toBeNull();

  await userEvent.click(screen.getByRole("button", {
    name: "Save configuration",
  }));
  await waitFor(() => expect(savedDocument).toMatchObject({
    expectedPersistedRevision: "saved-browser-draft",
  }));
  expect((savedDocument as { rawYaml: string }).rawYaml)
    .toContain("allowInsecure: true");
});


test("submits the exact saved revision after browser-local editing", async () => {
  let savedDocument: unknown;
  let reviewRequest: unknown;
  let preflightRequest: unknown;
  let submitRequest: unknown;
  const rawYaml = `
sourceClusters:
  source:
    endpoint: https://source.example.com:9200
    version: ES 7.10
    allowInsecure: false
targetClusters:
  target:
    endpoint: https://target.example.com:9200
snapshotMigrationConfigs: []
`;
  server.use(
    http.get("*/api/v1/config/document", () => HttpResponse.json({
      modelVersion: "1",
      persistedRevision: "saved-browser-submit",
      rawYaml,
    })),
    http.put("*/api/v1/config/document", async ({ request }) => {
      savedDocument = await request.json();
      const body = savedDocument as { rawYaml: string };
      return HttpResponse.json({
        modelVersion: "1",
        persistedRevision: "saved-browser-submit-2",
        rawYaml: body.rawYaml,
      });
    }),
    http.post("*/api/v1/config/review", async ({ request }) => {
      reviewRequest = await request.json();
      return HttpResponse.json({
        persistedRevision: "saved-browser-submit-2",
        valid: true,
        validationMessages: [],
        changes: [],
      });
    }),
    http.post("*/api/v1/config/preflight", async ({ request }) => {
      preflightRequest = await request.json();
      return HttpResponse.json({
        checkedResources: 0,
        allowed: true,
        issues: [],
      });
    }),
    http.post("*/api/v1/config/submit", async ({ request }) => {
      submitRequest = await request.json();
      return HttpResponse.json({
        id: "operation-browser-submit",
        kind: "submit",
        label: "Submit workflow configuration",
        status: "queued",
        targetIds: [],
        createdAt: "2026-09-12T12:00:00Z",
        updatedAt: "2026-09-12T12:00:00Z",
        message: "Queued",
        detail: null,
        result: {},
      }, { status: 202 });
    }),
  );
  renderApp(null);
  await enterEditMode();

  const [allowInsecure] = await screen.findAllByRole("checkbox", {
    name: /allow insecure/i,
  });
  await userEvent.click(allowInsecure);
  await userEvent.click(screen.getByRole("button", {
    name: "Save and submit",
  }));

  const dialog = await screen.findByRole("dialog", {
    name: "Submit configuration?",
  });
  const expectedRevision = {
    expectedPersistedRevision: "saved-browser-submit-2",
  };
  await waitFor(() => {
    expect(savedDocument).toMatchObject({
      expectedPersistedRevision: "saved-browser-submit",
    });
    expect(reviewRequest).toEqual(expectedRevision);
    expect(preflightRequest).toEqual(expectedRevision);
  });
  expect((savedDocument as { rawYaml: string }).rawYaml)
    .toContain("allowInsecure: true");

  await userEvent.click(within(dialog).getByRole("button", {
    name: "Confirm submit",
  }));
  await waitFor(() => expect(submitRequest).toEqual(expectedRevision));
});


test("does not rerun environment checks for unrelated browser edits", async () => {
  let diagnosticRequests = 0;
  server.use(
    http.get("*/api/v1/config/document", () => HttpResponse.json({
      modelVersion: "1",
      persistedRevision: "saved-browser-draft",
      rawYaml: `
sourceClusters:
  source:
    endpoint: https://source.example.com:9200
    version: ES 7.10
    allowInsecure: false
    authConfig:
      basic:
        secretName: source-creds
targetClusters: {}
snapshotMigrationConfigs: []
`,
    })),
    http.post("*/api/v1/config/diagnostics", async ({ request }) => {
      diagnosticRequests += 1;
      const body = await request.json() as { draftNonce: string };
      return HttpResponse.json({
        draftNonce: body.draftNonce,
        status: "valid",
        diagnostics: [],
      });
    }),
  );
  renderApp(null);
  await enterEditMode();

  const secretsCheck = await screen.findByRole("tab", {
    name: /Kubernetes Secrets/i,
  });
  await waitFor(() => expect(secretsCheck).toHaveTextContent(
    "Kubernetes Secrets · Valid",
  ));
  const secretRow = screen.getByRole("row", {
    name: /Secret Name.*Valid/i,
  });
  const inlineValidity = within(secretRow).getByRole("button", {
    name: "Valid",
  });
  expect(inlineValidity.closest(".property-value-validity"))
    .toBeInTheDocument();
  expect(inlineValidity.closest(".property-action-cell")).toBeNull();
  expect(document.querySelector(".config-scope-validity")).toBeNull();
  await userEvent.click(secretsCheck);
  expect(await screen.findByText(
    "Valid: the configured reference is available.",
  )).toBeInTheDocument();
  expect(diagnosticRequests).toBe(1);
  const [allowInsecure] = await screen.findAllByRole("checkbox", {
    name: /allow insecure/i,
  });
  await userEvent.click(allowInsecure);

  await new Promise((resolve) => globalThis.setTimeout(resolve, 450));
  expect(diagnosticRequests).toBe(1);
  expect(allowInsecure).toBeChecked();
});


test("preserves later browser edits while a save is in progress", async () => {
  let releaseSave: (() => void) | undefined;
  const savePending = new Promise<void>((resolve) => {
    releaseSave = resolve;
  });
  let savedRawYaml = "";
  server.use(
    http.get("*/api/v1/config/document", () => HttpResponse.json({
      modelVersion: "1",
      persistedRevision: "saved-browser-draft",
      rawYaml: `
sourceClusters:
  source:
    endpoint: https://source.example.com:9200
    version: ES 7.10
    allowInsecure: false
targetClusters: {}
snapshotMigrationConfigs: []
`,
    })),
    http.put("*/api/v1/config/document", async ({ request }) => {
      const body = await request.json() as { rawYaml: string };
      savedRawYaml = body.rawYaml;
      await savePending;
      return HttpResponse.json({
        modelVersion: "1",
        persistedRevision: "saved-browser-draft-2",
        rawYaml: body.rawYaml,
      });
    }),
  );
  const { client } = renderApp(null);
  await enterEditMode();
  const [allowInsecure] = await screen.findAllByRole("checkbox", {
    name: /allow insecure/i,
  });
  await userEvent.click(allowInsecure);
  await userEvent.click(screen.getByRole("button", {
    name: "Save configuration",
  }));
  await waitFor(() => expect(savedRawYaml).toContain("allowInsecure: true"));

  const [currentAllowInsecure] = await screen.findAllByRole("checkbox", {
    name: /allow insecure/i,
  });
  expect(currentAllowInsecure).toBeEnabled();
  await userEvent.click(currentAllowInsecure);
  expect(currentAllowInsecure).not.toBeChecked();
  await waitFor(() => expect(
    client.getQueryData<BrowserConfigDraft>(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
    )?.rawDocument,
  ).toContain("allowInsecure: false"));
  releaseSave?.();

  await waitFor(() => expect(client.getQueryData<BrowserConfigDraft>(
    BROWSER_CONFIG_DRAFT_QUERY_KEY,
  )).toMatchObject({
    dirty: true,
    persistedRevision: "saved-browser-draft-2",
  }));
  await waitFor(() => expect(screen.getByRole("button", {
    name: "Save configuration",
  })).toBeEnabled());
  expect(currentAllowInsecure).not.toBeChecked();
});


test("preserves browser-local edits while the runtime graph refreshes", async () => {
  let response = manageSnapshot;
  let stateRequests = 0;
  server.use(
    http.get("*/api/v1/manage/state", () => {
      stateRequests += 1;
      return HttpResponse.json(response);
    }),
    http.get("*/api/v1/config/document", () => HttpResponse.json({
      modelVersion: "1",
      persistedRevision: "saved-browser-draft",
      rawYaml: `
sourceClusters:
  source:
    endpoint: https://source.example.com:9200
    version: ES 7.10
    allowInsecure: false
targetClusters:
  target:
    endpoint: https://target.example.com:9200
snapshotMigrationConfigs: []
`,
    })),
  );
  const { client } = renderApp(null);
  await enterEditMode();

  const [allowInsecure] = await screen.findAllByRole("checkbox", {
    name: /allow insecure/i,
  });
  await userEvent.click(allowInsecure);
  expect(allowInsecure).toBeChecked();
  const requestsBeforeRefresh = stateRequests;

  response = {
    ...manageSnapshot,
    revision: "runtime-after-browser-edit",
    nodes: {
      ...manageSnapshot.nodes,
      "resource:captureproxies:capture": {
        ...manageSnapshot.nodes["resource:captureproxies:capture"],
        revision: "capture-after-browser-edit",
        phase: "Running",
        status: "running",
      },
    },
  };
  await client.invalidateQueries({ queryKey: ["manage-state"] });
  await waitFor(() => expect(stateRequests).toBeGreaterThan(
    requestsBeforeRefresh,
  ));

  expect(allowInsecure).toBeChecked();
  expect(screen.getByRole("button", {
    name: "Save configuration",
  })).toBeEnabled();
});


test("preserves dirty work but blocks persistence when its saved base is stale", async () => {
  server.use(
    http.get("*/api/v1/config/document", () => HttpResponse.json({
      modelVersion: "1",
      persistedRevision: "saved-browser-draft",
      rawYaml: `
sourceClusters:
  source:
    endpoint: https://source.example.com:9200
    version: ES 7.10
    allowInsecure: false
targetClusters: {}
snapshotMigrationConfigs: []
`,
    })),
  );
  const { client } = renderApp(null);
  await enterEditMode();

  const [allowInsecure] = await screen.findAllByRole("checkbox", {
    name: /allow insecure/i,
  });
  await userEvent.click(allowInsecure);
  client.setQueryData<BrowserConfigDraft>(
    BROWSER_CONFIG_DRAFT_QUERY_KEY,
    (current) => (
      current
        ? markBrowserConfigDraftStale(current, "saved-elsewhere")
        : current
    ),
  );

  expect(await screen.findByRole("alert")).toHaveTextContent(
    "The saved configuration changed elsewhere",
  );
  expect(allowInsecure).toBeChecked();
  expect(screen.getByRole("button", {
    name: "Save configuration",
  })).toBeDisabled();
  expect(screen.getByRole("button", {
    name: "Revert unsaved changes",
  })).toBeEnabled();
});


test("shows ConfigMap keys and selects the map plus key together", async () => {
  let selection: unknown;
  server.use(
    http.post("*/api/v1/external-resources/select", async ({ request }) => {
      selection = await request.json();
      return HttpResponse.json({ accepted: true });
    }),
  );
  renderApp();
  await enterEditMode();
  const configTree = await screen.findByRole("table", {
    name: "Configuration fields",
  });
  const configMapRow = within(configTree).getByRole("row", {
    name: /Config Map/,
  });
  await userEvent.click(within(configMapRow).getByRole("button", {
    name: /Configure$/,
  }));

  const selector = await screen.findByRole("dialog", {
    name: "Transform ConfigMap",
  });
  const useMainJs = await within(selector).findByRole("button", {
    name: "Use transform-code and key main.js",
  });
  expect(within(selector).getByRole("button", {
    name: "Use transform-code and key settings.json",
  })).toBeInTheDocument();
  await userEvent.click(useMainJs);

  expect(typeof (selection as { rawYaml?: unknown })?.rawYaml).toBe("string");
  expect(selection).toMatchObject({
    nodeId: "edit:traffic.transform.configMap",
    name: "transform-code",
    kind: "ConfigMap",
    group: "",
    key: "main.js",
    acceptWarning: false,
    manual: false,
  });
  expect(screen.queryByRole("dialog", {
    name: "Transform ConfigMap",
  })).toBeNull();
});


test("selects an HTTP Basic Auth Secret in the shared resource dialog", async () => {
  const secretDraft = structuredClone(configDraft);
  const sourceClusters = secretDraft.editState.nodes.find(
    (node) => node.id === "edit:sourceClusters",
  );
  const source = sourceClusters?.children.find(
    (node) => node.id === "edit:sourceClusters.legacy",
  );
  const auth = source?.children.find(
    (node) => node.id === "edit:sourceClusters.legacy.authConfig",
  );
  const secret = auth?.children.find(
    (node) => node.id.endsWith("basic.secretName"),
  );
  if (!secret) throw new Error("Missing HTTP Basic Secret fixture");
  secret.externalRef = {
    kind: "kubernetesResource",
    purpose: "http-basic-auth",
    displayName: "HTTP Basic Auth Secret",
    selection: { target: "scalarName" },
    k8s: {
      resourceTypes: [{
        group: "",
        version: "v1",
        kind: "Secret",
        namespaced: true,
      }],
    },
  };
  let selection: unknown;
  server.use(
    http.post("*/api/v1/external-resources", () => HttpResponse.json({
      nodeId: secret.id,
      displayName: "HTTP Basic Auth Secret",
      rows: [{
        name: "source-creds",
        kind: "Secret",
        group: "",
        version: "v1",
        type: "kubernetes.io/basic-auth",
        keys: ["username", "password"],
        status: "matching",
        message: "",
        current: true,
      }, {
        name: "unrelated-creds",
        kind: "Secret",
        group: "",
        version: "v1",
        type: "Opaque",
        keys: ["token"],
        status: "warn",
        message: "Missing username and password keys.",
        current: false,
      }],
    })),
    http.post("*/api/v1/external-resources/details", async ({ request }) => {
      const { name = "source-creds" } = await request.json() as {
        name?: string;
      };
      return HttpResponse.json({
        nodeId: secret.id,
        displayName: "HTTP Basic Auth Secret",
        name,
        kind: "Secret",
        resourceType: name === "source-creds"
          ? "kubernetes.io/basic-auth"
          : "Opaque",
        keys: name === "source-creds"
          ? ["username", "password"]
          : ["token"],
        fieldValues: {},
        hiddenFields: name === "source-creds"
          ? ["username", "password"]
          : ["token"],
        missing: false,
        message: null,
      });
    }),
    http.post("*/api/v1/external-resources/select", async ({ request }) => {
      selection = await request.json();
      return HttpResponse.json({ accepted: true });
    }),
  );
  renderApp(secretDraft);
  await enterEditMode();
  const configTree = await screen.findByRole("table", {
    name: "Configuration fields",
  });
  const secretRow = within(configTree).getByRole("row", {
    name: /Credentials secret/,
  });
  await userEvent.click(within(secretRow).getByRole("button", {
    name: /Configure$/,
  }));

  const selector = await screen.findByRole("dialog", {
    name: "HTTP Basic Auth Secret",
  });
  expect(await within(selector).findByText("source-creds"))
    .toBeInTheDocument();
  expect(within(selector).queryByText("unrelated-creds")).toBeNull();

  await userEvent.click(within(selector).getByRole("button", {
    name: "View all 2",
  }));
  const allResources = await screen.findByRole("dialog", {
    name: "All HTTP Basic Auth Secret resources",
  });
  expect(within(allResources).getByText("unrelated-creds"))
    .toBeInTheDocument();
  await userEvent.click(within(allResources).getByRole("button", {
    name: "Details for unrelated-creds",
  }));
  expect(await within(allResources).findByText(
    "Missing username and password keys.",
  )).toBeInTheDocument();
  await userEvent.click(within(allResources).getByRole("button", {
    name: "Close all Kubernetes resources",
  }));
  expect(within(allResources).getByText("source-creds")).toBeInTheDocument();
  await userEvent.click(within(allResources).getByRole("button", {
    name: "Details for source-creds",
  }));
  await userEvent.click(await within(allResources).findByRole("button", {
    name: "Use resource",
  }));

  expect(typeof (selection as { rawYaml?: unknown })?.rawYaml).toBe("string");
  expect(selection).toMatchObject({
    nodeId: secret.id,
    name: "source-creds",
    kind: "Secret",
    group: "",
    key: null,
    acceptWarning: false,
    manual: false,
  });
  expect(screen.queryByRole("dialog", {
    name: "HTTP Basic Auth Secret",
  })).toBeNull();
});


test("allows an explicit ConfigMap and key when inventory is unavailable", async () => {
  let selection: unknown;
  server.use(
    http.post("*/api/v1/external-resources/select", async ({ request }) => {
      selection = await request.json();
      return HttpResponse.json({ accepted: true });
    }),
  );
  renderApp();
  await enterEditMode();
  const configTree = await screen.findByRole("table", {
    name: "Configuration fields",
  });
  const configMapRow = within(configTree).getByRole("row", {
    name: /Config Map/,
  });
  await userEvent.click(
    within(configMapRow).getByRole("button", { name: /Configure$/ }),
  );
  const selector = await screen.findByRole("dialog", {
    name: "Transform ConfigMap",
  });
  await userEvent.click(within(selector).getByRole("button", {
    name: "Enter reference manually",
  }));
  await userEvent.type(
    within(selector).getByRole("textbox", { name: "Resource name" }),
    "private-transform",
  );
  await userEvent.type(
    within(selector).getByRole("textbox", { name: "ConfigMap key" }),
    "transform.js",
  );
  await userEvent.click(
    within(selector).getByRole("button", {
      name: "Use unverified reference",
    }),
  );

  expect(typeof (selection as { rawYaml?: unknown })?.rawYaml).toBe("string");
  expect(selection).toMatchObject({
    nodeId: "edit:traffic.transform.configMap",
    name: "private-transform",
    kind: "ConfigMap",
    group: "",
    key: "transform.js",
    acceptWarning: true,
    manual: true,
  });
  expect(screen.queryByRole("dialog", {
    name: "Transform ConfigMap",
  })).toBeNull();
});


test("dismisses Kubernetes resource selection without persistent inline controls", async () => {
  renderApp();
  await enterEditMode();
  const configTree = await screen.findByRole("table", {
    name: "Configuration fields",
  });
  const configMapRow = within(configTree).getByRole("row", {
    name: /Config Map/,
  });
  const configure = within(configMapRow).getByRole("button", {
    name: /Configure$/,
  });
  await userEvent.click(configure);

  const selector = await screen.findByRole("dialog", {
    name: "Transform ConfigMap",
  });
  expect(within(selector).getByRole("button", {
    name: "Close Kubernetes resource selector",
  })).toHaveFocus();
  await userEvent.keyboard("{Escape}");

  expect(screen.queryByRole("dialog", {
    name: "Transform ConfigMap",
  })).toBeNull();
  expect(screen.queryByRole("button", {
    name: "Enter reference manually",
  })).toBeNull();
  await waitFor(() => expect(configure).toHaveFocus());
});


test("promotes add commands to collection actions and keeps exact deletion", async () => {
  const { client } = renderApp();
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
  expect(screen.getByText(
    "This name is an alias used by references and status views.",
  )).toBeInTheDocument();
  const createSource = screen.getByRole("button", {
    name: "Create source cluster",
  });
  const cancelSource = screen.getByRole("button", {
    name: "Cancel creating source cluster",
  });
  expect(createSource).toHaveTextContent("");
  expect(cancelSource).toHaveTextContent("");
  await userEvent.type(
    screen.getByRole("textbox", { name: "source cluster name" }),
    "modern",
  );
  await userEvent.click(createSource);
  const resourceTree = screen.getByRole("tree", {
    name: "Workflow resources",
  });
  await userEvent.click(await within(resourceTree).findByRole("treeitem", {
    name: /^legacy/,
  }));

  await userEvent.click(screen.getByRole("button", { name: "Remove legacy" }));
  expect(await screen.findByRole("dialog", {
    name: "Remove legacy from configuration?",
  })).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", {
    name: "Confirm configuration removal",
  }));
  await waitFor(() => {
    const sourceClusters = (
      client.getQueryData<BrowserConfigDraft>(
        BROWSER_CONFIG_DRAFT_QUERY_KEY,
      )?.config as {
        sourceClusters?: Record<string, unknown>;
      }
    ).sourceClusters;
    expect(sourceClusters).toHaveProperty("modern");
    expect(sourceClusters).not.toHaveProperty("legacy");
  });
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
  const draft = structuredClone(configDraft);
  draft.rawYaml = `sourceClusters:
  legacy:
    endpoint: https://legacy.example.com:9200
    version: ES 7.10
targetClusters:
  target:
    endpoint: https://target.example.com:9200
traffic:
  kafkaClusters:
    default:
      autoCreate: {}
      topics:
        capture: {}
  proxies:
    capture:
      source: legacy
      proxyConfig: {}
      kafka: default
      kafkaTopic: capture
  replayers:
    replay:
      fromCapturedTraffic: capture
      toTarget: target
snapshotMigrationConfigs: []
`;
  draft.editState = projectConfigYaml(draft.rawYaml).editState;

  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
  );
  renderApp(draft);

  await enterEditMode();
  const [removeLegacy] = await screen.findAllByRole("button", {
    name: "Remove legacy",
  });
  await userEvent.click(removeLegacy);

  const dialog = await screen.findByRole("dialog", {
    name: "Remove legacy from configuration?",
  });
  expect(within(dialog).getByText("traffic.proxies.capture"))
    .toBeInTheDocument();
  expect(within(dialog).getByText("traffic.replayers.replay"))
    .toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole("button", {
    name: "Confirm configuration removal",
  }));

  const updatedTree = screen.getByRole("tree", { name: "Workflow resources" });
  expect(await within(updatedTree).findByRole("treeitem", {
    name: /^source, Marked for removal$/,
  })).toHaveAttribute("aria-selected", "true");
  expect(screen.getByRole("heading", { name: "source" })).toBeInTheDocument();
  expect(screen.getByText(
    "This source is marked for removal from the configuration.",
  )).toBeInTheDocument();
  expect(screen.queryByText("Workflow configuration")).toBeNull();
});


test("reviews a downstream resource and returns to cascading deletion", async () => {
  const rawYaml = `sourceClusters:
  legacy:
    endpoint: https://legacy.example.com:9200
    version: ES 7.10
targetClusters:
  target:
    endpoint: https://target.example.com:9200
snapshotMigrationConfigs: []
traffic:
  kafkaClusters:
    default:
      autoCreate: {}
      topics:
        capture: {}
  proxies:
    capture:
      source: legacy
      kafka: default
      kafkaTopic: capture
      proxyConfig:
        listenPort: 9200
  replayers:
    replay:
      fromCapturedTraffic: capture
      toTarget: target
`;
  const projection = projectConfigYaml(rawYaml);
  const draft: ConfigDraft = {
    baseRevision: "cascade-base",
    draftRevision: "cascade-draft",
    dirty: false,
    editState: projection.editState,
    rawYaml,
    notices: [],
  };
  const { client } = renderApp(draft);

  await enterEditMode();
  const tree = screen.getByRole("tree", { name: "Workflow resources" });
  await userEvent.click(within(tree).getByRole("treeitem", {
    name: "capture",
  }));
  const captureEditor = await screen.findByRole("region", {
    name: "Edit capture configuration",
  });
  await userEvent.click(within(captureEditor).getByRole("button", {
    name: "Remove capture",
  }));

  let dialog = await screen.findByRole("dialog", {
    name: /Remove capture from configuration/,
  });
  expect(within(dialog).getByText(
    /Referencing entries can be kept with their affected fields cleared/,
  )).toBeInTheDocument();
  expect(within(dialog).getByRole("button", {
    name: "Confirm configuration removal",
  })).toHaveTextContent("Remove 2 configuration entries");

  await userEvent.click(within(dialog).getByRole("button", {
    name: "View traffic.replayers.replay",
  }));
  expect(screen.queryByRole("dialog", {
    name: /Remove capture from configuration/,
  })).toBeNull();
  expect(await screen.findByRole("heading", {
    name: "Edit replay",
  })).toBeInTheDocument();
  expect(screen.getByRole("row", {
    name: /From Captured Traffic/,
  })).toBeInTheDocument();

  await userEvent.click(screen.getByRole("button", {
    name: "Back to capture",
  }));
  dialog = await screen.findByRole("dialog", {
    name: /Remove capture from configuration/,
  });
  await userEvent.click(within(dialog).getByRole("button", {
    name: "Confirm configuration removal",
  }));

  await waitFor(() => {
    const config = client.getQueryData<BrowserConfigDraft>(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
    )?.config as {
      traffic?: {
        proxies?: Record<string, unknown>;
        replayers?: Record<string, unknown>;
      };
    };
    expect(config.traffic?.proxies?.capture).toBeUndefined();
    expect(config.traffic?.replayers?.replay).toBeUndefined();
  });
});


test("keeps downstream resources and clears their references during deletion", async () => {
  const rawYaml = `sourceClusters:
  source:
    endpoint: https://source.example.com:9200
    version: ES 7.10
targetClusters:
  target:
    endpoint: https://target.example.com:9200
snapshotMigrationConfigs: []
traffic:
  kafkaClusters:
    default:
      autoCreate: {}
      topics:
        capture: {}
  proxies:
    capture:
      source: source
      kafka: default
      kafkaTopic: capture
      proxyConfig:
        listenPort: 9200
  replayers:
    replay:
      fromCapturedTraffic: capture
      toTarget: target
`;
  const projection = projectConfigYaml(rawYaml);
  const draft: ConfigDraft = {
    baseRevision: "keep-dependents-base",
    draftRevision: "keep-dependents-draft",
    dirty: false,
    editState: projection.editState,
    rawYaml,
    notices: [],
  };
  const { client } = renderApp(draft);

  await enterEditMode();
  const tree = screen.getByRole("tree", { name: "Workflow resources" });
  await userEvent.click(within(tree).getByRole("treeitem", {
    name: "capture",
  }));
  const captureEditor = await screen.findByRole("region", {
    name: "Edit capture configuration",
  });
  await userEvent.click(within(captureEditor).getByRole("button", {
    name: "Remove capture",
  }));

  const dialog = await screen.findByRole("dialog", {
    name: "Remove capture from configuration?",
  });
  expect(within(dialog).getByText(
    /Can be kept; clear traffic\.replayers\.replay\.fromCapturedTraffic/,
  )).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole("button", {
    name: "Remove and clear references",
  }));

  await waitFor(() => {
    const config = client.getQueryData<BrowserConfigDraft>(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
    )?.config as {
      traffic?: {
        proxies?: Record<string, unknown>;
        replayers?: Record<string, {
          fromCapturedTraffic?: string;
          toTarget?: string;
        }>;
      };
    };
    expect(config.traffic?.proxies?.capture).toBeUndefined();
    expect(config.traffic?.replayers?.replay).toEqual({
      toTarget: "target",
    });
  });
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
  const pendingDraft = structuredClone(configDraft);
  const pendingNavigation = setNavigation(pendingDraft, snapshot);
  pendingNavigation.nodes[source.id] = {
    ...pendingNavigation.nodes[source.id],
    revision: "source-removal-pending",
    status: "removed",
  };
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
  );
  renderApp(pendingDraft);

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


test("adds and focuses a named resource without a server round trip", async () => {
  renderApp();
  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const sourceGroup = screen.getAllByRole("treeitem", {
    name: /^Sources$/,
  }).find((item) => item.getAttribute("aria-level") === "1");
  expect(sourceGroup).toBeDefined();
  if (!sourceGroup) throw new Error("Source group was not rendered");
  const previousSelection = screen.getByRole("treeitem", {
    name: /^capture$/,
  });
  await userEvent.click(await within(sourceGroup).findByRole("button", {
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
    name: /^immediate, 5 unsaved changes$/,
  })).toHaveAttribute("aria-selected", "true");
  expect(document.querySelector(".interaction-shield")).toBeNull();
  expect(screen.queryByRole("textbox", {
    name: "source cluster name",
  })).toBeNull();
  expect(await screen.findByRole("heading", {
    name: "Edit immediate",
  })).toBeInTheDocument();
  expect(screen.getByRole("textbox", { name: "Endpoint" }))
    .toBeInTheDocument();
});


test("rejects a duplicate resource name without changing selection", async () => {
  const draft = addLegacySourceNavigation(structuredClone(configDraft));
  renderApp(draft);
  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  await userEvent.click(within(tree).getByRole("treeitem", {
    name: /^legacy, Addition pending submission$/,
  }));
  expect(await screen.findByRole("heading", { name: "Edit legacy" }))
    .toBeInTheDocument();

  const sourceGroup = screen.getAllByRole("treeitem", {
    name: /^Sources$/,
  }).find((item) => item.getAttribute("aria-level") === "1");
  if (!sourceGroup) throw new Error("Source group was not rendered");
  await userEvent.click(within(sourceGroup).getByRole("button", {
    name: "Add source cluster",
  }));
  await userEvent.type(
    within(tree).getByRole("textbox", { name: "source cluster name" }),
    "legacy{Enter}",
  );

  expect(within(tree).getAllByRole("treeitem", {
    name: /^legacy, Addition pending submission$/,
  })).toHaveLength(1);
  expect(within(tree).getByRole("treeitem", {
    name: /^legacy, Addition pending submission$/,
  })).toHaveAttribute("aria-selected", "true");
  expect(screen.getByRole("heading", { name: "Edit legacy" }))
    .toBeInTheDocument();
});


test("cancels inline resource naming and restores tree selection and focus", async () => {
  const draft = addLegacySourceNavigation(structuredClone(configDraft));
  renderApp(draft);
  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const capture = within(tree).getByRole("treeitem", {
    name: /^capture$/,
  });
  capture.focus();
  const sourceGroup = screen.getAllByRole("treeitem", {
    name: /^Sources$/,
  }).find((item) => item.getAttribute("aria-level") === "1");
  expect(sourceGroup).toBeDefined();
  if (!sourceGroup) throw new Error("Source group was not rendered");

  await userEvent.click(within(sourceGroup).getByRole("button", {
    name: "Add source cluster",
  }));
  const nameInput = within(tree).getByRole("textbox", {
    name: "source cluster name",
  });
  expect(nameInput).toHaveFocus();
  expect(within(tree).queryByText(
    "This name is an alias used by references and status views.",
  )).toBeNull();
  const create = within(tree).getByRole("button", {
    name: "Create source cluster",
  });
  const cancel = within(tree).getByRole("button", {
    name: "Cancel adding source cluster",
  });
  expect(create).toHaveTextContent("");
  expect(cancel).toHaveTextContent("");
  expect(create.parentElement).toHaveClass("tree-inline-name-actions");
  expect(create.parentElement).toBe(cancel.parentElement);
  await userEvent.type(nameInput, "Invalid Name");
  expect(within(tree).getByRole("alert")).toHaveTextContent(
    "Use a Kubernetes-compatible name.",
  );
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
  renderApp();
  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const sourceGroup = screen.getAllByRole("treeitem", {
    name: /^Sources$/,
  }).find((item) => item.getAttribute("aria-level") === "1");
  expect(sourceGroup).toBeDefined();
  if (!sourceGroup) throw new Error("Source group was not rendered");

  await userEvent.click(within(sourceGroup).getByRole("button", {
    name: "Add source cluster",
  }));
  await userEvent.type(within(tree).getByRole("textbox", {
    name: "source cluster name",
  }), "abandoned");
  const optional = screen.getByRole("checkbox", {
    name: "Show optional fields",
  });
  await userEvent.click(optional);

  expect(optional).not.toBeChecked();
  expect(within(tree).queryByRole("textbox", {
    name: "source cluster name",
  })).toBeNull();
});


test("views and creates descriptor-driven ConfigMaps without raw YAML", async () => {
  let saveRequest: unknown;
  server.use(
    http.post("*/api/v1/external-resources/save", async ({ request }) => {
      saveRequest = await request.json();
      return HttpResponse.json({
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
  const configMapRow = within(configTree).getByRole("row", {
    name: /Config Map/,
  });
  await userEvent.click(within(configMapRow).getByRole("button", {
    name: /Configure$/,
  }));
  const selector = await screen.findByRole("dialog", {
    name: "Transform ConfigMap",
  });

  await userEvent.click(
    await within(selector).findByRole("button", {
      name: "Details for transform-code",
    }),
  );
  expect(await within(selector).findByText("export default () => true;"))
    .toBeInTheDocument();
  expect(within(selector).queryByText(/raw YAML/i)).toBeNull();
  await userEvent.click(within(selector).getByRole("button", {
    name: "Close Kubernetes resource selector",
  }));

  await userEvent.click(
    within(selector).getByRole("button", {
      name: "Create Transform ConfigMap",
    }),
  );
  await userEvent.type(
    within(selector).getByRole("textbox", { name: "ConfigMap name" }),
    "next-transform",
  );
  const key = within(selector).getByRole("textbox", { name: "Key" });
  expect(key).toHaveValue("transform.js");
  await userEvent.type(
    within(selector).getByRole("textbox", { name: "JavaScript" }),
    "export default () => false;",
  );
  await userEvent.click(within(selector).getByRole("button", {
    name: "Create resource",
  }));

  expect(typeof (saveRequest as { rawYaml?: unknown })?.rawYaml).toBe("string");
  expect(saveRequest).toEqual({
    rawYaml: (saveRequest as { rawYaml: string }).rawYaml,
    nodeId: "edit:traffic.transform.configMap",
    values: {
      name: "next-transform",
      key: "transform.js",
      contents: "export default () => false;",
    },
    confirmations: {},
    existingName: null,
  });
  expect(screen.queryByRole("dialog", {
    name: "Transform ConfigMap",
  })).toBeNull();
});


test("exit offers continue or discard and reopening reloads saved values", async () => {
  const dirtyDraft = structuredClone(configDraft);
  dirtyDraft.dirty = true;
  dirtyDraft.draftRevision = "dirty-close";
  const { client } = renderApp(dirtyDraft);
  await enterEditMode();

  await userEvent.click(
    screen.getByRole("button", { name: "Exit editing" }),
  );
  const firstPrompt = screen.getByRole("dialog", { name: "Leave editing?" });
  await userEvent.click(within(firstPrompt).getByRole("button", {
    name: "Continue editing",
  }));
  expect(screen.getByText("Editing configuration")).toBeInTheDocument();

  await userEvent.click(
    screen.getByRole("button", { name: "Exit editing" }),
  );
  await userEvent.click(screen.getByRole("button", {
    name: "Discard and exit",
  }));

  expect(await screen.findByRole("button", { name: "Edit configuration" }))
    .toBeInTheDocument();

  await enterEditMode();
  expect(client.getQueryData(BROWSER_CONFIG_DRAFT_QUERY_KEY)).toMatchObject({
    baseRevision: dirtyDraft.baseRevision,
    dirty: false,
  });
});


test("save and exit persists before closing the edit session", async () => {
  let saveRequest: unknown;
  const dirtyDraft = structuredClone(configDraft);
  dirtyDraft.dirty = true;
  dirtyDraft.draftRevision = "dirty-save-exit";
  server.use(
    http.put("*/api/v1/config/document", async ({ request }) => {
      saveRequest = await request.json();
      const body = saveRequest as { rawYaml: string };
      return HttpResponse.json({
        modelVersion: "1",
        persistedRevision: "saved-on-exit",
        rawYaml: body.rawYaml,
      });
    }),
  );
  renderApp(dirtyDraft);
  await enterEditMode();

  await userEvent.click(screen.getByRole("button", { name: "Exit editing" }));
  await userEvent.click(screen.getByRole("button", { name: "Save and exit" }));

  expect(saveRequest).toMatchObject({
    expectedPersistedRevision: dirtyDraft.baseRevision,
  });
  expect(await screen.findByRole("button", { name: "Edit configuration" }))
    .toBeInTheDocument();
});


test("reviews and tracks submission while leaving edit mode", async () => {
  let saveRequest: unknown;
  let submitRequest: unknown;
  let submitAccepted = false;
  const persistedRevision = "saved-to-submit";
  const validDraft = structuredClone(configDraft);
  validDraft.dirty = true;
  validDraft.draftRevision = "dirty-to-submit";
  validDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.put("*/api/v1/config/document", async ({ request }) => {
      saveRequest = await request.json();
      const body = saveRequest as { rawYaml: string };
      return HttpResponse.json({
        modelVersion: "1",
        persistedRevision,
        rawYaml: body.rawYaml,
      });
    }),
    http.get("*/api/v1/config/document", () => HttpResponse.json(
      configurationDocument(persistedRevision),
    )),
    http.post("*/api/v1/config/review", () => HttpResponse.json({
      persistedRevision,
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
  renderApp(validDraft);
  await enterEditMode();

  await userEvent.click(screen.getByRole("button", {
    name: "Save and submit",
  }));
  const dialog = await screen.findByRole("dialog", {
    name: "Submit configuration?",
  });
  expect(dialog).toHaveClass("submission-dialog");
  expect(dialog.querySelector(":scope > .submission-dialog-body"))
    .toBeInTheDocument();
  expect(dialog.querySelector(":scope > footer"))
    .toContainElement(within(dialog).getByRole("button", {
      name: "Confirm submit",
    }));
  expect(within(dialog).getByText("capture")).toBeInTheDocument();
  expect(within(dialog).getByText("Service type")).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole("button", {
    name: "Confirm submit",
  }));

  expect(saveRequest).toMatchObject({
    expectedPersistedRevision: validDraft.baseRevision,
  });
  await waitFor(() => expect(submitRequest).toEqual({
    expectedPersistedRevision: persistedRevision,
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
  const persistedRevision = "saved-pending-revision";
  const savedDraft = structuredClone(configDraft);
  savedDraft.dirty = false;
  savedDraft.draftRevision = "saved-pending-revision";
  savedDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.get("*/api/v1/config/document", () => HttpResponse.json(
      configurationDocument(persistedRevision),
    )),
    http.post("*/api/v1/config/review", () => HttpResponse.json({
      persistedRevision,
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
  renderApp(savedDraft);

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
    expectedPersistedRevision: persistedRevision,
  }));
  expect(screen.queryByText("Editing configuration")).toBeNull();
  expect(await screen.findByText(
    "Workflow accepted; waiting for refreshed cluster state",
  )).toBeInTheDocument();
});


test("keeps submit enabled for admission warnings that may converge later", async () => {
  let submitRequest: unknown;
  const persistedRevision = "warning-preflight";
  const savedDraft = structuredClone(configDraft);
  savedDraft.dirty = false;
  savedDraft.draftRevision = "warning-preflight";
  savedDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.get("*/api/v1/config/document", () => HttpResponse.json(
      configurationDocument(persistedRevision),
    )),
    http.post("*/api/v1/config/review", () => HttpResponse.json({
      persistedRevision,
      valid: true,
      validationMessages: [],
      changes: [],
    })),
    http.post("*/api/v1/config/preflight", () => HttpResponse.json({
      checkedResources: 1,
      allowed: true,
      issues: [{
        kind: "CapturedTraffic",
        name: "capture-topic",
        plural: "capturedtraffics",
        classification: "warning",
        message: "The resource is still being deleted.",
        source: "kubernetes",
        blocking: false,
        resourceId: "resource:capturedtraffics:capture-topic",
      }],
    })),
    http.post("*/api/v1/config/submit", async ({ request }) => {
      submitRequest = await request.json();
      return HttpResponse.json({
        id: "submit-after-warning",
        kind: "submit",
        label: "Submit workflow configuration",
        status: "queued",
        targetIds: [],
        createdAt: "2026-08-16T13:00:00Z",
        updatedAt: "2026-08-16T13:00:00Z",
        message: "Queued",
        detail: null,
        result: {},
      }, { status: 202 });
    }),
  );
  renderApp(savedDraft);

  await userEvent.click(await screen.findByRole("button", {
    name: "Review and submit",
  }));
  const dialog = await screen.findByRole("dialog", {
    name: "Submit configuration?",
  });
  expect(within(dialog).getByText(
    "The resource is still being deleted.",
  )).toBeInTheDocument();
  const submit = within(dialog).getByRole("button", {
    name: "Confirm submit",
  });
  expect(submit).toBeEnabled();
  await userEvent.click(submit);

  await waitFor(() => expect(submitRequest).toEqual({
    expectedPersistedRevision: persistedRevision,
  }));
});


test("shows resources that submission will reconcile for checksum-only changes", async () => {
  const persistedRevision = "checksum-impact";
  const savedDraft = structuredClone(configDraft);
  savedDraft.dirty = false;
  savedDraft.draftRevision = "checksum-impact";
  savedDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.get("*/api/v1/config/document", () => HttpResponse.json(
      configurationDocument(persistedRevision),
    )),
    http.post("*/api/v1/config/review", () => HttpResponse.json({
      persistedRevision,
      valid: true,
      validationMessages: [],
      changes: [],
    })),
    http.post("*/api/v1/config/preflight", () => HttpResponse.json({
      checkedResources: 1,
      allowed: true,
      issues: [],
      deploymentActions: [{
        kind: "CaptureProxy",
        name: "p2",
        plural: "captureproxies",
        action: "reconcile",
        reason: "checksum-only",
        message: (
          "The workflow will reconcile this resource because its generated "
          + "checksum changed, although no projected fields changed."
        ),
        currentConfigChecksum: "old",
        desiredConfigChecksum: "new",
        resourceId: "resource:captureproxies:p2",
      }],
    })),
  );
  renderApp(savedDraft);

  await userEvent.click(await screen.findByRole("button", {
    name: "Review and submit",
  }));
  const dialog = await screen.findByRole("dialog", {
    name: "Submit configuration?",
  });
  expect(within(dialog).getByText("Deployment impact")).toBeInTheDocument();
  expect(within(dialog).getByText("p2")).toBeInTheDocument();
  expect(within(dialog).getByText("Checksum-only reconcile")).toBeInTheDocument();
  expect(within(dialog).getByText(
    /although no projected fields changed/,
  )).toBeInTheDocument();
});


test("offers one reset and resubmit action for immutable preflight failures", async () => {
  let resetRequest: unknown;
  let submitCalled = false;
  const persistedRevision = "immutable-preflight";
  const savedDraft = structuredClone(configDraft);
  savedDraft.dirty = false;
  savedDraft.draftRevision = "immutable-preflight";
  savedDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.get("*/api/v1/config/document", () => HttpResponse.json(
      configurationDocument(persistedRevision),
    )),
    http.post("*/api/v1/config/review", () => HttpResponse.json({
      persistedRevision,
      valid: true,
      validationMessages: [],
      changes: [],
    })),
    http.post("*/api/v1/config/preflight", () => HttpResponse.json({
      checkedResources: 1,
      allowed: false,
      issues: [{
        kind: "CapturedTraffic",
        name: "capture-topic",
        plural: "capturedtraffics",
        classification: "recreate-required",
        message: "Impossible: sourceLabel cannot be changed.",
        source: "kubernetes",
        blocking: true,
        resourceId: "resource:capturedtraffics:capture-topic",
        resetTargetId: "reset:capturedtraffics:capture-topic",
      }],
    })),
    http.post("*/api/v1/resets/plan", () => HttpResponse.json({
      token: "preflight-reset-token",
      requestTargetId: "reset:capturedtraffics:capture-topic",
      targets: [{
        plural: "capturedtraffics",
        type: "capturedtraffic",
        name: "capture-topic",
        path: "capturedtraffic.capture-topic",
        phase: "Ready",
        dependsOn: [],
      }, {
        plural: "captureproxies",
        type: "captureproxy",
        name: "p2",
        path: "captureproxy.p2",
        phase: "Ready",
        dependsOn: ["capturedtraffic.capture-topic"],
      }],
      messages: [],
      warnings: [],
    })),
    http.post("*/api/v1/resets", async ({ request }) => {
      resetRequest = await request.json();
      return HttpResponse.json({
        id: "reset-resubmit-preflight",
        kind: "reset",
        label: "Reset and resubmit capturedtraffic.capture-topic",
        status: "queued",
        targetIds: ["resource:capturedtraffics:capture-topic"],
        createdAt: "2026-08-16T13:00:00Z",
        updatedAt: "2026-08-16T13:00:00Z",
        message: "Queued",
        detail: null,
        result: {},
      }, { status: 202 });
    }),
    http.post("*/api/v1/config/submit", () => {
      submitCalled = true;
      return new HttpResponse(null, { status: 500 });
    }),
  );
  renderApp(savedDraft);

  await userEvent.click(await screen.findByRole("button", {
    name: "Review and submit",
  }));
  const dialog = await screen.findByRole("dialog", {
    name: "Submit configuration?",
  });
  expect(within(dialog).getByText(
    "Impossible: sourceLabel cannot be changed.",
  )).toBeInTheDocument();
  expect(within(dialog).queryByText(
    "No field-level pending differences were reported.",
  )).toBeNull();
  const blockedSubmit = within(dialog).getByRole("button", {
    name: "Confirm submit",
  });
  expect(blockedSubmit).toBeDisabled();
  expect(blockedSubmit).toHaveAttribute(
    "title",
    "No workflow will be submitted while admission errors requiring "
      + "resource deletion remain. The affected resources and their "
      + "dependencies will stay blocked. Use Delete resources and resubmit.",
  );
  const resetAndResubmit = await within(dialog).findByRole("button", {
    name: "Delete resources and resubmit (2)",
  });
  expect(resetAndResubmit).toHaveAttribute(
    "title",
    "Delete 2 resources before submitting a new workflow: "
      + "capturedtraffic.capture-topic; captureproxy.p2.",
  );
  await userEvent.click(resetAndResubmit);

  await waitFor(() => expect(resetRequest).toEqual({
    planToken: "preflight-reset-token",
    resubmit: true,
    expectedPersistedRevision: persistedRevision,
  }));
  expect(submitCalled).toBe(false);
});


test("explains why submission is unavailable after validation state changes", async () => {
  let response = structuredClone(manageSnapshot);
  const currentState = structuredClone(manageSnapshot);
  currentState.revision = "snapshot-current";
  const capture = currentState.nodes["resource:captureproxies:capture"];
  capture.revision = "capture-current";
  capture.valueSummary = "Deployed";
  capture.comparisons = capture.comparisons.map((comparison) => ({
    ...comparison,
    pending: comparison.submitted,
    pendingChanged: false,
  }));
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(response)),
  );
  const { client } = renderApp();

  expect(await screen.findByText("1 configuration error"))
    .toBeInTheDocument();
  response = currentState;
  await client.invalidateQueries({ queryKey: ["manage-state"] });

  expect(await screen.findByText(
    "Configuration is current; no resources are missing or failed",
  )).toHaveClass("sr-only");
  const submit = screen.getByRole("button", {
    name: "Review and submit",
  });
  expect(submit).toBeDisabled();
  expect(submit).toHaveAttribute(
    "title",
    "Configuration is current; no resources are missing or failed",
  );
});


test("enables submit from snapshot-level configuration dirtiness", async () => {
  const currentState = structuredClone(manageSnapshot);
  currentState.configurationPending = true;
  const capture = currentState.nodes["resource:captureproxies:capture"];
  capture.comparisons = capture.comparisons.map((comparison) => ({
    ...comparison,
    pending: comparison.submitted,
    pendingChanged: false,
  }));
  const validDraft = structuredClone(configDraft);
  validDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.get(
      "*/api/v1/manage/state",
      () => HttpResponse.json(currentState),
    ),
  );

  renderApp(validDraft);

  const submit = await screen.findByRole("button", {
    name: "Review and submit",
  });
  await waitFor(() => expect(submit).toBeEnabled());
  expect(submit).toHaveAttribute(
    "title",
    "Review and submit pending configuration",
  );
});


test("offers resubmission when a configured resource is missing", async () => {
  const currentState = structuredClone(manageSnapshot);
  const capture = currentState.nodes["resource:captureproxies:capture"];
  capture.status = "pending";
  capture.phase = "Pending Config";
  capture.valueSummary = "Addition in progress";
  capture.configPresence = {
    deployed: false,
    submitted: true,
    pending: true,
  };
  capture.comparisons = [];
  const validDraft = structuredClone(configDraft);
  const persistedRevision = "missing-resource-resubmit";
  validDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(currentState)),
    http.get("*/api/v1/config/document", () => HttpResponse.json(
      configurationDocument(persistedRevision),
    )),
    http.post("*/api/v1/config/review", () => HttpResponse.json({
      persistedRevision,
      valid: true,
      validationMessages: [],
      changes: [],
    })),
  );
  renderApp(validDraft);

  const resubmit = await screen.findByRole("button", {
    name: "Review and resubmit",
  });
  await waitFor(() => expect(resubmit).toBeEnabled());
  expect(resubmit).toHaveAttribute(
    "title",
    "Review and resubmit the saved configuration. "
      + "1 configured resource is missing",
  );
  await userEvent.click(resubmit);
  const dialog = await screen.findByRole("dialog", {
    name: "Resubmit configuration?",
  });
  expect(within(dialog).queryByText(
    "No configuration differences were reported; resubmission will retry the saved configuration.",
  )).toBeNull();
  await waitFor(() => {
    expect(within(dialog).getByRole("button", {
      name: "Confirm resubmit",
    })).toBeEnabled();
  });
});


test("offers resubmission when a managed resource has failed", async () => {
  const currentState = structuredClone(manageSnapshot);
  const capture = currentState.nodes["resource:captureproxies:capture"];
  capture.status = "error";
  capture.phase = "Failed";
  capture.valueSummary = "Failed";
  capture.configPresence = {
    deployed: true,
    submitted: true,
    pending: true,
  };
  capture.comparisons = [];
  const validDraft = structuredClone(configDraft);
  validDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(currentState)),
  );
  renderApp(validDraft);

  const resubmit = await screen.findByRole("button", {
    name: "Review and resubmit",
  });
  await waitFor(() => expect(resubmit).toBeEnabled());
  expect(resubmit).toHaveAttribute(
    "title",
    "Review and resubmit the saved configuration. "
      + "1 managed resource has failed",
  );
});


test("offers submission for a pending resource addition without field diffs", async () => {
  const pendingState = structuredClone(manageSnapshot);
  const capture = pendingState.nodes["resource:captureproxies:capture"];
  capture.valueSummary = "Addition pending submission";
  capture.configPresence = {
    deployed: false,
    submitted: false,
    pending: true,
  };
  capture.comparisons = [];
  const validDraft = structuredClone(configDraft);
  validDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(pendingState)),
  );
  renderApp(validDraft);

  await waitFor(() => expect(screen.getByRole("button", {
    name: "Review and submit",
  })).toBeEnabled());
});


test("exposes a blocking validation reason through the submit tooltip", async () => {
  renderApp();

  const submit = await screen.findByRole("button", {
    name: "Review and submit",
  });
  expect(submit).toBeDisabled();
  expect(await screen.findByText("1 configuration error"))
    .toBeInTheDocument();
  expect(submit).toHaveAttribute(
    "title",
    "Resolve 1 configuration error before submitting",
  );
});


test("Escape invokes the active edit confirmation cancel action", async () => {
  const dirtyDraft = structuredClone(configDraft);
  dirtyDraft.dirty = true;
  dirtyDraft.draftRevision = "dirty-escape";
  renderApp(dirtyDraft);
  await enterEditMode();

  await userEvent.click(screen.getByRole("button", {
    name: "Exit editing",
  }));
  expect(screen.getByRole("dialog", { name: "Leave editing?" }))
    .toBeInTheDocument();

  await userEvent.keyboard("{Escape}");

  expect(screen.queryByRole("dialog", { name: "Leave editing?" })).toBeNull();
  expect(screen.getByText("Editing configuration")).toBeInTheDocument();
});


test("Escape closes only the topmost submit dialog while editing", async () => {
  const persistedRevision = "saved-submit-escape";
  const dirtyDraft = structuredClone(configDraft);
  dirtyDraft.dirty = true;
  dirtyDraft.draftRevision = "dirty-submit-escape";
  dirtyDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.put("*/api/v1/config/document", async ({ request }) => {
      const body = await request.json() as { rawYaml: string };
      return HttpResponse.json({
        modelVersion: "1",
        persistedRevision,
        rawYaml: body.rawYaml,
      });
    }),
    http.get("*/api/v1/config/document", () => HttpResponse.json(
      configurationDocument(persistedRevision),
    )),
    http.post("*/api/v1/config/review", () => HttpResponse.json({
      persistedRevision,
      valid: true,
      validationMessages: [],
      changes: [],
    })),
  );
  renderApp(dirtyDraft);
  await enterEditMode();

  await userEvent.click(screen.getByRole("button", {
    name: "Save and submit",
  }));
  expect(await screen.findByRole("dialog", {
    name: "Submit configuration?",
  })).toBeInTheDocument();

  await userEvent.keyboard("{Escape}");

  expect(screen.queryByRole("dialog", {
    name: "Submit configuration?",
  })).toBeNull();
  expect(screen.getByText("Editing configuration")).toBeInTheDocument();
});


test("Escape invokes the reset dialog Cancel action", async () => {
  renderApp();
  const tree = await screen.findByRole("tree", {
    name: "Workflow resources",
  });
  await userEvent.click(within(tree).getByRole("treeitem", {
    name: /^capture, Ready$/,
  }));
  await userEvent.click(screen.getByRole("button", {
    name: "Delete resource",
  }));
  expect(await screen.findByRole("dialog", {
    name: "Review resource deletion",
  })).toBeInTheDocument();

  await userEvent.keyboard("{Escape}");

  expect(screen.queryByRole("dialog", {
    name: "Review resource deletion",
  })).toBeNull();
  expect(screen.getByRole("heading", { name: "capture" }))
    .toBeInTheDocument();
});


test("shows structured admission preflight preparation failures", async () => {
  const persistedRevision = "preflight-failure";
  const validDraft = structuredClone(configDraft);
  validDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.get("*/api/v1/config/document", () => HttpResponse.json(
      configurationDocument(persistedRevision),
    )),
    http.post("*/api/v1/config/review", () => HttpResponse.json({
      persistedRevision,
      valid: true,
      validationMessages: [],
      changes: [],
    })),
    http.post("*/api/v1/config/preflight", () => HttpResponse.json({
      detail: {
        code: "admission_preflight_unavailable",
        message: (
          "Admission preflight could not prepare the workflow: "
          + "getaddrinfo ENOTFOUND localstack"
        ),
      },
    }, { status: 502 })),
  );
  renderApp(validDraft);

  const submit = await screen.findByRole("button", {
    name: "Review and submit",
  });
  await waitFor(() => expect(submit).toBeEnabled());
  await userEvent.click(submit);

  expect(await screen.findByText(
    "Admission preflight could not prepare the workflow: "
      + "getaddrinfo ENOTFOUND localstack",
  )).toBeInTheDocument();
});
