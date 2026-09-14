import type {
  ManageNode,
  ManageSnapshot,
} from "../../api/client";
import type { EditNode } from "@opensearch-migrations/config-edit-core";
import {
  type PendingResourceAddition,
  type PendingResourceRename,
} from "./resourceAdds";


export function editTarget(node: ManageNode): string | null {
  const capability = node.capabilities.find(
    (candidate) => candidate.kind === "edit",
  );
  return capability?.kind === "edit" ? capability.editTargetId : null;
}


export function removableEditTarget(
  nodes: EditNode[],
  targetId: string | null,
): string | null {
  if (!targetId) return null;
  let bestMatchId: string | null = null;
  const visit = (node: EditNode) => {
    if (
      node.removable
      && (
        node.id === targetId
        || targetId.startsWith(`${node.id}.`)
      )
      && (!bestMatchId || node.id.length > bestMatchId.length)
    ) {
      bestMatchId = node.id;
    }
    if (Array.isArray(node.children)) {
      node.children.forEach(visit);
    }
  };
  nodes.forEach(visit);
  return bestMatchId;
}


export function navigationResourceId(
  nodes: Record<string, ManageNode>,
  targetId: string,
): string | null {
  const resource = Object.values(nodes).find(
    (node) => (
      ["resource", "config-definition"].includes(node.kind)
      && editTarget(node) === targetId
    ),
  );
  return resource?.id ?? null;
}


export function settledRenameResourceId(
  nodes: Record<string, ManageNode>,
  rename: PendingResourceRename,
): string | null {
  if (rename.id === rename.oldId) {
    const stableNode = nodes[rename.id];
    if (
      stableNode?.resourceName === rename.resourceName
      && editTarget(stableNode) === rename.editTargetId
    ) {
      return rename.id;
    }
    // A stable edit target does not promise a stable node id. The server can
    // answer the rename by retiring the old identity and publishing the new
    // name under a different node, so accept whichever node now owns the
    // target under the new name - otherwise the overlay never settles and its
    // synthesized row shadows the real one.
    const republished = Object.values(nodes).find((node) => (
      node.id !== rename.oldId
      && node.resourceName === rename.resourceName
      && editTarget(node) === rename.editTargetId
    ));
    return republished?.id ?? null;
  }
  if (nodes[rename.oldId]) return null;
  if (nodes[rename.id]) return rename.id;
  const resourceId = navigationResourceId(nodes, rename.editTargetId);
  return resourceId === rename.oldId ? null : resourceId;
}


export interface ResourceValidationState {
  issueCount: number;
  label: string;
  level: "valid" | "warning" | "error";
}


function validationState(
  node: ManageNode,
): ResourceValidationState | null {
  const state = node.configState;
  if (!state) return null;
  const errors = state.validationErrors;
  const warnings = state.validationWarnings;
  if (errors > 0) {
    return {
      issueCount: errors + warnings,
      label: `${errors + warnings} validation ${
        errors + warnings === 1 ? "issue" : "issues"
      }`,
      level: "error",
    };
  }
  if (warnings > 0) {
    return {
      issueCount: warnings,
      label: `${warnings} validation ${
        warnings === 1 ? "warning" : "warnings"
      }`,
      level: "warning",
    };
  }
  return {
    issueCount: 0,
    label: "Configuration valid",
    level: "valid",
  };
}


export function resourceValidationStates(
  snapshot: ManageSnapshot,
): Record<string, ResourceValidationState> {
  const result: Record<string, ResourceValidationState> = {};
  Object.values(snapshot.nodes).forEach((node) => {
    if (!["resource", "config-definition"].includes(node.kind)) return;
    const state = validationState(node);
    if (state) result[node.id] = state;
  });
  return result;
}


export interface ResourceDraftChangeState {
  count: number;
  label: string;
}


function draftChangeState(
  node: ManageNode,
): ResourceDraftChangeState | null {
  const count = node.configState?.draftChangeCount ?? 0;
  if (!count) return null;
  return {
    count,
    label: `${count} unsaved ${count === 1 ? "change" : "changes"}`,
  };
}


export function resourceDraftChangeStates(
  snapshot: ManageSnapshot,
): Record<string, ResourceDraftChangeState> {
  const result: Record<string, ResourceDraftChangeState> = {};
  Object.values(snapshot.nodes).forEach((node) => {
    if (!["resource", "config-definition"].includes(node.kind)) return;
    const change = draftChangeState(node);
    if (change) result[node.id] = change;
  });
  return result;
}


function appendAddition(
  nodes: Record<string, ManageNode>,
  addition: PendingResourceAddition | PendingResourceRename,
  revision: string,
  status: string,
  valueSummary: string,
) {
  if (nodes[addition.id]) return;
  if (
    "sectionId" in addition
    && addition.sectionId
    && !nodes[addition.groupId]
  ) {
    const section = nodes[addition.sectionId];
    if (section) {
      nodes[addition.groupId] = {
        id: addition.groupId,
        revision: `${revision}:group`,
        parentId: section.id,
        childIds: [],
        kind: "group",
        label: addition.groupLabel,
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
      nodes[section.id] = {
        ...section,
        revision: `${section.revision}:${revision}:group`,
        childIds: section.childIds.includes(addition.groupId)
          ? section.childIds
          : [...section.childIds, addition.groupId],
      };
    }
  }
  const group = nodes[addition.groupId];
  if (!group) return;
  nodes[addition.id] = {
    id: addition.id,
    revision,
    parentId: group.id,
    childIds: [],
    kind: addition.nodeKind,
    label: addition.label,
    description: addition.nodeKind === "config-definition"
      ? addition.resourceType
      : `${addition.resourcePlural}/${addition.resourceName}`,
    status,
    phase: status === "syncing" ? "Syncing" : "Pending Config",
    valueSummary,
    diagnostics: [],
    capabilities: status === "syncing" ? [] : [{
      kind: "edit",
      editTargetId: addition.editTargetId,
      label: `Edit ${addition.label}`,
    }],
    details: [{
      label: "Phase",
      value: status === "syncing" ? "Syncing" : "Pending Config",
      kind: "phase",
    }],
    relationships: [],
    comparisons: [],
    resourcePlural: addition.resourcePlural ?? null,
    resourceName: addition.nodeKind === "resource"
      ? addition.resourceName
      : null,
    resourceType: addition.resourceType,
    configPresence: {
      deployed: false,
      pending: true,
    },
  };
  nodes[group.id] = {
    ...group,
    revision: `${group.revision}:${revision}`,
    childIds: [...group.childIds, addition.id],
  };
}


function projectPendingRename(
  nodes: Record<string, ManageNode>,
  rename: PendingResourceRename,
) {
  const previous = nodes[rename.oldId];
  const groupId = previous?.parentId ?? rename.groupId;
  delete nodes[rename.oldId];

  if (!nodes[rename.id]) {
    appendAddition(
      nodes,
      rename,
      `rename:${rename.oldId}:${rename.id}`,
      rename.status === "syncing" ? "syncing" : "changed",
      rename.status === "syncing"
        ? "Syncing configuration"
        : "Rename pending submission",
    );
  }

  const renamed = nodes[rename.id];
  if (renamed) {
    nodes[rename.id] = rename.status === "syncing"
      ? {
        ...renamed,
        label: rename.label,
        revision: `rename:${rename.oldId}:${rename.id}:syncing`,
        resourceName: rename.resourceName,
        status: "syncing",
        phase: "Syncing",
        valueSummary: "Syncing configuration",
        capabilities: [],
      }
      : {
        ...renamed,
        label: rename.label,
        resourceName: rename.resourceName,
        valueSummary: "Rename pending submission",
      };
  }

  const group = nodes[groupId];
  if (!group) return;
  const childIds = group.childIds.map(
    (childId) => childId === rename.oldId ? rename.id : childId,
  );
  if (!childIds.includes(rename.id)) childIds.push(rename.id);
  nodes[groupId] = {
    ...group,
    revision: `${group.revision}:rename:${rename.oldId}:${rename.id}`,
    childIds: [...new Set(childIds)],
  };
}


function withoutWorkflowSteps(snapshot: ManageSnapshot): ManageSnapshot {
  const stepIds = new Set(
    Object.values(snapshot.nodes)
      .filter((node) => node.kind === "workflow-step")
      .map((node) => node.id),
  );
  if (stepIds.size === 0) return snapshot;
  return {
    ...snapshot,
    rootIds: snapshot.rootIds.filter((nodeId) => !stepIds.has(nodeId)),
    nodes: Object.fromEntries(
      Object.entries(snapshot.nodes).flatMap(([nodeId, node]) => (
        stepIds.has(nodeId)
          ? []
          : [[nodeId, {
            ...node,
            childIds: node.childIds.filter(
              (childId) => !stepIds.has(childId),
            ),
          }]]
      )),
    ),
  };
}


export function projectEditSnapshot(
  snapshot: ManageSnapshot,
  pendingAdditions: PendingResourceAddition[] = [],
  pendingRenames: PendingResourceRename[] = [],
): ManageSnapshot {
  // Navigation is absent until the server has its first runtime observation.
  // Keep that loading fallback free of workflow execution steps.
  const configurationSnapshot = withoutWorkflowSteps(snapshot);
  if (pendingAdditions.length === 0 && pendingRenames.length === 0) {
    return configurationSnapshot;
  }
  const nodes = { ...configurationSnapshot.nodes };
  pendingAdditions.forEach((addition) => {
    const syncing = addition.status === "syncing";
    appendAddition(
      nodes,
      addition,
      `optimistic:${addition.id}`,
      syncing ? "syncing" : "changed",
      syncing ? "Syncing configuration" : "Addition pending submission",
    );
  });
  pendingRenames.forEach((rename) => projectPendingRename(nodes, rename));
  return {
    ...configurationSnapshot,
    revision: `${configurationSnapshot.revision}:optimistic-edit`,
    nodes,
  };
}
