import {
  Suspense,
  lazy,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  projectConfigResourceGraph,
} from "@opensearch-migrations/config-edit-core";
import {
  Activity,
  CircleAlert,
  Copy,
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
  getConfigurationDocument,
  getConfigurationSchema,
  getHealth,
  getManageState,
  getOperations,
  reconcileManageState,
  setGatePreapproval,
  type ApprovalGateSummary,
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
import {
  editTarget,
  navigationResourceId,
  projectEditSnapshot,
  removableEditTarget,
  resourceDraftChangeStates,
  resourceValidationStates,
  settledRenameResourceId,
} from "../features/configuration/editProjection";
import {
  BROWSER_CONFIG_DRAFT_QUERY_KEY,
  createBrowserConfigDraft,
  markBrowserConfigDraftStale,
  type BrowserConfigDraft,
} from "../features/configuration/browserDraft";
import {
  navigationConnectivityTargets,
  StandaloneConnectivityLogs,
  type ConnectivityNavigationStates,
  useConnectivityChecks,
} from "../features/configuration/connectivityChecks";
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
import { ClusterCurlDock } from "../features/workspace/ClusterCurlDock";
import {
  clusterCurlTargets,
} from "../features/workspace/clusterCurlTargets";
import { RuntimeStatusDock } from "../features/workspace/RuntimeStatusDock";
import {
  copyRuntimeDashboardUrl,
  hydrateRuntimeDashboardFromHash,
  RUNTIME_DASHBOARD_CHANGE_EVENT,
  syncRuntimeDashboardUrl,
} from "../features/workspace/runtimeDashboardState";
import { LogPanel } from "../features/logviewer/LogPanel";
import { StatusIndicator } from "../features/status/StatusIndicator";
import {
  activeResetTargetIds,
  presentActiveResets,
} from "../features/status/operationPresentation";


const ConfigEditor = lazy(async () => {
  const module = await import("../features/configuration/ConfigEditor");
  return { default: module.ConfigEditor };
});


const HISTORY_GUARD_KEY = "__workflowManageGuard";
const HISTORY_GUARD_MESSAGE =
  "Leave Workflow Manage? Active operations will continue in the cluster.";
const PROMPTED_APPROVALS_KEY = "workflow-manage-prompted-approvals";


interface EditContext {
  resourceId: string;
  targetId: string;
  removalTargetId?: string;
}


interface LinkedNavigationEntry {
  nodeId: string | null;
  editTargetId: string | null;
  label: string;
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


function hasPendingConfiguration(snapshot: ManageSnapshot): boolean {
  // Mirrors the server's value-summary derivation from configPresence
  // instead of matching its presentation strings.
  return Object.values(snapshot.nodes).some((node) => {
    if (node.kind !== "resource") return false;
    if (node.comparisons.some((comparison) => comparison.pendingChanged)) {
      return true;
    }
    const presence = node.configPresence ?? {};
    if (!("pending" in presence)) return false;
    const deployed = presence.deployed ?? true;
    const submitted = presence.submitted ?? deployed;
    return presence.pending !== submitted;
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
    pendingConfiguration: (
      snapshot.configurationPending ?? hasPendingConfiguration(snapshot)
    ),
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
  const [savedConfigurationRevision, setSavedConfigurationRevision] =
    useState<string | null>(null);
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
  const noteSavedConfiguration = useCallback((persistedRevision: string) => {
    setSavedConfigurationRevision(persistedRevision);
  }, []);
  const eventConnection = useManageEvents(
    queryClient,
    noteSavedConfiguration,
  );
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
  const selectionInitializedRef = useRef(false);
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
  const pendingApprovalClaims = useRef(new Map<string, number>());
  const [approvalCenterProblem, setApprovalCenterProblem] = useState("");
  // A ref, not state: prompt bookkeeping never needs to trigger a render.
  const promptedApprovalsRef = useRef<Set<string> | null>(null);
  promptedApprovalsRef.current ??= promptedApprovalKeys();
  const promptedApprovals = promptedApprovalsRef.current;
  const [resourceAdds, setResourceAdds] =
    useState<ResourceAddController | null>(null);
  const [connectivityStates, setConnectivityStates] =
    useState<ConnectivityNavigationStates>({});
  const [pendingResourceAdditions, setPendingResourceAdditions] =
    useState<PendingResourceAddition[]>([]);
  const [pendingResourceRenames, setPendingResourceRenames] =
    useState<PendingResourceRename[]>([]);
  const [editGlobalActionsTarget, setEditGlobalActionsTarget] =
    useState<HTMLDivElement | null>(null);
  const editExitRef = useRef<(() => void) | null>(null);
  const editSubmitRef = useRef<(() => void) | null>(null);
  // An optimistic add moves the selection onto a resource that may never
  // exist, so remember where the user was in case the server rejects it.
  const selectionRef = useRef<{
    selectedId: string | null;
    editContext: EditContext | null;
  }>({ selectedId: null, editContext: null });
  const addReturnSelections = useRef(new Map<string, {
    selectedId: string | null;
    editContext: EditContext | null;
  }>());
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
  const browserConfigDraft = useQuery({
    queryKey: BROWSER_CONFIG_DRAFT_QUERY_KEY,
    queryFn: async () => {
      const [document, schema] = await Promise.all([
        getConfigurationDocument(),
        getConfigurationSchema(),
      ]);
      return createBrowserConfigDraft(
        document,
        undefined,
        schema.unifiedSchema,
      );
    },
    enabled: true,
    staleTime: Infinity,
  });
  const provisionalRuntimeConnectivityTargets = useMemo(
    () => navigationConnectivityTargets(
      Object.values(state.data?.nodes ?? {}),
    ),
    [state.data?.nodes],
  );
  const runtimeConnectivity = useConnectivityChecks(
    browserConfigDraft.data,
    null,
    500,
    !editContext,
    provisionalRuntimeConnectivityTargets,
  );
  const resetTargetIds = useMemo(
    () => activeResetTargetIds(operations.data),
    [operations.data],
  );
  const blockingGateCount = (approvalGates.data?.gates ?? []).filter(
    (gate) => gate.state === "blocking",
  ).length;
  const observedState = useMemo(
    () => presentActiveResets(state.data, resetTargetIds),
    [resetTargetIds, state.data],
  );

  useEffect(() => {
    if (!savedConfigurationRevision) return;
    const current = queryClient.getQueryData<BrowserConfigDraft>(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
    );
    if (!editContext) {
      if (current?.persistedRevision !== savedConfigurationRevision) {
        queryClient.removeQueries({
          queryKey: BROWSER_CONFIG_DRAFT_QUERY_KEY,
        });
      }
      return;
    }
    if (!current || current.persistedRevision === savedConfigurationRevision) {
      return;
    }
    if (current.dirty) {
      queryClient.setQueryData(
        BROWSER_CONFIG_DRAFT_QUERY_KEY,
        markBrowserConfigDraftStale(current, savedConfigurationRevision),
      );
      return;
    }
    let cancelled = false;
    void Promise.all([
      getConfigurationDocument(),
      getConfigurationSchema(),
    ])
      .then(([document, schema]) => {
        if (
          cancelled
          || document.persistedRevision !== savedConfigurationRevision
        ) {
          return;
        }
        queryClient.setQueryData(
          BROWSER_CONFIG_DRAFT_QUERY_KEY,
          createBrowserConfigDraft(
            document,
            undefined,
            schema.unifiedSchema,
          ),
        );
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [
    editContext,
    queryClient,
    savedConfigurationRevision,
  ]);

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
  const configurationState = useMemo(
    () => (
      observedState && browserConfigDraft.data
        ? projectConfigResourceGraph(observedState, browserConfigDraft.data)
        : observedState
    ),
    [browserConfigDraft.data, observedState],
  );
  const displayedState = useMemo(
    () => (
      observedState && editContext
        ? projectEditSnapshot(
          configurationState ?? observedState,
          pendingResourceAdditions,
          pendingResourceRenames,
        )
        : overviewState
    ),
    [
      configurationState,
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
    if (selectedId && displayedState.nodes[selectedId]) {
      selectionInitializedRef.current = true;
      return;
    }
    if (editContext) {
      if (selectedId) setSelectedId(null);
      return;
    }
    if (!selectionInitializedRef.current) {
      selectionInitializedRef.current = true;
      setSelectedId(firstSelectableId(displayedState));
      return;
    }
    if (selectedId) setSelectedId(null);
  }, [displayedState, editContext, selectedId]);

  const selectedNode = useMemo(
    () => (
      selectedId && displayedState
        ? displayedState.nodes[selectedId] ?? null
        : null
    ),
    [displayedState, selectedId],
  );
  const selectedRemovalTargetId = useMemo(
    () => removableEditTarget(
      browserConfigDraft.data?.editState.nodes ?? [],
      selectedNode ? editTarget(selectedNode) : null,
    ),
    [browserConfigDraft.data?.editState.nodes, selectedNode],
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
  const activeValidationQuery = browserConfigDraft;
  const submitValidation = activeValidationQuery.data?.editState.validation;
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
      activeValidationQuery.isPending
      || activeValidationQuery.isError
      || submitValidation?.valid === false
    )
  );
  const submissionBlockedReason = submitActive
    ? "Submission in progress"
    : submissionAvailable && activeValidationQuery.isPending
      ? "Checking configuration"
      : submissionAvailable && activeValidationQuery.isError
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
    : submissionAvailable && activeValidationQuery.isPending
      ? "Checking configuration before submission"
      : submissionAvailable && activeValidationQuery.isError
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
  const registerEditSubmit = useCallback((handler: (() => void) | null) => {
    editSubmitRef.current = handler;
  }, []);
  const editStateSummary = useMemo(() => {
    if (!editContext) return null;
    const node = displayedState?.nodes[editContext.resourceId];
    const parts: string[] = [];
    const change = resourceDraftChanges[editContext.resourceId];
    if (change) parts.push(change.label);
    if (node?.configPresence?.deployed === false) {
      parts.push(node.valueSummary ?? "Not deployed yet");
    }
    return parts.length > 0 ? parts.join(" · ") : null;
  }, [displayedState, editContext, resourceDraftChanges]);
  const registerResourceAdds = useCallback((
    controller: ResourceAddController | null,
  ) => {
    setResourceAdds(controller);
  }, []);
  const resourceAddStarted = useCallback((
    addition: PendingResourceAddition,
  ) => {
    addReturnSelections.current.set(addition.id, selectionRef.current);
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
    const restore = addReturnSelections.current.get(addition.id);
    addReturnSelections.current.delete(addition.id);
    if (!applied) {
      setPendingResourceAdditions((current) => current.filter(
        (candidate) => candidate.id !== addition.id,
      ));
      // The provisional resource is gone; leaving the selection on it strands
      // the editor on a node that no longer exists.
      if (restore) {
        setSelectedId(restore.selectedId);
        setEditContext(restore.editContext);
      }
      return;
    }
    const resourceId = navigationResourceId(
      resourceNavigationState?.nodes ?? {},
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
  }, [resourceNavigationState?.nodes]);
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
    const resourceId = settledRenameResourceId(
      resourceNavigationState?.nodes ?? {},
      rename,
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
  }, [resourceNavigationState?.nodes]);
  const resourceDraftReverted = useCallback(() => {
    const addedIds = new Set(
      pendingResourceAdditions.map((addition) => addition.id),
    );
    const renamedResources = new Map(
      pendingResourceRenames.map((rename) => [rename.id, rename]),
    );
    setPendingResourceAdditions([]);
    setPendingResourceRenames([]);
    setSelectedId((current) => {
      if (!current) return current;
      if (addedIds.has(current)) return null;
      return renamedResources.get(current)?.oldId ?? current;
    });
    setEditContext((current) => {
      if (!current) return current;
      if (addedIds.has(current.resourceId)) {
        return {
          resourceId: "",
          targetId: "edit:workflowConfiguration",
        };
      }
      const rename = renamedResources.get(current.resourceId);
      return rename ? {
        resourceId: rename.oldId,
        targetId: rename.oldEditTargetId,
      } : current;
    });
  }, [pendingResourceAdditions, pendingResourceRenames]);

  useEffect(() => {
    if (!browserConfigDraft.data) return;
    const nodes = resourceNavigationState?.nodes ?? {};
    const settledAdditions = pendingResourceAdditions.flatMap((addition) => {
      const resourceId = navigationResourceId(nodes, addition.editTargetId);
      return resourceId ? [{ addition, resourceId }] : [];
    });
    const settledRenames = pendingResourceRenames.flatMap((rename) => {
      const resourceId = settledRenameResourceId(nodes, rename);
      return resourceId ? [{ rename, resourceId }] : [];
    });
    if (settledAdditions.length > 0 || settledRenames.length > 0) {
      setSelectedId((current) => {
        const addition = settledAdditions.find(
          (candidate) => candidate.addition.id === current,
        );
        if (addition) return addition.resourceId;
        const rename = settledRenames.find(
          (candidate) => candidate.rename.id === current,
        );
        return rename?.resourceId ?? current;
      });
      setEditContext((current) => {
        if (!current) return current;
        const addition = settledAdditions.find(
          (candidate) => candidate.addition.id === current.resourceId,
        );
        if (addition) {
          return {
            resourceId: addition.resourceId,
            targetId: addition.addition.editTargetId,
          };
        }
        const rename = settledRenames.find(
          (candidate) => candidate.rename.id === current.resourceId,
        );
        return rename ? {
          resourceId: rename.resourceId,
          targetId: rename.rename.editTargetId,
        } : current;
      });
    }
    setPendingResourceAdditions((current) => {
      const next = current.filter((addition) => (
        addition.status === "syncing"
        || !navigationResourceId(nodes, addition.editTargetId)
      ));
      return next.length === current.length ? current : next;
    });
    setPendingResourceRenames((current) => {
      const next = current.filter((rename) => (
        rename.status === "syncing"
        || !settledRenameResourceId(nodes, rename)
      ));
      return next.length === current.length ? current : next;
    });
  }, [
    browserConfigDraft.data,
    pendingResourceAdditions,
    pendingResourceRenames,
    resourceNavigationState?.nodes,
  ]);

  useEffect(() => {
    if (editContext) return;
    setPendingResourceAdditions([]);
    setPendingResourceRenames([]);
  }, [editContext]);

  useEffect(() => {
    selectionRef.current = { selectedId, editContext };
  }, [selectedId, editContext]);

  const startEditing = () => {
    if (!state.data) return;
    const editSource = configurationState ?? state.data;
    const resourceId = (
      selectedId && editSource.nodes[selectedId]
        ? selectedId
        : firstSelectableId(editSource)
    );
    const node = resourceId ? editSource.nodes[resourceId] : null;
    const targetId = node ? editTarget(node) : null;
    setLinkedNavigation([]);
    setSelectedId(targetId && node ? node.id : null);
    setEditContext({
      resourceId: targetId && node ? node.id : "",
      targetId: targetId ?? "edit:workflowConfiguration",
    });
  };
  const startDeleting = () => {
    if (
      !selectedNode
      || selectedNode.kind !== "resource"
      || !selectedRemovalTargetId
    ) return;
    setLinkedNavigation([]);
    setSelectedId(selectedNode.id);
    setEditContext({
      resourceId: selectedNode.id,
      targetId: selectedRemovalTargetId,
      removalTargetId: selectedRemovalTargetId,
    });
    setTreeOpen(false);
  };
  const clearRuntimeSelection = () => {
    setLinkedNavigation([]);
    setSelectedId(null);
    setTreeOpen(false);
  };
  const applyNodeSelection = (nodeId: string) => {
    const navigation = editContext
      ? resourceNavigationState ?? displayedState
      : displayedState;
    const node = navigation?.nodes[nodeId];
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
    const navigation = editContext
      ? resourceNavigationState ?? displayedState
      : displayedState;
    const targetId = navigation?.nodes[nodeId]
      ? editTarget(navigation.nodes[nodeId])
      : null;
    if (
      nodeId === selectedId
      && (!editContext || targetId === editContext.targetId)
    ) {
      return;
    }
    setLinkedNavigation([]);
    applyNodeSelection(nodeId);
  };
  const rememberLinkedOrigin = () => {
    const navigation = resourceNavigationState ?? displayedState;
    const node = selectedId ? navigation?.nodes[selectedId] : null;
    if (!node) return;
    const entry: LinkedNavigationEntry = {
      nodeId: selectedId,
      editTargetId: editContext?.targetId ?? null,
      label: node.label,
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
    if (
      !node
      || (
        node.id === selectedId
        && editContext?.targetId === targetId
      )
    ) {
      return;
    }
    rememberLinkedOrigin();
    setSelectedId(node.id);
    setEditContext({
      resourceId: node.id,
      targetId,
    });
    setTreeOpen(false);
  };
  const navigateCreatedEditTarget = (
    targetId: string,
    returnTargetId: string,
    returnLabel: string,
  ) => {
    const navigation = resourceNavigationState ?? displayedState;
    const node = Object.values(navigation?.nodes ?? {}).find(
      (candidate) => editTarget(candidate) === targetId,
    );
    if (!node) return false;
    const entry: LinkedNavigationEntry = {
      nodeId: selectedId,
      editTargetId: returnTargetId,
      label: returnLabel,
    };
    setLinkedNavigation((current) => {
      const previous = current.at(-1);
      return (
        previous?.editTargetId === entry.editTargetId
        && previous.nodeId === entry.nodeId
      )
        ? current
        : [...current, entry];
    });
    setSelectedId(node.id);
    setEditContext({
      resourceId: node.id,
      targetId,
    });
    setTreeOpen(false);
    return true;
  };
  const linkedNavigationNode = (entry: LinkedNavigationEntry) => {
    const navigation = resourceNavigationState ?? displayedState;
    if (entry.editTargetId) {
      const currentNode = Object.values(navigation?.nodes ?? {}).find(
        (candidate) => editTarget(candidate) === entry.editTargetId,
      );
      if (currentNode) return currentNode;
    }
    return entry.nodeId ? navigation?.nodes[entry.nodeId] ?? null : null;
  };
  let linkedBackIndex = -1;
  for (let index = linkedNavigation.length - 1; index >= 0; index -= 1) {
    const entry = linkedNavigation[index];
    const node = linkedNavigationNode(entry);
    if (
      (node || entry.editTargetId)
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
    ? linkedNavigationNode(linkedBackEntry)?.label
      ?? linkedBackEntry.label
    : null;
  const navigateLinkedBack = () => {
    if (!linkedBackEntry) return;
    const node = linkedNavigationNode(linkedBackEntry);
    if (!node && !linkedBackEntry.editTargetId) return;
    setLinkedNavigation(linkedNavigation.slice(0, linkedBackIndex));
    setSelectedId(node?.id ?? null);
    if (linkedBackEntry.editTargetId) {
      setEditContext({
        resourceId: node?.id ?? "",
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
  const claimPendingApprovals = (names: string[]) => {
    names.forEach((name) => pendingApprovalClaims.current.set(
      name,
      (pendingApprovalClaims.current.get(name) ?? 0) + 1,
    ));
    setPendingApprovalNames(new Set(pendingApprovalClaims.current.keys()));
  };
  const releasePendingApprovals = (names: string[]) => {
    names.forEach((name) => {
      const remaining = (pendingApprovalClaims.current.get(name) ?? 1) - 1;
      if (remaining > 0) pendingApprovalClaims.current.set(name, remaining);
      else pendingApprovalClaims.current.delete(name);
    });
    setPendingApprovalNames(new Set(pendingApprovalClaims.current.keys()));
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
    claimPendingApprovals(changed.map((gate) => gate.name));
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
      releasePendingApprovals(changed.map((gate) => gate.name));
    }
  };
  const approveBlockingGate = async (gate: ApprovalGateSummary) => {
    if (!gate.approvalTargetId) return;
    setApprovalCenterProblem("");
    claimPendingApprovals([gate.name]);
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
      releasePendingApprovals([gate.name]);
    }
  };
  const viewGateOutput = (gate: ApprovalGateSummary) => {
    const candidate = approvals.find((approval) => (
      approval.targetId === gate.approvalTargetId
    ));
    if (candidate) setApprovalOutput(candidate);
    else {
      setApprovalCenterProblem(
        "The output for this checkpoint is not available yet. "
        + "Refresh and try again.",
      );
    }
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
      || approvalCenterOpen
      || approvalOutput
      || document.visibilityState !== "visible"
      // Fallback for dialogs whose open state lives in child components
      // (e.g. the workspace reset dialog).
      || document.querySelector('[role="dialog"]')
    ) {
      return;
    }
    rememberApprovalPrompt(firstApproval.targetId, review.gateRevision);
    setApprovalDialogTargetId(firstApproval.targetId);
  }, [
    approvalCenterOpen,
    approvalDialogTargetId,
    approvalOutput,
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
          {editContext ? (
            <div
              className="edit-global-actions"
              ref={setEditGlobalActionsTarget}
            />
          ) : null}
          {editContext ? (
            <button
              aria-label="Save and submit"
              className="edit-mode-button submit-mode-button"
              onClick={() => editSubmitRef.current?.()}
              title="Save configuration, submit the workflow, and leave editing"
              type="button"
            >
              <Send aria-hidden="true" />
              <span>Save and submit</span>
            </button>
          ) : null}
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
              aria-label={blockingGateCount > 0
                ? `Approvals, ${blockingGateCount} blocking`
                : "Approvals"}
              className="edit-mode-button approvals-mode-button"
              disabled={!state.data}
              onClick={() => setApprovalCenterOpen(true)}
              title="Review all workflow approval checkpoints"
              type="button"
            >
              <ShieldCheck aria-hidden="true" />
              <span>Approvals</span>
              {blockingGateCount > 0
                ? <b aria-hidden="true">{blockingGateCount}</b>
                : null}
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
              || browserConfigDraft.isFetching
            }
            onClick={() => {
              void state.refetch();
              void operations.refetch();
              if (editContext || submissionAvailable) {
                void browserConfigDraft.refetch();
              }
            }}
            title="Refresh state"
            type="button"
          >
            <RefreshCw
              className={
                state.isFetching
                || operations.isFetching
                || browserConfigDraft.isFetching
                  ? "spin"
                  : ""
              }
            />
          </button>
          <button
            aria-expanded={treeOpen}
            aria-label={treeOpen ? "Close resources" : "Open resources"}
            className="icon-button mobile-tree-toggle"
            onClick={() => setTreeOpen((open) => !open)}
            type="button"
          >
            {treeOpen
              ? <X aria-hidden="true" />
              : <Menu aria-hidden="true" />}
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
          {visibleProblems.map((problem, index) => (
            <output
              className="state-banner problem-banner"
              key={`${problem.source}-${index}`}
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
                  connectivityStates={
                    editContext
                      ? connectivityStates
                      : runtimeConnectivity.navigationStates
                  }
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
                <Suspense
                  fallback={(
                    <section className="workspace shell-loading">
                      <LoaderCircle className="spin" />
                      <strong>Opening configuration</strong>
                    </section>
                  )}
                >
                  <ConfigEditor
                    globalActionsTarget={editGlobalActionsTarget}
                    initialTargetId={editContext.targetId}
                    initialRemovalTargetId={
                      editContext.removalTargetId ?? null
                    }
                    navigationBackLabel={linkedBackLabel}
                    onClose={() => {
                      setLinkedNavigation([]);
                      setEditContext(null);
                    }}
                    onExitReady={registerEditExit}
                    onSubmitReady={registerEditSubmit}
                    onNavigateBack={navigateLinkedBack}
                    onDraftReverted={resourceDraftReverted}
                    onResourceAddSettled={resourceAddSettled}
                    onResourceAddStarted={resourceAddStarted}
                    onResourceRenameSettled={resourceRenameSettled}
                    onResourceRenameStarted={resourceRenameStarted}
                    onResourceAddsReady={registerResourceAdds}
                    onNavigateEditTarget={navigateEditTarget}
                    onNavigateCreatedEditTarget={
                      navigateCreatedEditTarget
                    }
                    onInitialRemovalHandled={() => {
                      setEditContext((current) => (
                        current?.removalTargetId
                          ? {
                            resourceId: current.resourceId,
                            targetId: current.targetId,
                          }
                          : current
                      ));
                    }}
                    onConnectivityStatesChange={setConnectivityStates}
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
                    resourceId={editContext.resourceId}
                    resourceLabel={
                      (resourceNavigationState ?? displayedState)
                        .nodes[editContext.resourceId]?.label
                      ?? "resource"
                    }
                    resourceType={
                      (resourceNavigationState ?? displayedState)
                        .nodes[editContext.resourceId]?.resourceType
                      ?? "Workflow configuration"
                    }
                    resourceSyncing={selectedNode?.status === "syncing"}
                    stateSummary={editStateSummary}
                    navigationSnapshot={
                      resourceNavigationState ?? displayedState
                    }
                  />
                </Suspense>
              ) : selectedNode ? (
                <ResourceWorkspace
                  approvalGates={approvalGates.data?.gates ?? []}
                  approvalGatesLoading={approvalGates.isPending}
                  approvals={approvals}
                  key={selectedNode.id}
                  navigationBackLabel={linkedBackLabel}
                  node={selectedNode}
                  nodes={displayedState.nodes}
                  connectivityState={
                    runtimeConnectivity.navigationTargets[
                      editTarget(selectedNode) ?? ""
                    ]
                  }
                  onCheckConnectivity={(targetIds) => {
                    void runtimeConnectivity.start(targetIds);
                  }}
                  onDelete={
                    selectedRemovalTargetId ? startDeleting : undefined
                  }
                  onResourceDeletionStarted={clearRuntimeSelection}
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
                  workflowPhase={state.data?.workflow?.phase}
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
                planned={Boolean(editContext)}
                selectedNode={observedSelectedNode}
                snapshot={
                  editContext
                    ? displayedState
                    : observedState ?? state.data
                }
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


function StandaloneRuntimeDashboard() {
  useState(() => {
    hydrateRuntimeDashboardFromHash();
    return true;
  });
  const state = useQuery({
    queryKey: ["manage-state"],
    queryFn: getManageState,
  });
  const availableClusters = clusterCurlTargets(state.data?.nodes ?? {});
  useEffect(() => {
    const syncUrl = () => syncRuntimeDashboardUrl();
    syncUrl();
    globalThis.addEventListener(RUNTIME_DASHBOARD_CHANGE_EVENT, syncUrl);
    globalThis.addEventListener("storage", syncUrl);
    return () => {
      globalThis.removeEventListener(RUNTIME_DASHBOARD_CHANGE_EVENT, syncUrl);
      globalThis.removeEventListener("storage", syncUrl);
    };
  }, []);
  return (
    <main className="standalone-runtime-dashboard-page">
      <header className="runtime-dashboard-header">
        <div>
          <strong>Runtime dashboard</strong>
          <span>Pinned status and cluster requests</span>
        </div>
        <button
          aria-label="Copy runtime dashboard link"
          className="icon-button"
          onClick={() => void copyRuntimeDashboardUrl()}
          title="Copy a link that restores this dashboard"
          type="button"
        >
          <Copy aria-hidden="true" />
        </button>
      </header>
      {state.isPending ? (
        <div className="standalone-page-status">
          <LoaderCircle aria-hidden="true" className="spin" />
          Loading runtime resources
        </div>
      ) : null}
      {state.error ? (
        <div className="standalone-page-status error">
          {state.error instanceof Error
            ? state.error.message
            : "Runtime resources are unavailable."}
        </div>
      ) : null}
      {state.data ? (
        <div className="standalone-runtime-dashboard-docks">
          <RuntimeStatusDock
            activeNode={null}
            nodes={state.data.nodes}
            standalone
          />
          {availableClusters.length > 0 ? (
            <ClusterCurlDock
              activeCluster={null}
              availableClusters={availableClusters}
              standalone
            />
          ) : null}
        </div>
      ) : null}
    </main>
  );
}


export function App() {
  const params = new URLSearchParams(globalThis.location.search);
  if (globalThis.location.pathname === "/connectivity-logs") {
    return <StandaloneConnectivityLogs />;
  }
  const standaloneNodeId = (
    globalThis.location.pathname === "/logs"
      ? params.get("nodeId")
      : null
  );
  if (standaloneNodeId) {
    return <StandaloneLogs nodeId={standaloneNodeId} />;
  }
  if (
    globalThis.location.pathname === "/runtime-dashboard"
    || globalThis.location.pathname === "/cluster-curl"
  ) {
    return <StandaloneRuntimeDashboard />;
  }
  return <ManageApp />;
}
