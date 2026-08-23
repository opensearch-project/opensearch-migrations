import type {
  ManageNode,
  ManageRelationship,
  ManageSnapshot,
} from "../../api/client";


export interface WorkflowGraphNode {
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
    node: null,
    label: relationship.targetName,
    resourcePlural: relationship.targetPlural ?? null,
    phase: relationship.targetPhase ?? null,
    status: relationship.targetStatus,
    steps: [],
    unresolved: true,
  };
}


export function buildWorkflowGraph(snapshot: ManageSnapshot): WorkflowGraph {
  const order = resourceOrder(snapshot);
  const resources = Object.values(snapshot.nodes)
    .filter((node) => node.kind === "resource")
    .sort((left, right) => (
      (order.get(left.id) ?? Number.MAX_SAFE_INTEGER)
      - (order.get(right.id) ?? Number.MAX_SAFE_INTEGER)
    ))
    .map((node): WorkflowGraphNode => ({
      id: node.id,
      node,
      label: node.label,
      resourcePlural: node.resourcePlural,
      phase: node.phase,
      status: node.status,
      steps: workflowStepsFor(snapshot, node),
      unresolved: false,
    }));
  const nodes = new Map(resources.map((node) => [node.id, node]));
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

  const incoming = new Map<string, number>();
  const outgoing = new Map<string, WorkflowGraphEdge[]>();
  const depths = new Map<string, number>();
  for (const nodeId of nodes.keys()) {
    incoming.set(nodeId, 0);
    outgoing.set(nodeId, []);
  }
  for (const edge of edges.values()) {
    incoming.set(edge.targetId, (incoming.get(edge.targetId) ?? 0) + 1);
    outgoing.get(edge.sourceId)?.push(edge);
  }

  const queue = [...nodes.keys()].filter((nodeId) => incoming.get(nodeId) === 0);
  queue.forEach((nodeId) => depths.set(nodeId, 0));
  for (let index = 0; index < queue.length; index += 1) {
    const nodeId = queue[index];
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
  for (const nodeId of nodes.keys()) {
    if (!depths.has(nodeId)) depths.set(nodeId, maxDepth + 1);
  }

  const levels: WorkflowGraphNode[][] = [];
  for (const node of nodes.values()) {
    const depth = depths.get(node.id) ?? 0;
    if (!levels[depth]) levels[depth] = [];
    levels[depth].push(node);
  }
  for (const level of levels) {
    level.sort((left, right) => {
      if (left.unresolved !== right.unresolved) return left.unresolved ? -1 : 1;
      return (
        (order.get(left.id) ?? Number.MAX_SAFE_INTEGER)
        - (order.get(right.id) ?? Number.MAX_SAFE_INTEGER)
      );
    });
  }

  return {
    levels: levels.filter(Boolean),
    edges: [...edges.values()],
  };
}
