import { useState, type FormEvent } from "react";
import {
  ArrowLeft,
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
  type ConfigDraft,
  type EditNode,
  type ExternalResourceDetails,
  type ExternalResourceInventory,
  type ExternalResourceSelection,
} from "../../api/client";


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
  | { mode: "view" | "update"; details: ExternalResourceDetails };


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


function ManualExternalResourceForm({
  draft,
  node,
  onBack,
  replaceDraft,
}: Readonly<{
  draft: ConfigDraft;
  node: EditNode;
  onBack: () => void;
  replaceDraft: (promise: Promise<ConfigDraft>) => Promise<boolean>;
}>) {
  const resourceTypes = kubernetesResourceTypes(node);
  const selection = record(record(node.externalRef).selection);
  const selectsKey = selection.target === "fileRefConfigMap";
  const [resourceTypeIndex, setResourceTypeIndex] = useState(0);
  const [name, setName] = useState("");
  const [key, setKey] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const resourceType = resourceTypes[resourceTypeIndex];

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!resourceType) return;
    setSubmitting(true);
    const applied = await replaceDraft(selectExternalResource(
      draft.draftRevision,
      {
        nodeId: node.id,
        name: name.trim(),
        kind: resourceType.kind,
        group: resourceType.group,
        key: selectsKey ? key.trim() : undefined,
        acceptWarning: true,
        manual: true,
      },
    ));
    setSubmitting(false);
    if (applied) onBack();
  };

  return (
    <form
      className="external-resource-form"
      onSubmit={(event) => void submit(event)}
    >
      <header>
        <div>
          <strong>Enter reference manually</strong>
          <span>The server will validate it against the field descriptor.</span>
        </div>
        <button className="secondary-button" onClick={onBack} type="button">
          <ArrowLeft aria-hidden="true" />
          Back to resources
        </button>
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
        <button
          className="primary-button"
          disabled={submitting || resourceTypes.length === 0}
          type="submit"
        >
          {submitting ? <LoaderCircle className="spin" /> : <Keyboard />}
          Use unverified reference
        </button>
        <button disabled={submitting} onClick={onBack} type="button">
          Cancel
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
  onBack,
  replaceDraft,
  reportError,
}: Readonly<{
  descriptor: CreateDescriptor;
  details?: ExternalResourceDetails;
  draft: ConfigDraft;
  node: EditNode;
  onBack: () => void;
  replaceDraft: (promise: Promise<ConfigDraft>) => Promise<boolean>;
  reportError: (message: string) => void;
}>) {
  const updating = Boolean(details && !details.missing);
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(descriptor.fields.map((field) => [
      field.name,
      details?.fieldValues[field.name] ?? field.default ?? "",
    ])),
  );
  const [confirmations, setConfirmations] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [formProblem, setFormProblem] = useState("");

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
        draft.draftRevision,
        node.id,
        values,
        confirmations,
        updating ? details?.name : undefined,
      );
      await replaceDraft(Promise.resolve(result.draft));
      onBack();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setFormProblem(message);
      reportError(message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="external-resource-form" onSubmit={(event) => void submit(event)}>
      <header>
        <div>
          <strong>{updating ? "Update" : "Create"} {descriptor.label}</strong>
          {details?.message ? <span>{details.message}</span> : null}
        </div>
        <button className="secondary-button" onClick={onBack} type="button">
          <ArrowLeft aria-hidden="true" />
          Back to resources
        </button>
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
            </div>
          );
        })}
      </div>
      {formProblem ? <p className="field-error" role="alert">{formProblem}</p> : null}
      <div className="external-form-actions">
        <button className="primary-button" disabled={saving} type="submit">
          {saving ? <LoaderCircle className="spin" /> : updating ? <Pencil /> : <Plus />}
          {updating ? "Update resource" : "Create resource"}
        </button>
        <button disabled={saving} onClick={onBack} type="button">Cancel</button>
      </div>
    </form>
  );
}


function ExternalResourceView({
  descriptor,
  details,
  onBack,
  onUpdate,
}: Readonly<{
  descriptor: CreateDescriptor;
  details: ExternalResourceDetails;
  onBack: () => void;
  onUpdate: () => void;
}>) {
  return (
    <section className="external-resource-view">
      <header>
        <div>
          <strong>{details.name}</strong>
          <span>{details.kind}{details.resourceType ? ` · ${details.resourceType}` : ""}</span>
        </div>
        <div>
          <button className="secondary-button" onClick={onUpdate} type="button">
            <Pencil aria-hidden="true" />
            Update resource
          </button>
          <button className="secondary-button" onClick={onBack} type="button">
            <ArrowLeft aria-hidden="true" />
            Back to resources
          </button>
        </div>
      </header>
      {details.message ? <p className="field-help">{details.message}</p> : null}
      <dl className="external-resource-values">
        {descriptor.fields.map((field) => {
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


export function ExternalResourceEditor({
  draft,
  node,
  busy,
  replaceDraft,
  reportError,
}: Readonly<{
  draft: ConfigDraft;
  node: EditNode;
  busy: boolean;
  replaceDraft: (promise: Promise<ConfigDraft>) => Promise<boolean>;
  reportError: (message: string) => void;
}>) {
  const [inventory, setInventory] = useState<ExternalResourceInventory | null>(
    null,
  );
  const [loading, setLoading] = useState(false);
  const [pane, setPane] = useState<Pane | null>(null);
  const [warning, setWarning] = useState<{
    selection: ExternalResourceSelection;
    message: string;
  } | null>(null);
  const descriptor = createDescriptor(node);
  const selectionDescriptor = record(record(node.externalRef).selection);
  const selectsKey = selectionDescriptor.target === "fileRefConfigMap";
  const description = record(node.externalRef).description;

  const load = async () => {
    setLoading(true);
    setWarning(null);
    try {
      setInventory(await getExternalResources(node.id, draft.draftRevision));
    } catch (error) {
      reportError(error instanceof Error ? error.message : String(error));
    } finally {
      setLoading(false);
    }
  };

  const inspect = async (name: string, mode: "view" | "update") => {
    setLoading(true);
    try {
      const details = await getExternalResourceDetails(
        node.id,
        draft.draftRevision,
        name,
      );
      setPane({ mode, details });
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
    await replaceDraft(selectExternalResource(
      draft.draftRevision,
      selection,
    ));
  };

  if (descriptor && pane?.mode === "create") {
    return (
      <ExternalResourceForm
        descriptor={descriptor}
        draft={draft}
        node={node}
        onBack={() => setPane(null)}
        replaceDraft={replaceDraft}
        reportError={reportError}
      />
    );
  }
  if (pane?.mode === "manual") {
    return (
      <ManualExternalResourceForm
        draft={draft}
        node={node}
        onBack={() => setPane(null)}
        replaceDraft={replaceDraft}
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
        onBack={() => setPane(null)}
        replaceDraft={replaceDraft}
        reportError={reportError}
      />
    );
  }
  if (descriptor && pane?.mode === "view") {
    return (
      <ExternalResourceView
        descriptor={descriptor}
        details={pane.details}
        onBack={() => setPane(null)}
        onUpdate={() => setPane({ mode: "update", details: pane.details })}
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
            disabled={busy || loading}
            onClick={() => void load()}
            type="button"
          >
            {loading ? <LoaderCircle className="spin" /> : <Database />}
            Browse Kubernetes resources
          </button>
          <button
            className="secondary-button"
            disabled={busy}
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

  return (
    <section className="external-picker">
      <header>
        <div>
          <strong>{inventory.displayName}</strong>
          <span>{inventory.rows.length} resources</span>
        </div>
        <div className="external-picker-header-actions">
          {descriptor ? (
            <button
              className="secondary-button"
              onClick={() => setPane({ mode: "create" })}
              type="button"
            >
              <Plus aria-hidden="true" />
              Create {descriptor.label}
            </button>
          ) : null}
          <button
            className="secondary-button"
            disabled={busy}
            onClick={() => setPane({ mode: "manual" })}
            type="button"
          >
            <Keyboard aria-hidden="true" />
            Enter manually
          </button>
          <button
            aria-label="Refresh external resources"
            className="icon-button"
            disabled={loading}
            onClick={() => void load()}
            type="button"
          >
            <RefreshCw className={loading ? "spin" : ""} />
          </button>
        </div>
      </header>
      {warning ? (
        <div className="selection-warning" role="alert">
          <span>{warning.message || "This resource does not match all requirements."}</span>
          <button
            disabled={busy}
            onClick={() => void replaceDraft(selectExternalResource(
              draft.draftRevision,
              { ...warning.selection, acceptWarning: true },
            ))}
            type="button"
          >
            Use anyway
          </button>
          <button onClick={() => setWarning(null)} type="button">Cancel</button>
        </div>
      ) : null}
      <div className="external-resource-list">
        {inventory.rows.map((row) => (
          <div
            className={`external-resource-row status-${row.status}`}
            key={`${row.group}-${row.kind}-${row.name}`}
          >
            <div className="external-resource-heading">
              <strong>{row.name}</strong>
              <span>{row.kind}{row.type ? ` · ${row.type}` : ""}</span>
              {row.current ? <em>Current</em> : null}
            </div>
            {row.message ? <p>{row.message}</p> : null}
            {row.keys.length > 0 ? (
              <div className="external-keys" aria-label={`Keys in ${row.name}`}>
                {row.keys.map((key) => <span key={key}>{key}</span>)}
              </div>
            ) : <span className="empty-keys">No keys reported</span>}
            <div className="external-resource-actions">
              {selectsKey ? row.keys.map((key) => (
                <button
                  aria-label={`Use ${row.name} and key ${key}`}
                  disabled={busy}
                  key={key}
                  onClick={() => void select({
                    nodeId: node.id,
                    name: row.name,
                    kind: row.kind,
                    group: row.group,
                    key,
                  }, row.status, row.message)}
                  type="button"
                >
                  Use {key}
                </button>
              )) : (
                <button
                  aria-label={`Use ${row.name}`}
                  disabled={busy}
                  onClick={() => void select({
                    nodeId: node.id,
                    name: row.name,
                    kind: row.kind,
                    group: row.group,
                  }, row.status, row.message)}
                  type="button"
                >
                  Use resource
                </button>
              )}
              {descriptor ? (
                <>
                  <button
                    aria-label={`Inspect ${row.name}`}
                    disabled={loading}
                    onClick={() => void inspect(row.name, "view")}
                    type="button"
                  >
                    <Eye aria-hidden="true" />
                    Inspect
                  </button>
                  <button
                    aria-label={`Update ${row.name}`}
                    disabled={loading}
                    onClick={() => void inspect(row.name, "update")}
                    type="button"
                  >
                    <Pencil aria-hidden="true" />
                    Update
                  </button>
                </>
              ) : null}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
