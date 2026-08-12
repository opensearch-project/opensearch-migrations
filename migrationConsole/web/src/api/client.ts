import createClient from "openapi-fetch";

import type { components, paths } from "./schema.generated";


export type ManageSnapshot = components["schemas"]["ManageSnapshotV1"];
export type ManageNode = components["schemas"]["ManageNodeV1"];


const client = createClient<paths>({
  baseUrl: window.location.origin,
  fetch: (...args) => globalThis.fetch(...args),
});


export async function getHealth() {
  const { data, error, response } = await client.GET(
    "/api/v1/system/health",
  );
  if (!response.ok || error || !data) {
    throw new Error("Workflow Manage server is unavailable");
  }
  return data;
}


export async function getManageState(): Promise<ManageSnapshot> {
  const { data, error, response } = await client.GET(
    "/api/v1/manage/state",
  );
  if (!response.ok || error || !data) {
    throw new Error("Workflow state is unavailable");
  }
  return data;
}


export function reconcileManageState(
  previous: ManageSnapshot | undefined,
  incoming: ManageSnapshot,
): ManageSnapshot {
  if (!previous) {
    return incoming;
  }
  const nodes = Object.fromEntries(
    Object.entries(incoming.nodes).map(([nodeId, node]) => {
      const existing = previous.nodes[nodeId];
      return [
        nodeId,
        existing?.revision === node.revision ? existing : node,
      ];
    }),
  );
  return { ...incoming, nodes };
}
