import {
  memo,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
} from "react";
import { ChevronDown, ChevronRight, Search } from "lucide-react";

import type { ManageNode, ManageSnapshot } from "../../api/client";


interface VisibleRow {
  node: ManageNode;
  depth: number;
}


interface ResourceTreeProps {
  snapshot: ManageSnapshot;
  selectedId: string | null;
  onSelect: (nodeId: string) => void;
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


function filterIds(snapshot: ManageSnapshot, query: string): Set<string> | null {
  const normalized = query.trim().toLocaleLowerCase();
  if (!normalized) return null;
  const visible = new Set<string>();
  Object.values(snapshot.nodes).forEach((node) => {
    const searchable = [
      node.label,
      node.description,
      node.phase,
      node.status,
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
): VisibleRow[] {
  const included = filterIds(snapshot, query);
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
  expanded: boolean;
  inserted: boolean;
  focused: boolean;
  selected: boolean;
  onExpand: (nodeId: string) => void;
  onFocus: (nodeId: string) => void;
  onKeyDown: (event: KeyboardEvent<HTMLDivElement>, nodeId: string) => void;
  onSelect: (nodeId: string) => void;
  rowRef: (nodeId: string, element: HTMLDivElement | null) => void;
}


const TreeRow = memo(function TreeRow({
  row,
  expanded,
  inserted,
  focused,
  selected,
  onExpand,
  onFocus,
  onKeyDown,
  onSelect,
  rowRef,
}: TreeRowProps) {
  const { node, depth } = row;
  const expandable = node.childIds.length > 0;
  const spokenState = node.status === "removed"
    ? node.valueSummary ?? "Marked for removal"
    : node.phase ?? node.status;
  return (
    <div
      aria-expanded={expandable ? expanded : undefined}
      aria-label={`${node.label}, ${spokenState}`}
      aria-level={depth}
      aria-selected={selected}
      className={[
        "tree-row",
        `status-${node.status}`,
        selected ? "selected" : "",
        inserted ? "inserted" : "",
      ].join(" ")}
      data-node-id={node.id}
      onClick={() => onSelect(node.id)}
      onFocus={() => onFocus(node.id)}
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
      <span className="status-dot" aria-hidden="true" />
      <span className="tree-row-copy">
        <strong>{node.label}</strong>
        <span>{node.valueSummary ?? node.phase ?? node.kind}</span>
      </span>
      {node.diagnostics.length > 0 ? (
        <span className="diagnostic-count" aria-label={`${node.diagnostics.length} diagnostics`}>
          {node.diagnostics.length}
        </span>
      ) : null}
    </div>
  );
});


export function ResourceTree({
  snapshot,
  selectedId,
  onSelect,
}: ResourceTreeProps) {
  const [filter, setFilter] = useState("");
  const [expanded, setExpanded] = useState<Set<string>>(
    () => new Set(),
  );
  const [focusedId, setFocusedId] = useState<string | null>(selectedId);
  const [insertedIds, setInsertedIds] = useState<Set<string>>(() => new Set());
  const knownIds = useRef<Set<string> | null>(null);
  const rowElements = useRef(new Map<string, HTMLDivElement>());

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
    if (knownIds.current) {
      const inserted = new Set(
        [...nextIds].filter((nodeId) => !knownIds.current?.has(nodeId)),
      );
      if (inserted.size > 0) {
        setInsertedIds(inserted);
        const timer = window.setTimeout(() => setInsertedIds(new Set()), 450);
        knownIds.current = nextIds;
        return () => window.clearTimeout(timer);
      }
    }
    knownIds.current = nextIds;
  }, [snapshot]);

  const rows = useMemo(
    () => visibleRows(snapshot, expanded, filter),
    [snapshot, expanded, filter],
  );

  useEffect(() => {
    if (focusedId && snapshot.nodes[focusedId]) return;
    setFocusedId(selectedId ?? rows[0]?.node.id ?? null);
  }, [focusedId, rows, selectedId, snapshot.nodes]);

  const focusRow = useCallback((nodeId: string) => {
    setFocusedId(nodeId);
    rowElements.current.get(nodeId)?.focus();
  }, []);

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
            <TreeRow
              expanded={expanded.has(row.node.id) || filter.length > 0}
              focused={focusedId === row.node.id}
              inserted={insertedIds.has(row.node.id)}
              key={row.node.id}
              onExpand={toggleExpanded}
              onFocus={setFocusedId}
              onKeyDown={handleKeyDown}
              onSelect={onSelect}
              row={row}
              rowRef={rowRef}
              selected={selectedId === row.node.id}
            />
          ))}
          {rows.length === 0 ? (
            <p className="tree-empty">No resources match this filter.</p>
          ) : null}
        </div>
      </div>
    </>
  );
}
