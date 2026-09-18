import {
  AlertTriangle,
  Check,
  ChevronUp,
  CircleDashed,
  GripHorizontal,
  LoaderCircle,
  RefreshCw,
} from "lucide-react";
import {
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent,
} from "react";

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


const VALIDITY_PANEL_HEIGHT_KEY = "workflow-manage-validity-panel-height";
const VALIDITY_PANEL_MIN_HEIGHT = 96;
const VALIDITY_PANEL_KEYBOARD_STEP = 24;


function maximumPanelHeight(): number {
  return Math.max(VALIDITY_PANEL_MIN_HEIGHT, globalThis.innerHeight - 180);
}


function constrainedPanelHeight(height: number): number {
  return Math.min(
    maximumPanelHeight(),
    Math.max(VALIDITY_PANEL_MIN_HEIGHT, Math.round(height)),
  );
}


function savedPanelHeight(): number | null {
  try {
    const value = Number.parseInt(
      globalThis.localStorage.getItem(VALIDITY_PANEL_HEIGHT_KEY) ?? "",
      10,
    );
    return Number.isFinite(value) ? constrainedPanelHeight(value) : null;
  } catch {
    return null;
  }
}


function savePanelHeight(height: number) {
  try {
    globalThis.localStorage.setItem(
      VALIDITY_PANEL_HEIGHT_KEY,
      String(constrainedPanelHeight(height)),
    );
  } catch {
    // Browser storage can be unavailable in locked-down browsing contexts.
  }
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
  const items = [
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
  return items.sort((left, right) => {
    const rank = (item: ValidityItem) => {
      if (
        item.connectivity?.target.kind === "source"
        || item.connectivity?.target.kind === "target"
      ) {
        return 0;
      }
      if (item.connectivity?.target.kind === "repository") return 1;
      const category = item.environment?.references[0]?.category;
      if (category === "secret") return 2;
      if (category === "configmap") return 3;
      if (category === "image") return 4;
      return 5;
    };
    return rank(left) - rank(right)
      || left.typeLabel.localeCompare(right.typeLabel)
      || left.label.localeCompare(right.label);
  });
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


function ResizableValidityPanel({
  item,
  onCheckConnectivity,
}: Readonly<{
  item: ValidityItem;
  onCheckConnectivity: (targetIds: string[]) => void;
}>) {
  const panelRef = useRef<HTMLDivElement>(null);
  const resizeStartRef = useRef<{
    height: number;
    pointerY: number;
  } | null>(null);
  const latestHeightRef = useRef<number | null>(null);
  const measuredItemRef = useRef<string | null>(null);
  const initialSavedHeight = useRef(savedPanelHeight());
  const userSizedRef = useRef(initialSavedHeight.current !== null);
  const [height, setHeight] = useState<number | null>(
    initialSavedHeight.current,
  );

  const updateHeight = (nextHeight: number) => {
    const constrained = constrainedPanelHeight(nextHeight);
    latestHeightRef.current = constrained;
    setHeight(constrained);
  };

  useLayoutEffect(() => {
    if (
      userSizedRef.current
      || measuredItemRef.current === item.id
      || !panelRef.current
    ) {
      return;
    }
    panelRef.current.style.height = "auto";
    const naturalHeight = constrainedPanelHeight(
      panelRef.current.scrollHeight,
    );
    measuredItemRef.current = item.id;
    latestHeightRef.current = naturalHeight;
    setHeight(naturalHeight);
  }, [item.id]);

  const finishResize = (
    event: PointerEvent<HTMLDivElement>,
  ) => {
    if (!resizeStartRef.current) return;
    resizeStartRef.current = null;
    userSizedRef.current = true;
    const chosenHeight = latestHeightRef.current ?? height;
    if (chosenHeight !== null) savePanelHeight(chosenHeight);
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  };

  const resizeWithKeyboard = (event: KeyboardEvent<HTMLDivElement>) => {
    const currentHeight = height
      ?? panelRef.current?.getBoundingClientRect().height
      ?? VALIDITY_PANEL_MIN_HEIGHT;
    let nextHeight: number | null = null;
    if (event.key === "ArrowUp") {
      nextHeight = currentHeight - VALIDITY_PANEL_KEYBOARD_STEP;
    } else if (event.key === "ArrowDown") {
      nextHeight = currentHeight + VALIDITY_PANEL_KEYBOARD_STEP;
    } else if (event.key === "PageUp") {
      nextHeight = currentHeight - VALIDITY_PANEL_KEYBOARD_STEP * 4;
    } else if (event.key === "PageDown") {
      nextHeight = currentHeight + VALIDITY_PANEL_KEYBOARD_STEP * 4;
    } else if (event.key === "Home") {
      nextHeight = VALIDITY_PANEL_MIN_HEIGHT;
    } else if (event.key === "End") {
      nextHeight = maximumPanelHeight();
    }
    if (nextHeight === null) return;
    event.preventDefault();
    userSizedRef.current = true;
    updateHeight(nextHeight);
    savePanelHeight(nextHeight);
  };

  const panelHeight = height ?? VALIDITY_PANEL_MIN_HEIGHT;
  return (
    <div className="validity-expanded-panel">
      <div
        aria-labelledby={`${item.id}:tab`}
        className={`validity-tab-panel status-${validityStatusClass(
          item.status,
        )}`}
        id={`${item.id}:panel`}
        ref={panelRef}
        role="tabpanel"
        style={height === null ? undefined : { height }}
      >
        <ValidityDetails
          item={item}
          onCheckConnectivity={onCheckConnectivity}
        />
      </div>
      <div
        aria-label="Resize validity details"
        aria-orientation="horizontal"
        aria-valuemax={maximumPanelHeight()}
        aria-valuemin={VALIDITY_PANEL_MIN_HEIGHT}
        aria-valuenow={panelHeight}
        className="validity-resize-handle"
        onKeyDown={resizeWithKeyboard}
        onPointerCancel={finishResize}
        onPointerDown={(event) => {
          const panel = panelRef.current;
          if (!panel) return;
          event.preventDefault();
          userSizedRef.current = true;
          resizeStartRef.current = {
            height: panel.getBoundingClientRect().height,
            pointerY: event.clientY,
          };
          latestHeightRef.current = panel.getBoundingClientRect().height;
          event.currentTarget.setPointerCapture(event.pointerId);
        }}
        onPointerMove={(event) => {
          const start = resizeStartRef.current;
          if (!start) return;
          updateHeight(start.height + event.clientY - start.pointerY);
        }}
        onPointerUp={finishResize}
        role="separator"
        tabIndex={0}
        title="Drag to resize validity details"
      >
        <GripHorizontal aria-hidden="true" />
      </div>
    </div>
  );
}


export function ValidityDashboard({
  ariaLabel = "Configuration checks",
  connectivityLoading,
  connectivityProblem,
  connectivityStates,
  environmentGroups,
  onCheckConnectivity,
}: Readonly<{
  ariaLabel?: string;
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
    <section className="validity-dashboard" aria-label={ariaLabel}>
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
        <ResizableValidityPanel
          item={expandedItem}
          onCheckConnectivity={onCheckConnectivity}
        />
      ) : null}
      {connectivityProblem ? (
        <p className="validity-dashboard-problem" role="alert">
          {connectivityProblem}
        </p>
      ) : null}
    </section>
  );
}
