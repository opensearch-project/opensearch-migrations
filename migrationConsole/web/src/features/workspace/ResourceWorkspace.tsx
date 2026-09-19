import { useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowLeft,
  ArrowRight,
  ChevronDown,
  ExternalLink,
  FileOutput,
  LoaderCircle,
  Logs,
  Pencil,
  RefreshCw,
  ShieldCheck,
  Trash2,
  TriangleAlert,
} from "lucide-react";

import type {
  ApprovalGateSummary,
  ManageNode,
  Operation,
  RuntimeStatus,
} from "../../api/client";
import { getRuntimeStatus } from "../../api/client";
import { OutputPanel } from "../output/OutputPanel";
import { LogPanel } from "../logviewer/LogPanel";
import { ResetDialog } from "../actions/ResourceActionDialogs";
import type { ApprovalCandidate } from "../actions/approvals";
import { StatusIndicator } from "../status/StatusIndicator";
import { presentResourceActionText } from "../status/operationPresentation";
import type { ConnectivityTargetState } from "../configuration/connectivityChecks";
import { ValidityDashboard } from "../configuration/ValidityDashboard";
import { ModalDialog } from "../../components/ModalDialog";


interface PendingAction {
  kind: "reset";
  targetId: string;
}

const RUNTIME_STATUS_PLURALS = new Set([
  "datasnapshots",
  "snapshotmigrations",
  "kafkaclusters",
  "capturedtraffics",
  "captureproxies",
  "trafficreplays",
]);

const CONFIG_ONLY_PLURALS = new Set(["sourceconfigs", "targetconfigs"]);

const GROUP_NOUNS: Record<string, [string, string]> = {
  Buffer: ["buffer resource", "buffer resources"],
  Capture: ["capture", "captures"],
  "Kafka Clusters": ["Kafka cluster", "Kafka clusters"],
  Replay: ["replay", "replays"],
  "Snapshot Migration": ["snapshot migration", "snapshot migrations"],
  Sources: ["source", "sources"],
  Targets: ["target", "targets"],
  Topics: ["topic", "topics"],
};


function observedTime(value: string): string {
  const observed = new Date(value);
  return Number.isNaN(observed.valueOf())
    ? value
    : observed.toLocaleTimeString([], {
      hour: "numeric",
      minute: "2-digit",
      second: "2-digit",
    });
}


function activityTimestamp(value: string): string {
  const activity = new Date(value);
  return Number.isNaN(activity.valueOf())
    ? value
    : activity.toLocaleString();
}


function statusClassName(value: string): string {
  return `status-${value.toLowerCase().replaceAll(/\s+/g, "-")}`;
}


type RuntimeStatusContentValue = NonNullable<
  RuntimeStatus["sections"][number]["content"]
>;


function metricValue(
  metric: Extract<
    RuntimeStatusContentValue,
    { kind: "metrics" }
  >["metrics"][number],
): string {
  const value = String(metric.value);
  if (!metric.unit) return value;
  return metric.unit === "percent"
    ? `${value}%`
    : `${value} ${metric.unit}`;
}


function RuntimeStatusContent({
  content,
  title,
}: Readonly<{
  content: RuntimeStatusContentValue;
  title: string;
}>) {
  if (content.kind === "metrics") {
    return (
      <dl className="runtime-status-metrics">
        {content.metrics.map((metric) => (
          <div key={metric.key}>
            <dt>{metric.label}</dt>
            <dd>{metricValue(metric)}</dd>
          </div>
        ))}
      </dl>
    );
  }
  if (content.kind === "name-list") {
    return (
      <ul aria-label={`${title} values`} className="runtime-status-names">
        {content.items.map((item) => (
          <li key={item}><code>{item}</code></li>
        ))}
      </ul>
    );
  }
  if (content.kind === "consumer-offsets") {
    return (
      <table className="runtime-status-table">
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">Consumer group</th>
            <th scope="col">Partition</th>
            <th scope="col">Current offset</th>
            <th scope="col">Log end</th>
            <th scope="col">Lag</th>
          </tr>
        </thead>
        <tbody>
          {content.offsets.map((offset) => (
            <tr key={`${offset.group}-${offset.topic}-${offset.partition}`}>
              <td><code>{offset.group}</code></td>
              <td>{offset.partition}</td>
              <td>
                {offset.currentOffset === null
                  || offset.currentOffset === undefined
                  ? "Not committed"
                  : offset.currentOffset.toLocaleString()}
              </td>
              <td>
                {offset.logEndOffset === null
                  || offset.logEndOffset === undefined
                  ? "Unknown"
                  : offset.logEndOffset.toLocaleString()}
              </td>
              <td>
                {offset.lag === null || offset.lag === undefined
                  ? "Unknown"
                  : offset.lag.toLocaleString()}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  }
  if (content.kind === "topic-partitions") {
    return (
      <table className="runtime-status-table">
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">Topic</th>
            <th scope="col">Partition</th>
            <th scope="col">Records</th>
          </tr>
        </thead>
        <tbody>
          {content.partitions.map((partition) => (
            <tr key={`${partition.topic}-${partition.partition}`}>
              <td><code>{partition.topic}</code></td>
              <td>{partition.partition}</td>
              <td>{partition.records.toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  }
  return (
    <pre className="runtime-status-text">{content.lines.join("\n")}</pre>
  );
}


function RuntimeStatusPanel({ node }: Readonly<{ node: ManageNode }>) {
  const forceRefresh = useRef(false);
  const supported = Boolean(
    node.resourcePlural
    && RUNTIME_STATUS_PLURALS.has(node.resourcePlural),
  );
  const status = useQuery({
    queryKey: ["runtime-status", node.id],
    queryFn: async () => {
      const force = forceRefresh.current;
      forceRefresh.current = false;
      return getRuntimeStatus(node.id, force);
    },
    enabled: supported,
    retry: false,
    staleTime: 5000,
    refetchInterval: (query) => (
      query.state.data?.pollAfterMs ?? false
    ),
  });
  if (!supported) return null;

  return (
    <section
      aria-label="Runtime status"
      className="workspace-section runtime-status"
    >
      <header>
        <div>
          <h3>Runtime status</h3>
          {status.data ? (
            <span>Observed {observedTime(status.data.observedAt)}</span>
          ) : null}
        </div>
        <button
          aria-label="Refresh runtime status"
          className="icon-button"
          disabled={status.isFetching}
          onClick={() => {
            forceRefresh.current = true;
            void status.refetch();
          }}
          title="Refresh runtime status"
          type="button"
        >
          <RefreshCw
            aria-hidden="true"
            className={status.isFetching ? "spin" : ""}
          />
        </button>
      </header>
      {status.isPending ? (
        <div className="runtime-status-loading">
          <LoaderCircle aria-hidden="true" className="spin" />
          <span>Reading runtime status</span>
        </div>
      ) : null}
      {status.isError ? (
        <div className="runtime-status-error">
          <TriangleAlert aria-hidden="true" />
          <span>{status.error.message}</span>
        </div>
      ) : null}
      {status.data?.sections.map((section) => (
        <article
          className={`runtime-status-section status-${section.state}`}
          key={section.key}
        >
          <StatusIndicator status={section.state} />
          <div>
            <strong>{section.title}</strong>
            <p>{section.summary}</p>
            {section.content ? (
              <RuntimeStatusContent
                content={section.content}
                title={section.title}
              />
            ) : null}
            <small>{section.source}</small>
          </div>
        </article>
      ))}
    </section>
  );
}


function displayValue(value: { present: boolean; value?: unknown }): string {
  if (!value.present) return "Not set";
  if (typeof value.value === "string") return value.value;
  if (value.value === null || value.value === undefined) return "null";
  return JSON.stringify(value.value);
}


function activePreapprovalGates(
  gates: ApprovalGateSummary[],
): ApprovalGateSummary[] {
  return gates.filter((gate) => (
    gate.category === "checkpoint"
    && ["upcoming", "preapproved"].includes(gate.state)
  ));
}


function PreapprovalActions({
  gates,
  loading,
  onOpen,
  onToggle,
  pendingNames,
}: Readonly<{
  gates: ApprovalGateSummary[];
  loading: boolean;
  onOpen: (gates: ApprovalGateSummary[]) => void;
  onToggle: (
    gates: ApprovalGateSummary[],
    preapproved: boolean,
  ) => void;
  pendingNames: Set<string>;
}>) {
  const upcoming = activePreapprovalGates(gates);
  if (loading || upcoming.length === 0) return null;
  const pending = upcoming.some((gate) => pendingNames.has(gate.name));
  const approvedCount = upcoming.filter((gate) => gate.approved).length;
  const allApproved = approvedCount === upcoming.length;
  if (upcoming.length === 1) {
    const gate = upcoming[0];
    return (
      <button
        className={[
          "resource-action-button",
          "resource-action-preapproval",
          gate.approved ? "active" : "",
        ].filter(Boolean).join(" ")}
        disabled={pending || !gate.toggleable}
        onClick={() => onToggle([gate], !gate.approved)}
        title={
          gate.disabledReason
          ?? (
            gate.approved
              ? `Require preapproval for ${gate.stage}`
              : `Preapprove ${gate.stage}`
          )
        }
        type="button"
      >
        {pending
          ? <LoaderCircle aria-hidden="true" className="spin" />
          : <ShieldCheck aria-hidden="true" />}
        {gate.approved ? "Require preapproval" : "Preapprove"}
      </button>
    );
  }
  return (
    <>
      <button
        className="resource-action-button resource-action-preapproval"
        disabled={pending || allApproved}
        onClick={() => onToggle(upcoming, true)}
        title={
          allApproved
            ? "All upcoming checkpoints are already preapproved"
            : "Preapprove every upcoming checkpoint for these resources"
        }
        type="button"
      >
        {pending
          ? <LoaderCircle aria-hidden="true" className="spin" />
          : <ShieldCheck aria-hidden="true" />}
        Preapprove all
      </button>
      <button
        className="resource-action-button resource-action-preapproval-select"
        disabled={pending}
        onClick={() => onOpen(upcoming)}
        title="Choose which upcoming checkpoints require approval"
        type="button"
      >
        <ShieldCheck aria-hidden="true" />
        Select preapprovals
      </button>
    </>
  );
}


function PreapprovalSelectionDialog({
  gates,
  onClose,
  onToggle,
  pendingNames,
  title,
}: Readonly<{
  gates: ApprovalGateSummary[];
  onClose: () => void;
  onToggle: (
    gates: ApprovalGateSummary[],
    preapproved: boolean,
  ) => void;
  pendingNames: Set<string>;
  title: string;
}>) {
  return (
    <ModalDialog
      className="resource-preapproval-dialog"
      closeLabel="Close preapproval selection"
      icon={<ShieldCheck aria-hidden="true" />}
      onClose={onClose}
      portal
      subtitle={title}
      title="Select preapprovals"
    >
      <div className="resource-preapproval-list">
        {gates.map((gate) => {
          const pending = pendingNames.has(gate.name);
          return (
            <label key={gate.name}>
              <span>
                <strong>{gate.stage}</strong>
                <small>
                  {gate.resourceName ?? "Workflow"}
                  {gate.effect ? ` · ${gate.effect}` : ""}
                </small>
              </span>
              <input
                aria-label={`Preapprove ${gate.stage}`}
                checked={gate.approved}
                disabled={pending || !gate.toggleable}
                onChange={(event) => onToggle(
                  [gate],
                  event.currentTarget.checked,
                )}
                title={gate.disabledReason ?? `Preapprove ${gate.stage}`}
                type="checkbox"
              />
            </label>
          );
        })}
      </div>
    </ModalDialog>
  );
}


function capabilityIcon(kind: string) {
  if (kind === "logs") return Logs;
  if (kind === "reset") return Trash2;
  if (kind === "approve") return ShieldCheck;
  return FileOutput;
}


function ResourceActions({
  node,
  onOutput,
  onLogs,
  onApproval,
  onDelete,
  onReset,
  cleanupRequired,
  approvals,
  preapprovalGates,
  preapprovalLoading,
  onOpenPreapprovals,
  onTogglePreapprovals,
  pendingPreapprovalNames,
  resetInProgress,
}: Readonly<{
  node: ManageNode;
  onOutput: (targetId: string) => void;
  onLogs: (targetId: string) => void;
  onApproval: (targetId: string) => void;
  onDelete?: () => void;
  onReset: (targetId: string) => void;
  cleanupRequired: boolean;
  approvals: ApprovalCandidate[];
  preapprovalGates: ApprovalGateSummary[];
  preapprovalLoading: boolean;
  onOpenPreapprovals: (gates: ApprovalGateSummary[]) => void;
  onTogglePreapprovals?: (
    gates: ApprovalGateSummary[],
    preapproved: boolean,
  ) => void;
  pendingPreapprovalNames: Set<string>;
  resetInProgress: boolean;
}>) {
  const capabilities = node.capabilities.filter(
    (capability) => capability.kind !== "edit",
  );
  const showPreapprovals = Boolean(
    onTogglePreapprovals
    && activePreapprovalGates(preapprovalGates).length > 0,
  );
  if (capabilities.length === 0 && !onDelete && !showPreapprovals) return null;
  const resetBeforeRetry = cleanupRequired || capabilities.some(
    (capability) => (
      capability.kind === "approve"
      && Boolean(capability.disabledReason)
    ),
  );
  const approvalOutputs = new Set(approvals.flatMap((approval) => (
    approval.outputTargetId ? [approval.outputTargetId] : []
  )));
  const orderedCapabilities = [...capabilities].sort((left, right) => {
    const rank = (capability: typeof left) => {
      if (capability.kind === "approve") return 0;
      if (
        capability.kind === "output"
        && approvalOutputs.has(capability.outputTargetId)
      ) {
        return 1;
      }
      if (capability.kind === "logs") return 2;
      if (capability.kind === "output") return 3;
      return 4;
    };
    return rank(left) - rank(right);
  });
  const regularCapabilities = orderedCapabilities.filter(
    (capability) => capability.kind !== "reset",
  );
  const resetCapabilities = orderedCapabilities.filter(
    (capability) => capability.kind === "reset",
  );
  const renderCapability = (
    capability: (typeof orderedCapabilities)[number],
    capabilityIndex: number,
  ) => {
    const Icon = capabilityIcon(capability.kind);
    const label = capability.kind === "reset"
      ? "Delete resource"
      : capability.label ?? capability.kind;
    const outputTarget = (
      capability.kind === "output"
        ? capability.outputTargetId
        : null
    );
    const logTarget = (
      capability.kind === "logs"
        ? capability.logTargetId
        : null
    );
    const approvalTarget = (
      capability.kind === "approve"
        ? capability.approvalTargetId
        : null
    );
    const resetTarget = (
      capability.kind === "reset"
        ? capability.resetTargetId
        : null
    );
    const actionTarget = approvalTarget ?? resetTarget;
    const disabledReason = capability.disabledReason
      ? presentResourceActionText(capability.disabledReason)
      : undefined;
    return (
      <button
        aria-label={label}
        className={[
          "resource-action-button",
          `resource-action-${capability.kind}`,
          resetBeforeRetry && capability.kind === "reset"
            ? "primary-button cleanup-action"
            : "",
        ].filter(Boolean).join(" ")}
        disabled={
          Boolean(disabledReason)
          || (capability.kind === "reset" && resetInProgress)
          || (!outputTarget && !logTarget && !actionTarget)
        }
        key={`${capability.kind}-${label}-${capabilityIndex}`}
        onClick={() => {
          if (outputTarget) onOutput(outputTarget);
          else if (logTarget) onLogs(logTarget);
          else if (approvalTarget) {
            onApproval(approvalTarget);
          } else if (resetTarget) {
            onReset(resetTarget);
          }
        }}
        title={(
          capability.kind === "reset" && resetInProgress
            ? "Resource deletion is already in progress"
            : disabledReason
        ) ?? (
          outputTarget || logTarget || actionTarget
            ? label
            : "This action is enabled in a later phase"
        )}
        type="button"
      >
        <Icon aria-hidden="true" />
        {label}
      </button>
    );
  };
  return (
    <div className="resource-actions" aria-label="Available actions">
      {regularCapabilities.map(renderCapability)}
      {onTogglePreapprovals ? (
        <PreapprovalActions
          gates={preapprovalGates}
          loading={preapprovalLoading}
          onOpen={onOpenPreapprovals}
          onToggle={onTogglePreapprovals}
          pendingNames={pendingPreapprovalNames}
        />
      ) : null}
      {resetCapabilities.map(renderCapability)}
      {onDelete ? (
        <button
          aria-label={`Remove ${node.label} from configuration`}
          className="resource-action-button resource-action-delete"
          onClick={onDelete}
          title={`Review removing ${node.label} from the workflow configuration`}
          type="button"
        >
          <Trash2 aria-hidden="true" />
          Remove from configuration
        </button>
      ) : null}
    </div>
  );
}


function ResourceIssues({
  node,
  onEdit,
  onReviewApproval,
}: Readonly<{
  node: ManageNode;
  onEdit?: () => void;
  onReviewApproval?: (targetId: string) => void;
}>) {
  const issues = node.diagnostics.filter((diagnostic) => (
    diagnostic.source === "workflow-apply"
    || diagnostic.source === "workflow-step"
  ));
  if (issues.length === 0) return null;
  const approvalCapability = node.capabilities.find(
    (capability) => capability.kind === "approve",
  );
  const approvalIssue = issues.find(
    (diagnostic) => diagnostic.source === "workflow-apply",
  );
  const immutableIssue = issues.find(
    (diagnostic) => diagnostic.code === "immutable-resource-update",
  );
  const approvalTitle = approvalCapability?.disabledReason
    ? "Resource deletion required before approval"
    : approvalCapability || approvalIssue
      ? "Approval required"
      : null;
  return (
    <section
      className="resource-issues"
      aria-label={approvalTitle ?? "Action required"}
    >
      {approvalTitle ? (
        <header className="resource-approval-heading">
          <ShieldCheck aria-hidden="true" />
          <div>
            <strong>{approvalTitle}</strong>
            <span>
              {approvalCapability?.disabledReason
                ? presentResourceActionText(
                    approvalCapability.disabledReason,
                  )
                : "Review the denied change before approving the retry."}
            </span>
          </div>
        </header>
      ) : null}
      {issues.map((issue, index) => (
        <div
          aria-label={
            presentResourceActionText(issue.title ?? "Workflow failure")
          }
          className="resource-issue"
          key={`${issue.code ?? issue.message}-${index}`}
          role="alert"
        >
          <TriangleAlert aria-hidden="true" />
          <div>
            <h3>
              {presentResourceActionText(
                issue.title ?? "Workflow step failed",
              )}
            </h3>
            <p>{presentResourceActionText(issue.message)}</p>
            {issue.remedy ? (
              <div className="issue-remedy">
                <strong>Next step</strong>
                <span>{presentResourceActionText(issue.remedy)}</span>
              </div>
            ) : null}
            {issue.technicalDetail ? (
              <details>
                <summary>Technical details</summary>
                <pre>{issue.technicalDetail}</pre>
              </details>
            ) : null}
            {issue === immutableIssue ? (
              <div className="issue-actions">
                {onEdit ? (
                  <button onClick={onEdit} type="button">
                    <Pencil aria-hidden="true" />
                    Edit configuration
                  </button>
                ) : null}
                {
                  approvalCapability?.kind === "approve"
                  && onReviewApproval
                    ? (
                      <button
                        className="primary-button"
                        onClick={() => onReviewApproval(
                          approvalCapability.approvalTargetId,
                        )}
                        type="button"
                      >
                        <Trash2 aria-hidden="true" />
                        Review resource deletion and resubmit
                      </button>
                    )
                    : null
                }
              </div>
            ) : null}
          </div>
        </div>
      ))}
    </section>
  );
}


function RecentOperationFailure({
  node,
  operations,
}: Readonly<{
  node: ManageNode;
  operations: Operation[];
}>) {
  const nodeIds = new Set([node.id, ...node.childIds]);
  const latest = operations
    .filter((operation) => (
      operation.targetIds.some((targetId) => nodeIds.has(targetId))
    ))
    .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))[0];
  if (latest?.status !== "failed") return null;
  return (
    <section
      aria-label="Recent operation failed"
      className="workspace-section operation-failure"
      role="alert"
    >
      <header>
        <TriangleAlert aria-hidden="true" />
        <div>
          <h3>Recent operation failed</h3>
          <strong>{presentResourceActionText(latest.label)}</strong>
        </div>
      </header>
      <p>{presentResourceActionText(latest.message)}</p>
      <details>
        <summary>Failure details</summary>
        <pre>
          {presentResourceActionText(
            latest.detail || "No additional failure detail was reported.",
          )}
        </pre>
      </details>
    </section>
  );
}


function resourceState(node: ManageNode): string {
  const state = String(node.phase ?? node.status ?? "Unknown");
  if (state === "Deployed Config") return "Configured";
  if (state === "Pending Config") return "Pending";
  return state;
}


function resourceDescendants(
  node: ManageNode,
  nodes: Record<string, ManageNode>,
): ManageNode[] {
  const resources: ManageNode[] = [];
  const visited = new Set<string>();
  const visit = (nodeId: string) => {
    if (visited.has(nodeId)) return;
    visited.add(nodeId);
    const child = nodes[nodeId];
    if (!child || child.kind === "workflow-step") return;
    if (child.kind === "resource") {
      resources.push(child);
      return;
    }
    child.childIds.forEach(visit);
  };
  node.childIds.forEach(visit);
  return resources;
}


function groupCountLabel(node: ManageNode, count: number): string {
  const nouns = GROUP_NOUNS[node.label] ?? ["resource", "resources"];
  return `${count} ${count === 1 ? nouns[0] : nouns[1]}`;
}


function RuntimeSummaryCell({ node }: Readonly<{ node: ManageNode }>) {
  const supported = Boolean(
    node.resourcePlural
    && RUNTIME_STATUS_PLURALS.has(node.resourcePlural),
  );
  const status = useQuery({
    queryKey: ["runtime-status", node.id],
    queryFn: () => getRuntimeStatus(node.id, false),
    enabled: supported,
    retry: false,
    staleTime: 5000,
  });
  if (!supported) return <span className="table-empty-value">Not available</span>;
  if (status.isPending) {
    return <span className="table-empty-value">Reading status</span>;
  }
  if (status.isError || !status.data) {
    return <span className="table-empty-value">Status unavailable</span>;
  }
  return (
    <span className="resource-runtime-summary">
      {status.data.sections.map((section) => section.summary).join(" · ")}
    </span>
  );
}


function ResourceCollectionTable({
  approvalGates,
  label,
  onOpenPreapprovals,
  onRequestApproval,
  onReset,
  onSelect,
  onTogglePreapprovals,
  pendingPreapprovalNames,
  resources,
  showRuntimeSummary = false,
  workflowActive,
}: Readonly<{
  approvalGates: ApprovalGateSummary[];
  label: string;
  onOpenPreapprovals: (
    gates: ApprovalGateSummary[],
    title: string,
  ) => void;
  onRequestApproval?: (targetId: string) => void;
  onReset: (targetId: string) => void;
  onSelect: (nodeId: string) => void;
  onTogglePreapprovals?: (
    gates: ApprovalGateSummary[],
    preapproved: boolean,
  ) => void;
  pendingPreapprovalNames: Set<string>;
  resources: ManageNode[];
  showRuntimeSummary?: boolean;
  workflowActive: boolean;
}>) {
  const resourceIds = new Set(resources.map((resource) => resource.id));
  const scopedGates = workflowActive
    ? approvalGates.filter((gate) => (
      Boolean(gate.resourceId)
      && resourceIds.has(gate.resourceId!)
    ))
    : [];
  const ariaLabel = label.toLowerCase().endsWith("resources")
    ? label
    : `${label} resources`;
  const hasActions = resources.some((resource) => (
    resource.capabilities.some((capability) => (
      capability.kind === "logs" || capability.kind === "reset"
    ))
  ));
  return (
    <section
      aria-label={ariaLabel}
      className="workspace-section resource-collection"
    >
      <header>
        <h3>{label}</h3>
        {workflowActive && onTogglePreapprovals ? (
          <div
            aria-label={`${label} preapproval actions`}
            className="resource-collection-actions"
          >
            <PreapprovalActions
              gates={scopedGates}
              loading={false}
              onOpen={(gates) => onOpenPreapprovals(gates, label)}
              onToggle={onTogglePreapprovals}
              pendingNames={pendingPreapprovalNames}
            />
          </div>
        ) : null}
      </header>
      {resources.length === 0 ? (
        <p className="resource-collection-empty">
          No configured resources.
        </p>
      ) : (
        <div className="resource-collection-scroll">
          <table className="resource-collection-table">
            <thead>
              <tr>
                <th scope="col">Resource</th>
                <th scope="col">State</th>
                {showRuntimeSummary ? (
                  <th scope="col">Runtime summary</th>
                ) : null}
                <th scope="col">Approvals</th>
                {hasActions ? <th scope="col">Actions</th> : null}
              </tr>
            </thead>
            <tbody>
              {resources.map((resource) => {
                const gates = scopedGates.filter(
                  (gate) => gate.resourceId === resource.id,
                );
                const blocking = gates.find((gate) => (
                  gate.state === "blocking" && gate.approvalTargetId
                ));
                const upcoming = activePreapprovalGates(gates);
                const logs = resource.capabilities.find(
                  (capability) => capability.kind === "logs",
                );
                const reset = resource.capabilities.find(
                  (capability) => capability.kind === "reset",
                );
                return (
                  <tr key={resource.id}>
                    <th scope="row">
                      <button
                        onClick={() => onSelect(resource.id)}
                        type="button"
                      >
                        <strong>{resource.label}</strong>
                        <small>
                          {resource.resourceType ?? resource.resourcePlural}
                        </small>
                      </button>
                    </th>
                    <td>
                      <span className={`collection-state ${statusClassName(
                        resourceState(resource),
                      )}`}>
                        <StatusIndicator
                          status={resource.phase ?? resource.status}
                        />
                        {resourceState(resource)}
                      </span>
                    </td>
                    {showRuntimeSummary ? (
                      <td><RuntimeSummaryCell node={resource} /></td>
                    ) : null}
                    <td>
                      {blocking?.approvalTargetId && onRequestApproval ? (
                        <button
                          className="table-action approval"
                          onClick={() => onRequestApproval(
                            blocking.approvalTargetId!,
                          )}
                          type="button"
                        >
                          <ShieldCheck aria-hidden="true" />
                          Approve
                        </button>
                      ) : upcoming.length > 0 ? (
                        <button
                          className="table-action"
                          onClick={() => onOpenPreapprovals(
                            upcoming,
                            resource.label,
                          )}
                          type="button"
                        >
                          {upcoming.length} upcoming
                        </button>
                      ) : (
                        <span className="table-empty-value">None</span>
                      )}
                    </td>
                    {hasActions ? (
                      <td>
                        <div className="resource-table-actions">
                          {logs?.kind === "logs" ? (
                            <a
                              className="table-action"
                              href={`/logs?${new URLSearchParams({
                                nodeId: resource.id,
                              }).toString()}`}
                              rel="noopener noreferrer"
                              target="_blank"
                            >
                              <Logs aria-hidden="true" />
                              Logs
                              <ExternalLink aria-hidden="true" />
                            </a>
                          ) : null}
                          {reset?.kind === "reset" ? (
                            <button
                              className="table-action destructive"
                              onClick={() => onReset(reset.resetTargetId)}
                              type="button"
                            >
                              <Trash2 aria-hidden="true" />
                              Delete
                            </button>
                          ) : null}
                        </div>
                      </td>
                    ) : null}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}


function Findings({ node }: Readonly<{ node: ManageNode }>) {
  const diagnostics = node.diagnostics.filter((diagnostic) => (
    diagnostic.source !== "workflow-apply"
    && diagnostic.source !== "workflow-step"
  ));
  if (diagnostics.length === 0) return null;
  return (
    <section className="workspace-section">
      <h3>Findings</h3>
      <div className="diagnostic-list">
        {diagnostics.map((diagnostic, index) => (
          <details className={`diagnostic diagnostic-${diagnostic.severity}`} key={`${diagnostic.message}-${index}`}>
            <summary>
              <TriangleAlert aria-hidden="true" />
              <span>{diagnostic.title ?? diagnostic.message}</span>
              <strong>{diagnostic.severity}</strong>
            </summary>
            <div>
              {diagnostic.path.length > 0 ? (
                <code>{diagnostic.path.join(".")}</code>
              ) : null}
              {diagnostic.source ? <span>Source: {diagnostic.source}</span> : null}
            </div>
          </details>
        ))}
      </div>
    </section>
  );
}


function FailedWorkflowSteps({
  onSelect,
  steps,
}: Readonly<{
  onSelect: (nodeId: string) => void;
  steps: ManageNode[];
}>) {
  const failed = steps
    .filter((step) => (
      step.status === "error"
      || step.status === "blocked"
      || step.phase === "Failed"
      || step.phase === "Error"
      || step.phase === "Blocked"
    ))
    .sort((left, right) => (
      (right.activityAt ?? "").localeCompare(left.activityAt ?? "")
    ));
  if (failed.length === 0) return null;
  return (
    <section
      aria-label="Failed workflow steps"
      className="workspace-section failed-workflow-steps"
    >
      <header>
        <div>
          <h3>Failed workflow steps</h3>
          <span>
            {failed.length} {failed.length === 1 ? "step needs" : "steps need"}
            {" attention"}
          </span>
        </div>
      </header>
      <div className="failed-workflow-step-list">
        {failed.map((step) => {
          const message = step.details.find(
            (detail) => detail.kind === "message",
          );
          return (
            <button
              aria-label={`Inspect failed workflow step ${step.label}, ${
                step.phase ?? step.status
              }`}
              key={step.id}
              onClick={() => onSelect(step.id)}
              type="button"
            >
              <StatusIndicator status={step.phase ?? step.status} />
              <span>
                <strong>{step.label}</strong>
                <small>
                  {message
                    ? String(message.value)
                    : step.phase ?? step.status}
                </small>
              </span>
              <span>
                {step.activityAt ? (
                  <time dateTime={step.activityAt}>
                    {activityTimestamp(step.activityAt)}
                  </time>
                ) : null}
                <ArrowRight aria-hidden="true" />
              </span>
            </button>
          );
        })}
      </div>
    </section>
  );
}


function Comparisons({ node }: Readonly<{ node: ManageNode }>) {
  if (node.comparisons.length === 0) return null;
  return (
    <section className="workspace-section">
      <h3>Configuration comparison</h3>
      <div className="comparison-scroll">
        <table className="comparison-table">
          <thead>
            <tr>
              <th scope="col">Field</th>
              <th scope="col">Deployed</th>
              <th scope="col">Submitted</th>
              <th scope="col">Pending</th>
            </tr>
          </thead>
          <tbody>
            {node.comparisons.map((comparison) => (
              <tr key={comparison.path}>
                <th scope="row">{comparison.label}</th>
                <td>{displayValue(comparison.deployed)}</td>
                <td className={comparison.submittedChanged ? "changed" : ""}>
                  {displayValue(comparison.submitted)}
                </td>
                <td className={comparison.pendingChanged ? "changed" : ""}>
                  {displayValue(comparison.pending)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}


function ConfigurationOverview({ node }: Readonly<{ node: ManageNode }>) {
  if (node.comparisons.length > 0) return <Comparisons node={node} />;
  const fields = node.details.filter((detail) => detail.kind === "spec");
  return (
    <section
      aria-label="Configuration"
      className="workspace-section configuration-overview"
    >
      <h3>Configuration</h3>
      {fields.length === 0 ? (
        <p>No configuration fields are defined yet.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th scope="col">Field</th>
              <th scope="col">Saved configuration</th>
            </tr>
          </thead>
          <tbody>
            {fields.map((detail) => (
              <tr key={`${detail.label}-${detail.kind}`}>
                <th scope="row">{detail.label}</th>
                <td>
                  {displayValue({ present: true, value: detail.value })}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}


function ResourceDefinitionSummary({
  node,
}: Readonly<{ node: ManageNode }>) {
  const statusFields = [
    ...(node.activityAt ? [{
      label: "Last activity",
      value: activityTimestamp(node.activityAt),
    }] : []),
    ...node.details
      .filter((detail) => (
        detail.kind !== "spec"
        && detail.kind !== "dependency"
        && detail.kind !== "phase"
      ))
      .map((detail) => ({
        label: detail.label,
        value: displayValue({ present: true, value: detail.value }),
      })),
  ];
  const definitionFields = node.details
    .filter((detail) => detail.kind === "spec")
    .map((detail) => ({
      label: detail.label,
      value: displayValue({ present: true, value: detail.value }),
    }));
  if (statusFields.length === 0 && definitionFields.length === 0) return null;
  return (
    <details className="resource-definition-summary" open>
      <summary>
        <span>Resource summary</span>
        <ChevronDown aria-hidden="true" />
      </summary>
      <table>
        <tbody>
          {statusFields.length > 0 ? (
            <tr className="resource-definition-group">
              <th colSpan={2} scope="colgroup">Status</th>
            </tr>
          ) : null}
          {statusFields.map((field) => (
            <tr key={`status-${field.label}`}>
              <th scope="row">{field.label}</th>
              <td>{field.value}</td>
            </tr>
          ))}
          {definitionFields.length > 0 ? (
            <tr className="resource-definition-group">
              <th colSpan={2} scope="colgroup">Definition</th>
            </tr>
          ) : null}
          {definitionFields.map((field) => (
            <tr key={`definition-${field.label}`}>
              <th scope="row">{field.label}</th>
              <td>{field.value}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </details>
  );
}


export function ResourceWorkspace({
  node,
  nodes = {},
  navigationBackLabel,
  onSelect,
  onDelete,
  onEdit,
  onNavigateBack,
  onResourceDeletionStarted,
  onRequestApproval,
  approvalGates = [],
  approvalGatesLoading = false,
  approvals = [],
  onTogglePreapprovals,
  operations = [],
  pendingPreapprovalNames = new Set<string>(),
  resetInProgress = false,
  workflowSteps = [],
  connectivityState,
  onCheckConnectivity,
  workflowPhase,
}: Readonly<{
  node: ManageNode;
  nodes?: Record<string, ManageNode>;
  navigationBackLabel?: string | null;
  onSelect: (nodeId: string) => void;
  onDelete?: () => void;
  onEdit?: () => void;
  onNavigateBack?: () => void;
  onResourceDeletionStarted?: () => void;
  onRequestApproval?: (targetId: string) => void;
  approvalGates?: ApprovalGateSummary[];
  approvalGatesLoading?: boolean;
  approvals?: ApprovalCandidate[];
  onTogglePreapprovals?: (
    gates: ApprovalGateSummary[],
    preapproved: boolean,
  ) => void;
  operations?: Operation[];
  pendingPreapprovalNames?: Set<string>;
  resetInProgress?: boolean;
  workflowSteps?: ManageNode[];
  connectivityState?: ConnectivityTargetState;
  onCheckConnectivity?: (targetIds: string[]) => void;
  workflowPhase?: string | null;
}>) {
  const [outputTarget, setOutputTarget] = useState<string | null>(null);
  const [logTarget, setLogTarget] = useState<string | null>(null);
  const [preapprovalSelection, setPreapprovalSelection] = useState<{
    gates: ApprovalGateSummary[];
    title: string;
  } | null>(null);
  // The workspace is keyed on node.id by its parent, so panel targets
  // reset by remount instead of a one-frame-late effect.
  const [pendingAction, setPendingAction] =
    useState<PendingAction | null>(null);
  // Mirrors the server's orphan derivation from configPresence rather
  // than matching the "Orphaned; cleanup required" presentation string.
  const presence = node.configPresence ?? {};
  const presenceDeployed = presence.deployed ?? true;
  const presenceSubmitted = presence.submitted ?? presenceDeployed;
  const presencePending = presence.pending ?? presenceSubmitted;
  const cleanupRequired = (
    node.kind === "resource"
    && !("pending" in presence && presencePending !== presenceSubmitted)
    && "submitted" in presence
    && presenceSubmitted !== presenceDeployed
    && !presenceSubmitted
  );
  const workflowActive = ["pending", "running"].includes(
    String(workflowPhase ?? "").toLowerCase(),
  );
  const descendants = useMemo(
    () => resourceDescendants(node, nodes),
    [node, nodes],
  );
  const groupNode = node.kind === "group" || node.kind === "section";
  const configOnly = Boolean(
    node.resourcePlural && CONFIG_ONLY_PLURALS.has(node.resourcePlural),
  );
  const selectedGates = workflowActive
    ? approvalGates.filter((gate) => (
      gate.category === "checkpoint"
      && gate.resourceId === node.id
    ))
    : [];
  const collectionLabel = groupNode
    ? groupCountLabel(node, descendants.length)
    : "Related resources";
  return (
    <article className="workspace">
      <header className="workspace-header">
        <div className="workspace-heading">
          {navigationBackLabel && onNavigateBack ? (
            <button
              aria-label={`Back to ${navigationBackLabel}`}
              className="navigation-back-button"
              onClick={onNavigateBack}
              title={`Back to ${navigationBackLabel}`}
              type="button"
            >
              <ArrowLeft aria-hidden="true" />
            </button>
          ) : null}
          <div>
            <div className="workspace-title">
              <h2>{node.label}</h2>
              {groupNode ? <span className="group-label">(GROUP)</span> : null}
            </div>
            {!groupNode && !configOnly ? (
              <span className={`phase-badge resource-phase ${statusClassName(
                String(node.phase ?? node.status),
              )}`}>
                {node.phase ?? node.status}
              </span>
            ) : null}
          </div>
        </div>
        <div className="workspace-states">
          {node.status === "blocked" && node.phase !== "Blocked" ? (
            <span className="phase-badge status-blocked">
              Update blocked
            </span>
          ) : null}
        </div>
      </header>
      {!groupNode ? (
        <ResourceActions
          approvals={approvals.filter(
            (candidate) => candidate.nodeId === node.id,
          )}
          cleanupRequired={cleanupRequired}
          node={node}
          onLogs={(targetId) => {
            setOutputTarget(null);
            setLogTarget(targetId);
          }}
          onApproval={(targetId) => onRequestApproval?.(targetId)}
          onDelete={onDelete}
          onOpenPreapprovals={(gates) => setPreapprovalSelection({
            gates,
            title: node.label,
          })}
          onOutput={(targetId) => {
            setLogTarget(null);
            setOutputTarget(targetId);
          }}
          onReset={(targetId) => setPendingAction({
            kind: "reset",
            targetId,
          })}
          onTogglePreapprovals={
            workflowActive ? onTogglePreapprovals : undefined
          }
          pendingPreapprovalNames={pendingPreapprovalNames}
          preapprovalGates={selectedGates}
          preapprovalLoading={approvalGatesLoading}
          resetInProgress={resetInProgress}
        />
      ) : null}
      {connectivityState && onCheckConnectivity ? (
        <ValidityDashboard
          ariaLabel="Resource checks"
          connectivityLoading={false}
          connectivityProblem=""
          connectivityStates={[connectivityState]}
          environmentGroups={[]}
          onCheckConnectivity={onCheckConnectivity}
        />
      ) : null}
      {cleanupRequired || resetInProgress ? (
        <section
          aria-label={
            resetInProgress
              ? "Resource deletion in progress"
              : "Resource deletion required"
          }
          className={`cleanup-notice ${resetInProgress ? "removing" : ""}`}
        >
          {resetInProgress
            ? <LoaderCircle className="spin" aria-hidden="true" />
            : <Trash2 aria-hidden="true" />}
          <div>
            <h3>
              {resetInProgress
                ? "Resource deletion in progress"
                : "Resource deletion required"}
            </h3>
            <p>
              {resetInProgress
                ? "The resource deletion operation is deleting this resource and its planned dependents."
                : (
                  "This resource is still deployed but is no longer in the "
                  + "submitted configuration. Delete it and its planned "
                  + "dependents using the dependency-safe resource plan."
                )}
            </p>
          </div>
        </section>
      ) : null}
      <ResourceIssues
        node={node}
        onEdit={onEdit}
        onReviewApproval={onRequestApproval}
      />
      <RecentOperationFailure node={node} operations={operations} />
      <FailedWorkflowSteps onSelect={onSelect} steps={workflowSteps} />
      {groupNode ? (
        <ResourceCollectionTable
          approvalGates={approvalGates}
          label={collectionLabel}
          onOpenPreapprovals={(gates, title) => setPreapprovalSelection({
            gates,
            title,
          })}
          onRequestApproval={onRequestApproval}
          onReset={(targetId) => setPendingAction({
            kind: "reset",
            targetId,
          })}
          onSelect={onSelect}
          onTogglePreapprovals={onTogglePreapprovals}
          pendingPreapprovalNames={pendingPreapprovalNames}
          resources={descendants}
          workflowActive={workflowActive}
        />
      ) : configOnly ? (
        <>
          <ConfigurationOverview node={node} />
          {descendants.length > 0 ? (
            <ResourceCollectionTable
              approvalGates={approvalGates}
              label="Related resources"
              onOpenPreapprovals={(gates, title) => setPreapprovalSelection({
                gates,
                title,
              })}
              onRequestApproval={onRequestApproval}
              onReset={(targetId) => setPendingAction({
                kind: "reset",
                targetId,
              })}
              onSelect={onSelect}
              onTogglePreapprovals={onTogglePreapprovals}
              pendingPreapprovalNames={pendingPreapprovalNames}
              resources={descendants}
              showRuntimeSummary
              workflowActive={workflowActive}
            />
          ) : null}
        </>
      ) : (
        <ResourceDefinitionSummary node={node} />
      )}
      {logTarget ? (
        <LogPanel
          nodeId={node.id}
          onClose={() => setLogTarget(null)}
        />
      ) : null}
      {outputTarget ? (
        <OutputPanel
          approval={approvals.find(
            (candidate) => candidate.outputTargetId === outputTarget,
          )}
          onClose={() => setOutputTarget(null)}
          targetId={outputTarget}
        />
      ) : null}
      {pendingAction ? (
        <ResetDialog
          onClose={() => setPendingAction(null)}
          onStarted={onResourceDeletionStarted}
          targetId={pendingAction.targetId}
        />
      ) : null}
      {preapprovalSelection && onTogglePreapprovals ? (
        <PreapprovalSelectionDialog
          gates={preapprovalSelection.gates}
          onClose={() => setPreapprovalSelection(null)}
          onToggle={onTogglePreapprovals}
          pendingNames={pendingPreapprovalNames}
          title={preapprovalSelection.title}
        />
      ) : null}
      <Findings node={node} />
      {!configOnly && !groupNode ? <Comparisons node={node} /> : null}
      {!configOnly && !groupNode ? <RuntimeStatusPanel node={node} /> : null}
    </article>
  );
}
