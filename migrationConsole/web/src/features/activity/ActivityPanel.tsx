import {
  ArrowDown,
  CheckCircle2,
  CircleDashed,
  CircleX,
  GitBranch,
  LoaderCircle,
  ShieldCheck,
} from "lucide-react";

import type {
  ManageNode,
  ManageRelationship,
  ManageSnapshot,
  Operation,
} from "../../api/client";
import type { ApprovalCandidate } from "../actions/approvals";
import { StatusIndicator } from "../status/StatusIndicator";
import { statusLabel } from "../status/status";


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


function relationships(
  node: ManageNode | null,
  direction: ManageRelationship["direction"],
): ManageRelationship[] {
  return (node?.relationships ?? []).filter(
    (relationship) => relationship.direction === direction,
  );
}


function relationshipState(
  snapshot: ManageSnapshot,
  relationship: ManageRelationship,
) {
  const target = relationship.targetId
    ? snapshot.nodes[relationship.targetId]
    : undefined;
  return {
    id: target?.id ?? relationship.targetId,
    label: target?.label ?? relationship.targetName,
    phase: target?.phase ?? relationship.targetPhase,
    resourcePlural: target?.resourcePlural ?? relationship.targetPlural,
    status: target?.status ?? relationship.targetStatus,
  };
}


function GraphRelationship({
  direction,
  relationship,
  snapshot,
  approvals,
  onSelectNode,
}: Readonly<{
  direction: "dependent" | "prerequisite";
  relationship: ManageRelationship;
  snapshot: ManageSnapshot;
  approvals: ApprovalCandidate[];
  onSelectNode: (nodeId: string) => void;
}>) {
  const target = relationshipState(snapshot, relationship);
  const state = target.phase ?? statusLabel(target.status);
  const approval = approvals.some(
    (candidate) => candidate.nodeId === target.id,
  );
  return (
    <button
      aria-label={`Show ${direction} ${target.label}, ${state}`}
      className={`dependency-node dependency-${direction}`}
      disabled={!target.id}
      onClick={() => {
        if (target.id) onSelectNode(target.id);
      }}
      type="button"
    >
      <StatusIndicator status={target.status} />
      <span>
        <strong>{target.label}</strong>
        <small>
          {target.resourcePlural ? `${target.resourcePlural} · ` : ""}
          {state}
        </small>
        {approval ? (
          <em>
            <ShieldCheck aria-hidden="true" />
            Approval required
          </em>
        ) : null}
      </span>
    </button>
  );
}


function OperationState({ operation }: Readonly<{ operation: Operation }>) {
  return (
    <div
      className={`activity-step operation operation-${operation.status}`}
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
  );
}


function RunState({
  operations,
  steps,
}: Readonly<{
  operations: Operation[];
  steps: ManageNode[];
}>) {
  if (operations.length === 0 && steps.length === 0) return null;
  return (
    <section aria-label="Selected resource run" className="dependency-run">
      <h3>Run</h3>
      {operations.map((operation) => (
        <OperationState key={operation.id} operation={operation} />
      ))}
      {steps.map((step) => (
        <div className="activity-step" key={step.id}>
          <StatusIndicator status={step.phase ?? step.status} />
          <div>
            <strong>{step.label}</strong>
            <span>{step.phase ?? statusLabel(step.status)}</span>
          </div>
        </div>
      ))}
    </section>
  );
}


export function ActivityPanel({
  snapshot,
  selectedNode,
  operations,
  onSelectNode,
  approvals,
  onReviewApproval,
}: Readonly<{
  snapshot: ManageSnapshot;
  selectedNode: ManageNode | null;
  operations: Operation[];
  onSelectNode: (nodeId: string) => void;
  approvals: ApprovalCandidate[];
  onReviewApproval: (targetId: string) => void;
}>) {
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
  const prerequisites = relationships(selectedNode, "requires");
  const dependents = relationships(selectedNode, "required-by");
  const selectedOperations = selectedNode
    ? operations.filter((operation) => (
      operation.targetIds.includes(selectedNode.id)
    ))
    : [];
  const otherOperations = operations.filter(
    (operation) => !selectedOperations.includes(operation),
  );
  const selectedApproval = approvals.find(
    (candidate) => candidate.nodeId === selectedNode?.id,
  );
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
        <GitBranch aria-hidden="true" />
        <div>
          <h2>Run &amp; dependencies</h2>
          <span>Selected resource context</span>
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
              } waiting.
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
      {selectedNode ? (
        <section
          aria-label={`Dependency graph for ${selectedNode.label}`}
          className="dependency-graph"
        >
          {prerequisites.length > 0 ? (
            <div className="dependency-level">
              <span className="dependency-level-label">Requires</span>
              {prerequisites.map((relationship) => (
                <GraphRelationship
                  approvals={approvals}
                  direction="prerequisite"
                  key={`${relationship.targetId}-${relationship.targetName}`}
                  onSelectNode={onSelectNode}
                  relationship={relationship}
                  snapshot={snapshot}
                />
              ))}
            </div>
          ) : null}
          {prerequisites.length > 0 ? (
            <div className="dependency-connector" aria-hidden="true">
              <ArrowDown />
            </div>
          ) : null}
          <div className="dependency-node dependency-selected">
            <StatusIndicator
              status={selectedNode.phase ?? selectedNode.status}
            />
            <span>
              <small>Selected</small>
              <strong>{selectedNode.label}</strong>
              <small>
                {selectedNode.resourcePlural
                  ? `${selectedNode.resourcePlural} · `
                  : ""}
                {selectedNode.phase ?? statusLabel(selectedNode.status)}
              </small>
              {selectedApproval ? (
                <button
                  onClick={() => onReviewApproval(selectedApproval.targetId)}
                  type="button"
                >
                  <ShieldCheck aria-hidden="true" />
                  Review approval
                </button>
              ) : null}
            </span>
          </div>
          <RunState operations={selectedOperations} steps={steps} />
          {dependents.length > 0 ? (
            <div className="dependency-connector" aria-hidden="true">
              <ArrowDown />
            </div>
          ) : null}
          {dependents.length > 0 ? (
            <div className="dependency-level">
              <span className="dependency-level-label">Required by</span>
              {dependents.map((relationship) => (
                <GraphRelationship
                  approvals={approvals}
                  direction="dependent"
                  key={`${relationship.targetId}-${relationship.targetName}`}
                  onSelectNode={onSelectNode}
                  relationship={relationship}
                  snapshot={snapshot}
                />
              ))}
            </div>
          ) : null}
        </section>
      ) : (
        <p className="activity-empty">Select a resource to inspect its run.</p>
      )}
      {otherOperations.length > 0 ? (
        <section aria-label="Other operations" className="activity-steps">
          <h3>Other operations</h3>
          {otherOperations.map((operation) => (
            <OperationState key={operation.id} operation={operation} />
          ))}
        </section>
      ) : null}
      {!selectedNode && operations.length === 0 ? (
        <p className="activity-empty">No active operations.</p>
      ) : null}
    </aside>
  );
}
