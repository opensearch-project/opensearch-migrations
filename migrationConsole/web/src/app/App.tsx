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
  Send,
  ShieldCheck,
  X,
} from "lucide-react";

import {
  approveTarget,
  getApprovalGates,
  getApprovalReview,
  getConfigDraft,
  getHealth,
  getManageState,
  getOperations,
  reconcileManageState,
  setGatePreapproval,
  type ApprovalGateSummary,
  type ConfigDraft,
  type ManageNode,
  type ManageSnapshot,
} from "../api/client";
import { useManageEvents } from "../api/useManageEvents";
import { useOperationEvents } from "../api/useOperationEvents";
import { ActivityPanel } from "../features/activity/ActivityPanel";
import { ApprovalDialog } from "../features/actions/ResourceActionDialogs";
import { ApprovalCenterDialog } from "../features/actions/ApprovalCenterDialog";
import {
  approvalCandidates,
  type ApprovalCandidate,
} from "../features/actions/approvals";
import { ConfigEditor } from "../features/configuration/ConfigEditor";
import {
  editTarget,
  projectEditSnapshot,
  resourceDraftChangeStates,
  resourceValidationStates,
} from "../features/configuration/editProjection";
import type {
  PendingResourceAddition,
  PendingResourceRename,
  ResourceAddController,
} from "../features/configuration/resourceAdds";
import { ApprovalOutputDialog } from "../features/output/OutputPanel";
import { SubmitConfigDialog } from "../features/submission/SubmitConfigDialog";
import { ResourceTree } from "../features/tree/ResourceTree";
import {
  projectResourceNavigation,
  projectResourceView,
  RESOURCE_VIEW_OPTIONS,
  type ResourceViewMode,
} from "../features/tree/resourceView";
import { ResourceWorkspace } from "../features/workspace/ResourceWorkspace";
import { LogPanel } from "../features/logviewer/LogPanel";
import { StatusIndicator } from "../features/status/StatusIndicator";
import {
  activeResetTargetIds,
  presentActiveResets,
} from "../features/status/operationPresentation";


const HISTORY_GUARD_KEY = "__workflowManageGuard";
const HISTORY_GUARD_MESSAGE =
  "Leave Workflow Manage? Active operations will continue in the cluster.";
const PROMPTED_APPROVALS_KEY = "workflow-manage-prompted-approvals";


interface EditContext {
  resourceId: string;
  targetId: string;
}


interface LinkedNavigationEntry {
  nodeId: string;
  editTargetId: string | null;
}


interface SubmissionSignals {
  pendingConfiguration: boolean;
  missingResourceCount: number;
  failedResourceCount: number;
}


function firstSelectableId(snapshot: ManageSnapshot): string | null {
  const resource = Object.values(snapshot.nodes).find(
    (node) => node.kind === "resource",
  );
  return resource?.id ?? snapshot.rootIds[0] ?? null;
}


function workflowStepDescendants(
  snapshot: ManageSnapshot | null | undefined,
  node: ManageNode | null,
): ManageNode[] {
  if (!snapshot || !node) return [];
  const result: ManageNode[] = [];
  const pending: string[] = [...(node.childIds ?? [])];
  const visited = new Set<string>();
  while (pending.length > 0) {
    const childId = pending.shift();
    if (!childId || visited.has(childId)) continue;
    visited.add(childId);
    const child = snapshot.nodes[childId];
    if (!child || child.kind !== "workflow-step") continue;
    result.push(child);
    pending.push(...child.childIds);
  }
  return result;
}


function navigationResourceId(
  draft: ConfigDraft | undefined,
  targetId: string,
): string | null {
  const resource = Object.values(draft?.navigation?.nodes ?? {}).find(
    (node) => (
      ["resource", "config-definition"].includes(node.kind)
      && node.capabilities.some((capability) => (
        capability.kind === "edit"
        && capability.editTargetId === targetId
      ))
    ),
  );
  return resource?.id ?? null;
}


function hasPendingConfiguration(snapshot: ManageSnapshot): boolean {
  return Object.values(snapshot.nodes).some((node) => {
    if (node.kind !== "resource") return false;
    if (node.comparisons.some((comparison) => comparison.pendingChanged)) {
      return true;
    }
    const summary = (node.valueSummary ?? "").toLocaleLowerCase();
    return (
      summary.includes("pending submission")
      || summary.includes("will be orphaned")
      || /changes? to submit/.test(summary)
    );
  });
}


function configuredResourceIsMissing(
  node: ManageSnapshot["nodes"][string],
): boolean {
  if (node.kind !== "resource") return false;
  const presence = node.configPresence ?? {};
  const configured = "pending" in presence
    ? presence.pending
    : presence.submitted;
  return presence.deployed === false && configured === true;
}


function managedResourceHasFailed(
  node: ManageSnapshot["nodes"][string],
): boolean {
  if (node.kind !== "resource") return false;
  const status = node.status.toLocaleLowerCase();
  const phase = (node.phase ?? "").toLocaleLowerCase();
  return ["error", "failed"].includes(status)
    || ["error", "failed"].includes(phase);
}


function submissionSignals(snapshot?: ManageSnapshot): SubmissionSignals {
  if (!snapshot) {
    return {
      pendingConfiguration: false,
      missingResourceCount: 0,
      failedResourceCount: 0,
    };
  }
  const nodes = Object.values(snapshot.nodes);
  return {
    pendingConfiguration: hasPendingConfiguration(snapshot),
    missingResourceCount: nodes.filter(configuredResourceIsMissing).length,
    failedResourceCount: nodes.filter(managedResourceHasFailed).length,
  };
}


function submissionSignalText(signals: SubmissionSignals): string {
  const reasons = signals.pendingConfiguration ? ["Pending configuration"] : [];
  if (signals.missingResourceCount > 0) {
    const count = signals.missingResourceCount;
    reasons.push(`${count} configured resource${count === 1 ? " is" : "s are"} missing`);
  }
  if (signals.failedResourceCount > 0) {
    const count = signals.failedResourceCount;
    reasons.push(`${count} managed resource${count === 1 ? " has" : "s have"} failed`);
  }
  return reasons.join(" · ");
}


function isExpectedWorkflowReplacementProblem(
  problem: { source: string; message: string },
  submitActive: boolean,
): boolean {
  if (!submitActive || problem.source.toLocaleLowerCase() !== "argo") {
    return false;
  }
  const message = problem.message.toLocaleLowerCase();
  return message.includes("not found") || /\b404\b/.test(message);
}


function promptedApprovalKeys(): Set<string> {
  try {
    const stored: unknown = JSON.parse(
      globalThis.sessionStorage.getItem(PROMPTED_APPROVALS_KEY) ?? "[]",
    ) as unknown;
    return new Set(Array.isArray(stored) ? stored.map(String) : []);
  } catch {
    return new Set();
  }
}


function ManageApp() {
  const queryClient = useQueryClient();
  const health = useQuery({
    queryKey: ["system-health"],
    queryFn: getHealth,
    staleTime: 30_000,
  });
  const state = useQuery({
    queryKey: ["manage-state"],
    queryFn: getManageState,
    refetchInterval: 10_000,
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
        ? 2000
        : false
    ),
  });
  const approvalGates = useQuery({
    queryKey: ["approval-gates"],
    queryFn: getApprovalGates,
    refetchInterval: 10_000,
    retry: false,
  });
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [linkedNavigation, setLinkedNavigation] =
    useState<LinkedNavigationEntry[]>([]);
  const [treeOpen, setTreeOpen] = useState(false);
  const [resourceViewMode, setResourceViewMode] =
    useState<ResourceViewMode>("all");
  const [editContext, setEditContext] = useState<EditContext | null>(null);
  const [submitOpen, setSubmitOpen] = useState(false);
  const [approvalDialogTargetId, setApprovalDialogTargetId] =
    useState<string | null>(null);
  const [approvalCenterOpen, setApprovalCenterOpen] = useState(false);
  const [approvalOutput, setApprovalOutput] =
    useState<ApprovalCandidate | null>(null);
  const [pendingApprovalNames, setPendingApprovalNames] =
    useState<Set<string>>(new Set());
  const [approvalCenterProblem, setApprovalCenterProblem] = useState("");
  const [promptedApprovals] = useState(promptedApprovalKeys);
  const [resourceAdds, setResourceAdds] =
    useState<ResourceAddController | null>(null);
  const [pendingResourceAdditions, setPendingResourceAdditions] =
    useState<PendingResourceAddition[]>([]);
  const [pendingResourceRenames, setPendingResourceRenames] =
    useState<PendingResourceRename[]>([]);
  const editExitRef = useRef<(() => void) | null>(null);
  const submitSignals = useMemo(
    () => submissionSignals(state.data),
    [state.data],
  );
  const pendingConfiguration = submitSignals.pendingConfiguration;
  const submissionAvailable = pendingConfiguration
    || submitSignals.missingResourceCount > 0
    || submitSignals.failedResourceCount > 0;
  const recoveryAvailable = submitSignals.missingResourceCount > 0
    || submitSignals.failedResourceCount > 0;
  const resubmissionOnly = !pendingConfiguration && submissionAvailable;
  const submitSignalText = submissionSignalText(submitSignals);
  const configDraft = useQuery({
    queryKey: ["config-draft"],
    queryFn: getConfigDraft,
    enabled: editContext !== null || submissionAvailable,
    staleTime: Infinity,
  });
  const resetTargetIds = useMemo(
    () => activeResetTargetIds(operations.data),
    [operations.data],
  );
  const observedState = useMemo(
    () => presentActiveResets(state.data, resetTargetIds),
    [resetTargetIds, state.data],
  );

  useEffect(() => {
    const currentState = (
      typeof globalThis.history.state === "object"
      && globalThis.history.state !== null
        ? globalThis.history.state as Record<string, unknown>
        : {}
    );
    if (currentState[HISTORY_GUARD_KEY] !== "sentinel") {
      globalThis.history.replaceState(
        { ...currentState, [HISTORY_GUARD_KEY]: "base" },
        "",
        globalThis.location.href,
      );
      globalThis.history.pushState(
        { ...currentState, [HISTORY_GUARD_KEY]: "sentinel" },
        "",
        globalThis.location.href,
      );
    }

    const guardBackNavigation = () => {
      if (!globalThis.confirm(HISTORY_GUARD_MESSAGE)) {
        const state = (
          typeof globalThis.history.state === "object"
          && globalThis.history.state !== null
            ? globalThis.history.state as Record<string, unknown>
            : {}
        );
        globalThis.history.pushState(
          { ...state, [HISTORY_GUARD_KEY]: "sentinel" },
          "",
          globalThis.location.href,
        );
        return;
      }
      globalThis.removeEventListener("popstate", guardBackNavigation);
      globalThis.history.back();
    };

    globalThis.addEventListener("popstate", guardBackNavigation);
    return () => globalThis.removeEventListener(
      "popstate",
      guardBackNavigation,
    );
  }, []);

  const overviewState = useMemo(
    () => (
      observedState
        ? projectResourceView(observedState, resourceViewMode)
        : observedState
    ),
    [observedState, resourceViewMode],
  );
  const displayedState = useMemo(
    () => (
      observedState && editContext
        ? projectEditSnapshot(
          configDraft.data?.navigation ?? observedState,
          pendingResourceAdditions,
          pendingResourceRenames,
        )
        : overviewState
    ),
    [
      configDraft.data,
      editContext,
      overviewState,
      pendingResourceAdditions,
      pendingResourceRenames,
      observedState,
    ],
  );
  const displayedResourceCount = useMemo(
    () => Object.values(displayedState?.nodes ?? {}).filter(
      (node) => node.kind === "resource",
    ).length,
    [displayedState],
  );
  const resourceNavigationState = useMemo(
    () => (
      displayedState
        ? projectResourceNavigation(displayedState)
        : displayedState
    ),
    [displayedState],
  );
  const approvals = useMemo(
    () => approvalCandidates(state.data),
    [state.data],
  );
  const firstApproval = approvals[0] ?? null;
  const approvalPreview = useQuery({
    queryKey: ["approval-review", firstApproval?.targetId],
    queryFn: () => getApprovalReview(firstApproval?.targetId ?? ""),
    enabled: Boolean(firstApproval),
    retry: false,
  });

  useEffect(() => {
    if (!displayedState) return;
    setSelectedId((current) => (
      current && displayedState.nodes[current]
        ? current
        : editContext
          ? null
          : firstSelectableId(displayedState)
    ));
  }, [displayedState, editContext]);

  const selectedNode = useMemo(
    () => (
      selectedId && displayedState
        ? displayedState.nodes[selectedId] ?? null
        : null
    ),
    [displayedState, selectedId],
  );
  const resourceDraftChanges = useMemo(
    () => (
      displayedState && editContext
        ? resourceDraftChangeStates(displayedState)
        : {}
    ),
    [displayedState, editContext],
  );
  const resourceValidations = useMemo(
    () => (
      displayedState && editContext
        ? resourceValidationStates(displayedState)
        : {}
    ),
    [displayedState, editContext],
  );
  const observedSelectedNode = useMemo(
    () => (
      selectedId && observedState
        ? observedState.nodes[selectedId] ?? null
        : null
    ),
    [observedState, selectedId],
  );
  const selectedWorkflowSteps = useMemo(
    () => workflowStepDescendants(
      observedState,
      observedSelectedNode,
    ),
    [observedSelectedNode, observedState],
  );
  const submitActive = operations.data?.some((operation) => (
    operation.kind === "submit"
    && (
      operation.status === "queued"
      || operation.status === "running"
      || operation.status === "waiting"
    )
  )) ?? false;
  const visibleProblems = state.data?.problems.filter(
    (problem) => !isExpectedWorkflowReplacementProblem(
      problem,
      submitActive,
    ),
  ) ?? [];
  const submitValidation = configDraft.data?.editState.validation;
  const blockingDiagnosticCount = submitValidation?.diagnostics?.filter(
    (diagnostic) => (
      diagnostic.severity === "error"
      || diagnostic.severity === "required"
    ),
  ).length ?? 0;
  const submitErrorCount = Math.max(
    submitValidation?.errors?.length ?? 0,
    blockingDiagnosticCount,
  );
  const submitValidationBlocked = (
    submissionAvailable
    && (
      configDraft.isPending
      || configDraft.isError
      || submitValidation?.valid === false
    )
  );
  const submissionBlockedReason = submitActive
    ? "Submission in progress"
    : submissionAvailable && configDraft.isPending
      ? "Checking configuration"
      : submissionAvailable && configDraft.isError
        ? "Configuration validation unavailable"
        : submissionAvailable && submitValidation?.valid === false
          ? (
            submitErrorCount > 0
              ? `${submitErrorCount} configuration ${
                submitErrorCount === 1 ? "error" : "errors"
              }`
              : "Configuration has validation errors"
          )
          : null;
  const noSubmissionReason = (
    "Configuration is current; no resources are missing or failed"
  );
  const submitStatusText = submissionBlockedReason
    ?? (submissionAvailable ? submitSignalText : noSubmissionReason);
  const submitLabel = resubmissionOnly
    ? "Review and resubmit"
    : "Review and submit";
  const submitTitle = submitActive
    ? "A configuration submission is already in progress"
    : submissionAvailable && configDraft.isPending
      ? "Checking configuration before submission"
      : submissionAvailable && configDraft.isError
        ? "Configuration validation is unavailable"
        : submissionAvailable && submitValidation?.valid === false
          ? (
            submitErrorCount > 0
              ? `Resolve ${submitErrorCount} configuration ${
                submitErrorCount === 1 ? "error" : "errors"
              } before submitting`
              : "Resolve configuration errors before submitting"
          )
          : !submissionAvailable
            ? noSubmissionReason
            : resubmissionOnly
              ? "Review and resubmit the saved configuration"
              : "Review and submit pending configuration";
  const submitTooltip = (
    recoveryAvailable && !submissionBlockedReason
      ? submitTitle + ". " + submitStatusText
      : submitTitle
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
    setLinkedNavigation([]);
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
    const resourceId = navigationResourceId(
      currentDraft,
      addition.editTargetId,
    );
    setPendingResourceAdditions((current) => (
      resourceId
        ? current.filter((candidate) => candidate.id !== addition.id)
        : current.map((candidate) => (
          candidate.id === addition.id
            ? { ...candidate, status: "awaiting-draft" }
            : candidate
        ))
    ));
    setSelectedId(resourceId ?? addition.id);
    setEditContext({
      resourceId: resourceId ?? addition.id,
      targetId: addition.editTargetId,
    });
  }, [queryClient]);
  const resourceRenameStarted = useCallback((
    rename: PendingResourceRename,
  ) => {
    setLinkedNavigation([]);
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
    const currentDraft = queryClient.getQueryData<ConfigDraft>([
      "config-draft",
    ]);
    const resourceId = navigationResourceId(
      currentDraft,
      rename.editTargetId,
    );
    setPendingResourceRenames((current) => (
      resourceId
        ? current.filter((candidate) => candidate.oldId !== rename.oldId)
        : current.map((candidate) => (
          candidate.oldId === rename.oldId
            ? { ...candidate, status: "applied" }
            : candidate
        ))
    ));
    setSelectedId(resourceId ?? rename.id);
    setEditContext({
      resourceId: resourceId ?? rename.id,
      targetId: rename.editTargetId,
    });
  }, [queryClient]);

  useEffect(() => {
    if (!configDraft.data) return;
    setPendingResourceAdditions((current) => {
      const next = current.filter((addition) => (
        addition.status === "syncing"
        || !navigationResourceId(configDraft.data, addition.editTargetId)
      ));
      return next.length === current.length ? current : next;
    });
    setPendingResourceRenames((current) => {
      const next = current.filter((rename) => (
        rename.status === "syncing"
        || !navigationResourceId(configDraft.data, rename.editTargetId)
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
    setLinkedNavigation([]);
    setSelectedId(targetId && node ? node.id : null);
    setEditContext({
      resourceId: targetId && node ? node.id : "",
      targetId: targetId ?? "edit:workflowConfiguration",
    });
  };
  const applyNodeSelection = (nodeId: string) => {
    const node = displayedState?.nodes[nodeId];
    if (!node) return false;
    if (editContext) {
      const targetId = editTarget(node);
      if (!targetId) return false;
      setEditContext({
        resourceId: nodeId,
        targetId,
      });
    }
    setSelectedId(nodeId);
    setTreeOpen(false);
    return true;
  };
  const selectNode = (nodeId: string) => {
    if (nodeId === selectedId) return;
    setLinkedNavigation([]);
    applyNodeSelection(nodeId);
  };
  const rememberLinkedOrigin = () => {
    if (!selectedId || !displayedState?.nodes[selectedId]) return;
    const entry: LinkedNavigationEntry = {
      nodeId: selectedId,
      editTargetId: editContext?.targetId ?? null,
    };
    setLinkedNavigation((current) => {
      const previous = current.at(-1);
      return (
        previous?.nodeId === entry.nodeId
        && previous.editTargetId === entry.editTargetId
      )
        ? current
        : [...current, entry];
    });
  };
  const navigateLinkedNode = (nodeId: string) => {
    if (nodeId === selectedId || !displayedState?.nodes[nodeId]) return;
    rememberLinkedOrigin();
    applyNodeSelection(nodeId);
  };
  const navigateEditTarget = (targetId: string) => {
    const navigation = resourceNavigationState ?? displayedState;
    const node = Object.values(navigation?.nodes ?? {}).find(
      (candidate) => editTarget(candidate) === targetId,
    );
    if (!node || node.id === selectedId) return;
    rememberLinkedOrigin();
    setSelectedId(node.id);
    setEditContext({
      resourceId: node.id,
      targetId,
    });
    setTreeOpen(false);
  };
  let linkedBackIndex = -1;
  for (let index = linkedNavigation.length - 1; index >= 0; index -= 1) {
    const entry = linkedNavigation[index];
    const node = displayedState?.nodes[entry.nodeId];
    if (
      node
      && Boolean(entry.editTargetId) === Boolean(editContext)
    ) {
      linkedBackIndex = index;
      break;
    }
  }
  const linkedBackEntry = linkedBackIndex >= 0
    ? linkedNavigation[linkedBackIndex]
    : null;
  const linkedBackLabel = linkedBackEntry
    ? displayedState?.nodes[linkedBackEntry.nodeId]?.label ?? null
    : null;
  const navigateLinkedBack = () => {
    if (!linkedBackEntry) return;
    const node = displayedState?.nodes[linkedBackEntry.nodeId];
    if (!node) return;
    setLinkedNavigation(linkedNavigation.slice(0, linkedBackIndex));
    setSelectedId(node.id);
    if (linkedBackEntry.editTargetId) {
      setEditContext({
        resourceId: node.id,
        targetId: linkedBackEntry.editTargetId,
      });
    }
    setTreeOpen(false);
  };
  const editApprovalResource = (candidate: ApprovalCandidate) => {
    const node = state.data?.nodes[candidate.nodeId];
    const targetId = candidate.editTargetId ?? (
      node ? editTarget(node) : null
    );
    if (!node || !targetId) return;
    setLinkedNavigation([]);
    setApprovalDialogTargetId(null);
    setSelectedId(node.id);
    setEditContext({
      resourceId: node.id,
      targetId,
    });
    setTreeOpen(false);
  };
  const setPreapprovals = async (
    gates: ApprovalGateSummary[],
    preapproved: boolean,
  ) => {
    const changed = gates.filter((gate) => (
      gate.toggleable && gate.approved !== preapproved
    ));
    if (changed.length === 0) return;
    setApprovalCenterProblem("");
    setPendingApprovalNames((current) => new Set([
      ...current,
      ...changed.map((gate) => gate.name),
    ]));
    try {
      await Promise.all(changed.map((gate) => setGatePreapproval(
        gate.name,
        gate.gateRevision,
        preapproved,
      )));
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["approval-gates"] }),
        queryClient.invalidateQueries({ queryKey: ["manage-state"] }),
      ]);
    } catch (error) {
      setApprovalCenterProblem(
        error instanceof Error ? error.message : String(error),
      );
      void queryClient.invalidateQueries({ queryKey: ["approval-gates"] });
    } finally {
      const names = new Set(changed.map((gate) => gate.name));
      setPendingApprovalNames((current) => new Set(
        [...current].filter((name) => !names.has(name)),
      ));
    }
  };
  const approveBlockingGate = async (gate: ApprovalGateSummary) => {
    if (!gate.approvalTargetId) return;
    setApprovalCenterProblem("");
    setPendingApprovalNames((current) => new Set([
      ...current,
      gate.name,
    ]));
    try {
      await approveTarget(
        gate.approvalTargetId,
        gate.gateRevision,
      );
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["approval-gates"] }),
        queryClient.invalidateQueries({ queryKey: ["manage-state"] }),
        queryClient.invalidateQueries({ queryKey: ["operations"] }),
      ]);
    } catch (error) {
      setApprovalCenterProblem(
        error instanceof Error ? error.message : String(error),
      );
    } finally {
      setPendingApprovalNames((current) => {
        const next = new Set(current);
        next.delete(gate.name);
        return next;
      });
    }
  };
  const viewGateOutput = (gate: ApprovalGateSummary) => {
    const candidate = approvals.find((approval) => (
      approval.targetId === gate.approvalTargetId
    ));
    if (candidate) setApprovalOutput(candidate);
  };
  const persistPromptedApprovals = useCallback(() => {
    try {
      globalThis.sessionStorage.setItem(
        PROMPTED_APPROVALS_KEY,
        JSON.stringify([...promptedApprovals]),
      );
    } catch {
      // Session storage is an enhancement; the persistent banner remains.
    }
  }, [promptedApprovals]);
  const rememberApprovalPrompt = useCallback((
    targetId: string,
    gateRevision?: string,
  ) => {
    promptedApprovals.add(`${targetId}@${gateRevision ?? "*"}`);
    persistPromptedApprovals();
  }, [persistPromptedApprovals, promptedApprovals]);

  useEffect(() => {
    const review = approvalPreview.data;
    if (!firstApproval || !review || approvalDialogTargetId) return;
    const key = `${firstApproval.targetId}@${review.gateRevision}`;
    if (
      promptedApprovals.has(key)
      || promptedApprovals.has(`${firstApproval.targetId}@*`)
    ) {
      return;
    }
    if (
      editContext
      || submitOpen
      || document.visibilityState !== "visible"
      || document.querySelector('[role="dialog"]')
    ) {
      return;
    }
    rememberApprovalPrompt(firstApproval.targetId, review.gateRevision);
    setApprovalDialogTargetId(firstApproval.targetId);
  }, [
    approvalDialogTargetId,
    approvalPreview.data,
    editContext,
    firstApproval,
    promptedApprovals,
    rememberApprovalPrompt,
    submitOpen,
  ]);

  useEffect(() => {
    if (!approvalDialogTargetId) return;
    if (approvals.some(
      (candidate) => candidate.targetId === approvalDialogTargetId,
    )) {
      return;
    }
    setApprovalDialogTargetId(approvals[0]?.targetId ?? null);
  }, [approvalDialogTargetId, approvals]);

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
            <StatusIndicator status={state.data.workflow.phase} />
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
                else {
                  setLinkedNavigation([]);
                  setEditContext(null);
                }
              } else {
                startEditing();
              }
            }}
            title={editContext
              ? "Review unsaved changes and leave editing"
              : "Edit workflow configuration"}
            type="button"
          >
            {editContext
              ? <LogOut aria-hidden="true" />
              : <Pencil aria-hidden="true" />}
            <span>{editContext ? "Exit editing" : "Edit configuration"}</span>
          </button>
          {!editContext ? (
            <button
              aria-label="Approvals"
              className="edit-mode-button approvals-mode-button"
              disabled={!state.data}
              onClick={() => setApprovalCenterOpen(true)}
              title="Review all workflow approval checkpoints"
              type="button"
            >
              <ShieldCheck aria-hidden="true" />
              <span>Approvals</span>
              {(approvalGates.data?.gates ?? []).some((gate) => (
                gate.state === "blocking"
              )) ? (
                <b>
                  {approvalGates.data?.gates.filter((gate) => (
                    gate.state === "blocking"
                  )).length}
                </b>
              ) : null}
            </button>
          ) : null}
          {!editContext ? (
            <>
              <button
                aria-describedby="submit-status-reason"
                aria-label={submitLabel}
                className="edit-mode-button submit-mode-button"
                disabled={
                  !submissionAvailable
                  || submitActive
                  || submitValidationBlocked
                }
                onClick={() => setSubmitOpen(true)}
                title={submitTooltip}
                type="button"
              >
                <Send aria-hidden="true" />
                <span>{submitLabel}</span>
              </button>
              <output
                className="sr-only"
                id="submit-status-reason"
              >
                {submitStatusText}
              </output>
            </>
          ) : null}
          <button
            aria-label="Refresh state"
            className="icon-button"
            disabled={
              state.isFetching
              || operations.isFetching
              || configDraft.isFetching
            }
            onClick={() => {
              void state.refetch();
              void operations.refetch();
              if (editContext || submissionAvailable) {
                void configDraft.refetch();
              }
            }}
            title="Refresh state"
            type="button"
          >
            <RefreshCw
              className={
                state.isFetching
                || operations.isFetching
                || configDraft.isFetching
                  ? "spin"
                  : ""
              }
            />
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
      </header>
      {submitOpen ? (
        <SubmitConfigDialog
          intent={resubmissionOnly ? "resubmit" : "submit"}
          onClose={() => setSubmitOpen(false)}
          onSubmitted={() => setSubmitOpen(false)}
          reason={resubmissionOnly ? submitSignalText : undefined}
        />
      ) : null}
      {
        approvalCenterOpen
        && !approvalDialogTargetId
        && !approvalOutput
          ? (
            <ApprovalCenterDialog
              error={
                approvalGates.isError
                  ? approvalGates.error.message
                  : approvalCenterProblem || null
              }
              inventory={approvalGates.data}
              loading={approvalGates.isPending}
              onClose={() => setApprovalCenterOpen(false)}
              onApprove={(gate) => {
                void approveBlockingGate(gate);
              }}
              onToggle={(gate, preapproved) => {
                void setPreapprovals([gate], preapproved);
              }}
              onToggleAll={(gates, preapproved) => {
                void setPreapprovals(gates, preapproved);
              }}
              onViewOutput={viewGateOutput}
              pendingNames={pendingApprovalNames}
            />
            )
          : null
      }
      {approvalOutput ? (
        <ApprovalOutputDialog
          approval={approvalOutput}
          onClose={() => setApprovalOutput(null)}
        />
      ) : null}
      {
        approvalDialogTargetId
        && approvals.length > 0
        && !approvalOutput
          ? (
        <ApprovalDialog
          candidates={approvals}
          initialTargetId={approvalDialogTargetId}
          key={approvalDialogTargetId}
          onClose={() => {
            setApprovalDialogTargetId(null);
          }}
          onEdit={editApprovalResource}
          onViewOutput={setApprovalOutput}
        />
            )
          : null
      }
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
          {health.isError ? (
            <output className="state-banner problem-banner">
              <CircleAlert aria-hidden="true" />
              <strong>Workflow Manage server unavailable</strong>
              <span>Health checks are failing. State may be stale.</span>
            </output>
          ) : null}
          {eventConnection !== "live" ? (
            <output className="state-banner problem-banner">
              <CircleAlert aria-hidden="true" />
              <strong>
                {eventConnection === "reconnecting"
                  ? "Live updates interrupted"
                  : "Connecting to live updates"}
              </strong>
              <span>
                {eventConnection === "reconnecting"
                  ? "Reconnecting; use refresh for the latest state."
                  : "State will update automatically once connected."}
              </span>
            </output>
          ) : null}
          {state.data.stale ? (
            <output className="state-banner stale-banner">
              <CircleAlert aria-hidden="true" />
              <strong>Showing last known cluster state</strong>
              <span>{state.data.refreshError?.message}</span>
            </output>
          ) : null}
          {visibleProblems.map((problem) => (
            <output
              className="state-banner problem-banner"
              key={`${problem.source}-${problem.message}`}
            >
              <CircleAlert aria-hidden="true" />
              <strong>{problem.source}</strong>
              <span>{problem.message}</span>
            </output>
          ))}
          {approvalCenterProblem && !approvalCenterOpen ? (
            <output className="state-banner problem-banner">
              <CircleAlert aria-hidden="true" />
              <strong>Approval update failed</strong>
              <span>{approvalCenterProblem}</span>
            </output>
          ) : null}
          {firstApproval ? (
            <section
              aria-label="Approval required"
              className="state-banner approval-banner"
            >
              <ShieldCheck aria-hidden="true" />
              <div>
                <strong>
                  Action required
                  {approvals.length > 1 ? ` (${approvals.length})` : ""}
                </strong>
                {approvalPreview.data ? (
                  <>
                    <span className="approval-context">
                      <b>
                        {approvalPreview.data.resourceName
                          ?? firstApproval.nodeLabel}
                      </b>
                      <span>{approvalPreview.data.stage}</span>
                    </span>
                    <small>{approvalPreview.data.effect}</small>
                    {approvalPreview.data.reason ? (
                      <small className="approval-reason">
                        {approvalPreview.data.reason}
                      </small>
                    ) : null}
                  </>
                ) : (
                  <span>{firstApproval.label}</span>
                )}
              </div>
              <button
                onClick={() => setApprovalDialogTargetId(
                  firstApproval.targetId,
                )}
                type="button"
              >
                <ShieldCheck aria-hidden="true" />
                Review required actions
              </button>
            </section>
          ) : null}
          {!editContext && displayedState.rootIds.length === 0 ? (
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
                        : `${displayedResourceCount} resources`}
                    </span>
                  </div>
                </header>
                <ResourceTree
                  changeStates={resourceDraftChanges}
                  onSelect={selectNode}
                  presentation={editContext ? "configuration" : "runtime"}
                  resourceAdds={editContext ? resourceAdds : null}
                  selectedId={selectedId}
                  snapshot={resourceNavigationState ?? displayedState}
                  validationStates={resourceValidations}
                  viewTransitionKey={
                    editContext
                      ? "configuration"
                      : `overview:${resourceViewMode}`
                  }
                />
                {!editContext ? (
                  <fieldset
                    aria-label="Resource state view"
                    className="resource-view-switcher"
                  >
                    {RESOURCE_VIEW_OPTIONS.map((option) => (
                      <button
                        aria-pressed={resourceViewMode === option.mode}
                        key={option.mode}
                        onClick={() => setResourceViewMode(option.mode)}
                        title={option.description}
                        type="button"
                      >
                        {option.label}
                      </button>
                    ))}
                  </fieldset>
                ) : null}
              </section>
              {editContext ? (
                <ConfigEditor
                  initialTargetId={editContext.targetId}
                  navigationBackLabel={linkedBackLabel}
                  onClose={() => {
                    setLinkedNavigation([]);
                    setEditContext(null);
                  }}
                  onExitReady={registerEditExit}
                  onNavigateBack={navigateLinkedBack}
                  onResourceAddSettled={resourceAddSettled}
                  onResourceAddStarted={resourceAddStarted}
                  onResourceRenameSettled={resourceRenameSettled}
                  onResourceRenameStarted={resourceRenameStarted}
                  onResourceAddsReady={registerResourceAdds}
                  onNavigateEditTarget={navigateEditTarget}
                  onSubmitted={() => {
                    setLinkedNavigation([]);
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
                <ResourceWorkspace
                  approvalGates={approvalGates.data?.gates ?? []}
                  approvalGatesLoading={approvalGates.isPending}
                  approvals={approvals}
                  navigationBackLabel={linkedBackLabel}
                  node={selectedNode}
                  onEdit={startEditing}
                  onNavigateBack={navigateLinkedBack}
                  onRequestApproval={setApprovalDialogTargetId}
                  onSelect={navigateLinkedNode}
                  onTogglePreapprovals={(gates, preapproved) => {
                    void setPreapprovals(gates, preapproved);
                  }}
                  operations={operations.data ?? []}
                  pendingPreapprovalNames={pendingApprovalNames}
                  resetInProgress={resetTargetIds.has(selectedNode.id)}
                  workflowSteps={selectedWorkflowSteps}
                />
              ) : (
                <section className="workspace empty-state">
                  <h2>Select a resource</h2>
                </section>
              )}
              <ActivityPanel
                approvals={approvals}
                operations={operations.data ?? []}
                onReviewApproval={setApprovalDialogTargetId}
                onSelectNode={selectNode}
                onViewApprovalOutput={setApprovalOutput}
                selectedNode={observedSelectedNode}
                snapshot={observedState ?? state.data}
              />
            </main>
          )}
        </>
      ) : null}
    </div>
  );
}


function StandaloneLogs({ nodeId }: Readonly<{ nodeId: string }>) {
  return (
    <main className="standalone-log-page">
      <LogPanel
        nodeId={nodeId}
        onClose={() => globalThis.close()}
        standalone
      />
    </main>
  );
}


export function App() {
  const params = new URLSearchParams(globalThis.location.search);
  const standaloneNodeId = (
    globalThis.location.pathname === "/logs"
      ? params.get("nodeId")
      : null
  );
  if (standaloneNodeId) {
    return <StandaloneLogs nodeId={standaloneNodeId} />;
  }
  return <ManageApp />;
}
