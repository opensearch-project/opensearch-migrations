import {
  memo,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
} from "react";
import {
  AlertCircle,
  AlertTriangle,
  ChevronRight,
  CircleCheck,
  CircleDashed,
  FileSliders,
  Folder,
  Search,
  Workflow,
} from "lucide-react";
import {
  DEFAULT_EXPANDED_IDS,
  type ManageTreeState,
  type TreeNode,
} from "@manage-spike/shared";

interface VisibleRow {
  node: TreeNode;
  depth: number;
  expanded: boolean;
}

interface ResourceTreeProps {
  state: ManageTreeState;
  selectedId: string;
  focusedId: string;
  insertedIds: ReadonlySet<string>;
  onSelect: (nodeId: string) => void;
  onFocusChange: (nodeId: string) => void;
}

function nodeMatches(node: TreeNode, query: string): boolean {
  const searchable = [
    node.label,
    node.description,
    node.phase,
    node.valueSummary,
    node.diagnostic,
  ]
    .filter(Boolean)
    .join(" ")
    .toLocaleLowerCase();
  return searchable.includes(query);
}

function flattenVisibleRows(
  state: ManageTreeState,
  expandedIds: ReadonlySet<string>,
  filter: string,
): ReadonlyArray<VisibleRow> {
  const query = filter.trim().toLocaleLowerCase();
  const matchCache = new Map<string, boolean>();

  const branchMatches = (nodeId: string): boolean => {
    const cached = matchCache.get(nodeId);
    if (cached !== undefined) {
      return cached;
    }
    const node = state.nodes[nodeId];
    const matches =
      !!node &&
      (nodeMatches(node, query) ||
        node.childIds.some((childId) => branchMatches(childId)));
    matchCache.set(nodeId, matches);
    return matches;
  };

  const rows: VisibleRow[] = [];
  const visit = (nodeId: string, depth: number): void => {
    const node = state.nodes[nodeId];
    if (!node || (query && !branchMatches(nodeId))) {
      return;
    }
    const expanded = query.length > 0 || expandedIds.has(nodeId);
    rows.push({ node, depth, expanded });
    if (expanded) {
      node.childIds.forEach((childId) => visit(childId, depth + 1));
    }
  };
  state.rootIds.forEach((nodeId) => visit(nodeId, 1));
  return rows;
}

function SeverityIcon({ node }: { node: TreeNode }) {
  if (node.severity === "error" || node.severity === "blocked") {
    return <AlertCircle aria-hidden="true" />;
  }
  if (node.severity === "warning") {
    return <AlertTriangle aria-hidden="true" />;
  }
  if (node.severity === "running") {
    return <CircleDashed className="spin-slow" aria-hidden="true" />;
  }
  if (node.severity === "changed") {
    return <FileSliders aria-hidden="true" />;
  }
  if (node.kind === "section" || node.kind === "group") {
    return <Folder aria-hidden="true" />;
  }
  if (node.kind === "resource") {
    return <Workflow aria-hidden="true" />;
  }
  return <CircleCheck aria-hidden="true" />;
}

interface TreeRowProps {
  row: VisibleRow;
  position: number;
  setSize: number;
  selected: boolean;
  focused: boolean;
  inserted: boolean;
  rowRef: (element: HTMLDivElement | null) => void;
  onSelect: (nodeId: string) => void;
  onToggle: (nodeId: string) => void;
  onFocus: (nodeId: string) => void;
  onKeyDown: (event: KeyboardEvent<HTMLDivElement>, nodeId: string) => void;
}

const TreeRow = memo(function TreeRow({
  row,
  position,
  setSize,
  selected,
  focused,
  inserted,
  rowRef,
  onSelect,
  onToggle,
  onFocus,
  onKeyDown,
}: TreeRowProps) {
  const { node, depth, expanded } = row;
  const hasChildren = node.childIds.length > 0;
  const accessibleLabel = [
    node.label,
    node.phase,
    node.valueSummary,
    node.severity !== "normal" ? node.severity : undefined,
  ]
    .filter(Boolean)
    .join(", ");

  return (
    <div
      ref={rowRef}
      id={`tree-row-${node.id}`}
      className="tree-row"
      role="treeitem"
      aria-label={accessibleLabel}
      aria-level={depth}
      aria-posinset={position}
      aria-setsize={setSize}
      aria-expanded={hasChildren ? expanded : undefined}
      aria-selected={selected}
      data-node-id={node.id}
      data-severity={node.severity}
      data-inserted={inserted ? "true" : undefined}
      tabIndex={focused ? 0 : -1}
      style={{ "--tree-depth": depth } as CSSProperties}
      onClick={(event) => {
        onSelect(node.id);
        event.currentTarget.focus();
      }}
      onFocus={() => onFocus(node.id)}
      onKeyDown={(event) => onKeyDown(event, node.id)}
    >
      <button
        className="tree-expander"
        type="button"
        tabIndex={-1}
        aria-label={`${expanded ? "Collapse" : "Expand"} ${node.label}`}
        disabled={!hasChildren}
        onMouseDown={(event) => event.preventDefault()}
        onClick={(event) => {
          event.stopPropagation();
          onToggle(node.id);
        }}
      >
        {hasChildren ? (
          <ChevronRight
            className={expanded ? "tree-expander-open" : undefined}
            aria-hidden="true"
          />
        ) : null}
      </button>
      <span className="tree-node-icon" aria-hidden="true">
        <SeverityIcon node={node} />
      </span>
      <span className="tree-row-content">
        <span className="tree-row-label">{node.label}</span>
        {node.phase || node.valueSummary ? (
          <span className="tree-row-meta">
            {[node.phase, node.valueSummary].filter(Boolean).join(" · ")}
          </span>
        ) : null}
      </span>
      {node.severity !== "normal" ? (
        <span className="severity-dot" aria-hidden="true" />
      ) : null}
    </div>
  );
});

export function ResourceTree({
  state,
  selectedId,
  focusedId,
  insertedIds,
  onSelect,
  onFocusChange,
}: ResourceTreeProps) {
  const [filter, setFilter] = useState("");
  const [expandedIds, setExpandedIds] = useState<ReadonlySet<string>>(
    () => new Set(DEFAULT_EXPANDED_IDS),
  );
  const rowElements = useRef(new Map<string, HTMLDivElement>());

  useEffect(() => {
    if (insertedIds.size === 0) {
      return;
    }
    setExpandedIds((current) => {
      const next = new Set(current);
      insertedIds.forEach((insertedId) => {
        let node = state.nodes[insertedId];
        while (node?.parentId) {
          next.add(node.parentId);
          node = state.nodes[node.parentId];
        }
      });
      return next;
    });
  }, [insertedIds, state.nodes]);

  const rows = useMemo(
    () => flattenVisibleRows(state, expandedIds, filter),
    [expandedIds, filter, state],
  );
  const visibleIds = useMemo(
    () => new Set(rows.map(({ node }) => node.id)),
    [rows],
  );
  const rovingFocusId = visibleIds.has(focusedId)
    ? focusedId
    : rows[0]?.node.id;

  const focusRow = useCallback(
    (nodeId: string): void => {
      onFocusChange(nodeId);
      queueMicrotask(() => rowElements.current.get(nodeId)?.focus());
    },
    [onFocusChange],
  );

  const toggle = useCallback((nodeId: string): void => {
    setExpandedIds((current) => {
      const next = new Set(current);
      if (next.has(nodeId)) {
        next.delete(nodeId);
      } else {
        next.add(nodeId);
      }
      return next;
    });
  }, []);

  const handleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>, nodeId: string): void => {
      const index = rows.findIndex(({ node }) => node.id === nodeId);
      const row = rows[index];
      if (!row) {
        return;
      }

      let targetId: string | undefined;
      if (event.key === "ArrowDown") {
        targetId = rows[Math.min(index + 1, rows.length - 1)]?.node.id;
      } else if (event.key === "ArrowUp") {
        targetId = rows[Math.max(index - 1, 0)]?.node.id;
      } else if (event.key === "Home") {
        targetId = rows[0]?.node.id;
      } else if (event.key === "End") {
        targetId = rows.at(-1)?.node.id;
      } else if (event.key === "ArrowRight" && row.node.childIds.length > 0) {
        if (!row.expanded) {
          toggle(nodeId);
        } else {
          targetId = rows[index + 1]?.node.id;
        }
      } else if (event.key === "ArrowLeft") {
        if (row.expanded && row.node.childIds.length > 0) {
          toggle(nodeId);
        } else if (row.node.parentId && visibleIds.has(row.node.parentId)) {
          targetId = row.node.parentId;
        }
      } else if (event.key === "Enter" || event.key === " ") {
        onSelect(nodeId);
      } else {
        return;
      }

      event.preventDefault();
      if (targetId) {
        focusRow(targetId);
      }
    },
    [focusRow, onSelect, rows, toggle, visibleIds],
  );

  return (
    <section className="tree-panel" aria-label="Resources">
      <div className="tree-panel-header">
        <div>
          <h2>Resources</h2>
          <span>{state.mode === "edit" ? "Pending configuration" : "Live state"}</span>
        </div>
        <span className="revision-label">r{state.revision}</span>
      </div>
      <label className="tree-filter">
        <Search aria-hidden="true" />
        <span className="sr-only">Filter resources</span>
        <input
          type="search"
          value={filter}
          placeholder="Filter resources"
          onChange={(event) => setFilter(event.target.value)}
        />
      </label>
      <div className="tree-scroll">
        <div className="resource-tree" role="tree" aria-label="Workflow resources">
          {rows.map((row, index) => (
            <TreeRow
              key={row.node.id}
              row={row}
              position={index + 1}
              setSize={rows.length}
              selected={selectedId === row.node.id}
              focused={rovingFocusId === row.node.id}
              inserted={insertedIds.has(row.node.id)}
              rowRef={(element) => {
                if (element) {
                  rowElements.current.set(row.node.id, element);
                } else {
                  rowElements.current.delete(row.node.id);
                }
              }}
              onSelect={onSelect}
              onToggle={toggle}
              onFocus={onFocusChange}
              onKeyDown={handleKeyDown}
            />
          ))}
        </div>
        {rows.length === 0 ? (
          <div className="empty-tree">No resources match “{filter}”.</div>
        ) : null}
      </div>
    </section>
  );
}
