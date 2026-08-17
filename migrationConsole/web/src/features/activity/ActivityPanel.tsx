import {
  Activity,
  CheckCircle2,
  CircleDashed,
  CircleX,
  LoaderCircle,
  ShieldCheck,
} from "lucide-react";

import type {
  ManageNode,
  ManageSnapshot,
  Operation,
} from "../../api/client";
import type { ApprovalCandidate } from "../actions/approvals";
import { StatusIndicator } from "../status/StatusIndicator";


function workflowSteps(
  snapshot: ManageSnapshot,
  node: ManageNode | null,
): ManageNode[] {
  if (!node) return [];
  const result: ManageNode[] = [];
  const visit = (nodeId: string) => {
    const current = snapshot.nodes[nodeId];
    if (!current) return;
    if (current.kind === "workflow-step") result.push(current);
    current.childIds.forEach(visit);
  };
  node.childIds.forEach(visit);
  return result;
}


export function ActivityPanel({
  snapshot,
  selectedNode,
  operations,
  onSelectNode,
  approvals,
  onReviewApproval,
}: {
  snapshot: ManageSnapshot;
  selectedNode: ManageNode | null;
  operations: Operation[];
  onSelectNode: (nodeId: string) => void;
  approvals: ApprovalCandidate[];
  onReviewApproval: (targetId: string) => void;
}) {
  const steps = workflowSteps(snapshot, selectedNode);
  const resources = Object.values(snapshot.nodes).filter(
    (node) => node.kind === "resource",
  );
  const failedResources = resources.filter((node) => (
    node.phase === "Failed"
    || node.phase === "Error"
  ));
  const waitingResources = resources.filter((node) => (
    (node.relationships ?? []).some((relationship) => (
      relationship.direction === "requires"
      && relationship.targetStatus !== "ok"
    ))
  ));
  const currentBlocker = failedResources[0] ?? null;
  const blockerStep = currentBlocker
    ? workflowSteps(snapshot, currentBlocker).find(
      (step) => step.status === "error",
    )
    : null;
  const activeSubmit = operations.find((operation) => (
    operation.kind === "submit"
    && (
      operation.status === "queued"
      || operation.status === "running"
      || operation.status === "waiting"
    )
  ));
  return (
    <aside className="activity-panel">
      <header>
        <Activity aria-hidden="true" />
        <div>
          <h2>Activity</h2>
          <span>
            {operations.length > 0
              ? "Workflow and operations"
              : "Current workflow"}
          </span>
        </div>
      </header>
      {snapshot.workflow ? (
        <div className="workflow-summary">
          <StatusIndicator status={snapshot.workflow.phase} />
          <div>
            <strong>{snapshot.workflow.name}</strong>
            <span>{snapshot.workflow.phase}</span>
          </div>
        </div>
      ) : (
        <p className="activity-empty">
          {activeSubmit?.status === "waiting"
            ? "Waiting for submitted workflow."
            : activeSubmit
              ? "Submitting workflow."
          : "No active Argo workflow."}
        </p>
      )}
      {approvals.length > 0 ? (
        <section
          aria-label="Pending approvals"
          className="approval-activity"
        >
          <ShieldCheck aria-hidden="true" />
          <div>
            <strong>Approval required</strong>
            <span>
              {approvals.length} {
                approvals.length === 1 ? "gate is" : "gates are"
              } waiting for an explicit decision.
            </span>
          </div>
          <button
            aria-label={`Review approval for ${approvals[0].nodeLabel}`}
            onClick={() => onReviewApproval(approvals[0].targetId)}
            type="button"
          >
            Review
          </button>
        </section>
      ) : null}
      {snapshot.workflow && (
        failedResources.length > 0
        || waitingResources.length > 0
      ) ? (
        <section className="workflow-health" aria-label="Workflow blockers">
          {failedResources.length > 0 ? (
            <strong>
              {snapshot.workflow.phase} with {failedResources.length} failed {
                failedResources.length === 1 ? "resource" : "resources"
              }
            </strong>
          ) : (
            <strong>{snapshot.workflow.phase}</strong>
          )}
          {waitingResources.length > 0 ? (
            <span>
              {waitingResources.length} downstream {
                waitingResources.length === 1 ? "resource" : "resources"
              } waiting
            </span>
          ) : null}
          {currentBlocker ? (
            <button
              aria-label={`View blocker ${currentBlocker.label}`}
              onClick={() => onSelectNode(currentBlocker.id)}
              type="button"
            >
              <CircleX aria-hidden="true" />
              <span>
                Current blocker: {currentBlocker.label}
                {blockerStep ? ` / ${blockerStep.label}` : ""}
              </span>
            </button>
          ) : null}
        </section>
      ) : null}
      <div className="activity-steps">
        {operations.map((operation) => (
          <div
            className={`activity-step operation operation-${operation.status}`}
            key={operation.id}
          >
            {operation.status === "failed" ? (
              <CircleX aria-hidden="true" />
            ) : operation.status === "succeeded" ? (
              <CheckCircle2 aria-hidden="true" />
            ) : operation.status === "waiting" ? (
              <CircleDashed aria-hidden="true" />
            ) : (
              <LoaderCircle className="spin" aria-hidden="true" />
            )}
            <div>
              <strong>{operation.label}</strong>
              <span>{operation.message}</span>
              {operation.detail ? <small>{operation.detail}</small> : null}
            </div>
          </div>
        ))}
        {steps.map((step) => (
          <div className="activity-step" key={step.id}>
            {step.status === "ok" ? (
              <CheckCircle2 aria-hidden="true" />
            ) : (
              <CircleDashed aria-hidden="true" />
            )}
            <div>
              <strong>{step.label}</strong>
              <span>{step.phase ?? step.status}</span>
            </div>
          </div>
        ))}
        {selectedNode && steps.length === 0 && operations.length === 0 ? (
          <p className="activity-empty">No active steps for this selection.</p>
        ) : null}
      </div>
    </aside>
  );
}
