import type {
  ManageSnapshot,
  Operation,
} from "../../api/client";


const ACTIVE_STATUSES = new Set(["queued", "running", "waiting"]);


export function activeResetTargetIds(
  operations: Operation[] | undefined,
): Set<string> {
  return new Set(
    (operations ?? [])
      .filter((operation) => (
        operation.kind === "reset"
        && ACTIVE_STATUSES.has(operation.status)
      ))
      .flatMap((operation) => operation.targetIds),
  );
}


export function presentActiveResets(
  snapshot: ManageSnapshot | undefined,
  targetIds: ReadonlySet<string>,
): ManageSnapshot | undefined {
  if (!snapshot || targetIds.size === 0) return snapshot;
  const nodes = { ...snapshot.nodes };
  let changed = false;
  targetIds.forEach((targetId) => {
    const node = nodes[targetId];
    if (!node) return;
    nodes[targetId] = {
      ...node,
      status: "syncing",
      valueSummary: "Removing",
    };
    changed = true;
  });
  return changed ? { ...snapshot, nodes } : snapshot;
}
