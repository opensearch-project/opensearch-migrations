import type { ManageNode } from "../../api/client";


const RUNTIME_STATUS_PLURALS = new Set([
  "datasnapshots",
  "snapshotmigrations",
  "kafkaclusters",
  "capturedtraffics",
  "captureproxies",
  "trafficreplays",
]);


export function runtimeStatusSupported(node?: ManageNode | null): boolean {
  return Boolean(
    node?.resourcePlural
    && RUNTIME_STATUS_PLURALS.has(node.resourcePlural),
  );
}
