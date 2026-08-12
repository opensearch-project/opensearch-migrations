import { useMemo, useState } from "react";
import {
  Check,
  ChevronDown,
  FileKey,
  Plus,
} from "lucide-react";
import {
  type ConfigControl,
  type ManageTreeState,
  type TreeNode,
} from "@manage-spike/shared";

interface ConfigurationPanelProps {
  state: ManageTreeState;
  selectedNode: TreeNode;
  canAddTransform: boolean;
  transformAdded: boolean;
  onAddTransform: () => void;
}

function collectConfigFields(
  state: ManageTreeState,
  selectedNode: TreeNode,
): ReadonlyArray<TreeNode & { configControl: ConfigControl }> {
  const fields: Array<TreeNode & { configControl: ConfigControl }> = [];
  const visit = (nodeId: string): void => {
    const node = state.nodes[nodeId];
    if (!node) {
      return;
    }
    if (node.kind === "config-field" && node.configControl) {
      fields.push(node as TreeNode & { configControl: ConfigControl });
    }
    node.childIds.forEach(visit);
  };
  visit(selectedNode.id);
  return fields;
}

function assertNever(control: never): never {
  throw new Error(`Unhandled configuration control: ${JSON.stringify(control)}`);
}

interface FieldControlProps {
  node: TreeNode & { configControl: ConfigControl };
  control: ConfigControl;
  onChange: (control: ConfigControl) => void;
}

function FieldControl({ node, control, onChange }: FieldControlProps) {
  const controlId = `field-${node.id}`;

  switch (control.kind) {
    case "text":
      return (
        <input
          id={controlId}
          type="text"
          value={control.value}
          placeholder={control.placeholder}
          onChange={(event) =>
            onChange({ ...control, value: event.target.value })
          }
        />
      );
    case "number":
      return (
        <input
          id={controlId}
          type="number"
          value={control.value}
          min={control.minimum}
          max={control.maximum}
          onChange={(event) =>
            onChange({ ...control, value: event.target.valueAsNumber })
          }
        />
      );
    case "boolean":
      return (
        <label className="toggle-control" htmlFor={controlId}>
          <input
            id={controlId}
            type="checkbox"
            checked={control.value}
            onChange={(event) =>
              onChange({ ...control, value: event.target.checked })
            }
          />
          <span className="toggle-track" aria-hidden="true">
            <span className="toggle-thumb" />
          </span>
          <span>{control.value ? "Enabled" : "Disabled"}</span>
        </label>
      );
    case "enum":
      return (
        <span className="select-control">
          <select
            id={controlId}
            value={control.value}
            onChange={(event) =>
              onChange({ ...control, value: event.target.value })
            }
          >
            {control.options.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <ChevronDown aria-hidden="true" />
        </span>
      );
    case "config-map-key": {
      const selectedMap = control.options.find(
        (option) => option.name === control.configMap,
      );
      return (
        <div className="reference-control">
          <label>
            <span>ConfigMap</span>
            <span className="select-control">
              <select
                id={controlId}
                value={control.configMap}
                onChange={(event) => {
                  const option = control.options.find(
                    (candidate) => candidate.name === event.target.value,
                  );
                  onChange({
                    ...control,
                    configMap: event.target.value,
                    key: option?.keys[0] ?? "",
                  });
                }}
              >
                {control.options.map((option) => (
                  <option key={option.name} value={option.name}>
                    {option.name}
                  </option>
                ))}
              </select>
              <ChevronDown aria-hidden="true" />
            </span>
          </label>
          <span className="reference-separator">/</span>
          <label>
            <span>Key</span>
            <span className="select-control">
              <select
                value={control.key}
                onChange={(event) =>
                  onChange({ ...control, key: event.target.value })
                }
              >
                {selectedMap?.keys.map((key) => (
                  <option key={key} value={key}>
                    {key}
                  </option>
                ))}
              </select>
              <ChevronDown aria-hidden="true" />
            </span>
          </label>
          <span className="reference-preview">
            <FileKey aria-hidden="true" />
            {control.configMap}/{control.key}
          </span>
        </div>
      );
    }
    default:
      return assertNever(control);
  }
}

export function ConfigurationPanel({
  state,
  selectedNode,
  canAddTransform,
  transformAdded,
  onAddTransform,
}: ConfigurationPanelProps) {
  const fields = useMemo(
    () => collectConfigFields(state, selectedNode),
    [selectedNode, state],
  );
  const [drafts, setDrafts] = useState<
    Readonly<Record<string, ConfigControl>>
  >({});

  if (fields.length === 0) {
    return (
      <div className="workspace-empty">
        <FileKey aria-hidden="true" />
        <h3>No editable values</h3>
        <p>
          {state.mode === "inspect"
            ? "Configuration is available in edit mode."
            : "Select a resource or configuration group."}
        </p>
      </div>
    );
  }

  return (
    <form className="configuration-form" onSubmit={(event) => event.preventDefault()}>
      <div className="configuration-toolbar">
        <div>
          <h3>Pending configuration</h3>
          <span>{fields.length} editable values</span>
        </div>
        {state.nodes["config-replayer"] ? (
          <button
            className="button secondary"
            type="button"
            data-testid="add-transform-control"
            disabled={!canAddTransform}
            onClick={onAddTransform}
          >
            {transformAdded ? (
              <Check aria-hidden="true" />
            ) : (
              <Plus aria-hidden="true" />
            )}
            {transformAdded ? "Transform added" : "Add transform"}
          </button>
        ) : null}
      </div>
      <div className="configuration-fields">
        {fields.map((node) => {
          const control = drafts[node.id] ?? node.configControl;
          return (
            <div className="configuration-field" key={node.id}>
              <div className="field-copy">
                <label htmlFor={`field-${node.id}`}>{node.label}</label>
                {node.description ? <p>{node.description}</p> : null}
              </div>
              <FieldControl
                node={node}
                control={control}
                onChange={(nextControl) =>
                  setDrafts((current) => ({
                    ...current,
                    [node.id]: nextControl,
                  }))
                }
              />
            </div>
          );
        })}
      </div>
    </form>
  );
}
