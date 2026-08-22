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
  ChevronDown,
  ChevronRight,
  ChevronsDown,
  LoaderCircle,
  Pencil,
  Plus,
  Save,
  Send,
  Trash2,
  Undo2,
} from "lucide-react";

import {
  ConfigApiError,
  applyEditOperation,
  closeConfigDraft,
  discardConfigDraft,
  getConfigDraft,
  getConfigRemovalImpact,
  saveConfigDraft,
  type ConfigDraft,
  type ConfigRemovalImpact,
  type EditNode,
  type EditOperation,
} from "../../api/client";
import { useEscapeCancel } from "../../hooks/useEscapeCancel";
import { SubmitConfigDialog } from "../submission/SubmitConfigDialog";
import { ExternalResourceEditor } from "./ExternalResourceEditor";
import {
  pendingResourceAddition,
  pendingResourceRename,
  resourceAdditionIdentity,
  resourceAddPlacement,
  type PendingResourceAddition,
  type PendingResourceRename,
  type ResourceAddController,
  type ResourceRenameOption,
} from "./resourceAdds";


interface ConfigEditorProps {
  initialTargetId?: string | null;
  onClose: () => void;
  onExitReady: (handler: (() => void) | null) => void;
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
  onSubmitted: () => void;
  removalState?: string | null;
  resourceLabel: string;
  resourceSyncing?: boolean;
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


interface PendingRemoval {
  node: EditNode;
  impact: ConfigRemovalImpact | null;
  loading: boolean;
  error: string;
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


function topLevelAddContexts(nodes: EditNode[]): AddContext[] {
  const result: AddContext[] = [];
  const visit = (node: EditNode) => {
    if (resourceAddPlacement(node.path)) {
      const command = addCommand(node);
      if (command) result.push({ command, parent: node });
    }
    propertyChildren(node).forEach(visit);
  };
  nodes.forEach(visit);
  return result;
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
  return (
    path.length === 5
    && path[0] === "sourceClusters"
    && path[2] === "snapshotInfo"
    && ["repos", "snapshots"].includes(path[3])
  );
}


const KUBERNETES_NAME_PATTERN =
  String.raw`^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$`;
const KUBERNETES_NAME_MESSAGE =
  "Use a valid Kubernetes DNS name: lowercase letters, numbers, '-' or '.', starting and ending with an alphanumeric character.";


function resourceRenameOptions(nodes: EditNode[]): ResourceRenameOption[] {
  const result: ResourceRenameOption[] = [];
  const visit = (node: EditNode) => {
    const placement = resourceAddPlacement(node.path);
    if (placement) {
      const collectionDepth = node.path.length;
      propertyChildren(node).forEach((child, index) => {
        if (
          child.path.length !== collectionDepth + 1
          || !renameableConfigPath(child.path)
        ) {
          return;
        }
        const currentName = child.path.at(-1) ?? "";
        const parentHint = hintRecord(node.inputHint);
        const childValidation = hintRecord(child.validation);
        const kubernetesBacked = (
          child.path.length === 3 && child.path[0] === "traffic"
        );
        const identity = resourceAdditionIdentity(
          placement,
          currentName,
          index,
        );
        result.push({
          currentName,
          editTargetId: child.id,
          label: identity.label,
          path: child.path,
          pattern: kubernetesBacked
            ? KUBERNETES_NAME_PATTERN
            : typeof parentHint.keyPattern === "string"
              ? parentHint.keyPattern
              : typeof childValidation.pattern === "string"
                ? childValidation.pattern
                : undefined,
          placement,
          resourceId: identity.id,
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
    propertyChildren(node).forEach(visit);
  };
  nodes.forEach(visit);
  return result;
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


function fieldName(node: EditNode): string {
  const prefix = node.label.split(":", 1)[0].replace(/^\+ Add /, "");
  return prefix || node.path.at(-1) || "Configuration";
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


function initialExpanded(nodes: EditNode[]): Set<string> {
  const result = new Set<string>();
  const visit = (node: EditNode) => {
    if (
      propertyChildren(node).length > 0
      && (node.collapsed !== true || nodeTreeHasAuthoredValue(node))
    ) {
      result.add(node.id);
    }
    propertyChildren(node).forEach(visit);
  };
  nodes.forEach((node) => {
    if (propertyChildren(node).length > 0) result.add(node.id);
    propertyChildren(node).forEach(visit);
  });
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


function ScalarEditor({
  node,
  commit,
  busy,
  onLocalDirtyChange,
  showDocumentation,
}: Readonly<{
  node: EditNode;
  commit: (operation: EditOperation) => Promise<boolean>;
  busy: boolean;
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
  const noReferenceChoices = isReference && !allowCustom && options.length === 0;
  const [value, setValue] = useState(authoredValue);
  const [applying, setApplying] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const pattern = hintRecord(node.validation).pattern;
  const selectedOption = options.find(
    (option) => String(option.value) === value,
  );

  if (options.length > 0 && !allowCustom) {
    return (
      <div className="inline-choice-editor">
        <label>
          <span className="sr-only">{name}</span>
          <select
            aria-label={name}
            disabled={busy || applying}
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
            value={value}
          >
            {selectedOption ? null : (
              <option disabled value="">Select a value</option>
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
    if (applying || noReferenceChoices) return false;
    if (value === authoredValue) {
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
          disabled={noReferenceChoices || applying}
          list={options.length > 0 || examples.length > 0
            ? `${node.id}-choices`
            : undefined}
          onBlur={() => void syncValue()}
          onChange={(event) => {
            const nextValue = event.target.value;
            setValue(nextValue);
            onLocalDirtyChange(nextValue !== authoredValue);
          }}
          onKeyDown={(event) => {
            if (event.key !== "Enter") return;
            event.preventDefault();
            void syncValue();
          }}
          pattern={typeof pattern === "string" ? pattern : undefined}
          ref={inputRef}
          required={node.required === true}
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
      {showDocumentation && selectedOption?.description
        ? <p className="field-help">{selectedOption.description}</p>
        : null}
      {noReferenceChoices ? (
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
  const pattern = hintRecord(node.validation).pattern
    ?? hintRecord(node.inputHint).pattern;
  return (
    <form
      className="field-form inline-command-form"
      onKeyDown={(event) => {
        if (event.key !== "Escape" || busy) return;
        event.preventDefault();
        event.stopPropagation();
        onCancel();
      }}
      onSubmit={(event) => {
        event.preventDefault();
        const trimmedName = name.trim();
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
    >
      {requiresName ? (
        <label>
          <span className="sr-only">{label} name</span>
          <input
            aria-label={`${label} name`}
            autoFocus
            onChange={(event) => setName(event.target.value)}
            pattern={typeof pattern === "string" ? pattern : undefined}
            required
            value={name}
          />
        </label>
      ) : null}
      <button
        disabled={
          busy
          || Boolean(node.command?.blockedMessage)
          || (requiresName && !name.trim())
        }
        type="submit"
      >
        <Plus aria-hidden="true" />
        Create {label}
      </button>
      <button disabled={busy} onClick={onCancel} type="button">Cancel</button>
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
  const nextIndex = Array.isArray(parent?.value)
    ? parent.value.length
    : propertyChildren(parent ?? node).length;
  const addedPath = [
    ...node.path,
    requiresName ? name : String(nextIndex),
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
  replaceDraft,
  reportError,
  onLocalDirtyChange,
  onRequestRemoval,
  onSelectAdded,
  onSelect,
  onRevealChildren,
  onToggle,
  rowRef,
  contextProgress,
}: Readonly<{
  draft: ConfigDraft;
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
  replaceDraft: (promise: Promise<ConfigDraft>) => Promise<boolean>;
  reportError: (message: string) => void;
  onLocalDirtyChange: (nodeId: string, dirty: boolean) => void;
  onRequestRemoval: (node: EditNode) => void;
  onSelectAdded: (nodeId: string, parentId: string | null) => void;
  onSelect: () => void;
  onRevealChildren: () => void;
  onToggle: () => void;
  rowRef: (element: HTMLTableRowElement | null) => void;
  contextProgress: number;
}>) {
  const [renaming, setRenaming] = useState(false);
  const [addingCommandId, setAddingCommandId] = useState<string | null>(null);
  const [newName, setNewName] = useState(node.path.at(-1) ?? "");
  const children = propertyChildren(node);
  const commands = addCommands(node);
  const topLevelResourceCommand = resourceAddPlacement(node.path)
    ? commands[0] ?? null
    : null;
  const inlineCommands = topLevelResourceCommand ? [] : commands;
  const addingCommand = commands.find(
    (candidate) => candidate.id === addingCommandId,
  ) ?? null;
  const canRename = renameableConfigPath(node.path);
  const canUnset = (
    node.presence === "optional"
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
  const showDetails = Boolean(addingCommand)
    || (selected && (Boolean(node.externalRef) || structured));
  const name = fieldName(node);
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
  const fieldDescription = node.description ?? selectedDescription;
  const generatedTitle = [
    "Generated from defaults or related configuration, not explicitly set here.",
    effectiveDefaultLabel
      ? `Effective default: ${effectiveDefaultLabel}.`
      : "",
    effectiveDefaultDescription,
  ].filter(Boolean).join(" ");

  const valueEditor = node.externalRef ? (
    <button
      className="inline-resource-button"
      onClick={onSelect}
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
                    <span title={changeTitle}>
                      {node.draftChange.kind === "added" ? "Added" : "Changed"}
                    </span>
                  ) : null}
                  {node.valueAuthored ? (
                    <span title="Explicitly set in the pending configuration.">
                      Authored
                    </span>
                  ) : null}
                  {node.valueDefaulted ? (
                    <span title={generatedTitle}>Generated</span>
                  ) : null}
                  {node.presence ? <span>{node.presence}</span> : null}
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
        <td className="property-state-cell">
          <div className="property-state-content">
            <span className={`field-status status-${node.status ?? "ok"}`}>
              {node.status ?? "ok"}
            </span>
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
            {canUnset ? (
              <button
                aria-label={`Revert ${name} to default`}
                disabled={busy}
                onClick={() => void commit({ op: "unset", path: node.path })}
                title="Revert to default"
                type="button"
              >
                <Undo2 aria-hidden="true" />
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
                  onKeyDown={(event) => {
                    if (event.key !== "Escape") return;
                    event.preventDefault();
                    event.stopPropagation();
                    setRenaming(false);
                  }}
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
                >
                  <label>
                    <span>Configuration name</span>
                    <input
                      aria-label="Configuration name"
                      autoFocus
                      onChange={(event) => setNewName(event.target.value)}
                      pattern={
                        node.path.length === 3 && node.path[0] === "traffic"
                          ? KUBERNETES_NAME_PATTERN
                          : typeof hintRecord(parent?.inputHint).keyPattern
                            === "string"
                            ? String(hintRecord(parent?.inputHint).keyPattern)
                            : undefined
                      }
                      required
                      title={
                        node.path.length === 3 && node.path[0] === "traffic"
                          ? `${KUBERNETES_NAME_MESSAGE} Dependent workflow references will be updated.`
                          : "Dependent workflow references will be updated."
                      }
                      value={newName}
                    />
                  </label>
                  <button disabled={busy || !newName.trim()} type="submit">
                    Apply rename
                  </button>
                  <button onClick={() => setRenaming(false)} type="button">
                    Cancel
                  </button>
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
              ) : node.externalRef ? (
                <ExternalResourceEditor
                  busy={busy}
                  draft={draft}
                  node={node}
                  replaceDraft={replaceDraft}
                  reportError={reportError}
                />
              ) : structured ? (
                <StructuredEditor busy={busy} commit={commit} node={node} />
              ) : null}
            </div>
          </td>
        </tr>
      ) : null}
    </>
  );
}


export function ConfigEditor({
  initialTargetId,
  onClose,
  onExitReady,
  onResourceAddStarted,
  onResourceAddSettled,
  onResourceRenameStarted,
  onResourceRenameSettled,
  onResourceAddsReady,
  onSubmitted,
  removalState,
  resourceLabel,
  resourceSyncing = false,
}: Readonly<ConfigEditorProps>) {
  const queryClient = useQueryClient();
  const draftQuery = useQuery({
    queryKey: ["config-draft"],
    queryFn: getConfigDraft,
    staleTime: Infinity,
  });
  const [selectedId, setSelectedId] = useState<string | null>(
    initialTargetId ?? null,
  );
  const [activeTargetId, setActiveTargetId] = useState<string | null>(
    initialTargetId ?? null,
  );
  const [showOptional, setShowOptional] = useState(false);
  const [showExpert, setShowExpert] = useState(false);
  const [showDocumentation, setShowDocumentation] = useState(false);
  const [renderOptional, setRenderOptional] = useState(false);
  const [renderExpert, setRenderExpert] = useState(false);
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [insertedIds, setInsertedIds] = useState<Set<string>>(() => new Set());
  const [removingIds, setRemovingIds] = useState<Set<string>>(
    () => new Set(),
  );
  const [collapsingIds, setCollapsingIds] = useState<Set<string>>(
    () => new Set(),
  );
  const [scrollRetention, setScrollRetention] = useState(0);
  const [locallyEditedIds, setLocallyEditedIds] = useState<Set<string>>(
    () => new Set(),
  );
  const [busy, setBusy] = useState(false);
  const [actionPending, setActionPending] = useState(false);
  const [problem, setProblem] = useState("");
  const [pendingRemoval, setPendingRemoval] =
    useState<PendingRemoval | null>(null);
  const [confirmSubmit, setConfirmSubmit] = useState(false);
  const [exitPromptOpen, setExitPromptOpen] = useState(false);
  const [pinnedContext, setPinnedContext] = useState<PinnedContext[]>([]);
  const exitDialogRef = useEscapeCancel<HTMLElement>(
    () => setExitPromptOpen(false),
    actionPending || !exitPromptOpen,
  );
  const removalDialogRef = useEscapeCancel<HTMLElement>(
    () => setPendingRemoval(null),
    busy || !pendingRemoval,
  );
  const configTablePanelRef = useRef<HTMLElement>(null);
  const pinUpdateFrame = useRef<number | null>(null);
  const rowElements = useRef(new Map<string, HTMLTableRowElement>());
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

  const draft = draftQuery.data;
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
  const expansionScopeId = scope?.id
    ?? (globalTarget ? "edit:workflowConfiguration" : "edit:root");
  const scopedNodes = useMemo(
    () => (
      activeTargetId && !target && !globalTarget
        ? []
        : scope ? propertyChildren(scope) : nodes
    ),
    [activeTargetId, globalTarget, nodes, scope, target],
  );
  const topLevelAdds = useMemo(
    () => topLevelAddContexts(nodes),
    [nodes],
  );
  const resourceAddOptions = useMemo(
    () => topLevelAdds.flatMap((context) => {
      const placement = resourceAddPlacement(context.parent.path);
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

  useEffect(() => {
    setActiveTargetId(initialTargetId ?? null);
  }, [initialTargetId]);

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
        const initiallyExpanded = initialExpanded(scopedNodes);
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
        children.forEach(visit);
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
    setScrollRetention(0);
    pendingScrollTop.current = null;
    pendingRowAnchor.current = null;
  }, [expansionScopeId]);

  const hasLocalEdits = locallyEditedIds.size > 0;

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
  const retainScrollPosition = useCallback(() => {
    const panel = configTablePanelRef.current;
    if (!panel) return;
    pendingScrollTop.current = panel.scrollTop;
    setScrollRetention((current) => Math.max(current, panel.scrollTop));
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
    setShowOptional(next);
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
          ) {
            nextExpanded.add(id);
          }
        });
        return nextExpanded;
      });
      return;
    }
    const nextIds = new Set(
      treeRows(scopedNodes, expanded, false, renderExpert)
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
    });
  };
  const changeExpertVisibility = (next: boolean) => {
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
    setCollapsingIds((current) => new Set(current).add(node.id));
    const transition = beginRowExit(exiting, () => {
      collapseTransitions.current.delete(node.id);
      setExpanded((current) => {
        const next = new Set(current);
        next.delete(node.id);
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
  const replaceDraft = async (promise: Promise<ConfigDraft>) => {
    setBusy(true);
    setProblem("");
    try {
      const next = await promise;
      queryClient.setQueryData(["config-draft"], next);
      return true;
    } catch (error) {
      if (error instanceof ConfigApiError && error.current) {
        queryClient.setQueryData(["config-draft"], error.current);
      }
      setProblem(error instanceof Error ? error.message : String(error));
      return false;
    } finally {
      setBusy(false);
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
      const current = queryClient.getQueryData<ConfigDraft>(["config-draft"]);
      if (!current) return false;
      return replaceDraft(applyEditOperation(
        current.draftRevision,
        operation,
      ));
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

  const save = async () => {
    setActionPending(true);
    try {
      if (!await waitForPendingCommit()) return;
      const current = queryClient.getQueryData<ConfigDraft>(["config-draft"]);
      if (!current?.dirty) return;
      if (await replaceDraft(saveConfigDraft(current.draftRevision))) {
        setLocallyEditedIds(new Set());
      }
    } finally {
      setActionPending(false);
    }
  };

  const revert = async () => {
    setActionPending(true);
    try {
      if (!await waitForPendingCommit()) return false;
      const current = queryClient.getQueryData<ConfigDraft>(["config-draft"]);
      if (!current?.dirty) {
        setLocallyEditedIds(new Set());
        return true;
      }
      const discarded = await replaceDraft(discardConfigDraft(
        current.draftRevision,
      ));
      if (discarded) setLocallyEditedIds(new Set());
      return discarded;
    } finally {
      setActionPending(false);
    }
  };

  const openSubmitReview = async () => {
    if (!await waitForPendingCommit()) return;
    const current = queryClient.getQueryData<ConfigDraft>(["config-draft"]);
    if (!current) return;
    setConfirmSubmit(true);
  };

  const requestRemoval = async (node: EditNode) => {
    const current = queryClient.getQueryData<ConfigDraft>(["config-draft"]);
    if (!current) return;
    setPendingRemoval({
      node,
      impact: null,
      loading: true,
      error: "",
    });
    try {
      const impact = await getConfigRemovalImpact(
        current.draftRevision,
        node.path,
      );
      setPendingRemoval({
        node,
        impact,
        loading: false,
        error: "",
      });
    } catch (error) {
      setPendingRemoval({
        node,
        impact: null,
        loading: false,
        error: error instanceof Error ? error.message : String(error),
      });
    }
  };

  const confirmRemoval = async () => {
    if (!pendingRemoval?.impact) return;
    const operation = {
      op: "removeConfig" as const,
      path: pendingRemoval.node.path,
    };
    const applied = await commit(operation);
    if (applied) setPendingRemoval(null);
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
    const nextIndex = Array.isArray(context.parent.value)
      ? context.parent.value.length
      : propertyChildren(context.parent).length;
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
    if (!option || !newName.trim() || newName.trim() === option.currentName) {
      return Promise.resolve(false);
    }
    const rename = pendingResourceRename(option, resourceId, newName.trim());
    onResourceRenameStarted(rename);
    return commit({
      op: "renameConfig",
      path: option.path,
      newName: newName.trim(),
    }).then((applied) => {
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
      let current = queryClient.getQueryData<ConfigDraft>(["config-draft"]);
      if (!current) return;
      try {
        if (saveChanges && current.dirty) {
          current = await saveConfigDraft(current.draftRevision);
          queryClient.setQueryData(["config-draft"], current);
        }
        await closeConfigDraft(current.draftRevision);
      } catch (error) {
        if (error instanceof ConfigApiError && error.current) {
          queryClient.setQueryData(["config-draft"], error.current);
        }
        setExitPromptOpen(false);
        setProblem(error instanceof Error ? error.message : String(error));
        return;
      }
      queryClient.removeQueries({ queryKey: ["config-draft"] });
      setLocallyEditedIds(new Set());
      setExitPromptOpen(false);
      onClose();
    } finally {
      setActionPending(false);
    }
  };

  const close = async () => {
    if (!await waitForPendingCommit()) return;
    const current = queryClient.getQueryData<ConfigDraft>(["config-draft"]);
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
      className="workspace config-editor"
    >
      <header className="config-toolbar">
        <div className="config-toolbar-title">
          <span>Editing configuration</span>
          <h2>Edit {resourceLabel}</h2>
          <span>
            {removalState ?? (draft.dirty || hasLocalEdits
              ? "Unsaved changes"
              : "Saved configuration")}
          </span>
        </div>
        {!removalState ? <div className="config-toolbar-filters">
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
              onChange={(event) =>
                setShowDocumentation(event.target.checked)}
              type="checkbox"
            />
            <span>Show field documentation</span>
          </label>
        </div> : null}
        <div className="config-toolbar-actions">
          <button
            aria-label="Revert unsaved changes"
            disabled={
              actionPending
              || (!hasLocalEdits && (busy || !draft.dirty))
            }
            onClick={() => void revert()}
            title="Reread the saved configuration and discard unsaved changes"
            type="button"
          >
            <Undo2 />
            <span>Revert</span>
          </button>
          <button
            aria-label="Save configuration"
            className="primary-button"
            disabled={
              actionPending
              || (!hasLocalEdits && (busy || !draft.dirty))
            }
            onClick={() => void save()}
            title="Save configuration and continue editing"
            type="button"
          >
            <Save />
            <span>Save</span>
          </button>
          <button
            aria-label="Save and submit"
            className="submit-button"
            disabled={
              actionPending
              || (busy && !hasLocalEdits)
              || draft.editState.validation.valid === false
            }
            onClick={() => void openSubmitReview()}
            title={
              draft.editState.validation.valid === false
                ? "Resolve validation errors before submitting"
                : "Save configuration, submit the workflow, and leave editing"
            }
            type="button"
          >
            <Send />
            <span>Save and submit</span>
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
      {removalState ? (
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
          className="config-table-panel"
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
              <strong>{scope?.label ?? "Workflow configuration"}</strong>
              <span>{rows.length} visible settings</span>
            </div>
            <div className="config-outline-actions">
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
                    <strong>{fieldName(node)}</strong>
                  </span>
                  <span className="pinned-context-value">
                    {propertyChildren(node).length} {
                      propertyChildren(node).length === 1
                        ? "setting"
                        : "settings"
                    }
                  </span>
                  <span className={`field-status status-${node.status ?? "ok"}`}>
                    {node.status ?? "ok"}
                  </span>
                </button>
                );
              })}
            </nav>
          ) : null}
          <table aria-label="Configuration fields" className="config-table">
            <colgroup>
              <col className="config-setting-column" />
              <col className="config-value-column" />
              <col className="config-state-column" />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">Setting</th>
                <th scope="col">Value</th>
                <th scope="col">State</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ node, depth }) => {
                const isExpanded = (
                  expanded.has(node.id) && !collapsingIds.has(node.id)
                );
                return (
                  <ConfigPropertyRow
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
                    replaceDraft={replaceDraft}
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
          <div
            aria-hidden="true"
            className="config-scroll-space"
            style={{
              "--config-scroll-retention": `${scrollRetention}px`,
            } as React.CSSProperties}
          />
        </section>
      </div>)}
      {exitPromptOpen ? (
        <div className="modal-backdrop">
          <section
            aria-labelledby="exit-edit-dialog-title"
            aria-modal="true"
            className="confirmation-dialog"
            data-escape-cancel-layer
            ref={exitDialogRef}
            role="dialog"
          >
            <header>
              <AlertTriangle aria-hidden="true" />
              <div>
                <span>Unsaved configuration</span>
                <h2 id="exit-edit-dialog-title">Leave editing?</h2>
              </div>
            </header>
            <p>
              Save these changes before leaving, or discard them and reread
              the saved configuration next time you edit.
            </p>
            <footer>
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
                disabled={actionPending}
                onClick={() => void finishExit(true)}
                type="button"
              >
                {actionPending
                  ? <LoaderCircle className="spin" aria-hidden="true" />
                  : <Save aria-hidden="true" />}
                Save and exit
              </button>
            </footer>
          </section>
        </div>
      ) : null}
      {pendingRemoval ? (
        <div className="modal-backdrop">
          <section
            aria-labelledby="removal-dialog-title"
            aria-modal="true"
            className="confirmation-dialog"
            data-escape-cancel-layer
            ref={removalDialogRef}
            role="dialog"
          >
            <header>
              <Trash2 aria-hidden="true" />
              <div>
                <span>Configuration removal</span>
                <h2 id="removal-dialog-title">
                  Remove {fieldName(pendingRemoval.node)}?
                </h2>
              </div>
            </header>
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
                      This removal also affects the following configuration
                      entries:
                    </p>
                    <ul className="removal-impact-list">
                      {pendingRemoval.impact?.affected.map((entry) => (
                        <li key={entry.path.join(".")}>
                          <button
                            onClick={() => {
                              const targetId = `edit:${entry.path.join(".")}`;
                              setActiveTargetId(targetId);
                              setSelectedId(targetId);
                              setPendingRemoval(null);
                            }}
                            type="button"
                          >
                            {entry.path.join(".")}
                          </button>
                          <span>{entry.reason}</span>
                        </li>
                      ))}
                    </ul>
                  </>
                ) : (
                  <p>
                    This entry will be removed from the working configuration.
                  </p>
                )}
              </>
            )}
            <footer>
              <button
                disabled={busy}
                onClick={() => setPendingRemoval(null)}
                type="button"
              >
                Cancel
              </button>
              <button
                aria-label="Confirm removal"
                className="danger-confirm"
                disabled={
                  busy
                  || pendingRemoval.loading
                  || !pendingRemoval.impact
                }
                onClick={() => void confirmRemoval()}
                type="button"
              >
                <Trash2 aria-hidden="true" />
                Remove
              </button>
            </footer>
          </section>
        </div>
      ) : null}
      {confirmSubmit && draft ? (
        <SubmitConfigDialog
          draftRevision={draft.draftRevision}
          onClose={() => setConfirmSubmit(false)}
          onSubmitted={() => {
            setLocallyEditedIds(new Set());
            setConfirmSubmit(false);
            onSubmitted();
          }}
        />
      ) : null}
    </section>
  );
}
