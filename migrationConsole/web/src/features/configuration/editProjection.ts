import type {
  ConfigDraft,
  EditNode,
  ManageNode,
  ManageSnapshot,
} from "../../api/client";


const CONFIG_RESOURCE_ROOTS = [
  "sourceClusters",
  "targetClusters",
  "snapshotMigrationConfigs",
  "traffic.kafkaClusters",
  "traffic.proxies",
  "traffic.s3Sources",
  "traffic.replayers",
] as const;


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
  return CONFIG_RESOURCE_ROOTS.some(
    (root) => path === root || path.startsWith(`${root}.`),
  );
}


function removalLabel(node: ManageNode, draft: ConfigDraft): string {
  if (node.configPresence?.pending === false) {
    return "Removal pending submission";
  }
  return draft.dirty ? "Marked for removal" : "Removal pending submission";
}


export function projectEditSnapshot(
  snapshot: ManageSnapshot,
  draft: ConfigDraft | undefined,
): ManageSnapshot {
  if (!draft) return snapshot;
  const editNodes = flattenEditNodes(draft.editState.nodes);
  const nodes = Object.fromEntries(
    Object.entries(snapshot.nodes).map(([nodeId, node]) => {
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
    }),
  );
  return {
    ...snapshot,
    revision: `${snapshot.revision}:${draft.draftRevision}:editing`,
    nodes,
  };
}
