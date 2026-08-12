import { useEffect, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Activity,
  CircleAlert,
  LoaderCircle,
  Menu,
  RefreshCw,
  X,
} from "lucide-react";

import {
  getHealth,
  getManageState,
  reconcileManageState,
  type ManageSnapshot,
} from "../api/client";
import { useManageEvents } from "../api/useManageEvents";
import { ActivityPanel } from "../features/activity/ActivityPanel";
import { ConfigEditor } from "../features/configuration/ConfigEditor";
import { ResourceTree } from "../features/tree/ResourceTree";
import { ResourceWorkspace } from "../features/workspace/ResourceWorkspace";


const HISTORY_GUARD_KEY = "__workflowManageGuard";
const HISTORY_GUARD_MESSAGE =
  "Leave Workflow Manage? Active operations will continue in the cluster.";


function firstSelectableId(snapshot: ManageSnapshot): string | null {
  const resource = Object.values(snapshot.nodes).find(
    (node) => node.kind === "resource",
  );
  return resource?.id ?? snapshot.rootIds[0] ?? null;
}


export function App() {
  const queryClient = useQueryClient();
  const health = useQuery({
    queryKey: ["system-health"],
    queryFn: getHealth,
    staleTime: 30_000,
  });
  const state = useQuery({
    queryKey: ["manage-state"],
    queryFn: getManageState,
    structuralSharing: (previous, incoming) =>
      reconcileManageState(previous, incoming),
  });
  const eventConnection = useManageEvents(queryClient);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [treeOpen, setTreeOpen] = useState(false);
  const [editTargetId, setEditTargetId] = useState<string | null>(null);

  useEffect(() => {
    const currentState = (
      typeof window.history.state === "object" && window.history.state !== null
        ? window.history.state as Record<string, unknown>
        : {}
    );
    if (currentState[HISTORY_GUARD_KEY] !== "sentinel") {
      window.history.replaceState(
        { ...currentState, [HISTORY_GUARD_KEY]: "base" },
        "",
        window.location.href,
      );
      window.history.pushState(
        { ...currentState, [HISTORY_GUARD_KEY]: "sentinel" },
        "",
        window.location.href,
      );
    }

    const guardBackNavigation = () => {
      if (!window.confirm(HISTORY_GUARD_MESSAGE)) {
        const state = (
          typeof window.history.state === "object"
          && window.history.state !== null
            ? window.history.state as Record<string, unknown>
            : {}
        );
        window.history.pushState(
          { ...state, [HISTORY_GUARD_KEY]: "sentinel" },
          "",
          window.location.href,
        );
        return;
      }
      window.removeEventListener("popstate", guardBackNavigation);
      window.history.back();
    };

    window.addEventListener("popstate", guardBackNavigation);
    return () => window.removeEventListener(
      "popstate",
      guardBackNavigation,
    );
  }, []);

  useEffect(() => {
    if (!state.data) return;
    setSelectedId((current) => (
      current && state.data.nodes[current]
        ? current
        : firstSelectableId(state.data)
    ));
  }, [state.data]);

  const selectedNode = useMemo(
    () => (
      selectedId && state.data
        ? state.data.nodes[selectedId] ?? null
        : null
    ),
    [selectedId, state.data],
  );

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="brand-mark" aria-hidden="true">
          <Activity />
        </div>
        <div>
          <h1>Workflow Manage</h1>
          <p>{state.data?.namespace ?? "Migration orchestration"}</p>
        </div>
        {state.data?.workflow ? (
          <div className="workflow-state">
            <span className={`status-dot status-${state.data.workflow.phase.toLocaleLowerCase()}`} />
            <span>{state.data.workflow.name}</span>
            <strong>{state.data.workflow.phase}</strong>
          </div>
        ) : null}
        <div className="header-actions">
          <span className="revision" title="Manage state revision">
            {state.data?.revision ?? "waiting"}
          </span>
          <div className="server-state" aria-live="polite">
            <span
              className={`live-dot connection-${eventConnection}`}
              aria-hidden="true"
            />
            {eventConnection === "live" ? "Live" : (
              eventConnection === "reconnecting" ? "Reconnecting" : "Connecting"
            )}
          </div>
          <button
            aria-label="Refresh state"
            className="icon-button"
            disabled={state.isFetching}
            onClick={() => void state.refetch()}
            title="Refresh state"
            type="button"
          >
            <RefreshCw className={state.isFetching ? "spin" : ""} />
          </button>
          <button
            aria-label={treeOpen ? "Close resources" : "Open resources"}
            className="icon-button mobile-tree-toggle"
            onClick={() => setTreeOpen((open) => !open)}
            type="button"
          >
            {treeOpen ? <X /> : <Menu />}
          </button>
        </div>
        <div className="server-state health-state" aria-live="polite">
          {health.isPending ? (
            <>
              <LoaderCircle className="spin" aria-hidden="true" />
              Connecting to server
            </>
          ) : health.isError ? (
            <>
              <CircleAlert aria-hidden="true" />
              Server unavailable
            </>
          ) : (
            <>
              <span className="live-dot" aria-hidden="true" />
              Server ready
            </>
          )}
        </div>
      </header>
      {editTargetId ? (
        <ConfigEditor
          initialTargetId={editTargetId}
          onClose={() => setEditTargetId(null)}
        />
      ) : state.isPending ? (
        <main className="shell-loading">
          <LoaderCircle className="spin" aria-hidden="true" />
          <strong>Loading workflow state</strong>
        </main>
      ) : state.isError ? (
        <main className="shell-error">
          <CircleAlert aria-hidden="true" />
          <h2>Workflow state is unavailable</h2>
          <button onClick={() => void state.refetch()} type="button">
            Try again
          </button>
        </main>
      ) : state.data ? (
        <>
          {state.data.stale ? (
            <div className="state-banner stale-banner" role="status">
              <CircleAlert aria-hidden="true" />
              <strong>Showing last known cluster state</strong>
              <span>{state.data.refreshError?.message}</span>
            </div>
          ) : null}
          {state.data.problems.map((problem) => (
            <div className="state-banner problem-banner" key={`${problem.source}-${problem.message}`} role="status">
              <CircleAlert aria-hidden="true" />
              <strong>{problem.source}</strong>
              <span>{problem.message}</span>
            </div>
          ))}
          {state.data.rootIds.length === 0 ? (
            <main className="empty-state">
              <Activity aria-hidden="true" />
              <h2>No migration resources found</h2>
            </main>
          ) : (
            <main className="manage-layout">
              <section className={`tree-panel ${treeOpen ? "open" : ""}`}>
                <header className="panel-header">
                  <div>
                    <h2>Resources</h2>
                    <span>{Object.keys(state.data.nodes).length} observed</span>
                  </div>
                </header>
                <ResourceTree
                  onSelect={(nodeId) => {
                    setSelectedId(nodeId);
                    setTreeOpen(false);
                  }}
                  selectedId={selectedId}
                  snapshot={state.data}
                />
              </section>
              {selectedNode ? (
                <ResourceWorkspace
                  node={selectedNode}
                  onEdit={setEditTargetId}
                  snapshot={state.data}
                />
              ) : (
                <section className="workspace empty-state">
                  <h2>Select a resource</h2>
                </section>
              )}
              <ActivityPanel
                selectedNode={selectedNode}
                snapshot={state.data}
              />
            </main>
          )}
        </>
      ) : null}
    </div>
  );
}
