export const DEFAULT_DOCK_HEIGHT = 220;
export const MIN_DOCK_HEIGHT = 130;
export const MAX_DOCK_HEIGHT = 720;


export function boundedDockHeight(value: number): number {
  return Math.max(
    MIN_DOCK_HEIGHT,
    Math.min(MAX_DOCK_HEIGHT, Math.round(value)),
  );
}


export function moveDockItem<T extends { id: string }>(
  items: T[],
  sourceId: string,
  targetId: string,
): T[] {
  const sourceIndex = items.findIndex((item) => item.id === sourceId);
  const targetIndex = items.findIndex((item) => item.id === targetId);
  if (
    sourceIndex < 0
    || targetIndex < 0
    || sourceIndex === targetIndex
  ) {
    return items;
  }
  const reordered = [...items];
  const [moved] = reordered.splice(sourceIndex, 1);
  reordered.splice(targetIndex, 0, moved);
  return reordered;
}


export function moveDockItemBy<T extends { id: string }>(
  items: T[],
  itemId: string,
  offset: -1 | 1,
): T[] {
  const sourceIndex = items.findIndex((item) => item.id === itemId);
  const targetIndex = sourceIndex + offset;
  if (
    sourceIndex < 0
    || targetIndex < 0
    || targetIndex >= items.length
  ) {
    return items;
  }
  const reordered = [...items];
  [reordered[sourceIndex], reordered[targetIndex]] = [
    reordered[targetIndex],
    reordered[sourceIndex],
  ];
  return reordered;
}
