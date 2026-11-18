import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from "react";
import {
  createdExternalResourceOperations,
  externalResourceSelectionOperations,
  type EditNode,
  type EditOperation as CoreEditOperation,
} from "@opensearch-migrations/config-edit-core";
import {
  Database,
  Eye,
  Keyboard,
  LoaderCircle,
  Pencil,
  Plus,
  RefreshCw,
} from "lucide-react";

import {
  getExternalResourceDetails,
  getExternalResources,
  saveExternalResource,
  selectExternalResource,
  type ExternalResourceDetails,
  type ExternalResourceInventory,
  type ExternalResourceSelection,
} from "../../api/client";
import { ModalDialog } from "../../components/ModalDialog";
import { useEscapeCancel } from "../../hooks/useEscapeCancel";
import type { BrowserConfigDraft } from "./browserDraft";


interface ExternalField {
  name: string;
  label: string;
  input: string;
  required?: boolean;
  default?: string;
  sensitive?: boolean;
  options?: string[];
  validationIds?: string[];
  confirm?: boolean;
}


interface CreateDescriptor {
  label: string;
  fields: ExternalField[];
}


type Pane =
  | { mode: "create" }
  | { mode: "manual" }
  | {
      mode: "view" | "update";
      details: ExternalResourceDetails;
      row: ExternalResourceInventory["rows"][number];
    };


type ApplyExternalOperations = (
  operations: CoreEditOperation[],
  notice?: string,
) => Promise<boolean>;


interface KubernetesResourceType {
  group: string;
  kind: string;
  version: string;
}


function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}


function kubernetesResourceTypes(node: EditNode): KubernetesResourceType[] {
  const externalRef = record(node.externalRef);
  const k8s = record(externalRef.k8s);
  if (!Array.isArray(k8s.resourceTypes)) return [];
  return k8s.resourceTypes.flatMap((candidate) => {
    const resourceType = record(candidate);
    if (typeof resourceType.kind !== "string") return [];
    return [{
      group: typeof resourceType.group === "string" ? resourceType.group : "",
      kind: resourceType.kind,
      version: typeof resourceType.version === "string"
        ? resourceType.version
        : "",
    }];
  });
}


function createDescriptor(node: EditNode): CreateDescriptor | null {
  const externalRef = record(node.externalRef);
  const create = record(externalRef.create);
  if (!Array.isArray(create.fields)) return null;
  const fields = create.fields.flatMap((candidate) => {
    const field = record(candidate);
    if (typeof field.name !== "string") return [];
    return [{
      name: field.name,
      label: typeof field.label === "string" ? field.label : field.name,
      input: typeof field.input === "string" ? field.input : "text",
      required: field.required === true,
      default: typeof field.default === "string" ? field.default : undefined,
      sensitive: typeof field.sensitive === "boolean"
        ? field.sensitive
        : undefined,
      options: Array.isArray(field.options)
        ? field.options.map(String)
        : undefined,
      validationIds: Array.isArray(field.validationIds)
        ? field.validationIds.map(String)
        : undefined,
      confirm: field.confirm === true,
    }];
  });
  return {
    label: typeof create.label === "string"
      ? create.label
      : typeof externalRef.displayName === "string"
        ? externalRef.displayName
        : "External resource",
    fields,
  };
}


function externalResourceDisplayName(node: EditNode): string {
  const externalRef = record(node.externalRef);
  return typeof externalRef.displayName === "string"
    ? externalRef.displayName
    : node.label.split(":")[0] || "Kubernetes resource";
}


function ManualExternalResourceForm({
  draft,
  node,
  onApplied,
  onBack,
  applyOperations,
  reportError,
}: Readonly<{
  draft: BrowserConfigDraft;
  node: EditNode;
  onApplied: () => void;
  onBack: () => void;
  applyOperations: ApplyExternalOperations;
  reportError: (message: string) => void;
}>) {
  const resourceTypes = kubernetesResourceTypes(node);
  const selection = record(record(node.externalRef).selection);
  const selectsKey = selection.target === "fileRefConfigMap";
  const [resourceTypeIndex, setResourceTypeIndex] = useState(0);
  const [name, setName] = useState("");
  const [key, setKey] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const resourceType = resourceTypes[resourceTypeIndex];
  const formRef = useEscapeCancel<HTMLFormElement>(onBack, submitting);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!resourceType) return;
    setSubmitting(true);
    const selection = {
      nodeId: node.id,
      name: name.trim(),
      kind: resourceType.kind,
      group: resourceType.group,
      key: selectsKey ? key.trim() : undefined,
      acceptWarning: true,
      manual: true,
    };
    try {
      await selectExternalResource(draft.rawDocument, selection);
      const applied = await applyOperations(
        externalResourceSelectionOperations(node, selection),
      );
      if (applied) onApplied();
    } catch (error) {
      reportError(error instanceof Error ? error.message : String(error));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form
      className="external-resource-form"
      data-escape-cancel-layer
      onSubmit={(event) => void submit(event)}
      ref={formRef}
    >
      <header>
        <div>
          <span>The server will validate it against the field descriptor.</span>
        </div>
      </header>
      <div className="external-form-fields">
        {resourceTypes.length > 1 ? (
          <div className="external-form-field">
            <label>
              <span>Resource type</span>
              <select
                aria-label="Resource type"
                disabled={submitting}
                onChange={(event) => setResourceTypeIndex(
                  Number(event.target.value),
                )}
                value={resourceTypeIndex}
              >
                {resourceTypes.map((candidate, index) => (
                  <option
                    key={`${candidate.group}/${candidate.kind}`}
                    value={index}
                  >
                    {candidate.kind}
                    {candidate.group ? ` (${candidate.group})` : ""}
                  </option>
                ))}
              </select>
            </label>
          </div>
        ) : null}
        <div className="external-form-field">
          <label>
            <span>Resource name</span>
            <input
              aria-label="Resource name"
              disabled={submitting}
              onChange={(event) => setName(event.target.value)}
              pattern="[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*"
              required
              value={name}
            />
          </label>
        </div>
        {selectsKey ? (
          <div className="external-form-field">
            <label>
              <span>ConfigMap key</span>
              <input
                aria-label="ConfigMap key"
                disabled={submitting}
                onChange={(event) => setKey(event.target.value)}
                pattern="(?!\.{1,2}$)(?!\.\.)([A-Za-z0-9._-]+)"
                required
                value={key}
              />
            </label>
          </div>
        ) : null}
      </div>
      {resourceTypes.length === 0 ? (
        <p className="field-error" role="alert">
          This field does not declare an allowed Kubernetes resource type.
        </p>
      ) : null}
      <div className="external-form-actions">
        <button disabled={submitting} onClick={onBack} type="button">
          Cancel
        </button>
        <button
          className="primary-button"
          disabled={submitting || resourceTypes.length === 0}
          type="submit"
        >
          {submitting ? <LoaderCircle className="spin" /> : <Keyboard />}
          Use unverified reference
        </button>
      </div>
    </form>
  );
}


function sensitive(field: ExternalField): boolean {
  if (field.sensitive !== undefined) return field.sensitive;
  return field.input === "password" || field.input === "secretMultilineText";
}


function inputPattern(field: ExternalField): string | undefined {
  if (field.validationIds?.includes("k8s-name")) {
    return String.raw`[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*`;
  }
  if (field.validationIds?.includes("configmap-key")) {
    return String.raw`(?!\.{1,2}$)(?!\.\.)([A-Za-z0-9._-]+)`;
  }
  return undefined;
}


function ExternalResourceForm({
  descriptor,
  details,
  draft,
  node,
  onApplied,
  onBack,
  applyOperations,
  reportError,
}: Readonly<{
  descriptor: CreateDescriptor;
  details?: ExternalResourceDetails;
  draft: BrowserConfigDraft;
  node: EditNode;
  onApplied: () => void;
  onBack: () => void;
  applyOperations: ApplyExternalOperations;
  reportError: (message: string) => void;
}>) {
  const updating = Boolean(details && !details.missing);
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(descriptor.fields.map((field) => [
      field.name,
      details?.fieldValues[field.name]
        ?? field.default
        ?? (field.input === "select" ? field.options?.[0] ?? "" : ""),
    ])),
  );
  const [confirmations, setConfirmations] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [formProblem, setFormProblem] = useState("");
  const formRef = useEscapeCancel<HTMLFormElement>(onBack, saving);
  const mismatchedFields = new Set(descriptor.fields
    .filter((field) => (
      field.confirm
      && (confirmations[field.name] ?? "") !== ""
      && values[field.name] !== confirmations[field.name]
    ))
    .map((field) => field.name));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const mismatched = descriptor.fields.find(
      (field) => field.confirm
        && values[field.name] !== (confirmations[field.name] ?? ""),
    );
    if (mismatched) {
      setFormProblem(`${mismatched.label} and confirmation do not match.`);
      return;
    }
    setSaving(true);
    setFormProblem("");
    try {
      const result = await saveExternalResource(
        draft.rawDocument,
        node.id,
        values,
        confirmations,
        updating ? details?.name : undefined,
      );
      const applied = await applyOperations(
        createdExternalResourceOperations(
          node,
          values,
          result.name,
        ),
        result.message,
      );
      if (applied) onApplied();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setFormProblem(message);
      reportError(message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <form
      className="external-resource-form"
      data-escape-cancel-layer
      onSubmit={(event) => void submit(event)}
      ref={formRef}
    >
      <header>
        <div>
          {details?.message
            ? <span>{details.message}</span>
            : (
              <span>
                {updating
                  ? "Changed values replace the deployed resource."
                  : "The new resource is provisioned and selected here."}
              </span>
            )}
        </div>
      </header>
      <div className="external-form-fields">
        {descriptor.fields.map((field) => {
          const multiline = (
            field.input === "multilineText"
            || field.input === "secretMultilineText"
          );
          const leaveUnchanged = (
            updating
            && sensitive(field)
            && details?.hiddenFields.includes(field.name)
          );
          return (
            <div className="external-form-field" key={field.name}>
              <label>
                <span>{field.label}</span>
                {field.input === "select" ? (
                  <select
                    aria-label={field.label}
                    disabled={saving}
                    onChange={(event) => setValues((current) => ({
                      ...current,
                      [field.name]: event.target.value,
                    }))}
                    required={field.required}
                    value={values[field.name]}
                  >
                    {(field.options ?? []).map((option) => (
                      <option key={option} value={option}>{option}</option>
                    ))}
                  </select>
                ) : multiline ? (
                  <textarea
                    aria-label={field.label}
                    disabled={saving}
                    onChange={(event) => setValues((current) => ({
                      ...current,
                      [field.name]: event.target.value,
                    }))}
                    placeholder={leaveUnchanged ? "Leave unchanged" : undefined}
                    required={field.required && !leaveUnchanged}
                    rows={8}
                    value={values[field.name]}
                  />
                ) : (
                  <input
                    aria-label={field.label}
                    disabled={saving || (updating && field.input === "name")}
                    onChange={(event) => setValues((current) => ({
                      ...current,
                      [field.name]: event.target.value,
                    }))}
                    pattern={inputPattern(field)}
                    placeholder={leaveUnchanged ? "Leave unchanged" : undefined}
                    required={field.required && !leaveUnchanged}
                    type={sensitive(field) ? "password" : "text"}
                    value={values[field.name]}
                  />
                )}
              </label>
              {field.confirm ? (
                <label>
                  <span>Confirm {field.label}</span>
                  <input
                    aria-invalid={mismatchedFields.has(field.name) || undefined}
                    aria-label={`Confirm ${field.label}`}
                    disabled={saving}
                    onChange={(event) => setConfirmations((current) => ({
                      ...current,
                      [field.name]: event.target.value,
                    }))}
                    placeholder={leaveUnchanged ? "Leave unchanged" : undefined}
                    required={field.required && !leaveUnchanged}
                    type="password"
                    value={confirmations[field.name] ?? ""}
                  />
                </label>
              ) : null}
              {mismatchedFields.has(field.name) ? (
                <p className="field-error" role="alert">
                  {field.label} and confirmation do not match.
                </p>
              ) : null}
            </div>
          );
        })}
      </div>
      {formProblem ? <p className="field-error" role="alert">{formProblem}</p> : null}
      <div className="external-form-actions">
        <button disabled={saving} onClick={onBack} type="button">Cancel</button>
        <button
          className="primary-button"
          disabled={saving || mismatchedFields.size > 0}
          type="submit"
        >
          {saving ? <LoaderCircle className="spin" /> : updating ? <Pencil /> : <Plus />}
          {updating ? "Update resource" : "Create resource"}
        </button>
      </div>
    </form>
  );
}


function ExternalResourceView({
  descriptor,
  details,
  onSelect,
  onUpdate,
  selectsKey,
}: Readonly<{
  descriptor: CreateDescriptor | null;
  details: ExternalResourceDetails;
  onSelect: (key?: string) => void;
  onUpdate: () => void;
  selectsKey: boolean;
}>) {
  const fields = descriptor?.fields ?? Object.keys(details.fieldValues).map(
    (name) => ({ name, label: name, input: "text" }),
  );
  return (
    <section className="external-resource-view">
      <header>
        <div>
          <span>{details.kind}</span>
        </div>
        <div>
          {descriptor ? (
            <button
              className="secondary-button"
              onClick={onUpdate}
              type="button"
            >
              <Pencil aria-hidden="true" />
              Update resource
            </button>
          ) : null}
          {selectsKey ? details.keys.map((key) => (
            <button
              className="primary-button"
              key={key}
              onClick={() => onSelect(key)}
              type="button"
            >
              Use {key}
            </button>
          )) : (
            <button
              className="primary-button"
              onClick={() => onSelect()}
              type="button"
            >
              Use resource
            </button>
          )}
        </div>
      </header>
      {details.message ? <p className="field-help">{details.message}</p> : null}
      <dl className="external-resource-values">
        {fields.map((field) => {
          const value = details.fieldValues[field.name];
          const hidden = details.hiddenFields.includes(field.name);
          if (value === undefined && !hidden) return null;
          return (
            <div key={field.name}>
              <dt>{field.label}</dt>
              <dd>
                {hidden
                  ? <span className="hidden-value">Present, hidden</span>
                  : field.input.includes("multiline")
                    ? <pre>{value}</pre>
                    : value}
              </dd>
            </div>
          );
        })}
      </dl>
    </section>
  );
}


type ExternalResourceRow = ExternalResourceInventory["rows"][number];


function ExternalResourceRows({
  busy,
  onInspect,
  onSelect,
  rows,
  selectsKey,
  showDetails = false,
}: Readonly<{
  busy: boolean;
  onInspect: (row: ExternalResourceRow) => void;
  onSelect: (row: ExternalResourceRow, key?: string) => void;
  rows: ExternalResourceRow[];
  selectsKey: boolean;
  /** Show per-row key chips and status messages (the all-resources
      view); the matching list stays free of repeated detail. */
  showDetails?: boolean;
}>) {
  if (rows.length === 0) {
    return (
      <p className="external-resource-empty">
        No Kubernetes resources match this field.
      </p>
    );
  }
  return (
    <div className="external-resource-list">
      {rows.map((row) => (
        <div
          className={`external-resource-row status-${row.status}`}
          key={`${row.group}-${row.kind}-${row.name}`}
        >
          <div className="external-resource-heading">
            <strong>{row.name}</strong>
            {showDetails ? <span>{row.kind}</span> : null}
            {row.current ? <em>Current</em> : null}
          </div>
          {row.message && (showDetails || row.status !== "matching") ? (
            <p>{row.message}</p>
          ) : null}
          {showDetails ? (
            row.keys.length > 0 ? (
              <div
                aria-label={`Keys in ${row.name}`}
                className="external-keys"
              >
                {row.keys.map((key) => <span key={key}>{key}</span>)}
              </div>
            ) : <span className="empty-keys">No keys reported</span>
          ) : null}
          <div className="external-resource-actions">
            <button
              aria-label={`Details for ${row.name}`}
              disabled={busy}
              onClick={() => onInspect(row)}
              type="button"
            >
              <Eye aria-hidden="true" />
              Details
            </button>
            {selectsKey ? row.keys.map((key) => (
              <button
                aria-label={`Use ${row.name} and key ${key}`}
                className="primary-button"
                disabled={busy}
                key={key}
                onClick={() => onSelect(row, key)}
                type="button"
              >
                Use {key}
              </button>
            )) : (
              <button
                aria-label={`Use ${row.name}`}
                className="primary-button"
                disabled={busy}
                onClick={() => onSelect(row)}
                type="button"
              >
                Use resource
              </button>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}


function ExternalResourceDialogContent({
  draft,
  node,
  busy,
  onClose,
  registerPane,
  applyOperations,
  reportError,
}: Readonly<{
  draft: BrowserConfigDraft;
  node: EditNode;
  busy: boolean;
  onClose: () => void;
  registerPane: (
    info: { title: string; back: () => void } | null,
  ) => void;
  applyOperations: ApplyExternalOperations;
  reportError: (message: string) => void;
}>) {
  const [inventory, setInventory] = useState<ExternalResourceInventory | null>(
    null,
  );
  const [loading, setLoading] = useState(false);
  const [selecting, setSelecting] = useState(false);
  const [pane, setPane] = useState<Pane | null>(null);
  const [allResourcesOpen, setAllResourcesOpen] = useState(false);
  const [allResourcesPane, setAllResourcesPane] = useState<Pane | null>(null);
  const [warning, setWarning] = useState<{
    selection: ExternalResourceSelection;
    message: string;
  } | null>(null);
  const descriptor = createDescriptor(node);
  const selectionDescriptor = record(record(node.externalRef).selection);
  const selectsKey = selectionDescriptor.target === "fileRefConfigMap";
  const description = record(node.externalRef).description;
  const k8sHint = record(record(node.externalRef).k8s);
  const requiredKeysHint = record(k8sHint.match).requiredKeys
    ?? k8sHint.requiredKeys;
  const requiredKeys = Array.isArray(requiredKeysHint)
    ? requiredKeysHint.map(String)
    : [];
  const warningRef = useEscapeCancel<HTMLDivElement>(
    () => setWarning(null),
    warning === null,
  );
  const descriptorLabel = descriptor?.label ?? "resource";
  useEffect(() => {
    // A visible sub-pane folds its title into the dialog header and
    // turns the header X into "back one level", matching Escape.
    if (!pane) {
      registerPane(null);
      return () => registerPane(null);
    }
    const title = pane.mode === "create"
      ? `Create ${descriptorLabel}`
      : pane.mode === "update"
        ? `Update ${descriptorLabel}`
        : pane.mode === "manual"
          ? "Enter reference manually"
          : pane.details.name;
    const back = pane.mode === "update"
      // Update is reached from the details view; step back to it.
      ? () => setPane({ mode: "view", details: pane.details, row: pane.row })
      : () => setPane(null);
    registerPane({ title, back });
    return () => registerPane(null);
  }, [descriptorLabel, pane, registerPane]);

  const load = useCallback(async () => {
    setLoading(true);
    setWarning(null);
    try {
      setInventory(await getExternalResources(node.id, draft.rawDocument));
    } catch (error) {
      reportError(error instanceof Error ? error.message : String(error));
    } finally {
      setLoading(false);
    }
  }, [draft.rawDocument, node.id, reportError]);

  useEffect(() => {
    void load();
  }, [load]);

  const inspect = async (
    row: ExternalResourceInventory["rows"][number],
    mode: "view" | "update",
    fromAllResources = false,
  ) => {
    setLoading(true);
    try {
      const details = await getExternalResourceDetails(
        node.id,
        draft.rawDocument,
        row.name,
      );
      const nextPane = { mode, details, row } as const;
      if (fromAllResources) setAllResourcesPane(nextPane);
      else setPane(nextPane);
    } catch (error) {
      reportError(error instanceof Error ? error.message : String(error));
    } finally {
      setLoading(false);
    }
  };

  const select = async (
    selection: ExternalResourceSelection,
    status: string,
    message: string,
  ) => {
    if (status !== "matching") {
      setWarning({ selection, message });
      return;
    }
    setSelecting(true);
    try {
      await selectExternalResource(draft.rawDocument, selection);
      const applied = await applyOperations(
        externalResourceSelectionOperations(node, selection),
      );
      if (applied) onClose();
    } catch (error) {
      reportError(error instanceof Error ? error.message : String(error));
    } finally {
      setSelecting(false);
    }
  };
  const acceptWarning = async () => {
    if (!warning) return;
    const acceptedSelection = {
      ...warning.selection,
      acceptWarning: true,
    };
    setSelecting(true);
    try {
      await selectExternalResource(draft.rawDocument, acceptedSelection);
      const applied = await applyOperations(
        externalResourceSelectionOperations(node, acceptedSelection),
      );
      if (applied) onClose();
    } catch (error) {
      reportError(error instanceof Error ? error.message : String(error));
    } finally {
      setSelecting(false);
    }
  };
  const selectionForRow = (
    row: ExternalResourceInventory["rows"][number],
    key?: string,
  ): ExternalResourceSelection => ({
    nodeId: node.id,
    name: row.name,
    kind: row.kind,
    group: row.group,
    key,
  });

  if (descriptor && pane?.mode === "create") {
    return (
      <ExternalResourceForm
        descriptor={descriptor}
        draft={draft}
        node={node}
        onApplied={onClose}
        onBack={() => setPane(null)}
        applyOperations={applyOperations}
        reportError={reportError}
      />
    );
  }
  if (pane?.mode === "manual") {
    return (
      <ManualExternalResourceForm
        draft={draft}
        node={node}
        onApplied={onClose}
        onBack={() => setPane(null)}
        applyOperations={applyOperations}
        reportError={reportError}
      />
    );
  }
  if (descriptor && pane?.mode === "update") {
    return (
      <ExternalResourceForm
        descriptor={descriptor}
        details={pane.details}
        draft={draft}
        node={node}
        onApplied={onClose}
        onBack={() => setPane({
          mode: "view",
          details: pane.details,
          row: pane.row,
        })}
        applyOperations={applyOperations}
        reportError={reportError}
      />
    );
  }
  if (pane?.mode === "view") {
    return (
      <ExternalResourceView
        descriptor={descriptor}
        details={{
          ...pane.details,
          message: pane.details.message || pane.row.message,
        }}
        onSelect={(key) => void select(
          selectionForRow(pane.row, key),
          pane.row.status,
          pane.row.message,
        )}
        onUpdate={() => setPane({
          mode: "update",
          details: pane.details,
          row: pane.row,
        })}
        selectsKey={selectsKey}
      />
    );
  }

  if (!inventory) {
    return (
      <div className="external-entry">
        {typeof description === "string"
          ? <p className="field-help">{description}</p>
          : null}
        <div className="external-entry-actions">
          <button
            className="secondary-button"
            disabled={busy || loading || selecting}
            onClick={() => void load()}
            type="button"
          >
            {loading ? <LoaderCircle className="spin" /> : <Database />}
            Browse Kubernetes resources
          </button>
          <button
            className="secondary-button"
            disabled={busy || selecting}
            onClick={() => setPane({ mode: "manual" })}
            type="button"
          >
            <Keyboard aria-hidden="true" />
            Enter reference manually
          </button>
        </div>
      </div>
    );
  }

  const matchingRows = inventory.rows.filter(
    (row) => row.status === "matching",
  );
  return (
    <>
      <section className="external-picker">
        <header>
          <div>
            <strong>
              Existing {matchingRows[0]?.kind
                ?? inventory.rows[0]?.kind
                ?? "resource"}s
            </strong>
            <span>
              {matchingRows.length} matching {
                matchingRows.length === 1 ? "resource" : "resources"
              }
              {requiredKeys.length > 0
                ? ` with ${
                  requiredKeys.length === 1 ? "key" : "keys"
                } ${requiredKeys.join(", ")}`
                : ""}
            </span>
          </div>
          <div className="external-picker-header-actions">
            <button
              aria-label="Refresh external resources"
              className="icon-button"
              disabled={loading || selecting}
              onClick={() => void load()}
              type="button"
            >
              <RefreshCw className={loading ? "spin" : ""} />
            </button>
          </div>
        </header>
        {warning ? (
          <div
            className="selection-warning"
            data-escape-cancel-layer
            ref={warningRef}
            role="alert"
          >
            <span>
              {warning.message
                || "This resource does not match all requirements."}
            </span>
            <button
              disabled={busy || selecting}
              onClick={() => void acceptWarning()}
              type="button"
            >
              Use anyway
            </button>
            <button onClick={() => setWarning(null)} type="button">
              Cancel
            </button>
          </div>
        ) : null}
        <ExternalResourceRows
          busy={busy || loading || selecting}
          onInspect={(row) => void inspect(row, "view")}
          onSelect={(row, key) => void select(
            selectionForRow(row, key),
            row.status,
            row.message,
          )}
          rows={matchingRows}
          selectsKey={selectsKey}
        />
        {descriptor ? (
          <div className="external-resource-row external-create-row">
            <div className="external-resource-heading">
              <strong>Create a new {descriptor.label}</strong>
              <span>Provisioned in the cluster and selected here.</span>
            </div>
            <div className="external-resource-actions">
              <button
                aria-label={`Create ${descriptor.label}`}
                className="primary-button"
                disabled={busy || selecting}
                onClick={() => setPane({ mode: "create" })}
                type="button"
              >
                <Plus aria-hidden="true" />
                Create
              </button>
            </div>
          </div>
        ) : null}
        <footer className="external-picker-footer">
          <button
            disabled={busy || selecting}
            onClick={() => setPane({ mode: "manual" })}
            type="button"
          >
            <Keyboard aria-hidden="true" />
            Enter reference manually
          </button>
          <button
            disabled={busy || selecting}
            onClick={() => setAllResourcesOpen(true)}
            type="button"
          >
            <Eye aria-hidden="true" />
            View all {inventory.rows.length}
          </button>
        </footer>
      </section>
      {allResourcesOpen ? (
        <ModalDialog
          backdropClassName="nested-modal-backdrop"
          className="external-resource-dialog"
          closeLabel="Close all Kubernetes resources"
          escapeDisabled={busy || selecting}
          icon={<Database aria-hidden="true" />}
          kicker="Kubernetes resource inventory"
          onClose={() => {
            if (allResourcesPane) setAllResourcesPane(null);
            else setAllResourcesOpen(false);
          }}
          portal
          title={<>All {inventory.displayName} resources</>}
        >
            <div className="external-resource-dialog-body">
              {warning ? (
                <div className="selection-warning" role="alert">
                  <span>
                    {warning.message
                      || "This resource does not match all requirements."}
                  </span>
                  <button
                    disabled={busy || selecting}
                    onClick={() => void acceptWarning()}
                    type="button"
                  >
                    Use anyway
                  </button>
                  <button
                    onClick={() => setWarning(null)}
                    type="button"
                  >
                    Cancel
                  </button>
                </div>
              ) : null}
              {
                descriptor && allResourcesPane?.mode === "update"
                  ? (
                    <ExternalResourceForm
                      descriptor={descriptor}
                      details={allResourcesPane.details}
                      draft={draft}
                      node={node}
                      onApplied={onClose}
                      onBack={() => setAllResourcesPane({
                        mode: "view",
                        details: allResourcesPane.details,
                        row: allResourcesPane.row,
                      })}
                      applyOperations={applyOperations}
                      reportError={reportError}
                    />
                    )
                  : allResourcesPane?.mode === "view"
                    ? (
                      <ExternalResourceView
                        descriptor={descriptor}
                        details={{
                          ...allResourcesPane.details,
                          message: allResourcesPane.details.message
                            || allResourcesPane.row.message,
                        }}
                        onSelect={(key) => void select(
                          selectionForRow(allResourcesPane.row, key),
                          allResourcesPane.row.status,
                          allResourcesPane.row.message,
                        )}
                        onUpdate={() => setAllResourcesPane({
                          mode: "update",
                          details: allResourcesPane.details,
                          row: allResourcesPane.row,
                        })}
                        selectsKey={selectsKey}
                      />
                      )
                    : (
                      <ExternalResourceRows
                        busy={busy || loading || selecting}
                        onInspect={(row) => void inspect(
                          row,
                          "view",
                          true,
                        )}
                        onSelect={(row, key) => void select(
                          selectionForRow(row, key),
                          row.status,
                          row.message,
                        )}
                        rows={inventory.rows}
                        selectsKey={selectsKey}
                        showDetails
                      />
                      )
              }
            </div>
        </ModalDialog>
      ) : null}
    </>
  );
}


export function ExternalResourceEditor({
  draft,
  node,
  busy,
  onClose,
  applyOperations,
  reportError,
}: Readonly<{
  draft: BrowserConfigDraft;
  node: EditNode;
  busy: boolean;
  onClose: () => void;
  applyOperations: ApplyExternalOperations;
  reportError: (message: string) => void;
}>) {
  const displayName = externalResourceDisplayName(node);
  const backHandler = useRef<(() => void) | null>(null);
  const [paneTitle, setPaneTitle] = useState<string | null>(null);
  const registerPane = useCallback(
    (info: { title: string; back: () => void } | null) => {
      backHandler.current = info?.back ?? null;
      setPaneTitle(info?.title ?? null);
    },
    [],
  );
  return (
    <ModalDialog
      className="external-resource-dialog"
      closeLabel="Close Kubernetes resource selector"
      escapeDisabled={busy}
      icon={<Database aria-hidden="true" />}
      onClose={() => {
        if (backHandler.current) backHandler.current();
        else onClose();
      }}
      portal
      subtitle="Kubernetes resource"
      title={paneTitle ?? displayName}
    >
      <div className="external-resource-dialog-body">
        <ExternalResourceDialogContent
          busy={busy}
          draft={draft}
          node={node}
          onClose={onClose}
          registerPane={registerPane}
          applyOperations={applyOperations}
          reportError={reportError}
        />
      </div>
    </ModalDialog>
  );
}
