import type { ManageNode } from "../../api/client";
import type {
  ClusterCurlExplorerState,
} from "./ClusterCurlDock";
import type { ClusterCurlTarget } from "./clusterCurlTargets";
import { DEFAULT_DOCK_HEIGHT } from "./dockPaneState";
import type {
  RuntimeDashboardSnapshot,
} from "./runtimeDashboardState";
import type {
  RuntimeStatusPaneState,
} from "./RuntimeStatusDock";


export interface ActivityTarget extends ClusterCurlTarget {
  kind: "source" | "target";
}


export interface ActivityType {
  id: string;
  label: string;
  resourcePlural?: string;
  curlPath?: string;
}


export const CLUSTER_INDICES_ACTIVITY_ID = "cluster-indices";


const ACTIVITY_TYPES: ActivityType[] = [
  {
    id: CLUSTER_INDICES_ACTIVITY_ID,
    label: "Cluster indices",
    curlPath: "/_cat/indices?pretty&v",
  },
  {
    id: "cluster-health",
    label: "Cluster health",
    curlPath: "/_cluster/health?pretty",
  },
  {
    id: "data-snapshots",
    label: "Data snapshots",
    resourcePlural: "datasnapshots",
  },
  {
    id: "snapshot-migrations",
    label: "Snapshot migrations",
    resourcePlural: "snapshotmigrations",
  },
  {
    id: "captured-traffic",
    label: "Captured traffic",
    resourcePlural: "capturedtraffics",
  },
  {
    id: "capture-proxies",
    label: "Capture proxies",
    resourcePlural: "captureproxies",
  },
  {
    id: "traffic-replays",
    label: "Traffic replays",
    resourcePlural: "trafficreplays",
  },
];


export function activityTargets(
  nodes: Record<string, ManageNode>,
): ActivityTarget[] {
  return Object.values(nodes)
    .flatMap((node) => {
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
        kind: node.resourcePlural === "sourceconfigs"
          ? "source" as const
          : "target" as const,
      }];
    })
    .sort((left, right) => (
      left.kind.localeCompare(right.kind)
      || left.clusterName.localeCompare(right.clusterName)
    ));
}


export function activityTypes(
  nodes: Record<string, ManageNode>,
): ActivityType[] {
  const plurals = new Set(
    Object.values(nodes).map((node) => node.resourcePlural),
  );
  return ACTIVITY_TYPES.filter((type) => (
    type.curlPath || plurals.has(type.resourcePlural ?? "")
  ));
}


function targetMatchesNode(
  target: ActivityTarget,
  node: ManageNode,
): boolean {
  const references = target.kind === "source"
    ? node.sourceRefs
    : node.targetRefs;
  return references?.includes(target.clusterName) ?? false;
}


function curlExplorer(
  target: ActivityTarget,
  type: ActivityType,
): ClusterCurlExplorerState {
  return {
    id: `activity:${type.id}:${target.nodeId}`,
    clusterName: target.clusterName,
    nodeId: target.nodeId,
    method: "GET",
    path: type.curlPath ?? "/",
    body: "",
    pinned: false,
    autoRefresh: false,
    expanded: true,
    editing: false,
    height: DEFAULT_DOCK_HEIGHT,
  };
}


function runtimeStatusPane(node: ManageNode): RuntimeStatusPaneState {
  return {
    id: `runtime-status:${node.id}`,
    nodeId: node.id,
    nodeLabel: node.label,
    resourceType: node.resourceType ?? "Resource",
    pinned: false,
    expanded: true,
    height: DEFAULT_DOCK_HEIGHT,
  };
}


export function buildActivityDashboard(
  nodes: Record<string, ManageNode>,
  selectedTargetIds: Set<string>,
  selectedTypeIds: Set<string>,
): RuntimeDashboardSnapshot {
  const targets = activityTargets(nodes).filter(
    (target) => selectedTargetIds.has(target.nodeId),
  );
  const types = activityTypes(nodes).filter(
    (type) => selectedTypeIds.has(type.id),
  );
  const curlExplorers = targets.flatMap((target) => (
    types
      .filter((type) => type.curlPath)
      .map((type) => curlExplorer(target, type))
  ));
  const runtimeStatuses = new Map<string, RuntimeStatusPaneState>();
  for (const type of types) {
    if (!type.resourcePlural) continue;
    for (const node of Object.values(nodes)) {
      if (
        node.resourcePlural === type.resourcePlural
        && targets.some((target) => targetMatchesNode(target, node))
      ) {
        runtimeStatuses.set(node.id, runtimeStatusPane(node));
      }
    }
  }
  return {
    version: 1,
    curlExplorers,
    runtimeStatuses: [...runtimeStatuses.values()],
  };
}
