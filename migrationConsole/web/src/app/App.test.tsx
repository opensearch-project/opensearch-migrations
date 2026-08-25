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

import {
  getHealth,
  type ConfigDraft,
  type ManageNode,
  type ManageSnapshot,
} from "../api/client";
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


function addSourceDefinitionNavigation(
  draft: ConfigDraft,
  {
    groupLabel,
    itemLabel,
    targetId,
    typeLabel,
  }: {
    groupLabel: string;
    itemLabel: string;
    targetId: string;
    typeLabel: string;
  },
) {
  const navigation = draft.navigation;
  if (!navigation) throw new Error("Missing configuration navigation");
  const sourceId = "resource:sourceconfigs:legacy";
  const source = navigation.nodes[sourceId];
  if (!source) throw new Error("Missing source navigation node");
  const collectionTargetId = targetId.slice(0, targetId.lastIndexOf("."));
  const groupId = `definition-group:${collectionTargetId}`;
  const definitionId = `definition:${targetId}`;
  navigation.nodes[groupId] = {
    id: groupId,
    revision: `test:${groupId}`,
    parentId: sourceId,
    childIds: [definitionId],
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
  navigation.nodes[definitionId] = {
    id: definitionId,
    revision: `test:${definitionId}`,
    parentId: groupId,
    childIds: [],
    kind: "config-definition",
    label: itemLabel,
    description: typeLabel,
    status: "ok",
    phase: null,
    valueSummary: null,
    diagnostics: [],
    capabilities: [{
      kind: "edit",
      editTargetId: targetId,
      label: `Edit ${itemLabel}`,
    }],
    details: [],
    relationships: [],
    comparisons: [],
    resourcePlural: null,
    resourceName: null,
    resourceType: typeLabel,
    configPresence: {},
  };
  source.childIds.push(groupId);
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
  expect(screen.getByRole("button", { name: "Reset capture" })).toBeEnabled();
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


test("refreshes server-projected configuration navigation while editing", async () => {
  let snapshot = structuredClone(manageSnapshot);
  let configRequests = 0;
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
    http.get("*/api/v1/config", () => {
      configRequests += 1;
      const draft = structuredClone(configDraft);
      setNavigation(draft, snapshot);
      return HttpResponse.json(draft);
    }),
  );
  renderApp();
  await enterEditMode();

  const tree = screen.getByRole("tree", { name: "Workflow resources" });
  expect(within(tree).queryByText("capture-next")).toBeNull();

  const insertedId = "resource:captureproxies:capture-next";
  const groupId = "group:Live Traffic Migration:Capture";
  snapshot = {
    ...snapshot,
    revision: "snapshot-with-capture-next",
    nodes: {
      ...snapshot.nodes,
      [groupId]: {
        ...snapshot.nodes[groupId],
        childIds: [...snapshot.nodes[groupId].childIds, insertedId],
      },
      [insertedId]: {
        ...snapshot.nodes["resource:captureproxies:capture"],
        id: insertedId,
        revision: "capture-next-1",
        parentId: groupId,
        childIds: [],
        label: "capture-next",
        resourceName: "capture-next",
      },
    },
  };
  await userEvent.click(screen.getByRole("button", {
    name: "Refresh state",
  }));

  expect(await within(tree).findByRole("treeitem", {
    name: /^capture-next/,
  })).toBeInTheDocument();
  expect(configRequests).toBeGreaterThanOrEqual(2);
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
    details: [],
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
    { name: /^replay, Pending$/ },
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
    "Impossible update / reset required",
  )).toBeInTheDocument();
  expect(await within(requiredActions).findByText(
    /deployed resource must be deleted/,
  )).toBeInTheDocument();
  expect(within(requiredActions).getByRole("button", {
    name: "Edit configuration",
  })).toBeEnabled();
  await waitFor(() => expect(within(requiredActions).getByRole("button", {
    name: "Reset & resubmit",
  })).toBeEnabled());

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const capture = within(tree).getByRole(
    "treeitem",
    { name: /^capture, Ready$/ },
  );
  expect(within(capture).getByText("Reset before approval"))
    .toBeInTheDocument();
  expect(within(capture).getByText(/sourceLabel cannot be changed/))
    .toBeInTheDocument();
  await userEvent.click(capture);

  expect(screen.getByRole("region", {
    name: "Reset required before approval",
  })).toBeInTheDocument();
  const issue = screen.getByRole("alert", {
    name: "Apply failed; reset required",
  });
  expect(within(issue).getByText(
    "Impossible: sourceLabel cannot be changed. Delete and recreate.",
  )).toBeInTheDocument();
  expect(within(issue).getByText(
    "Reset capture to delete and recreate it, then retry the apply.",
  )).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Reset capture" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "Retry apply" }))
    .toBeDisabled();
  expect(screen.getByRole("button", { name: "Retry apply" }))
    .toHaveAttribute(
      "title",
      "Reset capture before retrying this apply.",
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
            message: "waitForProxyEndpointReady timed out",
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
  expect(screen.getByRole("combobox", { name: "Log target" }))
    .toHaveValue("log-target-all");

  await userEvent.click(screen.getByRole("button", {
    name: "Start logs",
  }));
  expect(await screen.findByText(
    "waitForProxyEndpointReady timed out",
  )).toBeInTheDocument();
  expect(startRequest).toEqual({
    targetId: "log-target-all",
    tailLines: 500,
    follow: true,
    pageSize: 200,
  });

  await userEvent.click(screen.getByRole("button", { name: "Pause" }));
  expect(screen.getByRole("button", { name: "Resume" }))
    .toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Stop" }));
  await waitFor(() => expect(stoppedStream).toBe("log-stream-test"));
  expect(screen.getByRole("button", { name: "Start logs" })).toBeEnabled();
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
  expect(within(outputReview).getByText("Approval accepted"))
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
  expect(await within(dialog).findByText("2 resources removed"))
    .toBeInTheDocument();
  const resetAll = within(dialog).getByRole("button", {
    name: "Reset & resubmit all (2)",
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

  const resourceToggle = await screen.findByRole("switch", {
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
  expect(within(center).getByRole("switch", {
    name: "Preapprove Document backfill",
  })).toBeDisabled();
  expect(within(center).getByRole("switch", {
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

  const allUpcoming = within(center).getByRole("switch", {
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
  expect(screen.getByRole("heading", { name: "Cleanup required" }))
    .toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Reset capture" }))
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
    name: /^capture, Removing$/,
  })).toBeInTheDocument());
  expect(screen.getAllByText("Removing")).not.toHaveLength(0);
  expect(screen.getByRole("button", { name: "Reset capture" }))
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
  expect(screen.getAllByText("Reset captureproxies/capture"))
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

  await client.invalidateQueries({ queryKey: ["manage-state"] });
  const inserted = await within(tree).findByRole(
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
  expect(screen.getByRole("heading", { name: "Workflow dependencies" }))
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
    name: /^replay$/,
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
  addSourceDefinitionNavigation(draft, {
    groupLabel: "Repositories",
    itemLabel: "repo1",
    targetId: "edit:sourceClusters.legacy.snapshotInfo.repos.repo1",
    typeLabel: "Snapshot repository",
  });
  addSourceDefinitionNavigation(draft, {
    groupLabel: "Snapshots",
    itemLabel: "nightly",
    targetId: "edit:sourceClusters.legacy.snapshotInfo.snapshots.nightly",
    typeLabel: "Source snapshot",
  });
  server.use(
    http.get("*/api/v1/config", () => HttpResponse.json(draft)),
  );
  renderApp();
  await enterEditMode();

  const tree = screen.getByRole("tree", { name: "Workflow resources" });
  const sourceItem = await within(tree).findByRole("treeitem", {
    name: /^legacy/,
  });
  await userEvent.click(within(sourceItem).getByRole("button", {
    name: "Expand legacy",
  }));
  await userEvent.click(await within(tree).findByRole("treeitem", {
    name: /^nightly/,
  }));

  expect(await screen.findByRole("heading", { name: "Edit nightly" }))
    .toBeInTheDocument();
  const config = screen.getByRole("table", {
    name: "Configuration fields",
  });
  expect(within(config).getByRole("row", { name: /Repository/ }))
    .toBeInTheDocument();
  expect(within(config).queryByRole("row", { name: /Endpoint/ })).toBeNull();

  await userEvent.click(sourceItem);
  expect(await screen.findByRole("heading", { name: "Edit legacy" }))
    .toBeInTheDocument();
  const snapshotRow = await within(config).findByRole("row", {
    name: /^nightly/,
  });
  await userEvent.click(within(snapshotRow).getByRole("button", {
    name: "Open nightly",
  }));
  expect(await screen.findByRole("heading", { name: "Edit nightly" }))
    .toBeInTheDocument();

  await userEvent.click(within(config).getByRole("button", {
    name: "Open repo1",
  }));

  expect(await screen.findByRole("heading", { name: "Edit repo1" }))
    .toBeInTheDocument();
  expect(within(config).getByRole("row", { name: /Repository URI/ }))
    .toBeInTheDocument();
  expect(within(tree).getByRole("treeitem", { name: /^repo1/ }))
    .toHaveAttribute("aria-selected", "true");

  expect(screen.getByRole("button", { name: "Back to nightly" }))
    .toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Back to nightly" }));
  expect(await screen.findByRole("heading", { name: "Edit nightly" }))
    .toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Back to legacy" }))
    .toBeInTheDocument();

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
    http.get("*/api/v1/config", () => HttpResponse.json(validDraft)),
  );
  renderApp();

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
    http.get("*/api/v1/config", () => HttpResponse.json(warningDraft)),
  );
  renderApp();

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
    http.get("*/api/v1/config", () => HttpResponse.json(invalidDraft)),
  );
  renderApp();

  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const sourceSection = within(tree).getAllByRole("treeitem", {
    name: /^Sources$/,
  }).find((item) => item.getAttribute("aria-level") === "1");
  const sourceGroup = within(tree).getAllByRole("treeitem", {
    name: /^Sources$/,
  }).find((item) => item.getAttribute("aria-level") === "2");
  const sourceRow = within(tree).getByRole("treeitem", {
    name: /^legacy$/,
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
    http.get("*/api/v1/config", () => HttpResponse.json(dirtyDraft)),
  );
  renderApp();
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
  expect(within(sourceRow).getByText("1 unsaved change")).toBeInTheDocument();

  const config = screen.getByRole("table", { name: "Configuration fields" });
  const endpointRow = within(config).getByRole("row", { name: /^Endpoint/ });
  const expectedTitle = "Changed in this edit. Previous value: https://legacy.example.com:9200.";
  expect(endpointRow).toHaveClass("draft-change-item");
  expect(endpointRow.querySelector(".property-label"))
    .toHaveAttribute("title", expectedTitle);
  expect(within(endpointRow).getByText("Changed"))
    .toHaveAttribute("title", expectedTitle);
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
    resourceType: "Kafka cluster",
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
    resourceType: "Captured traffic",
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
    name: /^proxy-topic, Captured traffic, Ready$/,
  });
  expect(within(kafka).getByText("Kafka cluster")).toBeInTheDocument();
  expect(within(kafka).getByText("Ready")).toBeInTheDocument();
  expect(within(kafka).getByText("Configured")).toBeInTheDocument();
  expect(within(s3).getByText("Captured traffic")).toBeInTheDocument();
  expect(within(s3).getByText("Ready")).toBeInTheDocument();
  expect(within(s3).getByText("Configured")).toBeInTheDocument();
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
    .getAllByRole("treeitem", { name: /^Sources$/ })
    .find((item) => item.getAttribute("aria-level") === "2");
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

  await waitFor(() => expect(operations).toEqual([{
    op: "add",
    path: ["sourceClusters"],
    value: { name: "next-source" },
  }]));
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
  const operations: unknown[] = [];
  let releaseOperation: (() => void) | null = null;
  const operationStarted = new Promise<void>((resolve) => {
    releaseOperation = resolve;
  });
  server.use(
    http.get("*/api/v1/config", () => HttpResponse.json(draft)),
    http.post("*/api/v1/config/operations", async ({ request }) => {
      const body = await request.json() as { operation: unknown };
      operations.push(body.operation);
      await operationStarted;
      return HttpResponse.json(draft);
    }),
  );
  renderApp();
  await enterEditMode();

  const tree = await screen.findByRole("tree", {
    name: "Workflow resources",
  });
  const sourceItem = within(tree).getByRole("treeitem", {
    name: /^legacy/,
  });
  await userEvent.click(within(sourceItem).getByRole("button", {
    name: "Expand legacy",
  }));
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
    name: /^repo2, Snapshot repository, Syncing configuration$/,
  })).toHaveAttribute("aria-selected", "true");
  expect(operations).toEqual([{
    op: "add",
    path: ["sourceClusters", "legacy", "snapshotInfo", "repos"],
    value: { name: "repo2" },
  }]);

  releaseOperation?.();
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
      resourceCollection: {
        navigation: {
          sectionId: "section:Snapshot Migration",
          sectionLabel: "Snapshot Migration",
          sectionOrder: 2,
          groupId: "group:Snapshot Migration:Backfill",
          groupLabel: "Backfill",
          groupOrder: 1,
          addControlId: "section:Snapshot Migration",
        },
        resource: {
          kind: "SnapshotMigration",
          plural: "snapshotmigrations",
          typeLabel: "Snapshot migration",
          identity: {
            kind: "indexed-config",
            prefix: "migration-",
            firstIndex: 1,
          },
        },
      },
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
  setNavigation(initialDraft, snapshot);

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
  if (!updatedDraft.navigation) {
    throw new Error("Missing snapshot navigation fixture");
  }
  addConfigNavigationResource(updatedDraft.navigation, {
    id: "config:snapshotMigrationConfigs:0",
    groupId: "group:Snapshot Migration:Backfill",
    label: "migration-1",
    editTargetId: "edit:snapshotMigrationConfigs.0",
    resourcePlural: "snapshotmigrations",
    resourceType: "Snapshot migration",
    status: "required",
  });

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
    name: /^Snapshot Migration$/,
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
  expect(screen.getByRole("heading", { name: "Workflow dependencies" }))
    .toBeInTheDocument();
});


test("repairs raw YAML and returns to the structured editor", async () => {
  const rawDraft = rawRepairDraft();
  const repairedDraft = structuredClone(configDraft);
  repairedDraft.draftRevision = "structured-repair-2";
  repairedDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  let replacement: unknown;
  server.use(
    http.get("*/api/v1/config", () => HttpResponse.json(rawDraft)),
    http.put("*/api/v1/config/raw", async ({ request }) => {
      replacement = await request.json();
      return HttpResponse.json(repairedDraft);
    }),
  );
  renderApp();
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

  await waitFor(() => expect(replacement).toEqual({
    expectedDraftRevision: "raw-repair-1",
    rawYaml: repairedYaml,
  }));
  expect(await screen.findByRole("table", {
    name: "Configuration fields",
  })).toBeInTheDocument();
  expect(screen.queryByRole("textbox", { name: "Workflow YAML" })).toBeNull();
});


test("protects and locally discards unsent raw YAML edits on exit", async () => {
  const rawDraft = rawRepairDraft();
  let rawReplacementCalls = 0;
  let closeRequest: unknown;
  server.use(
    http.get("*/api/v1/config", () => HttpResponse.json(rawDraft)),
    http.put("*/api/v1/config/raw", () => {
      rawReplacementCalls += 1;
      return HttpResponse.json(rawDraft);
    }),
    http.post("*/api/v1/config/close", async ({ request }) => {
      closeRequest = await request.json();
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderApp();
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

  expect(rawReplacementCalls).toBe(0);
  expect(closeRequest).toEqual({
    expectedDraftRevision: "raw-repair-1",
  });
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
  server.use(
    http.get("*/api/v1/config", () => HttpResponse.json(draft)),
  );
  renderApp();
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
      await new Promise((resolve) => globalThis.setTimeout(resolve, 20));
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
  const configMapRow = within(configTree).getByRole("row", {
    name: /ConfigMap/,
  });
  await userEvent.click(within(configMapRow).getByRole("button", {
    name: /Configure$/,
  }));

  const selector = await screen.findByRole("dialog", {
    name: "Select Transform ConfigMap",
  });
  expect(await within(selector).findByText("main.js")).toBeInTheDocument();
  expect(within(selector).getByText("settings.json")).toBeInTheDocument();
  await userEvent.click(
    within(selector).getByRole("button", {
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
  expect(screen.queryByRole("dialog", {
    name: "Select Transform ConfigMap",
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
    http.get("*/api/v1/config", () => HttpResponse.json(secretDraft)),
    http.get("*/api/v1/external-resources", () => HttpResponse.json({
      nodeId: secret.id,
      draftRevision: secretDraft.draftRevision,
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
      }],
    })),
    http.post("*/api/v1/external-resources/select", async ({ request }) => {
      selection = await request.json();
      return HttpResponse.json({
        ...secretDraft,
        dirty: true,
        draftRevision: "config-draft-secret-selected",
      });
    }),
  );
  renderApp();
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
    name: "Select HTTP Basic Auth Secret",
  });
  await userEvent.click(await within(selector).findByRole("button", {
    name: "Use source-creds",
  }));

  expect(selection).toEqual({
    expectedDraftRevision: "config-draft-1",
    nodeId: secret.id,
    name: "source-creds",
    kind: "Secret",
    group: "",
    key: null,
    acceptWarning: false,
    manual: false,
  });
  expect(screen.queryByRole("dialog", {
    name: "Select HTTP Basic Auth Secret",
  })).toBeNull();
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
  const configMapRow = within(configTree).getByRole("row", {
    name: /ConfigMap/,
  });
  await userEvent.click(
    within(configMapRow).getByRole("button", { name: /Configure$/ }),
  );
  const selector = await screen.findByRole("dialog", {
    name: "Select Transform ConfigMap",
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
  expect(screen.queryByRole("dialog", {
    name: "Select Transform ConfigMap",
  })).toBeNull();
});


test("dismisses Kubernetes resource selection without persistent inline controls", async () => {
  renderApp();
  await enterEditMode();
  const configTree = await screen.findByRole("table", {
    name: "Configuration fields",
  });
  const configMapRow = within(configTree).getByRole("row", {
    name: /ConfigMap/,
  });
  const configure = within(configMapRow).getByRole("button", {
    name: /Configure$/,
  });
  await userEvent.click(configure);

  const selector = await screen.findByRole("dialog", {
    name: "Select Transform ConfigMap",
  });
  expect(within(selector).getByRole("button", {
    name: "Close Kubernetes resource selector",
  })).toHaveFocus();
  await userEvent.keyboard("{Escape}");

  expect(screen.queryByRole("dialog", {
    name: "Select Transform ConfigMap",
  })).toBeNull();
  expect(screen.queryByRole("button", {
    name: "Enter reference manually",
  })).toBeNull();
  await waitFor(() => expect(configure).toHaveFocus());
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
  const removedNavigation = setNavigation(removedDraft, snapshot);
  removedNavigation.nodes[source.id] = {
    ...removedNavigation.nodes[source.id],
    revision: "source-removed",
    status: "removed",
    valueSummary: "Marked for removal",
  };

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
  const pendingDraft = structuredClone(configDraft);
  const pendingNavigation = setNavigation(pendingDraft, snapshot);
  pendingNavigation.nodes[source.id] = {
    ...pendingNavigation.nodes[source.id],
    revision: "source-removal-pending",
    status: "removed",
  };
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(snapshot)),
    http.get("*/api/v1/config", () => HttpResponse.json(pendingDraft)),
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
  const immediateNavigation = setNavigation(updatedDraft);
  addConfigNavigationResource(immediateNavigation, {
    id: "resource:sourceconfigs:immediate",
    groupId: "group:Sources:Sources",
    label: "immediate",
    editTargetId: "edit:sourceClusters.immediate",
    resourcePlural: "sourceconfigs",
    resourceType: "Source cluster",
    status: "required",
    diagnostics: [{
      severity: "required",
      message: "endpoint is required.",
      path: ["sourceClusters", "immediate", "endpoint"],
      source: null,
      code: null,
      title: null,
      remedy: null,
      technicalDetail: null,
    }],
  });

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
    name: /^Sources$/,
  }).find((item) => item.getAttribute("aria-level") === "2");
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
  const draft = addLegacySourceNavigation(structuredClone(configDraft));
  server.use(
    http.get("*/api/v1/config", () => HttpResponse.json(draft)),
  );
  renderApp();
  await enterEditMode();

  const tree = await screen.findByRole("tree", { name: "Workflow resources" });
  const capture = within(tree).getByRole("treeitem", {
    name: /^capture$/,
  });
  capture.focus();
  const sourceGroup = screen.getAllByRole("treeitem", {
    name: /^Sources$/,
  }).find((item) => item.getAttribute("aria-level") === "2");
  expect(sourceGroup).toBeDefined();
  if (!sourceGroup) throw new Error("Source group was not rendered");

  await userEvent.click(within(sourceGroup).getByRole("button", {
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
  const confirm = vi.spyOn(globalThis, "confirm").mockReturnValue(true);
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
    name: /^Sources$/,
  }).find((item) => item.getAttribute("aria-level") === "2");
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

  expect(optional).toBeChecked();
  expect(within(tree).queryByRole("textbox", {
    name: "source cluster name",
  })).toBeNull();
  expect(operations).toEqual([]);

  await userEvent.click(within(sourceGroup).getByRole("button", {
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
  const initialDraft = addLegacySourceNavigation(structuredClone(configDraft));
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
  const renamedNavigation = setNavigation(renamedDraft);
  addConfigNavigationResource(renamedNavigation, {
    id: "resource:sourceconfigs:modern",
    groupId: "group:Sources:Sources",
    label: "modern",
    editTargetId: "edit:sourceClusters.modern",
    resourcePlural: "sourceconfigs",
    resourceType: "Source cluster",
  });

  server.use(
    http.get("*/api/v1/config", () => HttpResponse.json(initialDraft)),
    http.post("*/api/v1/config/operations", async ({ request }) => {
      const body = await request.json() as { operation: unknown };
      operation = body.operation;
      await new Promise((resolve) => globalThis.setTimeout(resolve, 20));
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
    name: /^modern, Addition pending submission$/,
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
  const draft = addLegacySourceNavigation(structuredClone(configDraft));
  server.use(
    http.get("*/api/v1/config", () => HttpResponse.json(draft)),
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
  const configMapRow = within(configTree).getByRole("row", {
    name: /ConfigMap/,
  });
  await userEvent.click(within(configMapRow).getByRole("button", {
    name: /Configure$/,
  }));
  const selector = await screen.findByRole("dialog", {
    name: "Select Transform ConfigMap",
  });

  await userEvent.click(
    await within(selector).findByRole("button", {
      name: "Inspect transform-code",
    }),
  );
  expect(await within(selector).findByText("export default () => true;"))
    .toBeInTheDocument();
  expect(within(selector).queryByText(/raw YAML/i)).toBeNull();
  await userEvent.click(within(selector).getByRole("button", {
    name: "Back to resources",
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
  expect(screen.queryByRole("dialog", {
    name: "Select Transform ConfigMap",
  })).toBeNull();
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


test("exit offers continue or discard and reopening reloads saved values", async () => {
  let getCalls = 0;
  const closeRequests: unknown[] = [];
  const reopenedDraft = structuredClone(configDraft);
  reopenedDraft.baseRevision = "saved-after-close";
  reopenedDraft.draftRevision = "reopened-after-close";
  reopenedDraft.dirty = false;
  server.use(
    http.get("*/api/v1/config", () => {
      getCalls += 1;
      return HttpResponse.json(getCalls === 1 ? {
        ...configDraft,
        dirty: true,
        draftRevision: "dirty-close",
      } : reopenedDraft);
    }),
    http.post("*/api/v1/config/close", async ({ request }) => {
      closeRequests.push(await request.json());
      return new HttpResponse(null, { status: 204 });
    }),
  );
  const { client } = renderApp();
  await enterEditMode();

  await userEvent.click(
    screen.getByRole("button", { name: "Exit editing" }),
  );
  const firstPrompt = screen.getByRole("dialog", { name: "Leave editing?" });
  expect(closeRequests).toEqual([]);
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

  expect(closeRequests).toEqual([{
    expectedDraftRevision: "dirty-close",
  }]);
  expect(await screen.findByRole("button", { name: "Edit configuration" }))
    .toBeInTheDocument();

  await enterEditMode();
  await waitFor(() => expect(getCalls).toBe(2));
  expect(client.getQueryData(["config-draft"])).toMatchObject({
    baseRevision: "saved-after-close",
    draftRevision: "reopened-after-close",
    dirty: false,
  });

  await userEvent.click(
    screen.getByRole("button", { name: "Exit editing" }),
  );
  expect(closeRequests).toEqual([{
    expectedDraftRevision: "dirty-close",
  }, {
    expectedDraftRevision: "reopened-after-close",
  }]);
});


test("save and exit persists before closing the edit session", async () => {
  let saveRequest: unknown;
  let closeRequest: unknown;
  const savedDraft = {
    ...configDraft,
    baseRevision: "saved-on-exit",
    draftRevision: "saved-on-exit",
    dirty: false,
  };
  server.use(
    http.get("*/api/v1/config", () => HttpResponse.json({
      ...configDraft,
      dirty: true,
      draftRevision: "dirty-save-exit",
    })),
    http.post("*/api/v1/config/save", async ({ request }) => {
      saveRequest = await request.json();
      return HttpResponse.json(savedDraft);
    }),
    http.post("*/api/v1/config/close", async ({ request }) => {
      closeRequest = await request.json();
      return new HttpResponse(null, { status: 204 });
    }),
  );
  renderApp();
  await enterEditMode();

  await userEvent.click(screen.getByRole("button", { name: "Exit editing" }));
  await userEvent.click(screen.getByRole("button", { name: "Save and exit" }));

  expect(saveRequest).toEqual({
    expectedDraftRevision: "dirty-save-exit",
  });
  expect(closeRequest).toEqual({
    expectedDraftRevision: "saved-on-exit",
  });
  expect(await screen.findByRole("button", { name: "Edit configuration" }))
    .toBeInTheDocument();
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


test("keeps submit enabled for admission warnings that may converge later", async () => {
  let submitRequest: unknown;
  const savedDraft = structuredClone(configDraft);
  savedDraft.dirty = false;
  savedDraft.draftRevision = "warning-preflight";
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
  renderApp();

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
    expectedDraftRevision: "warning-preflight",
  }));
});


test("shows resources that submission will reconcile for checksum-only changes", async () => {
  const savedDraft = structuredClone(configDraft);
  savedDraft.dirty = false;
  savedDraft.draftRevision = "checksum-impact";
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
  renderApp();

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
  const savedDraft = structuredClone(configDraft);
  savedDraft.dirty = false;
  savedDraft.draftRevision = "immutable-preflight";
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
  renderApp();

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
    "No workflow will be submitted while reset-required admission errors "
      + "remain. The affected resources and their dependencies will stay "
      + "blocked. Use Reset & resubmit.",
  );
  const resetAndResubmit = await within(dialog).findByRole("button", {
    name: "Reset & resubmit (2)",
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
    expectedDraftRevision: "immutable-preflight",
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
  validDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.get("*/api/v1/manage/state", () => HttpResponse.json(currentState)),
    http.get("*/api/v1/config", () => HttpResponse.json(validDraft)),
    http.post("*/api/v1/config/review", () => HttpResponse.json({
      draftRevision: validDraft.draftRevision,
      baseRevision: validDraft.baseRevision,
      dirty: false,
      valid: true,
      validationMessages: [],
      changes: [],
    })),
  );
  renderApp();

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
    http.get("*/api/v1/config", () => HttpResponse.json(validDraft)),
  );
  renderApp();

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
  server.use(
    http.get("*/api/v1/config", () => HttpResponse.json({
      ...configDraft,
      dirty: true,
      draftRevision: "dirty-escape",
    })),
  );
  renderApp();
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
  const dirtyDraft = structuredClone(configDraft);
  dirtyDraft.dirty = true;
  dirtyDraft.draftRevision = "dirty-submit-escape";
  dirtyDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.get("*/api/v1/config", () => HttpResponse.json(dirtyDraft)),
  );
  renderApp();
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
    name: "Reset capture",
  }));
  expect(await screen.findByRole("dialog", {
    name: "Review reset plan",
  })).toBeInTheDocument();

  await userEvent.keyboard("{Escape}");

  expect(screen.queryByRole("dialog", {
    name: "Review reset plan",
  })).toBeNull();
  expect(screen.getByRole("heading", { name: "capture" }))
    .toBeInTheDocument();
});


test("shows structured admission preflight preparation failures", async () => {
  const validDraft = structuredClone(configDraft);
  validDraft.editState.validation = {
    valid: true,
    errors: [],
    diagnostics: [],
  };
  server.use(
    http.get("*/api/v1/config", () => HttpResponse.json(validDraft)),
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
  renderApp();

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
