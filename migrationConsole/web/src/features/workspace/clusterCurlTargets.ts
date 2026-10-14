import type { ManageNode } from "../../api/client";


export interface ClusterCurlTarget {
  clusterName: string;
  nodeId: string;
}


export function clusterCurlTargets(
  nodes: Record<string, ManageNode>,
): ClusterCurlTarget[] {
  const targets = Object.values(nodes).flatMap((node) => {
    if (
      !node.resourceName
      || !["sourceconfigs", "targetconfigs"].includes(
        node.resourcePlural ?? "",
      )
    ) {
      return [];
    }
    return [{
      clusterName: node.resourceName,
      nodeId: node.id,
      order: node.resourcePlural === "sourceconfigs" ? 0 : 1,
    }];
  });
  const seen = new Set<string>();
  return targets
    .sort((left, right) => (
      left.order - right.order
      || left.clusterName.localeCompare(right.clusterName)
    ))
    .flatMap(({ clusterName, nodeId }) => {
      if (seen.has(nodeId)) return [];
      seen.add(nodeId);
      return [{ clusterName, nodeId }];
    });
}
