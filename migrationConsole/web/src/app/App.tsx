import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Activity,
  CircleAlert,
  LogOut,
  LoaderCircle,
  Menu,
  Pencil,
  RefreshCw,
  X,
} from "lucide-react";

import {
  getConfigDraft,
  getHealth,
  getManageState,
  getOperations,
  reconcileManageState,
  type ConfigDraft,
  type ManageSnapshot,
} from "../api/client";
import { useManageEvents } from "../api/useManageEvents";
import { useOperationEvents } from "../api/useOperationEvents";
import { ActivityPanel } from "../features/activity/ActivityPanel";
import { ConfigEditor } from "../features/configuration/ConfigEditor";
import {
  editTarget,
  projectEditSnapshot,
  resourceValidationStates,
} from "../features/configuration/editProjection";
import type {
  PendingResourceAddition,
  PendingResourceRename,
  ResourceAddController,
} from "../features/configuration/resourceAdds";
import { ResourceTree } from "../features/tree/ResourceTree";
import { ResourceWorkspace } from "../features/workspace/ResourceWorkspace";


const HISTORY_GUARD_KEY = "__workflowManageGuard";
const HISTORY_GUARD_MESSAGE =
  "Leave Workflow Manage? Active operations will continue in the cluster.";


interface EditContext {
  resourceId: string;
  targetId: string;
}


function firstSelectableId(snapshot: ManageSnapshot): string | null {
  const resource = Object.values(snapshot.nodes).find(
    (node) => node.kind === "resource",
  );
  return resource?.id ?? snapshot.rootIds[0] ?? null;
}


function draftHasEditTarget(
  draft: ConfigDraft | undefined,
  targetId: string,
): boolean {
  if (!draft) return false;
  const visit = (nodes: ConfigDraft["editState"]["nodes"]): boolean => (
    nodes.some((node) => (
      node.id === targetId || visit(node.children ?? [])
    ))
  );
  return visit(draft.editState.nodes);
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
  useOperationEvents(queryClient);
  const operations = useQuery({
    queryKey: ["operations"],
    queryFn: getOperations,
    refetchInterval: (query) => (
      query.state.data?.some((operation) => (
        operation.status === "queued"
        || operation.status === "running"
        || operation.status === "waiting"
      ))
        ? 2_000
        : false
    ),
  });
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [treeOpen, setTreeOpen] = useState(false);
  const [editContext, setEditContext] = useState<EditContext | null>(null);
  const [resourceAdds, setResourceAdds] =
    useState<ResourceAddController | null>(null);
  const [pendingResourceAdditions, setPendingResourceAdditions] =
    useState<PendingResourceAddition[]>([]);
  const [pendingResourceRenames, setPendingResourceRenames] =
    useState<PendingResourceRename[]>([]);
  const editExitRef = useRef<(() => void) | null>(null);
  const configDraft = useQuery({
    queryKey: ["config-draft"],
    queryFn: getConfigDraft,
    enabled: editContext !== null,
    staleTime: Infinity,
  });

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

  const displayedState = useMemo(
    () => (
      state.data && editContext
        ? projectEditSnapshot(
          state.data,
          configDraft.data,
          pendingResourceAdditions,
          pendingResourceRenames,
        )
        : state.data
    ),
    [
      configDraft.data,
      editContext,
      pendingResourceAdditions,
      pendingResourceRenames,
      state.data,
    ],
  );

  useEffect(() => {
    if (!displayedState) return;
    setSelectedId((current) => (
      current && displayedState.nodes[current]
        ? current
        : firstSelectableId(displayedState)
    ));
  }, [displayedState]);

  const selectedNode = useMemo(
    () => (
      selectedId && displayedState
        ? displayedState.nodes[selectedId] ?? null
        : null
    ),
    [displayedState, selectedId],
  );
  const resourceValidations = useMemo(
    () => (
      displayedState && editContext
        ? resourceValidationStates(displayedState, configDraft.data)
        : {}
    ),
    [configDraft.data, displayedState, editContext],
  );
  const observedSelectedNode = useMemo(
    () => (
      selectedId && state.data
        ? state.data.nodes[selectedId] ?? null
        : null
    ),
    [selectedId, state.data],
  );

  const registerEditExit = useCallback((handler: (() => void) | null) => {
    editExitRef.current = handler;
  }, []);
  const registerResourceAdds = useCallback((
    controller: ResourceAddController | null,
  ) => {
    setResourceAdds(controller);
  }, []);
  const resourceAddStarted = useCallback((
    addition: PendingResourceAddition,
  ) => {
    setPendingResourceAdditions((current) => [
      ...current.filter((candidate) => candidate.id !== addition.id),
      addition,
    ]);
    setSelectedId(addition.id);
    setEditContext({
      resourceId: addition.id,
      targetId: addition.editTargetId,
    });
  }, []);
  const resourceAddSettled = useCallback((
    addition: PendingResourceAddition,
    applied: boolean,
  ) => {
    if (!applied) {
      setPendingResourceAdditions((current) => current.filter(
        (candidate) => candidate.id !== addition.id,
      ));
      return;
    }
    const currentDraft = queryClient.getQueryData<ConfigDraft>([
      "config-draft",
    ]);
    setPendingResourceAdditions((current) => (
      draftHasEditTarget(currentDraft, addition.editTargetId)
        ? current.filter((candidate) => candidate.id !== addition.id)
        : current.map((candidate) => (
          candidate.id === addition.id
            ? { ...candidate, status: "awaiting-draft" }
            : candidate
        ))
    ));
    setSelectedId(addition.id);
    setEditContext({
      resourceId: addition.id,
      targetId: addition.editTargetId,
    });
  }, [queryClient]);
  const resourceRenameStarted = useCallback((
    rename: PendingResourceRename,
  ) => {
    setPendingResourceRenames((current) => [
      ...current.filter((candidate) => candidate.oldId !== rename.oldId),
      rename,
    ]);
    setSelectedId(rename.id);
    setEditContext({
      resourceId: rename.id,
      targetId: rename.editTargetId,
    });
  }, []);
  const resourceRenameSettled = useCallback((
    rename: PendingResourceRename,
    applied: boolean,
  ) => {
    if (!applied) {
      setPendingResourceRenames((current) => current.filter(
        (candidate) => candidate.oldId !== rename.oldId,
      ));
      setSelectedId(rename.oldId);
      setEditContext({
        resourceId: rename.oldId,
        targetId: rename.oldEditTargetId,
      });
      return;
    }
    setPendingResourceRenames((current) => current.map((candidate) => (
      candidate.oldId === rename.oldId
        ? { ...candidate, status: "applied" }
        : candidate
    )));
    setSelectedId(rename.id);
    setEditContext({
      resourceId: rename.id,
      targetId: rename.editTargetId,
    });
  }, []);

  useEffect(() => {
    if (!configDraft.data) return;
    setPendingResourceAdditions((current) => {
      const next = current.filter((addition) => (
        addition.status === "syncing"
        || !draftHasEditTarget(configDraft.data, addition.editTargetId)
      ));
      return next.length === current.length ? current : next;
    });
  }, [configDraft.data]);

  useEffect(() => {
    if (editContext) return;
    setPendingResourceAdditions([]);
    setPendingResourceRenames([]);
  }, [editContext]);

  const startEditing = () => {
    if (!state.data) return;
    const resourceId = (
      selectedId && state.data.nodes[selectedId]
        ? selectedId
        : firstSelectableId(state.data)
    );
    const node = resourceId ? state.data.nodes[resourceId] : null;
    const targetId = node ? editTarget(node) : null;
    setEditContext({
      resourceId: targetId && node ? node.id : "",
      targetId: targetId ?? "edit:workflowConfiguration",
    });
  };

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
          <button
            aria-label={editContext ? "Exit editing" : "Edit configuration"}
            className={`edit-mode-button ${editContext ? "active" : ""}`}
            disabled={!state.data}
            onClick={() => {
              if (editContext) {
                if (editExitRef.current) editExitRef.current();
                else setEditContext(null);
              } else {
                startEditing();
              }
            }}
            title={editContext
              ? "Discard unsaved changes and leave editing"
              : "Edit workflow configuration"}
            type="button"
          >
            {editContext
              ? <LogOut aria-hidden="true" />
              : <Pencil aria-hidden="true" />}
            <span>{editContext ? "Exit editing" : "Edit configuration"}</span>
          </button>
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
      {state.isPending ? (
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
          {displayedState.rootIds.length === 0 ? (
            <main className="empty-state">
              <Activity aria-hidden="true" />
              <h2>No migration resources found</h2>
            </main>
          ) : (
            <main className="manage-layout">
              <section
                aria-label="Resource navigation"
                className={`tree-panel ${treeOpen ? "open" : ""}`}
              >
                <header className="panel-header">
                  <div>
                    <h2>{editContext ? "Configuration" : "Resources"}</h2>
                    <span>
                      {editContext
                        ? "Editing intended state"
                        : `${Object.keys(state.data.nodes).length} observed`}
                    </span>
                  </div>
                </header>
                <ResourceTree
                  onSelect={(nodeId) => {
                    const node = displayedState.nodes[nodeId];
                    if (editContext) {
                      const targetId = node ? editTarget(node) : null;
                      if (!targetId) return;
                      setEditContext({
                        resourceId: nodeId,
                        targetId,
                      });
                    }
                    setSelectedId(nodeId);
                    setTreeOpen(false);
                  }}
                  resourceAdds={editContext ? resourceAdds : null}
                  selectedId={selectedId}
                  snapshot={displayedState}
                  validationStates={resourceValidations}
                />
              </section>
              {editContext ? (
                <ConfigEditor
                  initialTargetId={editContext.targetId}
                  onClose={() => setEditContext(null)}
                  onExitReady={registerEditExit}
                  onResourceAddSettled={resourceAddSettled}
                  onResourceAddStarted={resourceAddStarted}
                  onResourceRenameSettled={resourceRenameSettled}
                  onResourceRenameStarted={resourceRenameStarted}
                  onResourceAddsReady={registerResourceAdds}
                  onSubmitted={() => {
                    setEditContext(null);
                    void queryClient.invalidateQueries({
                      queryKey: ["operations"],
                    });
                    void queryClient.invalidateQueries({
                      queryKey: ["manage-state"],
                    });
                  }}
                  removalState={
                    selectedNode?.status === "removed"
                      ? selectedNode.valueSummary ?? "Marked for removal"
                      : null
                  }
                  resourceLabel={
                    displayedState.nodes[editContext.resourceId]?.label
                    ?? "resource"
                  }
                  resourceSyncing={selectedNode?.status === "syncing"}
                />
              ) : selectedNode ? (
                <ResourceWorkspace node={selectedNode} />
              ) : (
                <section className="workspace empty-state">
                  <h2>Select a resource</h2>
                </section>
              )}
              <ActivityPanel
                operations={operations.data ?? []}
                selectedNode={observedSelectedNode}
                snapshot={state.data}
              />
            </main>
          )}
        </>
      ) : null}
    </div>
  );
}
