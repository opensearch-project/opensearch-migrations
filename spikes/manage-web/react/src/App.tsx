import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Activity,
  AlertTriangle,
  Check,
  ClipboardCheck,
  FileOutput,
  Gauge,
  ListTree,
  Logs,
  Pencil,
  RefreshCw,
  RotateCcw,
  Settings2,
  ShieldCheck,
  SquareActivity,
  X,
} from "lucide-react";
import {
  ADD_TRANSFORM_PATCH,
  ENTER_EDIT_MODE_PATCHES,
  STATUS_UPDATE_PATCH,
  createLogLine,
  createOperation,
  type OperationState,
  type TreeNode,
  type TreePatch,
} from "@manage-spike/shared";
import { ConfigurationPanel } from "./ConfigurationPanel";
import { LogViewer } from "./LogViewer";
import { OperationDrawer } from "./OperationDrawer";
import { ResourceTree } from "./ResourceTree";
import { useManageTree } from "./useManageTree";

type WorkspaceTab = "overview" | "configuration" | "activity" | "logs" | "output";

const TABS: ReadonlyArray<{
  id: WorkspaceTab;
  label: string;
  icon: typeof Gauge;
}> = [
  { id: "overview", label: "Overview", icon: Gauge },
  { id: "configuration", label: "Configuration", icon: Settings2 },
  { id: "activity", label: "Activity", icon: Activity },
  { id: "logs", label: "Logs", icon: Logs },
  { id: "output", label: "Output", icon: FileOutput },
];

function collectDiagnostics(
  nodes: Readonly<Record<string, TreeNode>>,
  node: TreeNode,
): ReadonlyArray<TreeNode> {
  const diagnostics: TreeNode[] = [];
  const visit = (nodeId: string): void => {
    const current = nodes[nodeId];
    if (!current) {
      return;
    }
    if (current.kind === "diagnostic") {
      diagnostics.push(current);
    }
    current.childIds.forEach(visit);
  };
  node.childIds.forEach(visit);
  return diagnostics;
}

function Overview({
  node,
  nodes,
}: {
  node: TreeNode;
  nodes: Readonly<Record<string, TreeNode>>;
}) {
  const diagnostics = collectDiagnostics(nodes, node);
  return (
    <div className="overview">
      <dl className="facts-grid">
        <div>
          <dt>Phase</dt>
          <dd>{node.phase ?? "Not started"}</dd>
        </div>
        <div>
          <dt>Status</dt>
          <dd className={`status-text status-${node.severity}`}>
            {node.severity}
          </dd>
        </div>
        <div>
          <dt>Current value</dt>
          <dd>{node.valueSummary ?? "No value reported"}</dd>
        </div>
        <div>
          <dt>Resource ID</dt>
          <dd className="mono">{node.id}</dd>
        </div>
      </dl>
      <section className="detail-section">
        <h3>Diagnostics</h3>
        {diagnostics.length > 0 ? (
          <div className="diagnostic-list">
            {diagnostics.map((diagnostic) => (
              <details className="diagnostic-item" key={diagnostic.id}>
                <summary>
                  <AlertTriangle aria-hidden="true" />
                  <span>{diagnostic.label}</span>
                  <span className="diagnostic-severity">
                    {diagnostic.severity}
                  </span>
                </summary>
                <p>{diagnostic.description}</p>
                {diagnostic.diagnostic ? (
                  <strong>{diagnostic.diagnostic}</strong>
                ) : null}
              </details>
            ))}
          </div>
        ) : (
          <div className="inline-empty">
            <Check aria-hidden="true" />
            No diagnostics for this selection.
          </div>
        )}
      </section>
      <section className="detail-section dependency-section">
        <h3>Dependencies</h3>
        <div className="dependency-row">
          <span>capture-proxy</span>
          <span className="dependency-line" />
          <span>captured-traffic</span>
          <span className="dependency-line" />
          <strong>{node.label}</strong>
        </div>
      </section>
    </div>
  );
}

function ActivityPanel({
  operations,
}: {
  operations: ReadonlyArray<OperationState>;
}) {
  return (
    <div className="activity-list">
      {operations.map((operation) => (
        <div className="activity-row" key={operation.id}>
          <span className={`activity-marker activity-${operation.state}`} />
          <div>
            <strong>{operation.label}</strong>
            <p>{operation.phase}</p>
          </div>
          <span>{operation.state}</span>
        </div>
      ))}
      <div className="activity-row">
        <span className="activity-marker activity-succeeded" />
        <div>
          <strong>Capture proxy observed</strong>
          <p>Cluster state matched the submitted configuration.</p>
        </div>
        <span>18 min ago</span>
      </div>
    </div>
  );
}

function OutputPanel() {
  return (
    <div className="output-panel">
      <div className="output-stage">
        <span className="stage-order">01</span>
        <div>
          <strong>Evaluate capture</strong>
          <p>Target compatibility and capture prerequisites</p>
        </div>
        <span className="stage-state success">Complete</span>
      </div>
      <div className="output-stage">
        <span className="stage-order">02</span>
        <div>
          <strong>Migrate live traffic</strong>
          <p>Capture, buffer, transform, and replay</p>
        </div>
        <span className="stage-state running">Running</span>
      </div>
      <pre className="structured-output">
        <code>{`target:
  endpoint: search-target:9200
replay:
  accepted: 14,208
  failed: 3
  checkpoint: 2026-08-11T14:22:38Z`}</code>
      </pre>
    </div>
  );
}

function getPersistentAncestor(
  nodeId: string,
  nodes: Readonly<Record<string, TreeNode>>,
): string {
  let current = nodes[nodeId];
  while (current?.parentId && current.kind.startsWith("config")) {
    current = nodes[current.parentId];
  }
  return current?.id ?? "resource-proxy";
}

export function App() {
  const {
    state,
    announcement,
    insertedIds,
    transitioning,
    applyPatch,
    applyPatches,
  } = useManageTree();
  const [selectedId, setSelectedId] = useState("resource-proxy");
  const [focusedId, setFocusedId] = useState("resource-proxy");
  const [activeTab, setActiveTab] = useState<WorkspaceTab>("overview");
  const [logRunning, setLogRunning] = useState(false);
  const [logLines, setLogLines] = useState<ReadonlyArray<string>>([]);
  const [operations, setOperations] = useState<ReadonlyArray<OperationState>>(
    () => [createOperation()],
  );

  const selectedNode =
    state.nodes[selectedId] ?? state.nodes["resource-proxy"];
  const canAddTransform =
    state.mode === "edit" &&
    !!state.nodes["config-replayer"] &&
    !state.nodes["config-transform"] &&
    !transitioning;

  useEffect(() => {
    if (!logRunning) {
      return;
    }
    const interval = window.setInterval(() => {
      setLogLines((current) => [
        ...current.slice(-149),
        createLogLine(current.length),
      ]);
    }, 420);
    return () => window.clearInterval(interval);
  }, [logRunning]);

  useEffect(() => {
    const interval = window.setInterval(() => {
      setOperations((current) =>
        current.map((operation) => {
          if (operation.state !== "running") {
            return operation;
          }
          const progress = Math.min(operation.progress + 9, 92);
          return progress >= 92
            ? {
                ...operation,
                progress,
                state: "waiting",
                phase: "Waiting for cluster state",
              }
            : { ...operation, progress };
        }),
      );
    }, 720);
    return () => window.clearInterval(interval);
  }, []);

  const beginOperation = useCallback((label: string, phase: string): void => {
    const operation: OperationState = {
      id: `operation-${Date.now()}`,
      label,
      phase,
      state: "running",
      progress: 12,
    };
    setOperations((current) => [operation, ...current].slice(0, 4));
  }, []);

  const enterEditMode = (): void => {
    if (state.mode === "inspect" && !transitioning) {
      applyPatches(ENTER_EDIT_MODE_PATCHES);
    }
  };

  const leaveEditMode = (): void => {
    if (state.mode !== "edit" || transitioning) {
      return;
    }
    const patches: TreePatch[] = [];
    if (state.nodes["config-proxy"]) {
      patches.push({
        type: "remove",
        nodeId: "config-proxy",
        announce: "Capture proxy configuration closed.",
      });
    }
    if (state.nodes["config-replayer"]) {
      patches.push({
        type: "remove",
        nodeId: "config-replayer",
        announce: "Traffic replayer configuration closed.",
      });
    }
    patches.push({
      type: "set-mode",
      mode: "inspect",
      announce: "Returned to live resource inspection.",
    });

    if (selectedNode.kind.startsWith("config")) {
      setSelectedId(getPersistentAncestor(selectedId, state.nodes));
    }
    const focusedNode = state.nodes[focusedId];
    if (focusedNode?.kind.startsWith("config")) {
      setFocusedId(getPersistentAncestor(focusedId, state.nodes));
    }
    applyPatches(patches);
  };

  const addTransform = (): void => {
    if (canAddTransform) {
      applyPatches([ADD_TRANSFORM_PATCH]);
    }
  };

  const performCapability = (capability: NonNullable<TreeNode["capabilities"]>[number]) => {
    if (capability === "edit") {
      enterEditMode();
    } else if (capability === "logs") {
      setActiveTab("logs");
      setLogRunning(true);
    } else if (capability === "output") {
      setActiveTab("output");
    } else if (capability === "approve") {
      beginOperation(`Approve ${selectedNode.label}`, "Submitting approval");
      setActiveTab("activity");
    } else if (capability === "reset") {
      beginOperation(`Plan reset for ${selectedNode.label}`, "Calculating reset plan");
      setActiveTab("activity");
    }
  };

  const capabilityDetails = {
    edit: { label: "Edit", icon: Pencil },
    approve: { label: "Approve", icon: ShieldCheck },
    reset: { label: "Reset", icon: RotateCcw },
    logs: { label: "Logs", icon: Logs },
    output: { label: "Output", icon: FileOutput },
  } as const;

  const workspacePanel = useMemo(() => {
    if (activeTab === "overview") {
      return <Overview node={selectedNode} nodes={state.nodes} />;
    }
    if (activeTab === "configuration") {
      return (
        <ConfigurationPanel
          state={state}
          selectedNode={selectedNode}
          canAddTransform={canAddTransform}
          transformAdded={!!state.nodes["config-transform"]}
          onAddTransform={addTransform}
        />
      );
    }
    if (activeTab === "activity") {
      return <ActivityPanel operations={operations} />;
    }
    if (activeTab === "logs") {
      return (
        <LogViewer
          lines={logLines}
          running={logRunning}
          onStart={() => setLogRunning(true)}
          onStop={() => setLogRunning(false)}
          onClear={() => setLogLines([])}
        />
      );
    }
    return <OutputPanel />;
  }, [
    activeTab,
    canAddTransform,
    logLines,
    logRunning,
    operations,
    selectedNode,
    state,
  ]);

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="brand-block">
          <span className="brand-mark">
            <SquareActivity aria-hidden="true" />
          </span>
          <div>
            <h1>Workflow Manage</h1>
            <span>migration · ma-demo</span>
          </div>
        </div>
        <div className="header-state">
          <span className="run-state">
            <span />
            Running
          </span>
          <span className="observed-time">Observed just now</span>
        </div>
        <div className="header-actions">
          <button
            className="icon-button"
            type="button"
            data-testid="refresh-control"
            aria-label="Simulate status refresh"
            title="Simulate status refresh"
            onClick={() => applyPatch(STATUS_UPDATE_PATCH)}
          >
            <RefreshCw aria-hidden="true" />
          </button>
          <button
            className={`button ${state.mode === "edit" ? "danger-subtle" : "secondary"}`}
            type="button"
            data-testid="edit-mode-control"
            disabled={transitioning}
            onClick={state.mode === "edit" ? leaveEditMode : enterEditMode}
          >
            {state.mode === "edit" ? (
              <X aria-hidden="true" />
            ) : (
              <Pencil aria-hidden="true" />
            )}
            {state.mode === "edit" ? "Leave edit mode" : "Edit configuration"}
          </button>
          <button
            className="button primary"
            type="button"
            disabled={state.mode !== "edit" || transitioning}
            onClick={() =>
              beginOperation(
                "Review pending configuration",
                "Checking schema and cluster state",
              )
            }
          >
            <ClipboardCheck aria-hidden="true" />
            Review changes
          </button>
        </div>
      </header>

      <div className="main-layout">
        <ResourceTree
          state={state}
          selectedId={selectedNode.id}
          focusedId={focusedId}
          insertedIds={insertedIds}
          onSelect={setSelectedId}
          onFocusChange={setFocusedId}
        />

        <main className="workspace">
          <div className="workspace-header">
            <div className="selection-heading">
              <span className={`selection-icon severity-${selectedNode.severity}`}>
                <ListTree aria-hidden="true" />
              </span>
              <div>
                <div className="selection-context">
                  <span>{selectedNode.kind.replace("-", " ")}</span>
                  <span>·</span>
                  <span>{selectedNode.phase ?? selectedNode.severity}</span>
                </div>
                <h2>{selectedNode.label}</h2>
                <p>{selectedNode.description ?? "Workflow resource"}</p>
              </div>
            </div>
            <div className="selection-actions" aria-label="Resource actions">
              {selectedNode.capabilities?.map((capability) => {
                const details = capabilityDetails[capability];
                const Icon = details.icon;
                return (
                  <button
                    className="button tertiary"
                    type="button"
                    key={capability}
                    disabled={
                      capability === "edit" &&
                      (state.mode === "edit" || transitioning)
                    }
                    onClick={() => performCapability(capability)}
                  >
                    <Icon aria-hidden="true" />
                    {details.label}
                  </button>
                );
              })}
            </div>
          </div>

          <div className="workspace-tabs" role="tablist" aria-label="Resource details">
            {TABS.map((tab) => {
              const Icon = tab.icon;
              return (
                <button
                  id={`tab-${tab.id}`}
                  role="tab"
                  type="button"
                  key={tab.id}
                  aria-selected={activeTab === tab.id}
                  aria-controls={`panel-${tab.id}`}
                  tabIndex={activeTab === tab.id ? 0 : -1}
                  onClick={() => setActiveTab(tab.id)}
                >
                  <Icon aria-hidden="true" />
                  {tab.label}
                  {tab.id === "logs" && logRunning ? (
                    <span className="tab-live-dot" aria-label="streaming" />
                  ) : null}
                </button>
              );
            })}
          </div>
          <div
            className="workspace-content"
            id={`panel-${activeTab}`}
            role="tabpanel"
            aria-labelledby={`tab-${activeTab}`}
          >
            {workspacePanel}
          </div>
        </main>

        <OperationDrawer operations={operations} />
      </div>
      <div className="sr-only" aria-live="polite" aria-atomic="true">
        {announcement}
      </div>
    </div>
  );
}
