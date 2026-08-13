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
  type ResourceAddPlacement,
} from "./resourceAdds";


export function editTarget(node: ManageNode): string | null {
  const capability = node.capabilities.find(
    (candidate) => candidate.kind === "edit",
  );
  return capability?.kind === "edit" ? capability.editTargetId : null;
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


export function projectEditSnapshot(
  snapshot: ManageSnapshot,
  draft: ConfigDraft | undefined,
  pendingAdditions: PendingResourceAddition[] = [],
): ManageSnapshot {
  if (!draft && pendingAdditions.length === 0) return snapshot;
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
  return {
    ...snapshot,
    revision: `${snapshot.revision}:${draft?.draftRevision ?? "loading"}:editing`,
    nodes,
  };
}
