import { useEffect, useState } from "react";
import {
  CheckCircle2,
  FileOutput,
  Logs,
  RotateCcw,
  ShieldCheck,
  TriangleAlert,
} from "lucide-react";

import type { ManageNode } from "../../api/client";
import { OutputPanel } from "../output/OutputPanel";
import {
  ApprovalDialog,
  ResetDialog,
} from "../actions/ResourceActionDialogs";


interface PendingAction {
  kind: "approve" | "reset";
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
  onAction,
}: {
  node: ManageNode;
  onOutput: (targetId: string) => void;
  onAction: (action: PendingAction) => void;
}) {
  const capabilities = node.capabilities.filter(
    (capability) => capability.kind !== "edit",
  );
  if (capabilities.length === 0) return null;
  return (
    <div className="resource-actions" aria-label="Available actions">
      {capabilities.map((capability) => {
        const Icon = capabilityIcon(capability.kind);
        const label = capability.label ?? capability.kind;
        const outputTarget = (
          capability.kind === "output"
            ? capability.outputTargetId
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
        return (
          <button
            aria-label={label}
            disabled={!outputTarget && !actionTarget}
            key={`${capability.kind}-${label}`}
            onClick={() => {
              if (outputTarget) onOutput(outputTarget);
              else if (approvalTarget) {
                onAction({ kind: "approve", targetId: approvalTarget });
              } else if (resetTarget) {
                onAction({ kind: "reset", targetId: resetTarget });
              }
            }}
            title={outputTarget || actionTarget
              ? label
              : "This action is enabled in a later phase"}
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


function Diagnostics({ node }: { node: ManageNode }) {
  return (
    <section className="workspace-section">
      <h3>Diagnostics</h3>
      {node.diagnostics.length === 0 ? (
        <div className="inline-empty">
          <CheckCircle2 aria-hidden="true" />
          No diagnostics for this resource.
        </div>
      ) : (
        <div className="diagnostic-list">
          {node.diagnostics.map((diagnostic, index) => (
            <details className={`diagnostic diagnostic-${diagnostic.severity}`} key={`${diagnostic.message}-${index}`}>
              <summary>
                <TriangleAlert aria-hidden="true" />
                <span>{diagnostic.message}</span>
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


function Comparisons({ node }: { node: ManageNode }) {
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


export function ResourceWorkspace({
  node,
}: {
  node: ManageNode;
}) {
  const [outputTarget, setOutputTarget] = useState<string | null>(null);
  const [pendingAction, setPendingAction] =
    useState<PendingAction | null>(null);
  useEffect(() => {
    setOutputTarget(null);
    setPendingAction(null);
  }, [node.id]);
  return (
    <article className="workspace">
      <header className="workspace-header">
        <div>
          <span className="resource-kind">{node.kind.replace("-", " ")}</span>
          <h2>{node.label}</h2>
          <p>{node.description ?? node.id}</p>
        </div>
        <span className={`phase-badge status-${node.status}`}>
          {node.phase ?? node.status}
        </span>
      </header>
      <ResourceActions
        node={node}
        onAction={setPendingAction}
        onOutput={setOutputTarget}
      />
      {outputTarget ? (
        <OutputPanel
          onClose={() => setOutputTarget(null)}
          targetId={outputTarget}
        />
      ) : null}
      {pendingAction?.kind === "approve" ? (
        <ApprovalDialog
          onClose={() => setPendingAction(null)}
          targetId={pendingAction.targetId}
        />
      ) : null}
      {pendingAction?.kind === "reset" ? (
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
        {node.details.slice(0, 4).map((detail) => (
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
