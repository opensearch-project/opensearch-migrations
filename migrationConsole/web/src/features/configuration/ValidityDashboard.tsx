import {
  AlertTriangle,
  Check,
  ChevronUp,
  CircleDashed,
  LoaderCircle,
  RefreshCw,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import {
  ConnectivityCheckDetails,
  connectivityStatusLabel,
  type ConnectivityStatus,
  type ConnectivityTargetState,
} from "./connectivityChecks";
import {
  type EnvironmentDiagnosticLifecycle,
  type EnvironmentReferenceGroup,
} from "./environmentDiagnostics";


export type ValidityStatus =
  | ConnectivityStatus
  | EnvironmentDiagnosticLifecycle;


export interface ValidityItem {
  id: string;
  label: string;
  typeLabel: string;
  paths: string[][];
  status: ValidityStatus;
  environment?: EnvironmentReferenceGroup;
  connectivity?: ConnectivityTargetState;
}


// eslint-disable-next-line react-refresh/only-export-components
export function validityStatusLabel(status: ValidityStatus): string {
  if (status === "not-checked") return "Not checked";
  if (status === "warning") return "Warning";
  if (status === "error") return "Error";
  return connectivityStatusLabel(status);
}


// eslint-disable-next-line react-refresh/only-export-components
export function validityStatusClass(status: ValidityStatus): string {
  if (status === "error") return "failed";
  if (status === "warning") return "partially_verified";
  if (status === "not-checked") return "not_checked";
  return status;
}


export function ValidityStatusIcon({
  status,
}: Readonly<{ status: ValidityStatus }>) {
  if (status === "checking" || status === "pending" || status === "stale") {
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


function connectivityTypeLabel(
  kind: ConnectivityTargetState["target"]["kind"],
): string {
  switch (kind) {
    case "source": return "Source Cluster";
    case "target": return "Target Cluster";
    case "repository": return "Snapshot Repository";
  }
}


// eslint-disable-next-line react-refresh/only-export-components
export function buildValidityItems(
  environmentGroups: EnvironmentReferenceGroup[],
  connectivityStates: ConnectivityTargetState[],
): ValidityItem[] {
  return [
    ...environmentGroups.map((group): ValidityItem => ({
      id: group.id,
      label: group.label,
      typeLabel: group.typeLabel,
      paths: group.references.map(({ path }) => path),
      status: group.status,
      environment: group,
    })),
    ...connectivityStates.map((state): ValidityItem => ({
      id: `connectivity:${state.target.id}`,
      label: state.target.refName,
      typeLabel: connectivityTypeLabel(state.target.kind),
      paths: [state.target.editPath],
      status: state.status,
      connectivity: state,
    })),
  ];
}


export function ValidityDetails({
  item,
  onCheckConnectivity,
}: Readonly<{
  item: ValidityItem;
  onCheckConnectivity: (targetIds: string[]) => void;
}>) {
  if (item.environment) {
    return (
      <div className="validity-detail-content">
        <header>
          <strong>{item.label}</strong>
          <span>{item.typeLabel}</span>
        </header>
        {item.environment.diagnostics.length > 0 ? (
          <ul className="validity-diagnostic-list">
            {item.environment.diagnostics.map((diagnostic, index) => (
              <li
                className={`status-${diagnostic.severity}`}
                key={`${diagnostic.path.join(".")}:${diagnostic.message}:${index}`}
              >
                {diagnostic.message}
              </li>
            ))}
          </ul>
        ) : (
          <p>
            {validityStatusLabel(item.status)}: the configured reference is
            available.
          </p>
        )}
      </div>
    );
  }
  if (!item.connectivity) return null;
  return (
    <div className="validity-connectivity-target">
      <header>
        <div>
          <strong>{item.connectivity.target.label}</strong>
          <span>{validityStatusLabel(item.status)}</span>
        </div>
        <button
          disabled={item.status === "checking" || item.status === "pending"}
          onClick={() => onCheckConnectivity([item.connectivity!.target.id])}
          type="button"
        >
          <RefreshCw aria-hidden="true" />
          {item.connectivity.check ? "Recheck" : "Check"}
        </button>
      </header>
      <ConnectivityCheckDetails state={item.connectivity} />
    </div>
  );
}


export function ValidityIndicator({
  expanded,
  item,
  onToggle,
}: Readonly<{
  expanded: boolean;
  item: ValidityItem;
  onToggle: () => void;
}>) {
  return (
    <button
      aria-expanded={expanded}
      className={`validity-indicator status-${validityStatusClass(item.status)}`}
      onClick={onToggle}
      title={`${item.typeLabel}: ${validityStatusLabel(item.status)}`}
      type="button"
    >
      <ValidityStatusIcon status={item.status} />
      <span>{validityStatusLabel(item.status)}</span>
      {expanded ? <ChevronUp aria-hidden="true" /> : null}
    </button>
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
  const items = useMemo(
    () => buildValidityItems(environmentGroups, connectivityStates),
    [connectivityStates, environmentGroups],
  );
  const [expandedId, setExpandedId] = useState<string | null>(null);
  useEffect(() => {
    if (expandedId && !items.some(({ id }) => id === expandedId)) {
      setExpandedId(null);
    }
  }, [expandedId, items]);
  if (items.length === 0 && !connectivityLoading && !connectivityProblem) {
    return null;
  }
  const expandedItem = items.find(({ id }) => id === expandedId);
  return (
    <section className="validity-dashboard" aria-label="Configuration checks">
      <div className="validity-tabs" role="tablist">
        {items.map((item) => (
          <button
            aria-controls={`${item.id}:panel`}
            aria-expanded={expandedId === item.id}
            aria-selected={expandedId === item.id}
            className={`validity-tab status-${validityStatusClass(item.status)}`}
            id={`${item.id}:tab`}
            key={item.id}
            onClick={() => setExpandedId(
              (current) => current === item.id ? null : item.id,
            )}
            role="tab"
            type="button"
          >
            <ValidityStatusIcon status={item.status} />
            <span>
              <strong>{item.label}</strong>
              <small>{item.typeLabel} · {validityStatusLabel(item.status)}</small>
            </span>
          </button>
        ))}
        {connectivityLoading && items.length === 0 ? (
          <div className="validity-tab status-checking" role="status">
            <LoaderCircle className="spin" aria-hidden="true" />
            <span><strong>Connectivity</strong><small>Loading checks</small></span>
          </div>
        ) : null}
      </div>
      {expandedItem ? (
        <div
          aria-labelledby={`${expandedItem.id}:tab`}
          className={`validity-tab-panel status-${validityStatusClass(
            expandedItem.status,
          )}`}
          id={`${expandedItem.id}:panel`}
          role="tabpanel"
        >
          <ValidityDetails
            item={expandedItem}
            onCheckConnectivity={onCheckConnectivity}
          />
        </div>
      ) : null}
      {connectivityProblem ? (
        <p className="validity-dashboard-problem" role="alert">
          {connectivityProblem}
        </p>
      ) : null}
    </section>
  );
}
