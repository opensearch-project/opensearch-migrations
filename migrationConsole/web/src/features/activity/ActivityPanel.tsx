import {
  Activity,
  CheckCircle2,
  CircleDashed,
  CircleX,
  LoaderCircle,
} from "lucide-react";

import type {
  ManageNode,
  ManageSnapshot,
  Operation,
} from "../../api/client";


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
}: {
  snapshot: ManageSnapshot;
  selectedNode: ManageNode | null;
  operations: Operation[];
}) {
  const steps = workflowSteps(snapshot, selectedNode);
  return (
    <aside className="activity-panel">
      <header>
        <Activity aria-hidden="true" />
        <div>
          <h2>Activity</h2>
          <span>Current workflow</span>
        </div>
      </header>
      {snapshot.workflow ? (
        <div className="workflow-summary">
          <span className={`status-dot status-${snapshot.workflow.phase.toLocaleLowerCase()}`} />
          <div>
            <strong>{snapshot.workflow.name}</strong>
            <span>{snapshot.workflow.phase}</span>
          </div>
        </div>
      ) : (
        <p className="activity-empty">No active Argo workflow.</p>
      )}
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
