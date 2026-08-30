import { useEffect, useState } from "react";
import {
  ArrowLeft,
  ArrowRight,
  FileOutput,
  LoaderCircle,
  Logs,
  Pencil,
  RotateCcw,
  ShieldCheck,
  TriangleAlert,
} from "lucide-react";

import type {
  ApprovalGateSummary,
  ManageNode,
  ManageRelationship,
  Operation,
} from "../../api/client";
import { OutputPanel } from "../output/OutputPanel";
import { LogPanel } from "../logviewer/LogPanel";
import { ResetDialog } from "../actions/ResourceActionDialogs";
import type { ApprovalCandidate } from "../actions/approvals";
import { StatusIndicator } from "../status/StatusIndicator";


interface PendingAction {
  kind: "reset";
  targetId: string;
}

function displayValue(value: { present: boolean; value?: unknown }): string {
  if (!value.present) return "Not set";
  if (typeof value.value === "string") return value.value;
  if (value.value === null || value.value === undefined) return "null";
  return JSON.stringify(value.value);
}


function capabilityIcon(kind: string) {
  if (kind === "logs") return Logs;
  if (kind === "reset") return RotateCcw;
  if (kind === "approve") return ShieldCheck;
  return FileOutput;
}


function ResourceActions({
  node,
  onOutput,
  onLogs,
  onApproval,
  onReset,
  cleanupRequired,
  approvals,
  resetInProgress,
}: Readonly<{
  node: ManageNode;
  onOutput: (targetId: string) => void;
  onLogs: (targetId: string) => void;
  onApproval: (targetId: string) => void;
  onReset: (targetId: string) => void;
  cleanupRequired: boolean;
  approvals: ApprovalCandidate[];
  resetInProgress: boolean;
}>) {
  const capabilities = node.capabilities.filter(
    (capability) => capability.kind !== "edit",
  );
  if (capabilities.length === 0) return null;
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
      if (resetBeforeRetry && capability.kind === "reset") return 0;
      if (capability.kind === "approve") return 1;
      if (
        capability.kind === "output"
        && approvalOutputs.has(capability.outputTargetId)
      ) {
        return 2;
      }
      if (capability.kind === "logs") return 3;
      if (capability.kind === "output") return 4;
      return 5;
    };
    return rank(left) - rank(right);
  });
  return (
    <div className="resource-actions" aria-label="Available actions">
      {orderedCapabilities.map((capability) => {
        const Icon = capabilityIcon(capability.kind);
        const label = capability.label ?? capability.kind;
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
        const disabledReason = capability.disabledReason;
        return (
          <button
            aria-label={label}
            className={
              resetBeforeRetry && capability.kind === "reset"
                ? "primary-button cleanup-action"
                : undefined
            }
            disabled={
              Boolean(disabledReason)
              || (capability.kind === "reset" && resetInProgress)
              || (!outputTarget && !logTarget && !actionTarget)
            }
            key={`${capability.kind}-${label}`}
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
                ? "Removal is already in progress"
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
      })}
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
    ? "Reset required before approval"
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
                ?? "Review the denied change before approving the retry."}
            </span>
          </div>
        </header>
      ) : null}
      {issues.map((issue, index) => (
        <div
          aria-label={issue.title ?? "Workflow failure"}
          className="resource-issue"
          key={`${issue.code ?? issue.message}-${index}`}
          role="alert"
        >
          <TriangleAlert aria-hidden="true" />
          <div>
            <h3>{issue.title ?? "Workflow step failed"}</h3>
            <p>{issue.message}</p>
            {issue.remedy ? (
              <div className="issue-remedy">
                <strong>Next step</strong>
                <span>{issue.remedy}</span>
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
                        <RotateCcw aria-hidden="true" />
                        Review reset &amp; resubmit
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
          <strong>{latest.label}</strong>
        </div>
      </header>
      <p>{latest.message}</p>
      <details>
        <summary>Failure details</summary>
        <pre>
          {latest.detail || "No additional failure detail was reported."}
        </pre>
      </details>
    </section>
  );
}


function ResourcePreapproval({
  gates,
  loading,
  onToggle,
  pendingNames,
}: Readonly<{
  gates: ApprovalGateSummary[];
  loading: boolean;
  onToggle: (
    gates: ApprovalGateSummary[],
    preapproved: boolean,
  ) => void;
  pendingNames: Set<string>;
}>) {
  const upcoming = gates.filter((gate) => (
    gate.state === "upcoming" || gate.state === "preapproved"
  ));
  const approvedCount = upcoming.filter((gate) => gate.approved).length;
  const checked = upcoming.length > 0 && approvedCount === upcoming.length;
  const mixed = approvedCount > 0 && !checked;
  const pending = gates.some((gate) => pendingNames.has(gate.name));
  const disabledReason = loading
    ? "Approval checkpoints are still loading."
    : gates.length === 0
      ? (
        "The submitted configuration does not define a manual approval "
        + "checkpoint for this resource."
      )
      : upcoming.length === 0
        ? (
          gates.find((gate) => gate.disabledReason)?.disabledReason
          ?? "This resource has no upcoming approval checkpoints."
        )
        : null;
  const disabled = Boolean(disabledReason) || pending;
  return (
    <section
      aria-label="Resource preapproval"
      className="resource-preapproval"
    >
      <div>
        <ShieldCheck aria-hidden="true" />
        <span>
          <strong>Preapprove upcoming checkpoints</strong>
          <small>
            {upcoming.length > 0
              ? `${approvedCount} of ${upcoming.length} upcoming ${
                upcoming.length === 1 ? "checkpoint" : "checkpoints"
              } preapproved`
              : disabledReason}
          </small>
        </span>
      </div>
      <button
        aria-checked={mixed ? "mixed" : checked}
        aria-label="Preapprove upcoming checkpoints"
        className={[
          "approval-toggle",
          checked ? "active" : "",
          mixed ? "mixed" : "",
        ].filter(Boolean).join(" ")}
        disabled={disabled}
        onClick={() => onToggle(upcoming, !checked)}
        role="switch"
        title={disabledReason ?? "Preapprove all upcoming checkpoints"}
        type="button"
      >
        <span aria-hidden="true">
          {pending ? <LoaderCircle className="spin" /> : null}
        </span>
        <b>{mixed ? "Some" : checked ? "On" : "Off"}</b>
      </button>
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
            {failed.length} step{failed.length === 1 ? "" : "s"} need
            attention
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
                    {new Date(step.activityAt).toLocaleString()}
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
              <th>Field</th>
              <th>Deployed</th>
              <th>Submitted</th>
              <th>Pending</th>
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


function RelationshipList({
  direction,
  onSelect,
  relationships,
}: Readonly<{
  direction: ManageRelationship["direction"];
  onSelect: (nodeId: string) => void;
  relationships: ManageRelationship[];
}>) {
  if (relationships.length === 0) return null;
  const prerequisite = direction === "requires";
  return (
    <section className="relationship-group">
      <h3>{prerequisite ? "Requires" : "Required by"}</h3>
      <div className="relationship-list">
        {relationships.map((relationship, index) => (
          <button
            aria-label={`${
              prerequisite ? "Open prerequisite" : "Open dependent"
            } ${relationship.targetName}, ${
              relationship.targetPhase ?? relationship.targetStatus
            }`}
            disabled={!relationship.targetId}
            key={`${relationship.direction}-${relationship.targetId ?? relationship.targetName}-${index}`}
            onClick={() => {
              if (relationship.targetId) onSelect(relationship.targetId);
            }}
            type="button"
          >
            <StatusIndicator
              status={relationship.targetPhase ?? relationship.targetStatus}
            />
            <span>
              <strong>{relationship.targetName}</strong>
              <small>
                {relationship.targetPhase ?? relationship.targetStatus}
                {relationship.targetPlural
                  ? ` · ${relationship.targetPlural}`
                  : ""}
              </small>
            </span>
            {relationship.targetId ? <ArrowRight aria-hidden="true" /> : null}
          </button>
        ))}
      </div>
    </section>
  );
}


function Relationships({
  node,
  onSelect,
}: Readonly<{
  node: ManageNode;
  onSelect: (nodeId: string) => void;
}>) {
  const relationships = node.relationships ?? [];
  const requires = relationships.filter(
    (relationship) => relationship.direction === "requires",
  );
  const requiredBy = relationships.filter(
    (relationship) => relationship.direction === "required-by",
  );
  if (relationships.length === 0) return null;
  return (
    <section
      aria-label="Runtime dependencies"
      className="workspace-section relationship-section"
    >
      <RelationshipList
        direction="requires"
        onSelect={onSelect}
        relationships={requires}
      />
      <RelationshipList
        direction="required-by"
        onSelect={onSelect}
        relationships={requiredBy}
      />
    </section>
  );
}


export function ResourceWorkspace({
  node,
  navigationBackLabel,
  onSelect,
  onEdit,
  onNavigateBack,
  onRequestApproval,
  approvalGates = [],
  approvalGatesLoading = false,
  approvals = [],
  onTogglePreapprovals,
  operations = [],
  pendingPreapprovalNames = new Set<string>(),
  resetInProgress = false,
  workflowSteps = [],
}: Readonly<{
  node: ManageNode;
  navigationBackLabel?: string | null;
  onSelect: (nodeId: string) => void;
  onEdit?: () => void;
  onNavigateBack?: () => void;
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
}>) {
  const [outputTarget, setOutputTarget] = useState<string | null>(null);
  const [logTarget, setLogTarget] = useState<string | null>(null);
  const [pendingAction, setPendingAction] =
    useState<PendingAction | null>(null);
  useEffect(() => {
    setOutputTarget(null);
    setLogTarget(null);
    setPendingAction(null);
  }, [node.id]);
  const cleanupRequired = (
    node.valueSummary === "Orphaned; cleanup required"
  );
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
            <span className="resource-kind">{node.kind.replace("-", " ")}</span>
            <h2>{node.label}</h2>
            <p>{node.description ?? node.id}</p>
          </div>
        </div>
        <div className="workspace-states">
          <span className={`phase-badge status-${String(
            node.phase ?? node.status,
          ).toLocaleLowerCase()}`}>
            {node.phase ?? node.status}
          </span>
          {node.status === "blocked" && node.phase !== "Blocked" ? (
            <span className="phase-badge status-blocked">
              Update blocked
            </span>
          ) : null}
        </div>
      </header>
      <ResourceActions
        approvals={approvals.filter((candidate) => candidate.nodeId === node.id)}
        cleanupRequired={cleanupRequired}
        node={node}
        onLogs={(targetId) => {
          setOutputTarget(null);
          setLogTarget(targetId);
        }}
        onApproval={(targetId) => onRequestApproval?.(targetId)}
        onOutput={(targetId) => {
          setLogTarget(null);
          setOutputTarget(targetId);
        }}
        onReset={(targetId) => setPendingAction({
          kind: "reset",
          targetId,
        })}
        resetInProgress={resetInProgress}
      />
      {onTogglePreapprovals ? (
        <ResourcePreapproval
          gates={approvalGates.filter((gate) => (
            gate.category === "checkpoint"
            && gate.resourceId === node.id
          ))}
          loading={approvalGatesLoading}
          onToggle={onTogglePreapprovals}
          pendingNames={pendingPreapprovalNames}
        />
      ) : null}
      {cleanupRequired || resetInProgress ? (
        <section
          aria-label={resetInProgress ? "Removal in progress" : "Cleanup required"}
          className={`cleanup-notice ${resetInProgress ? "removing" : ""}`}
        >
          {resetInProgress
            ? <LoaderCircle className="spin" aria-hidden="true" />
            : <RotateCcw aria-hidden="true" />}
          <div>
            <h3>
              {resetInProgress ? "Removal in progress" : "Cleanup required"}
            </h3>
            <p>
              {resetInProgress
                ? "The reset operation is removing this resource and its planned dependents."
                : (
                  "This resource is still deployed but is no longer in the "
                  + "submitted configuration. Reset it to perform the "
                  + "dependency-safe cleanup."
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
      <Relationships node={node} onSelect={onSelect} />
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
          onApprovalStarted={() => {
            void 0;
          }}
          onClose={() => setOutputTarget(null)}
          targetId={outputTarget}
        />
      ) : null}
      {pendingAction ? (
        <ResetDialog
          onClose={() => setPendingAction(null)}
          targetId={pendingAction.targetId}
        />
      ) : null}
      <dl className="facts-grid">
        <div>
          <dt>Status</dt>
          <dd>{node.status}</dd>
        </div>
        <div>
          <dt>Current state</dt>
          <dd>{node.valueSummary ?? node.phase ?? "Unknown"}</dd>
        </div>
        {node.details
          .filter((detail) => detail.kind !== "dependency")
          .slice(0, 4)
          .map((detail) => (
          <div key={`${detail.label}-${detail.kind}`}>
            <dt>{detail.label}</dt>
            <dd>{displayValue({ present: true, value: detail.value })}</dd>
          </div>
          ))}
      </dl>
      <Findings node={node} />
      <Comparisons node={node} />
    </article>
  );
}
