import {
  useCallback,
  useEffect,
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
  LoaderCircle,
  Pencil,
  Plus,
  RefreshCw,
  Save,
  Trash2,
  Undo2,
  X,
} from "lucide-react";

import {
  ConfigApiError,
  applyEditOperation,
  discardConfigDraft,
  getConfigDraft,
  saveConfigDraft,
  type ConfigDraft,
  type EditNode,
  type EditOperation,
} from "../../api/client";
import { ExternalResourceEditor } from "./ExternalResourceEditor";


interface ConfigEditorProps {
  initialTargetId?: string | null;
  onClose: () => void;
  resourceLabel: string;
}


interface EditRow {
  node: EditNode;
  depth: number;
}


interface PinnedContext {
  id: string;
  progress: number;
}


interface AddContext {
  command: EditNode;
  parent: EditNode;
}


const PINNED_CONTEXT_HEIGHT = 32;
const PINNED_CONTEXT_TRANSITION = 28;


function nodeChildren(node: EditNode): EditNode[] {
  return node.children ?? [];
}


function propertyChildren(node: EditNode): EditNode[] {
  return nodeChildren(node).filter((child) => child.valueKind !== "command");
}


function addCommand(node: EditNode): EditNode | null {
  return nodeChildren(node).find(
    (child) => child.valueKind === "command",
  ) ?? null;
}


function nearestAddContext(
  nodes: EditNode[],
  nodeId: string | null,
): AddContext | null {
  let candidate = findClosestNode(nodes, nodeId);
  while (candidate) {
    const command = addCommand(candidate);
    if (command) return { command, parent: candidate };
    candidate = findParent(nodes, candidate.id);
  }
  return null;
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


function nodeHasIssue(node: EditNode): boolean {
  return ["required", "error", "warning", "gated", "blocked"]
    .includes(node.status ?? "");
}


function visibleNode(
  node: EditNode,
  showOptional: boolean,
  showExpert: boolean,
): boolean {
  if (node.expert && !showExpert && !nodeHasIssue(node)) return false;
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


function pathWithin(path: string[], scopePath: string[]): boolean {
  return scopePath.every((part, index) => path[index] === part);
}


function initialExpanded(nodes: EditNode[]): Set<string> {
  const result = new Set<string>();
  const visit = (node: EditNode) => {
    if (propertyChildren(node).length > 0 && node.collapsed !== true) {
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
}: {
  node: EditNode;
  commit: (operation: EditOperation) => Promise<boolean>;
  busy: boolean;
  onLocalDirtyChange: (dirty: boolean) => void;
}) {
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
        {selectedOption?.description
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
      {selectedOption?.description
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
}: {
  node: EditNode;
  commit: (operation: EditOperation) => Promise<boolean>;
  busy: boolean;
  onRevealChildren: () => void;
}) {
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
      {selected?.description
        ? <span className="inline-field-help">{selected.description}</span>
        : null}
    </div>
  );
}


function BooleanEditor({
  node,
  commit,
  busy,
}: {
  node: EditNode;
  commit: (operation: EditOperation) => Promise<boolean>;
  busy: boolean;
}) {
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
  onAdded,
  onCancel,
  onComplete,
}: {
  node: EditNode;
  parent: EditNode | null;
  commit: (operation: EditOperation) => Promise<boolean>;
  busy: boolean;
  onAdded: (nodeId: string, parentId: string | null) => void;
  onCancel: () => void;
  onComplete: () => void;
}) {
  const requiresName = node.command?.requiresName !== false;
  const label = fieldName(node);
  const [name, setName] = useState("");
  const pattern = hintRecord(node.validation).pattern
    ?? hintRecord(node.inputHint).pattern;
  return (
    <form
      className="field-form inline-command-form"
      onSubmit={(event) => {
        event.preventDefault();
        const trimmedName = name.trim();
        void runAddCommand(
          node,
          parent,
          trimmedName,
          commit,
          onAdded,
        ).then((applied) => {
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
}: {
  node: EditNode;
  commit: (operation: EditOperation) => Promise<boolean>;
  busy: boolean;
}) {
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
  busy,
  commit,
  replaceDraft,
  reportError,
  onLocalDirtyChange,
  onSelectAdded,
  onSelect,
  onRevealChildren,
  onToggle,
  rowRef,
  contextProgress,
}: {
  draft: ConfigDraft;
  node: EditNode;
  parent: EditNode | null;
  depth: number;
  expanded: boolean;
  selected: boolean;
  inserted: boolean;
  busy: boolean;
  commit: (operation: EditOperation) => Promise<boolean>;
  replaceDraft: (promise: Promise<ConfigDraft>) => Promise<boolean>;
  reportError: (message: string) => void;
  onLocalDirtyChange: (nodeId: string, dirty: boolean) => void;
  onSelectAdded: (nodeId: string, parentId: string | null) => void;
  onSelect: () => void;
  onRevealChildren: () => void;
  onToggle: () => void;
  rowRef: (element: HTMLTableRowElement | null) => void;
  contextProgress: number;
}) {
  const [renaming, setRenaming] = useState(false);
  const [adding, setAdding] = useState(false);
  const [newName, setNewName] = useState(node.path.at(-1) ?? "");
  const children = propertyChildren(node);
  const command = addCommand(node);
  const canRename = node.removable === true && parent?.valueKind === "record";
  const canUnset = (
    node.presence === "optional"
    && node.required !== true
    && node.valueKind !== "command"
  );
  const structured = (
    !node.externalRef
    && !command
    && children.length === 0
    && !["scalar", "boolean", "union", "command"].includes(node.valueKind)
  );
  const showDetails = adding
    || (selected && (Boolean(node.externalRef) || structured));
  const name = fieldName(node);

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
    />
  ) : node.valueKind === "boolean" ? (
    <BooleanEditor busy={busy} commit={commit} node={node} />
  ) : node.valueKind === "union" ? (
    <UnionEditor
      busy={busy}
      commit={commit}
      node={node}
      onRevealChildren={onRevealChildren}
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
          selected ? "selected" : "",
          inserted ? "inserted" : "",
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
            <span className="status-dot" aria-hidden="true" />
            <span>
              <strong>{name}</strong>
              {node.description ? <small>{node.description}</small> : null}
            </span>
          </div>
          <div
            className="property-flags"
            style={{ "--config-depth": depth } as React.CSSProperties}
          >
            {node.valueAuthored ? <span>Authored value</span> : null}
            {node.valueDefaulted ? <span>Generated value</span> : null}
            {node.presence ? <span>{node.presence}</span> : null}
            {node.expert ? <span>Expert</span> : null}
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
            {node.effectiveDefault ? (
              <div className="inline-effective-default">
                <strong>
                  {typeof node.effectiveDefault.label === "string"
                    ? node.effectiveDefault.label
                    : "Effective default"}
                </strong>
                {typeof node.effectiveDefault.description === "string"
                  ? <span>{node.effectiveDefault.description}</span>
                  : null}
              </div>
            ) : null}
          </div>
        </td>
        <td className="property-state-cell">
          <span className={`field-status status-${node.status ?? "ok"}`}>
            {node.status ?? "ok"}
          </span>
          <div className="property-actions">
            {command ? (
              <button
                aria-label={`Add ${fieldName(command)}`}
                disabled={busy || Boolean(command.command?.blockedMessage)}
                onClick={() => {
                  onSelect();
                  if (command.command?.requiresName !== false) {
                    setAdding(true);
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
                  ?? `Add ${fieldName(command)}`}
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
                aria-label={`Use default for ${name}`}
                disabled={busy}
                onClick={() => void commit({ op: "unset", path: node.path })}
                title="Use default"
                type="button"
              >
                <Undo2 aria-hidden="true" />
              </button>
            ) : null}
            {node.removable ? (
              <button
                aria-label={`Remove ${node.path.at(-1)}`}
                className="danger-button"
                disabled={busy}
                onClick={() => {
                  if (window.confirm(`Remove ${name}?`)) {
                    void commit({ op: "removeConfig", path: node.path });
                  }
                }}
                title={`Remove ${node.path.at(-1)}`}
                type="button"
              >
                <Trash2 aria-hidden="true" />
              </button>
            ) : null}
          </div>
        </td>
      </tr>
      {renaming || showDetails ? (
        <tr className="config-property-detail">
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
                      pattern={typeof hintRecord(parent?.inputHint).keyPattern === "string"
                        ? String(hintRecord(parent?.inputHint).keyPattern)
                        : undefined}
                      required
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
              ) : adding && command ? (
                <CommandEditor
                  busy={busy}
                  commit={commit}
                  node={command}
                  onAdded={onSelectAdded}
                  onCancel={() => setAdding(false)}
                  onComplete={() => setAdding(false)}
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
  resourceLabel,
}: ConfigEditorProps) {
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
  const [contextAdding, setContextAdding] = useState(false);
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [insertedIds, setInsertedIds] = useState<Set<string>>(() => new Set());
  const [locallyEditedIds, setLocallyEditedIds] = useState<Set<string>>(
    () => new Set(),
  );
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState("");
  const [pinnedContext, setPinnedContext] = useState<PinnedContext[]>([]);
  const configTablePanelRef = useRef<HTMLElement>(null);
  const pinUpdateFrame = useRef<number | null>(null);
  const rowElements = useRef(new Map<string, HTMLTableRowElement>());
  const knownRowIds = useRef<Set<string> | null>(null);
  const skipNextRowTracking = useRef(false);
  const pendingCommit = useRef<Promise<boolean> | null>(null);
  const revealChildrenFor = useRef<{
    parentId: string;
    previousChildIds: Set<string>;
  } | null>(null);

  const draft = draftQuery.data;
  const nodes = useMemo(
    () => draft?.editState.nodes ?? [],
    [draft?.editState.nodes],
  );
  const target = useMemo(
    () => findClosestNode(nodes, activeTargetId),
    [activeTargetId, nodes],
  );
  const scope = useMemo(
    () => editScope(nodes, activeTargetId),
    [activeTargetId, nodes],
  );
  const scopedNodes = useMemo(
    () => scope ? [scope] : nodes,
    [nodes, scope],
  );
  const contextualAdd = useMemo(
    () => nearestAddContext(nodes, scope?.id ?? target?.id ?? null),
    [nodes, scope?.id, target?.id],
  );

  useEffect(() => {
    setActiveTargetId(initialTargetId ?? null);
    setContextAdding(false);
  }, [initialTargetId]);

  useEffect(() => {
    if (!draft) return;
    setExpanded((current) => {
      const retained = new Set(
        [...current].filter((id) => findNode(scopedNodes, id)),
      );
      if (retained.size > 0) return retained;
      knownRowIds.current = allNodeIds(scopedNodes);
      skipNextRowTracking.current = true;
      return initialExpanded(scopedNodes);
    });
    setSelectedId((current) => (
      findNode(scopedNodes, current)
        ? current
        : target?.id ?? scopedNodes[0]?.id ?? null
    ));
  }, [draft, scopedNodes, target]);

  const hasLocalEdits = locallyEditedIds.size > 0;

  useEffect(() => {
    if (!draft?.dirty && !hasLocalEdits) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [draft?.dirty, hasLocalEdits]);

  const rows = useMemo(
    () => treeRows(scopedNodes, expanded, showOptional, showExpert),
    [expanded, scopedNodes, showExpert, showOptional],
  );
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
    pinUpdateFrame.current = window.requestAnimationFrame(() => {
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
    window.addEventListener("resize", schedulePinnedContextUpdate);
    return () => {
      window.removeEventListener("resize", schedulePinnedContextUpdate);
      if (pinUpdateFrame.current !== null) {
        window.cancelAnimationFrame(pinUpdateFrame.current);
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
    const timer = window.setTimeout(() => setInsertedIds(new Set()), 420);
    return () => window.clearTimeout(timer);
  }, [rows]);
  useEffect(() => {
    const pendingReveal = revealChildrenFor.current;
    if (!pendingReveal) return;
    const parentIndex = rows.findIndex(
      ({ node }) => node.id === pendingReveal.parentId,
    );
    const parentRow = rows[parentIndex];
    const childRow = rows[parentIndex + 1];
    if (!parentRow || childRow?.depth !== parentRow.depth + 1) return;
    if (pendingReveal.previousChildIds.has(childRow.node.id)) return;
    revealChildrenFor.current = null;
    const parentElement = rowElements.current.get(parentRow.node.id);
    const childElement = rowElements.current.get(childRow.node.id);
    const scroller = childElement?.closest<HTMLElement>(".config-table-panel");
    if (scroller?.scrollTo && parentElement) {
      const scrollerTop = scroller.getBoundingClientRect().top;
      const parentTop = parentElement.getBoundingClientRect().top;
      scroller.scrollTo({
        behavior: "smooth",
        top: Math.max(0, scroller.scrollTop + parentTop - scrollerTop - 84),
      });
    } else {
      childElement?.scrollIntoView?.({ block: "nearest" });
    }
  }, [rows]);
  const scopedDiagnostics = useMemo(
    () => (draft?.editState.validation.diagnostics ?? []).filter(
      (diagnostic) => (
        !scope
        || (diagnostic.path ?? []).length === 0
        || pathWithin(diagnostic.path ?? [], scope.path)
      ),
    ),
    [draft?.editState.validation.diagnostics, scope],
  );
  const scopeNeedsAttention = rows.some(({ node }) => nodeHasIssue(node))
    || scopedDiagnostics.length > 0;

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
    return pending ? pending : true;
  };

  const save = async () => {
    if (!await waitForPendingCommit()) return;
    const current = queryClient.getQueryData<ConfigDraft>(["config-draft"]);
    if (!current?.dirty) return;
    await replaceDraft(saveConfigDraft(current.draftRevision));
    setLocallyEditedIds(new Set());
  };

  const discard = async () => {
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

  const close = async () => {
    if (!await waitForPendingCommit()) return;
    const current = queryClient.getQueryData<ConfigDraft>(["config-draft"]);
    if (
      (current?.dirty || hasLocalEdits)
      && !window.confirm("Discard this unsaved browser draft and close configuration?")
    ) {
      return;
    }
    if (current?.dirty && !await discard()) return;
    onClose();
  };

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
          <span>Configuration</span>
          <h2>Edit {resourceLabel}</h2>
          <span>
            {draft.dirty || hasLocalEdits
              ? "Unsaved changes"
              : "Saved configuration"}
          </span>
        </div>
        <div className="config-toolbar-filters">
          <label>
            <input
              checked={showOptional}
              onChange={(event) => setShowOptional(event.target.checked)}
              type="checkbox"
            />
            Show optional fields
          </label>
          <label>
            <input
              checked={showExpert}
              onChange={(event) => setShowExpert(event.target.checked)}
              type="checkbox"
            />
            Show expert fields
          </label>
        </div>
        <div className="config-toolbar-actions">
          {contextualAdd ? (
            <button
              aria-expanded={contextAdding || undefined}
              aria-label={`Add ${fieldName(contextualAdd.command)}`}
              disabled={
                busy
                || Boolean(contextualAdd.command.command?.blockedMessage)
              }
              onClick={() => {
                if (contextualAdd.command.command?.requiresName !== false) {
                  setContextAdding((current) => !current);
                } else {
                  void runAddCommand(
                    contextualAdd.command,
                    contextualAdd.parent,
                    "",
                    commit,
                    selectContextAdded,
                  );
                }
              }}
              title={contextualAdd.command.command?.blockedMessage
                ?? `Add ${fieldName(contextualAdd.command)}`}
              type="button"
            >
              <Plus />
              <span>Add {fieldName(contextualAdd.command)}</span>
            </button>
          ) : null}
          <button
            aria-label="Reload draft"
            disabled={busy || draftQuery.isFetching}
            onClick={() => void draftQuery.refetch()}
            title="Reload draft"
            type="button"
          >
            <RefreshCw className={draftQuery.isFetching ? "spin" : ""} />
            <span>Reload draft</span>
          </button>
          <button
            aria-label="Discard changes"
            disabled={!hasLocalEdits && (busy || !draft.dirty)}
            onClick={() => void discard()}
            title="Discard changes"
            type="button"
          >
            <Undo2 />
            <span>Discard changes</span>
          </button>
          <button
            aria-label="Save changes"
            className="primary-button"
            disabled={!hasLocalEdits && (busy || !draft.dirty)}
            onClick={() => void save()}
            title="Save changes"
            type="button"
          >
            <Save />
            <span>Save changes</span>
          </button>
          <button
            aria-label="Close configuration"
            className="icon-button"
            onClick={() => void close()}
            type="button"
          >
            <X />
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
      {contextAdding && contextualAdd ? (
        <section
          aria-label={`Add ${fieldName(contextualAdd.command)}`}
          className="config-context-add"
        >
          <header>
            <strong>Add {fieldName(contextualAdd.command)}</strong>
            <span>{fieldName(contextualAdd.parent)}</span>
          </header>
          <CommandEditor
            busy={busy}
            commit={commit}
            node={contextualAdd.command}
            onAdded={selectContextAdded}
            onCancel={() => setContextAdding(false)}
            onComplete={() => setContextAdding(false)}
            parent={contextualAdd.parent}
          />
        </section>
      ) : null}
      <div className="config-layout">
        <section
          className="config-table-panel"
          onScroll={schedulePinnedContextUpdate}
          ref={configTablePanelRef}
        >
          <header className="config-outline-header">
            <strong>{scope?.label ?? "Workflow configuration"}</strong>
            <span>{rows.length} visible settings</span>
          </header>
          {pinnedRows.length > 0 ? (
            <nav
              aria-label="Current configuration path"
              className="pinned-config-context"
            >
              {pinnedRows.map(({ node, depth, progress }) => (
                <button
                  className="pinned-context-row"
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
              ))}
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
                const isExpanded = expanded.has(node.id);
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
                    onLocalDirtyChange={markLocalEdit}
                    onRevealChildren={() => {
                      revealChildrenFor.current = {
                        parentId: node.id,
                        previousChildIds: new Set(
                          propertyChildren(node).map((child) => child.id),
                        ),
                      };
                      setExpanded((current) => new Set(current).add(node.id));
                    }}
                    onSelect={() => setSelectedId(node.id)}
                    onSelectAdded={selectAdded}
                    onToggle={() => {
                      setExpanded((current) => {
                        const next = new Set(current);
                        if (next.has(node.id)) next.delete(node.id);
                        else next.add(node.id);
                        return next;
                      });
                    }}
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
        </section>
        <aside className="config-diagnostics">
          <header>
            <AlertTriangle aria-hidden="true" />
            <h3>Validation</h3>
          </header>
          <strong>
            {scopeNeedsAttention
              ? "This configuration needs attention"
              : "This configuration is valid"}
          </strong>
          {!scope ? (draft.editState.validation.errors ?? []).map((error) => (
            <p key={error}>{error}</p>
          )) : null}
          {scopedDiagnostics.map(
            (diagnostic, index) => (
              <button
                key={`${diagnostic.message}-${index}`}
                onClick={() => {
                  const diagnosticTarget = scopedNodes
                    .flatMap(function flatten(node): EditNode[] {
                      return [node, ...nodeChildren(node).flatMap(flatten)];
                    })
                    .find((node) => (
                      node.path.join(".") === (diagnostic.path ?? []).join(".")
                    ));
                  if (diagnosticTarget) setSelectedId(diagnosticTarget.id);
                }}
                type="button"
              >
                <span>{diagnostic.severity}</span>
                {diagnostic.message}
              </button>
            ),
          )}
          {draft.editState.provenance.lossy ? (
            <div className="provenance-warning">
              <strong>Projection warnings</strong>
              {(draft.editState.provenance.warnings ?? []).map((warning) => (
                <p key={warning}>{warning}</p>
              ))}
            </div>
          ) : null}
        </aside>
      </div>
    </section>
  );
}
