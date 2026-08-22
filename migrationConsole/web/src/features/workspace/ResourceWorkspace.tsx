import { useEffect, useState } from "react";
import {
  ArrowRight,
  CheckCircle2,
  FileOutput,
  LoaderCircle,
  Logs,
  Pencil,
  RotateCcw,
  ShieldCheck,
  TriangleAlert,
} from "lucide-react";

import type {
  ManageNode,
  ManageRelationship,
} from "../../api/client";
import { OutputPanel } from "../output/OutputPanel";
import { LogPanel } from "../logviewer/LogPanel";
import { ResetDialog } from "../actions/ResourceActionDialogs";
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
  resetInProgress,
}: Readonly<{
  node: ManageNode;
  onOutput: (targetId: string) => void;
  onLogs: (targetId: string) => void;
  onApproval: (targetId: string) => void;
  onReset: (targetId: string) => void;
  cleanupRequired: boolean;
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
  const orderedCapabilities = [...capabilities].sort((left, right) => {
    if (!resetBeforeRetry) return 0;
    const order: Record<string, number> = {
      reset: 0,
      logs: 1,
      approve: 2,
      output: 3,
    };
    return (order[left.kind] ?? 4) - (order[right.kind] ?? 4);
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


function Diagnostics({ node }: Readonly<{ node: ManageNode }>) {
  const diagnostics = node.diagnostics.filter((diagnostic) => (
    diagnostic.source !== "workflow-apply"
    && diagnostic.source !== "workflow-step"
  ));
  if (diagnostics.length === 0 && node.diagnostics.length > 0) return null;
  return (
    <section className="workspace-section">
      <h3>Diagnostics</h3>
      {diagnostics.length === 0 ? (
        <div className="inline-empty">
          <CheckCircle2 aria-hidden="true" />
          No diagnostics for this resource.
        </div>
      ) : (
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
      )}
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
  onSelect,
  onEdit,
  onRequestApproval,
  resetInProgress = false,
}: Readonly<{
  node: ManageNode;
  onSelect: (nodeId: string) => void;
  onEdit?: () => void;
  onRequestApproval?: (targetId: string) => void;
  resetInProgress?: boolean;
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
        <div>
          <span className="resource-kind">{node.kind.replace("-", " ")}</span>
          <h2>{node.label}</h2>
          <p>{node.description ?? node.id}</p>
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
      <Relationships node={node} onSelect={onSelect} />
      {logTarget ? (
        <LogPanel
          nodeId={node.id}
          onClose={() => setLogTarget(null)}
        />
      ) : null}
      {outputTarget ? (
        <OutputPanel
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
      <Diagnostics node={node} />
      <Comparisons node={node} />
    </article>
  );
}
