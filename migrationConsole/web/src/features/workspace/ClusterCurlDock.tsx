import {
  useEffect,
  useState,
  type FormEvent,
} from "react";
import { useQuery } from "@tanstack/react-query";
import {
  LoaderCircle,
  Maximize2,
  Minimize2,
  Pencil,
  Pin,
  PinOff,
  Plus,
  RefreshCw,
  X,
} from "lucide-react";

import {
  runClusterCurl,
  type ClusterCurlRequest,
} from "../../api/client";
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
import type { ClusterCurlTarget } from "./clusterCurlTargets";
import {
  CURL_WORKSPACE_STORAGE_KEY,
  notifyRuntimeDashboardChanged,
  PINNED_CURL_STORAGE_KEY,
} from "./runtimeDashboardState";


const DEFAULT_PATH = "/_cat/indices?pretty&v";
const AUTO_REFRESH_MS = 10_000;
let nextExplorerId = 1;


export interface ClusterCurlExplorerState {
  id: string;
  clusterName: string;
  nodeId: string;
  method: ClusterCurlRequest["method"];
  path: string;
  body: string;
  pinned: boolean;
  autoRefresh: boolean;
  expanded: boolean;
  editing: boolean;
  height: number;
}


function createClusterCurlExplorer(
  target: ClusterCurlTarget,
  path = DEFAULT_PATH,
): ClusterCurlExplorerState {
  const id = `curl-${Date.now()}-${nextExplorerId}`;
  nextExplorerId += 1;
  return {
    id,
    clusterName: target.clusterName,
    nodeId: target.nodeId,
    method: "GET",
    path,
    body: "",
    pinned: false,
    autoRefresh: false,
    expanded: true,
    editing: true,
    height: DEFAULT_DOCK_HEIGHT,
  };
}


function parseStoredExplorers(
  stored: string | null,
  pinnedOnly: boolean,
): ClusterCurlExplorerState[] {
  if (!stored) return [];
  try {
    const values = JSON.parse(stored) as Partial<ClusterCurlExplorerState>[];
    if (!Array.isArray(values)) return [];
    return values.flatMap((value) => {
      if (
        typeof value.id !== "string"
        || typeof value.clusterName !== "string"
        || typeof value.nodeId !== "string"
        || typeof value.path !== "string"
        || !["GET", "POST", "PUT", "DELETE", "HEAD"].includes(
          String(value.method),
        )
      ) {
        return [];
      }
      const hasEditingState = typeof value.editing === "boolean";
      return [{
        id: value.id,
        clusterName: value.clusterName,
        nodeId: value.nodeId,
        method: value.method as ClusterCurlRequest["method"],
        path: value.path,
        body: typeof value.body === "string" ? value.body : "",
        pinned: pinnedOnly || Boolean(value.pinned),
        autoRefresh: Boolean(value.autoRefresh),
        expanded: hasEditingState ? value.expanded !== false : true,
        editing: pinnedOnly
          ? false
          : (
            hasEditingState
              ? value.editing
              : value.expanded !== false
          ),
        height: boundedDockHeight(value.height ?? DEFAULT_DOCK_HEIGHT),
      }];
    });
  } catch {
    return [];
  }
}


function loadPinnedExplorers(): ClusterCurlExplorerState[] {
  return parseStoredExplorers(
    globalThis.localStorage?.getItem(PINNED_CURL_STORAGE_KEY) ?? null,
    true,
  );
}


function loadWorkspaceExplorers(): ClusterCurlExplorerState[] {
  return parseStoredExplorers(
    globalThis.localStorage?.getItem(CURL_WORKSPACE_STORAGE_KEY) ?? null,
    false,
  );
}


function savePinnedExplorers(
  explorers: ClusterCurlExplorerState[],
): void {
  try {
    const pinned = explorers.filter((explorer) => explorer.pinned);
    if (pinned.length === 0) {
      globalThis.localStorage?.removeItem(PINNED_CURL_STORAGE_KEY);
      return;
    }
    globalThis.localStorage?.setItem(
      PINNED_CURL_STORAGE_KEY,
      JSON.stringify(pinned.map((explorer) => ({
        ...explorer,
        editing: false,
      }))),
    );
  } catch {
    // Browser storage is a convenience; curl execution remains available.
  }
}


function saveWorkspaceExplorers(
  explorers: ClusterCurlExplorerState[],
): void {
  try {
    if (explorers.length === 0) {
      globalThis.localStorage?.removeItem(CURL_WORKSPACE_STORAGE_KEY);
      return;
    }
    globalThis.localStorage?.setItem(
      CURL_WORKSPACE_STORAGE_KEY,
      JSON.stringify(explorers),
    );
  } catch {
    // The workspace still functions if another tab cannot restore it.
  }
}


function responseTime(value: string): string {
  const observed = new Date(value);
  return Number.isNaN(observed.valueOf())
    ? value
    : observed.toLocaleTimeString([], {
      hour: "numeric",
      minute: "2-digit",
      second: "2-digit",
    });
}


function CurlObservation({
  explorerNumber,
  fetching,
  observedAt,
  onRefresh,
}: Readonly<{
  explorerNumber: number;
  fetching: boolean;
  observedAt?: string;
  onRefresh: () => void;
}>) {
  return (
    <div className="runtime-dock-observation">
      {observedAt ? (
        <time dateTime={observedAt}>{responseTime(observedAt)}</time>
      ) : null}
      {fetching ? (
        <LoaderCircle
          aria-label="Curl request updating"
          className="spin"
        />
      ) : (
        <button
          aria-label={`Refresh curl explorer ${explorerNumber}`}
          className="icon-button"
          onClick={onRefresh}
          title="Refresh this request"
          type="button"
        >
          <RefreshCw aria-hidden="true" />
        </button>
      )}
    </div>
  );
}


function removeExplorer(
  explorers: ClusterCurlExplorerState[],
  explorerId: string,
): ClusterCurlExplorerState[] {
  return explorers.filter((candidate) => candidate.id !== explorerId);
}


function replaceExplorer(
  explorers: ClusterCurlExplorerState[],
  next: ClusterCurlExplorerState,
): ClusterCurlExplorerState[] {
  return explorers.map(
    (candidate) => candidate.id === next.id ? next : candidate,
  );
}


function ClusterCurlGlobalActions({
  onAdd,
}: Readonly<{
  onAdd: () => void;
}>) {
  return (
    <button
      aria-label="Add curl explorer"
      className="icon-button"
      onClick={onAdd}
      title="Add curl explorer"
      type="button"
    >
      <Plus aria-hidden="true" />
    </button>
  );
}


function ClusterCurlExplorer({
  availableClusters,
  explorer,
  index,
  onAdd,
  onClose,
  onDragEnd,
  onDragStart,
  onDrop,
  onMove,
  onUpdate,
  primary,
  standalone,
}: Readonly<{
  availableClusters: ClusterCurlTarget[];
  explorer: ClusterCurlExplorerState;
  index: number;
  onAdd: (explorer: ClusterCurlExplorerState) => void;
  onClose: () => void;
  onDragEnd: () => void;
  onDragStart: () => void;
  onDrop: () => void;
  onMove: (offset: -1 | 1) => void;
  onUpdate: (next: ClusterCurlExplorerState) => void;
  primary: boolean;
  standalone: boolean;
}>) {
  const [requestRevision, setRequestRevision] = useState(0);
  const [executedNodeId, setExecutedNodeId] = useState(explorer.nodeId);
  const [executedRequest, setExecutedRequest] = useState<ClusterCurlRequest>({
    method: explorer.method,
    path: explorer.path,
    body: explorer.body || null,
    headers: [],
  });
  const request = useQuery({
    queryKey: [
      "cluster-curl",
      executedNodeId,
      explorer.id,
      requestRevision,
      executedRequest,
    ],
    queryFn: ({ signal }) => runClusterCurl(
      executedNodeId,
      executedRequest,
      signal,
    ),
    refetchInterval: explorer.autoRefresh ? AUTO_REFRESH_MS : false,
    retry: false,
  });

  const update = (patch: Partial<ClusterCurlExplorerState>) => {
    onUpdate({ ...explorer, ...patch });
  };
  const execute = (event?: FormEvent) => {
    event?.preventDefault();
    setExecutedNodeId(explorer.nodeId);
    setExecutedRequest({
      method: explorer.method,
      path: explorer.path,
      body: explorer.body || null,
      headers: [],
    });
    setRequestRevision((revision) => revision + 1);
    update({ editing: false });
  };

  const response = request.data;
  const error = request.error instanceof Error
    ? request.error.message
    : request.error
      ? String(request.error)
      : null;
  return (
    <section
      aria-label={`Curl explorer ${index + 1}`}
      className={[
        "cluster-curl-explorer",
        explorer.expanded ? "expanded" : "collapsed",
        explorer.editing ? "editing" : "",
        standalone ? "standalone" : "",
      ].join(" ")}
      onDragOver={(event) => {
        event.preventDefault();
        event.dataTransfer.dropEffect = "move";
      }}
      onDrop={(event) => {
        event.preventDefault();
        onDrop();
      }}
      style={{
        height: explorer.expanded
          ? `${explorer.height}px`
          : explorer.editing
            ? "auto"
            : "41px",
      }}
    >
      {!standalone && explorer.expanded ? (
        <DockResizeHandle
          height={explorer.height}
          label={`Resize curl explorer ${index + 1}`}
          onChange={(height) => update({ height })}
        />
      ) : null}
      {explorer.editing ? (
        <form className="cluster-curl-request" onSubmit={execute}>
          <DockReorderHandle
            label={`Reorder curl explorer ${index + 1}`}
            onDragEnd={onDragEnd}
            onDragStart={onDragStart}
            onMove={onMove}
          />
          {primary ? (
            <strong className="cluster-curl-dock-label">Cluster curl</strong>
          ) : null}
          <label className="cluster-curl-target">
            <span className="sr-only">Cluster</span>
            <select
              aria-label={`Curl explorer ${index + 1} cluster`}
              onChange={(event) => {
                const target = availableClusters.find(
                  (candidate) => candidate.nodeId === event.currentTarget.value,
                );
                if (target) update(target);
              }}
              value={explorer.nodeId}
            >
              {availableClusters.map((target) => (
                <option key={target.nodeId} value={target.nodeId}>
                  {target.clusterName}
                </option>
              ))}
            </select>
          </label>
          <label className="cluster-curl-method">
            <span className="sr-only">Method</span>
            <select
              aria-label={`Curl explorer ${index + 1} method`}
              onChange={(event) => update({
                method: event.currentTarget.value as ClusterCurlRequest["method"],
              })}
              value={explorer.method}
            >
              {["GET", "POST", "PUT", "DELETE", "HEAD"].map((method) => (
                <option key={method} value={method}>{method}</option>
              ))}
            </select>
          </label>
          <label className="cluster-curl-path">
            <span className="sr-only">Path</span>
            <input
              aria-label={`Curl explorer ${index + 1} path`}
              onChange={(event) => update({ path: event.currentTarget.value })}
              spellCheck={false}
              value={explorer.path}
            />
          </label>
          <label className="cluster-curl-auto-refresh">
            <input
              checked={explorer.autoRefresh}
              onChange={(event) => update({
                autoRefresh: event.currentTarget.checked,
              })}
              type="checkbox"
            />
            <RefreshCw aria-hidden="true" />
            Auto-refresh
          </label>
          <div className="cluster-curl-pane-actions runtime-dock-pane-actions">
            <CurlObservation
              explorerNumber={index + 1}
              fetching={request.isFetching}
              observedAt={response?.observedAt}
              onRefresh={execute}
            />
            <button
              aria-label={`Add curl explorer after explorer ${index + 1}`}
              className="icon-button"
              onClick={() => onAdd(explorer)}
              title={`Add another request for ${explorer.clusterName}`}
              type="button"
            >
              <Plus aria-hidden="true" />
            </button>
            {!standalone ? (
              <button
                aria-label={explorer.pinned
                  ? "Unpin curl explorer"
                  : "Pin curl explorer"}
                aria-pressed={explorer.pinned}
                className="icon-button"
                onClick={() => update({ pinned: !explorer.pinned })}
                title={explorer.pinned
                  ? "Show this explorer only on its cluster page"
                  : "Keep this explorer visible throughout the runtime view"}
                type="button"
              >
                {explorer.pinned
                  ? <PinOff aria-hidden="true" />
                  : <Pin aria-hidden="true" />}
              </button>
            ) : null}
            <button
              aria-label={`${explorer.expanded ? "Collapse" : "Expand"} curl explorer ${index + 1}`}
              className="icon-button"
              onClick={() => update({ expanded: !explorer.expanded })}
              title={explorer.expanded ? "Collapse output" : "Expand output"}
              type="button"
            >
              {explorer.expanded
                ? <Minimize2 aria-hidden="true" />
                : <Maximize2 aria-hidden="true" />}
            </button>
            <button
              aria-label={`Close curl explorer ${index + 1}`}
              className="icon-button"
              onClick={onClose}
              title="Close curl explorer"
              type="button"
            >
              <X aria-hidden="true" />
            </button>
          </div>
          {!["GET", "HEAD"].includes(explorer.method) ? (
            <label className="cluster-curl-body">
              <span>Request body</span>
              <textarea
                aria-label={`Curl explorer ${index + 1} request body`}
                onChange={(event) => update({ body: event.currentTarget.value })}
                spellCheck={false}
                value={explorer.body}
              />
            </label>
          ) : null}
        </form>
      ) : (
        <header className="cluster-curl-summary">
          <div className="cluster-curl-summary-request">
            <DockReorderHandle
              label={`Reorder curl explorer ${index + 1}`}
              onDragEnd={onDragEnd}
              onDragStart={onDragStart}
              onMove={onMove}
            />
            {primary ? (
              <strong className="cluster-curl-dock-label">Cluster curl</strong>
            ) : null}
            <strong>{explorer.clusterName}</strong>
            <span>{explorer.method}</span>
            <code>{explorer.path}</code>
          </div>
          <div className="cluster-curl-pane-actions runtime-dock-pane-actions">
            <CurlObservation
              explorerNumber={index + 1}
              fetching={request.isFetching}
              observedAt={response?.observedAt}
              onRefresh={execute}
            />
            {!standalone ? (
              <button
                aria-label={explorer.pinned
                  ? "Unpin curl explorer"
                  : "Pin curl explorer"}
                aria-pressed={explorer.pinned}
                className="icon-button"
                onClick={() => update({ pinned: !explorer.pinned })}
                title={explorer.pinned
                  ? "Show this explorer only on its cluster page"
                  : "Keep this explorer visible throughout the runtime view"}
                type="button"
              >
                {explorer.pinned
                  ? <PinOff aria-hidden="true" />
                  : <Pin aria-hidden="true" />}
              </button>
            ) : null}
            <button
              aria-label={`Edit curl explorer ${index + 1}`}
              className="icon-button"
              onClick={() => update({ editing: true })}
              title="Edit cluster, method, or URI"
              type="button"
            >
              <Pencil aria-hidden="true" />
            </button>
            <button
              aria-label={`${explorer.expanded ? "Collapse" : "Expand"} curl explorer ${index + 1}`}
              className="icon-button"
              onClick={() => update({ expanded: !explorer.expanded })}
              title={explorer.expanded ? "Collapse output" : "Expand output"}
              type="button"
            >
              {explorer.expanded
                ? <Minimize2 aria-hidden="true" />
                : <Maximize2 aria-hidden="true" />}
            </button>
            <button
              aria-label={`Close curl explorer ${index + 1}`}
              className="icon-button"
              onClick={onClose}
              title="Close curl explorer"
              type="button"
            >
              <X aria-hidden="true" />
            </button>
          </div>
        </header>
      )}
      {explorer.expanded ? (
        <div
          aria-live="polite"
          className={[
            "cluster-curl-result",
            response?.success === false || error ? "error" : "",
          ].filter(Boolean).join(" ")}
        >
          <pre>
            {error
              ?? response?.error
              ?? response?.output
              ?? "Waiting for the first response."}
          </pre>
        </div>
      ) : null}
      {standalone && explorer.expanded ? (
        <DockResizeHandle
          edge="bottom"
          height={explorer.height}
          label={`Resize curl explorer ${index + 1}`}
          onChange={(height) => update({ height })}
        />
      ) : null}
    </section>
  );
}


export function ClusterCurlDock({
  activeCluster,
  availableClusters,
  standalone = false,
}: Readonly<{
  activeCluster: ClusterCurlTarget | null;
  availableClusters: ClusterCurlTarget[];
  standalone?: boolean;
}>) {
  const [explorers, setExplorers] = useState<ClusterCurlExplorerState[]>(
    () => {
      const availableIds = new Set(
        availableClusters.map((cluster) => cluster.nodeId),
      );
      const restored = (
        standalone ? loadWorkspaceExplorers() : loadPinnedExplorers()
      ).filter((explorer) => availableIds.has(explorer.nodeId));
      if (
        activeCluster
        && !restored.some(
          (explorer) => explorer.nodeId === activeCluster.nodeId,
        )
      ) {
        return [
          createClusterCurlExplorer(activeCluster),
          ...restored,
        ];
      }
      if (restored.length > 0 || !standalone) {
        return restored;
      }
      const initialCluster = activeCluster ?? availableClusters[0];
      return initialCluster
        ? [createClusterCurlExplorer(initialCluster)]
        : [];
    },
  );
  const [draggedExplorerId, setDraggedExplorerId] = useState<string | null>(
    null,
  );

  useEffect(() => {
    savePinnedExplorers(explorers);
    saveWorkspaceExplorers(explorers);
    notifyRuntimeDashboardChanged();
  }, [explorers]);

  useEffect(() => {
    const availableIds = new Set(
      availableClusters.map((cluster) => cluster.nodeId),
    );
    const syncWorkspace = (event: StorageEvent) => {
      if (event.key !== CURL_WORKSPACE_STORAGE_KEY) return;
      setExplorers(
        parseStoredExplorers(event.newValue, false)
          .filter((explorer) => availableIds.has(explorer.nodeId)),
      );
    };
    globalThis.addEventListener("storage", syncWorkspace);
    return () => globalThis.removeEventListener("storage", syncWorkspace);
  }, [availableClusters]);

  const addExplorer = () => {
    const target = activeCluster
      ?? explorers[0]
      ?? availableClusters[0];
    if (!target) return;
    setExplorers((current) => [
      createClusterCurlExplorer(target),
      ...current,
    ]);
  };
  const addAdjacentExplorer = (explorer: ClusterCurlExplorerState) => {
    setExplorers((current) => {
      const index = current.findIndex(
        (candidate) => candidate.id === explorer.id,
      );
      const next = createClusterCurlExplorer({
        clusterName: explorer.clusterName,
        nodeId: explorer.nodeId,
      });
      if (index < 0) return [...current, next];
      return [
        ...current.slice(0, index + 1),
        next,
        ...current.slice(index + 1),
      ];
    });
  };

  const closeExplorer = (explorerId: string) => {
    setExplorers((current) => removeExplorer(current, explorerId));
  };
  const updateExplorer = (next: ClusterCurlExplorerState) => {
    setExplorers((current) => replaceExplorer(current, next));
  };
  const dropExplorer = (targetId: string) => {
    if (!draggedExplorerId) return;
    setExplorers((current) => moveDockItem(
      current,
      draggedExplorerId,
      targetId,
    ));
    setDraggedExplorerId(null);
  };
  const moveExplorer = (explorerId: string, offset: -1 | 1) => {
    setExplorers((current) => moveDockItemBy(
      current,
      explorerId,
      offset,
    ));
  };

  if (availableClusters.length === 0 && explorers.length === 0) return null;
  if (standalone && explorers.length === 0) return null;

  return (
    <section
      aria-label="Cluster curl explorers"
      className={[
        "cluster-curl-dock",
        standalone ? "standalone" : "",
      ].filter(Boolean).join(" ")}
    >
      <div className="cluster-curl-panes">
        {explorers.map((explorer, index) => (
          <ClusterCurlExplorer
            availableClusters={availableClusters}
            explorer={explorer}
            index={index}
            key={explorer.id}
            onAdd={addAdjacentExplorer}
            onClose={() => closeExplorer(explorer.id)}
            onDragEnd={() => setDraggedExplorerId(null)}
            onDragStart={() => setDraggedExplorerId(explorer.id)}
            onDrop={() => dropExplorer(explorer.id)}
            onMove={(offset) => moveExplorer(explorer.id, offset)}
            onUpdate={updateExplorer}
            primary={index === 0}
            standalone={standalone}
          />
        ))}
        {explorers.length === 0 && !standalone ? (
          <div className="cluster-curl-empty-toolbar">
            <strong className="cluster-curl-dock-label">Cluster curl</strong>
            <div className="cluster-curl-pane-actions">
              <ClusterCurlGlobalActions
                onAdd={addExplorer}
              />
            </div>
          </div>
        ) : null}
      </div>
    </section>
  );
}
