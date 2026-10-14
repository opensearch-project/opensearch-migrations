import {
  useEffect,
  useRef,
  useState,
} from "react";
import { useQuery } from "@tanstack/react-query";
import {
  LoaderCircle,
  Maximize2,
  Minimize2,
  Pin,
  PinOff,
  RefreshCw,
  TriangleAlert,
  X,
} from "lucide-react";

import type {
  ManageNode,
  RuntimeStatus,
} from "../../api/client";
import { getRuntimeStatus } from "../../api/client";
import { StatusIndicator } from "../status/StatusIndicator";
import {
  DockResizeHandle,
} from "./DockResizeHandle";
import {
  DockReorderHandle,
} from "./DockReorderHandle";
import {
  boundedDockHeight,
  DEFAULT_DOCK_HEIGHT,
  moveDockItem,
  moveDockItemBy,
} from "./dockPaneState";
import {
  notifyRuntimeDashboardChanged,
  PINNED_RUNTIME_STATUS_STORAGE_KEY,
  RUNTIME_STATUS_WORKSPACE_STORAGE_KEY,
} from "./runtimeDashboardState";
import { runtimeStatusSupported } from "./runtimeStatusSupport";


export interface RuntimeStatusPaneState {
  id: string;
  nodeId: string;
  nodeLabel: string;
  resourceType: string;
  pinned: boolean;
  expanded: boolean;
  height: number;
}


type RuntimeStatusContentValue = NonNullable<
  RuntimeStatus["sections"][number]["content"]
>;


function createRuntimeStatusPane(
  node: ManageNode,
): RuntimeStatusPaneState {
  return {
    id: `runtime-status:${node.id}`,
    nodeId: node.id,
    nodeLabel: node.label,
    resourceType: node.resourceType ?? "Resource",
    pinned: false,
    expanded: true,
    height: DEFAULT_DOCK_HEIGHT,
  };
}


function parseStoredPanes(stored: string | null): RuntimeStatusPaneState[] {
  if (!stored) return [];
  try {
    const values = JSON.parse(stored) as Partial<RuntimeStatusPaneState>[];
    if (!Array.isArray(values)) return [];
    return values.flatMap((value) => {
      if (
        typeof value.id !== "string"
        || typeof value.nodeId !== "string"
        || typeof value.nodeLabel !== "string"
      ) {
        return [];
      }
      return [{
        id: value.id,
        nodeId: value.nodeId,
        nodeLabel: value.nodeLabel,
        resourceType: typeof value.resourceType === "string"
          ? value.resourceType
          : "Resource",
        pinned: Boolean(value.pinned),
        expanded: value.expanded !== false,
        height: boundedDockHeight(value.height ?? DEFAULT_DOCK_HEIGHT),
      }];
    });
  } catch {
    return [];
  }
}


function loadPanes(standalone: boolean): RuntimeStatusPaneState[] {
  const key = standalone
    ? RUNTIME_STATUS_WORKSPACE_STORAGE_KEY
    : PINNED_RUNTIME_STATUS_STORAGE_KEY;
  return parseStoredPanes(globalThis.localStorage?.getItem(key) ?? null);
}


function savePanes(panes: RuntimeStatusPaneState[]): void {
  try {
    if (panes.length === 0) {
      globalThis.localStorage?.removeItem(RUNTIME_STATUS_WORKSPACE_STORAGE_KEY);
    } else {
      globalThis.localStorage?.setItem(
        RUNTIME_STATUS_WORKSPACE_STORAGE_KEY,
        JSON.stringify(panes),
      );
    }
    const pinned = panes.filter((pane) => pane.pinned);
    if (pinned.length === 0) {
      globalThis.localStorage?.removeItem(
        PINNED_RUNTIME_STATUS_STORAGE_KEY,
      );
    } else {
      globalThis.localStorage?.setItem(
        PINNED_RUNTIME_STATUS_STORAGE_KEY,
        JSON.stringify(pinned),
      );
    }
  } catch {
    // Runtime status remains available without browser persistence.
  }
}


function withActivePane(
  panes: RuntimeStatusPaneState[],
  activeNode?: ManageNode | null,
): RuntimeStatusPaneState[] {
  if (
    !runtimeStatusSupported(activeNode)
    || panes.some((pane) => pane.nodeId === activeNode?.id)
  ) {
    return panes;
  }
  return [createRuntimeStatusPane(activeNode as ManageNode), ...panes];
}


function observedTime(value: string): string {
  const observed = new Date(value);
  return Number.isNaN(observed.valueOf())
    ? value
    : observed.toLocaleTimeString([], {
      hour: "numeric",
      minute: "2-digit",
      second: "2-digit",
    });
}


function metricValue(
  metric: Extract<
    RuntimeStatusContentValue,
    { kind: "metrics" }
  >["metrics"][number],
): string {
  const value = String(metric.value);
  if (!metric.unit) return value;
  return metric.unit === "percent"
    ? `${value}%`
    : `${value} ${metric.unit}`;
}


function RuntimeStatusContent({
  content,
  title,
}: Readonly<{
  content: RuntimeStatusContentValue;
  title: string;
}>) {
  if (content.kind === "metrics") {
    return (
      <dl className="runtime-status-metrics">
        {content.metrics.map((metric) => (
          <div key={metric.key}>
            <dt>{metric.label}</dt>
            <dd>{metricValue(metric)}</dd>
          </div>
        ))}
      </dl>
    );
  }
  if (content.kind === "name-list") {
    return (
      <ul aria-label={`${title} values`} className="runtime-status-names">
        {content.items.map((item) => (
          <li key={item}><code>{item}</code></li>
        ))}
      </ul>
    );
  }
  if (content.kind === "consumer-offsets") {
    return (
      <table className="runtime-status-table">
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">Consumer group</th>
            <th scope="col">Partition</th>
            <th scope="col">Current offset</th>
            <th scope="col">Log end</th>
            <th scope="col">Lag</th>
          </tr>
        </thead>
        <tbody>
          {content.offsets.map((offset) => (
            <tr key={`${offset.group}-${offset.topic}-${offset.partition}`}>
              <td><code>{offset.group}</code></td>
              <td>{offset.partition}</td>
              <td>
                {offset.currentOffset === null
                  || offset.currentOffset === undefined
                  ? "Not committed"
                  : offset.currentOffset.toLocaleString()}
              </td>
              <td>
                {offset.logEndOffset === null
                  || offset.logEndOffset === undefined
                  ? "Unknown"
                  : offset.logEndOffset.toLocaleString()}
              </td>
              <td>
                {offset.lag === null || offset.lag === undefined
                  ? "Unknown"
                  : offset.lag.toLocaleString()}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  }
  if (content.kind === "topic-partitions") {
    return (
      <table className="runtime-status-table">
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">Topic</th>
            <th scope="col">Partition</th>
            <th scope="col">Records</th>
          </tr>
        </thead>
        <tbody>
          {content.partitions.map((partition) => (
            <tr key={`${partition.topic}-${partition.partition}`}>
              <td><code>{partition.topic}</code></td>
              <td>{partition.partition}</td>
              <td>{partition.records.toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  }
  return (
    <pre className="runtime-status-text">{content.lines.join("\n")}</pre>
  );
}


function RuntimeStatusPane({
  node,
  onClose,
  onDragEnd,
  onDragStart,
  onDrop,
  onMove,
  onUpdate,
  pane,
  standalone,
}: Readonly<{
  node?: ManageNode;
  onClose: () => void;
  onDragEnd: () => void;
  onDragStart: () => void;
  onDrop: () => void;
  onMove: (offset: -1 | 1) => void;
  onUpdate: (next: RuntimeStatusPaneState) => void;
  pane: RuntimeStatusPaneState;
  standalone: boolean;
}>) {
  const forceRefresh = useRef(false);
  const status = useQuery({
    queryKey: ["runtime-status", pane.nodeId],
    queryFn: async () => {
      const force = forceRefresh.current;
      forceRefresh.current = false;
      return getRuntimeStatus(pane.nodeId, force);
    },
    enabled: runtimeStatusSupported(node),
    retry: false,
    staleTime: 5000,
    refetchInterval: (query) => query.state.data?.pollAfterMs ?? false,
  });
  const update = (patch: Partial<RuntimeStatusPaneState>) => {
    onUpdate({ ...pane, ...patch });
  };
  const refresh = () => {
    forceRefresh.current = true;
    void status.refetch();
  };

  return (
    <section
      aria-label={`Runtime status for ${pane.nodeLabel}`}
      className={[
        "runtime-status-explorer",
        pane.expanded ? "expanded" : "collapsed",
      ].join(" ")}
      onDragOver={(event) => {
        event.preventDefault();
        event.dataTransfer.dropEffect = "move";
      }}
      onDrop={(event) => {
        event.preventDefault();
        onDrop();
      }}
      style={{ height: `${pane.expanded ? pane.height : 41}px` }}
    >
      {!standalone && pane.expanded ? (
        <DockResizeHandle
          height={pane.height}
          label={`Resize runtime status for ${pane.nodeLabel}`}
          onChange={(height) => update({ height })}
        />
      ) : null}
      <header className="runtime-dock-summary">
        <div className="runtime-dock-summary-request">
          <DockReorderHandle
            label={`Reorder runtime status for ${pane.nodeLabel}`}
            onDragEnd={onDragEnd}
            onDragStart={onDragStart}
            onMove={onMove}
          />
          <strong>{pane.nodeLabel}</strong>
          <span>{pane.resourceType}</span>
        </div>
        <div className="runtime-dock-pane-actions">
          <div className="runtime-dock-observation">
            {status.data ? (
              <time dateTime={status.data.observedAt}>
                {observedTime(status.data.observedAt)}
              </time>
            ) : null}
            {status.isFetching ? (
              <LoaderCircle
                aria-label={`${pane.nodeLabel} runtime status updating`}
                className="spin"
              />
            ) : (
              <button
                aria-label={`Refresh runtime status for ${pane.nodeLabel}`}
                className="icon-button"
                onClick={refresh}
                title="Refresh runtime status"
                type="button"
              >
                <RefreshCw aria-hidden="true" />
              </button>
            )}
          </div>
          {!standalone ? (
            <button
              aria-label={pane.pinned
                ? `Unpin runtime status for ${pane.nodeLabel}`
                : `Pin runtime status for ${pane.nodeLabel}`}
              aria-pressed={pane.pinned}
              className="icon-button"
              onClick={() => update({ pinned: !pane.pinned })}
              title={pane.pinned
                ? "Show this status only on its resource page"
                : "Keep this status visible throughout the runtime view"}
              type="button"
            >
              {pane.pinned
                ? <PinOff aria-hidden="true" />
                : <Pin aria-hidden="true" />}
            </button>
          ) : null}
          <button
            aria-label={`${pane.expanded ? "Collapse" : "Expand"} runtime status for ${pane.nodeLabel}`}
            className="icon-button"
            onClick={() => update({ expanded: !pane.expanded })}
            title={pane.expanded ? "Collapse status" : "Expand status"}
            type="button"
          >
            {pane.expanded
              ? <Minimize2 aria-hidden="true" />
              : <Maximize2 aria-hidden="true" />}
          </button>
          <button
            aria-label={`Close runtime status for ${pane.nodeLabel}`}
            className="icon-button"
            onClick={onClose}
            title="Close runtime status"
            type="button"
          >
            <X aria-hidden="true" />
          </button>
        </div>
      </header>
      {pane.expanded ? (
        <div className="runtime-status-pane-body">
          {!node ? (
            <div className="runtime-status-error">
              <TriangleAlert aria-hidden="true" />
              <span>The resource is not present in the current workflow.</span>
            </div>
          ) : null}
          {node && status.isPending ? (
            <div className="runtime-status-loading">
              <LoaderCircle aria-hidden="true" className="spin" />
              <span>Reading runtime status</span>
            </div>
          ) : null}
          {node && status.isError ? (
            <div className="runtime-status-error">
              <TriangleAlert aria-hidden="true" />
              <span>{status.error.message}</span>
            </div>
          ) : null}
          {status.data?.sections.map((section) => (
            <article
              className={`runtime-status-section status-${section.state}`}
              key={section.key}
            >
              <StatusIndicator status={section.state} />
              <div>
                <strong>{section.title}</strong>
                <p>{section.summary}</p>
                {section.content ? (
                  <RuntimeStatusContent
                    content={section.content}
                    title={section.title}
                  />
                ) : null}
                <small>{section.source}</small>
              </div>
            </article>
          ))}
        </div>
      ) : null}
      {standalone && pane.expanded ? (
        <DockResizeHandle
          edge="bottom"
          height={pane.height}
          label={`Resize runtime status for ${pane.nodeLabel}`}
          onChange={(height) => update({ height })}
        />
      ) : null}
    </section>
  );
}


export function RuntimeStatusDock({
  activeNode,
  nodes,
  standalone = false,
}: Readonly<{
  activeNode?: ManageNode | null;
  nodes: Record<string, ManageNode>;
  standalone?: boolean;
}>) {
  const [panes, setPanes] = useState<RuntimeStatusPaneState[]>(
    () => withActivePane(loadPanes(standalone), activeNode),
  );
  const [draggedPaneId, setDraggedPaneId] = useState<string | null>(null);

  useEffect(() => {
    savePanes(panes);
    notifyRuntimeDashboardChanged();
  }, [panes]);

  useEffect(() => {
    const key = standalone
      ? RUNTIME_STATUS_WORKSPACE_STORAGE_KEY
      : PINNED_RUNTIME_STATUS_STORAGE_KEY;
    const syncPanes = (event: StorageEvent) => {
      if (event.key !== key) return;
      setPanes(withActivePane(parseStoredPanes(event.newValue), activeNode));
    };
    globalThis.addEventListener("storage", syncPanes);
    return () => globalThis.removeEventListener("storage", syncPanes);
  }, [activeNode, standalone]);

  const updatePane = (next: RuntimeStatusPaneState) => {
    setPanes((current) => current.map(
      (pane) => pane.id === next.id ? next : pane,
    ));
  };
  const closePane = (paneId: string) => {
    setPanes((current) => current.filter((pane) => pane.id !== paneId));
  };
  const dropPane = (targetId: string) => {
    if (!draggedPaneId) return;
    setPanes((current) => moveDockItem(
      current,
      draggedPaneId,
      targetId,
    ));
    setDraggedPaneId(null);
  };
  const movePane = (paneId: string, offset: -1 | 1) => {
    setPanes((current) => moveDockItemBy(current, paneId, offset));
  };

  if (panes.length === 0) return null;
  return (
    <section
      aria-label="Runtime status dashboard"
      className={[
        "runtime-status-dock",
        standalone ? "standalone" : "",
      ].filter(Boolean).join(" ")}
    >
      {panes.map((pane) => (
        <RuntimeStatusPane
          key={pane.id}
          node={nodes[pane.nodeId]}
          onClose={() => closePane(pane.id)}
          onDragEnd={() => setDraggedPaneId(null)}
          onDragStart={() => setDraggedPaneId(pane.id)}
          onDrop={() => dropPane(pane.id)}
          onMove={(offset) => movePane(pane.id, offset)}
          onUpdate={updatePane}
          pane={pane}
          standalone={standalone}
        />
      ))}
    </section>
  );
}
