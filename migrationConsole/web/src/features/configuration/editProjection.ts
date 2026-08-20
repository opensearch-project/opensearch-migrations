import type {
  ConfigDraft,
  EditNode,
  ManageNode,
  ManageSnapshot,
} from "../../api/client";
import {
  resourceAdditionIdentity,
  resourceAddPlacements,
  type PendingResourceAddition,
  type PendingResourceRename,
  type ResourceAddPlacement,
} from "./resourceAdds";


export function editTarget(node: ManageNode): string | null {
  const capability = node.capabilities.find(
    (candidate) => candidate.kind === "edit",
  );
  return capability?.kind === "edit" ? capability.editTargetId : null;
}


export interface ResourceValidationState {
  issueCount: number;
  label: string;
  level: "valid" | "warning" | "error";
}


function validationState(node: EditNode): ResourceValidationState {
  const counts = node.statusCounts;
  let errors = (
    (counts?.errors ?? 0)
    + (counts?.required ?? 0)
    + (counts?.gated ?? 0)
    + (counts?.blocked ?? 0)
  );
  let warnings = counts?.warnings ?? 0;
  if (errors === 0 && warnings === 0) {
    if (["required", "error", "gated", "blocked"].includes(node.status ?? "")) {
      errors = 1;
    } else if (node.status === "warning") {
      warnings = 1;
    }
  }
  const childStates = (node.children ?? [])
    .filter((child) => child.valueKind !== "command")
    .map(validationState);
  errors = Math.max(
    errors,
    childStates
      .filter((state) => state.level === "error")
      .reduce((total, state) => total + state.issueCount, 0),
  );
  warnings = Math.max(
    warnings,
    childStates
      .filter((state) => state.level === "warning")
      .reduce((total, state) => total + state.issueCount, 0),
  );
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


function flattenEditNodes(nodes: EditNode[]): Map<string, EditNode> {
  const result = new Map<string, EditNode>();
  const visit = (node: EditNode) => {
    result.set(node.id, node);
    (node.children ?? []).forEach(visit);
  };
  nodes.forEach(visit);
  return result;
}


export function resourceValidationStates(
  snapshot: ManageSnapshot,
  draft: ConfigDraft | undefined,
): Record<string, ResourceValidationState> {
  if (!draft) return {};
  const editNodes = flattenEditNodes(draft.editState.nodes);
  const result: Record<string, ResourceValidationState> = {};
  Object.values(snapshot.nodes).forEach((node) => {
    if (node.kind !== "resource") return;
    const targetId = editTarget(node);
    const target = targetId ? editNodes.get(targetId) : undefined;
    if (target) result[node.id] = validationState(target);
  });
  resourceAddPlacements().forEach((placement) => {
    const collection = collectionNode(editNodes, placement);
    if (!collection) return;
    const pathLength = placement.collectionPath.split(".").length;
    (collection.children ?? [])
      .filter((child) => child.valueKind !== "command")
      .forEach((child, index) => {
        const name = child.path[pathLength] ?? "";
        const identity = resourceAdditionIdentity(placement, name, index);
        if (snapshot.nodes[identity.id] && !result[identity.id]) {
          result[identity.id] = validationState(child);
        }
      });
  });
  return result;
}


export interface ResourceDraftChangeState {
  count: number;
  label: string;
}


function draftChangeState(node: EditNode): ResourceDraftChangeState | null {
  const count = node.draftChangeCount
    || (node.draftChange ? 1 : 0);
  if (!count) return null;
  return {
    count,
    label: `${count} unsaved ${count === 1 ? "change" : "changes"}`,
  };
}


export function resourceDraftChangeStates(
  snapshot: ManageSnapshot,
  draft: ConfigDraft | undefined,
): Record<string, ResourceDraftChangeState> {
  if (!draft?.dirty) return {};
  const editNodes = flattenEditNodes(draft.editState.nodes);
  const result: Record<string, ResourceDraftChangeState> = {};
  Object.values(snapshot.nodes).forEach((node) => {
    if (node.kind !== "resource") return;
    const targetId = editTarget(node);
    const target = targetId ? editNodes.get(targetId) : undefined;
    const change = target ? draftChangeState(target) : null;
    if (change) result[node.id] = change;
  });
  resourceAddPlacements().forEach((placement) => {
    const collection = collectionNode(editNodes, placement);
    if (!collection) return;
    const pathLength = placement.collectionPath.split(".").length;
    (collection.children ?? [])
      .filter((child) => child.valueKind !== "command")
      .forEach((child, index) => {
        const name = child.path[pathLength] ?? "";
        const identity = resourceAdditionIdentity(placement, name, index);
        const change = draftChangeState(child);
        if (snapshot.nodes[identity.id] && change && !result[identity.id]) {
          result[identity.id] = change;
        }
      });
  });
  return result;
}


function isConfigResourceTarget(targetId: string): boolean {
  const path = targetId.startsWith("edit:")
    ? targetId.slice("edit:".length)
    : targetId;
  return resourceAddPlacements().some(
    ({ collectionPath }) => (
      path === collectionPath || path.startsWith(`${collectionPath}.`)
    ),
  );
}


function removalLabel(node: ManageNode, draft: ConfigDraft): string {
  if (node.configPresence?.pending === false) {
    return "Removal pending submission";
  }
  return draft.dirty ? "Marked for removal" : "Removal pending submission";
}


function appendAddition(
  nodes: Record<string, ManageNode>,
  addition: {
    id: string;
    editTargetId: string;
    groupId: string;
    label: string;
    resourceName: string;
    resourcePlural: string;
  },
  revision: string,
  status: string,
  valueSummary: string,
  diagnostics: ManageNode["diagnostics"] = [],
) {
  if (nodes[addition.id]) return;
  const group = nodes[addition.groupId];
  if (!group) return;
  nodes[addition.id] = {
    id: addition.id,
    revision,
    parentId: group.id,
    childIds: [],
    kind: "resource",
    label: addition.label,
    description: `${addition.resourcePlural}/${addition.resourceName}`,
    status,
    phase: status === "syncing" ? "Syncing" : "Pending Config",
    valueSummary,
    diagnostics,
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
    comparisons: [],
    resourcePlural: addition.resourcePlural,
    resourceName: addition.resourceName,
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


function collectionNode(
  editNodes: Map<string, EditNode>,
  placement: ResourceAddPlacement,
): EditNode | undefined {
  return editNodes.get(`edit:${placement.collectionPath}`);
}


function projectDraftAdditions(
  nodes: Record<string, ManageNode>,
  editNodes: Map<string, EditNode>,
  draft: ConfigDraft,
) {
  resourceAddPlacements().forEach((placement) => {
    const collection = collectionNode(editNodes, placement);
    if (!collection) return;
    const pathLength = placement.collectionPath.split(".").length;
    (collection.children ?? [])
      .filter((child) => child.valueKind !== "command")
      .forEach((child, index) => {
        if (
          Object.values(nodes).some(
            (node) => editTarget(node) === child.id,
          )
        ) {
          return;
        }
        const name = child.path[pathLength] ?? "";
        const identity = resourceAdditionIdentity(placement, name, index);
        appendAddition(
          nodes,
          {
            ...identity,
            groupId: placement.groupId,
            resourcePlural: placement.resourcePlural,
          },
          `${draft.draftRevision}:${child.id}:added`,
          child.status && child.status !== "ok" ? child.status : "changed",
          "Addition pending submission",
          (child.diagnostics ?? []).map((diagnostic) => ({
            ...diagnostic,
            source: null,
          })),
        );
      });
  });
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
        revision: `rename:${rename.oldId}:${rename.id}:syncing`,
        status: "syncing",
        phase: "Syncing",
        valueSummary: "Syncing configuration",
        capabilities: [],
      }
      : {
        ...renamed,
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


export function projectEditSnapshot(
  snapshot: ManageSnapshot,
  draft: ConfigDraft | undefined,
  pendingAdditions: PendingResourceAddition[] = [],
  pendingRenames: PendingResourceRename[] = [],
): ManageSnapshot {
  if (
    !draft
    && pendingAdditions.length === 0
    && pendingRenames.length === 0
  ) {
    return snapshot;
  }
  const editNodes = flattenEditNodes(draft?.editState.nodes ?? []);
  const nodes: Record<string, ManageNode> = draft
    ? Object.fromEntries(Object.entries(snapshot.nodes).map(([nodeId, node]) => {
      if (node.kind === "workflow-step") return [nodeId, node];
      const targetId = editTarget(node);
      const pendingRemoval = (
        node.configPresence?.pending === false
        && (
          node.configPresence.deployed === true
          || node.configPresence.submitted === true
        )
      );
      if (
        !pendingRemoval
        && (
          !targetId
          || !isConfigResourceTarget(targetId)
          || editNodes.has(targetId)
        )
      ) {
        return [nodeId, node];
      }
      const label = removalLabel(node, draft);
      return [
        nodeId,
        {
          ...node,
          revision: `${node.revision}:${draft.draftRevision}:removed`,
          status: "removed",
          valueSummary: label,
        },
      ];
    }))
    : { ...snapshot.nodes };
  if (draft) projectDraftAdditions(nodes, editNodes, draft);
  pendingAdditions.forEach((addition) => appendAddition(
    nodes,
    addition,
    `optimistic:${addition.id}`,
    "syncing",
    "Syncing configuration",
  ));
  pendingRenames.forEach((rename) => projectPendingRename(nodes, rename));
  return {
    ...snapshot,
    revision: `${snapshot.revision}:${draft?.draftRevision ?? "loading"}:editing`,
    nodes,
  };
}
