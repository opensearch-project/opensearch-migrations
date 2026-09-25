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


type ResourceValueState = Exclude<ResourceViewMode, "all">;


function navigationState(
  node: ManageNode,
  mode: ResourceViewMode,
): ResourceValueState {
  if (mode !== "all") return mode;
  const presence = node.configPresence ?? {};
  if (presence.pending ?? true) return "pending";
  if (presence.submitted ?? presence.deployed ?? true) return "submitted";
  return "deployed";
}


function comparisonValue(
  node: ManageNode,
  path: string,
  state: ResourceValueState,
): string | null | undefined {
  const comparison = node.comparisons?.find(
    (candidate) => candidate.path === path,
  );
  if (!comparison) return undefined;
  const valueState = comparison[state];
  return valueState.present && typeof valueState.value === "string"
    ? valueState.value
    : null;
}


function detailValue(node: ManageNode, label: string): string | undefined {
  const detail = node.details?.find(
    (candidate) => candidate.kind === "spec" && candidate.label === label,
  );
  return typeof detail?.value === "string" ? detail.value : undefined;
}


function clusterNameForNode(
  snapshot: ManageSnapshot,
  node: ManageNode,
): string | undefined {
  const parent = node.parentId ? snapshot.nodes[node.parentId] : undefined;
  const cluster = parent?.parentId
    ? snapshot.nodes[parent.parentId]
    : undefined;
  return (
    cluster?.kind === "resource"
    && ["kafkaclusters", "kafkaconfigs"].includes(
      cluster.resourcePlural ?? "",
    )
  )
    ? cluster.resourceName ?? cluster.label
    : undefined;
}


function topicValue(
  snapshot: ManageSnapshot,
  node: ManageNode,
  path: "kafkaClusterName" | "topicName",
  state: ResourceValueState,
): string | undefined {
  const compared = comparisonValue(node, path, state);
  if (compared !== undefined) return compared ?? undefined;
  if (path === "topicName") {
    return node.label || detailValue(node, path);
  }
  return clusterNameForNode(snapshot, node) ?? detailValue(node, path);
}


function emptyTopicGroup(
  id: string,
  clusterId: string,
  revision: string,
): ManageNode {
  return {
    id,
    revision: `${revision}:${id}`,
    parentId: clusterId,
    childIds: [],
    kind: "group",
    label: "Topics",
    description: null,
    status: "ok",
    phase: null,
    valueSummary: null,
    activityAt: null,
    diagnostics: [],
    capabilities: [],
    details: [],
    relationships: [],
    comparisons: [],
    resourcePlural: null,
    resourceName: null,
    resourceType: null,
    configPresence: {},
    configState: null,
    navigationKey: [],
  };
}


function projectKafkaTopicNavigation(
  snapshot: ManageSnapshot,
  mode: ResourceViewMode,
): ManageSnapshot {
  const nodes = Object.fromEntries(
    Object.entries(snapshot.nodes).map(([nodeId, node]) => [
      nodeId,
      { ...node, childIds: [...(node.childIds ?? [])] },
    ]),
  ) as ManageSnapshot["nodes"];
  const topicNodes = Object.values(nodes).filter(
    (node) => (
      node.kind === "resource"
      && node.resourcePlural === "capturedtraffics"
      && node.resourceType === "Kafka topic"
    ),
  );

  topicNodes.forEach((node) => {
    const state = navigationState(node, mode);
    const clusterName = topicValue(snapshot, node, "kafkaClusterName", state);
    const topicName = topicValue(snapshot, node, "topicName", state);
    if (!clusterName || !topicName) return;
    const cluster = (
      nodes[`resource:kafkaclusters:${clusterName}`]
      ?? nodes[`resource:kafkaconfigs:${clusterName}`]
    );
    if (!cluster) return;

    if (node.parentId && nodes[node.parentId]) {
      nodes[node.parentId] = {
        ...nodes[node.parentId],
        childIds: (nodes[node.parentId].childIds ?? []).filter(
          (childId) => childId !== node.id,
        ),
      };
    }
    const topicTarget = `edit:traffic.kafkaClusters.${clusterName}.topics`;
    const groupId = `definition-group:${topicTarget}`;
    const group = nodes[groupId]
      ?? emptyTopicGroup(groupId, cluster.id, snapshot.revision);
    nodes[groupId] = {
      ...group,
      parentId: cluster.id,
      childIds: [...new Set([...(group.childIds ?? []), node.id])],
    };
    nodes[cluster.id] = {
      ...cluster,
      childIds: [...new Set([...(cluster.childIds ?? []), groupId])],
    };
    nodes[node.id] = {
      ...node,
      revision: `${node.revision}:navigation:${state}:${clusterName}:${topicName}`,
      parentId: groupId,
      label: topicName,
    };
  });

  Object.values(nodes)
    .filter((node) => (
      node.kind === "group"
      && node.id.startsWith(
        "definition-group:edit:traffic.kafkaClusters.",
      )
      && node.id.endsWith(".topics")
      && (node.childIds ?? []).length === 0
    ))
    .forEach((group) => {
      if (group.parentId && nodes[group.parentId]) {
        nodes[group.parentId] = {
          ...nodes[group.parentId],
          childIds: (nodes[group.parentId].childIds ?? []).filter(
            (childId) => childId !== group.id,
          ),
        };
      }
      delete nodes[group.id];
    });

  return { ...snapshot, nodes };
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
  const navigated = projectKafkaTopicNavigation(snapshot, mode);
  if (mode === "all") return navigated;
  const included = new Set<string>();
  Object.values(navigated.nodes).forEach((node) => {
    if (node.kind !== "resource" || !resourceVisible(node, mode)) return;
    included.add(node.id);
    includeAncestors(navigated, node, included);
    includeDescendants(navigated, node.id, included);
  });
  const nodes = Object.fromEntries(
    [...included].flatMap((nodeId) => {
      const node = navigated.nodes[nodeId];
      if (!node) return [];
      return [[nodeId, {
        ...node,
        childIds: node.childIds.filter((childId) => included.has(childId)),
      }]];
    }),
  );
  return {
    ...navigated,
    nodes,
    rootIds: navigated.rootIds.filter((rootId) => included.has(rootId)),
  };
}


export function projectResourceNavigation(
  snapshot: ManageSnapshot,
): ManageSnapshot {
  const workflowStepIds = new Set(
    Object.values(snapshot.nodes)
      .filter((node) => node.kind === "workflow-step")
      .map((node) => node.id),
  );
  if (workflowStepIds.size === 0) return snapshot;
  return {
    ...snapshot,
    nodes: Object.fromEntries(
      Object.entries(snapshot.nodes).flatMap(([nodeId, node]) => (
        workflowStepIds.has(nodeId)
          ? []
          : [[nodeId, {
            ...node,
            childIds: node.childIds.filter(
              (childId) => !workflowStepIds.has(childId),
            ),
          }]]
      )),
    ),
    rootIds: snapshot.rootIds.filter(
      (rootId) => !workflowStepIds.has(rootId),
    ),
  };
}
