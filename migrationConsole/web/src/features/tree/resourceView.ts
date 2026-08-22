import type { ManageNode, ManageSnapshot } from "../../api/client";


export type ResourceViewMode =
  | "all"
  | "deployed"
  | "submitted"
  | "pending";


export const RESOURCE_VIEW_OPTIONS: ReadonlyArray<{
  mode: ResourceViewMode;
  label: string;
  description: string;
}> = [
  {
    mode: "all",
    label: "All",
    description: "Compare resources across every rollout state",
  },
  {
    mode: "deployed",
    label: "Deployed",
    description: "Show resources currently deployed in the cluster",
  },
  {
    mode: "submitted",
    label: "Submitted",
    description: "Show resources owned by the active workflow",
  },
  {
    mode: "pending",
    label: "Saved config",
    description: "Show resources planned by the saved configuration",
  },
];


function resourceVisible(node: ManageNode, mode: ResourceViewMode): boolean {
  if (mode === "all") return true;
  const presence = node.configPresence ?? {};
  if (Object.keys(presence).length === 0) return true;
  const deployed = presence.deployed ?? true;
  if (mode === "deployed") return deployed;
  const submitted = presence.submitted ?? deployed;
  if (mode === "submitted") return submitted;
  return presence.pending ?? submitted;
}


function includeDescendants(
  snapshot: ManageSnapshot,
  nodeId: string,
  included: Set<string>,
) {
  const node = snapshot.nodes[nodeId];
  if (!node) return;
  node.childIds.forEach((childId) => {
    included.add(childId);
    includeDescendants(snapshot, childId, included);
  });
}


function includeAncestors(
  snapshot: ManageSnapshot,
  node: ManageNode,
  included: Set<string>,
) {
  let parentId = node.parentId;
  while (parentId) {
    included.add(parentId);
    parentId = snapshot.nodes[parentId]?.parentId ?? null;
  }
}


export function projectResourceView(
  snapshot: ManageSnapshot,
  mode: ResourceViewMode,
): ManageSnapshot {
  if (mode === "all") return snapshot;
  const included = new Set<string>();
  Object.values(snapshot.nodes).forEach((node) => {
    if (node.kind !== "resource" || !resourceVisible(node, mode)) return;
    included.add(node.id);
    includeAncestors(snapshot, node, included);
    includeDescendants(snapshot, node.id, included);
  });
  const nodes = Object.fromEntries(
    [...included].flatMap((nodeId) => {
      const node = snapshot.nodes[nodeId];
      if (!node) return [];
      return [[nodeId, {
        ...node,
        childIds: node.childIds.filter((childId) => included.has(childId)),
      }]];
    }),
  );
  return {
    ...snapshot,
    nodes,
    rootIds: snapshot.rootIds.filter((rootId) => included.has(rootId)),
  };
}
