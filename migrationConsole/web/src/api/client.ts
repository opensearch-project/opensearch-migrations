import createClient from "openapi-fetch";

import type { components, paths } from "./schema.generated";


export type ManageSnapshot = components["schemas"]["ManageSnapshotV1"];
export type ManageNode = components["schemas"]["ManageNodeV1"];
export type ConfigDraft = components["schemas"]["ConfigDraftV1"];
export type EditNode = components["schemas"]["EditNodeV1"];
export type EditOperation =
  components["schemas"]["ApplyEditOperationRequestV1"]["operation"];
export type ExternalResourceInventory =
  components["schemas"]["ExternalResourceInventoryV1"];
export type ExternalResourceRow =
  components["schemas"]["ExternalResourceRowV1"];
export type ExternalResourceDetails =
  components["schemas"]["ExternalResourceDetailsV1"];
export type ExternalResourceMutation =
  components["schemas"]["ExternalResourceMutationV1"];


const client = createClient<paths>({
  baseUrl: window.location.origin,
  fetch: (...args) => globalThis.fetch(...args),
});


interface ApiErrorDetail {
  code?: string;
  message?: string;
  current?: ConfigDraft;
  persistedRevision?: string;
}


export class ConfigApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly current?: ConfigDraft;

  constructor(status: number, fallback: string, error: unknown) {
    const body = error as { detail?: ApiErrorDetail | string } | undefined;
    const detail = body?.detail;
    let message = fallback;
    if (typeof detail === "string") {
      if (detail !== "Not Found") message = detail;
    } else if (detail?.message) {
      message = detail.message;
    }
    super(message);
    this.name = "ConfigApiError";
    this.status = status;
    this.code = typeof detail === "object" ? detail.code : undefined;
    this.current = typeof detail === "object" ? detail.current : undefined;
  }
}


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


export async function getConfigDraft(): Promise<ConfigDraft> {
  const { data, error, response } = await client.GET("/api/v1/config");
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      response.status === 404
        ? "This Workflow Manage server does not provide configuration editing. Restart it with the current web application."
        : "Configuration is unavailable",
      error,
    );
  }
  return data;
}


export async function applyEditOperation(
  draftRevision: string,
  operation: EditOperation,
): Promise<ConfigDraft> {
  const { data, error, response } = await client.POST(
    "/api/v1/config/operations",
    {
      body: {
        expectedDraftRevision: draftRevision,
        operation,
      },
    },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "The configuration change could not be applied",
      error,
    );
  }
  return data;
}


export async function saveConfigDraft(
  draftRevision: string,
): Promise<ConfigDraft> {
  const { data, error, response } = await client.POST(
    "/api/v1/config/save",
    { body: { expectedDraftRevision: draftRevision } },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "The configuration could not be saved",
      error,
    );
  }
  return data;
}


export async function discardConfigDraft(
  draftRevision: string,
): Promise<ConfigDraft> {
  const { data, error, response } = await client.POST(
    "/api/v1/config/discard",
    { body: { expectedDraftRevision: draftRevision } },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "The configuration draft could not be discarded",
      error,
    );
  }
  return data;
}


export async function getExternalResources(
  nodeId: string,
  draftRevision: string,
): Promise<ExternalResourceInventory> {
  const { data, error, response } = await client.GET(
    "/api/v1/external-resources",
    {
      params: {
        query: {
          nodeId,
          expectedDraftRevision: draftRevision,
        },
      },
    },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "External resources could not be listed",
      error,
    );
  }
  return data;
}


export interface ExternalResourceSelection {
  nodeId: string;
  name: string;
  kind: string;
  group: string;
  key?: string | null;
  acceptWarning?: boolean;
  manual?: boolean;
}


export async function selectExternalResource(
  draftRevision: string,
  selection: ExternalResourceSelection,
): Promise<ConfigDraft> {
  const { data, error, response } = await client.POST(
    "/api/v1/external-resources/select",
    {
      body: {
        expectedDraftRevision: draftRevision,
        nodeId: selection.nodeId,
        name: selection.name,
        kind: selection.kind,
        group: selection.group,
        key: selection.key ?? null,
        acceptWarning: selection.acceptWarning ?? false,
        manual: selection.manual ?? false,
      },
    },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "The external resource could not be selected",
      error,
    );
  }
  return data;
}


export async function getExternalResourceDetails(
  nodeId: string,
  draftRevision: string,
  name: string,
): Promise<ExternalResourceDetails> {
  const { data, error, response } = await client.GET(
    "/api/v1/external-resources/details",
    {
      params: {
        query: {
          nodeId,
          expectedDraftRevision: draftRevision,
          name,
        },
      },
    },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "The external resource could not be read",
      error,
    );
  }
  return data;
}


export async function saveExternalResource(
  draftRevision: string,
  nodeId: string,
  values: Record<string, string>,
  confirmations: Record<string, string>,
  existingName?: string,
): Promise<ExternalResourceMutation> {
  const { data, error, response } = await client.POST(
    "/api/v1/external-resources/save",
    {
      body: {
        expectedDraftRevision: draftRevision,
        nodeId,
        values,
        confirmations,
        existingName: existingName ?? null,
      },
    },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "The external resource could not be saved",
      error,
    );
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
