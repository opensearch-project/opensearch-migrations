import type {
  ManageNode,
  ManageRelationship,
  ManageSnapshot,
} from "../../api/client";


export interface WorkflowGraphNode {
  activityAt: string | null;
  depth: number;
  id: string;
  node: ManageNode | null;
  label: string;
  resourcePlural: string | null;
  phase: string | null;
  status: string;
  steps: WorkflowGraphStep[];
  unresolved: boolean;
}


export interface WorkflowGraphStep {
  activityAt: string | null;
  depth: number;
  id: string;
  label: string;
  node: ManageNode;
  phase: string | null;
  status: string;
}


export interface WorkflowGraphEdge {
  sourceId: string;
  targetId: string;
}


export interface WorkflowGraph {
  levels: WorkflowGraphNode[][];
  nodes: WorkflowGraphNode[];
  edges: WorkflowGraphEdge[];
}


function resourceOrder(snapshot: ManageSnapshot): Map<string, number> {
  const order = new Map<string, number>();
  const visited = new Set<string>();
  const visit = (nodeId: string) => {
    if (visited.has(nodeId)) return;
    visited.add(nodeId);
    const node = snapshot.nodes[nodeId];
    if (!node) return;
    if (node.kind === "resource") order.set(node.id, order.size);
    node.childIds.forEach(visit);
  };
  snapshot.rootIds.forEach(visit);
  Object.values(snapshot.nodes).forEach((node) => visit(node.id));
  return order;
}


function unresolvedId(relationship: ManageRelationship): string {
  return [
    "unresolved",
    relationship.targetPlural ?? "resource",
    relationship.targetName,
  ].join(":");
}


function workflowStepsFor(
  snapshot: ManageSnapshot,
  resource: ManageNode,
): WorkflowGraphStep[] {
  const steps: WorkflowGraphStep[] = [];
  const visited = new Set<string>();
  const visit = (nodeId: string, depth: number) => {
    if (visited.has(nodeId)) return;
    visited.add(nodeId);
    const node = snapshot.nodes[nodeId];
    if (!node || node.kind !== "workflow-step") return;
    steps.push({
      activityAt: node.activityAt,
      depth,
      id: node.id,
      label: node.label,
      node,
      phase: node.phase,
      status: node.status,
    });
    node.childIds.forEach((childId) => visit(childId, depth + 1));
  };
  resource.childIds.forEach((childId) => visit(childId, 0));
  return steps;
}


function relationshipTarget(
  relationship: ManageRelationship,
  resources: WorkflowGraphNode[],
): WorkflowGraphNode {
  const target = resources.find((candidate) => (
    candidate.id === relationship.targetId
  )) ?? resources.find((candidate) => (
    candidate.label === relationship.targetName
    && (
      !relationship.targetPlural
      || candidate.resourcePlural === relationship.targetPlural
    )
  ));
  if (target) return target;
  return {
    id: relationship.targetId ?? unresolvedId(relationship),
    activityAt: null,
    depth: 0,
    node: null,
    label: relationship.targetName,
    resourcePlural: relationship.targetPlural ?? null,
    phase: relationship.targetPhase ?? null,
    status: relationship.targetStatus,
    steps: [],
    unresolved: true,
  };
}


interface GraphTopology {
  incoming: Map<string, number>;
  outgoing: Map<string, WorkflowGraphEdge[]>;
  prerequisites: Map<string, string[]>;
}


function graphResources(
  snapshot: ManageSnapshot,
  order: Map<string, number>,
): WorkflowGraphNode[] {
  return Object.values(snapshot.nodes)
    .filter((node) => node.kind === "resource")
    .sort((left, right) => (
      (order.get(left.id) ?? Number.MAX_SAFE_INTEGER)
      - (order.get(right.id) ?? Number.MAX_SAFE_INTEGER)
    ))
    .map((node): WorkflowGraphNode => ({
      activityAt: node.activityAt,
      depth: 0,
      id: node.id,
      node,
      label: node.label,
      resourcePlural: node.resourcePlural,
      phase: node.phase,
      status: node.status,
      steps: workflowStepsFor(snapshot, node),
      unresolved: false,
    }));
}


function graphEdges(
  resources: WorkflowGraphNode[],
  nodes: Map<string, WorkflowGraphNode>,
): Map<string, WorkflowGraphEdge> {
  const edges = new Map<string, WorkflowGraphEdge>();
  for (const resource of resources) {
    const requirements = (resource.node?.relationships ?? []).filter(
      (relationship) => relationship.direction === "requires",
    );
    for (const requirement of requirements) {
      const prerequisite = relationshipTarget(requirement, resources);
      if (!nodes.has(prerequisite.id)) nodes.set(prerequisite.id, prerequisite);
      const key = `${prerequisite.id}\n${resource.id}`;
      edges.set(key, {
        sourceId: prerequisite.id,
        targetId: resource.id,
      });
    }
  }
  return edges;
}


function graphTopology(
  nodes: Map<string, WorkflowGraphNode>,
  edges: Map<string, WorkflowGraphEdge>,
): GraphTopology {
  const incoming = new Map<string, number>();
  const prerequisites = new Map<string, string[]>();
  const outgoing = new Map<string, WorkflowGraphEdge[]>();
  for (const nodeId of nodes.keys()) {
    incoming.set(nodeId, 0);
    prerequisites.set(nodeId, []);
    outgoing.set(nodeId, []);
  }
  for (const edge of edges.values()) {
    incoming.set(edge.targetId, (incoming.get(edge.targetId) ?? 0) + 1);
    prerequisites.get(edge.targetId)?.push(edge.sourceId);
    outgoing.get(edge.sourceId)?.push(edge);
  }
  return { incoming, outgoing, prerequisites };
}


function assignDepths(
  nodes: Map<string, WorkflowGraphNode>,
  incoming: Map<string, number>,
  outgoing: Map<string, WorkflowGraphEdge[]>,
) {
  const depths = new Map<string, number>();
  const queue = [...nodes.keys()].filter((nodeId) => incoming.get(nodeId) === 0);
  queue.forEach((nodeId) => depths.set(nodeId, 0));
  for (const nodeId of queue) {
    const depth = depths.get(nodeId) ?? 0;
    for (const edge of outgoing.get(nodeId) ?? []) {
      depths.set(
        edge.targetId,
        Math.max(depths.get(edge.targetId) ?? 0, depth + 1),
      );
      const nextIncoming = (incoming.get(edge.targetId) ?? 1) - 1;
      incoming.set(edge.targetId, nextIncoming);
      if (nextIncoming === 0) queue.push(edge.targetId);
    }
  }

  // Runtime dependencies should be acyclic. Keep malformed cycles visible
  // instead of dropping them from the operator's graph.
  const maxDepth = Math.max(0, ...depths.values());
  for (const node of nodes.values()) {
    if (!depths.has(node.id)) depths.set(node.id, maxDepth + 1);
    node.depth = depths.get(node.id) ?? 0;
  }
}


function compareKeys(left: number[], right: number[]) {
  const length = Math.max(left.length, right.length);
  for (let index = 0; index < length; index += 1) {
    const difference = (
      (left[index] ?? Number.MIN_SAFE_INTEGER)
      - (right[index] ?? Number.MIN_SAFE_INTEGER)
    );
    if (difference !== 0) return difference;
  }
  return 0;
}


function releaseReadyChild(
  edge: WorkflowGraphEdge,
  nestedIncoming: Map<string, number>,
  nodes: Map<string, WorkflowGraphNode>,
): WorkflowGraphNode | null {
  const remaining = (nestedIncoming.get(edge.targetId) ?? 1) - 1;
  nestedIncoming.set(edge.targetId, remaining);
  return remaining === 0 ? nodes.get(edge.targetId) ?? null : null;
}


function firstParentLayoutKey(
  childId: string,
  prerequisites: Map<string, string[]>,
  layoutKeys: Map<string, number[]>,
  fallback: number[],
): number[] {
  const parentKeys = (prerequisites.get(childId) ?? [])
    .flatMap((nodeId) => {
      const key = layoutKeys.get(nodeId);
      return key ? [key] : [];
    })
    .sort(compareKeys);
  return parentKeys[0] ?? fallback;
}


function orderedGraphNodes(
  nodes: Map<string, WorkflowGraphNode>,
  edges: Map<string, WorkflowGraphEdge>,
  outgoing: Map<string, WorkflowGraphEdge[]>,
  prerequisites: Map<string, string[]>,
  order: Map<string, number>,
): WorkflowGraphNode[] {
  const insertionOrder = new Map(
    [...nodes.keys()].map((nodeId, index) => [nodeId, index]),
  );
  const fallbackOrder = (node: WorkflowGraphNode): number => (
    node.unresolved
      ? -nodes.size + (insertionOrder.get(node.id) ?? 0)
      : order.get(node.id) ?? Number.MAX_SAFE_INTEGER
  );
  const nestedIncoming = new Map<string, number>();
  for (const nodeId of nodes.keys()) nestedIncoming.set(nodeId, 0);
  for (const edge of edges.values()) {
    nestedIncoming.set(
      edge.targetId,
      (nestedIncoming.get(edge.targetId) ?? 0) + 1,
    );
  }
  const layoutKeys = new Map<string, number[]>();
  const roots = [...nodes.values()]
    .filter((node) => nestedIncoming.get(node.id) === 0)
    .sort((left, right) => fallbackOrder(left) - fallbackOrder(right));
  roots.forEach((node, index) => layoutKeys.set(node.id, [index]));
  const available = [...roots];
  const nestedNodes: WorkflowGraphNode[] = [];
  const nestedNodeIds = new Set<string>();
  while (available.length > 0) {
    available.sort((left, right) => (
      compareKeys(
        layoutKeys.get(left.id) ?? [fallbackOrder(left)],
        layoutKeys.get(right.id) ?? [fallbackOrder(right)],
      )
      || fallbackOrder(left) - fallbackOrder(right)
    ));
    const node = available.shift();
    if (!node || nestedNodeIds.has(node.id)) continue;
    nestedNodeIds.add(node.id);
    nestedNodes.push(node);
    for (const edge of outgoing.get(node.id) ?? []) {
      const child = releaseReadyChild(edge, nestedIncoming, nodes);
      if (!child) continue;
      layoutKeys.set(child.id, [
        ...firstParentLayoutKey(
          child.id,
          prerequisites,
          layoutKeys,
          [roots.length],
        ),
        fallbackOrder(child),
      ]);
      available.push(child);
    }
  }

  // Keep malformed cycles visible after the acyclic workflow resources.
  const cyclicNodes = [...nodes.values()]
    .filter((node) => !nestedNodeIds.has(node.id))
    .sort((left, right) => fallbackOrder(left) - fallbackOrder(right));
  nestedNodes.push(...cyclicNodes);
  return nestedNodes;
}


function graphLevels(nodes: WorkflowGraphNode[]): WorkflowGraphNode[][] {
  const levels: WorkflowGraphNode[][] = [];
  for (const node of nodes) {
    if (!levels[node.depth]) levels[node.depth] = [];
    levels[node.depth].push(node);
  }
  return levels.filter(Boolean);
}


export function buildWorkflowGraph(snapshot: ManageSnapshot): WorkflowGraph {
  const order = resourceOrder(snapshot);
  const resources = graphResources(snapshot, order);
  const nodes = new Map(resources.map((node) => [node.id, node]));
  const edges = graphEdges(resources, nodes);
  const topology = graphTopology(nodes, edges);
  assignDepths(nodes, topology.incoming, topology.outgoing);
  const nestedNodes = orderedGraphNodes(
    nodes,
    edges,
    topology.outgoing,
    topology.prerequisites,
    order,
  );

  return {
    levels: graphLevels(nestedNodes),
    nodes: nestedNodes,
    edges: [...edges.values()],
  };
}
