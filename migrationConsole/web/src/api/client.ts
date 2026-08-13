import createClient from "openapi-fetch";

import type { components, paths } from "./schema.generated";


export type ManageSnapshot = components["schemas"]["ManageSnapshotV1"];
export type ManageNode = components["schemas"]["ManageNodeV1"];
export type ConfigDraft = components["schemas"]["ConfigDraftV1"];
export type ConfigRemovalImpact =
  components["schemas"]["ConfigRemovalImpactV1"];
export type ConfigSubmission = components["schemas"]["ConfigSubmissionV1"];
export type ConfigReview = components["schemas"]["ConfigReviewV1"];
export type Operation = components["schemas"]["OperationV1"];
export type ApprovalReview = components["schemas"]["ApprovalReviewV1"];
export type ResetPlan = components["schemas"]["ResetPlanV1"];
export type OutputInventory = components["schemas"]["OutputInventoryV1"];
export type OutputDescriptor = components["schemas"]["OutputDescriptorV1"];
export type OutputContent = components["schemas"]["OutputContentV1"];
export type LogTargetInventory =
  components["schemas"]["LogTargetInventoryV1"];
export type LogTarget = components["schemas"]["LogTargetV1"];
export type LogStream = components["schemas"]["LogStreamV1"];
export type LogStreamStatus =
  components["schemas"]["LogStreamStatusV1"];
export type LogPage = components["schemas"]["LogPageV1"];
export type LogEvent = components["schemas"]["LogEventV1"];
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


export async function getConfigRemovalImpact(
  draftRevision: string,
  path: string[],
): Promise<ConfigRemovalImpact> {
  const { data, error, response } = await client.POST(
    "/api/v1/config/removal-impact",
    {
      body: {
        expectedDraftRevision: draftRevision,
        path,
      },
    },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "The effects of this removal could not be determined",
      error,
    );
  }
  return data;
}


export async function submitConfigDraft(
  draftRevision: string,
): Promise<Operation> {
  const { data, error, response } = await client.POST(
    "/api/v1/config/submit",
    { body: { expectedDraftRevision: draftRevision } },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "The workflow configuration could not be submitted",
      error,
    );
  }
  return data;
}


export async function getConfigReview(
  draftRevision: string,
): Promise<ConfigReview> {
  const { data, error, response } = await client.POST(
    "/api/v1/config/review",
    { body: { expectedDraftRevision: draftRevision } },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "The pending changes could not be reviewed",
      error,
    );
  }
  return data;
}


export async function getOperations(): Promise<Operation[]> {
  const { data, error, response } = await client.GET(
    "/api/v1/operations",
  );
  if (!response.ok || error || !data) {
    throw new Error("Recent operations are unavailable");
  }
  return data.operations;
}


export async function getApprovalReview(
  targetId: string,
): Promise<ApprovalReview> {
  const { data, error, response } = await client.GET(
    "/api/v1/approvals/review",
    { params: { query: { targetId } } },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Approval details are unavailable",
      error,
    );
  }
  return data;
}


export async function approveTarget(
  targetId: string,
  expectedGateRevision: string,
): Promise<Operation> {
  const { data, error, response } = await client.POST(
    "/api/v1/approvals",
    { body: { targetId, expectedGateRevision } },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Approval could not be started",
      error,
    );
  }
  return data;
}


export async function getResetPlan(targetId: string): Promise<ResetPlan> {
  const { data, error, response } = await client.POST(
    "/api/v1/resets/plan",
    { body: { targetId } },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "A reset plan could not be created",
      error,
    );
  }
  return data;
}


export async function executeReset(planToken: string): Promise<Operation> {
  const { data, error, response } = await client.POST(
    "/api/v1/resets",
    { body: { planToken } },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Reset could not be started",
      error,
    );
  }
  return data;
}


export async function getOutputs(
  targetId: string,
): Promise<OutputInventory> {
  const { data, error, response } = await client.GET("/api/v1/outputs", {
    params: { query: { targetId } },
  });
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Managed output is unavailable",
      error,
    );
  }
  return data;
}


export async function getOutputContent(
  outputId: string,
): Promise<OutputContent> {
  const { data, error, response } = await client.GET(
    "/api/v1/outputs/content",
    {
      params: { query: { outputId } },
    },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Managed output could not be read",
      error,
    );
  }
  return data;
}


export function outputDownloadUrl(outputId: string): string {
  const params = new URLSearchParams({ outputId });
  return `/api/v1/outputs/download?${params.toString()}`;
}


export async function getLogTargets(
  nodeId: string,
): Promise<LogTargetInventory> {
  const { data, error, response } = await client.GET(
    "/api/v1/nodes/{node_id}/log-targets",
    { params: { path: { node_id: nodeId } } },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Log targets are unavailable",
      error,
    );
  }
  return data;
}


export async function startLogStream(
  targetId: string,
  options: {
    tailLines: number;
    follow: boolean;
    pageSize?: number;
  },
): Promise<LogStream> {
  const { data, error, response } = await client.POST(
    "/api/v1/log-streams",
    {
      body: {
        targetId,
        tailLines: options.tailLines,
        follow: options.follow,
        pageSize: options.pageSize ?? 200,
      },
    },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Logs could not be started",
      error,
    );
  }
  return data;
}


export async function getLogPage(
  streamId: string,
  options: {
    before?: string;
    after?: string;
    limit?: number;
  } = {},
): Promise<LogPage> {
  const { data, error, response } = await client.GET(
    "/api/v1/log-streams/{stream_id}/pages",
    {
      params: {
        path: { stream_id: streamId },
        query: {
          before: options.before,
          after: options.after,
          limit: options.limit ?? 200,
        },
      },
    },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Buffered logs could not be read",
      error,
    );
  }
  return data;
}


export async function stopLogStream(
  streamId: string,
): Promise<LogStreamStatus> {
  const { data, error, response } = await client.DELETE(
    "/api/v1/log-streams/{stream_id}",
    { params: { path: { stream_id: streamId } } },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "The log stream could not be stopped",
      error,
    );
  }
  return data;
}


export function logEventsUrl(
  streamId: string,
  afterSequence: number,
): string {
  const params = new URLSearchParams({
    after: String(afterSequence),
  });
  return (
    `/api/v1/log-streams/${encodeURIComponent(streamId)}/events?`
    + params.toString()
  );
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
