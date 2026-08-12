<script setup lang="ts">
import {
  computed,
  onBeforeUnmount,
  ref,
  shallowRef,
  watch,
  type Component,
} from "vue";
import {
  Activity,
  AlertTriangle,
  Check,
  ClipboardCheck,
  FileOutput,
  Gauge,
  GitBranch,
  ListTree,
  Logs,
  Pencil,
  RefreshCw,
  RotateCcw,
  Settings2,
  ShieldCheck,
  SquareActivity,
  X,
} from "lucide-vue-next";
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
import ConfigurationPanel from "./ConfigurationPanel.vue";
import LogViewer from "./LogViewer.vue";
import OperationDrawer from "./OperationDrawer.vue";
import ResourceTree from "./ResourceTree.vue";
import { useManageTree } from "./useManageTree";

type WorkspaceTab = "overview" | "configuration" | "activity" | "logs" | "output";

interface TabDefinition {
  id: WorkspaceTab;
  label: string;
  icon: Component;
}

const tabs: ReadonlyArray<TabDefinition> = [
  { id: "overview", label: "Overview", icon: Gauge },
  { id: "configuration", label: "Configuration", icon: Settings2 },
  { id: "activity", label: "Activity", icon: Activity },
  { id: "logs", label: "Logs", icon: Logs },
  { id: "output", label: "Output", icon: FileOutput },
];

const {
  state,
  announcement,
  insertedIds,
  transitioning,
  applyPatch,
  applyPatches,
} = useManageTree();

const selectedId = ref("resource-proxy");
const focusedId = ref("resource-proxy");
const activeTab = ref<WorkspaceTab>("overview");
const logRunning = ref(false);
const logLines = shallowRef<ReadonlyArray<string>>([]);
const operations = shallowRef<ReadonlyArray<OperationState>>([createOperation()]);

let logIndex = 0;
let logTimer: ReturnType<typeof setInterval> | undefined;
let operationTimer: ReturnType<typeof setInterval> | undefined;

const selectedNode = computed(
  () => state.value.nodes[selectedId.value] ?? state.value.nodes["resource-proxy"],
);

const canAddTransform = computed(
  () =>
    state.value.mode === "edit" &&
    !!state.value.nodes["config-replayer"] &&
    !state.value.nodes["config-transform"] &&
    !transitioning.value,
);

const diagnostics = computed<ReadonlyArray<TreeNode>>(() => {
  const selected = selectedNode.value;
  if (!selected) {
    return [];
  }
  const result: TreeNode[] = [];
  function visit(nodeId: string): void {
    const node = state.value.nodes[nodeId];
    if (!node) {
      return;
    }
    if (node.kind === "diagnostic") {
      result.push(node);
    }
    node.childIds.forEach(visit);
  }
  selected.childIds.forEach(visit);
  return result;
});

watch(logRunning, (running) => {
  if (logTimer) {
    clearInterval(logTimer);
    logTimer = undefined;
  }
  if (running) {
    logTimer = setInterval(() => {
      logLines.value = [
        ...logLines.value.slice(-149),
        createLogLine(logIndex++),
      ];
    }, 420);
  }
});

operationTimer = setInterval(() => {
  operations.value = operations.value.map((operation) => {
    if (operation.state !== "running") {
      return operation;
    }
    const progress = Math.min(operation.progress + 8, 92);
    return progress >= 92
      ? {
          ...operation,
          progress,
          state: "waiting",
          phase: "Waiting for cluster state",
        }
      : { ...operation, progress };
  });
}, 760);

onBeforeUnmount(() => {
  if (logTimer) {
    clearInterval(logTimer);
  }
  if (operationTimer) {
    clearInterval(operationTimer);
  }
});

function enterEditMode(): void {
  if (state.value.mode === "inspect" && !transitioning.value) {
    applyPatches(ENTER_EDIT_MODE_PATCHES);
  }
}

function persistentAncestor(nodeId: string): string {
  let current = state.value.nodes[nodeId];
  while (current?.parentId && current.kind.startsWith("config")) {
    current = state.value.nodes[current.parentId];
  }
  return current?.id ?? "resource-proxy";
}

function leaveEditMode(): void {
  if (state.value.mode !== "edit" || transitioning.value) {
    return;
  }
  const patches: TreePatch[] = [];
  if (state.value.nodes["config-proxy"]) {
    patches.push({
      type: "remove",
      nodeId: "config-proxy",
      announce: "Capture proxy configuration closed.",
    });
  }
  if (state.value.nodes["config-replayer"]) {
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

  if (selectedNode.value?.kind.startsWith("config")) {
    selectedId.value = persistentAncestor(selectedId.value);
  }
  const focused = state.value.nodes[focusedId.value];
  if (focused?.kind.startsWith("config")) {
    focusedId.value = persistentAncestor(focusedId.value);
  }
  applyPatches(patches);
}

function addTransform(): void {
  if (canAddTransform.value) {
    applyPatches([ADD_TRANSFORM_PATCH]);
  }
}

function selectNode(nodeId: string): void {
  selectedId.value = nodeId;
  const node = state.value.nodes[nodeId];
  if (node?.kind === "config-field" || node?.kind === "config-group") {
    activeTab.value = "configuration";
  }
}

function startLogs(): void {
  activeTab.value = "logs";
  logRunning.value = true;
}

function beginOperation(label: string, phase: string): void {
  const operation: OperationState = {
    id: `operation-${Date.now()}`,
    label,
    phase,
    state: "running",
    progress: 12,
  };
  operations.value = [
    operation,
    ...operations.value,
  ].slice(0, 4);
  activeTab.value = "activity";
}

function performCapability(
  capability: NonNullable<TreeNode["capabilities"]>[number],
): void {
  if (capability === "edit") {
    enterEditMode();
  } else if (capability === "logs") {
    startLogs();
  } else if (capability === "output") {
    activeTab.value = "output";
  } else if (capability === "approve") {
    beginOperation(`Approve ${selectedNode.value?.label}`, "Submitting approval");
  } else if (capability === "reset") {
    beginOperation(`Plan reset for ${selectedNode.value?.label}`, "Calculating reset plan");
  }
}

const capabilityDetails = {
  edit: { label: "Edit", icon: Pencil },
  approve: { label: "Approve", icon: ShieldCheck },
  reset: { label: "Reset", icon: RotateCcw },
  logs: { label: "Logs", icon: Logs },
  output: { label: "Output", icon: FileOutput },
} as const;
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="brand">
        <span class="brand-mark">
          <SquareActivity :size="19" aria-hidden="true" />
        </span>
        <div>
          <h1>Workflow Manage</h1>
          <span>migration · ma-demo</span>
        </div>
      </div>

      <div class="header-state">
        <span class="run-state"><span /> Running</span>
        <span>Observed just now</span>
      </div>

      <div class="header-actions">
        <button
          class="icon-button"
          type="button"
          data-testid="refresh-control"
          aria-label="Simulate status refresh"
          title="Simulate status refresh"
          @click="applyPatch(STATUS_UPDATE_PATCH)"
        >
          <RefreshCw :size="16" aria-hidden="true" />
        </button>
        <button
          class="button secondary"
          type="button"
          data-testid="edit-mode-control"
          :disabled="transitioning"
          @click="state.mode === 'edit' ? leaveEditMode() : enterEditMode()"
        >
          <X v-if="state.mode === 'edit'" :size="16" aria-hidden="true" />
          <Pencil v-else :size="16" aria-hidden="true" />
          {{ state.mode === "edit" ? "Leave edit mode" : "Edit configuration" }}
        </button>
        <button
          class="button primary"
          type="button"
          :disabled="state.mode !== 'edit' || transitioning"
          @click="beginOperation('Review pending configuration', 'Checking schema and cluster state')"
        >
          <ClipboardCheck :size="16" aria-hidden="true" />
          Review changes
        </button>
      </div>
    </header>

    <div class="main-layout">
      <ResourceTree
        :state="state"
        :selected-id="selectedNode?.id ?? 'resource-proxy'"
        :focused-id="focusedId"
        :inserted-ids="insertedIds"
        @select="selectNode"
        @focus-change="focusedId = $event"
      />

      <main v-if="selectedNode" class="workspace">
        <div class="workspace-header">
          <div class="selection-heading">
            <span class="selection-icon" :data-severity="selectedNode.severity">
              <GitBranch :size="19" aria-hidden="true" />
            </span>
            <div>
              <div class="selection-context">
                {{ selectedNode.kind.replace("-", " ") }} ·
                {{ selectedNode.phase ?? selectedNode.severity }}
              </div>
              <h2>{{ selectedNode.label }}</h2>
              <p>{{ selectedNode.description ?? "Workflow resource" }}</p>
            </div>
          </div>
          <div class="selection-actions" aria-label="Resource actions">
            <button
              v-for="capability in selectedNode.capabilities"
              :key="capability"
              class="button tertiary"
              type="button"
              :disabled="capability === 'edit' && (state.mode === 'edit' || transitioning)"
              @click="performCapability(capability)"
            >
              <component :is="capabilityDetails[capability].icon" :size="16" aria-hidden="true" />
              {{ capabilityDetails[capability].label }}
            </button>
          </div>
        </div>

        <div class="workspace-tabs" role="tablist" aria-label="Resource details">
          <button
            v-for="tab in tabs"
            :id="`tab-${tab.id}`"
            :key="tab.id"
            role="tab"
            type="button"
            :aria-selected="activeTab === tab.id"
            :tabindex="activeTab === tab.id ? 0 : -1"
            @click="activeTab = tab.id"
          >
            <component :is="tab.icon" :size="16" aria-hidden="true" />
            {{ tab.label }}
            <span
              v-if="tab.id === 'logs' && logRunning"
              class="tab-live-dot"
              aria-label="streaming"
            />
          </button>
        </div>

        <section
          class="workspace-content"
          role="tabpanel"
          :aria-labelledby="`tab-${activeTab}`"
        >
          <div v-if="activeTab === 'overview'" class="overview">
            <dl class="facts-grid">
              <div><dt>Phase</dt><dd>{{ selectedNode.phase ?? "Not started" }}</dd></div>
              <div><dt>Status</dt><dd :data-severity="selectedNode.severity">{{ selectedNode.severity }}</dd></div>
              <div><dt>Current value</dt><dd>{{ selectedNode.valueSummary ?? "No value reported" }}</dd></div>
              <div><dt>Resource ID</dt><dd class="mono">{{ selectedNode.id }}</dd></div>
            </dl>

            <section class="detail-section">
              <h3>Diagnostics</h3>
              <details v-for="diagnostic in diagnostics" :key="diagnostic.id" class="diagnostic-item">
                <summary>
                  <AlertTriangle :size="16" aria-hidden="true" />
                  <span>{{ diagnostic.label }}</span>
                  <span>{{ diagnostic.severity }}</span>
                </summary>
                <p>{{ diagnostic.description }}</p>
                <strong>{{ diagnostic.diagnostic }}</strong>
              </details>
              <div v-if="diagnostics.length === 0" class="inline-empty">
                <Check :size="17" aria-hidden="true" />
                No diagnostics for this selection.
              </div>
            </section>

            <section class="detail-section">
              <h3>Dependencies</h3>
              <div class="dependency-row">
                <span>capture-proxy</span><i /><span>captured-traffic</span><i /><strong>{{ selectedNode.label }}</strong>
              </div>
            </section>
          </div>

          <ConfigurationPanel
            v-else-if="activeTab === 'configuration'"
            :state="state"
            :selected-node="selectedNode"
            :can-add-transform="canAddTransform"
            :transform-added="!!state.nodes['config-transform']"
            @add-transform="addTransform"
          />

          <div v-else-if="activeTab === 'activity'" class="activity-list">
            <div v-for="operation in operations" :key="operation.id" class="activity-row">
              <span :data-state="operation.state" />
              <div><strong>{{ operation.label }}</strong><p>{{ operation.phase }}</p></div>
              <small>{{ operation.state }}</small>
            </div>
          </div>

          <LogViewer
            v-else-if="activeTab === 'logs'"
            :lines="logLines"
            :running="logRunning"
            @start="logRunning = true"
            @stop="logRunning = false"
            @clear="logLines = []"
          />

          <div v-else class="output-panel">
            <div class="output-stage"><span>01</span><div><strong>Evaluate capture</strong><p>Target compatibility and prerequisites</p></div><b>Complete</b></div>
            <div class="output-stage"><span>02</span><div><strong>Migrate live traffic</strong><p>Capture, buffer, transform, and replay</p></div><b>Running</b></div>
            <pre><code>target:
  endpoint: search-target:9200
replay:
  accepted: 14,208
  failed: 3</code></pre>
          </div>
        </section>
      </main>

      <OperationDrawer :operations="operations" />
    </div>

    <div class="sr-only" aria-live="polite" aria-atomic="true">
      {{ announcement }}
    </div>
  </div>
</template>
