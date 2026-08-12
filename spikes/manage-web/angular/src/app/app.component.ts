import { A11yModule, LiveAnnouncer } from "@angular/cdk/a11y";
import { CommonModule } from "@angular/common";
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
  computed,
  inject,
  signal,
} from "@angular/core";
import {
  Activity,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  CircleAlert,
  Clock3,
  FileOutput,
  FileText,
  GitBranch,
  Layers3,
  ListTree,
  LucideAngularModule,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  RotateCcw,
  Search,
  Send,
  Settings2,
  ShieldCheck,
  Square,
  Terminal,
  X,
} from "lucide-angular";
import {
  ADD_TRANSFORM_PATCH,
  DEFAULT_EXPANDED_IDS,
  ENTER_EDIT_MODE_PATCHES,
  STATUS_UPDATE_PATCH,
  applyTreePatch,
  createInitialState,
  createLogLine,
  createOperation,
  type ManageTreeState,
  type OperationState,
  type TreeNode,
  type TreePatch,
} from "@manage-spike/shared";
import { Subscription, interval } from "rxjs";

import { ConfigFieldComponent } from "./config-field.component";

type DetailTab = "overview" | "configuration" | "activity" | "logs" | "output";

interface VisibleTreeRow {
  readonly node: TreeNode;
  readonly level: number;
  readonly hasChildren: boolean;
  readonly expanded: boolean;
}

interface DetailTabDefinition {
  readonly id: DetailTab;
  readonly label: string;
  readonly icon: "file-text" | "settings-2" | "activity" | "terminal" | "file-output";
}

const TABS: ReadonlyArray<DetailTabDefinition> = [
  { id: "overview", label: "Overview", icon: "file-text" },
  { id: "configuration", label: "Configuration", icon: "settings-2" },
  { id: "activity", label: "Activity", icon: "activity" },
  { id: "logs", label: "Logs", icon: "terminal" },
  { id: "output", label: "Output", icon: "file-output" },
];

export const MANAGE_ICONS = {
  Activity,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  CircleAlert,
  Clock3,
  FileOutput,
  FileText,
  GitBranch,
  Layers3,
  ListTree,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  RotateCcw,
  Search,
  Send,
  Settings2,
  ShieldCheck,
  Square,
  Terminal,
  X,
};

@Component({
  selector: "app-root",
  standalone: true,
  imports: [
    A11yModule,
    CommonModule,
    ConfigFieldComponent,
    LucideAngularModule,
  ],
  templateUrl: "./app.component.html",
  styleUrl: "./app.component.css",
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent implements OnDestroy {
  @ViewChild("logViewport") private logViewport?: ElementRef<HTMLElement>;

  private readonly liveAnnouncer = inject(LiveAnnouncer);
  private readonly pendingPatchTimers = new Set<ReturnType<typeof setTimeout>>();
  private logSubscription?: Subscription;
  private logIndex = 0;

  readonly tabs = TABS;
  readonly state = signal<ManageTreeState>(createInitialState());
  readonly selectedId = signal("resource-proxy");
  readonly activeTab = signal<DetailTab>("overview");
  readonly expandedIds = signal<ReadonlySet<string>>(new Set(DEFAULT_EXPANDED_IDS));
  readonly filterText = signal("");
  readonly recentlyInsertedIds = signal<ReadonlySet<string>>(new Set());
  readonly lastRefresh = signal("12 seconds ago");
  readonly logs = signal<ReadonlyArray<string>>([]);
  readonly logsRunning = signal(false);
  readonly operations = signal<ReadonlyArray<OperationState>>([createOperation()]);

  readonly selectedNode = computed(
    () => this.state().nodes[this.selectedId()] ?? this.state().nodes["resource-proxy"],
  );

  readonly visibleRows = computed<ReadonlyArray<VisibleTreeRow>>(() => {
    const state = this.state();
    const expanded = this.expandedIds();
    const query = this.filterText().trim().toLocaleLowerCase();
    const included = new Map<string, boolean>();

    const isIncluded = (nodeId: string): boolean => {
      const cached = included.get(nodeId);
      if (cached !== undefined) {
        return cached;
      }
      const node = state.nodes[nodeId];
      if (!node) {
        included.set(nodeId, false);
        return false;
      }
      const ownMatch =
        node.label.toLocaleLowerCase().includes(query) ||
        node.description?.toLocaleLowerCase().includes(query) === true ||
        node.valueSummary?.toLocaleLowerCase().includes(query) === true;
      const childMatch = node.childIds.some((childId) => isIncluded(childId));
      const result = query.length === 0 || ownMatch || childMatch;
      included.set(nodeId, result);
      return result;
    };

    const rows: VisibleTreeRow[] = [];
    const visit = (nodeId: string, level: number): void => {
      const node = state.nodes[nodeId];
      if (!node || !isIncluded(nodeId)) {
        return;
      }
      const expandedForView = query.length > 0 || expanded.has(nodeId);
      rows.push({
        node,
        level,
        hasChildren: node.childIds.length > 0,
        expanded: expandedForView,
      });
      if (expandedForView) {
        node.childIds.forEach((childId) => visit(childId, level + 1));
      }
    };
    state.rootIds.forEach((rootId) => visit(rootId, 1));
    return rows;
  });

  readonly configurationFields = computed<ReadonlyArray<TreeNode>>(() => {
    const state = this.state();
    const selected = this.selectedNode();
    if (!selected) {
      return [];
    }
    const fields: TreeNode[] = [];
    const collect = (nodeId: string): void => {
      const node = state.nodes[nodeId];
      if (!node) {
        return;
      }
      if (node.kind === "config-field" && node.configControl) {
        fields.push(node);
      }
      node.childIds.forEach(collect);
    };

    if (selected.kind === "config-field") {
      collect(selected.id);
    } else if (selected.kind === "config-group") {
      collect(selected.id);
    } else {
      selected.childIds.forEach(collect);
    }
    return fields;
  });

  readonly selectedDiagnostics = computed<ReadonlyArray<TreeNode>>(() => {
    const state = this.state();
    const selected = this.selectedNode();
    if (!selected) {
      return [];
    }
    return selected.childIds
      .map((childId) => state.nodes[childId])
      .filter((node): node is TreeNode => node?.kind === "diagnostic");
  });

  readonly canAddTransform = computed(
    () =>
      this.state().mode === "edit" &&
      this.state().nodes["config-replayer"] !== undefined &&
      this.state().nodes["config-transform"] === undefined,
  );

  readonly treeStatusText = computed(() => {
    const count = Object.keys(this.state().nodes).length;
    return `${count} nodes · revision ${this.state().revision}`;
  });

  ngOnDestroy(): void {
    this.pendingPatchTimers.forEach(clearTimeout);
    this.pendingPatchTimers.clear();
    this.logSubscription?.unsubscribe();
  }

  setFilter(event: Event): void {
    this.filterText.set((event.target as HTMLInputElement).value);
  }

  clearFilter(): void {
    this.filterText.set("");
  }

  selectNode(nodeId: string): void {
    this.selectedId.set(nodeId);
    const node = this.state().nodes[nodeId];
    if (node?.kind === "config-field" || node?.kind === "config-group") {
      this.activeTab.set("configuration");
    }
  }

  setActiveTab(tab: DetailTab): void {
    this.activeTab.set(tab);
  }

  toggleExpanded(nodeId: string, event?: Event): void {
    event?.stopPropagation();
    const next = new Set(this.expandedIds());
    if (next.has(nodeId)) {
      next.delete(nodeId);
    } else {
      next.add(nodeId);
    }
    this.expandedIds.set(next);
  }

  handleTreeKey(event: KeyboardEvent, row: VisibleTreeRow): void {
    const rows = this.visibleRows();
    const index = rows.findIndex((candidate) => candidate.node.id === row.node.id);
    if (index < 0) {
      return;
    }

    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        this.focusRow(rows[Math.min(index + 1, rows.length - 1)]?.node.id);
        break;
      case "ArrowUp":
        event.preventDefault();
        this.focusRow(rows[Math.max(index - 1, 0)]?.node.id);
        break;
      case "ArrowRight":
        event.preventDefault();
        if (row.hasChildren && !row.expanded) {
          this.toggleExpanded(row.node.id);
        } else if (row.hasChildren) {
          this.focusRow(row.node.childIds[0]);
        }
        break;
      case "ArrowLeft":
        event.preventDefault();
        if (row.hasChildren && row.expanded) {
          this.toggleExpanded(row.node.id);
        } else {
          this.focusRow(row.node.parentId);
        }
        break;
      case "Home":
        event.preventDefault();
        this.focusRow(rows[0]?.node.id);
        break;
      case "End":
        event.preventDefault();
        this.focusRow(rows[rows.length - 1]?.node.id);
        break;
      case "Enter":
      case " ":
        event.preventDefault();
        this.selectNode(row.node.id);
        break;
    }
  }

  enterEditMode(): void {
    if (this.state().mode === "edit") {
      return;
    }
    this.addExpandedIds([
      "resource-proxy",
      "config-proxy",
      "config-client-auth",
      "resource-replayer",
      "config-replayer",
    ]);
    this.applyPatchSequence(ENTER_EDIT_MODE_PATCHES);
  }

  leaveEditMode(): void {
    this.clearPendingPatchTimers();
    const selected = this.selectedNode();
    if (
      selected?.id.startsWith("config-") ||
      selected?.parentId?.startsWith("config-")
    ) {
      this.selectedId.set(
        this.isDescendantOf(selected.id, "resource-replayer")
          ? "resource-replayer"
          : "resource-proxy",
      );
    }

    if (this.state().nodes["config-proxy"]) {
      this.applyPatch({
        type: "remove",
        nodeId: "config-proxy",
        announce: "Capture proxy configuration hidden.",
      });
    }
    if (this.state().nodes["config-replayer"]) {
      this.applyPatch({
        type: "remove",
        nodeId: "config-replayer",
        announce: "Traffic replayer configuration hidden.",
      });
    }
    this.applyPatch({
      type: "set-mode",
      mode: "inspect",
      announce: "Returned to inspection mode.",
    });
  }

  addTransform(): void {
    if (!this.canAddTransform()) {
      return;
    }
    this.addExpandedIds(["resource-replayer", "config-replayer", "config-transform"]);
    this.applyPatch(ADD_TRANSFORM_PATCH);
  }

  simulateStatusRefresh(): void {
    this.applyPatch(STATUS_UPDATE_PATCH);
    this.lastRefresh.set("just now");
  }

  startLogs(): void {
    if (this.logsRunning()) {
      return;
    }
    this.activeTab.set("logs");
    this.logsRunning.set(true);
    this.logSubscription = interval(360).subscribe(() => {
      const nextLine = createLogLine(this.logIndex++);
      this.logs.update((current) => [...current.slice(-119), nextLine]);
      requestAnimationFrame(() => {
        const viewport = this.logViewport?.nativeElement;
        if (viewport) {
          viewport.scrollTop = viewport.scrollHeight;
        }
      });
    });
  }

  stopLogs(): void {
    this.logSubscription?.unsubscribe();
    this.logSubscription = undefined;
    this.logsRunning.set(false);
  }

  clearLogs(): void {
    this.logs.set([]);
  }

  dismissOperation(operationId: string): void {
    this.operations.update((operations) =>
      operations.filter((operation) => operation.id !== operationId),
    );
  }

  runReview(): void {
    this.operations.set([
      {
        id: "operation-review",
        label: "Validate pending configuration",
        phase: "Checking cluster references",
        state: "running",
        progress: 36,
      },
      ...this.operations().filter((operation) => operation.id !== "operation-review"),
    ]);
  }

  severityLabel(node: TreeNode): string {
    return node.severity === "normal" ? "ready" : node.severity;
  }

  parentLabel(node: TreeNode): string {
    let current = node.parentId ? this.state().nodes[node.parentId] : undefined;
    while (current?.kind === "config-group") {
      if (current.parentId && this.state().nodes[current.parentId]?.kind !== "config-group") {
        return current.label;
      }
      current = current.parentId ? this.state().nodes[current.parentId] : undefined;
    }
    return current?.label ?? "Configuration";
  }

  trackOperation(_index: number, operation: OperationState): string {
    return operation.id;
  }

  private applyPatchSequence(patches: ReadonlyArray<TreePatch>): void {
    patches.forEach((patch, index) => {
      const timer = setTimeout(() => {
        this.pendingPatchTimers.delete(timer);
        this.applyPatch(patch);
      }, index * 140);
      this.pendingPatchTimers.add(timer);
    });
  }

  private applyPatch(patch: TreePatch): void {
    const insertedIds =
      patch.type === "insert" ? patch.nodes.map((node) => node.id) : [];
    this.state.update((state) => applyTreePatch(state, patch));
    if (insertedIds.length > 0) {
      const next = new Set(this.recentlyInsertedIds());
      insertedIds.forEach((id) => next.add(id));
      this.recentlyInsertedIds.set(next);
      const timer = setTimeout(() => {
        this.pendingPatchTimers.delete(timer);
        const remaining = new Set(this.recentlyInsertedIds());
        insertedIds.forEach((id) => remaining.delete(id));
        this.recentlyInsertedIds.set(remaining);
      }, 1100);
      this.pendingPatchTimers.add(timer);
    }
    void this.liveAnnouncer.announce(patch.announce, "polite");
  }

  private addExpandedIds(ids: ReadonlyArray<string>): void {
    const next = new Set(this.expandedIds());
    ids.forEach((id) => next.add(id));
    this.expandedIds.set(next);
  }

  private focusRow(nodeId: string | undefined): void {
    if (!nodeId) {
      return;
    }
    const row = document.querySelector<HTMLElement>(
      `[data-node-id="${nodeId}"]`,
    );
    row?.focus();
  }

  private isDescendantOf(nodeId: string, ancestorId: string): boolean {
    let current = this.state().nodes[nodeId];
    while (current?.parentId) {
      if (current.parentId === ancestorId) {
        return true;
      }
      current = this.state().nodes[current.parentId];
    }
    return false;
  }

  private clearPendingPatchTimers(): void {
    this.pendingPatchTimers.forEach(clearTimeout);
    this.pendingPatchTimers.clear();
  }
}
