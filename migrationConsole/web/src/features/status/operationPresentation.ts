import type {
  ManageSnapshot,
  Operation,
} from "../../api/client";


const ACTIVE_STATUSES = new Set(["queued", "running", "waiting"]);


function replacementFor(match: string, replacement: string): string {
  return /^[A-Z]/.test(match)
    ? replacement[0].toUpperCase() + replacement.slice(1)
    : replacement;
}


export function presentResourceActionText(text: string): string {
  return text
    .replaceAll(
      /\breset\s*(?:&|and)\s*resubmit\b/gi,
      (match) => replacementFor(match, "delete resource and resubmit"),
    )
    .replaceAll(
      /\breset and retry\b/gi,
      (match) => replacementFor(match, "delete resources and retry"),
    )
    .replaceAll(
      /\breset-required\b/gi,
      (match) => replacementFor(match, "resource-deletion-required"),
    )
    .replaceAll(
      /\breset required\b/gi,
      (match) => replacementFor(match, "resource deletion required"),
    )
    .replaceAll(
      /\breset before approval\b/gi,
      (match) => replacementFor(match, "delete resource before approval"),
    )
    .replaceAll(
      /\breset operation\b/gi,
      (match) => replacementFor(match, "resource deletion operation"),
    )
    .replaceAll(
      /\breset plan\b/gi,
      (match) => replacementFor(match, "resource deletion plan"),
    )
    .replaceAll(
      /\breset ([\w./-]+) to delete and recreate it\b/gi,
      (match, name: string) => replacementFor(
        match,
        `delete resource ${name} so it can be recreated`,
      ),
    )
    .replaceAll(
      /\breset ([\w./-]+) before\b/gi,
      (match, name: string) => replacementFor(
        match,
        `delete resource ${name} before`,
      ),
    )
    .replaceAll(
      /\breset the resource\b/gi,
      (match) => replacementFor(match, "delete the resource"),
    )
    .replaceAll(
      /\breset it\b/gi,
      (match) => replacementFor(match, "delete it"),
    )
    .replace(
      /^reset ([\w./-]+)$/i,
      (_match, name: string) => `Delete resource ${name}`,
    );
}


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
      valueSummary: "Deleting",
    };
    changed = true;
  });
  return changed ? { ...snapshot, nodes } : snapshot;
}
