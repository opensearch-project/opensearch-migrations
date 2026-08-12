import {
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
}


interface EditRow {
  node: EditNode;
  depth: number;
}


function nodeChildren(node: EditNode): EditNode[] {
  return node.children ?? [];
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
    return nodeChildren(node).some((child) =>
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


function initialExpanded(nodes: EditNode[]): Set<string> {
  const result = new Set<string>();
  const visit = (node: EditNode) => {
    if (nodeChildren(node).length > 0 && node.collapsed !== true) {
      result.add(node.id);
    }
    nodeChildren(node).forEach(visit);
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
}: {
  node: EditNode;
  commit: (operation: EditOperation) => Promise<boolean>;
  busy: boolean;
}) {
  const name = fieldName(node);
  const options = hintOptions(node);
  const examples = hintExamples(node);
  const hint = hintRecord(node.inputHint);
  const isReference = hint.kind === "reference";
  const allowCustom = hint.allowCustom === true;
  const noReferenceChoices = isReference && !allowCustom && options.length === 0;
  const [value, setValue] = useState(
    scalarString(node.value),
  );
  const pattern = hintRecord(node.validation).pattern;
  const selectedOption = options.find(
    (option) => String(option.value) === value,
  );

  if (options.length > 0 && !allowCustom) {
    return (
      <form
        className="field-form"
        onSubmit={(event) => {
          event.preventDefault();
          const selected = options.find(
            (option) => String(option.value) === value,
          );
          if (selected) {
            void commit({ op: "set", path: node.path, value: selected.value });
          }
        }}
      >
        <label>
          <span>{name}</span>
          <select
            aria-label={name}
            onChange={(event) => setValue(event.target.value)}
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
        {selectedOption?.description
          ? <p className="field-help">{selectedOption.description}</p>
          : null}
        <button disabled={busy || !selectedOption} type="submit">
          Apply value
        </button>
      </form>
    );
  }

  return (
    <form
      className="field-form"
      onSubmit={(event) => {
        event.preventDefault();
        const operationValue = node.valueType === "number"
          ? Number(value)
          : value;
        void commit({ op: "set", path: node.path, value: operationValue });
      }}
    >
      <label>
        <span>{name}</span>
        <input
          aria-label={name}
          disabled={noReferenceChoices}
          list={options.length > 0 || examples.length > 0
            ? `${node.id}-choices`
            : undefined}
          onChange={(event) => setValue(event.target.value)}
          pattern={typeof pattern === "string" ? pattern : undefined}
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
      <button
        disabled={
          busy
          || noReferenceChoices
          || (node.valueType === "number" && value === "")
        }
        type="submit"
      >
        Apply value
      </button>
    </form>
  );
}


function UnionEditor({
  node,
  commit,
  busy,
}: {
  node: EditNode;
  commit: (operation: EditOperation) => Promise<boolean>;
  busy: boolean;
}) {
  const name = fieldName(node);
  const variants = node.variants ?? [];
  const [value, setValue] = useState(scalarString(node.value));
  return (
    <form
      className="field-form"
      onSubmit={(event) => {
        event.preventDefault();
        const selected = variants.find(
          (variant) => String(variant.value) === value,
        );
        if (selected) {
          void commit({ op: "set", path: node.path, value: selected.value });
        }
      }}
    >
      <label>
        <span>{name}</span>
        <select
          aria-label={name}
          onChange={(event) => setValue(event.target.value)}
          value={value}
        >
          {variants.map((variant) => (
            <option key={String(variant.value)} value={String(variant.value)}>
              {variant.label}
            </option>
          ))}
        </select>
      </label>
      {variants.find((variant) => String(variant.value) === value)?.description
        ? <p className="field-help">
          {variants.find((variant) => String(variant.value) === value)?.description}
        </p>
        : null}
      <button disabled={busy} type="submit">Apply option</button>
    </form>
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
  return (
    <form
      className="field-form"
      onSubmit={(event) => {
        event.preventDefault();
        void commit({ op: "set", path: node.path, value: checked });
      }}
    >
      <label className="boolean-field">
        <input
          checked={checked}
          disabled={busy}
          onChange={(event) => setChecked(event.target.checked)}
          type="checkbox"
        />
        <span>{fieldName(node)}</span>
      </label>
      <button disabled={busy} type="submit">Apply value</button>
    </form>
  );
}


function CommandEditor({
  node,
  parent,
  commit,
  busy,
  onAdded,
}: {
  node: EditNode;
  parent: EditNode | null;
  commit: (operation: EditOperation) => Promise<boolean>;
  busy: boolean;
  onAdded: (nodeId: string, parentId: string | null) => void;
}) {
  const requiresName = node.command?.requiresName !== false;
  const label = fieldName(node);
  const [name, setName] = useState("");
  const pattern = hintRecord(node.validation).pattern;
  return (
    <form
      className="field-form"
      onSubmit={(event) => {
        event.preventDefault();
        const trimmedName = name.trim();
        const nextIndex = Array.isArray(parent?.value)
          ? parent.value.length
          : nodeChildren(parent ?? node).filter(
            (child) => child.valueKind !== "command",
          ).length;
        const addedPath = [
          ...node.path,
          requiresName ? trimmedName : String(nextIndex),
        ];
        void commit({
          op: "add",
          path: node.path,
          value: requiresName ? { name: trimmedName } : {},
        }).then((applied) => {
          if (applied && node.command?.autoEditAdded !== false) {
            onAdded(`edit:${addedPath.join(".")}`, parent?.id ?? null);
          }
        });
      }}
    >
      {requiresName ? (
        <label>
          <span>{label} name</span>
          <input
            aria-label={`${label} name`}
            onChange={(event) => setName(event.target.value)}
            pattern={typeof pattern === "string" ? pattern : undefined}
            required
            value={name}
          />
        </label>
      ) : null}
      <button
        disabled={busy || Boolean(node.command?.blockedMessage)}
        type="submit"
      >
        <Plus aria-hidden="true" />
        Add {label}
      </button>
      {node.command?.blockedMessage
        ? <p className="field-help">{node.command.blockedMessage}</p>
        : null}
    </form>
  );
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


function FieldPanel({
  draft,
  node,
  parent,
  busy,
  commit,
  replaceDraft,
  reportError,
  onSelectAdded,
}: {
  draft: ConfigDraft;
  node: EditNode;
  parent: EditNode | null;
  busy: boolean;
  commit: (operation: EditOperation) => Promise<boolean>;
  replaceDraft: (promise: Promise<ConfigDraft>) => Promise<boolean>;
  reportError: (message: string) => void;
  onSelectAdded: (nodeId: string, parentId: string | null) => void;
}) {
  const [renaming, setRenaming] = useState(false);
  const [newName, setNewName] = useState(node.path.at(-1) ?? "");
  const canRename = node.removable === true && parent?.valueKind === "record";
  const canUnset = (
    node.presence === "optional"
    && node.required !== true
    && node.valueKind !== "command"
  );

  return (
    <article className="config-field-panel">
      <header>
        <div>
          <span>{node.path.join(".") || "configuration"}</span>
          <h3>{fieldName(node)}</h3>
        </div>
        <span className={`field-status status-${node.status ?? "ok"}`}>
          {node.status ?? "ok"}
        </span>
      </header>
      {node.description ? <p className="field-description">{node.description}</p> : null}
      <div className="value-provenance">
        {node.valueAuthored ? <span>Authored value</span> : null}
        {node.valueDefaulted ? <span>Generated value</span> : null}
        {node.presence ? <span>{node.presence}</span> : null}
        {node.expert ? <span>expert</span> : null}
      </div>
      {node.effectiveDefault ? (
        <div className="effective-default">
          <strong>
            {typeof node.effectiveDefault.label === "string"
              ? node.effectiveDefault.label
              : "Effective default"}
          </strong>
          {typeof node.effectiveDefault.description === "string"
            ? <p>{node.effectiveDefault.description}</p>
            : null}
        </div>
      ) : null}
      {(node.diagnostics ?? []).length > 0 ? (
        <div className="field-diagnostics">
          {(node.diagnostics ?? []).map((diagnostic, index) => (
            <div className={`diagnostic-${diagnostic.severity}`} key={`${diagnostic.message}-${index}`}>
              <AlertTriangle aria-hidden="true" />
              <span>{diagnostic.message}</span>
            </div>
          ))}
        </div>
      ) : null}

      <div className="field-editor" key={`${node.id}-${draft.draftRevision}`}>
        {node.externalRef ? (
          <ExternalResourceEditor
            busy={busy}
            draft={draft}
            node={node}
            replaceDraft={replaceDraft}
            reportError={reportError}
          />
        ) : node.valueKind === "scalar" ? (
          <ScalarEditor busy={busy} commit={commit} node={node} />
        ) : node.valueKind === "boolean" ? (
          <BooleanEditor busy={busy} commit={commit} node={node} />
        ) : node.valueKind === "union" ? (
          <UnionEditor busy={busy} commit={commit} node={node} />
        ) : node.valueKind === "command" ? (
          <CommandEditor
            busy={busy}
            commit={commit}
            node={node}
            onAdded={onSelectAdded}
            parent={parent}
          />
        ) : nodeChildren(node).length === 0 ? (
          <StructuredEditor busy={busy} commit={commit} node={node} />
        ) : (
          <p className="field-help">
            Select a child field to change this {node.valueKind}.
          </p>
        )}
      </div>

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
      ) : null}

      <div className="field-actions">
        {canRename ? (
          <button
            disabled={busy}
            onClick={() => setRenaming(true)}
            type="button"
          >
            <Pencil aria-hidden="true" />
            Rename {node.path.at(-1)}
          </button>
        ) : null}
        {canUnset ? (
          <button
            disabled={busy}
            onClick={() => void commit({ op: "unset", path: node.path })}
            type="button"
          >
            <Undo2 aria-hidden="true" />
            Use default
          </button>
        ) : null}
        {node.removable ? (
          <button
            className="danger-button"
            disabled={busy}
            onClick={() => {
              if (window.confirm(`Remove ${fieldName(node)}?`)) {
                void commit({ op: "removeConfig", path: node.path });
              }
            }}
            type="button"
          >
            <Trash2 aria-hidden="true" />
            Remove {node.path.at(-1)}
          </button>
        ) : null}
      </div>
    </article>
  );
}


export function ConfigEditor({
  initialTargetId,
  onClose,
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
  const [showOptional, setShowOptional] = useState(false);
  const [showExpert, setShowExpert] = useState(false);
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState("");
  const rowElements = useRef(new Map<string, HTMLDivElement>());

  const draft = draftQuery.data;
  const nodes = useMemo(
    () => draft?.editState.nodes ?? [],
    [draft?.editState.nodes],
  );

  useEffect(() => {
    if (!draft) return;
    setExpanded((current) => current.size > 0
      ? new Set([...current].filter((id) => findNode(nodes, id)))
      : initialExpanded(nodes));
    setSelectedId((current) => (
      findNode(nodes, current)
        ? current
        : findNode(nodes, initialTargetId ?? null)?.id ?? nodes[0]?.id ?? null
    ));
  }, [draft, initialTargetId, nodes]);

  useEffect(() => {
    if (!draft?.dirty) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [draft?.dirty]);

  const rows = useMemo(
    () => treeRows(nodes, expanded, showOptional, showExpert),
    [expanded, nodes, showExpert, showOptional],
  );
  const selected = findNode(nodes, selectedId);
  const parent = selected ? findParent(nodes, selected.id) : null;

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

  const commit = async (operation: EditOperation) => {
    if (!draft) return false;
    return replaceDraft(applyEditOperation(draft.draftRevision, operation));
  };

  const selectAdded = (nodeId: string, parentId: string | null) => {
    if (parentId) {
      setExpanded((current) => new Set(current).add(parentId));
    }
    setSelectedId(nodeId);
    queueMicrotask(() => rowElements.current.get(nodeId)?.focus());
  };

  const close = async () => {
    if (
      draft?.dirty
      && !window.confirm("Discard this unsaved browser draft and close configuration?")
    ) {
      return;
    }
    if (
      draft?.dirty
      && !await replaceDraft(discardConfigDraft(draft.draftRevision))
    ) {
      return;
    }
    onClose();
  };

  if (draftQuery.isPending) {
    return (
      <main className="shell-loading">
        <LoaderCircle className="spin" />
        <strong>Opening configuration</strong>
      </main>
    );
  }
  if (draftQuery.isError || !draft) {
    return (
      <main className="shell-error">
        <AlertTriangle />
        <h2>Configuration is unavailable</h2>
        <button onClick={() => void draftQuery.refetch()} type="button">
          Try again
        </button>
      </main>
    );
  }

  return (
    <main className="config-editor">
      <header className="config-toolbar">
        <div>
          <h2>Configuration</h2>
          <span>{draft.dirty ? "Unsaved changes" : "Saved configuration"}</span>
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
          <button
            disabled={busy || draftQuery.isFetching}
            onClick={() => void draftQuery.refetch()}
            type="button"
          >
            <RefreshCw className={draftQuery.isFetching ? "spin" : ""} />
            Reload draft
          </button>
          <button
            disabled={busy || !draft.dirty}
            onClick={() => void replaceDraft(discardConfigDraft(
              draft.draftRevision,
            ))}
            type="button"
          >
            <Undo2 />
            Discard changes
          </button>
          <button
            className="primary-button"
            disabled={busy || !draft.dirty}
            onClick={() => void replaceDraft(saveConfigDraft(
              draft.draftRevision,
            ))}
            type="button"
          >
            <Save />
            Save changes
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
      <div className="config-layout">
        <section className="config-tree-panel">
          <div aria-label="Configuration fields" className="config-tree" role="tree">
            {rows.map(({ node, depth }, rowIndex) => {
              const children = nodeChildren(node);
              const isExpanded = expanded.has(node.id);
              return (
                <div
                  aria-expanded={children.length ? isExpanded : undefined}
                  aria-label={node.label}
                  aria-level={depth}
                  aria-selected={selectedId === node.id}
                  className={[
                    "config-tree-row",
                    `status-${node.status ?? "ok"}`,
                    selectedId === node.id ? "selected" : "",
                  ].join(" ")}
                  key={node.id}
                  onClick={() => setSelectedId(node.id)}
                  onKeyDown={(event) => {
                    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                      event.preventDefault();
                      const offset = event.key === "ArrowDown" ? 1 : -1;
                      const next = rows[rowIndex + offset];
                      if (next) rowElements.current.get(next.node.id)?.focus();
                      return;
                    }
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      setSelectedId(node.id);
                      return;
                    }
                    if (event.key === "ArrowRight") {
                      event.preventDefault();
                      if (children.length > 0 && !isExpanded) {
                        setExpanded((current) => new Set(current).add(node.id));
                      } else {
                        const child = rows[rowIndex + 1];
                        if (child?.depth === depth + 1) {
                          rowElements.current.get(child.node.id)?.focus();
                        }
                      }
                      return;
                    }
                    if (event.key === "ArrowLeft") {
                      event.preventDefault();
                      if (children.length > 0 && isExpanded) {
                        setExpanded((current) => {
                          const next = new Set(current);
                          next.delete(node.id);
                          return next;
                        });
                      } else {
                        const parentNode = findParent(nodes, node.id);
                        if (parentNode) {
                          rowElements.current.get(parentNode.id)?.focus();
                        }
                      }
                    }
                  }}
                  ref={(element) => {
                    if (element) rowElements.current.set(node.id, element);
                    else rowElements.current.delete(node.id);
                  }}
                  role="treeitem"
                  style={{ "--config-depth": depth } as React.CSSProperties}
                  tabIndex={selectedId === node.id ? 0 : -1}
                >
                  {children.length > 0 ? (
                    <button
                      aria-label={`${isExpanded ? "Collapse" : "Expand"} ${fieldName(node)}`}
                      onClick={(event) => {
                        event.stopPropagation();
                        setExpanded((current) => {
                          const next = new Set(current);
                          if (next.has(node.id)) next.delete(node.id);
                          else next.add(node.id);
                          return next;
                        });
                      }}
                      tabIndex={-1}
                      type="button"
                    >
                      {isExpanded ? <ChevronDown /> : <ChevronRight />}
                    </button>
                  ) : <span className="config-tree-spacer" />}
                  <span className="status-dot" aria-hidden="true" />
                  <span>{node.label}</span>
                  {node.valueDefaulted ? <em>generated</em> : null}
                </div>
              );
            })}
          </div>
        </section>
        {selected ? (
          <FieldPanel
            busy={busy}
            commit={commit}
            draft={draft}
            node={selected}
            parent={parent}
            replaceDraft={replaceDraft}
            reportError={setProblem}
            onSelectAdded={selectAdded}
          />
        ) : (
          <section className="config-field-panel">
            <p>Select a configuration field.</p>
          </section>
        )}
        <aside className="config-diagnostics">
          <header>
            <AlertTriangle aria-hidden="true" />
            <h3>Validation</h3>
          </header>
          <strong>
            {draft.editState.validation.valid
              ? "Configuration is valid"
              : "Configuration needs attention"}
          </strong>
          {(draft.editState.validation.errors ?? []).map((error) => (
            <p key={error}>{error}</p>
          ))}
          {(draft.editState.validation.diagnostics ?? []).map(
            (diagnostic, index) => (
              <button
                key={`${diagnostic.message}-${index}`}
                onClick={() => {
                  const target = nodes
                    .flatMap(function flatten(node): EditNode[] {
                      return [node, ...nodeChildren(node).flatMap(flatten)];
                    })
                    .find((node) => (
                      node.path.join(".") === (diagnostic.path ?? []).join(".")
                    ));
                  if (target) setSelectedId(target.id);
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
    </main>
  );
}
