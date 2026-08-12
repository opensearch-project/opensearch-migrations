<script setup lang="ts">
import {
  computed,
  nextTick,
  shallowRef,
  watch,
  type ComponentPublicInstance,
} from "vue";
import {
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  ChevronRight,
  CircleDashed,
  FileSliders,
  Folder,
  Search,
  Workflow,
} from "lucide-vue-next";
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

const props = defineProps<{
  state: ManageTreeState;
  selectedId: string;
  focusedId: string;
  insertedIds: ReadonlySet<string>;
}>();

const emit = defineEmits<{
  select: [nodeId: string];
  focusChange: [nodeId: string];
}>();

const filter = shallowRef("");
const expandedIds = shallowRef<ReadonlySet<string>>(
  new Set(DEFAULT_EXPANDED_IDS),
);
const rowElements = new Map<string, HTMLElement>();

function nodeMatches(node: TreeNode, query: string): boolean {
  return [
    node.label,
    node.description,
    node.phase,
    node.valueSummary,
    node.diagnostic,
  ]
    .filter(Boolean)
    .join(" ")
    .toLocaleLowerCase()
    .includes(query);
}

const rows = computed<ReadonlyArray<VisibleRow>>(() => {
  const query = filter.value.trim().toLocaleLowerCase();
  const matches = new Map<string, boolean>();

  function branchMatches(nodeId: string): boolean {
    const cached = matches.get(nodeId);
    if (cached !== undefined) {
      return cached;
    }
    const node = props.state.nodes[nodeId];
    const result =
      !!node &&
      (nodeMatches(node, query) ||
        node.childIds.some((childId) => branchMatches(childId)));
    matches.set(nodeId, result);
    return result;
  }

  const visible: VisibleRow[] = [];
  function visit(nodeId: string, depth: number): void {
    const node = props.state.nodes[nodeId];
    if (!node || (query && !branchMatches(nodeId))) {
      return;
    }
    const expanded = query.length > 0 || expandedIds.value.has(nodeId);
    visible.push({ node, depth, expanded });
    if (expanded) {
      node.childIds.forEach((childId) => visit(childId, depth + 1));
    }
  }

  props.state.rootIds.forEach((nodeId) => visit(nodeId, 1));
  return visible;
});

const visibleIds = computed(
  () => new Set(rows.value.map(({ node }) => node.id)),
);

const rovingFocusId = computed(() =>
  visibleIds.value.has(props.focusedId)
    ? props.focusedId
    : rows.value[0]?.node.id,
);

watch(
  () => props.insertedIds,
  (inserted) => {
    if (inserted.size === 0) {
      return;
    }
    const next = new Set(expandedIds.value);
    inserted.forEach((insertedId) => {
      let node = props.state.nodes[insertedId];
      while (node?.parentId) {
        next.add(node.parentId);
        node = props.state.nodes[node.parentId];
      }
    });
    expandedIds.value = next;
  },
);

function setRowElement(
  nodeId: string,
  element: Element | ComponentPublicInstance | null,
): void {
  if (element instanceof HTMLElement) {
    rowElements.set(nodeId, element);
  } else {
    rowElements.delete(nodeId);
  }
}

function toggle(nodeId: string): void {
  const next = new Set(expandedIds.value);
  if (next.has(nodeId)) {
    next.delete(nodeId);
  } else {
    next.add(nodeId);
  }
  expandedIds.value = next;
}

async function focusRow(nodeId: string | undefined): Promise<void> {
  if (!nodeId) {
    return;
  }
  emit("focusChange", nodeId);
  await nextTick();
  rowElements.get(nodeId)?.focus();
}

function selectRow(nodeId: string, event: MouseEvent): void {
  emit("select", nodeId);
  emit("focusChange", nodeId);
  (event.currentTarget as HTMLElement).focus();
}

function handleKey(event: KeyboardEvent, row: VisibleRow): void {
  const index = rows.value.findIndex(({ node }) => node.id === row.node.id);
  if (index < 0) {
    return;
  }

  let targetId: string | undefined;
  if (event.key === "ArrowDown") {
    targetId = rows.value[Math.min(index + 1, rows.value.length - 1)]?.node.id;
  } else if (event.key === "ArrowUp") {
    targetId = rows.value[Math.max(index - 1, 0)]?.node.id;
  } else if (event.key === "Home") {
    targetId = rows.value[0]?.node.id;
  } else if (event.key === "End") {
    targetId = rows.value.at(-1)?.node.id;
  } else if (event.key === "ArrowRight" && row.node.childIds.length > 0) {
    if (!row.expanded) {
      toggle(row.node.id);
    } else {
      targetId = rows.value[index + 1]?.node.id;
    }
  } else if (event.key === "ArrowLeft") {
    if (row.expanded && row.node.childIds.length > 0) {
      toggle(row.node.id);
    } else if (row.node.parentId && visibleIds.value.has(row.node.parentId)) {
      targetId = row.node.parentId;
    }
  } else if (event.key === "Enter" || event.key === " ") {
    emit("select", row.node.id);
  } else {
    return;
  }

  event.preventDefault();
  if (targetId) {
    void focusRow(targetId);
  }
}

function severityIcon(node: TreeNode) {
  if (node.severity === "error" || node.severity === "blocked") {
    return AlertCircle;
  }
  if (node.severity === "warning") {
    return AlertTriangle;
  }
  if (node.severity === "running") {
    return CircleDashed;
  }
  if (node.severity === "changed") {
    return FileSliders;
  }
  if (node.kind === "section" || node.kind === "group") {
    return Folder;
  }
  if (node.kind === "resource") {
    return Workflow;
  }
  return CheckCircle2;
}

function accessibleLabel(node: TreeNode): string {
  return [
    node.label,
    node.phase,
    node.valueSummary,
    node.severity !== "normal" ? node.severity : undefined,
  ]
    .filter(Boolean)
    .join(", ");
}
</script>

<template>
  <section class="tree-panel" aria-label="Resources">
    <div class="tree-heading">
      <div>
        <span class="eyebrow">Resources</span>
        <h2>Migration workflow</h2>
      </div>
      <span class="mode-badge" :class="{ editing: state.mode === 'edit' }">
        {{ state.mode === "edit" ? "Editing" : "Inspecting" }}
      </span>
    </div>

    <label class="tree-filter">
      <Search :size="16" aria-hidden="true" />
      <span class="sr-only">Filter resources</span>
      <input v-model="filter" type="search" placeholder="Filter resources">
    </label>

    <div class="tree-scroll">
      <TransitionGroup
        tag="div"
        name="tree-row"
        class="resource-tree"
        role="tree"
        aria-label="Workflow resources"
      >
        <div
          v-for="(row, index) in rows"
          :id="`tree-row-${row.node.id}`"
          :key="row.node.id"
          :ref="(element) => setRowElement(row.node.id, element)"
          class="tree-row"
          role="treeitem"
          :aria-label="accessibleLabel(row.node)"
          :aria-level="row.depth"
          :aria-posinset="index + 1"
          :aria-setsize="rows.length"
          :aria-expanded="row.node.childIds.length > 0 ? row.expanded : undefined"
          :aria-selected="selectedId === row.node.id"
          :data-node-id="row.node.id"
          :data-severity="row.node.severity"
          :data-inserted="insertedIds.has(row.node.id) || undefined"
          :tabindex="rovingFocusId === row.node.id ? 0 : -1"
          :style="{ '--tree-depth': row.depth }"
          @click="selectRow(row.node.id, $event)"
          @focus="emit('focusChange', row.node.id)"
          @keydown="handleKey($event, row)"
        >
          <button
            class="tree-expander"
            type="button"
            tabindex="-1"
            :disabled="row.node.childIds.length === 0"
            :aria-label="`${row.expanded ? 'Collapse' : 'Expand'} ${row.node.label}`"
            @mousedown.prevent
            @click.stop="toggle(row.node.id)"
          >
            <ChevronRight
              v-if="row.node.childIds.length > 0"
              :size="15"
              :class="{ open: row.expanded }"
              aria-hidden="true"
            />
          </button>
          <span class="tree-icon" aria-hidden="true">
            <component
              :is="severityIcon(row.node)"
              :size="16"
              :class="{ spinning: row.node.severity === 'running' }"
            />
          </span>
          <span class="tree-copy">
            <span class="tree-label">{{ row.node.label }}</span>
            <span v-if="row.node.phase || row.node.valueSummary" class="tree-meta">
              {{ [row.node.phase, row.node.valueSummary].filter(Boolean).join(" · ") }}
            </span>
          </span>
          <span
            v-if="row.node.severity !== 'normal'"
            class="severity-dot"
            aria-hidden="true"
          />
        </div>
      </TransitionGroup>
      <div v-if="rows.length === 0" class="tree-empty">
        No resources match "{{ filter }}".
      </div>
    </div>

    <footer class="tree-footer">
      <span>{{ Object.keys(state.nodes).length }} nodes · r{{ state.revision }}</span>
      <span>Arrow keys navigate</span>
    </footer>
  </section>
</template>
