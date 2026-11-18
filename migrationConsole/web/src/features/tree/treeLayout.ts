export interface TreeLayoutOffset {
  x: number;
  y: number;
}


export function resolveTreeLayoutOffset(
  nodeId: string,
  parentIds: ReadonlyMap<string, string | null>,
  directOffsets: ReadonlyMap<string, TreeLayoutOffset>,
  resolved = new Map<string, TreeLayoutOffset>(),
  resolving = new Set<string>(),
): TreeLayoutOffset {
  const cached = resolved.get(nodeId);
  if (cached) return cached;
  const direct = directOffsets.get(nodeId);
  if (direct) {
    resolved.set(nodeId, direct);
    return direct;
  }
  const parentId = parentIds.get(nodeId);
  if (!parentId || resolving.has(nodeId)) {
    const stationary = { x: 0, y: 0 };
    resolved.set(nodeId, stationary);
    return stationary;
  }
  resolving.add(nodeId);
  const parentOffset = resolveTreeLayoutOffset(
    parentId,
    parentIds,
    directOffsets,
    resolved,
    resolving,
  );
  resolving.delete(nodeId);
  resolved.set(nodeId, parentOffset);
  return parentOffset;
}
