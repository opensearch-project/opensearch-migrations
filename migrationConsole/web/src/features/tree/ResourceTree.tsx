import {
  Fragment,
  memo,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
} from "react";
import {
  Check,
  ChevronDown,
  ChevronRight,
  CircleCheck,
  Pencil,
  Plus,
  Search,
  TriangleAlert,
  X,
} from "lucide-react";

import type { ManageNode, ManageSnapshot } from "../../api/client";
import { StatusIndicator } from "../status/StatusIndicator";
import { statusLabel } from "../status/status";
import type {
  ResourceAddController,
  ResourceAddOption,
  ResourceRenameOption,
} from "../configuration/resourceAdds";
import type {
  ResourceDraftChangeState,
  ResourceValidationState,
} from "../configuration/editProjection";
import {
  resolveTreeLayoutOffset,
  type TreeLayoutOffset,
} from "./treeLayout";


interface VisibleRow {
  node: ManageNode;
  depth: number;
}


interface RowLayout {
  height: number;
  labelLeft: number | null;
  labelTop: number | null;
  left: number;
  statusOpacity: number;
  top: number;
}



interface ResourceTreeProps {
  snapshot: ManageSnapshot;
  selectedId: string | null;
  onSelect: (nodeId: string) => void;
  presentation: "configuration" | "runtime";
  viewTransitionKey: string;
  resourceAdds: ResourceAddController | null;
  changeStates: Record<string, ResourceDraftChangeState>;
  validationStates: Record<string, ResourceValidationState>;
}


function descendants(
  snapshot: ManageSnapshot,
  nodeId: string,
  target: Set<string>,
) {
  const node = snapshot.nodes[nodeId];
  if (!node) return;
  target.add(nodeId);
  node.childIds.forEach((childId) => descendants(snapshot, childId, target));
}


function filterIds(
  snapshot: ManageSnapshot,
  query: string,
  presentation: ResourceTreeProps["presentation"],
): Set<string> | null {
  const normalized = query.trim().toLocaleLowerCase();
  if (!normalized) return null;
  const visible = new Set<string>();
  Object.values(snapshot.nodes).forEach((node) => {
    const searchable = [
      node.label,
      node.description,
      ...(presentation === "runtime" ? [node.phase, node.status] : []),
      node.valueSummary,
    ]
      .filter(Boolean)
      .join(" ")
      .toLocaleLowerCase();
    if (!searchable.includes(normalized)) return;
    descendants(snapshot, node.id, visible);
    let parentId = node.parentId;
    while (parentId) {
      visible.add(parentId);
      parentId = snapshot.nodes[parentId]?.parentId;
    }
  });
  return visible;
}


function visibleRows(
  snapshot: ManageSnapshot,
  expanded: ReadonlySet<string>,
  query: string,
  presentation: ResourceTreeProps["presentation"],
): VisibleRow[] {
  const included = filterIds(snapshot, query, presentation);
  const rows: VisibleRow[] = [];
  const visit = (nodeId: string, depth: number) => {
    const node = snapshot.nodes[nodeId];
    if (!node || (included && !included.has(nodeId))) return;
    rows.push({ node, depth });
    if (included || expanded.has(nodeId)) {
      node.childIds.forEach((childId) => visit(childId, depth + 1));
    }
  };
  snapshot.rootIds.forEach((rootId) => visit(rootId, 1));
  return rows;
}


interface TreeRowProps {
  row: VisibleRow;
  presentation: ResourceTreeProps["presentation"];
  expanded: boolean;
  inserted: boolean;
  focused: boolean;
  selected: boolean;
  addMenuOpen: boolean;
  addOptions: ResourceAddOption[];
  addPending: boolean;
  renameOption?: ResourceRenameOption;
  resourceType?: string;
  validation?: ResourceValidationState;
  draftChange?: ResourceDraftChangeState;
  draftChangeAncestor: boolean;
  validationErrorAncestor: boolean;
  validationErrorItem: boolean;
  renaming: boolean;
  renameValue: string;
  onAddResource: (optionId: string) => void;
  onCancelRename: () => void;
  onChangeRename: (value: string) => void;
  onExpand: (nodeId: string) => void;
  onFocus: (nodeId: string) => void;
  onKeyDown: (event: KeyboardEvent<HTMLDivElement>, nodeId: string) => void;
  onSelect: (nodeId: string) => void;
  onStartRename: (nodeId: string) => void;
  onSubmitRename: () => void;
  onToggleAddMenu: (nodeId: string) => void;
  rowRef: (nodeId: string, element: HTMLDivElement | null) => void;
}


interface InlineCreate {
  groupId: string;
  name: string;
  option: ResourceAddOption;
  previousFocusedId: string | null;
  previousSelectedId: string | null;
}


interface InlineRename {
  name: string;
  nodeId: string;
  option: ResourceRenameOption;
  previousFocusedId: string | null;
  previousSelectedId: string | null;
}


const TreeRow = memo(function TreeRow({
  row,
  presentation,
  expanded,
  inserted,
  focused,
  selected,
  addMenuOpen,
  addOptions,
  addPending,
  renameOption,
  resourceType,
  validation,
  draftChange,
  draftChangeAncestor,
  validationErrorAncestor,
  validationErrorItem,
  renaming,
  renameValue,
  onAddResource,
  onCancelRename,
  onChangeRename,
  onExpand,
  onFocus,
  onKeyDown,
  onSelect,
  onStartRename,
  onSubmitRename,
  onToggleAddMenu,
  rowRef,
}: TreeRowProps) {
  const { node, depth } = row;
  const configurationOnly = presentation === "configuration";
  const expandable = node.childIds.length > 0;
  const runtimeState = node.phase ?? statusLabel(node.status);
  const configurationState = (
    node.valueSummary
    && node.valueSummary !== node.phase
    && node.valueSummary !== runtimeState
  )
    ? node.valueSummary
    : null;
  const explicitConfigurationState = [
    "Addition pending submission",
    "Rename pending submission",
    "Removal pending submission",
    "Will be orphaned by new configuration",
    "Orphaned; cleanup required",
    "Marked for removal",
    "Syncing",
    "Syncing configuration",
    "Removing",
  ].includes(node.valueSummary ?? "");
  const spokenState = node.status === "removed"
    ? node.valueSummary ?? "Marked for removal"
    : node.status === "syncing"
      ? node.valueSummary ?? "Syncing"
      : explicitConfigurationState
        ? node.valueSummary
        : node.phase ?? node.status;
  const attentionState = !configurationOnly && (
    node.phase
    && ["warning", "required", "gated", "blocked", "error"].includes(node.status)
    && !(
      node.status === "error"
      && ["error", "failed"].includes(node.phase.toLocaleLowerCase())
    )
  )
    ? (
      (node.diagnostics ?? []).some(
        (diagnostic) => diagnostic.source === "workflow-apply",
      )
        ? "Update blocked"
        : statusLabel(node.status)
    )
    : null;
  const activeRequirements = configurationOnly ? [] : (
    node.relationships ?? []
  ).filter(
    (relationship) => (
      relationship.direction === "requires"
      && relationship.targetStatus !== "ok"
    ),
  );
  const activeRequirement = [...activeRequirements].sort((left, right) => {
    const rank: Record<string, number> = {
      error: 3,
      unknown: 2,
      blocked: 2,
      running: 1,
      pending: 1,
    };
    return (rank[right.targetStatus] ?? 0) - (rank[left.targetStatus] ?? 0);
  })[0];
  const dependencyPrefix = activeRequirement?.targetStatus === "error"
    ? "Blocked by"
    : activeRequirement?.targetStatus === "unknown"
      ? "Depends on"
      : "Waiting for";
  const approvalCapability = configurationOnly ? undefined : node.capabilities.find(
    (capability) => capability.kind === "approve",
  );
  const approvalDiagnostic = configurationOnly ? undefined : node.diagnostics.find(
    (diagnostic) => diagnostic.source === "workflow-apply",
  );
  const approvalAttention = approvalCapability || approvalDiagnostic
    ? {
        headline: approvalCapability?.disabledReason
          ? "Reset before approval"
          : approvalCapability
            ? "Approval required"
            : approvalDiagnostic?.title ?? "Approval blocked",
        reason: approvalDiagnostic?.message
          ?? approvalCapability?.disabledReason
          ?? approvalCapability?.label
          ?? "",
      }
    : null;
  const indicatorState = [
    "blocked",
    "error",
    "gated",
    "required",
    "warning",
  ].includes(node.status)
    ? node.status
    : node.phase ?? node.status;
  const configurationStateVisible = Boolean(
    configurationState
    && (!configurationOnly || explicitConfigurationState),
  );
  const singleLine = configurationOnly
    && !resourceType
    && !draftChange
    && !configurationStateVisible;
  const configurationStatusVisible = configurationOnly
    && ["changed", "removed", "syncing"].includes(node.status);
  return (
    <div
      aria-expanded={expandable ? expanded : undefined}
      aria-label={[
        node.label,
        resourceType,
        configurationOnly
          ? draftChange?.label
            ?? (explicitConfigurationState ? configurationState : null)
          : spokenState,
      ].filter(Boolean).join(", ")}
      aria-level={depth}
      aria-selected={selected}
      className={[
        "tree-row",
        (
          !configurationOnly
          || ["changed", "removed", "syncing"].includes(node.status)
        )
          ? `status-${node.status}`
          : "",
        configurationOnly ? "configuration-tree-row" : "",
        validationErrorItem ? "validation-error-item" : "",
        validationErrorAncestor ? "validation-error-ancestor" : "",
        selected ? "selected" : "",
        inserted ? "inserted" : "",
        draftChange ? "draft-change-item" : "",
        draftChangeAncestor ? "draft-change-ancestor" : "",
        activeRequirement ? "has-dependency-hint" : "",
        approvalAttention ? "has-approval-hint" : "",
        singleLine ? "single-line-tree-row" : "",
      ].join(" ")}
      data-node-id={node.id}
      onClick={() => onSelect(node.id)}
      onFocus={(event) => {
        if (event.target === event.currentTarget) onFocus(node.id);
      }}
      onKeyDown={(event) => onKeyDown(event, node.id)}
      ref={(element) => rowRef(node.id, element)}
      role="treeitem"
      style={{ "--tree-depth": depth } as React.CSSProperties}
      tabIndex={focused ? 0 : -1}
    >
      {expandable ? (
        <button
          aria-label={`${expanded ? "Collapse" : "Expand"} ${node.label}`}
          className="tree-expander"
          onClick={(event) => {
            event.stopPropagation();
            onExpand(node.id);
          }}
          tabIndex={-1}
          type="button"
        >
          {expanded ? <ChevronDown /> : <ChevronRight />}
        </button>
      ) : (
        <span className="tree-expander-spacer" />
      )}
      <span
        className={[
          "tree-status-slot",
          configurationStatusVisible ? "configuration-status-visible" : "",
        ].join(" ")}
        data-tree-mode-icon="status"
      >
        <StatusIndicator status={indicatorState} />
      </span>
      {renaming && renameOption ? (
        <form
          className="tree-inline-name"
          onSubmit={(event) => {
            event.preventDefault();
            onSubmitRename();
          }}
        >
          <input
            aria-label={`New name for ${renameOption.currentName}`}
            autoFocus
            onChange={(event) => {
              event.currentTarget.setCustomValidity("");
              onChangeRename(event.target.value);
            }}
            onClick={(event) => event.stopPropagation()}
            onInvalid={(event) => {
              if (
                event.currentTarget.validity.patternMismatch
                && renameOption.validationMessage
              ) {
                event.currentTarget.setCustomValidity(
                  renameOption.validationMessage,
                );
              }
            }}
            onKeyDown={(event) => {
              event.stopPropagation();
              if (event.key === "Escape" && !addPending) {
                event.preventDefault();
                onCancelRename();
              }
            }}
            pattern={renameOption.pattern}
            required
            title={[
              renameOption.validationMessage,
              "Dependent workflow references will be updated.",
            ].filter(Boolean).join(" ")}
            value={renameValue}
          />
          <button
            aria-label="Apply rename"
            disabled={
              addPending
              || !renameValue.trim()
              || renameValue.trim() === renameOption.currentName
            }
            onClick={(event) => event.stopPropagation()}
            title="Apply rename"
            type="submit"
          >
            <Check aria-hidden="true" />
          </button>
          <button
            aria-label="Cancel rename"
            disabled={addPending}
            onClick={(event) => {
              event.stopPropagation();
              onCancelRename();
            }}
            title="Cancel rename"
            type="button"
          >
            <X aria-hidden="true" />
          </button>
        </form>
      ) : (
        <>
          <span className="tree-row-copy">
            <strong>{node.label}</strong>
            <span className="tree-row-state">
              {resourceType ? (
                <span className="tree-resource-type">{resourceType}</span>
              ) : null}
              {!configurationOnly ? <span>{runtimeState}</span> : null}
              {attentionState && !approvalAttention ? (
                <span className={`tree-attention-state attention-${node.status}`}>
                  {attentionState}
                </span>
              ) : null}
              {draftChange ? (
                <span
                  className="tree-draft-state"
                  title={draftChange.label + ". Save or discard before leaving edit mode."}
                >
                  {draftChange.label}
                </span>
              ) : null}
              {configurationStateVisible ? (
                <span className={[
                  "tree-config-state",
                  (
                    configurationState.includes("orphaned")
                    || configurationState.includes("Orphaned")
                    || configurationState.includes("Removal")
                    || configurationState === "Removing"
                  )
                    ? "tree-removal-state"
                    : "",
                ].join(" ")}>
                  {configurationState}
                </span>
              ) : null}
            </span>
            {approvalAttention ? (
              <span
                className="tree-approval-hint"
                title={approvalAttention.reason}
              >
                <strong>{approvalAttention.headline}</strong>
                <span>{approvalAttention.reason}</span>
              </span>
            ) : null}
            {activeRequirement ? (
              <button
                aria-label={
                  activeRequirement.targetStatus === "error"
                    ? `View blocker ${activeRequirement.targetName}`
                    : `View prerequisite ${activeRequirement.targetName}`
                }
                className={[
                  "tree-dependency-hint",
                  activeRequirement.targetStatus === "error"
                    ? "dependency-blocked"
                    : "",
                ].join(" ")}
                disabled={!activeRequirement.targetId}
                onClick={(event) => {
                  event.stopPropagation();
                  if (activeRequirement.targetId) {
                    onSelect(activeRequirement.targetId);
                  }
                }}
                title={`${dependencyPrefix} ${activeRequirement.targetName}`}
                type="button"
              >
                <span>
                  {dependencyPrefix} {activeRequirement.targetName}
                  {activeRequirements.length > 1
                    ? ` +${activeRequirements.length - 1}`
                    : ""}
                </span>
                <small>
                  {activeRequirement.targetPhase
                    ?? statusLabel(activeRequirement.targetStatus)}
                </small>
              </button>
            ) : null}
          </span>
          <span className="tree-row-tools">
        {addOptions.length === 1 ? (
          <button
            aria-label={`Add ${addOptions[0].label}`}
            className="tree-group-add"
            disabled={addPending || addOptions[0].disabled}
            onClick={(event) => {
              event.stopPropagation();
              onAddResource(addOptions[0].id);
            }}
            title={
              addOptions[0].disabledReason
              ?? `Add ${addOptions[0].label} to ${node.label}`
            }
            type="button"
          >
            <Plus aria-hidden="true" />
          </button>
        ) : addOptions.length > 1 ? (
          <>
            <button
              aria-expanded={addMenuOpen}
              aria-haspopup="menu"
              aria-label={`Add resource to ${node.label}`}
              className="tree-group-add"
              disabled={
                addPending
                || addOptions.every((option) => option.disabled)
              }
              onClick={(event) => {
                event.stopPropagation();
                onToggleAddMenu(node.id);
              }}
              title={`Add resource to ${node.label}`}
              type="button"
            >
              <Plus aria-hidden="true" />
            </button>
            {addMenuOpen ? (
              <span
                aria-label={`Resource types for ${node.label}`}
                className="tree-group-add-menu"
                role="menu"
              >
                {addOptions.map((option) => (
                  <button
                    disabled={addPending || option.disabled}
                    key={option.id}
                    onClick={(event) => {
                      event.stopPropagation();
                      onAddResource(option.id);
                    }}
                    role="menuitem"
                    title={option.disabledReason ?? `Add ${option.label}`}
                    type="button"
                  >
                    <Plus aria-hidden="true" />
                    <span>{option.label}</span>
                  </button>
                ))}
              </span>
            ) : null}
          </>
        ) : null}
        {renameOption ? (
          <button
            aria-label={`Rename ${node.label}`}
            className="tree-resource-rename"
            disabled={addPending}
            onClick={(event) => {
              event.stopPropagation();
              onStartRename(node.id);
            }}
            title={`Rename ${node.label} and update dependent workflow references`}
            type="button"
          >
            <Pencil aria-hidden="true" />
          </button>
        ) : null}
        {validation ? (
          <span
            aria-label={validation.label}
            className={[
              "tree-validation-indicator",
              `validation-${validation.level}`,
            ].join(" ")}
            title={validation.label}
          >
            {validation.level === "valid"
              ? <CircleCheck aria-hidden="true" />
              : <TriangleAlert aria-hidden="true" />}
            {validation.issueCount > 1 ? (
              <span>{validation.issueCount}</span>
            ) : null}
          </span>
        ) : !configurationOnly && node.diagnostics.length > 0 ? (
          <span
            aria-label={`${node.diagnostics.length} diagnostics`}
            className="diagnostic-count"
          >
            {node.diagnostics.length}
          </span>
        ) : null}
          </span>
        </>
      )}
    </div>
  );
});


function InlineCreateRow({
  create,
  depth,
  pending,
  onCancel,
  onChange,
  onFormRef,
  onLeave,
  onSubmit,
}: Readonly<{
  create: InlineCreate;
  depth: number;
  pending: boolean;
  onCancel: () => void;
  onChange: (name: string) => void;
  onFormRef: (element: HTMLFormElement | null) => void;
  onLeave: () => void;
  onSubmit: () => void;
}>) {
  return (
    <form
      aria-label={`New ${create.option.label}`}
      aria-level={depth}
      aria-selected="true"
      className="tree-row tree-inline-create selected inserted"
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) onLeave();
      }}
      onKeyDown={(event) => {
        event.stopPropagation();
        if (event.key === "Escape" && !pending) {
          event.preventDefault();
          onCancel();
        }
      }}
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
      role="treeitem"
      ref={onFormRef}
      style={{ "--tree-depth": depth } as React.CSSProperties}
    >
      <span className="tree-expander-spacer" />
      <StatusIndicator status="pending" />
      <div className="tree-inline-name">
        <input
          aria-label={`${create.option.label} name`}
          autoFocus
          onChange={(event) => {
            event.currentTarget.setCustomValidity("");
            onChange(event.target.value);
          }}
          onInvalid={(event) => {
            if (
              event.currentTarget.validity.patternMismatch
              && create.option.validationMessage
            ) {
              event.currentTarget.setCustomValidity(
                create.option.validationMessage,
              );
            }
          }}
          pattern={create.option.pattern}
          placeholder={`New ${create.option.label}`}
          required
          title={create.option.validationMessage}
          value={create.name}
        />
        <button
          aria-label={`Create ${create.option.label}`}
          disabled={pending || !create.name.trim()}
          title={`Create ${create.option.label}`}
          type="submit"
        >
          <Check aria-hidden="true" />
        </button>
        <button
          aria-label={`Cancel adding ${create.option.label}`}
          disabled={pending}
          onClick={onCancel}
          title="Cancel"
          type="button"
        >
          <X aria-hidden="true" />
        </button>
      </div>
    </form>
  );
}


export function ResourceTree({
  snapshot,
  selectedId,
  onSelect,
  presentation,
  viewTransitionKey,
  resourceAdds,
  changeStates,
  validationStates,
}: Readonly<ResourceTreeProps>) {
  const [filter, setFilter] = useState("");
  const [expanded, setExpanded] = useState<Set<string>>(
    () => new Set(),
  );
  const [focusedId, setFocusedId] = useState<string | null>(selectedId);
  const [insertedIds, setInsertedIds] = useState<Set<string>>(() => new Set());
  const [addMenuGroupId, setAddMenuGroupId] = useState<string | null>(null);
  const [inlineCreate, setInlineCreate] = useState<InlineCreate | null>(null);
  const [inlineRename, setInlineRename] = useState<InlineRename | null>(null);
  const knownIds = useRef<Set<string> | null>(null);
  const knownIdsViewKey = useRef(viewTransitionKey);
  const layoutViewKey = useRef(viewTransitionKey);
  const layoutRects = useRef(new Map<string, RowLayout>());
  const paintedRects = useRef(new Map<string, RowLayout>());
  const layoutSampleFrame = useRef<number | null>(null);
  const rowElements = useRef(new Map<string, HTMLDivElement>());
  const inlineCreateElement = useRef<HTMLFormElement>(null);
  const focusAfterMutation = useRef(false);

  useEffect(() => {
    setExpanded((current) => {
      const next = new Set(
        [...current].filter((nodeId) => snapshot.nodes[nodeId]),
      );
      Object.values(snapshot.nodes).forEach((node) => {
        if (
          node.childIds.length > 0
          && (node.kind === "section" || node.kind === "group")
        ) {
          next.add(node.id);
        }
      });
      return next;
    });
  }, [snapshot]);

  useEffect(() => {
    const nextIds = new Set(Object.keys(snapshot.nodes));
    if (knownIdsViewKey.current !== viewTransitionKey) {
      knownIdsViewKey.current = viewTransitionKey;
      knownIds.current = nextIds;
      setInsertedIds(new Set());
      return;
    }
    if (knownIds.current) {
      const inserted = new Set(
        [...nextIds].filter((nodeId) => !knownIds.current?.has(nodeId)),
      );
      if (inserted.size > 0) {
        setInsertedIds(inserted);
        const timer = globalThis.setTimeout(
          () => setInsertedIds(new Set()),
          450,
        );
        knownIds.current = nextIds;
        return () => globalThis.clearTimeout(timer);
      }
    }
    knownIds.current = nextIds;
  }, [snapshot, viewTransitionKey]);

  const rows = useMemo(
    () => visibleRows(snapshot, expanded, filter, presentation),
    [snapshot, expanded, filter, presentation],
  );

  useEffect(() => () => {
    if (layoutSampleFrame.current !== null) {
      globalThis.cancelAnimationFrame(layoutSampleFrame.current);
    }
  }, []);

  useLayoutEffect(() => {
    const viewChanged = layoutViewKey.current !== viewTransitionKey;
    const transitionIds = new Set([
      "tree-icon-transition",
      "tree-label-transition",
      "tree-layout-transition",
      "tree-size-transition",
    ]);
    const animationActive = (animation: Animation) => (
      transitionIds.has(animation.id)
      && ["paused", "pending", "running"].includes(animation.playState)
    );
    const animationsFor = (element: Element): Animation[] => (
      typeof element.getAnimations === "function"
        ? element.getAnimations()
        : []
    );
    const measure = (element: HTMLDivElement): RowLayout => {
      const rect = element.getBoundingClientRect();
      const label = element.querySelector<HTMLElement>(
        ".tree-row-copy > strong",
      );
      const labelRect = label?.getBoundingClientRect();
      const status = element.querySelector<HTMLElement>(".tree-status-slot");
      return {
        height: rect.height,
        labelLeft: labelRect?.left ?? null,
        labelTop: labelRect?.top ?? null,
        left: rect.left,
        statusOpacity: status
          ? Number(globalThis.getComputedStyle(status).opacity)
          : 0,
        top: rect.top,
      };
    };
    const activeRects = new Map<string, RowLayout>();
    let activeTransition = false;
    if (layoutSampleFrame.current !== null) {
      globalThis.cancelAnimationFrame(layoutSampleFrame.current);
      layoutSampleFrame.current = null;
    }
    rowElements.current.forEach((element, nodeId) => {
      const transitionActive = [
        element,
        ...element.querySelectorAll<HTMLElement>(
          ".tree-row-copy > strong, [data-tree-mode-icon]",
        ),
      ].some((target) => animationsFor(target).some(animationActive));
      if (!transitionActive) return;
      activeTransition = true;
      activeRects.set(
        nodeId,
        paintedRects.current.get(nodeId) ?? measure(element),
      );
    });

    if (viewChanged || activeTransition) {
      rowElements.current.forEach((element) => {
        animationsFor(element).forEach((animation) => {
          if (transitionIds.has(animation.id)) animation.cancel();
        });
        element.querySelectorAll<HTMLElement>(
          ".tree-row-copy > strong, [data-tree-mode-icon]",
        ).forEach((target) => {
          animationsFor(target).forEach((animation) => {
            if (transitionIds.has(animation.id)) animation.cancel();
          });
        });
      });
    }

    const nextRects = new Map<string, RowLayout>();
    rowElements.current.forEach((element, nodeId) => {
      nextRects.set(nodeId, measure(element));
    });
    const layoutChanged = (
      layoutRects.current.size !== nextRects.size
      || [...nextRects].some(([nodeId, next]) => {
        const previous = layoutRects.current.get(nodeId);
        if (!previous) return true;
        return (
          Math.abs(previous.left - next.left) >= 0.5
          || Math.abs(previous.top - next.top) >= 0.5
          || Math.abs(previous.height - next.height) >= 0.5
          || Math.abs(previous.statusOpacity - next.statusOpacity) >= 0.01
          || (
            previous.labelLeft === null
              ? next.labelLeft !== null
              : next.labelLeft === null
                || Math.abs(previous.labelLeft - next.labelLeft) >= 0.5
          )
          || (
            previous.labelTop === null
              ? next.labelTop !== null
              : next.labelTop === null
                || Math.abs(previous.labelTop - next.labelTop) >= 0.5
          )
        );
      })
    );
    const previousRects = new Map<string, RowLayout>();
    nextRects.forEach((_next, nodeId) => {
      const previous = activeRects.get(nodeId)
        ?? paintedRects.current.get(nodeId)
        ?? layoutRects.current.get(nodeId);
      if (previous) previousRects.set(nodeId, previous);
    });

    const reduceMotion = globalThis.matchMedia?.(
      "(prefers-reduced-motion: reduce)",
    ).matches ?? false;
    const animationSupported = [...rowElements.current.values()].every(
      (element) => (
        typeof element.animate === "function"
        && typeof element.getAnimations === "function"
      ),
    );
    if (
      (viewChanged || activeTransition || layoutChanged)
      && !reduceMotion
      && animationSupported
    ) {
      const preparedAnimations: Animation[] = [];
      const prepare = (
        animation: Animation,
        id: string,
        onFinish?: () => void,
      ) => {
        animation.id = id;
        animation.pause();
        animation.currentTime = 0;
        animation.onfinish = () => {
          animation.oncancel = null;
          animation.cancel();
          onFinish?.();
        };
        preparedAnimations.push(animation);
      };

      nextRects.forEach((nextRect, nodeId) => {
        const previousRect = previousRects.get(nodeId);
        const element = rowElements.current.get(nodeId);
        if (
          !previousRect
          || !element
          || Math.abs(previousRect.height - nextRect.height) < 0.5
        ) {
          return;
        }
        prepare(element.animate([
          { height: `${previousRect.height}px` },
          { height: `${nextRect.height}px` },
        ], {
          duration: 520,
          easing: "cubic-bezier(0.2, 0.75, 0.25, 1)",
          fill: "both",
        }), "tree-size-transition");
      });

      const startRects = new Map<string, RowLayout>();
      rowElements.current.forEach((element, nodeId) => {
        startRects.set(nodeId, measure(element));
      });
      const parentIds = new Map<string, string | null>();
      const directOffsets = new Map<string, TreeLayoutOffset>();
      nextRects.forEach((_nextRect, nodeId) => {
        parentIds.set(nodeId, snapshot.nodes[nodeId]?.parentId ?? null);
        const previousRect = previousRects.get(nodeId);
        const startRect = startRects.get(nodeId);
        if (!previousRect || !startRect) return;
        directOffsets.set(nodeId, {
          x: previousRect.left - startRect.left,
          y: previousRect.top - startRect.top,
        });
      });
      const resolvedOffsets = new Map<string, TreeLayoutOffset>();

      nextRects.forEach((nextRect, nodeId) => {
        const previousRect = previousRects.get(nodeId);
        const startRect = startRects.get(nodeId);
        const element = rowElements.current.get(nodeId);
        if (!startRect || !element) return;
        const { x, y } = resolveTreeLayoutOffset(
          nodeId,
          parentIds,
          directOffsets,
          resolvedOffsets,
        );
        const sizeChanged = previousRect
          ? Math.abs(previousRect.height - nextRect.height) >= 0.5
          : false;
        if (
          (Math.abs(x) < 0.5 && Math.abs(y) < 0.5)
          && !sizeChanged
        ) {
          return;
        }

        element.classList.add("layout-moving");
        const animation = element.animate([
          { transform: `translate(${x}px, ${y}px)` },
          { transform: "translate(0, 0)" },
        ], {
          duration: 520,
          easing: "cubic-bezier(0.2, 0.75, 0.25, 1)",
          fill: "both",
        });
        const releaseMovingStyle = () => {
          const replacementActive = animationsFor(element).some(
            (candidate) => (
              candidate !== animation
              && animationActive(candidate)
            ),
          );
          if (!replacementActive) element.classList.remove("layout-moving");
        };
        animation.oncancel = releaseMovingStyle;
        prepare(animation, "tree-layout-transition", releaseMovingStyle);

        if (
          previousRect
          && previousRect.labelLeft !== null
          && previousRect.labelTop !== null
          && startRect.labelLeft !== null
          && startRect.labelTop !== null
        ) {
          const labelX = (
            previousRect.labelLeft - previousRect.left
          ) - (
            startRect.labelLeft - startRect.left
          );
          const labelY = (
            previousRect.labelTop - previousRect.top
          ) - (
            startRect.labelTop - startRect.top
          );
          const label = element.querySelector<HTMLElement>(
            ".tree-row-copy > strong",
          );
          if (
            label
            && (Math.abs(labelX) >= 0.5 || Math.abs(labelY) >= 0.5)
          ) {
            prepare(label.animate([
              { transform: `translate(${labelX}px, ${labelY}px)` },
              { transform: "translate(0, 0)" },
            ], {
              duration: 520,
              easing: "cubic-bezier(0.2, 0.75, 0.25, 1)",
              fill: "both",
            }), "tree-label-transition");
          }
        }
      });

      nextRects.forEach((nextRect, nodeId) => {
        const previousRect = previousRects.get(nodeId);
        const element = rowElements.current.get(nodeId);
        if (!previousRect || !element) return;
        const status = element.querySelector<HTMLElement>(
          ".tree-status-slot",
        );
        if (
          status
          && Math.abs(previousRect.statusOpacity - nextRect.statusOpacity)
            >= 0.01
        ) {
          prepare(status.animate([
            { opacity: previousRect.statusOpacity },
            { opacity: nextRect.statusOpacity },
          ], {
            duration: 240,
            easing: "ease",
            fill: "both",
          }), "tree-icon-transition");
        }
      });

      // A new tree projection can commit mid-animation. Keep the last painted
      // geometry so its replacement continues from what the user actually saw.
      const samplePaintedLayout = () => {
        const currentRects = new Map<string, RowLayout>();
        let transitionActive = false;
        rowElements.current.forEach((element, nodeId) => {
          currentRects.set(nodeId, measure(element));
          transitionActive ||= [
            element,
            ...element.querySelectorAll<HTMLElement>(
              ".tree-row-copy > strong, [data-tree-mode-icon]",
            ),
          ].some((target) => animationsFor(target).some(animationActive));
        });
        paintedRects.current = currentRects;
        layoutSampleFrame.current = transitionActive
          ? globalThis.requestAnimationFrame(samplePaintedLayout)
          : null;
      };
      samplePaintedLayout();
      preparedAnimations.forEach((animation) => {
        if (animation.playState === "paused") animation.play();
      });
    } else {
      paintedRects.current = nextRects;
    }

    layoutRects.current = nextRects;
    layoutViewKey.current = viewTransitionKey;
  }, [
    changeStates,
    presentation,
    rows,
    snapshot.nodes,
    validationStates,
    viewTransitionKey,
  ]);
  const validationErrorPaths = useMemo(() => {
    const items = new Set<string>();
    const ancestors = new Set<string>();
    Object.entries(validationStates).forEach(([nodeId, validation]) => {
      if (validation.level !== "error" || !snapshot.nodes[nodeId]) return;
      items.add(nodeId);
      let parentId = snapshot.nodes[nodeId].parentId;
      while (parentId) {
        ancestors.add(parentId);
        parentId = snapshot.nodes[parentId]?.parentId ?? null;
      }
    });
    return { ancestors, items };
  }, [snapshot.nodes, validationStates]);
  const draftChangePaths = useMemo(() => {
    const items = new Set<string>();
    const ancestors = new Set<string>();
    Object.keys(changeStates).forEach((nodeId) => {
      if (!snapshot.nodes[nodeId]) return;
      items.add(nodeId);
      let parentId = snapshot.nodes[nodeId].parentId;
      while (parentId) {
        ancestors.add(parentId);
        parentId = snapshot.nodes[parentId]?.parentId ?? null;
      }
    });
    return { ancestors, items };
  }, [changeStates, snapshot.nodes]);
  const inlineCreatePosition = (() => {
    if (!inlineCreate) return null;
    const groupIndex = rows.findIndex(
      ({ node }) => node.id === inlineCreate.groupId,
    );
    const groupRow = rows[groupIndex];
    if (!groupRow) return null;
    let afterId = groupRow.node.id;
    for (let index = groupIndex + 1; index < rows.length; index += 1) {
      if (rows[index].depth <= groupRow.depth) break;
      afterId = rows[index].node.id;
    }
    return {
      afterId,
      depth: groupRow.depth + 1,
    };
  })();
  const resourceTypesByNode = useMemo(() => {
    const result = new Map<string, string>();
    Object.values(snapshot.nodes).forEach((node) => {
      if (node.kind !== "group") return;
      const resources = node.childIds
        .map((childId) => snapshot.nodes[childId])
        .filter((child) => child?.kind === "resource");
      const types = new Set(resources.flatMap((resource) => (
        resource.resourceType ? [resource.resourceType] : []
      )));
      if (types.size < 2) return;
      resources.forEach((resource) => {
        if (resource.resourceType) {
          result.set(resource.id, resource.resourceType);
        }
      });
    });
    return result;
  }, [snapshot.nodes]);
  const addOptionsByGroup = useMemo(() => {
    const result = new Map<string, ResourceAddOption[]>();
    (resourceAdds?.options ?? []).forEach((option) => {
      const exactAnchor = snapshot.nodes[
        option.placement.addControlId ?? option.placement.groupId
      ];
      const matchingAnchor = (
        exactAnchor?.kind === "group" || exactAnchor?.kind === "section"
      )
        ? exactAnchor
        : Object.values(snapshot.nodes).find((node) => (
          node.kind === "group"
          && node.childIds.some(
            (childId) => (
              snapshot.nodes[childId]?.resourcePlural
              === option.placement.resourcePlural
            ),
          )
        ));
      if (!matchingAnchor) return;
      const current = result.get(matchingAnchor.id) ?? [];
      current.push(option);
      result.set(matchingAnchor.id, current);
    });
    return result;
  }, [resourceAdds?.options, snapshot]);
  const renameOptionsByResource = useMemo(() => {
    const result = new Map<string, ResourceRenameOption>();
    (resourceAdds?.renames ?? []).forEach((option) => {
      const matchingResource = Object.values(snapshot.nodes).find((node) => (
        node.kind === "resource"
        && (
          node.capabilities.some((capability) => (
            capability.kind === "edit"
            && capability.editTargetId === option.editTargetId
          ))
          || (
            node.resourcePlural === option.placement.resourcePlural
            && (
              node.resourceName === option.currentName
              || node.label === option.label
            )
          )
        )
      ));
      if (matchingResource) result.set(matchingResource.id, option);
    });
    return result;
  }, [resourceAdds?.renames, snapshot]);

  useEffect(() => {
    if (focusedId && snapshot.nodes[focusedId]) return;
    setFocusedId(selectedId ?? rows[0]?.node.id ?? null);
  }, [focusedId, rows, selectedId, snapshot.nodes]);

  const focusRow = useCallback((nodeId: string) => {
    setFocusedId(nodeId);
    rowElements.current.get(nodeId)?.focus();
  }, []);

  useEffect(() => {
    if (
      !focusAfterMutation.current
      || !selectedId
      || !snapshot.nodes[selectedId]
    ) {
      return;
    }
    focusAfterMutation.current = false;
    focusRow(selectedId);
  }, [focusRow, selectedId, snapshot.nodes]);

  const restoreTreeContext = useCallback((
    previousSelectedId: string | null,
    previousFocusedId: string | null,
  ) => {
    if (previousSelectedId && snapshot.nodes[previousSelectedId]) {
      onSelect(previousSelectedId);
    }
    const targetId = (
      previousFocusedId && snapshot.nodes[previousFocusedId]
        ? previousFocusedId
        : previousSelectedId
    );
    if (targetId) queueMicrotask(() => focusRow(targetId));
  }, [focusRow, onSelect, snapshot.nodes]);

  const cancelCreate = useCallback(() => {
    if (!inlineCreate) return;
    setInlineCreate(null);
    restoreTreeContext(
      inlineCreate.previousSelectedId,
      inlineCreate.previousFocusedId,
    );
  }, [inlineCreate, restoreTreeContext]);

  const inlineCreateActive = inlineCreate !== null;
  useEffect(() => {
    if (!inlineCreateActive) return;
    const abandonOnOutsidePointer = (event: PointerEvent) => {
      if (
        event.target instanceof Node
        && !inlineCreateElement.current?.contains(event.target)
      ) {
        setInlineCreate(null);
      }
    };
    document.addEventListener("pointerdown", abandonOnOutsidePointer, true);
    return () => document.removeEventListener(
      "pointerdown",
      abandonOnOutsidePointer,
      true,
    );
  }, [inlineCreateActive]);

  const submitCreate = useCallback(async () => {
    if (!inlineCreate || !resourceAdds) return;
    const name = inlineCreate.name.trim();
    if (inlineCreate.option.requiresName && !name) return;
    const previousSelectedId = inlineCreate.previousSelectedId;
    const previousFocusedId = inlineCreate.previousFocusedId;
    const optionId = inlineCreate.option.id;
    setInlineCreate(null);
    focusAfterMutation.current = true;
    const applied = await resourceAdds.add(optionId, name);
    if (!applied) {
      focusAfterMutation.current = false;
      restoreTreeContext(previousSelectedId, previousFocusedId);
    }
  }, [inlineCreate, resourceAdds, restoreTreeContext]);

  const beginAdd = useCallback((optionId: string, groupId: string) => {
    const option = resourceAdds?.options.find(
      (candidate) => candidate.id === optionId,
    );
    if (!option || option.disabled || resourceAdds?.busy) return;
    setAddMenuGroupId(null);
    setInlineRename(null);
    setExpanded((current) => new Set(current).add(groupId));
    const context = {
      previousFocusedId: focusedId,
      previousSelectedId: selectedId,
    };
    if (option.requiresName) {
      setInlineCreate({
        ...context,
        groupId,
        name: "",
        option,
      });
      return;
    }
    focusAfterMutation.current = true;
    void resourceAdds.add(option.id, "").then((applied) => {
      if (!applied) {
        focusAfterMutation.current = false;
        restoreTreeContext(
          context.previousSelectedId,
          context.previousFocusedId,
        );
      }
    });
  }, [
    focusedId,
    resourceAdds,
    restoreTreeContext,
    selectedId,
  ]);

  const beginRename = useCallback((nodeId: string) => {
    const option = renameOptionsByResource.get(nodeId);
    if (!option || resourceAdds?.busy) return;
    const context = {
      previousFocusedId: focusedId,
      previousSelectedId: selectedId,
    };
    setInlineCreate(null);
    setInlineRename({
      ...context,
      name: option.currentName,
      nodeId,
      option,
    });
    onSelect(nodeId);
    setFocusedId(nodeId);
  }, [
    focusedId,
    onSelect,
    renameOptionsByResource,
    resourceAdds?.busy,
    selectedId,
  ]);

  const cancelRename = useCallback(() => {
    if (!inlineRename) return;
    setInlineRename(null);
    restoreTreeContext(
      inlineRename.previousSelectedId,
      inlineRename.previousFocusedId,
    );
  }, [inlineRename, restoreTreeContext]);

  const submitRename = useCallback(async () => {
    if (!inlineRename || !resourceAdds) return;
    const newName = inlineRename.name.trim();
    if (!newName || newName === inlineRename.option.currentName) return;
    const { nodeId, option } = inlineRename;
    setInlineRename(null);
    focusAfterMutation.current = true;
    const applied = await resourceAdds.rename(
      option.editTargetId,
      nodeId,
      newName,
    );
    if (!applied) {
      focusAfterMutation.current = false;
      queueMicrotask(() => focusRow(nodeId));
    }
  }, [focusRow, inlineRename, resourceAdds]);

  const handleKeyDown = useCallback((
    event: KeyboardEvent<HTMLDivElement>,
    nodeId: string,
  ) => {
    const index = rows.findIndex((row) => row.node.id === nodeId);
    const node = snapshot.nodes[nodeId];
    let targetId: string | undefined;
    if (event.key === "ArrowDown") targetId = rows[index + 1]?.node.id;
    if (event.key === "ArrowUp") targetId = rows[index - 1]?.node.id;
    if (event.key === "Home") targetId = rows[0]?.node.id;
    if (event.key === "End") targetId = rows.at(-1)?.node.id;
    if (event.key === "ArrowRight" && node.childIds.length > 0) {
      if (!expanded.has(nodeId)) {
        setExpanded((current) => new Set(current).add(nodeId));
      } else {
        targetId = node.childIds[0];
      }
    }
    if (event.key === "ArrowLeft") {
      if (expanded.has(nodeId)) {
        setExpanded((current) => {
          const next = new Set(current);
          next.delete(nodeId);
          return next;
        });
      } else {
        targetId = node.parentId ?? undefined;
      }
    }
    if (event.key === "Enter" || event.key === " ") {
      onSelect(nodeId);
    }
    if (targetId || [
      "ArrowDown",
      "ArrowUp",
      "ArrowLeft",
      "ArrowRight",
      "Home",
      "End",
      "Enter",
      " ",
    ].includes(event.key)) {
      event.preventDefault();
    }
    if (targetId) focusRow(targetId);
  }, [expanded, focusRow, onSelect, rows, snapshot.nodes]);

  const toggleExpanded = useCallback((nodeId: string) => {
    setExpanded((current) => {
      const next = new Set(current);
      if (next.has(nodeId)) next.delete(nodeId);
      else next.add(nodeId);
      return next;
    });
  }, []);

  const rowRef = useCallback((
    nodeId: string,
    element: HTMLDivElement | null,
  ) => {
    if (element) rowElements.current.set(nodeId, element);
    else rowElements.current.delete(nodeId);
  }, []);

  return (
    <>
      <label className="tree-filter">
        <Search aria-hidden="true" />
        <span className="sr-only">Filter resources</span>
        <input
          aria-label="Filter resources"
          onChange={(event) => setFilter(event.target.value)}
          placeholder="Filter resources"
          type="search"
          value={filter}
        />
      </label>
      <div className="tree-scroll" data-testid="tree-scroller">
        <div aria-label="Workflow resources" className="resource-tree" role="tree">
          {rows.map((row) => (
            <Fragment key={row.node.id}>
              <TreeRow
                expanded={expanded.has(row.node.id) || filter.length > 0}
                focused={focusedId === row.node.id}
                inserted={insertedIds.has(row.node.id)}
                presentation={presentation}
                addMenuOpen={addMenuGroupId === row.node.id}
                addOptions={addOptionsByGroup.get(row.node.id) ?? []}
                addPending={resourceAdds?.busy ?? false}
                renameOption={renameOptionsByResource.get(row.node.id)}
                resourceType={resourceTypesByNode.get(row.node.id)}
                validation={validationStates[row.node.id]}
                draftChange={changeStates[row.node.id]}
                draftChangeAncestor={
                  draftChangePaths.ancestors.has(row.node.id)
                }
                validationErrorAncestor={
                  validationErrorPaths.ancestors.has(row.node.id)
                }
                validationErrorItem={
                  validationErrorPaths.items.has(row.node.id)
                }
                renameValue={
                  inlineRename?.nodeId === row.node.id
                    ? inlineRename.name
                    : ""
                }
                renaming={inlineRename?.nodeId === row.node.id}
                onAddResource={(optionId) => beginAdd(optionId, row.node.id)}
                onCancelRename={cancelRename}
                onChangeRename={(name) => setInlineRename((current) => (
                  current?.nodeId === row.node.id
                    ? { ...current, name }
                    : current
                ))}
                onExpand={toggleExpanded}
                onFocus={setFocusedId}
                onKeyDown={handleKeyDown}
                onSelect={onSelect}
                onStartRename={beginRename}
                onSubmitRename={() => void submitRename()}
                onToggleAddMenu={(nodeId) => setAddMenuGroupId((current) => (
                  current === nodeId ? null : nodeId
                ))}
                row={row}
                rowRef={rowRef}
                selected={
                  inlineCreate
                    ? false
                    : selectedId === row.node.id
                      || inlineRename?.nodeId === row.node.id
                }
              />
              {inlineCreate && inlineCreatePosition?.afterId === row.node.id ? (
                <InlineCreateRow
                  create={inlineCreate}
                  depth={inlineCreatePosition.depth}
                  onCancel={cancelCreate}
                  onChange={(name) => setInlineCreate((current) => (
                    current ? { ...current, name } : current
                  ))}
                  onFormRef={(element) => {
                    inlineCreateElement.current = element;
                  }}
                  onLeave={() => setInlineCreate(null)}
                  onSubmit={() => void submitCreate()}
                  pending={resourceAdds?.busy ?? false}
                />
              ) : null}
            </Fragment>
          ))}
          {rows.length === 0 ? (
            <p className="tree-empty">No resources match this filter.</p>
          ) : null}
        </div>
      </div>
    </>
  );
}
