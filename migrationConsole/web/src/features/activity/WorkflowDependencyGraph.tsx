import {
  ChevronDown,
  ChevronRight,
  CircleAlert,
  FileOutput,
  LoaderCircle,
  ShieldCheck,
} from "lucide-react";
import {
  useCallback,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from "react";

import type {
  ManageSnapshot,
  Operation,
} from "../../api/client";
import type { ApprovalCandidate } from "../actions/approvals";
import { StatusIndicator } from "../status/StatusIndicator";
import { normalizedStatus, statusLabel } from "../status/status";
import {
  buildWorkflowGraph,
  type WorkflowGraphNode,
  type WorkflowGraphStep,
} from "./workflowGraph";
import {
  connectedPath,
  dependencyAnchorY,
  edgeId,
  groupDependencyRoutes,
} from "./workflowRouting";


type RouteState = "approval" | "blocked" | "normal" | "unknown";


interface BranchPath {
  d: string;
  targetId: string;
  targetY: number;
}


interface RoutedPath {
  branches: BranchPath[];
  d: string;
  id: string;
  sourceId: string;
  sourceX: number;
  sourceY: number;
  state: RouteState;
}


function operationForNode(
  operations: Operation[],
  nodeIds: string[],
): Operation | undefined {
  const latest = operations
    .filter((operation) => (
      operation.targetIds.some((targetId) => nodeIds.includes(targetId))
    ))
    .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))[0];
  return latest?.status === "succeeded" ? undefined : latest;
}


function graphNodeState(
  node: WorkflowGraphNode,
  approvals: ApprovalCandidate[],
): RouteState {
  if (approvals.some((candidate) => candidate.nodeId === node.id)) {
    return "approval";
  }
  if (node.status === "error" || node.status === "blocked") return "blocked";
  if (node.unresolved || node.status === "unknown") return "unknown";
  return "normal";
}


function workflowStepState(step: WorkflowGraphStep): string {
  return step.phase ?? statusLabel(step.status);
}


function workflowStepActive(step: WorkflowGraphStep): boolean {
  return normalizedStatus(step.status) !== "ok";
}


function parsedActivityAt(value: string | null | undefined): number {
  if (!value) return Number.NEGATIVE_INFINITY;
  const timestamp = Date.parse(value);
  return Number.isNaN(timestamp) ? Number.NEGATIVE_INFINITY : timestamp;
}


function latestActivityAt(
  ...values: (string | null | undefined)[]
): string | null {
  return values.reduce<string | null>((latest, value) => (
    parsedActivityAt(value) > parsedActivityAt(latest) ? value ?? null : latest
  ), null);
}


function formatActivityAt(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  }).format(date);
}


function GraphNode({
  graphNode,
  selectedNodeId,
  operation,
  approval,
  onReviewApproval,
  onViewApprovalOutput,
  onSelectNode,
  onActivate,
  onDeactivate,
  pathActive,
  pathMuted,
  register,
}: Readonly<{
  graphNode: WorkflowGraphNode;
  selectedNodeId: string | null;
  operation: Operation | undefined;
  approval: ApprovalCandidate | undefined;
  onReviewApproval: (targetId: string) => void;
  onViewApprovalOutput: (approval: ApprovalCandidate) => void;
  onSelectNode: (nodeId: string) => void;
  onActivate: (nodeId: string) => void;
  onDeactivate: () => void;
  pathActive: boolean;
  pathMuted: boolean;
  register: (nodeId: string, element: HTMLDivElement | null) => void;
}>) {
  const [completedStepsExpanded, setCompletedStepsExpanded] = useState(false);
  const state = graphNode.phase ?? statusLabel(graphNode.status);
  const resourceSelected = graphNode.id === selectedNodeId;
  const selectedStepId = graphNode.steps.some(
    (step) => step.id === selectedNodeId,
  )
    ? selectedNodeId
    : null;
  const selected = resourceSelected || Boolean(selectedStepId);
  const activeSteps = graphNode.steps.filter(workflowStepActive);
  const visibleSteps = completedStepsExpanded
    ? graphNode.steps
    : graphNode.steps.filter((step) => (
      workflowStepActive(step) || step.id === selectedStepId
    ));
  const hiddenStepCount = graphNode.steps.length - visibleSteps.length;
  const activityAt = latestActivityAt(
    graphNode.activityAt,
    operation?.updatedAt,
  );
  return (
    <div
      className={[
        "workflow-graph-node",
        `graph-state-${graphNodeState(graphNode, approval ? [approval] : [])}`,
        selected ? "selected" : "",
        graphNode.unresolved ? "unresolved" : "",
        pathActive ? "path-active" : "",
        pathMuted ? "path-muted" : "",
      ].filter(Boolean).join(" ")}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) onDeactivate();
      }}
      onFocus={() => onActivate(graphNode.id)}
      onMouseEnter={() => onActivate(graphNode.id)}
      onMouseLeave={onDeactivate}
      ref={(element) => register(graphNode.id, element)}
    >
      <button
        aria-current={selected ? "true" : undefined}
        aria-label={`Open ${graphNode.label}, ${state}`}
        className="workflow-graph-node-main"
        disabled={!graphNode.node}
        onClick={() => onSelectNode(graphNode.id)}
        type="button"
      >
        <StatusIndicator status={graphNode.status} />
        <span>
          <strong>{graphNode.label}</strong>
          <small>
            {graphNode.resourcePlural
              ? `${graphNode.resourcePlural} · `
              : ""}
            {graphNode.unresolved ? "Not found" : state}
          </small>
          {activityAt ? (
            <small className="workflow-graph-activity">
              <time
                dateTime={activityAt}
                title={new Date(activityAt).toLocaleString()}
              >
                Last activity {formatActivityAt(activityAt)}
              </time>
            </small>
          ) : null}
        </span>
      </button>
      {approval ? (
        <div className="workflow-graph-actions">
          {approval.outputTargetId ? (
            <button
              className="workflow-graph-action"
              onClick={() => onViewApprovalOutput(approval)}
              type="button"
            >
              <FileOutput aria-hidden="true" />
              View output
            </button>
          ) : null}
          <button
            className="workflow-graph-action"
            onClick={() => onReviewApproval(approval.targetId)}
            title={approval.immutable
              ? approval.immutableReason ?? approval.label
              : approval.label}
            type="button"
          >
            <ShieldCheck aria-hidden="true" />
            {approval.immutable ? "Reset required" : "Review approval"}
          </button>
        </div>
      ) : null}
      {graphNode.steps.length > 0 ? (
        <section
          aria-label={`Workflow steps for ${graphNode.label}`}
          className="workflow-graph-steps"
        >
          {visibleSteps.map((step) => {
            const stepState = workflowStepState(step);
            return (
              <button
                aria-current={step.id === selectedNodeId ? "true" : undefined}
                aria-label={`Open workflow step ${step.label}, ${stepState}`}
                className={[
                  "workflow-graph-step",
                  step.id === selectedNodeId ? "selected" : "",
                ].filter(Boolean).join(" ")}
                key={step.id}
                onClick={() => onSelectNode(step.id)}
                style={{
                  "--workflow-step-depth": step.depth,
                } as React.CSSProperties}
                type="button"
              >
                <StatusIndicator status={step.phase ?? step.status} />
                <span>
                  <strong>{step.label}</strong>
                  <small>
                    {stepState}
                    {step.activityAt ? (
                      <>
                        {" · "}
                        <time
                          dateTime={step.activityAt}
                          title={new Date(step.activityAt).toLocaleString()}
                        >
                          {formatActivityAt(step.activityAt)}
                        </time>
                      </>
                    ) : null}
                  </small>
                </span>
              </button>
            );
          })}
          {hiddenStepCount > 0 || completedStepsExpanded ? (
            <button
              className="workflow-graph-step-toggle"
              onClick={() => setCompletedStepsExpanded((current) => !current)}
              type="button"
            >
              {completedStepsExpanded
                ? <ChevronDown aria-hidden="true" />
                : <ChevronRight aria-hidden="true" />}
              {completedStepsExpanded
                ? activeSteps.length > 0
                  ? "Show active steps"
                  : "Hide completed steps"
                : `Show ${hiddenStepCount} completed step${
                  hiddenStepCount === 1 ? "" : "s"
                }`}
            </button>
          ) : null}
        </section>
      ) : null}
      {operation?.status === "failed" ? (
        <details className="workflow-graph-operation operation-failed">
          <summary>
            <CircleAlert aria-hidden="true" />
            <span>{operation.message || operation.label}</span>
            <ChevronRight
              aria-hidden="true"
              className="workflow-graph-operation-chevron"
            />
          </summary>
          <div className="workflow-graph-operation-detail">
            <strong>{operation.label}</strong>
            <p>{operation.detail || "No additional failure detail was reported."}</p>
          </div>
        </details>
      ) : operation ? (
        <div
          className={`workflow-graph-operation operation-${operation.status}`}
        >
          <LoaderCircle className="spin" aria-hidden="true" />
          <span>{operation.message || operation.label}</span>
        </div>
      ) : null}
    </div>
  );
}


export function WorkflowDependencyGraph({
  approvals,
  operations,
  onReviewApproval,
  onViewApprovalOutput,
  onSelectNode,
  selectedNodeId,
  snapshot,
}: Readonly<{
  approvals: ApprovalCandidate[];
  operations: Operation[];
  onReviewApproval: (targetId: string) => void;
  onViewApprovalOutput: (approval: ApprovalCandidate) => void;
  onSelectNode: (nodeId: string) => void;
  selectedNodeId: string | null;
  snapshot: ManageSnapshot;
}>) {
  const graph = useMemo(() => buildWorkflowGraph(snapshot), [snapshot]);
  const graphRef = useRef<HTMLDivElement>(null);
  const nodeElements = useRef(new Map<string, HTMLDivElement>());
  const [routedPaths, setRoutedPaths] = useState<RoutedPath[]>([]);
  const [hoveredNodeId, setHoveredNodeId] = useState<string | null>(null);
  const nodeById = useMemo(() => new Map(
    graph.nodes.map((node) => [node.id, node]),
  ), [graph.nodes]);
  const depthById = useMemo(() => new Map(
    graph.nodes.map((node) => [node.id, node.depth]),
  ), [graph.nodes]);
  const pathAnchorId = hoveredNodeId;
  const activePath = useMemo(
    () => connectedPath(graph.edges, pathAnchorId),
    [graph.edges, pathAnchorId],
  );
  const register = useCallback((
    nodeId: string,
    element: HTMLDivElement | null,
  ) => {
    if (element) nodeElements.current.set(nodeId, element);
    else nodeElements.current.delete(nodeId);
  }, []);

  useLayoutEffect(() => {
    const container = graphRef.current;
    if (!container) return undefined;
    let frame = 0;
    const update = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        const graphRect = container.getBoundingClientRect();
        const measuredEdges = graph.edges.flatMap((edge) => {
          const source = nodeElements.current.get(edge.sourceId);
          const target = nodeElements.current.get(edge.targetId);
          if (!source || !target) return [];
          const sourceRect = source.getBoundingClientRect();
          const targetRect = target.getBoundingClientRect();
          return [{
            ...edge,
            sourceY: dependencyAnchorY(
              sourceRect.top - graphRect.top,
              sourceRect.height,
              "outgoing",
            ),
            targetDepth: depthById.get(edge.targetId) ?? 0,
            targetY: dependencyAnchorY(
              targetRect.top - graphRect.top,
              targetRect.height,
              "incoming",
            ),
          }];
        });
        const routes = groupDependencyRoutes(measuredEdges);
        const firstNode = nodeElements.current.values().next().value;
        const nodeLeft = firstNode
          ? firstNode.getBoundingClientRect().left - graphRect.left
          : 72;
        const maxDepth = Math.max(
          1,
          ...routes.map((route) => route.depth),
        );
        const laneSpacing = Math.min(
          10,
          Math.max(5, (nodeLeft - 18) / (maxDepth + 1)),
        );
        const leftmostX = Math.max(
          12,
          nodeLeft - laneSpacing * (maxDepth + 1),
        );
        const paths = routes.flatMap((route): RoutedPath[] => {
          const source = nodeElements.current.get(route.sourceId);
          if (!source) return [];
          const sourceRect = source.getBoundingClientRect();
          const sourceX = sourceRect.left - graphRect.left;
          const sourceY = dependencyAnchorY(
            sourceRect.top - graphRect.top,
            sourceRect.height,
            "outgoing",
          );
          const laneX = leftmostX + route.depth * laneSpacing;
          const branches = route.targetIds.flatMap((targetId): BranchPath[] => {
            const target = nodeElements.current.get(targetId);
            if (!target) return [];
            const targetRect = target.getBoundingClientRect();
            const targetX = targetRect.left - graphRect.left;
            const targetY = dependencyAnchorY(
              targetRect.top - graphRect.top,
              targetRect.height,
              "incoming",
            );
            return [{
              d: `M ${laneX} ${targetY} H ${targetX}`,
              targetId,
              targetY,
            }];
          });
          const endY = Math.max(
            sourceY,
            ...branches.map((branch) => branch.targetY),
          );
          const sourceNode = nodeById.get(route.sourceId);
          return [{
            branches,
            d: [
              `M ${sourceX} ${sourceY}`,
              `H ${laneX}`,
              `V ${endY}`,
            ].join(" "),
            id: `${route.sourceId}:${route.depth}`,
            sourceId: route.sourceId,
            sourceX,
            sourceY,
            state: sourceNode
              ? graphNodeState(sourceNode, approvals)
              : "unknown",
          }];
        });
        setRoutedPaths(paths);
      });
    };
    update();
    const observer = typeof ResizeObserver === "undefined"
      ? null
      : new ResizeObserver(update);
    observer?.observe(container);
    nodeElements.current.forEach((element) => observer?.observe(element));
    globalThis.addEventListener("resize", update);
    return () => {
      cancelAnimationFrame(frame);
      observer?.disconnect();
      globalThis.removeEventListener("resize", update);
    };
  }, [approvals, depthById, graph.edges, nodeById]);

  if (graph.nodes.length === 0) {
    return (
      <p className="activity-empty">
        No migration resources are available for the dependency graph.
      </p>
    );
  }

  return (
    <section
      aria-label="Workflow dependency graph"
      className="workflow-dependency-graph"
      ref={graphRef}
    >
      <svg aria-hidden="true" className="workflow-graph-edges">
        <defs>
          <marker
            id="graph-arrow"
            markerHeight="5"
            markerWidth="5"
            orient="auto"
            refX="4"
            refY="2.5"
          >
            <path d="M 0 0 L 5 2.5 L 0 5 z" />
          </marker>
          <marker
            id="graph-arrow-approval"
            markerHeight="5"
            markerWidth="5"
            orient="auto"
            refX="4"
            refY="2.5"
          >
            <path d="M 0 0 L 5 2.5 L 0 5 z" />
          </marker>
          <marker
            id="graph-arrow-blocked"
            markerHeight="5"
            markerWidth="5"
            orient="auto"
            refX="4"
            refY="2.5"
          >
            <path d="M 0 0 L 5 2.5 L 0 5 z" />
          </marker>
        </defs>
        {routedPaths.map((route) => {
          const activeBranches = route.branches.filter((branch) => (
            activePath.edgeIds.has(edgeId({
              sourceId: route.sourceId,
              targetId: branch.targetId,
            }))
          ));
          const routeMuted = Boolean(pathAnchorId)
            && activeBranches.length === 0;
          const marker = `url(#graph-arrow${route.state === "normal"
            || route.state === "unknown"
            ? ""
            : `-${route.state}`})`;
          return (
            <g key={route.id}>
              <path
                className="workflow-graph-edge-halo"
                d={route.d}
              />
              <path
                className={[
                  "workflow-graph-edge",
                  `edge-${route.state}`,
                  routeMuted ? "path-muted" : "",
                  activeBranches.length > 0 ? "path-active" : "",
                ].filter(Boolean).join(" ")}
                d={route.d}
              />
              <circle
                className={[
                  "workflow-graph-port",
                  `edge-${route.state}`,
                  routeMuted ? "path-muted" : "",
                ].filter(Boolean).join(" ")}
                cx={route.sourceX}
                cy={route.sourceY}
                r="2.5"
              />
              {route.branches.map((branch) => {
                const branchActive = activePath.edgeIds.has(edgeId({
                  sourceId: route.sourceId,
                  targetId: branch.targetId,
                }));
                const branchMuted = Boolean(pathAnchorId) && !branchActive;
                return (
                  <g key={`${route.sourceId}-${branch.targetId}`}>
                    <path
                      className="workflow-graph-edge-halo"
                      d={branch.d}
                    />
                    <path
                      className={[
                        "workflow-graph-edge",
                        "workflow-graph-branch",
                        `edge-${route.state}`,
                        branchMuted ? "path-muted" : "",
                        branchActive ? "path-active" : "",
                      ].filter(Boolean).join(" ")}
                      d={branch.d}
                      markerEnd={marker}
                    />
                  </g>
                );
              })}
            </g>
          );
        })}
      </svg>
      {graph.nodes.map((graphNode) => (
        <div className="workflow-graph-item" key={graphNode.id}>
          <GraphNode
            approval={approvals.find(
              (candidate) => candidate.nodeId === graphNode.id,
            )}
            graphNode={graphNode}
            onActivate={setHoveredNodeId}
            onDeactivate={() => setHoveredNodeId(null)}
            onReviewApproval={onReviewApproval}
            onViewApprovalOutput={onViewApprovalOutput}
            onSelectNode={onSelectNode}
            operation={operationForNode(operations, [
              graphNode.id,
              ...graphNode.steps.map((step) => step.id),
            ])}
            pathActive={activePath.nodeIds.has(graphNode.id)}
            pathMuted={Boolean(pathAnchorId)
              && !activePath.nodeIds.has(graphNode.id)}
            register={register}
            selectedNodeId={selectedNodeId}
          />
        </div>
      ))}
    </section>
  );
}
