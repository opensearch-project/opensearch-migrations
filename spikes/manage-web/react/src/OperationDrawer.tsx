import { CheckCircle2, CircleDashed, Clock3 } from "lucide-react";
import { type OperationState } from "@manage-spike/shared";

interface OperationDrawerProps {
  operations: ReadonlyArray<OperationState>;
}

function OperationIcon({ operation }: { operation: OperationState }) {
  if (operation.state === "succeeded") {
    return <CheckCircle2 aria-hidden="true" />;
  }
  if (operation.state === "running") {
    return <CircleDashed className="spin-slow" aria-hidden="true" />;
  }
  return <Clock3 aria-hidden="true" />;
}

export function OperationDrawer({ operations }: OperationDrawerProps) {
  const activeCount = operations.filter(
    (operation) => operation.state !== "succeeded",
  ).length;

  return (
    <aside className="operations-drawer" aria-label="Operations">
      <div className="operations-header">
        <div>
          <h2>Operations</h2>
          <span>{activeCount} active</span>
        </div>
        <span className="live-indicator">
          <span />
          Live
        </span>
      </div>
      <div className="operation-list">
        {operations.map((operation) => (
          <article className="operation-item" key={operation.id}>
            <div className="operation-icon">
              <OperationIcon operation={operation} />
            </div>
            <div className="operation-content">
              <div className="operation-title">
                <strong>{operation.label}</strong>
                <span>{operation.state}</span>
              </div>
              <p>{operation.phase}</p>
              <div
                className="progress-track"
                role="progressbar"
                aria-label={`${operation.label} progress`}
                aria-valuenow={operation.progress}
                aria-valuemin={0}
                aria-valuemax={100}
              >
                <span style={{ width: `${operation.progress}%` }} />
              </div>
            </div>
          </article>
        ))}
      </div>
    </aside>
  );
}
