import type { WorkflowGraphEdge } from "./workflowGraph";


export interface DependencyEdgeSpan extends WorkflowGraphEdge {
  sourceY: number;
  targetDepth: number;
  targetY: number;
}


export interface DependencyRoute {
  sourceId: string;
  targetIds: string[];
  startY: number;
  endY: number;
  depth: number;
}


export interface ConnectedPath {
  edgeIds: Set<string>;
  nodeIds: Set<string>;
}


export function edgeId(edge: WorkflowGraphEdge): string {
  return `${edge.sourceId}\n${edge.targetId}`;
}


export function dependencyAnchorY(
  top: number,
  height: number,
  direction: "incoming" | "outgoing",
): number {
  return top + height * (direction === "incoming" ? 1 / 3 : 2 / 3);
}


export function groupDependencyRoutes(
  edges: DependencyEdgeSpan[],
): DependencyRoute[] {
  const grouped = new Map<string, DependencyEdgeSpan[]>();
  for (const edge of edges) {
    const key = `${edge.sourceId}\n${edge.targetDepth}`;
    const group = grouped.get(key) ?? [];
    group.push(edge);
    grouped.set(key, group);
  }
  const routes = [...grouped.values()].map((sourceEdges) => {
    const orderedEdges = [...sourceEdges].sort(
      (left, right) => left.targetY - right.targetY,
    );
    return {
      sourceId: sourceEdges[0].sourceId,
      targetIds: orderedEdges.map((edge) => edge.targetId),
      startY: Math.min(...sourceEdges.map((edge) => edge.sourceY)),
      endY: Math.max(...sourceEdges.map((edge) => edge.targetY)),
      depth: sourceEdges[0].targetDepth,
    };
  });
  return routes.sort((left, right) => (
    left.depth - right.depth
    || left.startY - right.startY
    || right.endY - left.endY
  ));
}


export function connectedPath(
  edges: WorkflowGraphEdge[],
  selectedNodeId: string | null,
): ConnectedPath {
  if (!selectedNodeId) {
    return { edgeIds: new Set(), nodeIds: new Set() };
  }
  const outgoing = new Map<string, WorkflowGraphEdge[]>();
  const incoming = new Map<string, WorkflowGraphEdge[]>();
  for (const edge of edges) {
    const sourceEdges = outgoing.get(edge.sourceId) ?? [];
    sourceEdges.push(edge);
    outgoing.set(edge.sourceId, sourceEdges);
    const targetEdges = incoming.get(edge.targetId) ?? [];
    targetEdges.push(edge);
    incoming.set(edge.targetId, targetEdges);
  }

  const nodeIds = new Set([selectedNodeId]);
  const edgeIds = new Set<string>();
  const visit = (
    start: string,
    adjacency: Map<string, WorkflowGraphEdge[]>,
    next: (edge: WorkflowGraphEdge) => string,
  ) => {
    const queue = [start];
    for (const queuedNodeId of queue) {
      for (const edge of adjacency.get(queuedNodeId) ?? []) {
        edgeIds.add(edgeId(edge));
        const nodeId = next(edge);
        if (nodeIds.has(nodeId)) continue;
        nodeIds.add(nodeId);
        queue.push(nodeId);
      }
    }
  };
  visit(selectedNodeId, incoming, (edge) => edge.sourceId);
  visit(selectedNodeId, outgoing, (edge) => edge.targetId);
  return { edgeIds, nodeIds };
}
