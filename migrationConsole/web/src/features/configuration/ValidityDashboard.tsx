import {
  AlertTriangle,
  Check,
  CircleDashed,
  LoaderCircle,
  RefreshCw,
} from "lucide-react";
import {
  useEffect,
  useMemo,
  useState,
} from "react";

import {
  ConnectivityCheckDetails,
  connectivityOverallStatus,
  connectivityStatusLabel,
  type ConnectivityStatus,
  type ConnectivityTargetState,
} from "./connectivityChecks";
import {
  type EnvironmentDiagnosticLifecycle,
  type EnvironmentReferenceGroup,
} from "./environmentDiagnostics";


type ValidityStatus = ConnectivityStatus | EnvironmentDiagnosticLifecycle;


interface ConnectivityGroup {
  id: string;
  label: string;
  states: ConnectivityTargetState[];
  status: ConnectivityStatus;
}


function statusLabel(status: ValidityStatus): string {
  if (status === "not-checked") return "Not checked";
  if (status === "warning") return "Warning";
  if (status === "error") return "Error";
  return connectivityStatusLabel(status);
}


function statusClass(status: ValidityStatus): string {
  if (status === "error") return "failed";
  if (status === "warning") return "partially_verified";
  if (status === "not-checked") return "not_checked";
  return status;
}


function statusIcon(status: ValidityStatus) {
  if (status === "checking" || status === "stale") {
    return <LoaderCircle className="spin" aria-hidden="true" />;
  }
  if (
    status === "valid"
    || status === "not_applicable"
    || status === "partially_verified"
  ) {
    return <Check aria-hidden="true" />;
  }
  if (status === "failed" || status === "error" || status === "warning") {
    return <AlertTriangle aria-hidden="true" />;
  }
  return <CircleDashed aria-hidden="true" />;
}


function checkedSummary(names: string[]): string {
  const shown = names.slice(0, 3).join(", ");
  const omitted = names.length > 3 ? ", ..." : "";
  return `Checked ${shown}${omitted} (${names.length} checked).`;
}


function connectivityGroupLabel(
  kind: ConnectivityTargetState["target"]["kind"],
): string {
  switch (kind) {
    case "source": return "Source Clusters";
    case "target": return "Target Clusters";
    case "repository": return "Snapshot Repositories";
  }
}


function EnvironmentDetails({
  group,
}: Readonly<{ group: EnvironmentReferenceGroup }>) {
  const names = group.references.map(({ name }) => name);
  return (
    <div className="validity-detail-content">
      <p>{checkedSummary(names)}</p>
      <ul className="validity-reference-list">
        {group.references.map((reference) => (
          <li key={reference.id}>
            <code>{reference.name}</code>
            <span>{reference.displayName}</span>
          </li>
        ))}
      </ul>
      {group.diagnostics.length > 0 ? (
        <ul className="validity-diagnostic-list">
          {group.diagnostics.map((diagnostic, index) => (
            <li
              className={`status-${diagnostic.severity}`}
              key={`${diagnostic.path.join(".")}:${diagnostic.message}:${index}`}
            >
              {diagnostic.message}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}


function ConnectivityDetails({
  group,
  onCheck,
}: Readonly<{
  group: ConnectivityGroup;
  onCheck: (targetIds: string[]) => void;
}>) {
  return (
    <div className="validity-connectivity-list">
      {group.states.map((state) => (
        <article
          className={`validity-connectivity-target status-${state.status}`}
          key={state.target.id}
        >
          <header>
            <div>
              <strong>{state.target.label}</strong>
              <span>{connectivityStatusLabel(state.status)}</span>
            </div>
            <button
              disabled={state.status === "checking"}
              onClick={() => onCheck([state.target.id])}
              type="button"
            >
              <RefreshCw aria-hidden="true" />
              {state.check ? "Recheck" : "Check"}
            </button>
          </header>
          <ConnectivityCheckDetails state={state} />
        </article>
      ))}
    </div>
  );
}


export function ValidityDashboard({
  connectivityLoading,
  connectivityProblem,
  connectivityStates,
  environmentGroups,
  onCheckConnectivity,
}: Readonly<{
  connectivityLoading: boolean;
  connectivityProblem: string;
  connectivityStates: ConnectivityTargetState[];
  environmentGroups: EnvironmentReferenceGroup[];
  onCheckConnectivity: (targetIds: string[]) => void;
}>) {
  const connectivityGroups = useMemo(() => {
    const byKind = new Map<
      ConnectivityTargetState["target"]["kind"],
      ConnectivityTargetState[]
    >();
    connectivityStates.forEach((state) => {
      byKind.set(state.target.kind, [
        ...(byKind.get(state.target.kind) ?? []),
        state,
      ]);
    });
    return [...byKind.entries()].map(([kind, states]): ConnectivityGroup => ({
      id: `connectivity:${kind}`,
      label: connectivityGroupLabel(kind),
      states,
      status: connectivityOverallStatus(states),
    }));
  }, [connectivityStates]);
  const itemIds = useMemo(
    () => [
      ...environmentGroups.map(({ id }) => id),
      ...connectivityGroups.map(({ id }) => id),
    ],
    [connectivityGroups, environmentGroups],
  );
  const [expandedId, setExpandedId] = useState<string | null>(null);
  useEffect(() => {
    if (expandedId && !itemIds.includes(expandedId)) setExpandedId(null);
  }, [expandedId, itemIds]);

  if (
    environmentGroups.length === 0
    && connectivityGroups.length === 0
    && !connectivityLoading
    && !connectivityProblem
  ) {
    return null;
  }
  const expandedEnvironment = environmentGroups.find(
    ({ id }) => id === expandedId,
  );
  const expandedConnectivity = connectivityGroups.find(
    ({ id }) => id === expandedId,
  );
  return (
    <section className="validity-dashboard" aria-label="Configuration checks">
      <div className="validity-tabs" role="tablist">
        {environmentGroups.map((group) => (
          <button
            aria-controls={`${group.id}:panel`}
            aria-expanded={expandedId === group.id}
            aria-selected={expandedId === group.id}
            className={`validity-tab status-${statusClass(group.status)}`}
            id={`${group.id}:tab`}
            key={group.id}
            onClick={() => setExpandedId(
              (current) => current === group.id ? null : group.id,
            )}
            role="tab"
            type="button"
          >
            {statusIcon(group.status)}
            <span>
              <strong>{group.label}</strong>
              <small>
                {statusLabel(group.status)} · {group.references.length}
              </small>
            </span>
          </button>
        ))}
        {connectivityGroups.map((group) => (
          <button
            aria-controls={`${group.id}:panel`}
            aria-expanded={expandedId === group.id}
            aria-selected={expandedId === group.id}
            className={`validity-tab status-${statusClass(group.status)}`}
            id={`${group.id}:tab`}
            key={group.id}
            onClick={() => setExpandedId(
              (current) => current === group.id ? null : group.id,
            )}
            role="tab"
            type="button"
          >
            {statusIcon(group.status)}
            <span>
              <strong>{group.label}</strong>
              <small>
                {statusLabel(group.status)} · {group.states.length}
              </small>
            </span>
          </button>
        ))}
        {connectivityLoading && connectivityGroups.length === 0 ? (
          <div className="validity-tab status-checking" role="status">
            <LoaderCircle className="spin" aria-hidden="true" />
            <span>
              <strong>Connectivity</strong>
              <small>Loading checks</small>
            </span>
          </div>
        ) : null}
        {connectivityProblem && connectivityGroups.length === 0 ? (
          <div className="validity-tab status-failed" role="alert">
            <AlertTriangle aria-hidden="true" />
            <span>
              <strong>Connectivity</strong>
              <small>Unavailable</small>
            </span>
          </div>
        ) : null}
      </div>
      {expandedEnvironment ? (
        <div
          aria-labelledby={`${expandedEnvironment.id}:tab`}
          className={`validity-tab-panel status-${statusClass(
            expandedEnvironment.status,
          )}`}
          id={`${expandedEnvironment.id}:panel`}
          role="tabpanel"
        >
          <EnvironmentDetails group={expandedEnvironment} />
        </div>
      ) : null}
      {expandedConnectivity ? (
        <div
          aria-labelledby={`${expandedConnectivity.id}:tab`}
          className={`validity-tab-panel status-${statusClass(
            expandedConnectivity.status,
          )}`}
          id={`${expandedConnectivity.id}:panel`}
          role="tabpanel"
        >
          <ConnectivityDetails
            group={expandedConnectivity}
            onCheck={onCheckConnectivity}
          />
        </div>
      ) : null}
      {connectivityProblem && connectivityGroups.length > 0 ? (
        <p className="validity-dashboard-problem" role="alert">
          {connectivityProblem}
        </p>
      ) : null}
    </section>
  );
}
