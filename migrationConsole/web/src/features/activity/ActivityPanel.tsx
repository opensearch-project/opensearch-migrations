import {
  CircleAlert,
  GitBranch,
  LoaderCircle,
} from "lucide-react";

import type {
  ManageNode,
  ManageSnapshot,
  Operation,
} from "../../api/client";
import type { ApprovalCandidate } from "../actions/approvals";
import { WorkflowDependencyGraph } from "./WorkflowDependencyGraph";


function exceptionalOperations(operations: Operation[]): Operation[] {
  return operations.filter((operation) => (
    operation.status !== "succeeded"
    && operation.targetIds.length === 0
  ));
}


export function ActivityPanel({
  snapshot,
  selectedNode,
  operations,
  onSelectNode,
  approvals,
  onReviewApproval,
  onViewApprovalOutput,
}: Readonly<{
  snapshot: ManageSnapshot;
  selectedNode: ManageNode | null;
  operations: Operation[];
  onSelectNode: (nodeId: string) => void;
  approvals: ApprovalCandidate[];
  onReviewApproval: (targetId: string) => void;
  onViewApprovalOutput: (approval: ApprovalCandidate) => void;
}>) {
  const resources = Object.values(snapshot.nodes).filter(
    (node) => node.kind === "resource",
  );
  const blockerIds = new Set(resources.filter((node) => (
    node.status === "error"
    || node.status === "blocked"
    || node.phase === "Error"
    || node.phase === "Failed"
  )).map((node) => node.id));
  resources.forEach((node) => {
    (node.relationships ?? []).forEach((relationship) => {
      if (
        relationship.direction === "requires"
        && (
          relationship.targetStatus === "error"
          || relationship.targetStatus === "blocked"
        )
        && relationship.targetId
      ) {
        blockerIds.add(relationship.targetId);
      }
    });
  });
  const waiting = resources.filter((node) => (
    (node.relationships ?? []).some((relationship) => (
      relationship.direction === "requires"
      && relationship.targetStatus !== "ok"
    ))
  ));
  const actionCount = new Set([
    ...blockerIds,
    ...approvals.map((approval) => approval.nodeId),
  ]).size;
  const globalOperations = exceptionalOperations(operations);
  return (
    <aside className="activity-panel">
      <header>
        <GitBranch aria-hidden="true" />
        <div>
          <h2>Workflow dependencies</h2>
          <span>Resources, active steps, and blockers</span>
        </div>
      </header>
      {blockerIds.size > 0 || approvals.length > 0 || waiting.length > 0 ? (
        <section className="workflow-health" aria-label="Workflow blockers">
          <strong>
            {actionCount} action{
              actionCount === 1 ? "" : "s"
            } {actionCount === 1 ? "needs" : "need"} attention
          </strong>
          {waiting.length > 0 ? (
            <span>
              {waiting.length} downstream {
                waiting.length === 1 ? "resource is" : "resources are"
              } waiting
            </span>
          ) : null}
        </section>
      ) : null}
      {globalOperations.map((operation) => (
        <section
          className={`workflow-global-operation operation-${operation.status}`}
          key={operation.id}
        >
          {operation.status === "failed" ? (
            <CircleAlert aria-hidden="true" />
          ) : (
            <LoaderCircle className="spin" aria-hidden="true" />
          )}
          <div>
            <strong>{operation.label}</strong>
            <span>{operation.message}</span>
          </div>
        </section>
      ))}
      <WorkflowDependencyGraph
        approvals={approvals}
        onReviewApproval={onReviewApproval}
        onViewApprovalOutput={onViewApprovalOutput}
        onSelectNode={onSelectNode}
        operations={operations}
        selectedNodeId={selectedNode?.id ?? null}
        snapshot={snapshot}
      />
    </aside>
  );
}
