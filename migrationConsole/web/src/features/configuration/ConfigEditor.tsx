import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  ArrowLeft,
  Check,
  ChevronDown,
  ChevronRight,
  ChevronsDown,
  LoaderCircle,
  Link2,
  Network,
  Pencil,
  Plus,
  Save,
  SquareArrowOutUpRight,
  Trash2,
  Unlink,
  Undo2,
  X,
} from "lucide-react";

import {
  configRemovalImpact as localConfigRemovalImpact,
  type ConfigRemovalImpactEntry,
  type EditNode,
  type EditOperation,
} from "@opensearch-migrations/config-edit-core";
import {
  getConfigurationDocument,
  getConfigurationSchema,
  saveConfigurationDocument,
  type ManageSnapshot,
} from "../../api/client";
import { ModalDialog } from "../../components/ModalDialog";
import { useEscapeCancel } from "../../hooks/useEscapeCancel";
import { SubmitConfigDialog } from "../submission/SubmitConfigDialog";
import { ExternalResourceEditor } from "./ExternalResourceEditor";
import {
  BROWSER_CONFIG_DRAFT_QUERY_KEY,
  acknowledgeSavedBrowserConfigDraft,
  applyBrowserEditOperation,
  createBrowserConfigDraft,
  replaceBrowserConfigYaml,
  revertedBrowserConfigDraft,
  type BrowserConfigDraft,
} from "./browserDraft";
import {
  readEditorDisplayPreferences,
  writeEditorDisplayPreferences,
} from "./editorPreferences";
import {
  ConnectivityDialog,
  useConnectivityChecks,
  type ConnectivityNavigationStates,
} from "./connectivityChecks";
import {
  environmentReferenceGroups,
  useEnvironmentDiagnostics,
} from "./environmentDiagnostics";
import { humanizeFieldLabel } from "./fieldLabels";
import { fieldValidationProblem } from "./fieldValidation";
import {
  pendingResourceAddition,
  pendingResourceRename,
  resourceAddPlacement,
  resourceRenameCollisionProblem,
  type PendingResourceAddition,
  type PendingResourceRename,
  type ResourceAddController,
  type ResourceAddOption,
  type ResourceRenameOption,
} from "./resourceAdds";
import { ValidityDashboard } from "./ValidityDashboard";


interface ConfigEditorProps {
  initialRemovalTargetId?: string | null;
  initialTargetId?: string | null;
  navigationBackLabel?: string | null;
  onClose: () => void;
  onExitReady: (handler: (() => void) | null) => void;
  onSubmitReady: (handler: (() => void) | null) => void;
  onNavigateBack?: () => void;
  onDraftReverted: () => void;
  onResourceAddStarted: (addition: PendingResourceAddition) => void;
  onResourceAddSettled: (
    addition: PendingResourceAddition,
    applied: boolean,
  ) => void;
  onResourceRenameStarted: (rename: PendingResourceRename) => void;
  onResourceRenameSettled: (
    rename: PendingResourceRename,
    applied: boolean,
  ) => void;
  onResourceAddsReady: (controller: ResourceAddController | null) => void;
  onNavigateEditTarget: (targetId: string) => void;
  onNavigateCreatedEditTarget: (
    targetId: string,
    returnTargetId: string,
    returnLabel: string,
  ) => boolean;
  onInitialRemovalHandled?: () => void;
  onConnectivityStatesChange?: (
    states: ConnectivityNavigationStates,
  ) => void;
  onSubmitted: () => void;
  removalState?: string | null;
  resourceId: string;
  resourceLabel: string;
  resourceType: string;
  resourceSyncing?: boolean;
  stateSummary?: string | null;
  navigationSnapshot?: ManageSnapshot | null;
}


interface CanonicalReference {
  label: string;
  targetId: string;
}


type ApplyExternalOperations = (
  operations: EditOperation[],
  notice?: string,
) => Promise<boolean>;


interface ConfigRemovalImpact {
  targetPath: string[];
  targetLabel: string;
  affected: ConfigRemovalImpactEntry[];
}


interface EditRow {
  node: EditNode;
  depth: number;
}


interface PinnedContext {
  id: string;
  progress: number;
}


type ValidationErrorEmphasis = "item" | "ancestor" | null;


interface AddContext {
  command: EditNode;
  parent: EditNode;
}


interface ReferenceAddContext extends AddContext {
  option: ResourceAddOption;
  sourcePath: string[];
}


interface PendingRemoval {
  node: EditNode;
  impact: ConfigRemovalImpact | null;
  loading: boolean;
  error: string;
  reviewingTargetId?: string;
}


const PINNED_CONTEXT_HEIGHT = 32;
const PINNED_CONTEXT_TRANSITION = 28;
const ROW_TRANSITION_MS = 220;
function nodeChildren(node: EditNode): EditNode[] {
  return node.children ?? [];
}


function propertyChildren(node: EditNode): EditNode[] {
  return nodeChildren(node).filter((child) => child.valueKind !== "command");
}


function addCommands(node: EditNode): EditNode[] {
  return nodeChildren(node).filter(
    (child) => child.valueKind === "command",
  );
}


function addCommand(node: EditNode): EditNode | null {
  return addCommands(node)[0] ?? null;
}


function editPathsOverlap(left: string[], right: string[]): boolean {
  return left.every((part, index) => right[index] === part)
    || right.every((part, index) => left[index] === part);
}


function contentScope(scope: EditNode | null): EditNode | null {
  if (scope?.id !== "edit:snapshotMigration") return scope;
  const children = propertyChildren(scope);
  const collection = children.length === 1 ? children[0] : null;
  return (
    collection?.valueKind === "array"
    && collection.path.length === 1
    && collection.path[0] === "snapshotMigrationConfigs"
  )
    ? collection
    : scope;
}


function topLevelAddContexts(nodes: EditNode[]): AddContext[] {
  const result: AddContext[] = [];
  const visit = (node: EditNode) => {
    if (resourceAddPlacement(node)) {
      const command = addCommand(node);
      if (command) result.push({ command, parent: node });
    }
    propertyChildren(node).forEach(visit);
  };
  nodes.forEach(visit);
  return result;
}


function referenceSourcePaths(node: EditNode): string[][] {
  if (node.inputHint?.kind !== "reference") return [];
  const hint = hintRecord(node.inputHint);
  const paths: string[][] = [];
  if (
    Array.isArray(hint.sourcePath)
    && hint.sourcePath.every((segment) => typeof segment === "string")
  ) {
    paths.push(hint.sourcePath);
  }
  if (Array.isArray(hint.sourcePaths)) {
    hint.sourcePaths.forEach((path) => {
      if (
        Array.isArray(path)
        && path.every((segment) => typeof segment === "string")
      ) {
        paths.push(path);
      }
    });
  }
  return paths;
}


function referenceAddContexts(
  node: EditNode,
  topLevelAdds: AddContext[],
  resourceAddOptions: ResourceAddOption[],
): ReferenceAddContext[] {
  return referenceSourcePaths(node).flatMap((sourcePath) => {
    const collectionPath = sourcePath.join(".");
    const option = resourceAddOptions.find(
      (candidate) => candidate.placement.collectionPath === collectionPath,
    );
    const context = option
      ? topLevelAdds.find(
        (candidate) => candidate.command.id === option.id,
      )
      : undefined;
    return option && context
      ? [{ ...context, option, sourcePath }]
      : [];
  });
}


function provisionalReferenceName(
  node: EditNode,
  context: ReferenceAddContext,
): string {
  const existingNames = new Set(
    propertyChildren(context.parent)
      .map((child) => child.path.at(-1))
      .filter((name): name is string => typeof name === "string"),
  );
  const resourceSlug = context.option.placement.resourceType
    .toLocaleLowerCase()
    .replaceAll(/[^a-z0-9]+/g, "-")
    .replaceAll(/^-+/g, "")
    .replaceAll(/-+$/g, "");
  const ownerName = node.path.at(-2) ?? "";
  const preferredBase = (
    context.option.placement.resourceType.toLocaleLowerCase().includes("topic")
    && ownerName
  )
    ? ownerName
    : resourceSlug || "resource";
  for (let suffix = 1; suffix < 10_000; suffix += 1) {
    const candidate = suffix === 1
      ? preferredBase
      : `${preferredBase}-${suffix}`;
    if (
      !existingNames.has(candidate)
      && !fieldValidationProblem(
        candidate,
        context.option.pattern,
        context.option.validationMessage,
      )
    ) {
      return candidate;
    }
  }
  return `resource-${Date.now()}`;
}


function renameableConfigPath(path: readonly string[]): boolean {
  if (
    path.length === 2
    && ["sourceClusters", "targetClusters"].includes(path[0])
  ) {
    return true;
  }
  if (
    path.length === 3
    && path[0] === "traffic"
    && ["kafkaClusters", "proxies", "s3Sources", "replayers"]
      .includes(path[1])
  ) {
    return true;
  }
  if (
    path.length === 5
    && path[0] === "traffic"
    && path[1] === "kafkaClusters"
    && path[3] === "topics"
  ) {
    return true;
  }
  return (
    path.length === 5
    && path[0] === "sourceClusters"
    && path[2] === "snapshotInfo"
    && ["repos", "snapshots", "backups"].includes(path[3])
  );
}


const KUBERNETES_NAME_PATTERN =
  String.raw`^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$`;
const KUBERNETES_NAME_MESSAGE =
  "Use a valid Kubernetes DNS name: lowercase letters, numbers, '-' or '.', starting and ending with an alphanumeric character.";


function resourceRenameOptions(nodes: EditNode[]): ResourceRenameOption[] {
  const result: ResourceRenameOption[] = [];
  const visit = (node: EditNode) => {
    const placement = resourceAddPlacement(node);
    const command = addCommand(node);
    if (
      placement
      && placement.resourcePlural !== "snapshotmigrations"
      && command?.command?.requiresName !== false
    ) {
      const collectionDepth = node.path.length;
      propertyChildren(node).forEach((child) => {
        if (
          child.path.length !== collectionDepth + 1
          || child.implicit === true
        ) {
          return;
        }
        const currentName = child.path.at(-1) ?? "";
        const parentHint = hintRecord(node.inputHint);
        const childValidation = hintRecord(child.validation);
        const kubernetesBacked = (
          child.path.length === 3 && child.path[0] === "traffic"
        );
        result.push({
          currentName,
          editTargetId: child.id,
          label: currentName,
          path: child.path,
          pattern: kubernetesBacked
            ? KUBERNETES_NAME_PATTERN
            : typeof parentHint.keyPattern === "string"
              ? parentHint.keyPattern
              : typeof childValidation.pattern === "string"
                ? childValidation.pattern
                : undefined,
          placement,
          validationMessage: kubernetesBacked
            ? KUBERNETES_NAME_MESSAGE
            : typeof parentHint.message === "string"
              ? parentHint.message
              : typeof childValidation.message === "string"
                ? childValidation.message
                : undefined,
        });
      });
    }
    if (placement?.resourcePlural === "snapshotmigrations") {
      propertyChildren(node).forEach((migration) => {
        const migrationValue = hintRecord(migration.value);
        const source = typeof migrationValue.fromSource === "string"
          ? migrationValue.fromSource
          : "";
        const target = typeof migrationValue.toTarget === "string"
          ? migrationValue.toTarget
          : "";
        const snapshot = typeof migrationValue.fromSnapshot === "string"
          ? migrationValue.fromSnapshot
          : "";
        const currentName = typeof migrationValue.slice === "string"
          ? migrationValue.slice
          : "";
        const sliceField = propertyChildren(migration).find(
          (child) => child.path.at(-1) === "slice",
        );
        const validation = hintRecord(sliceField?.validation);
        const collisionScope = source && target && snapshot
          ? `${source}-${target}-${snapshot}`
          : undefined;
        const displayPrefix = `${
          source || "<SOURCE>"
        }-${target || "<TARGET>"}-${snapshot || "<SNAPSHOT>"}-`;
        result.push({
          collisionScope,
          currentName,
          editTargetStable: true,
          editTargetId: migration.id,
          label: `${displayPrefix}${currentName || "<NAME>"}`,
          labelPrefix: displayPrefix,
          operation: "set",
          path: [...migration.path, "slice"],
          pattern: typeof validation.pattern === "string"
            ? validation.pattern
            : KUBERNETES_NAME_PATTERN,
          placement,
          resourceNamePrefix: displayPrefix,
          validationMessage: typeof validation.message === "string"
            ? validation.message
            : KUBERNETES_NAME_MESSAGE,
        });
      });
    }
    propertyChildren(node).forEach(visit);
  };
  nodes.forEach(visit);
  return result.map((option) => (
    option.collisionScope
      ? {
          ...option,
          conflictingNames: result.flatMap((candidate) => (
            candidate.editTargetId !== option.editTargetId
            && candidate.collisionScope === option.collisionScope
              ? [candidate.currentName]
              : []
          )),
        }
      : option
  ));
}


function allNodeIds(nodes: EditNode[]): Set<string> {
  const result = new Set<string>();
  const visit = (node: EditNode) => {
    result.add(node.id);
    nodeChildren(node).forEach(visit);
  };
  nodes.forEach(visit);
  return result;
}


function fieldName(node: EditNode, parent: EditNode | null = null): string {
  const prefix = node.label.split(":", 1)[0].replace(/^\+ Add /, "");
  if (
    node.valueKind === "command"
    || renameableConfigPath(node.path)
    || (
      node.path.length === 2
      && node.path[0] === "snapshotMigrationConfigs"
    )
    || (parent?.valueKind === "record" || parent?.valueKind === "array")
  ) {
    return prefix || node.path.at(-1) || "Configuration";
  }
  return humanizeFieldLabel(
    prefix || node.path.at(-1) || "Configuration",
  );
}


function previousValueLabel(value: unknown): string {
  if (typeof value === "string") return value || "(empty string)";
  if (value === null) return "null";
  if (value === undefined) return "unset";
  try {
    return JSON.stringify(value) ?? "(value unavailable)";
  } catch {
    return "(value unavailable)";
  }
}


function draftChangeTitle(node: EditNode): string {
  const change = node.draftChange;
  if (change?.kind === "added") return "Added in this edit. Previously unset.";
  if (change) {
    return change.previousValuePresent
      ? `Changed in this edit. Previous value: ${previousValueLabel(change.previousValue)}.`
      : "Changed in this edit. Previously unset.";
  }
  if (node.draftChangeCount) {
    return `${node.draftChangeCount} changed ${
      node.draftChangeCount === 1 ? "field" : "fields"
    } in this section.`;
  }
  return "";
}


function nodeHasIssue(node: EditNode): boolean {
  return ["required", "error", "warning", "gated", "blocked"]
    .includes(node.status ?? "");
}


function nodeHasValidationError(node: EditNode): boolean {
  const counts = node.statusCounts;
  return (
    (counts?.errors ?? 0)
    + (counts?.required ?? 0)
    + (counts?.gated ?? 0)
    + (counts?.blocked ?? 0)
  ) > 0 || ["required", "error", "gated", "blocked"]
    .includes(node.status ?? "");
}


function nodeTreeHasValidationError(node: EditNode): boolean {
  return nodeHasValidationError(node)
    || propertyChildren(node).some(nodeTreeHasValidationError);
}


function nodeTreeHasAuthoredValue(node: EditNode): boolean {
  return Boolean(node.valueAuthored)
    || propertyChildren(node).some(nodeTreeHasAuthoredValue);
}


function validationErrorEmphasis(
  node: EditNode,
): ValidationErrorEmphasis {
  const selfHasError = nodeHasValidationError(node);
  const childHasError = propertyChildren(node).some(
    nodeTreeHasValidationError,
  );
  if (!selfHasError && !childHasError) return null;
  const hasOwnDiagnostic = (node.diagnostics ?? []).some((diagnostic) =>
    ["required", "error", "gated", "blocked"]
      .includes(diagnostic.severity));
  return selfHasError && (hasOwnDiagnostic || !childHasError)
    ? "item"
    : "ancestor";
}


function scopeActionMessage(message: string): string {
  const normalized = message.toLocaleLowerCase();
  const compact = normalized.replaceAll(/[^a-z]/g, "");
  if (
    compact.includes("metadata")
    && compact.includes("documentbackfill")
    && (
      normalized.includes("at least one")
      || normalized.startsWith("add metadata migration")
    )
  ) {
    return "Add at least one migration path: metadata migration, document backfill, or both.";
  }
  return message;
}


function visibleNode(
  node: EditNode,
  showOptional: boolean,
  showExpert: boolean,
): boolean {
  if (
    node.expert
    && !showExpert
    && !nodeTreeHasAuthoredValue(node)
    && !nodeHasIssue(node)
  ) {
    return false;
  }
  if (
    node.presence === "optional"
    && !showOptional
    && !node.essential
    && !node.valueAuthored
    && !nodeHasIssue(node)
  ) {
    return propertyChildren(node).some((child) =>
      visibleNode(child, showOptional, showExpert));
  }
  return true;
}


function treeRows(
  nodes: EditNode[],
  expanded: ReadonlySet<string>,
  showOptional: boolean,
  showExpert: boolean,
): EditRow[] {
  const rows: EditRow[] = [];
  const visit = (node: EditNode, depth: number) => {
    if (node.valueKind === "command") return;
    if (!visibleNode(node, showOptional, showExpert)) return;
    rows.push({ node, depth });
    if (expanded.has(node.id)) {
      nodeChildren(node).forEach((child) => visit(child, depth + 1));
    }
  };
  nodes.forEach((node) => visit(node, 1));
  return rows;
}

const AUTO_EXPAND_SUBTREE_LIMIT = 12;


function visibleSubtreeSize(
  node: EditNode,
  showOptional: boolean,
  showExpert: boolean,
  limit = Number.POSITIVE_INFINITY,
): number {
  if (!visibleNode(node, showOptional, showExpert)) return 0;
  let size = 1;
  for (const child of propertyChildren(node)) {
    size += visibleSubtreeSize(
      child,
      showOptional,
      showExpert,
      limit - size,
    );
    if (size > limit) break;
  }
  return size;
}


function shouldAutoExpand(
  node: EditNode,
  showOptional: boolean,
  showExpert: boolean,
): boolean {
  const visibleChildren = propertyChildren(node).filter((child) =>
    visibleNode(child, showOptional, showExpert));
  if (visibleChildren.length === 0) return false;
  if (node.collapsed === true && !nodeTreeHasAuthoredValue(node)) return false;
  if (visibleChildren.length > AUTO_EXPAND_SUBTREE_LIMIT) return false;
  return !node.expert || visibleSubtreeSize(
      node,
      showOptional,
      showExpert,
      AUTO_EXPAND_SUBTREE_LIMIT,
    ) <= AUTO_EXPAND_SUBTREE_LIMIT;
}


function descendantNodeIds(node: EditNode): Set<string> {
  const result = new Set<string>();
  const visit = (current: EditNode) => {
    propertyChildren(current).forEach((child) => {
      result.add(child.id);
      visit(child);
    });
  };
  visit(node);
  return result;
}


function findNode(nodes: EditNode[], nodeId: string | null): EditNode | null {
  if (!nodeId) return null;
  const stack = [...nodes];
  while (stack.length > 0) {
    const node = stack.pop();
    if (!node) continue;
    if (node.id === nodeId) return node;
    stack.push(...nodeChildren(node));
  }
  return null;
}


function findParent(nodes: EditNode[], nodeId: string): EditNode | null {
  const stack = [...nodes];
  while (stack.length > 0) {
    const node = stack.pop();
    if (!node) continue;
    if (nodeChildren(node).some((child) => child.id === nodeId)) return node;
    stack.push(...nodeChildren(node));
  }
  return null;
}


function findClosestNode(
  nodes: EditNode[],
  nodeId: string | null,
): EditNode | null {
  let candidate = nodeId;
  while (candidate) {
    const node = findNode(nodes, candidate);
    if (node) return node;
    const separator = candidate.lastIndexOf(".");
    if (separator < 0) return null;
    candidate = candidate.slice(0, separator);
  }
  return null;
}


function editScope(nodes: EditNode[], nodeId: string | null): EditNode | null {
  const target = findClosestNode(nodes, nodeId);
  if (!target || nodeChildren(target).length > 0) return target;
  return findParent(nodes, target.id) ?? target;
}


function initialExpanded(
  nodes: EditNode[],
  showOptional: boolean,
  showExpert: boolean,
): Set<string> {
  const result = new Set<string>();
  const visit = (node: EditNode) => {
    if (!visibleNode(node, showOptional, showExpert)) return;
    if (!shouldAutoExpand(node, showOptional, showExpert)) return;
    result.add(node.id);
    propertyChildren(node).forEach(visit);
  };
  nodes.forEach(visit);
  return result;
}


function visibleExpandableIds(
  nodes: EditNode[],
  showOptional: boolean,
  showExpert: boolean,
): Set<string> {
  const result = new Set<string>();
  const visit = (node: EditNode) => {
    if (!visibleNode(node, showOptional, showExpert)) return;
    const children = propertyChildren(node);
    if (children.length > 0) result.add(node.id);
    children.forEach(visit);
  };
  nodes.forEach(visit);
  return result;
}


function hintRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}


function scalarString(value: unknown): string {
  if (
    typeof value === "string"
    || typeof value === "number"
    || typeof value === "boolean"
  ) {
    return String(value);
  }
  return "";
}


function hintOptions(node: EditNode): {
  label: string;
  value: unknown;
  description?: string;
  editTargetId?: string;
}[] {
  const options = hintRecord(node.inputHint).options;
  if (!Array.isArray(options)) return [];
  return options.flatMap((option) => {
    const value = hintRecord(option);
    return typeof value.label === "string" && "value" in value
      ? [{
        label: value.label,
        value: value.value,
        description: typeof value.description === "string"
          ? value.description
          : undefined,
        editTargetId: typeof value.editTargetId === "string"
          ? value.editTargetId
          : undefined,
      }]
      : [];
  });
}


function hintExamples(node: EditNode): string[] {
  const examples = hintRecord(node.inputHint).examples;
  return Array.isArray(examples) ? examples.map(String) : [];
}


function regex101Url(value: string, samples: string[]): string {
  const params = new URLSearchParams({
    flavor: "java8",
    regex: value,
  });
  if (samples.length > 0) params.set("testString", samples.join("\n"));
  return `https://regex101.com/?${params.toString()}`;
}

type CreatedReferenceFocus = "name" | "first-required";


function ScalarEditor({
  node,
  commit,
  busy,
  hasReferenceCreationAction,
  onLocalDirtyChange,
  showDocumentation,
}: Readonly<{
  node: EditNode;
  commit: (operation: EditOperation) => Promise<boolean>;
  busy: boolean;
  hasReferenceCreationAction: boolean;
  onLocalDirtyChange: (dirty: boolean) => void;
  showDocumentation: boolean;
}>) {
  const name = fieldName(node);
  const authoredValue = scalarString(node.value);
  const options = hintOptions(node);
  const examples = hintExamples(node);
  const hint = hintRecord(node.inputHint);
  const isReference = hint.kind === "reference";
  const allowCustom = hint.allowCustom === true;
  const referenceUnavailable = (
    isReference && !allowCustom && options.length === 0
  );
  const usesImplicitReferenceDefault = (
    referenceUnavailable
    && !hasReferenceCreationAction
    && typeof hint.emptyMeansDefault === "string"
  );
  const noReferenceChoices = (
    referenceUnavailable
    && !usesImplicitReferenceDefault
    && !hasReferenceCreationAction
  );
  const readOnly = hint.readOnly === true;
  const generatedDefault = (
    node.valueDefaulted && !node.valueAuthored ? authoredValue : ""
  );
  const [value, setValue] = useState(generatedDefault ? "" : authoredValue);
  const [applying, setApplying] = useState(false);
  const [focused, setFocused] = useState(false);
  const [validationProblem, setValidationProblem] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);
  const autoSelectedReference = useRef("");
  const pattern = hintRecord(node.validation).pattern;
  const patternMessage = hintRecord(node.validation).message;
  const selectedOption = options.find(
    (option) => String(option.value) === (value || generatedDefault),
  );
  useEffect(() => {
    if (
      !isReference
      || allowCustom
      || options.length !== 1
      || value
      || generatedDefault
      || busy
      || applying
    ) {
      return;
    }
    const onlyOption = options[0];
    const selectionKey = `${node.id}:${String(onlyOption.value)}`;
    if (autoSelectedReference.current === selectionKey) return;
    autoSelectedReference.current = selectionKey;
    setValue(String(onlyOption.value));
    setApplying(true);
    void commit({
      op: "set",
      path: node.path,
      value: onlyOption.value,
    }).then((applied) => {
      if (!applied) {
        autoSelectedReference.current = "";
        setValue("");
      }
    }).finally(() => setApplying(false));
  }, [
    allowCustom,
    applying,
    busy,
    commit,
    generatedDefault,
    isReference,
    node.id,
    node.path,
    options,
    value,
  ]);

  if (referenceUnavailable && !value && hasReferenceCreationAction) {
    return null;
  }

  if (options.length > 0 && !allowCustom) {
    return (
      <div className="inline-choice-editor">
        <label>
          <span className="sr-only">{name}</span>
          <select
            aria-label={name}
            className={generatedDefault && !value ? "default-hint-input" : ""}
            disabled={busy || applying}
            onBlur={() => setFocused(false)}
            onChange={(event) => {
              const nextValue = event.target.value;
              const selected = options.find(
                (option) => String(option.value) === nextValue,
              );
              setValue(nextValue);
              if (!selected) return;
              setApplying(true);
              void commit({
                op: "set",
                path: node.path,
                value: selected.value,
              }).finally(() => setApplying(false));
            }}
            onFocus={() => setFocused(true)}
            value={value}
          >
            {value ? null : (
              <option disabled value="">
                {generatedDefault && !focused
                  ? `Default: ${authoredValue}`
                  : "<Select existing...>"}
              </option>
            )}
            {options.map((option) => (
              <option key={String(option.value)} value={String(option.value)}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
        {applying ? <LoaderCircle className="spin inline-spinner" /> : null}
        {showDocumentation && selectedOption?.description
          ? <span className="inline-field-help">{selectedOption.description}</span>
          : null}
      </div>
    );
  }

  const syncValue = async () => {
    if (applying || referenceUnavailable || readOnly) return false;
    if (value === authoredValue || (generatedDefault && value === "")) {
      onLocalDirtyChange(false);
      return true;
    }
    if (inputRef.current && !inputRef.current.checkValidity()) {
      inputRef.current.reportValidity();
      return false;
    }
    if (node.valueType === "number" && value === "") return false;
    const operationValue = node.valueType === "number"
      ? Number(value)
      : value;
    setApplying(true);
    const applied = await commit({
      op: "set",
      path: node.path,
      value: operationValue,
    });
    setApplying(false);
    if (applied) onLocalDirtyChange(false);
    return applied;
  };

  return (
    <div className="inline-text-editor">
      <label>
        <span className="sr-only">{name}</span>
        <input
          aria-label={name}
          aria-busy={applying}
          className={generatedDefault && !value ? "default-hint-input" : ""}
          disabled={referenceUnavailable || readOnly || applying}
          list={options.length > 0 || examples.length > 0
            ? `${node.id}-choices`
            : undefined}
          onBlur={() => {
            setFocused(false);
            void syncValue();
          }}
          onChange={(event) => {
            const nextValue = event.target.value;
            setValue(nextValue);
            setValidationProblem(fieldValidationProblem(
              nextValue,
              typeof pattern === "string" ? pattern : undefined,
              typeof patternMessage === "string"
                ? patternMessage
                : undefined,
            ));
            onLocalDirtyChange(
              generatedDefault ? nextValue !== "" : nextValue !== authoredValue,
            );
          }}
          onFocus={() => setFocused(true)}
          onKeyDown={(event) => {
            if (event.key !== "Enter") return;
            event.preventDefault();
            void syncValue();
          }}
          pattern={typeof pattern === "string" ? pattern : undefined}
          min={node.validation?.minimum}
          max={node.validation?.maximum}
          step={node.validation?.integer ? 1 : undefined}
          placeholder={!focused && generatedDefault
            ? generatedDefault
            : undefined}
          ref={inputRef}
          required={node.required === true && !generatedDefault}
          type={node.valueType === "number" ? "number" : "text"}
          value={value}
        />
        {options.length > 0 || examples.length > 0 ? (
          <datalist id={`${node.id}-choices`}>
            {options.map((option) => (
              <option key={String(option.value)} value={String(option.value)}>
                {option.label}
              </option>
            ))}
            {examples.map((example) => (
              <option key={example} value={example} />
            ))}
          </datalist>
        ) : null}
      </label>
      {applying ? <LoaderCircle className="spin inline-spinner" /> : null}
      {validationProblem ? (
        <p className="field-error inline-validation-help" role="alert">
          {validationProblem}
        </p>
      ) : null}
      {showDocumentation && selectedOption?.description
        ? <p className="field-help">{selectedOption.description}</p>
        : null}
      {usesImplicitReferenceDefault ? (
        <p className="field-help">
          {typeof hint.message === "string"
            ? hint.message
            : `A ${String(hint.emptyMeansDefault)} configuration will be provided.`}
        </p>
      ) : noReferenceChoices ? (
        <p className="field-error">
          {typeof hint.message === "string"
            ? hint.message
            : "No configured values are available for this reference."}
        </p>
      ) : typeof hint.emptyMeansDefault === "string" ? (
        <p className="field-help">
          Leaving this empty uses {hint.emptyMeansDefault}.
        </p>
      ) : null}
      {hint.kind === "javaRegex" ? (
        <div className="regex-help">
          {typeof hint.message === "string" ? <p>{hint.message}</p> : null}
          <a
            href={regex101Url(value, Array.isArray(hint.testStrings)
              ? hint.testStrings.map(String)
              : [])}
            rel="noreferrer"
            target="_blank"
          >
            Test this Java regular expression
          </a>
        </div>
      ) : null}
      {busy && !applying
        ? <span className="sr-only">Another draft update is processing</span>
        : null}
    </div>
  );
}


function UnionEditor({
  node,
  commit,
  busy,
  onRevealChildren,
  showDocumentation,
}: Readonly<{
  node: EditNode;
  commit: (operation: EditOperation) => Promise<boolean>;
  busy: boolean;
  onRevealChildren: () => void;
  showDocumentation: boolean;
}>) {
  const name = fieldName(node);
  const variants = node.variants ?? [];
  const [value, setValue] = useState(scalarString(node.value));
  const [applying, setApplying] = useState(false);
  const selected = variants.find(
    (variant) => String(variant.value) === value,
  );
  return (
    <div className="inline-choice-editor">
      <label>
        <span className="sr-only">{name}</span>
        <select
          aria-label={name}
          disabled={busy || applying}
          onChange={(event) => {
            const nextValue = event.target.value;
            const previousValue = value;
            const next = variants.find(
              (variant) => String(variant.value) === nextValue,
            );
            setValue(nextValue);
            if (!next) return;
            onRevealChildren();
            setApplying(true);
            void commit({
              op: "set",
              path: node.path,
              value: next.value,
            }).then((applied) => {
              if (!applied) setValue(previousValue);
            }).finally(() => setApplying(false));
          }}
          value={value}
        >
          {variants.map((variant) => (
            <option key={String(variant.value)} value={String(variant.value)}>
              {variant.label}
            </option>
          ))}
        </select>
      </label>
      {applying ? <LoaderCircle className="spin inline-spinner" /> : null}
      {showDocumentation && selected?.description
        ? <span className="inline-field-help">{selected.description}</span>
        : null}
    </div>
  );
}


function BooleanEditor({
  node,
  commit,
  busy,
}: Readonly<{
  node: EditNode;
  commit: (operation: EditOperation) => Promise<boolean>;
  busy: boolean;
}>) {
  const [checked, setChecked] = useState(node.value === true);
  const [applying, setApplying] = useState(false);
  return (
    <div className="inline-boolean-editor">
      <label>
        <input
          aria-label={fieldName(node)}
          checked={checked}
          disabled={busy || applying}
          onChange={(event) => {
            const next = event.target.checked;
            setChecked(next);
            setApplying(true);
            void commit({
              op: "set",
              path: node.path,
              value: next,
            }).then((applied) => {
              if (!applied) setChecked(!next);
            }).finally(() => setApplying(false));
          }}
          type="checkbox"
        />
        <span>{checked ? "Enabled" : "Disabled"}</span>
      </label>
      {applying ? <LoaderCircle className="spin inline-spinner" /> : null}
    </div>
  );
}


function CommandEditor({
  node,
  parent,
  commit,
  busy,
  execute,
  onAdded,
  onCancel,
  onComplete,
}: Readonly<{
  node: EditNode;
  parent: EditNode | null;
  commit: (operation: EditOperation) => Promise<boolean>;
  busy: boolean;
  execute?: (name: string) => Promise<boolean>;
  onAdded: (nodeId: string, parentId: string | null) => void;
  onCancel: () => void;
  onComplete: () => void;
}>) {
  const requiresName = node.command?.requiresName !== false;
  const label = fieldName(node);
  const [name, setName] = useState("");
  const [validationProblem, setValidationProblem] = useState("");
  const pattern = hintRecord(node.validation).pattern
    ?? hintRecord(node.inputHint).pattern;
  const patternMessage = hintRecord(node.validation).message
    ?? hintRecord(node.inputHint).message;
  const resourceType = resourceAddPlacement(parent ?? node)
    ?.resourceType.toLocaleLowerCase() ?? "";
  const clusterObjectHelp = resourceType.includes("repository")
    ? (
        "Submitting this configuration can create this repository on the "
        + "source Elasticsearch/OpenSearch cluster."
      )
    : resourceType.includes("snapshot")
      ? (
          "Submitting this configuration can create this snapshot on the "
          + "source Elasticsearch/OpenSearch cluster."
        )
      : "";
  const formRef = useEscapeCancel<HTMLFormElement>(onCancel, busy);
  return (
    <form
      className="field-form inline-command-form"
      data-escape-cancel-layer
      onSubmit={(event) => {
        event.preventDefault();
        const trimmedName = name.trim();
        const currentProblem = fieldValidationProblem(
          trimmedName,
          typeof pattern === "string" ? pattern : undefined,
          typeof patternMessage === "string" ? patternMessage : undefined,
        );
        if (currentProblem) {
          setValidationProblem(currentProblem);
          return;
        }
        const operation = execute
          ? execute(trimmedName)
          : runAddCommand(
            node,
            parent,
            trimmedName,
            commit,
            onAdded,
          );
        void operation.then((applied) => {
          if (applied) onComplete();
        });
      }}
      ref={formRef}
    >
      {requiresName ? (
        <label>
          <span className="sr-only">{label} name</span>
          <input
            aria-label={`${label} name`}
            autoFocus
            onChange={(event) => {
              const nextName = event.target.value;
              setName(nextName);
              setValidationProblem(fieldValidationProblem(
                nextName,
                typeof pattern === "string" ? pattern : undefined,
                typeof patternMessage === "string"
                  ? patternMessage
                  : undefined,
              ));
            }}
            pattern={typeof pattern === "string" ? pattern : undefined}
            required
            type="text"
            value={name}
          />
        </label>
      ) : null}
      <button
        aria-label={`Create ${label}`}
        disabled={
          busy
          || Boolean(node.command?.blockedMessage)
          || (requiresName && !name.trim())
          || Boolean(validationProblem)
        }
        title={`Create ${label}`}
        type="submit"
      >
        <Check aria-hidden="true" />
      </button>
      <button
        aria-label={`Cancel creating ${label}`}
        disabled={busy}
        onClick={onCancel}
        title="Cancel"
        type="button"
      >
        <X aria-hidden="true" />
      </button>
      {requiresName ? (
        <p className="field-help naming-help">
          This name is an alias used by references and status views.
        </p>
      ) : null}
      {requiresName && clusterObjectHelp ? (
        <p className="field-help cluster-object-name-help">
          {clusterObjectHelp}
        </p>
      ) : null}
      {validationProblem ? (
        <p className="field-error inline-validation-help" role="alert">
          {validationProblem}
        </p>
      ) : null}
      {node.command?.blockedMessage
        ? <p className="field-help">{node.command.blockedMessage}</p>
        : null}
    </form>
  );
}


function runAddCommand(
  node: EditNode,
  parent: EditNode | null,
  name: string,
  commit: (operation: EditOperation) => Promise<boolean>,
  onAdded: (nodeId: string, parentId: string | null) => void,
): Promise<boolean> {
  const requiresName = node.command?.requiresName !== false;
  if (requiresName && !name) return Promise.resolve(false);
  const nextIndex = nextCollectionIndex(parent ?? node);
  const addedKey = parent?.valueKind === "array"
    ? String(nextIndex)
    : requiresName ? name : String(nextIndex);
  const addedPath = [
    ...node.path,
    addedKey,
  ];
  return commit({
    op: "add",
    path: node.path,
    value: requiresName ? { name } : {},
  }).then((applied) => {
    if (applied && node.command?.autoEditAdded !== false) {
      onAdded(`edit:${addedPath.join(".")}`, parent?.id ?? null);
    }
    return applied;
  });
}

function nextCollectionIndex(node: EditNode): number {
  const children = propertyChildren(node);
  return node.valueKind === "array"
    ? children.filter((child) => child.valueKind !== "command").length
    : children.length;
}


function StructuredEditor({
  node,
  commit,
  busy,
}: Readonly<{
  node: EditNode;
  commit: (operation: EditOperation) => Promise<boolean>;
  busy: boolean;
}>) {
  const [value, setValue] = useState(
    JSON.stringify(node.value ?? (node.valueKind === "array" ? [] : {}), null, 2),
  );
  const [parseError, setParseError] = useState("");
  return (
    <form
      className="field-form"
      onSubmit={(event) => {
        event.preventDefault();
        try {
          const parsed = JSON.parse(value) as unknown;
          setParseError("");
          void commit({ op: "set", path: node.path, value: parsed });
        } catch (error) {
          setParseError(error instanceof Error ? error.message : String(error));
        }
      }}
    >
      <label>
        <span>{fieldName(node)} JSON</span>
        <textarea
          aria-label={`${fieldName(node)} JSON`}
          onChange={(event) => setValue(event.target.value)}
          rows={9}
          value={value}
        />
      </label>
      {parseError ? <p className="field-error">{parseError}</p> : null}
      <button disabled={busy} type="submit">Apply structure</button>
    </form>
  );
}


function ConfigPropertyRow({
  draft,
  node,
  parent,
  depth,
  expanded,
  selected,
  inserted,
  removing,
  showDocumentation,
  busy,
  commit,
  applyExternalOperations,
  reportError,
  onLocalDirtyChange,
  onRequestRemoval,
  onSelectAdded,
  onSelect,
  onNavigateEditTarget,
  onReferenceCreated,
  referenceAdds,
  onRevealChildren,
  onToggle,
  rowRef,
  contextProgress,
}: Readonly<{
  draft: BrowserConfigDraft;
  node: EditNode;
  parent: EditNode | null;
  depth: number;
  expanded: boolean;
  selected: boolean;
  inserted: boolean;
  removing: boolean;
  showDocumentation: boolean;
  busy: boolean;
  commit: (operation: EditOperation) => Promise<boolean>;
  applyExternalOperations: ApplyExternalOperations;
  reportError: (message: string) => void;
  onLocalDirtyChange: (nodeId: string, dirty: boolean) => void;
  onRequestRemoval: (node: EditNode) => void;
  onSelectAdded: (nodeId: string, parentId: string | null) => void;
  onSelect: () => void;
  onNavigateEditTarget: (targetId: string) => void;
  onReferenceCreated: (
    targetId: string,
    focus: CreatedReferenceFocus,
  ) => void;
  referenceAdds: ReferenceAddContext[];
  onRevealChildren: () => void;
  onToggle: () => void;
  rowRef: (element: HTMLTableRowElement | null) => void;
  contextProgress: number;
}>) {
  const [renaming, setRenaming] = useState(false);
  const [addingCommandId, setAddingCommandId] = useState<string | null>(null);
  const [referenceApplying, setReferenceApplying] = useState(false);
  const [externalEditorOpen, setExternalEditorOpen] = useState(false);
  const externalEditorTriggerRef = useRef<HTMLButtonElement>(null);
  const [newName, setNewName] = useState(node.path.at(-1) ?? "");
  const renameFormRef = useEscapeCancel<HTMLFormElement>(
    () => setRenaming(false),
    !renaming,
  );
  const children = propertyChildren(node);
  const commands = addCommands(node);
  const topLevelResourceCommand = resourceAddPlacement(node)
    ? commands[0] ?? null
    : null;
  const inlineCommands = topLevelResourceCommand ? [] : commands;
  const addingCommand = commands.find(
    (candidate) => candidate.id === addingCommandId,
  ) ?? null;
  const canRename = renameableConfigPath(node.path) && node.implicit !== true;
  const renamePattern = (
    node.path.length === 3 && node.path[0] === "traffic"
      ? KUBERNETES_NAME_PATTERN
      : typeof hintRecord(parent?.inputHint).keyPattern === "string"
        ? String(hintRecord(parent?.inputHint).keyPattern)
        : undefined
  );
  const renameMessage = (
    node.path.length === 3 && node.path[0] === "traffic"
      ? KUBERNETES_NAME_MESSAGE
      : typeof hintRecord(parent?.inputHint).message === "string"
        ? String(hintRecord(parent?.inputHint).message)
        : undefined
  );
  const renameValidationProblem = fieldValidationProblem(
    newName,
    renamePattern,
    renameMessage,
  );
  // Only authored values offer the clear action; its presence documents
  // that the field is holding an explicit value over a default.
  const canClear = (
    node.valueAuthored === true
    && node.presence === "optional"
    && node.required !== true
    && !node.removable
    && node.valueKind !== "command"
  );
  const structured = (
    !node.externalRef
    && commands.length === 0
    && children.length === 0
    && !["scalar", "boolean", "union", "command"].includes(node.valueKind)
  );
  const showDetails = Boolean(addingCommand) || (selected && structured);
  const name = fieldName(node, parent);
  const errorEmphasis = validationErrorEmphasis(node);
  const changeTitle = draftChangeTitle(node);
  const effectiveDefaultLabel = typeof node.effectiveDefault?.label === "string"
    ? node.effectiveDefault.label
    : "";
  const effectiveDefaultDescription = (
    typeof node.effectiveDefault?.description === "string"
      ? node.effectiveDefault.description
      : ""
  );
  const selectedDescription = (
    node.valueKind === "union"
      ? node.variants?.find(
        (variant) => String(variant.value) === scalarString(node.value),
      )?.description
      : hintOptions(node).find(
        (option) => String(option.value) === scalarString(node.value),
      )?.description
  );
  const selectedReference = hintOptions(node).find(
    (option) => String(option.value) === scalarString(node.value),
  );
  const referenceOptions = hintOptions(node);
  const createReference = node.inputHint?.kind === "reference"
    ? node.inputHint.createReference
    : undefined;
  const createReferenceSourcePath = (
    node.inputHint?.kind === "reference"
    && Array.isArray(node.inputHint.sourcePath)
  )
    ? node.inputHint.sourcePath
    : null;
  const createReferenceValue = createReference?.value
    ?? (
      typeof createReference?.valueFromPathSegmentFromEnd === "number"
        ? node.path.at(-createReference.valueFromPathSegmentFromEnd)
        : undefined
    );
  const canCreateExplicitReference = Boolean(
    createReference
    && createReferenceSourcePath
    && createReferenceValue
    && !referenceOptions.some(
      (option) => String(option.value) === String(createReferenceValue),
    ),
  );
  const hasReferenceCreationAction = (
    canCreateExplicitReference || referenceAdds.length > 0
  );
  const visibleReferenceAdds = canCreateExplicitReference
    ? []
    : referenceAdds;
  const referenceTargetId = node.referenceTargetId
    ?? selectedReference?.editTargetId;
  const referenceLabel = node.referenceLabel
    ?? selectedReference?.label
    ?? node.path.at(-1)
    ?? "definition";
  const fieldDescription = node.description ?? selectedDescription;
  const closeExternalEditor = () => {
    setExternalEditorOpen(false);
    globalThis.setTimeout(() => externalEditorTriggerRef.current?.focus(), 0);
  };
  const createAndSelectExplicitReference = async () => {
    if (
      !createReference
      || !createReferenceSourcePath
      || !createReferenceValue
      || referenceApplying
      || busy
    ) {
      return false;
    }
    setReferenceApplying(true);
    try {
      const applied = await applyExternalOperations([
        {
          op: "add",
          path: createReferenceSourcePath,
          value: { name: createReferenceValue },
        },
        {
          op: "set",
          path: node.path,
          value: createReferenceValue,
        },
      ], `${createReference.label}.`);
      if (applied && createReference.navigateToCreated !== false) {
        const focus = createReference.focusName
          ?? createReference.value === undefined;
        onReferenceCreated(
          `edit:${[
            ...createReferenceSourcePath,
            String(createReferenceValue),
          ].join(".")}`,
          focus ? "name" : "first-required",
        );
      }
      return applied;
    } finally {
      setReferenceApplying(false);
    }
  };
  const createAndSelectReference = async (
    context: ReferenceAddContext,
  ) => {
    if (referenceApplying || busy) return false;
    const name = provisionalReferenceName(node, context);
    setReferenceApplying(true);
    try {
      const applied = await applyExternalOperations([
        {
          op: "add",
          path: context.sourcePath,
          value: { name },
        },
        {
          op: "set",
          path: node.path,
          value: name,
        },
      ], `Created and selected ${name}.`);
      if (applied) {
        onReferenceCreated(
          `edit:${[...context.sourcePath, name].join(".")}`,
          "name",
        );
      }
      return applied;
    } finally {
      setReferenceApplying(false);
    }
  };

  const valueEditor = node.externalRef ? (
    <button
      className="inline-resource-button"
      disabled={busy}
      onClick={() => {
        onSelect();
        setExternalEditorOpen(true);
      }}
      ref={externalEditorTriggerRef}
      type="button"
    >
      <span>{scalarString(node.value) || "Not selected"}</span>
      <Pencil aria-hidden="true" />
      Configure
    </button>
  ) : node.valueKind === "scalar" ? (
    <ScalarEditor
      busy={busy}
      commit={commit}
      hasReferenceCreationAction={hasReferenceCreationAction}
      node={node}
      onLocalDirtyChange={(dirty) => onLocalDirtyChange(node.id, dirty)}
      showDocumentation={showDocumentation}
    />
  ) : node.valueKind === "boolean" ? (
    <BooleanEditor busy={busy} commit={commit} node={node} />
  ) : node.valueKind === "union" ? (
    <UnionEditor
      busy={busy}
      commit={commit}
      node={node}
      onRevealChildren={onRevealChildren}
      showDocumentation={showDocumentation}
    />
  ) : structured ? (
    <button
      className="secondary-button"
      onClick={onSelect}
      type="button"
    >
      <Pencil aria-hidden="true" />
      Edit structure
    </button>
  ) : (
    <span className="property-summary">
      {children.length} {children.length === 1 ? "setting" : "settings"}
    </span>
  );

  return (
    <>
      <tr
        aria-selected={selected}
        className={[
          "config-property-row",
          `status-${node.status ?? "ok"}`,
          errorEmphasis ? "validation-error-" + errorEmphasis : "",
          node.draftChange ? "draft-change-item" : "",
          !node.draftChange && node.draftChangeCount
            ? "draft-change-ancestor"
            : "",
          selected ? "selected" : "",
          inserted ? "inserted" : "",
          removing ? "removing" : "",
          contextProgress > 0 ? "context-transition" : "",
        ].join(" ")}
        onClick={(event) => {
          const targetElement = event.target as HTMLElement;
          if (!targetElement.closest("button, input, select, textarea, a")) {
            onSelect();
          }
        }}
        ref={rowRef}
        style={{
          "--context-pin-progress": contextProgress,
          "--context-content-opacity": 1 - contextProgress,
          "--context-detail-opacity": 1 - contextProgress * 0.65,
        } as React.CSSProperties}
        tabIndex={selected ? 0 : -1}
      >
        <th scope="row">
          <div
            className="property-heading"
            style={{ "--config-depth": depth } as React.CSSProperties}
          >
            {children.length > 0 ? (
              <button
                aria-expanded={expanded}
                aria-label={`${expanded ? "Collapse" : "Expand"} ${name}`}
                onClick={onToggle}
                type="button"
              >
                {expanded ? <ChevronDown /> : <ChevronRight />}
              </button>
            ) : <span className="config-tree-spacer" />}
            <div className="property-heading-content">
              <span
                className="property-label"
                title={[
                  changeTitle,
                  !showDocumentation ? fieldDescription : "",
                ].filter(Boolean).join(" ") || undefined}
              >
                <strong>{name}</strong>
                <span className="property-flags">
                  {node.draftChange ? (
                    <span
                      title={[
                        "Cyan highlighting marks an unsaved browser draft change.",
                        changeTitle,
                      ].filter(Boolean).join(" ")}
                    >
                      {node.draftChange.kind === "added"
                        ? "Unsaved addition"
                        : "Unsaved change"}
                    </span>
                  ) : null}
                  {node.presence === "required"
                    ? <span>{node.presence}</span>
                    : null}
                  {node.expert ? <span>Expert</span> : null}
                </span>
              </span>
              {showDocumentation && node.description
                ? <small>{node.description}</small>
                : null}
            </div>
          </div>
          {(node.diagnostics ?? []).map((diagnostic, index) => (
            <div
              className={`property-diagnostic diagnostic-${diagnostic.severity}`}
              key={`${diagnostic.message}-${index}`}
              style={{ "--config-depth": depth } as React.CSSProperties}
            >
              <AlertTriangle aria-hidden="true" />
              <span>{diagnostic.message}</span>
            </div>
          ))}
        </th>
        <td>
          <div
            className="property-value"
            key={`${node.id}-${draft.draftRevision}`}
          >
            {valueEditor}
            {referenceTargetId
              || canCreateExplicitReference
              || visibleReferenceAdds.length > 0 ? (
              <div className="inline-reference-actions">
                {referenceTargetId
                  && node.id !== referenceTargetId
                  && !node.id.startsWith(`${referenceTargetId}.`) ? (
                  <button
                    className="inline-reference-link"
                    onClick={() => onNavigateEditTarget(referenceTargetId)}
                    title={`Open the definition referenced by ${name}`}
                    type="button"
                  >
                    <Link2 aria-hidden="true" />
                    Defined in {referenceLabel}
                  </button>
                ) : null}
                {canCreateExplicitReference && createReference ? (
                  <button
                    className="inline-reference-create"
                    disabled={busy || referenceApplying}
                    onClick={() => void createAndSelectExplicitReference()}
                    title={createReference.description}
                    type="button"
                  >
                    {referenceApplying
                      ? <LoaderCircle className="spin inline-spinner" />
                      : <Plus aria-hidden="true" />}
                    {createReference.label}
                  </button>
                ) : null}
                {visibleReferenceAdds.map((context) => (
                  <button
                    className="inline-reference-create"
                    disabled={
                      busy
                      || referenceApplying
                      || context.option.disabled
                    }
                    key={context.option.id}
                    onClick={() => void createAndSelectReference(context)}
                    title={
                      context.option.disabledReason
                      ?? `Create new ${context.option.placement.resourceType}`
                    }
                    type="button"
                  >
                    <Plus aria-hidden="true" />
                    Create new {context.option.placement.resourceType}
                  </button>
                ))}
              </div>
            ) : null}
            {inlineCommands.length > 0 ? (
              <div className="inline-add-actions">
                {inlineCommands.map((command) => {
                  const commandName = fieldName(command);
                  return (
                    <button
                      aria-label={`Add ${commandName}`}
                      disabled={
                        busy || Boolean(command.command?.blockedMessage)
                      }
                      key={command.id}
                      onClick={() => {
                        onSelect();
                        if (command.command?.requiresName !== false) {
                          setAddingCommandId(command.id);
                        } else {
                          void runAddCommand(
                            command,
                            node,
                            "",
                            commit,
                            onSelectAdded,
                          );
                        }
                      }}
                      title={command.command?.blockedMessage
                        ?? `Add ${commandName}`}
                      type="button"
                    >
                      <Plus aria-hidden="true" />
                      Add {commandName}
                    </button>
                  );
                })}
              </div>
            ) : null}
            {showDocumentation && node.effectiveDefault ? (
              <div className="inline-effective-default">
                <strong>
                  {effectiveDefaultLabel || "Effective default"}
                </strong>
                {effectiveDefaultDescription
                  ? <span>{effectiveDefaultDescription}</span>
                  : null}
              </div>
            ) : null}
          </div>
        </td>
        <td className="property-action-cell">
          <div className="property-action-content">
                  {node.status
                    && !["ok", "required"].includes(node.status) ? (
                    <span className={`field-status status-${node.status}`}>
                      {node.status}
                    </span>
            ) : null}
            <div className="property-actions">
            {topLevelResourceCommand ? (
              <button
                aria-label={`Add ${fieldName(topLevelResourceCommand)}`}
                disabled={
                  busy
                  || Boolean(topLevelResourceCommand.command?.blockedMessage)
                }
                onClick={() => {
                  onSelect();
                  if (
                    topLevelResourceCommand.command?.requiresName !== false
                  ) {
                    setAddingCommandId(topLevelResourceCommand.id);
                  } else {
                    void runAddCommand(
                      topLevelResourceCommand,
                      node,
                      "",
                      commit,
                      onSelectAdded,
                    );
                  }
                }}
                title={topLevelResourceCommand.command?.blockedMessage
                  ?? `Add ${fieldName(topLevelResourceCommand)}`}
                type="button"
              >
                <Plus aria-hidden="true" />
              </button>
            ) : null}
            {canRename ? (
              <button
                aria-label={`Rename ${node.path.at(-1)}`}
                disabled={busy}
                onClick={() => {
                  onSelect();
                  setRenaming(true);
                }}
                title={`Rename ${node.path.at(-1)}`}
                type="button"
              >
                <Pencil aria-hidden="true" />
              </button>
            ) : null}
            {canClear ? (
              <button
                aria-label={`Clear ${name} and use the default`}
                disabled={busy}
                onClick={() => void commit({ op: "unset", path: node.path })}
                title="Clear this value and use the default"
                type="button"
              >
                <X aria-hidden="true" />
              </button>
            ) : null}
            {node.removable ? (
              <button
                aria-label={`Remove ${name}`}
                className="danger-button"
                disabled={busy}
                onClick={() => onRequestRemoval(node)}
                title={`Remove ${name}`}
                type="button"
              >
                <Trash2 aria-hidden="true" />
              </button>
            ) : null}
            </div>
          </div>
        </td>
      </tr>
      {renaming || showDetails ? (
        <tr className={[
          "config-property-detail",
          removing ? "removing" : "",
        ].join(" ")}>
          <td colSpan={3}>
            <div
              className="property-detail-content"
              style={{ "--config-depth": depth } as React.CSSProperties}
            >
              <span className="property-path">
                {node.path.join(".") || "configuration"}
              </span>
              {renaming ? (
                <form
                  className="rename-form"
                  data-escape-cancel-layer
                  onSubmit={(event: FormEvent) => {
                    event.preventDefault();
                    void commit({
                      op: "renameConfig",
                      path: node.path,
                      newName: newName.trim(),
                    }).then((applied) => {
                      if (applied) setRenaming(false);
                    });
                  }}
                  ref={renameFormRef}
                >
                  <label>
                    <span>Configuration name</span>
                    <input
                      aria-label="Configuration name"
                      autoFocus
                      onChange={(event) => setNewName(event.target.value)}
                      pattern={renamePattern}
                      required
                      title={
                        node.path.length === 3 && node.path[0] === "traffic"
                          ? `${KUBERNETES_NAME_MESSAGE} Dependent workflow references will be updated.`
                          : "Dependent workflow references will be updated."
                      }
                      value={newName}
                    />
                  </label>
                  <button
                    disabled={
                      busy || !newName.trim() || Boolean(renameValidationProblem)
                    }
                    type="submit"
                  >
                    Apply rename
                  </button>
                  <button onClick={() => setRenaming(false)} type="button">
                    Cancel
                  </button>
                  {renameValidationProblem ? (
                    <p className="field-error" role="alert">
                      {renameValidationProblem}
                    </p>
                  ) : null}
                </form>
              ) : addingCommand ? (
                <CommandEditor
                  busy={busy}
                  commit={commit}
                  node={addingCommand}
                  onAdded={onSelectAdded}
                  onCancel={() => setAddingCommandId(null)}
                  onComplete={() => setAddingCommandId(null)}
                  parent={node}
                />
              ) : structured ? (
                <StructuredEditor busy={busy} commit={commit} node={node} />
              ) : null}
            </div>
          </td>
        </tr>
      ) : null}
      {node.externalRef && externalEditorOpen ? (
        <ExternalResourceEditor
          applyOperations={applyExternalOperations}
          busy={busy}
          draft={draft}
          node={node}
          onClose={closeExternalEditor}
          reportError={reportError}
        />
      ) : null}
    </>
  );
}


export function ConfigEditor({
  initialRemovalTargetId,
  initialTargetId,
  navigationBackLabel,
  onClose,
  onExitReady,
  onSubmitReady,
  onNavigateBack,
  onDraftReverted,
  onResourceAddStarted,
  onResourceAddSettled,
  onResourceRenameStarted,
  onResourceRenameSettled,
  onResourceAddsReady,
  onNavigateEditTarget,
  onNavigateCreatedEditTarget,
  onInitialRemovalHandled,
  onConnectivityStatesChange,
  onSubmitted,
  removalState,
  resourceId,
  resourceLabel,
  resourceType,
  resourceSyncing = false,
  stateSummary = null,
  navigationSnapshot = null,
}: Readonly<ConfigEditorProps>) {
  const queryClient = useQueryClient();
  const draftQuery = useQuery({
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
    staleTime: Infinity,
  });
  const [selectedId, setSelectedId] = useState<string | null>(
    initialTargetId ?? null,
  );
  const [connectivityDialogOpen, setConnectivityDialogOpen] = useState(false);
  const [activeTargetId, setActiveTargetId] = useState<string | null>(
    initialTargetId ?? null,
  );
  const initialDisplayPreferences = readEditorDisplayPreferences(resourceType);
  const [showOptional, setShowOptional] = useState(
    initialDisplayPreferences.showOptional,
  );
  const [showExpert, setShowExpert] = useState(
    initialDisplayPreferences.showExpert,
  );
  const [showDocumentation, setShowDocumentation] = useState(
    initialDisplayPreferences.showDocumentation,
  );
  const [renderOptional, setRenderOptional] = useState(
    initialDisplayPreferences.showOptional,
  );
  const [renderExpert, setRenderExpert] = useState(
    initialDisplayPreferences.showExpert,
  );
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [insertedIds, setInsertedIds] = useState<Set<string>>(() => new Set());
  const [removingIds, setRemovingIds] = useState<Set<string>>(
    () => new Set(),
  );
  const [collapsingIds, setCollapsingIds] = useState<Set<string>>(
    () => new Set(),
  );
  const [locallyEditedIds, setLocallyEditedIds] = useState<Set<string>>(
    () => new Set(),
  );
  const [rawYamlText, setRawYamlText] = useState("");
  const [rawYamlDirty, setRawYamlDirty] = useState(false);
  const busy = false;
  const [actionPending, setActionPending] = useState(false);
  const [problem, setProblem] = useState("");
  const [notice, setNotice] = useState("");
  const [titleRenaming, setTitleRenaming] = useState(false);
  const [titleRenameName, setTitleRenameName] = useState("");
  const [pendingCreatedTarget, setPendingCreatedTarget] = useState<{
    targetId: string;
    focus: CreatedReferenceFocus;
    returnTargetId: string;
    returnLabel: string;
  } | null>(null);
  const [pendingRemoval, setPendingRemoval] =
    useState<PendingRemoval | null>(null);
  const [confirmSubmit, setConfirmSubmit] = useState(false);
  const [submitPersistedRevision, setSubmitPersistedRevision] =
    useState<string | null>(null);
  const [exitPromptOpen, setExitPromptOpen] = useState(false);
  const [pinnedContext, setPinnedContext] = useState<PinnedContext[]>([]);
  const configTablePanelRef = useRef<HTMLElement>(null);
  const pinUpdateFrame = useRef<number | null>(null);
  const rowElements = useRef(new Map<string, HTMLTableRowElement>());
  const flipTops = useRef(new Map<string, number>());
  const flipScope = useRef<string | null>(null);
  const activeFlipCount = useRef(0);
  const knownRowIds = useRef<Set<string> | null>(null);
  const knownExpansionIds = useRef<Set<string> | null>(null);
  const expansionScope = useRef<string | null>(null);
  const manuallyCollapsedIds = useRef(new Set<string>());
  const skipNextRowTracking = useRef(false);
  const insertedTimer = useRef<number | null>(null);
  const transitionTimers = useRef(new Set<number>());
  const removingClaims = useRef(new Map<string, number>());
  const collapseTransitions = useRef(new Map<
    string,
    { timer: number; rowIds: Set<string> }
  >());
  const optionalTransition = useRef<{
    timer: number;
    rowIds: Set<string>;
  } | null>(null);
  const expertTransition = useRef<{
    timer: number;
    rowIds: Set<string>;
  } | null>(null);
  const pendingScrollTop = useRef<number | null>(null);
  const pendingCommit = useRef<Promise<boolean> | null>(null);
  const pendingRowAnchor = useRef<{
    nodeId: string;
    top: number;
    draftRevision: string;
  } | null>(null);
  const resourceAddRequest = useRef<ResourceAddController["add"]>(
    () => Promise.resolve(false),
  );
  const resourceRenameRequest = useRef<ResourceAddController["rename"]>(
    () => Promise.resolve(false),
  );
  const autoRenameTarget = useRef<string | null>(null);
  const autoFocusFieldTarget = useRef<string | null>(null);
  const handledInitialRemovalTarget = useRef<string | null>(null);

  const draft = draftQuery.data;
  const editorBusy = busy || resourceSyncing;
  const draftBaseStale = Boolean(draft?.baseStale);
  const environmentDiagnostics = useEnvironmentDiagnostics(
    draft,
  );

  useEffect(() => {
    const preferences = readEditorDisplayPreferences(resourceType);
    setShowOptional(preferences.showOptional);
    setRenderOptional(preferences.showOptional);
    setShowExpert(preferences.showExpert);
    setRenderExpert(preferences.showExpert);
    setShowDocumentation(preferences.showDocumentation);
  }, [resourceType]);

  const storeDisplayPreferences = (
    next: Partial<ReturnType<typeof readEditorDisplayPreferences>>,
  ) => {
    writeEditorDisplayPreferences(resourceType, {
      showDocumentation,
      showExpert,
      showOptional,
      ...next,
    });
  };
  const nodes = useMemo(
    () => draft?.editState.nodes ?? [],
    [draft?.editState.nodes],
  );
  const globalTarget = activeTargetId === "edit:workflowConfiguration";
  const target = useMemo(
    () => globalTarget ? null : findNode(nodes, activeTargetId),
    [activeTargetId, globalTarget, nodes],
  );
  const scope = useMemo(
    () => target ? editScope(nodes, target.id) : null,
    [nodes, target],
  );
  const connectivity = useConnectivityChecks(
    draft,
    scope?.path ?? null,
  );
  useEffect(() => {
    onConnectivityStatesChange?.(connectivity.navigationStates);
    return () => onConnectivityStatesChange?.({});
  }, [connectivity.navigationStates, onConnectivityStatesChange]);
  const environmentGroups = useMemo(
    () => environmentReferenceGroups(
      nodes,
      scope?.path ?? null,
      environmentDiagnostics.diagnostics,
      environmentDiagnostics.lifecycle,
    ),
    [
      environmentDiagnostics.diagnostics,
      environmentDiagnostics.lifecycle,
      nodes,
      scope?.path,
    ],
  );
  const scopedConnectivityStates = useMemo(
    () => {
      if (!scope?.path) return connectivity.states;
      return connectivity.states.filter(({ target: connectivityTarget }) => (
        editPathsOverlap(scope.path, connectivityTarget.editPath)
      ));
    },
    [connectivity.states, scope?.path],
  );
  const renderedScope = useMemo(
    () => contentScope(scope),
    [scope],
  );
  const expansionScopeId = scope?.id
    ?? (globalTarget ? "edit:workflowConfiguration" : "edit:root");
  const scopedNodes = useMemo(
    () => {
      if (activeTargetId && !target && !globalTarget) return [];
      if (!scope) return nodes;
      const children = propertyChildren(renderedScope ?? scope);
      if (renderedScope !== scope) return children;
      // Keep an empty selected collection visible so its add command remains
      // available instead of rendering an unexplained blank editor.
      return children.length > 0 ? children : [scope];
    },
    [activeTargetId, globalTarget, nodes, renderedScope, scope, target],
  );
  const scopeCommands = useMemo(
    () => (
      renderedScope && !scopedNodes.includes(renderedScope)
        ? addCommands(renderedScope)
        : []
    ),
    [renderedScope, scopedNodes],
  );
  const scopeActionMessages = useMemo(
    () => [...new Set([
      ...scopeCommands.flatMap((command) => (
        command.command?.blockedMessage
          ? [scopeActionMessage(command.command.blockedMessage)]
          : []
      )),
      ...(scope?.diagnostics ?? []).flatMap((diagnostic) => (
        ["required", "error", "gated", "blocked"].includes(
          diagnostic.severity,
        )
          ? [scopeActionMessage(diagnostic.message)]
          : []
      )),
      ...(
        renderedScope && renderedScope !== scope
          ? renderedScope.diagnostics
          : []
      ).flatMap((diagnostic) => (
        ["required", "error", "gated", "blocked"].includes(
          diagnostic.severity,
        )
          ? [scopeActionMessage(diagnostic.message)]
          : []
      )),
    ])],
    [renderedScope, scope, scopeCommands],
  );
  const scopeHasValidationError = Boolean(
    scope && nodeTreeHasValidationError(scope),
  );
  const editSurfaces = useMemo(() => {
    const surfaces: { kind: string; label: string; targetId: string }[] = [];
    Object.values(
      navigationSnapshot?.nodes ?? draft?.navigation?.nodes ?? {},
    ).forEach((navNode) => {
      if (!["resource", "config-definition"].includes(navNode.kind)) return;
      navNode.capabilities.forEach((capability) => {
        if (capability.kind !== "edit") return;
        surfaces.push({
          kind: navNode.kind,
          label: navNode.label,
          targetId: capability.editTargetId,
        });
      });
    });
    return surfaces;
  }, [draft?.navigation, navigationSnapshot]);
  const scopeTargetId = scope?.id ?? null;
  const usedIn = useMemo(() => {
    if (!scopeTargetId) return [];
    const users = new Map<string, CanonicalReference>();
    const resourceSurfaces = editSurfaces.filter(
      (surface) => surface.kind === "resource",
    );
    const visit = (node: EditNode) => {
      const referenceTarget = node.referenceTargetId;
      if (
        referenceTarget
        && (
          scopeTargetId === referenceTarget
          || scopeTargetId.startsWith(`${referenceTarget}.`)
        )
        // References from inside the definition's own subtree are
        // structure, not usage.
        && !node.id.startsWith(`${referenceTarget}.`)
      ) {
        const owner = resourceSurfaces
          .filter((surface) => (
            node.id === surface.targetId
            || node.id.startsWith(`${surface.targetId}.`)
          ))
          .sort((a, b) => b.targetId.length - a.targetId.length)[0];
        if (owner && owner.targetId !== scopeTargetId) {
          users.set(owner.targetId, {
            label: owner.label,
            targetId: owner.targetId,
          });
        }
      }
      nodeChildren(node).forEach(visit);
    };
    nodes.forEach(visit);
    return [...users.values()];
  }, [editSurfaces, nodes, scopeTargetId]);
  const topLevelAdds = useMemo(
    () => topLevelAddContexts(nodes),
    [nodes],
  );
  const resourceAddOptions = useMemo(
    () => topLevelAdds.flatMap((context) => {
      const placement = resourceAddPlacement(context.parent);
      const validation = hintRecord(context.command.validation);
      const inputHint = hintRecord(context.command.inputHint);
      return placement ? [{
        id: context.command.id,
        label: fieldName(context.command),
        disabled: Boolean(context.command.command?.blockedMessage),
        disabledReason: context.command.command?.blockedMessage,
        placement,
        requiresName: context.command.command?.requiresName !== false,
        pattern: typeof validation.pattern === "string"
          ? validation.pattern
          : typeof inputHint.pattern === "string"
            ? inputHint.pattern
            : undefined,
        validationMessage: typeof validation.message === "string"
          ? validation.message
          : typeof inputHint.message === "string"
            ? inputHint.message
            : undefined,
      }] : [];
    }),
    [topLevelAdds],
  );
  const resourceRenames = useMemo(
    () => resourceRenameOptions(nodes),
    [nodes],
  );
  const titleRenameOption = useMemo(
    () => resourceRenames.find(
      (option) => option.editTargetId === initialTargetId,
    ) ?? resourceRenames.find((option) => (
      option.label === resourceLabel
      || `${option.resourceNamePrefix ?? ""}${option.currentName}`
        === resourceLabel
    )) ?? null,
    [initialTargetId, resourceLabel, resourceRenames],
  );
  const titleRenameValidationProblem = fieldValidationProblem(
    titleRenameName,
    titleRenameOption?.pattern,
    titleRenameOption?.validationMessage,
  );
  const titleRenameCollisionProblem = resourceRenameCollisionProblem(
    titleRenameOption ?? undefined,
    titleRenameName,
  );
  const titleRenameProblem = titleRenameValidationProblem
    || titleRenameCollisionProblem;
  const titleIdentityProblem = useMemo(() => {
    if (!scope?.path[0]?.startsWith("snapshotMigrationConfigs")) return "";
    return (scope.diagnostics ?? [])
      .find((diagnostic) => (
        diagnostic.severity === "error"
        && diagnostic.message.includes("already configured")
      ))?.message ?? "";
  }, [scope]);
  const titleRenameFormRef = useEscapeCancel<HTMLFormElement>(
    () => setTitleRenaming(false),
    !titleRenaming,
  );

  useEffect(() => {
    const focusCreatedName = autoRenameTarget.current === initialTargetId;
    setActiveTargetId(initialTargetId ?? null);
    setTitleRenaming(focusCreatedName);
    setTitleRenameName("");
    if (focusCreatedName) autoRenameTarget.current = null;
  }, [initialTargetId]);

  useEffect(() => {
    if (
      !pendingCreatedTarget
      || !findNode(nodes, pendingCreatedTarget.targetId)
    ) {
      return;
    }
    if (pendingCreatedTarget.focus === "name") {
      autoRenameTarget.current = pendingCreatedTarget.targetId;
    } else {
      autoFocusFieldTarget.current = pendingCreatedTarget.targetId;
    }
    const navigated = onNavigateCreatedEditTarget(
      pendingCreatedTarget.targetId,
      pendingCreatedTarget.returnTargetId,
      pendingCreatedTarget.returnLabel,
    );
    if (navigated) setPendingCreatedTarget(null);
  }, [nodes, onNavigateCreatedEditTarget, pendingCreatedTarget]);

  useEffect(() => {
    setPendingRemoval((current) => (
      current?.reviewingTargetId
      && initialTargetId === current.node.id
        ? { ...current, reviewingTargetId: undefined }
        : current
    ));
  }, [initialTargetId]);

  useEffect(() => {
    if (!titleRenaming) {
      setTitleRenameName(titleRenameOption?.currentName ?? "");
    }
  }, [titleRenameOption, titleRenaming]);

  useEffect(() => {
    if (!draft) return;
    const currentIds = allNodeIds(scopedNodes);
    const scopeChanged = expansionScope.current !== expansionScopeId;
    const previousIds = knownExpansionIds.current;
    expansionScope.current = expansionScopeId;
    knownExpansionIds.current = currentIds;
    setExpanded((current) => {
      if (scopeChanged || previousIds === null) {
        manuallyCollapsedIds.current.clear();
        const initiallyExpanded = initialExpanded(
          scopedNodes,
          renderOptional,
          renderExpert,
        );
        knownRowIds.current = new Set(
          treeRows(
            scopedNodes,
            initiallyExpanded,
            renderOptional,
            renderExpert,
          ).map(({ node }) => node.id),
        );
        skipNextRowTracking.current = true;
        return initiallyExpanded;
      }
      const retained = new Set(
        [...current].filter((id) => findNode(scopedNodes, id)),
      );
      const visit = (node: EditNode) => {
        const children = propertyChildren(node);
        const newlyAdded = !previousIds.has(node.id);
        const receivedNewChildren = children.some(
          (child) => !previousIds.has(child.id),
        );
        const authoredPath = nodeTreeHasAuthoredValue(node);
        if (
          children.length > 0
          && !manuallyCollapsedIds.current.has(node.id)
          && shouldAutoExpand(node, renderOptional, renderExpert)
          && (
            (
              (newlyAdded || receivedNewChildren)
              && (!node.expert || authoredPath)
            )
            || (node.expert && authoredPath)
          )
        ) {
          retained.add(node.id);
        }
        if (retained.has(node.id)) children.forEach(visit);
      };
      scopedNodes.forEach(visit);
      return retained;
    });
    setSelectedId((current) => (
      findNode(scopedNodes, current)
        ? current
        : target?.id ?? scopedNodes[0]?.id ?? null
    ));
  }, [
    draft,
    expansionScopeId,
    renderExpert,
    renderOptional,
    scopedNodes,
    target,
  ]);

  useEffect(() => {
    pendingScrollTop.current = null;
    pendingRowAnchor.current = null;
  }, [expansionScopeId]);

  const hasLocalEdits = locallyEditedIds.size > 0 || rawYamlDirty;
  useEffect(() => {
    if (draft?.rawYaml === undefined) return;
    setRawYamlText(draft.rawYaml);
    setRawYamlDirty(false);
  }, [draft?.draftRevision, draft?.rawYaml]);

  useEffect(() => {
    if (!draft?.dirty && !hasLocalEdits) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    globalThis.addEventListener("beforeunload", warn);
    return () => globalThis.removeEventListener("beforeunload", warn);
  }, [draft?.dirty, hasLocalEdits]);

  const rows = useMemo(
    () => treeRows(scopedNodes, expanded, renderOptional, renderExpert),
    [expanded, renderExpert, renderOptional, scopedNodes],
  );
  useLayoutEffect(() => {
    if (
      autoFocusFieldTarget.current !== initialTargetId
      || activeTargetId !== initialTargetId
      || rows.length === 0
    ) {
      return;
    }
    const editableRows = rows.filter(({ node }) => (
      ["boolean", "scalar", "union"].includes(node.valueKind)
      && hintRecord(node.inputHint).readOnly !== true
    ));
    const candidate = editableRows.find(({ node }) => (
      node.required === true
      || node.presence === "required"
      || node.status === "required"
    )) ?? editableRows[0];
    if (!candidate) return;
    const row = rowElements.current.get(candidate.node.id);
    const control = row?.querySelector<HTMLElement>(
      ".property-value input:not(:disabled), "
      + ".property-value select:not(:disabled), "
      + ".property-value textarea:not(:disabled), "
      + ".property-value button:not(:disabled)",
    );
    if (!control) return;
    autoFocusFieldTarget.current = null;
    setSelectedId(candidate.node.id);
    control.focus();
  }, [activeTargetId, initialTargetId, rows]);
  const measureRowTops = useCallback(() => {
    const tops = new Map<string, number>();
    // offsetTop is layout truth: unaffected by panel scroll AND by any
    // in-flight transform animations, so a re-run during a transition
    // (e.g. the inserted-row tracking commit) measures identical values
    // and stacks no second animation on the moving rows.
    rowElements.current.forEach((element, nodeId) => {
      tops.set(nodeId, element.offsetTop);
    });
    return tops;
  }, []);
  useLayoutEffect(() => {
    const previousTops = flipTops.current;
    const scopeChanged = flipScope.current !== expansionScopeId;
    flipScope.current = expansionScopeId;
    const nextTops = measureRowTops();
    flipTops.current = nextTops;
    // Trigger-only dependencies: these change row geometry without
    // being read here, and each needs a measurement pass.
    void renderOptional;
    void renderExpert;
    void showDocumentation;
    void removingIds;
    void collapsingIds;
    void insertedIds;
    if (scopeChanged) return;
    if (globalThis.matchMedia?.("(prefers-reduced-motion: reduce)").matches) {
      return;
    }
    nextTops.forEach((top, nodeId) => {
      const previous = previousTops.get(nodeId);
      const element = rowElements.current.get(nodeId);
      if (
        previous === undefined
        || !element
        || typeof element.animate !== "function"
      ) {
        return;
      }
      const delta = previous - top;
      if (Math.abs(delta) < 0.5) return;
      activeFlipCount.current += 1;
      const animation = element.animate([
        { transform: `translateY(${delta}px)` },
        { transform: "translateY(0)" },
      ], {
        duration: 420,
        easing: "cubic-bezier(0.2, 0.75, 0.25, 1)",
      });
      const release = () => {
        activeFlipCount.current = Math.max(0, activeFlipCount.current - 1);
      };
      animation.onfinish = release;
      animation.oncancel = release;
    });
  }, [
    collapsingIds,
    expansionScopeId,
    insertedIds,
    measureRowTops,
    removingIds,
    renderExpert,
    renderOptional,
    rows,
    showDocumentation,
  ]);
  useEffect(() => {
    const panel = configTablePanelRef.current;
    if (!panel || typeof ResizeObserver === "undefined") return;
    // Row detail panes open and close from row-local state; refresh the
    // move baselines whenever content resizes so the next animated
    // change starts from what is actually on screen.
    const observer = new ResizeObserver(() => {
      if (activeFlipCount.current > 0) return;
      flipTops.current = measureRowTops();
    });
    observer.observe(panel);
    const body = panel.querySelector("tbody");
    if (body) observer.observe(body);
    return () => observer.disconnect();
  }, [measureRowTops]);
  const retainScrollPosition = useCallback(() => {
    const panel = configTablePanelRef.current;
    if (!panel) return;
    pendingScrollTop.current = panel.scrollTop;
  }, []);
  const clearRemovingRows = useCallback((rowIds: ReadonlySet<string>) => {
    const released = new Set<string>();
    rowIds.forEach((id) => {
      const remaining = (removingClaims.current.get(id) ?? 1) - 1;
      if (remaining > 0) {
        removingClaims.current.set(id, remaining);
      } else {
        removingClaims.current.delete(id);
        released.add(id);
      }
    });
    setRemovingIds((current) => {
      const next = new Set(current);
      released.forEach((id) => next.delete(id));
      return next;
    });
  }, []);
  const beginRowExit = useCallback((
    rowIds: Set<string>,
    complete: () => void,
  ) => {
    if (rowIds.size === 0) {
      complete();
      return null;
    }
    retainScrollPosition();
    rowIds.forEach((id) => {
      removingClaims.current.set(
        id,
        (removingClaims.current.get(id) ?? 0) + 1,
      );
    });
    setRemovingIds((current) => new Set([...current, ...rowIds]));
    const timer = globalThis.setTimeout(() => {
      transitionTimers.current.delete(timer);
      complete();
      clearRemovingRows(rowIds);
    }, ROW_TRANSITION_MS);
    transitionTimers.current.add(timer);
    return { timer, rowIds };
  }, [clearRemovingRows, retainScrollPosition]);
  const cancelRowExit = useCallback((
    transition: { timer: number; rowIds: Set<string> } | null,
  ) => {
    if (!transition) return;
    globalThis.clearTimeout(transition.timer);
    transitionTimers.current.delete(transition.timer);
    clearRemovingRows(transition.rowIds);
  }, [clearRemovingRows]);
  const changeOptionalVisibility = (next: boolean) => {
    const hideExpert = !next && showExpert;
    storeDisplayPreferences({
      showOptional: next,
      ...(hideExpert ? { showExpert: false } : {}),
    });
    setShowOptional(next);
    if (hideExpert) {
      setShowExpert(false);
      cancelRowExit(expertTransition.current);
      expertTransition.current = null;
    }
    cancelRowExit(optionalTransition.current);
    optionalTransition.current = null;
    if (next) {
      setRenderOptional(true);
      const newlyVisible = visibleExpandableIds(
        scopedNodes,
        true,
        renderExpert,
      );
      setExpanded((current) => {
        const nextExpanded = new Set(current);
        newlyVisible.forEach((id) => {
          const node = findNode(scopedNodes, id);
          if (
            node
            && (!node.expert || nodeTreeHasAuthoredValue(node))
            && !manuallyCollapsedIds.current.has(id)
            && shouldAutoExpand(node, true, renderExpert)
          ) {
            nextExpanded.add(id);
          }
        });
        return nextExpanded;
      });
      return;
    }
    const nextIds = new Set(
      treeRows(scopedNodes, expanded, false, hideExpert ? false : renderExpert)
        .map(({ node }) => node.id),
    );
    const exiting = new Set(
      rows
        .map(({ node }) => node.id)
        .filter((id) => !nextIds.has(id)),
    );
    optionalTransition.current = beginRowExit(exiting, () => {
      optionalTransition.current = null;
      setRenderOptional(false);
      if (hideExpert) setRenderExpert(false);
    });
  };
  const changeExpertVisibility = (next: boolean) => {
    if (next && !showOptional) {
      changeOptionalVisibility(true);
    }
    storeDisplayPreferences({
      showExpert: next,
      ...(next ? { showOptional: true } : {}),
    });
    setShowExpert(next);
    cancelRowExit(expertTransition.current);
    expertTransition.current = null;
    if (next) {
      setRenderExpert(true);
      return;
    }
    const nextIds = new Set(
      treeRows(scopedNodes, expanded, renderOptional, false)
        .map(({ node }) => node.id),
    );
    const exiting = new Set(
      rows
        .map(({ node }) => node.id)
        .filter((id) => !nextIds.has(id)),
    );
    expertTransition.current = beginRowExit(exiting, () => {
      expertTransition.current = null;
      setRenderExpert(false);
    });
  };
  const toggleExpanded = (node: EditNode) => {
    const pendingCollapse = collapseTransitions.current.get(node.id);
    if (pendingCollapse) {
      cancelRowExit(pendingCollapse);
      collapseTransitions.current.delete(node.id);
      setCollapsingIds((current) => {
        const next = new Set(current);
        next.delete(node.id);
        return next;
      });
      manuallyCollapsedIds.current.delete(node.id);
      return;
    }
    if (!expanded.has(node.id)) {
      manuallyCollapsedIds.current.delete(node.id);
      setExpanded((current) => new Set(current).add(node.id));
      return;
    }
    const parentIndex = rows.findIndex(({ node: rowNode }) =>
      rowNode.id === node.id);
    const parentDepth = rows[parentIndex]?.depth;
    const exiting = new Set<string>();
    if (parentIndex >= 0 && parentDepth !== undefined) {
      for (let index = parentIndex + 1; index < rows.length; index += 1) {
        if (rows[index].depth <= parentDepth) break;
        exiting.add(rows[index].node.id);
      }
    }
    manuallyCollapsedIds.current.add(node.id);
    const descendants = descendantNodeIds(node);
    setCollapsingIds((current) => new Set(current).add(node.id));
    const transition = beginRowExit(exiting, () => {
      collapseTransitions.current.delete(node.id);
      setExpanded((current) => {
        const next = new Set(current);
        next.delete(node.id);
        descendants.forEach((id) => next.delete(id));
        return next;
      });
      setCollapsingIds((current) => {
        const next = new Set(current);
        next.delete(node.id);
        return next;
      });
    });
    if (transition) {
      collapseTransitions.current.set(node.id, transition);
    } else {
      setCollapsingIds((current) => {
        const next = new Set(current);
        next.delete(node.id);
        return next;
      });
    }
  };
  const expandAll = () => {
    collapseTransitions.current.forEach((transition) => {
      cancelRowExit(transition);
    });
    collapseTransitions.current.clear();
    setCollapsingIds(new Set());
    const expandable = visibleExpandableIds(
      scopedNodes,
      renderOptional,
      renderExpert,
    );
    expandable.forEach((id) => manuallyCollapsedIds.current.delete(id));
    setExpanded((current) => new Set([...current, ...expandable]));
  };
  useLayoutEffect(() => {
    if (removingIds.size > 0 || pendingScrollTop.current === null) return;
    const panel = configTablePanelRef.current;
    if (panel) panel.scrollTop = pendingScrollTop.current;
    pendingScrollTop.current = null;
  }, [removingIds, rows]);
  useEffect(() => () => {
    if (insertedTimer.current !== null) {
      globalThis.clearTimeout(insertedTimer.current);
    }
    transitionTimers.current.forEach((timer) => globalThis.clearTimeout(timer));
    transitionTimers.current.clear();
    removingClaims.current.clear();
  }, []);
  const rowAncestors = useMemo(() => {
    const stack: EditRow[] = [];
    return rows.map((row) => {
      while (stack.at(-1)?.depth >= row.depth) stack.pop();
      const ancestors = [...stack];
      stack.push(row);
      return ancestors;
    });
  }, [rows]);
  const updatePinnedContext = useCallback(() => {
    const panel = configTablePanelRef.current;
    if (!panel || panel.scrollTop <= 1) {
      setPinnedContext((current) => current.length === 0 ? current : []);
      return;
    }
    const headerBottom = panel.querySelector("thead")
      ?.getBoundingClientRect().bottom ?? panel.getBoundingClientRect().top;
    let activeIndex = -1;
    rows.forEach(({ node }, index) => {
      const element = rowElements.current.get(node.id);
      if (!element) return;
      const ancestors = (rowAncestors[index] ?? []).filter(
        ({ node: ancestor }) => ancestor.id !== scope?.id,
      );
      const activationTop = (
        headerBottom
        + ancestors.length * PINNED_CONTEXT_HEIGHT
        + PINNED_CONTEXT_TRANSITION
      );
      if (element.getBoundingClientRect().top <= activationTop) {
        activeIndex = index;
      }
    });
    if (activeIndex < 0) return;

    const activeHasVisibleChildren = (
      rows[activeIndex + 1]?.depth > rows[activeIndex].depth
    );
    const nextPinned = [
      ...(rowAncestors[activeIndex] ?? []),
      ...(activeHasVisibleChildren ? [rows[activeIndex]] : []),
    ]
      .filter(({ node }) => node.id !== scope?.id)
      .map(({ node }, index) => {
        const element = rowElements.current.get(node.id);
        const rowTop = element?.getBoundingClientRect().top ?? headerBottom;
        const slotTop = headerBottom + index * PINNED_CONTEXT_HEIGHT;
        return {
          id: node.id,
          progress: Math.max(0, Math.min(
            1,
            (slotTop + PINNED_CONTEXT_TRANSITION - rowTop)
              / PINNED_CONTEXT_TRANSITION,
          )),
        };
      })
      .filter(({ progress }) => progress > 0);
    setPinnedContext((current) => (
      current.length === nextPinned.length
      && current.every((item, index) => (
        item.id === nextPinned[index].id
        && Math.abs(item.progress - nextPinned[index].progress) < 0.01
      ))
        ? current
        : nextPinned
    ));
  }, [rowAncestors, rows, scope?.id]);
  const pinnedRows = useMemo(
    () => pinnedContext.flatMap((context) => {
      const row = rows.find(({ node }) => node.id === context.id);
      return row ? [{ ...row, progress: context.progress }] : [];
    }),
    [pinnedContext, rows],
  );
  const schedulePinnedContextUpdate = useCallback(() => {
    if (pinUpdateFrame.current !== null) return;
    pinUpdateFrame.current = globalThis.requestAnimationFrame(() => {
      pinUpdateFrame.current = null;
      updatePinnedContext();
    });
  }, [updatePinnedContext]);
  const scrollToRow = useCallback((nodeId: string) => {
    const panel = configTablePanelRef.current;
    const element = rowElements.current.get(nodeId);
    if (!panel || !element) return;
    const headerBottom = panel.querySelector("thead")
      ?.getBoundingClientRect().bottom ?? panel.getBoundingClientRect().top;
    const rowIndex = rows.findIndex(({ node }) => node.id === nodeId);
    const pinnedAncestors = (rowAncestors[rowIndex] ?? []).filter(
      ({ node }) => node.id !== scope?.id,
    ).length;
    const targetTop = headerBottom
      + pinnedAncestors * PINNED_CONTEXT_HEIGHT;
    panel.scrollTo({
      behavior: "smooth",
      top: Math.max(
        0,
        panel.scrollTop + element.getBoundingClientRect().top - targetTop,
      ),
    });
  }, [rowAncestors, rows, scope?.id]);
  useEffect(() => {
    updatePinnedContext();
    globalThis.addEventListener("resize", schedulePinnedContextUpdate);
    return () => {
      globalThis.removeEventListener("resize", schedulePinnedContextUpdate);
      if (pinUpdateFrame.current !== null) {
        globalThis.cancelAnimationFrame(pinUpdateFrame.current);
        pinUpdateFrame.current = null;
      }
    };
  }, [schedulePinnedContextUpdate, updatePinnedContext]);
  useEffect(() => {
    const currentIds = new Set(rows.map(({ node }) => node.id));
    if (skipNextRowTracking.current) {
      skipNextRowTracking.current = false;
      return;
    }
    if (knownRowIds.current === null) {
      knownRowIds.current = currentIds;
      return;
    }
    const inserted = new Set(
      [...currentIds].filter((id) => !knownRowIds.current?.has(id)),
    );
    knownRowIds.current = currentIds;
    if (inserted.size === 0) return;
    setInsertedIds(inserted);
    if (insertedTimer.current !== null) {
      globalThis.clearTimeout(insertedTimer.current);
    }
    insertedTimer.current = globalThis.setTimeout(() => {
      insertedTimer.current = null;
      setInsertedIds(new Set());
    }, 420);
  }, [rows]);
  useLayoutEffect(() => {
    const anchor = pendingRowAnchor.current;
    if (!draft || !anchor || draft.draftRevision === anchor.draftRevision) {
      return;
    }
    pendingRowAnchor.current = null;
    const element = rowElements.current.get(anchor.nodeId);
    const panel = configTablePanelRef.current;
    if (!element || !panel) return;
    panel.scrollTop += element.getBoundingClientRect().top - anchor.top;
  }, [draft, rows]);
  const applyExternalOperations: ApplyExternalOperations = (
    operations,
    operationNotice,
  ) => {
    setProblem("");
    try {
      const current = queryClient.getQueryData<BrowserConfigDraft>(
        BROWSER_CONFIG_DRAFT_QUERY_KEY,
      );
      if (!current) return false;
      const updated = operations.reduce(
        (working, operation) =>
          applyBrowserEditOperation(working, operation),
        current,
      );
      queryClient.setQueryData(BROWSER_CONFIG_DRAFT_QUERY_KEY, updated);
      setNotice(operationNotice ?? "");
      return Promise.resolve(true);
    } catch (error) {
      setProblem(error instanceof Error ? error.message : String(error));
      return Promise.resolve(false);
    }
  };

  const markLocalEdit = (nodeId: string, dirty: boolean) => {
    setLocallyEditedIds((current) => {
      const next = new Set(current);
      if (dirty) next.add(nodeId);
      else next.delete(nodeId);
      return next;
    });
  };

  const commit = (operation: EditOperation) => {
    const previous = pendingCommit.current;
    const operationPromise = (async () => {
      if (previous && !await previous) return false;
      const current = queryClient.getQueryData<BrowserConfigDraft>(
        BROWSER_CONFIG_DRAFT_QUERY_KEY,
      );
      if (!current) return false;
      setProblem("");
      try {
        queryClient.setQueryData(
          BROWSER_CONFIG_DRAFT_QUERY_KEY,
          applyBrowserEditOperation(current, operation),
        );
        return true;
      } catch (error) {
        setProblem(error instanceof Error ? error.message : String(error));
        return false;
      }
    })();
    pendingCommit.current = operationPromise;
    void operationPromise.finally(() => {
      if (pendingCommit.current === operationPromise) {
        pendingCommit.current = null;
      }
    });
    return operationPromise;
  };

  const waitForPendingCommit = async () => {
    const pending = pendingCommit.current;
    return pending ?? true;
  };

  const checkRawYaml = (): BrowserConfigDraft | null => {
    const current = queryClient.getQueryData<BrowserConfigDraft>(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
    );
    if (!current) return null;
    if (!rawYamlDirty) return current;
    try {
      const next = replaceBrowserConfigYaml(current, rawYamlText);
      queryClient.setQueryData(BROWSER_CONFIG_DRAFT_QUERY_KEY, next);
      setRawYamlDirty(false);
      setProblem("");
      return next;
    } catch (error) {
      setProblem(error instanceof Error ? error.message : String(error));
      return null;
    }
  };

  const persistBrowserDraft = async (
    current: BrowserConfigDraft,
  ): Promise<BrowserConfigDraft> => {
    const document = current.dirty
      ? await saveConfigurationDocument(
        current.persistedRevision,
        current.rawDocument,
      )
      : {
        modelVersion: "1" as const,
        persistedRevision: current.persistedRevision,
        rawYaml: current.savedRawDocument,
      };
    await waitForPendingCommit();
    const latest = queryClient.getQueryData<BrowserConfigDraft>(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
    ) ?? current;
    const saved = acknowledgeSavedBrowserConfigDraft(
      document,
      current,
      latest,
    );
    queryClient.setQueryData(BROWSER_CONFIG_DRAFT_QUERY_KEY, saved);
    return saved;
  };

  const save = async () => {
    if (draftBaseStale) {
      setProblem(
        "The saved configuration changed elsewhere. Revert to load the current saved configuration before saving.",
      );
      return;
    }
    setActionPending(true);
    try {
      if (!await waitForPendingCommit()) return;
      const current = rawYamlDirty
        ? checkRawYaml()
        : queryClient.getQueryData<BrowserConfigDraft>(
          BROWSER_CONFIG_DRAFT_QUERY_KEY,
        );
      if (!current?.dirty) return;
      try {
        const saved = await persistBrowserDraft(current);
        if (!saved.dirty) setLocallyEditedIds(new Set());
      } catch (error) {
        setProblem(error instanceof Error ? error.message : String(error));
      }
    } finally {
      setActionPending(false);
    }
  };

  const revert = async () => {
    const current = queryClient.getQueryData<BrowserConfigDraft>(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
    );
    if (!current) return false;
    if (current.baseStale) {
      setActionPending(true);
      try {
        const [document, schema] = await Promise.all([
          getConfigurationDocument(),
          getConfigurationSchema(),
        ]);
        queryClient.setQueryData(
          BROWSER_CONFIG_DRAFT_QUERY_KEY,
          createBrowserConfigDraft(
            document,
            undefined,
            schema.unifiedSchema,
          ),
        );
        setLocallyEditedIds(new Set());
        setRawYamlText(document.rawYaml);
        setRawYamlDirty(false);
        setProblem("");
        onDraftReverted();
        return true;
      } catch (error) {
        setProblem(error instanceof Error ? error.message : String(error));
        return false;
      } finally {
        setActionPending(false);
      }
    }
    queryClient.setQueryData(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
      revertedBrowserConfigDraft(current),
    );
    setLocallyEditedIds(new Set());
    setRawYamlText(current.savedRawDocument);
    setRawYamlDirty(false);
    setProblem("");
    onDraftReverted();
    return true;
  };

  const openSubmitReview = async () => {
    if (!await waitForPendingCommit()) return;
    const current = rawYamlDirty
      ? checkRawYaml()
      : queryClient.getQueryData<BrowserConfigDraft>(
        BROWSER_CONFIG_DRAFT_QUERY_KEY,
      );
    if (!current) return;
    if (current.baseStale) {
      setProblem(
        "The saved configuration changed elsewhere. Revert to load it before submitting.",
      );
      return;
    }
    setActionPending(true);
    try {
      const saved = await persistBrowserDraft(current);
      setLocallyEditedIds(new Set());
      setRawYamlDirty(false);
      setSubmitPersistedRevision(saved.persistedRevision);
      queryClient.setQueryData(BROWSER_CONFIG_DRAFT_QUERY_KEY, saved);
    } catch (error) {
      setProblem(error instanceof Error ? error.message : String(error));
      return;
    } finally {
      setActionPending(false);
    }
    setConfirmSubmit(true);
  };

  const requestRemoval = useCallback((node: EditNode) => {
    const current = queryClient.getQueryData<BrowserConfigDraft>(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
    );
    if (!current) return;
    setPendingRemoval({
      node,
      impact: {
        targetPath: node.path,
        targetLabel: fieldName(node),
        affected: localConfigRemovalImpact(current.config, node.path),
      },
      loading: false,
      error: "",
    });
  }, [queryClient]);

  useEffect(() => {
    if (
      !initialRemovalTargetId
      || handledInitialRemovalTarget.current === initialRemovalTargetId
    ) {
      return;
    }
    const removalNode = findNode(nodes, initialRemovalTargetId);
    if (!removalNode) return;
    handledInitialRemovalTarget.current = initialRemovalTargetId;
    setActiveTargetId(initialRemovalTargetId);
    setSelectedId(initialRemovalTargetId);
    if (removalNode.removable) {
      requestRemoval(removalNode);
    } else {
      setProblem(
        `${fieldName(removalNode)} cannot be removed directly from this configuration view.`,
      );
    }
    onInitialRemovalHandled?.();
  }, [
    initialRemovalTargetId,
    nodes,
    onInitialRemovalHandled,
    requestRemoval,
  ]);

  const confirmRemoval = async (
    referencingResources: "delete" | "clear-references",
  ) => {
    if (!pendingRemoval?.impact) return;
    const operation = {
      op: "removeConfig" as const,
      path: pendingRemoval.node.path,
      referencingResources,
    };
    const applied = await commit(operation);
    if (applied) setPendingRemoval(null);
  };

  const reviewRemovalTarget = (entry: ConfigRemovalImpactEntry) => {
    const targetId = `edit:${entry.path.join(".")}`;
    setPendingRemoval((current) => (
      current ? { ...current, reviewingTargetId: targetId } : current
    ));
    onNavigateEditTarget(targetId);
  };

  const selectAdded = (nodeId: string, parentId: string | null) => {
    if (parentId) {
      setExpanded((current) => new Set(current).add(parentId));
    }
    setSelectedId(nodeId);
    queueMicrotask(() => rowElements.current.get(nodeId)?.focus());
  };

  const selectContextAdded = (
    nodeId: string,
    parentId: string | null,
  ) => {
    setActiveTargetId(nodeId);
    selectAdded(nodeId, parentId);
  };

  const runTopLevelAdd = (context: AddContext, name: string) => {
    const option = resourceAddOptions.find(
      (candidate) => candidate.id === context.command.id,
    );
    if (!option) return Promise.resolve(false);
    const nextIndex = nextCollectionIndex(context.parent);
    const addition = pendingResourceAddition(option, name, nextIndex);
    onResourceAddStarted(addition);
    return runAddCommand(
      context.command,
      context.parent,
      name,
      commit,
      selectContextAdded,
    ).then((applied) => {
      onResourceAddSettled(addition, applied);
      return applied;
    });
  };

  resourceAddRequest.current = (optionId, name) => {
    const context = topLevelAdds.find(
      ({ command }) => command.id === optionId,
    );
    return context ? runTopLevelAdd(context, name) : Promise.resolve(false);
  };

  resourceRenameRequest.current = (editTargetId, resourceId, newName) => {
    const option = resourceRenames.find(
      (candidate) => candidate.editTargetId === editTargetId,
    );
    if (
      !option
      || !newName.trim()
      || newName.trim() === option.currentName
      || resourceRenameCollisionProblem(option, newName)
    ) {
      return Promise.resolve(false);
    }
    const rename = pendingResourceRename(option, resourceId, newName.trim());
    onResourceRenameStarted(rename);
    const operation: EditOperation = option.operation === "set"
      ? {
          op: "set",
          path: option.path,
          value: newName.trim(),
        }
      : {
          op: "renameConfig",
          path: option.path,
          newName: newName.trim(),
        };
    return commit(operation).then((applied) => {
      if (applied) {
        setActiveTargetId(rename.editTargetId);
        setSelectedId(rename.editTargetId);
      }
      onResourceRenameSettled(rename, applied);
      return applied;
    });
  };

  const finishExit = async (saveChanges: boolean) => {
    setActionPending(true);
    try {
      if (!await waitForPendingCommit()) return;
      const current = rawYamlDirty
        ? checkRawYaml()
        : queryClient.getQueryData<BrowserConfigDraft>(
          BROWSER_CONFIG_DRAFT_QUERY_KEY,
        );
      if (!current) return;
      try {
        if (saveChanges) {
          if (current.baseStale) {
            throw new Error(
              "The saved configuration changed elsewhere. Revert to load it before saving.",
            );
          }
          await persistBrowserDraft(current);
        }
      } catch (error) {
        setExitPromptOpen(false);
        setProblem(error instanceof Error ? error.message : String(error));
        return;
      }
      queryClient.removeQueries({
        queryKey: BROWSER_CONFIG_DRAFT_QUERY_KEY,
      });
      setLocallyEditedIds(new Set());
      setExitPromptOpen(false);
      onClose();
    } finally {
      setActionPending(false);
    }
  };

  const close = async () => {
    if (!await waitForPendingCommit()) return;
    const current = queryClient.getQueryData<BrowserConfigDraft>(
      BROWSER_CONFIG_DRAFT_QUERY_KEY,
    );
    if (!current) return;
    if (current.dirty || hasLocalEdits) {
      setExitPromptOpen(true);
      return;
    }
    await finishExit(false);
  };

  useEffect(() => {
    onExitReady(() => {
      void close();
    });
    return () => onExitReady(null);
  });

  useEffect(() => {
    onSubmitReady(() => {
      void openSubmitReview();
    });
    return () => onSubmitReady(null);
  });

  useEffect(() => {
    onResourceAddsReady({
      options: resourceAddOptions,
      renames: resourceRenames,
      busy,
      add: (optionId, name) => resourceAddRequest.current(optionId, name),
      rename: (editTargetId, resourceId, newName) =>
        resourceRenameRequest.current(editTargetId, resourceId, newName),
    });
    return () => onResourceAddsReady(null);
  }, [
    busy,
    onResourceAddsReady,
    resourceAddOptions,
    resourceRenames,
  ]);

  if (draftQuery.isPending) {
    return (
      <section className="workspace shell-loading">
        <LoaderCircle className="spin" />
        <strong>Opening configuration</strong>
      </section>
    );
  }
  if (draftQuery.isError || !draft) {
    const message = draftQuery.error instanceof Error
      ? draftQuery.error.message
      : "The server did not return a configuration draft.";
    return (
      <section className="workspace shell-error" role="alert">
        <AlertTriangle />
        <h2>Configuration is unavailable</h2>
        <p>{message}</p>
        <button onClick={() => void draftQuery.refetch()} type="button">
          Try again
        </button>
      </section>
    );
  }

  return (
    <section
      aria-label={`Edit ${resourceLabel} configuration`}
      aria-busy={busy || actionPending || resourceSyncing}
      className="workspace config-editor"
    >
      <header className="config-toolbar">
        {navigationBackLabel && onNavigateBack ? (
          <button
            aria-label={`Back to ${navigationBackLabel}`}
            className="navigation-back-button"
            onClick={onNavigateBack}
            title={`Back to ${navigationBackLabel}`}
            type="button"
          >
            <ArrowLeft aria-hidden="true" />
          </button>
        ) : null}
        <div className="config-toolbar-title">
          <span>Editing configuration</span>
          {titleRenaming && titleRenameOption ? (
            <form
              className="title-rename-form"
              data-escape-cancel-layer
              onSubmit={(event: FormEvent) => {
                event.preventDefault();
                void resourceRenameRequest.current(
                  titleRenameOption.editTargetId,
                  resourceId,
                  titleRenameName,
                ).then((applied) => {
                  if (applied) setTitleRenaming(false);
                });
              }}
              ref={titleRenameFormRef}
            >
              <label>
                <span className="sr-only">
                  New name for {resourceLabel}
                </span>
                {titleRenameOption.labelPrefix ? (
                  <span
                    aria-hidden="true"
                    className="title-rename-prefix"
                  >
                    {titleRenameOption.labelPrefix}
                  </span>
                ) : null}
                <input
                  aria-label={`New name for ${resourceLabel}`}
                  autoFocus
                  onChange={(event) =>
                    setTitleRenameName(event.target.value)}
                  pattern={titleRenameOption.pattern}
                  required
                  title={titleRenameOption.validationMessage
                    ?? "Dependent workflow references will be updated."}
                  value={titleRenameName}
                />
              </label>
              <button
                aria-label="Apply rename"
                disabled={
                  editorBusy
                  || !titleRenameName.trim()
                  || titleRenameName.trim() === titleRenameOption.currentName
                  || Boolean(titleRenameProblem)
                }
                title="Apply rename"
                type="submit"
              >
                <Check aria-hidden="true" />
              </button>
              <button
                aria-label="Cancel rename"
                disabled={editorBusy}
                onClick={() => setTitleRenaming(false)}
                title="Cancel rename"
                type="button"
              >
                <X aria-hidden="true" />
              </button>
              {titleRenameProblem ? (
                <span className="field-error" role="alert">
                  {titleRenameProblem}
                </span>
              ) : null}
            </form>
          ) : (
            <div className="config-toolbar-heading">
              <h2>Edit {resourceLabel}</h2>
              {titleRenameOption && !removalState ? (
                <button
                  aria-label={`Rename ${resourceLabel}`}
                  className="icon-button title-rename-button"
                  disabled={editorBusy}
                  onClick={() => {
                    setTitleRenameName(titleRenameOption.currentName);
                    setTitleRenaming(true);
                  }}
                  title={`Rename ${resourceLabel}`}
                  type="button"
                >
                  <Pencil aria-hidden="true" />
                </button>
              ) : null}
              {titleIdentityProblem ? (
                <span className="title-identity-error" role="alert">
                  <AlertTriangle aria-hidden="true" />
                  {titleIdentityProblem}
                </span>
              ) : null}
            </div>
          )}
          <span>
            {removalState
              ?? stateSummary
              ?? (draftBaseStale
                ? "Saved configuration changed elsewhere"
                : draft.dirty || hasLocalEdits
                  ? "Unsaved changes"
                  : "Saved configuration")}
          </span>
        </div>
        {!removalState && draft.rawYaml === undefined
          ? <div className="config-toolbar-filters">
          <label>
            <input
              checked={showOptional}
              onChange={(event) =>
                changeOptionalVisibility(event.target.checked)}
              type="checkbox"
            />
            <span>Show optional fields</span>
          </label>
          <label>
            <input
              checked={showExpert}
              onChange={(event) =>
                changeExpertVisibility(event.target.checked)}
              type="checkbox"
            />
            <span>Show expert fields</span>
          </label>
          <label>
            <input
              checked={showDocumentation}
              onChange={(event) => {
                storeDisplayPreferences({
                  showDocumentation: event.target.checked,
                });
                setShowDocumentation(event.target.checked);
              }}
              type="checkbox"
            />
            <span>Show field documentation</span>
          </label>
        </div> : null}
        <div className="config-toolbar-actions">
          <button
            onClick={() => setConnectivityDialogOpen(true)}
            title="Check source, target, and repository connectivity"
            type="button"
          >
            <Network aria-hidden="true" />
            <span>Connectivity</span>
          </button>
          <button
            aria-label="Revert unsaved changes"
            disabled={
              actionPending
              || (
                !draftBaseStale
                && !hasLocalEdits
                && (busy || !draft.dirty)
              )
            }
            onClick={() => void revert()}
            title="Reread the saved configuration and discard unsaved changes"
            type="button"
          >
            <Undo2 />
            <span>Revert</span>
          </button>
          {draft.rawYaml !== undefined ? (
            <button
              disabled={actionPending || busy || !rawYamlDirty}
              onClick={() => void checkRawYaml()}
              type="button"
            >
              <ChevronRight />
              <span>Check YAML</span>
            </button>
          ) : null}
          <button
            aria-label="Save configuration"
            className="primary-button"
            disabled={
              actionPending
              || draftBaseStale
              || (!hasLocalEdits && (busy || !draft.dirty))
            }
            onClick={() => void save()}
            title="Save configuration and continue editing"
            type="button"
          >
            <Save />
            <span>Save</span>
          </button>
        </div>
      </header>
      {problem ? (
        <div className="config-problem" role="alert">
          <AlertTriangle />
          <span>{problem}</span>
          <button onClick={() => setProblem("")} type="button">Dismiss</button>
        </div>
      ) : null}
      {draftBaseStale && !problem ? (
        <div className="config-problem" role="alert">
          <AlertTriangle />
          <span>
            The saved configuration changed elsewhere. Your local changes are
            still here; revert to load the current saved version before saving
            or submitting.
          </span>
        </div>
      ) : null}
      {notice ? (
        <div className="config-notice" role="status">
          <Check aria-hidden="true" />
          <span>{notice}</span>
          <button onClick={() => setNotice("")} type="button">Dismiss</button>
        </div>
      ) : null}
      <ValidityDashboard
        connectivityLoading={connectivity.inventoryLoading}
        connectivityProblem={connectivity.inventoryProblem}
        connectivityStates={scopedConnectivityStates}
        environmentGroups={environmentGroups}
        onCheckConnectivity={(targetIds) => void connectivity.start(targetIds)}
      />
      {connectivityDialogOpen ? (
        <ConnectivityDialog
          loading={connectivity.inventoryLoading}
          onCheck={(targetIds) => void connectivity.start(targetIds)}
          onClose={() => setConnectivityDialogOpen(false)}
          problem={connectivity.inventoryProblem}
          states={connectivity.states}
        />
      ) : null}
      {draft.rawYaml !== undefined ? (
        <section className="raw-config-repair">
          <header>
            <div>
              <span>Configuration repair</span>
              <h2>Workflow YAML</h2>
            </div>
            <span className="field-status status-error">
              {draft.editState.validation.errors.length} {
                draft.editState.validation.errors.length === 1
                  ? "issue"
                  : "issues"
              }
            </span>
          </header>
          <textarea
            aria-label="Workflow YAML"
            onChange={(event) => {
              setRawYamlText(event.target.value);
              setRawYamlDirty(event.target.value !== draft.rawYaml);
            }}
            spellCheck={false}
            value={rawYamlText}
          />
          <div className="raw-config-diagnostics" role="alert">
            {(draft.editState.validation.diagnostics ?? []).map(
              (diagnostic, index) => (
                <p key={`${diagnostic.message}:${index}`}>
                  <AlertTriangle aria-hidden="true" />
                  <span>{diagnostic.message}</span>
                </p>
              ),
            )}
          </div>
        </section>
      ) : removalState ? (
        <section className="config-removal-workspace" role="status">
          <div className="config-removal-icon" aria-hidden="true">
            <Trash2 />
          </div>
          <span className="config-removal-state">{removalState}</span>
          <h2>{resourceLabel}</h2>
          <p>
            This {resourceLabel} is marked for removal from the configuration.
          </p>
          <p>
            It can remain deployed until the saved configuration is submitted
            and the cluster finishes processing the change.
          </p>
          {draft.dirty ? (
            <button
              disabled={busy}
              onClick={() => void revert()}
              type="button"
            >
              <Undo2 aria-hidden="true" />
              Revert unsaved changes
            </button>
          ) : null}
        </section>
      ) : resourceSyncing ? (
        <section className="config-syncing-workspace" role="status">
          <LoaderCircle className="spin" aria-hidden="true" />
          <h2>Preparing {resourceLabel} configuration</h2>
          <p>
            The configuration service is applying the change and generating
            the editable fields.
          </p>
        </section>
      ) : (
        <div className="config-layout">
        <section
          className={[
            "config-table-panel",
            scopeHasValidationError ? "scope-validation-error" : "",
          ].join(" ")}
          onScroll={() => {
            if (removingIds.size > 0 && configTablePanelRef.current) {
              pendingScrollTop.current = configTablePanelRef.current.scrollTop;
            }
            schedulePinnedContextUpdate();
          }}
          ref={configTablePanelRef}
        >
          <header className="config-outline-header">
            <div>
              <strong>
                {scope
                  ? fieldName(scope, findParent(nodes, scope.id))
                  : "Workflow configuration"}
              </strong>
              <span>{rows.length} visible settings</span>
              {scopeTargetId
                && usedIn.length === 0
                && resourceType.toLowerCase().includes("snapshot")
                && !resourceType.toLowerCase().includes("migration") ? (
                <span className="config-outline-note">
                  No snapshot migrations use this snapshot yet; it is
                  still created when the configuration is submitted.
                </span>
              ) : null}
            </div>
            <div className="config-outline-actions">
              {scope?.referenceTargetId
                && scope.referenceTargetId !== scope.id ? (
                <button
                  className="inline-reference-link"
                  onClick={() =>
                    onNavigateEditTarget(scope.referenceTargetId ?? "")}
                  title={`Go to ${scope.referenceLabel ?? "referenced definition"}`}
                  type="button"
                >
                  <Link2 aria-hidden="true" />
                  Defined in {scope.referenceLabel ?? "referenced definition"}
                </button>
              ) : null}
              {usedIn.map((user) => (
                <button
                  className="inline-reference-link"
                  key={user.targetId}
                  onClick={() => onNavigateEditTarget(user.targetId)}
                  title={`Go to ${user.label}`}
                  type="button"
                >
                  <Link2 aria-hidden="true" />
                  Used in {user.label}
                </button>
              ))}
              <button
                className="secondary-button"
                onClick={expandAll}
                type="button"
              >
                <ChevronsDown aria-hidden="true" />
                Expand all
              </button>
              {scope?.removable ? (
                <button
                  aria-label={`Remove ${scope.path.at(-1)}`}
                  className="config-scope-remove danger-button"
                  disabled={busy}
                  onClick={() => void requestRemoval(scope)}
                  title={`Remove ${scope.path.at(-1)}`}
                  type="button"
                >
                  <Trash2 aria-hidden="true" />
                </button>
              ) : null}
            </div>
          </header>
          {pinnedRows.length > 0 ? (
            <nav
              aria-label="Current configuration path"
              className="pinned-config-context"
            >
              {pinnedRows.map(({ node, depth, progress }) => {
                const errorEmphasis = validationErrorEmphasis(node);
                return (
                <button
                  className={[
                    "pinned-context-row",
                    errorEmphasis
                      ? `validation-error-${errorEmphasis}`
                      : "",
                  ].join(" ")}
                  key={node.id}
                  onClick={() => {
                    setSelectedId(node.id);
                    scrollToRow(node.id);
                  }}
                  style={{
                    opacity: progress,
                    transform: `translateY(${(1 - progress) * 6}px)`,
                  }}
                  type="button"
                >
                  <span
                    className="pinned-context-setting"
                    style={{ "--config-depth": depth } as React.CSSProperties}
                  >
                    <ChevronRight aria-hidden="true" />
                    <strong>{fieldName(node, findParent(nodes, node.id))}</strong>
                  </span>
                  <span className="pinned-context-value">
                    {propertyChildren(node).length} {
                      propertyChildren(node).length === 1
                        ? "setting"
                        : "settings"
                    }
                  </span>
            {node.status
              && !["ok", "required"].includes(node.status) ? (
              <span className={`field-status status-${node.status}`}>
                {node.status}
              </span>
                  ) : null}
                </button>
                );
              })}
            </nav>
          ) : null}
          <table aria-label="Configuration fields" className="config-table">
            <colgroup>
              <col className="config-setting-column" />
              <col className="config-value-column" />
              <col className="config-actions-column" />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">Setting</th>
                <th scope="col">Value</th>
                <th className="config-actions-heading" scope="col">
                  <span className="sr-only">Row actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ node, depth }) => {
                const isExpanded = (
                  expanded.has(node.id) && !collapsingIds.has(node.id)
                );
                return (
                  <ConfigPropertyRow
                    applyExternalOperations={applyExternalOperations}
                    busy={busy}
                    commit={commit}
                    contextProgress={
                      pinnedContext.find(({ id }) => id === node.id)?.progress
                      ?? 0
                    }
                    depth={depth}
                    draft={draft}
                    expanded={isExpanded}
                    inserted={insertedIds.has(node.id)}
                    key={node.id}
                    node={node}
                    removing={removingIds.has(node.id)}
                    showDocumentation={showDocumentation}
                    onLocalDirtyChange={markLocalEdit}
                    onNavigateEditTarget={onNavigateEditTarget}
                    onReferenceCreated={(targetId, focus) => {
                      setPendingCreatedTarget({
                        targetId,
                        focus,
                        returnTargetId: initialTargetId
                          ?? "edit:workflowConfiguration",
                        returnLabel: resourceLabel,
                      });
                    }}
                    referenceAdds={referenceAddContexts(
                      node,
                      topLevelAdds,
                      resourceAddOptions,
                    )}
                    onRequestRemoval={(removalNode) => {
                      void requestRemoval(removalNode);
                    }}
                    onRevealChildren={() => {
                      manuallyCollapsedIds.current.delete(node.id);
                      const element = rowElements.current.get(node.id);
                      if (element) {
                        pendingRowAnchor.current = {
                          nodeId: node.id,
                          top: element.getBoundingClientRect().top,
                          draftRevision: draft.draftRevision,
                        };
                      }
                      setExpanded((current) => new Set(current).add(node.id));
                    }}
                    onSelect={() => setSelectedId(node.id)}
                    onSelectAdded={selectAdded}
                    onToggle={() => toggleExpanded(node)}
                    parent={findParent(nodes, node.id)}
                    reportError={setProblem}
                    rowRef={(element) => {
                      if (element) rowElements.current.set(node.id, element);
                      else rowElements.current.delete(node.id);
                    }}
                    selected={selectedId === node.id}
                  />
                );
              })}
            </tbody>
          </table>
          {scopeCommands.length > 0 || scopeActionMessages.length > 0 ? (
            <div className="config-scope-add-actions">
              {scopeCommands.length > 0 ? (
                <div className="config-scope-command-list">
                  {scopeCommands.map((command) => {
                    const commandName = fieldName(command);
                    return (
                      <button
                        aria-label={`Add ${commandName}`}
                        className="primary-button"
                        disabled={
                          busy
                          || Boolean(command.command?.blockedMessage)
                        }
                        key={command.id}
                        onClick={() => {
                          if (!scope) return;
                          void runAddCommand(
                            command,
                            scope,
                            "",
                            commit,
                            selectAdded,
                          );
                        }}
                        title={command.command?.blockedMessage
                          ?? `Add ${commandName}`}
                        type="button"
                      >
                        <Plus aria-hidden="true" />
                        Add {commandName}
                      </button>
                    );
                  })}
                </div>
              ) : null}
              {scopeActionMessages.map((message) => (
                <span className="config-scope-blocker" key={message}>
                  <AlertTriangle aria-hidden="true" />
                  {message}
                </span>
              ))}
            </div>
          ) : null}
        </section>
      </div>)}
      {exitPromptOpen ? (
        <ModalDialog
          className="removal-dialog"
          escapeDisabled={actionPending}
          hideCloseButton
          icon={<AlertTriangle aria-hidden="true" />}
          kicker="Unsaved configuration"
          onClose={() => setExitPromptOpen(false)}
          title="Leave editing?"
          footer={(
            <>
              <button
                disabled={actionPending}
                onClick={() => setExitPromptOpen(false)}
                type="button"
              >
                <Pencil aria-hidden="true" />
                Continue editing
              </button>
              <button
                className="danger-confirm"
                disabled={actionPending}
                onClick={() => void finishExit(false)}
                type="button"
              >
                <Trash2 aria-hidden="true" />
                Discard and exit
              </button>
              <button
                className="primary-button"
                disabled={actionPending || draftBaseStale}
                onClick={() => void finishExit(true)}
                type="button"
              >
                {actionPending
                  ? <LoaderCircle className="spin" aria-hidden="true" />
                  : <Save aria-hidden="true" />}
                Save and exit
              </button>
            </>
          )}
        >
          <p>
            Save these changes before leaving, or discard them and reread
            the saved configuration next time you edit.
          </p>
        </ModalDialog>
      ) : null}
      {pendingRemoval && !pendingRemoval.reviewingTargetId ? (
        <ModalDialog
          closeLabel="Cancel configuration removal"
          escapeDisabled={busy}
          icon={<Trash2 aria-hidden="true" />}
          kicker={
            pendingRemoval.impact?.affected.length
              ? "Referenced configuration"
              : "Configuration removal"
          }
          onClose={() => setPendingRemoval(null)}
          title={(
            <>
              Remove {fieldName(pendingRemoval.node)} from configuration?
            </>
          )}
          footer={(
            <>
              {(pendingRemoval.impact?.affected.length ?? 0) > 0 ? (
                <button
                  className="primary-button"
                  disabled={
                    busy
                    || pendingRemoval.loading
                    || !pendingRemoval.impact
                  }
                  onClick={() =>
                    void confirmRemoval("clear-references")}
                  type="button"
                >
                  <Unlink aria-hidden="true" />
                  Remove and clear references
                </button>
              ) : null}
              <button
                aria-label="Confirm configuration removal"
                className="danger-confirm"
                disabled={
                  busy
                  || pendingRemoval.loading
                  || !pendingRemoval.impact
                }
                onClick={() => void confirmRemoval("delete")}
                type="button"
              >
                <Trash2 aria-hidden="true" />
                {pendingRemoval.impact?.affected.length
                  ? `Remove ${
                    pendingRemoval.impact.affected.length + 1
                  } configuration entries`
                  : "Remove from configuration"}
              </button>
              <button
                disabled={busy}
                onClick={() => setPendingRemoval(null)}
                type="button"
              >
                Cancel
              </button>
            </>
          )}
        >
            {pendingRemoval.loading ? (
              <div className="dialog-loading">
                <LoaderCircle className="spin" aria-hidden="true" />
                Checking dependent configuration
              </div>
            ) : pendingRemoval.error ? (
              <p className="dialog-error">{pendingRemoval.error}</p>
            ) : (
              <>
                {(pendingRemoval.impact?.affected.length ?? 0) > 0 ? (
                  <>
                    <p>
                      Configuration nested inside {
                        fieldName(pendingRemoval.node)
                      } is always removed. Referencing entries can be kept
                      with their affected fields cleared, or removed with
                      their downstream chain.
                    </p>
                    <ul className="removal-impact-list">
                      {pendingRemoval.impact?.affected.map((entry) => (
                        <li key={entry.path.join(".")}>
                          <span className="removal-impact-copy">
                            <strong>{entry.path.join(".")}</strong>
                            <small>
                              {entry.direct
                                ? `Can be kept; clear ${entry.fieldPath.join(".")}`
                                : "Removed only when cascading through its upstream entry"}
                            </small>
                          </span>
                          <button
                            aria-label={`View ${entry.path.join(".")}`}
                            onClick={() => reviewRemovalTarget(entry)}
                            type="button"
                          >
                            <SquareArrowOutUpRight aria-hidden="true" />
                            View
                          </button>
                        </li>
                      ))}
                    </ul>
                  </>
                ) : (
                  <p>
                    This entry will be removed from the working configuration.
                  </p>
                )}
                <div
                  className="action-warning removal-reset-warning"
                  role="note"
                >
                  <AlertTriangle aria-hidden="true" />
                  <span>
                    <strong>Deployed resources are not deleted.</strong>
                    {" "}
                    Resource deletion remains a separate action because this
                    resource and its downstream dependencies may require
                    coordinated cleanup or recovery outside Kubernetes, such
                    as deleting migrated indexes or restoring snapshots.
                  </span>
                </div>
              </>
            )}
        </ModalDialog>
      ) : null}
      {confirmSubmit && draft ? (
        <SubmitConfigDialog
          persistedRevision={submitPersistedRevision ?? undefined}
          onClose={() => {
            setSubmitPersistedRevision(null);
            setConfirmSubmit(false);
          }}
          onSubmitted={() => {
            setLocallyEditedIds(new Set());
            setSubmitPersistedRevision(null);
            setConfirmSubmit(false);
            onSubmitted();
          }}
        />
      ) : null}
    </section>
  );
}
