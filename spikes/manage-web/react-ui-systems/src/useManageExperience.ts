import { useCallback, useEffect, useState } from "react";
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
import { useManageTree } from "../../react/src/useManageTree";

export type WorkspaceTab =
  | "overview"
  | "configuration"
  | "activity"
  | "logs"
  | "output";

function persistentAncestor(
  nodeId: string,
  nodes: Readonly<Record<string, TreeNode>>,
): string {
  let current = nodes[nodeId];
  while (current?.parentId && current.kind.startsWith("config")) {
    current = nodes[current.parentId];
  }
  return current?.id ?? "resource-proxy";
}

export function useManageExperience() {
  const tree = useManageTree();
  const [selectedId, setSelectedId] = useState("resource-proxy");
  const [focusedId, setFocusedId] = useState("resource-proxy");
  const [activeTab, setActiveTab] = useState<WorkspaceTab>("overview");
  const [logRunning, setLogRunning] = useState(false);
  const [logLines, setLogLines] = useState<ReadonlyArray<string>>([]);
  const [operations, setOperations] = useState<ReadonlyArray<OperationState>>(
    () => [createOperation()],
  );

  const selectedNode =
    tree.state.nodes[selectedId] ?? tree.state.nodes["resource-proxy"];
  const canAddTransform =
    tree.state.mode === "edit" &&
    !!tree.state.nodes["config-replayer"] &&
    !tree.state.nodes["config-transform"] &&
    !tree.transitioning;

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
    setOperations((current) =>
      [
        {
          id: `operation-${Date.now()}`,
          label,
          phase,
          state: "running" as const,
          progress: 12,
        },
        ...current,
      ].slice(0, 4),
    );
  }, []);

  const enterEditMode = useCallback((): void => {
    if (tree.state.mode === "inspect" && !tree.transitioning) {
      tree.applyPatches(ENTER_EDIT_MODE_PATCHES);
      setActiveTab("configuration");
    }
  }, [tree]);

  const leaveEditMode = useCallback((): void => {
    if (tree.state.mode !== "edit" || tree.transitioning) {
      return;
    }
    const patches: TreePatch[] = [];
    if (tree.state.nodes["config-proxy"]) {
      patches.push({
        type: "remove",
        nodeId: "config-proxy",
        announce: "Capture proxy configuration closed.",
      });
    }
    if (tree.state.nodes["config-replayer"]) {
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
      setSelectedId(persistentAncestor(selectedId, tree.state.nodes));
    }
    const focusedNode = tree.state.nodes[focusedId];
    if (focusedNode?.kind.startsWith("config")) {
      setFocusedId(persistentAncestor(focusedId, tree.state.nodes));
    }
    tree.applyPatches(patches);
    setActiveTab("overview");
  }, [focusedId, selectedId, selectedNode, tree]);

  const performCapability = useCallback(
    (capability: NonNullable<TreeNode["capabilities"]>[number]): void => {
      if (capability === "edit") {
        enterEditMode();
      } else if (capability === "logs") {
        setActiveTab("logs");
        setLogRunning(true);
      } else if (capability === "output") {
        setActiveTab("output");
      } else {
        beginOperation(
          capability === "approve"
            ? `Approve ${selectedNode.label}`
            : `Plan reset for ${selectedNode.label}`,
          capability === "approve"
            ? "Submitting approval"
            : "Calculating reset plan",
        );
        setActiveTab("activity");
      }
    },
    [beginOperation, enterEditMode, selectedNode.label],
  );

  return {
    ...tree,
    selectedId,
    selectedNode,
    focusedId,
    activeTab,
    logRunning,
    logLines,
    operations,
    canAddTransform,
    setSelectedId,
    setFocusedId,
    setActiveTab,
    setLogRunning,
    clearLogs: () => setLogLines([]),
    enterEditMode,
    leaveEditMode,
    addTransform: () => {
      if (canAddTransform) {
        tree.applyPatches([ADD_TRANSFORM_PATCH]);
      }
    },
    refresh: () => tree.applyPatch(STATUS_UPDATE_PATCH),
    review: () =>
      beginOperation(
        "Review pending configuration",
        "Checking schema and cluster state",
      ),
    performCapability,
  };
}
