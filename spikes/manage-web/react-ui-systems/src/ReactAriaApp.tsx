import { useMemo, useState } from "react";
import {
  Button,
  Input,
  Label,
  ListBox,
  ListBoxItem,
  Popover,
  ProgressBar,
  Select,
  SelectValue,
  Switch,
  Tab,
  TabList,
  TabPanel,
  Tabs,
  TextField,
} from "react-aria-components";
import {
  Activity,
  AlertTriangle,
  Check,
  ChevronDown,
  CircleDot,
  ClipboardCheck,
  FileOutput,
  Gauge,
  ListTree,
  Logs,
  Pause,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  RotateCcw,
  Settings2,
  ShieldCheck,
  SquareActivity,
  Trash2,
  X,
} from "lucide-react";
import {
  type ConfigControl,
  type OperationState,
  type TreeNode,
} from "@manage-spike/shared";
import { ResourceTree } from "../../react/src/ResourceTree";
import {
  assertNever,
  collectConfigFields,
  collectDiagnostics,
  type ConfigField,
} from "./model";
import {
  useManageExperience,
  type WorkspaceTab,
} from "./useManageExperience";

const tabs: ReadonlyArray<{
  id: WorkspaceTab;
  label: string;
  Icon: typeof Gauge;
}> = [
  { id: "overview", label: "Overview", Icon: Gauge },
  { id: "configuration", label: "Configuration", Icon: Settings2 },
  { id: "activity", label: "Activity", Icon: Activity },
  { id: "logs", label: "Logs", Icon: Logs },
  { id: "output", label: "Output", Icon: FileOutput },
];

function ComparisonBar() {
  return (
    <div className="comparison-bar">
      <strong>React UI system comparison</strong>
      <nav className="comparison-links" aria-label="UI system options">
        <a href="/cloudscape.html">Cloudscape</a>
        <a href="/react-aria.html" aria-current="page">
          React Aria
        </a>
      </nav>
    </div>
  );
}

function AriaSelect({
  label,
  selectedKey,
  options,
  onChange,
}: {
  label: string;
  selectedKey: string;
  options: ReadonlyArray<{
    value: string;
    label: string;
    description?: string;
  }>;
  onChange: (value: string) => void;
}) {
  return (
    <Select
      className="aria-select"
      aria-label={label}
      selectedKey={selectedKey}
      onSelectionChange={(key) => onChange(String(key))}
    >
      <Button>
        <SelectValue />
        <ChevronDown aria-hidden="true" />
      </Button>
      <Popover className="aria-popover">
        <ListBox className="aria-listbox">
          {options.map((option) => (
            <ListBoxItem
              className="aria-listbox-item"
              id={option.value}
              key={option.value}
              textValue={option.label}
            >
              <span>{option.label}</span>
              {option.description ? <small>{option.description}</small> : null}
            </ListBoxItem>
          ))}
        </ListBox>
      </Popover>
    </Select>
  );
}

function AriaField({
  field,
  control,
  onChange,
}: {
  field: ConfigField;
  control: ConfigControl;
  onChange: (next: ConfigControl) => void;
}) {
  switch (control.kind) {
    case "text":
      return (
        <TextField
          className="aria-text-field"
          aria-label={field.label}
          value={control.value}
          onChange={(value) => onChange({ ...control, value })}
        >
          <Input placeholder={control.placeholder} />
        </TextField>
      );
    case "number":
      return (
        <TextField
          className="aria-text-field"
          aria-label={field.label}
          value={String(control.value)}
          onChange={(value) => onChange({ ...control, value: Number(value) })}
        >
          <Input
            type="number"
            min={control.minimum}
            max={control.maximum}
          />
        </TextField>
      );
    case "boolean":
      return (
        <Switch
          className="aria-switch"
          aria-label={field.label}
          isSelected={control.value}
          onChange={(isSelected) =>
            onChange({ ...control, value: isSelected })
          }
        >
          <span className="aria-switch-track" aria-hidden="true">
            <span />
          </span>
          {control.value ? "Enabled" : "Disabled"}
        </Switch>
      );
    case "enum":
      return (
        <AriaSelect
          label={field.label}
          selectedKey={control.value}
          options={control.options}
          onChange={(value) => onChange({ ...control, value })}
        />
      );
    case "config-map-key": {
      const selectedMap = control.options.find(
        (option) => option.name === control.configMap,
      );
      return (
        <div className="aria-reference">
          <div>
            <span className="aria-control-label">ConfigMap</span>
            <AriaSelect
              label={`${field.label} ConfigMap`}
              selectedKey={control.configMap}
              options={control.options.map((option) => ({
                value: option.name,
                label: option.name,
                description: option.keys.join(", "),
              }))}
              onChange={(configMap) => {
                const option = control.options.find(
                  (candidate) => candidate.name === configMap,
                );
                onChange({
                  ...control,
                  configMap,
                  key: option?.keys[0] ?? "",
                });
              }}
            />
          </div>
          <div>
            <span className="aria-control-label">Key</span>
            <AriaSelect
              label={`${field.label} key`}
              selectedKey={control.key}
              options={(selectedMap?.keys ?? []).map((key) => ({
                value: key,
                label: key,
              }))}
              onChange={(key) => onChange({ ...control, key })}
            />
          </div>
          <code className="aria-reference-preview">
            {control.configMap}/{control.key}
          </code>
        </div>
      );
    }
    default:
      return assertNever(control);
  }
}

function ConfigurationPanel({
  state,
  selectedNode,
  canAddTransform,
  transformAdded,
  onAddTransform,
}: {
  state: ReturnType<typeof useManageExperience>["state"];
  selectedNode: TreeNode;
  canAddTransform: boolean;
  transformAdded: boolean;
  onAddTransform: () => void;
}) {
  const fields = useMemo(
    () => collectConfigFields(state, selectedNode),
    [selectedNode, state],
  );
  const [drafts, setDrafts] = useState<
    Readonly<Record<string, ConfigControl>>
  >({});

  if (fields.length === 0) {
    return (
      <div className="aria-empty">
        <Settings2 aria-hidden="true" />
        <strong>No editable values</strong>
        <span>
          {state.mode === "inspect"
            ? "Configuration is available in edit mode."
            : "Select a resource or configuration group."}
        </span>
      </div>
    );
  }

  return (
    <form className="aria-configuration" onSubmit={(event) => event.preventDefault()}>
      <div className="aria-section-header">
        <div>
          <h3>Pending configuration</h3>
          <span>{fields.length} editable values</span>
        </div>
        {state.nodes["config-replayer"] ? (
          <Button
            className="aria-button secondary"
            isDisabled={!canAddTransform}
            onPress={onAddTransform}
          >
            {transformAdded ? (
              <Check aria-hidden="true" />
            ) : (
              <Plus aria-hidden="true" />
            )}
            {transformAdded ? "Transform added" : "Add transform"}
          </Button>
        ) : null}
      </div>
      <div className="aria-fields">
        {fields.map((field) => {
          const control = drafts[field.id] ?? field.configControl;
          return (
            <div className="aria-field" key={field.id}>
              <div className="aria-field-copy">
                <Label>{field.label}</Label>
                <p>{field.description}</p>
              </div>
              <AriaField
                field={field}
                control={control}
                onChange={(next) =>
                  setDrafts((current) => ({ ...current, [field.id]: next }))
                }
              />
            </div>
          );
        })}
      </div>
    </form>
  );
}

function Overview({
  selectedNode,
  nodes,
}: {
  selectedNode: TreeNode;
  nodes: Readonly<Record<string, TreeNode>>;
}) {
  const diagnostics = collectDiagnostics(nodes, selectedNode);
  return (
    <div className="aria-overview">
      <dl className="aria-facts">
        <div>
          <dt>Phase</dt>
          <dd>{selectedNode.phase ?? "Not started"}</dd>
        </div>
        <div>
          <dt>Status</dt>
          <dd>
            <span
              className={`aria-status aria-status-${selectedNode.severity}`}
            >
              <CircleDot aria-hidden="true" />
              {selectedNode.severity}
            </span>
          </dd>
        </div>
        <div>
          <dt>Current value</dt>
          <dd>{selectedNode.valueSummary ?? "No value reported"}</dd>
        </div>
        <div>
          <dt>Resource ID</dt>
          <dd>
            <code>{selectedNode.id}</code>
          </dd>
        </div>
      </dl>
      <section className="aria-diagnostics">
        <h3>Diagnostics</h3>
        {diagnostics.length > 0 ? (
          diagnostics.map((diagnostic) => (
            <details key={diagnostic.id}>
              <summary>
                <AlertTriangle aria-hidden="true" />
                <span>{diagnostic.label}</span>
                <strong>{diagnostic.severity}</strong>
              </summary>
              <p>{diagnostic.description}</p>
              <p>{diagnostic.diagnostic}</p>
            </details>
          ))
        ) : (
          <div className="aria-inline-success">
            <Check aria-hidden="true" />
            No diagnostics for this selection.
          </div>
        )}
      </section>
    </div>
  );
}

function Operation({
  operation,
}: {
  operation: OperationState;
}) {
  return (
    <article className="aria-operation">
      <div>
        <span className={`aria-operation-state state-${operation.state}`}>
          {operation.state}
        </span>
        <strong>{operation.label}</strong>
        <p>{operation.phase}</p>
      </div>
      <ProgressBar
        className="aria-progress"
        aria-label={`${operation.label} progress`}
        value={operation.progress}
      >
        <div>
          <span style={{ width: `${operation.progress}%` }} />
        </div>
      </ProgressBar>
    </article>
  );
}

function ActivityPanel({
  operations,
}: {
  operations: ReadonlyArray<OperationState>;
}) {
  return (
    <section className="aria-activity">
      <div className="aria-section-header">
        <div>
          <h3>Recent activity</h3>
          <span>Cluster and user initiated work</span>
        </div>
      </div>
      {operations.map((operation) => (
        <Operation operation={operation} key={operation.id} />
      ))}
    </section>
  );
}

function LogPanel({
  lines,
  running,
  onStart,
  onStop,
  onClear,
}: {
  lines: ReadonlyArray<string>;
  running: boolean;
  onStart: () => void;
  onStop: () => void;
  onClear: () => void;
}) {
  return (
    <section className="aria-logs">
      <div className="aria-section-header">
        <div>
          <h3>Logs</h3>
          <span>traffic-replayer · all matching pods</span>
        </div>
        <div className="aria-button-row">
          <Button
            className={`aria-button ${running ? "danger" : "primary"}`}
            onPress={running ? onStop : onStart}
          >
            {running ? <Pause aria-hidden="true" /> : <Play aria-hidden="true" />}
            {running ? "Stop stream" : "Start stream"}
          </Button>
          <Button
            className="aria-icon-button"
            aria-label="Clear logs"
            isDisabled={lines.length === 0}
            onPress={onClear}
          >
            <Trash2 aria-hidden="true" />
          </Button>
        </div>
      </div>
      <div className="log-console" role="log" aria-label="Resource logs">
        {lines.length === 0 ? (
          <span className="log-placeholder">Log stream is stopped.</span>
        ) : (
          lines.map((line, index) => (
            <div className="log-line" key={`${index}-${line}`}>
              <span>{String(index + 1).padStart(3, "0")}</span>
              <code>{line}</code>
            </div>
          ))
        )}
      </div>
    </section>
  );
}

function OutputPanel() {
  return (
    <section className="aria-output">
      <div className="aria-section-header">
        <div>
          <h3>Managed output</h3>
          <span>Latest retained result for this resource</span>
        </div>
      </div>
      <div className="aria-stage">
        <Check aria-hidden="true" />
        <div>
          <strong>Evaluate capture</strong>
          <span>Complete</span>
        </div>
      </div>
      <div className="aria-stage running">
        <RefreshCw aria-hidden="true" />
        <div>
          <strong>Migrate live traffic</strong>
          <span>Running</span>
        </div>
      </div>
      <pre>{`target:
  endpoint: search-target:9200
replay:
  accepted: 14,208
  failed: 3
  checkpoint: 2026-08-11T14:22:38Z`}</pre>
    </section>
  );
}

export function ReactAriaApp() {
  const manage = useManageExperience();
  const capabilityDetails = {
    edit: { label: "Edit", Icon: Pencil },
    approve: { label: "Approve", Icon: ShieldCheck },
    reset: { label: "Reset", Icon: RotateCcw },
    logs: { label: "Logs", Icon: Logs },
    output: { label: "Output", Icon: FileOutput },
  } as const;

  return (
    <div className="aria-spike">
      <header className="aria-header">
        <ComparisonBar />
        <div className="aria-product-bar">
          <div className="aria-brand">
            <span>
              <SquareActivity aria-hidden="true" />
            </span>
            <div>
              <h1>Workflow Manage</h1>
              <p>migration · ma-demo</p>
            </div>
          </div>
          <div className="aria-run-state">
            <span />
            Running
            <small>Observed just now</small>
          </div>
          <div className="aria-button-row">
            <Button
              className="aria-icon-button"
              aria-label="Simulate status refresh"
              onPress={manage.refresh}
            >
              <RefreshCw aria-hidden="true" />
            </Button>
            <Button
              className="aria-button secondary"
              isDisabled={manage.transitioning}
              onPress={
                manage.state.mode === "edit"
                  ? manage.leaveEditMode
                  : manage.enterEditMode
              }
            >
              {manage.state.mode === "edit" ? (
                <X aria-hidden="true" />
              ) : (
                <Pencil aria-hidden="true" />
              )}
              {manage.state.mode === "edit"
                ? "Leave edit mode"
                : "Edit configuration"}
            </Button>
            <Button
              className="aria-button primary"
              isDisabled={
                manage.state.mode !== "edit" || manage.transitioning
              }
              onPress={manage.review}
            >
              <ClipboardCheck aria-hidden="true" />
              Review changes
            </Button>
          </div>
        </div>
      </header>

      <div className="aria-layout">
        <ResourceTree
          state={manage.state}
          selectedId={manage.selectedNode.id}
          focusedId={manage.focusedId}
          insertedIds={manage.insertedIds}
          onSelect={manage.setSelectedId}
          onFocusChange={manage.setFocusedId}
        />

        <main className="aria-workspace">
          <div className="aria-selection-header">
            <div className="aria-selection-title">
              <span>
                <ListTree aria-hidden="true" />
              </span>
              <div>
                <small>
                  {manage.selectedNode.kind.replace("-", " ")} ·{" "}
                  {manage.selectedNode.phase ?? manage.selectedNode.severity}
                </small>
                <h2>{manage.selectedNode.label}</h2>
                <p>
                  {manage.selectedNode.description ?? "Workflow resource"}
                </p>
              </div>
            </div>
            <div className="aria-button-row">
              {manage.selectedNode.capabilities?.map((capability) => {
                const details = capabilityDetails[capability];
                return (
                  <Button
                    className="aria-button quiet"
                    key={capability}
                    isDisabled={
                      capability === "edit" &&
                      (manage.state.mode === "edit" || manage.transitioning)
                    }
                    onPress={() => manage.performCapability(capability)}
                  >
                    <details.Icon aria-hidden="true" />
                    {details.label}
                  </Button>
                );
              })}
            </div>
          </div>

          <Tabs
            className="aria-tabs"
            selectedKey={manage.activeTab}
            onSelectionChange={(key) =>
              manage.setActiveTab(String(key) as WorkspaceTab)
            }
          >
            <TabList aria-label="Resource details">
              {tabs.map(({ id, label, Icon }) => (
                <Tab id={id} key={id}>
                  <Icon aria-hidden="true" />
                  {label}
                  {id === "logs" && manage.logRunning ? (
                    <span className="aria-tab-live" aria-label="streaming" />
                  ) : null}
                </Tab>
              ))}
            </TabList>
            <TabPanel id="overview">
              <Overview
                selectedNode={manage.selectedNode}
                nodes={manage.state.nodes}
              />
            </TabPanel>
            <TabPanel id="configuration">
              <ConfigurationPanel
                state={manage.state}
                selectedNode={manage.selectedNode}
                canAddTransform={manage.canAddTransform}
                transformAdded={!!manage.state.nodes["config-transform"]}
                onAddTransform={manage.addTransform}
              />
            </TabPanel>
            <TabPanel id="activity">
              <ActivityPanel operations={manage.operations} />
            </TabPanel>
            <TabPanel id="logs">
              <LogPanel
                lines={manage.logLines}
                running={manage.logRunning}
                onStart={() => manage.setLogRunning(true)}
                onStop={() => manage.setLogRunning(false)}
                onClear={manage.clearLogs}
              />
            </TabPanel>
            <TabPanel id="output">
              <OutputPanel />
            </TabPanel>
          </Tabs>
        </main>

        <aside className="aria-operations" aria-label="Operations">
          <div className="aria-section-header">
            <div>
              <h2>Operations</h2>
              <span>
                {
                  manage.operations.filter(
                    (operation) => operation.state !== "succeeded",
                  ).length
                }{" "}
                active
              </span>
            </div>
            <strong>
              <span />
              Live
            </strong>
          </div>
          <div className="aria-operation-list">
            {manage.operations.map((operation) => (
              <Operation operation={operation} key={operation.id} />
            ))}
          </div>
        </aside>
      </div>
      <div className="sr-only" aria-live="polite" aria-atomic="true">
        {manage.announcement}
      </div>
    </div>
  );
}
