import createClient from "openapi-fetch";

import type { components, paths } from "./schema.generated";


export type ManageSnapshot = components["schemas"]["ManageSnapshotV1"];
export type ManageNode = components["schemas"]["ManageNodeV1"];
export type ManageRelationship = components["schemas"]["RelationshipV1"];
export type RuntimeStatus = components["schemas"]["RuntimeStatusV1"];
export type ConfigurationDocument =
  components["schemas"]["ConfigurationDocumentV1"];
export type ConfigurationSchema =
  components["schemas"]["ConfigurationSchemaV1"];
export type ConfigEnvironmentDiagnostics =
  components["schemas"]["ConfigEnvironmentDiagnosticsV1"];
export type ConnectivityInventory =
  components["schemas"]["ConnectivityInventoryV1"];
export type ConnectivityTarget =
  components["schemas"]["ConnectivityTargetV1"];
export type ConfigReview = components["schemas"]["ConfigReviewV1"];
export type AdmissionPreflight =
  components["schemas"]["AdmissionPreflightV1"];
export type Operation = components["schemas"]["OperationV1"];
export type ApprovalReview = components["schemas"]["ApprovalReviewV1"];
export type ApprovalGateInventory =
  components["schemas"]["ApprovalGateInventoryV1"];
export type ApprovalGateSummary =
  components["schemas"]["ApprovalGateSummaryV1"];
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
export type ExternalResourceInventory =
  components["schemas"]["ExternalResourceInventoryV1"];
export type ExternalResourceRow =
  components["schemas"]["ExternalResourceRowV1"];
export type ExternalResourceDetails =
  components["schemas"]["ExternalResourceDetailsV1"];
export type ExternalResourceMutation =
  components["schemas"]["ExternalResourceMutationV1"];


const client = createClient<paths>({
  baseUrl: globalThis.location.origin,
  fetch: (...args) => globalThis.fetch(...args),
});


interface ApiErrorDetail {
  code?: string;
  message?: string;
  current?: ConfigurationDocument;
  persistedRevision?: string;
}


export class ConfigApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly currentDocument?: ConfigurationDocument;

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
    this.code = detail && typeof detail === "object" ? detail.code : undefined;
    const current = detail && typeof detail === "object"
      ? detail.current
      : undefined;
    this.currentDocument = current && "persistedRevision" in current
      ? current
      : undefined;
  }
}


export async function getHealth() {
  const { data, error, response } = await client.GET(
    "/api/v1/system/health",
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Workflow Manage server is unavailable",
      error,
    );
  }
  return data;
}


export async function getManageState(): Promise<ManageSnapshot> {
  const { data, error, response } = await client.GET(
    "/api/v1/manage/state",
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Workflow state is unavailable",
      error,
    );
  }
  return data;
}


export async function getRuntimeStatus(
  nodeId: string,
  force = false,
): Promise<RuntimeStatus> {
  const { data, error, response } = await client.GET(
    "/api/v1/nodes/{node_id}/runtime-status",
    {
      params: {
        path: { node_id: nodeId },
        query: { force },
      },
    },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Runtime status is unavailable",
      error,
    );
  }
  return data;
}


export async function getConfigurationDocument(): Promise<ConfigurationDocument> {
  const { data, error, response } = await client.GET(
    "/api/v1/config/document",
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "The saved configuration document is unavailable",
      error,
    );
  }
  return data;
}


export async function getConfigurationSchema(): Promise<ConfigurationSchema> {
  const { data, error, response } = await client.GET(
    "/api/v1/config/schema",
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "The configuration editor schema is unavailable",
      error,
    );
  }
  return data;
}


export async function saveConfigurationDocument(
  expectedPersistedRevision: string,
  rawYaml: string,
): Promise<ConfigurationDocument> {
  const { data, error, response } = await client.PUT(
    "/api/v1/config/document",
    {
      body: {
        expectedPersistedRevision,
        rawYaml,
      },
    },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "The configuration document could not be saved",
      error,
    );
  }
  return data;
}


export async function diagnoseConfigurationEnvironment(
  rawYaml: string,
  draftNonce: string,
  signal?: AbortSignal,
): Promise<ConfigEnvironmentDiagnostics> {
  const { data, error, response } = await client.POST(
    "/api/v1/config/diagnostics",
    {
      body: {
        rawYaml,
        draftNonce,
      },
      signal,
    },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Configuration environment checks could not be completed",
      error,
    );
  }
  return data;
}

export async function getConnectivityInventory(
  rawYaml: string,
  configNonce: string,
  signal?: AbortSignal,
): Promise<ConnectivityInventory> {
  const { data, error, response } = await client.POST(
    "/api/v1/config/connectivity/inventory",
    {
      body: { rawYaml, configNonce },
      signal,
    },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Connectivity inventory is unavailable",
      error,
    );
  }
  return data;
}


export async function startConnectivityChecks(
  rawYaml: string,
  configNonce: string,
  targetIds: string[] = [],
): Promise<Operation> {
  const { data, error, response } = await client.POST(
    "/api/v1/config/connectivity/checks",
    {
      body: { rawYaml, configNonce, targetIds },
    },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Connectivity checks could not be started",
      error,
    );
  }
  return data;
}


export async function submitSavedConfiguration(
  persistedRevision: string,
): Promise<Operation> {
  const { data, error, response } = await client.POST(
    "/api/v1/config/submit",
    { body: { expectedPersistedRevision: persistedRevision } },
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
  persistedRevision: string,
): Promise<ConfigReview> {
  const { data, error, response } = await client.POST(
    "/api/v1/config/review",
    { body: { expectedPersistedRevision: persistedRevision } },
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

export async function getConfigPreflight(
  persistedRevision: string,
): Promise<AdmissionPreflight> {
  const { data, error, response } = await client.POST(
    "/api/v1/config/preflight",
    { body: { expectedPersistedRevision: persistedRevision } },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Admission preflight could not be completed",
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
    throw new ConfigApiError(
      response.status,
      "Recent operations are unavailable",
      error,
    );
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


export async function getApprovalGates(): Promise<ApprovalGateInventory> {
  const { data, error, response } = await client.GET(
    "/api/v1/approval-gates",
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Approval checkpoints are unavailable",
      error,
    );
  }
  return data;
}


export async function setGatePreapproval(
  gateName: string,
  expectedGateRevision: string,
  preapproved: boolean,
): Promise<void> {
  const { error, response } = await client.PATCH(
    "/api/v1/approval-gates/{gate_name}",
    {
      params: { path: { gate_name: gateName } },
      body: { expectedGateRevision, preapproved },
    },
  );
  if (!response.ok || error) {
    throw new ConfigApiError(
      response.status,
      "The preapproval could not be changed",
      error,
    );
  }
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
      "A resource deletion plan could not be created",
      error,
    );
  }
  return data;
}


export async function getCombinedResetPlan(
  targetIds: string[],
): Promise<ResetPlan> {
  const { data, error, response } = await client.POST(
    "/api/v1/resets/plan",
    { body: { targetIds } },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "A combined resource deletion plan could not be created",
      error,
    );
  }
  return data;
}


export async function executeReset(
  planToken: string,
  options: {
    resubmit?: boolean;
    expectedPersistedRevision?: string;
  } = {},
): Promise<Operation> {
  const { data, error, response } = await client.POST(
    "/api/v1/resets",
    {
      body: {
        planToken,
        resubmit: options.resubmit ?? false,
        expectedPersistedRevision: options.expectedPersistedRevision,
      },
    },
  );
  if (!response.ok || error || !data) {
    throw new ConfigApiError(
      response.status,
      "Resource deletion could not be started",
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
  rawYaml: string,
): Promise<ExternalResourceInventory> {
  const { data, error, response } = await client.POST(
    "/api/v1/external-resources",
    {
      body: {
        nodeId,
        rawYaml,
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
  rawYaml: string,
  selection: ExternalResourceSelection,
): Promise<void> {
  const { data, error, response } = await client.POST(
    "/api/v1/external-resources/select",
    {
      body: {
        rawYaml,
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
}


export async function getExternalResourceDetails(
  nodeId: string,
  rawYaml: string,
  name: string,
): Promise<ExternalResourceDetails> {
  const { data, error, response } = await client.POST(
    "/api/v1/external-resources/details",
    {
      body: {
        nodeId,
        rawYaml,
        name,
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
  rawYaml: string,
  nodeId: string,
  values: Record<string, string>,
  confirmations: Record<string, string>,
  existingName?: string,
): Promise<ExternalResourceMutation> {
  const { data, error, response } = await client.POST(
    "/api/v1/external-resources/save",
    {
      body: {
        rawYaml,
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
