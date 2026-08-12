import { useMemo, useState } from "react";
import Alert from "@cloudscape-design/components/alert";
import AppLayout from "@cloudscape-design/components/app-layout";
import Box from "@cloudscape-design/components/box";
import Button from "@cloudscape-design/components/button";
import ColumnLayout from "@cloudscape-design/components/column-layout";
import Container from "@cloudscape-design/components/container";
import ContentLayout from "@cloudscape-design/components/content-layout";
import FormField from "@cloudscape-design/components/form-field";
import Header from "@cloudscape-design/components/header";
import Input from "@cloudscape-design/components/input";
import ProgressBar from "@cloudscape-design/components/progress-bar";
import Select from "@cloudscape-design/components/select";
import SpaceBetween from "@cloudscape-design/components/space-between";
import SplitPanel from "@cloudscape-design/components/split-panel";
import StatusIndicator from "@cloudscape-design/components/status-indicator";
import Tabs from "@cloudscape-design/components/tabs";
import Toggle from "@cloudscape-design/components/toggle";
import TopNavigation from "@cloudscape-design/components/top-navigation";
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

function ComparisonBar() {
  return (
    <div className="comparison-bar">
      <strong>React UI system comparison</strong>
      <nav className="comparison-links" aria-label="UI system options">
        <a href="/cloudscape.html" aria-current="page">
          Cloudscape
        </a>
        <a href="/react-aria.html">React Aria</a>
      </nav>
    </div>
  );
}

function CloudscapeField({
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
        <Input
          value={control.value}
          placeholder={control.placeholder}
          onChange={({ detail }) => onChange({ ...control, value: detail.value })}
        />
      );
    case "number":
      return (
        <Input
          type="number"
          value={String(control.value)}
          onChange={({ detail }) =>
            onChange({ ...control, value: Number(detail.value) })
          }
        />
      );
    case "boolean":
      return (
        <Toggle
          checked={control.value}
          onChange={({ detail }) =>
            onChange({ ...control, value: detail.checked })
          }
        >
          {control.value ? "Enabled" : "Disabled"}
        </Toggle>
      );
    case "enum":
      return (
        <Select
          selectedOption={
            control.options.find((option) => option.value === control.value) ??
            null
          }
          options={control.options}
          onChange={({ detail }) =>
            onChange({ ...control, value: detail.selectedOption.value ?? "" })
          }
        />
      );
    case "config-map-key": {
      const selectedMap = control.options.find(
        (option) => option.name === control.configMap,
      );
      return (
        <div className="cloudscape-reference">
          <FormField label="ConfigMap">
            <Select
              selectedOption={{ label: control.configMap, value: control.configMap }}
              options={control.options.map((option) => ({
                label: option.name,
                value: option.name,
                description: option.keys.join(", "),
              }))}
              onChange={({ detail }) => {
                const nextMap = detail.selectedOption.value ?? "";
                const option = control.options.find(
                  (candidate) => candidate.name === nextMap,
                );
                onChange({
                  ...control,
                  configMap: nextMap,
                  key: option?.keys[0] ?? "",
                });
              }}
            />
          </FormField>
          <FormField label="Key">
            <Select
              selectedOption={{ label: control.key, value: control.key }}
              options={(selectedMap?.keys ?? []).map((key) => ({
                label: key,
                value: key,
              }))}
              onChange={({ detail }) =>
                onChange({
                  ...control,
                  key: detail.selectedOption.value ?? "",
                })
              }
            />
          </FormField>
          <Box variant="small" color="text-body-secondary">
            {control.configMap}/{control.key}
          </Box>
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
      <Container>
        <Box textAlign="center" color="text-body-secondary" padding="xxl">
          {state.mode === "inspect"
            ? "Configuration is available in edit mode."
            : "Select a resource or configuration group."}
        </Box>
      </Container>
    );
  }

  return (
    <Container
      header={
        <Header
          variant="h2"
          description={`${fields.length} editable values`}
          actions={
            state.nodes["config-replayer"] ? (
              <Button
                iconName={transformAdded ? "status-positive" : "add-plus"}
                disabled={!canAddTransform}
                onClick={onAddTransform}
              >
                {transformAdded ? "Transform added" : "Add transform"}
              </Button>
            ) : null
          }
        >
          Pending configuration
        </Header>
      }
    >
      <SpaceBetween size="l">
        {fields.map((field) => {
          const control = drafts[field.id] ?? field.configControl;
          return (
            <FormField
              key={field.id}
              label={field.label}
              description={field.description}
              stretch
            >
              <CloudscapeField
                field={field}
                control={control}
                onChange={(next) =>
                  setDrafts((current) => ({ ...current, [field.id]: next }))
                }
              />
            </FormField>
          );
        })}
      </SpaceBetween>
    </Container>
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
    <SpaceBetween size="l">
      <Container header={<Header variant="h2">Resource state</Header>}>
        <ColumnLayout columns={4} variant="text-grid">
          <div>
            <Box variant="awsui-key-label">Phase</Box>
            <Box>{selectedNode.phase ?? "Not started"}</Box>
          </div>
          <div>
            <Box variant="awsui-key-label">Status</Box>
            <StatusIndicator
              type={
                selectedNode.severity === "normal"
                  ? "success"
                  : selectedNode.severity === "running"
                    ? "in-progress"
                    : selectedNode.severity === "error"
                      ? "error"
                      : "warning"
              }
            >
              {selectedNode.severity}
            </StatusIndicator>
          </div>
          <div>
            <Box variant="awsui-key-label">Current value</Box>
            <Box>{selectedNode.valueSummary ?? "No value reported"}</Box>
          </div>
          <div>
            <Box variant="awsui-key-label">Resource ID</Box>
            <code>{selectedNode.id}</code>
          </div>
        </ColumnLayout>
      </Container>
      {diagnostics.map((diagnostic) => (
        <Alert
          key={diagnostic.id}
          type={diagnostic.severity === "error" ? "error" : "warning"}
          header={diagnostic.label}
        >
          {diagnostic.description} {diagnostic.diagnostic}
        </Alert>
      ))}
      {diagnostics.length === 0 ? (
        <Alert type="success">No diagnostics for this selection.</Alert>
      ) : null}
    </SpaceBetween>
  );
}

function ActivityPanel({
  operations,
}: {
  operations: ReadonlyArray<OperationState>;
}) {
  return (
    <Container header={<Header variant="h2">Recent activity</Header>}>
      <SpaceBetween size="m">
        {operations.map((operation) => (
          <div className="cloudscape-operation" key={operation.id}>
            <SpaceBetween size="xxs">
              <StatusIndicator
                type={
                  operation.state === "succeeded"
                    ? "success"
                    : operation.state === "running"
                      ? "in-progress"
                      : "pending"
                }
              >
                {operation.label}
              </StatusIndicator>
              <Box variant="small" color="text-body-secondary">
                {operation.phase}
              </Box>
            </SpaceBetween>
            <ProgressBar value={operation.progress} />
          </div>
        ))}
      </SpaceBetween>
    </Container>
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
    <Container
      header={
        <Header
          variant="h2"
          description="traffic-replayer · all matching pods"
          actions={
            <SpaceBetween direction="horizontal" size="xs">
              <Button
                variant={running ? "normal" : "primary"}
                iconName={running ? "status-stopped" : "caret-right-filled"}
                onClick={running ? onStop : onStart}
              >
                {running ? "Stop stream" : "Start stream"}
              </Button>
              <Button iconName="remove" disabled={lines.length === 0} onClick={onClear}>
                Clear
              </Button>
            </SpaceBetween>
          }
        >
          Logs
        </Header>
      }
    >
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
    </Container>
  );
}

function OutputPanel() {
  return (
    <Container header={<Header variant="h2">Managed output</Header>}>
      <SpaceBetween size="m">
        <StatusIndicator type="success">Evaluate capture complete</StatusIndicator>
        <StatusIndicator type="in-progress">
          Migrate live traffic running
        </StatusIndicator>
        <pre className="cloudscape-output">{`target:
  endpoint: search-target:9200
replay:
  accepted: 14,208
  failed: 3
  checkpoint: 2026-08-11T14:22:38Z`}</pre>
      </SpaceBetween>
    </Container>
  );
}

function OperationsPanel({
  operations,
}: {
  operations: ReadonlyArray<OperationState>;
}) {
  return (
    <SplitPanel header={`Operations (${operations.length})`}>
      <SpaceBetween size="m">
        {operations.map((operation) => (
          <div className="cloudscape-operation" key={operation.id}>
            <SpaceBetween size="xxs">
              <StatusIndicator
                type={operation.state === "running" ? "in-progress" : "pending"}
              >
                {operation.label}
              </StatusIndicator>
              <Box variant="small" color="text-body-secondary">
                {operation.phase}
              </Box>
            </SpaceBetween>
            <ProgressBar value={operation.progress} />
          </div>
        ))}
      </SpaceBetween>
    </SplitPanel>
  );
}

export function CloudscapeApp() {
  const manage = useManageExperience();
  const [navigationOpen, setNavigationOpen] = useState(true);
  const [splitPanelOpen, setSplitPanelOpen] = useState(true);

  const capabilityNames = {
    edit: "Edit",
    approve: "Approve",
    reset: "Reset",
    logs: "Logs",
    output: "Output",
  } as const;

  const tabContent: Record<WorkspaceTab, React.ReactNode> = {
    overview: (
      <Overview selectedNode={manage.selectedNode} nodes={manage.state.nodes} />
    ),
    configuration: (
      <ConfigurationPanel
        state={manage.state}
        selectedNode={manage.selectedNode}
        canAddTransform={manage.canAddTransform}
        transformAdded={!!manage.state.nodes["config-transform"]}
        onAddTransform={manage.addTransform}
      />
    ),
    activity: <ActivityPanel operations={manage.operations} />,
    logs: (
      <LogPanel
        lines={manage.logLines}
        running={manage.logRunning}
        onStart={() => manage.setLogRunning(true)}
        onStop={() => manage.setLogRunning(false)}
        onClear={manage.clearLogs}
      />
    ),
    output: <OutputPanel />,
  };

  return (
    <div className="cloudscape-spike">
      <header id="cloudscape-header">
        <ComparisonBar />
        <TopNavigation
          identity={{
            href: "#",
            title: "Workflow Manage",
            onFollow: (event) => event.preventDefault(),
          }}
          search={
            <div className="cloudscape-run-state">
              <StatusIndicator type="in-progress">Running</StatusIndicator>
              <span>migration · ma-demo</span>
            </div>
          }
          utilities={[
            {
              type: "button",
              iconName: "refresh",
              ariaLabel: "Simulate status refresh",
              onClick: manage.refresh,
            },
          ]}
        />
      </header>
      <AppLayout
        headerSelector="#cloudscape-header"
        ariaLabels={{
          navigation: "Workflow resources",
          navigationClose: "Close resource navigation",
          navigationToggle: "Open resource navigation",
          tools: "Tools",
          toolsClose: "Close tools",
          toolsToggle: "Open tools",
        }}
        navigationOpen={navigationOpen}
        onNavigationChange={({ detail }) => setNavigationOpen(detail.open)}
        navigationWidth={330}
        navigation={
          <ResourceTree
            state={manage.state}
            selectedId={manage.selectedNode.id}
            focusedId={manage.focusedId}
            insertedIds={manage.insertedIds}
            onSelect={manage.setSelectedId}
            onFocusChange={manage.setFocusedId}
          />
        }
        toolsHide
        splitPanel={<OperationsPanel operations={manage.operations} />}
        splitPanelOpen={splitPanelOpen}
        onSplitPanelToggle={({ detail }) => setSplitPanelOpen(detail.open)}
        splitPanelPreferences={{ position: "side" }}
        splitPanelSize={320}
        maxContentWidth={Number.MAX_VALUE}
        content={
          <ContentLayout
            header={
              <Header
                variant="h1"
                description={
                  manage.selectedNode.description ?? "Workflow resource"
                }
                actions={
                  <SpaceBetween direction="horizontal" size="xs">
                    {manage.selectedNode.capabilities?.map((capability) => (
                      <Button
                        key={capability}
                        disabled={
                          capability === "edit" &&
                          (manage.state.mode === "edit" || manage.transitioning)
                        }
                        onClick={() => manage.performCapability(capability)}
                      >
                        {capabilityNames[capability]}
                      </Button>
                    ))}
                    <Button
                      variant="primary"
                      iconName="check"
                      disabled={
                        manage.state.mode !== "edit" || manage.transitioning
                      }
                      onClick={manage.review}
                    >
                      Review changes
                    </Button>
                  </SpaceBetween>
                }
              >
                {manage.selectedNode.label}
              </Header>
            }
          >
            <Tabs
              activeTabId={manage.activeTab}
              onChange={({ detail }) =>
                manage.setActiveTab(detail.activeTabId as WorkspaceTab)
              }
              tabs={[
                "overview",
                "configuration",
                "activity",
                "logs",
                "output",
              ].map((id) => ({
                id,
                label: id[0].toUpperCase() + id.slice(1),
                content: tabContent[id as WorkspaceTab],
              }))}
            />
          </ContentLayout>
        }
      />
      <div className="sr-only" aria-live="polite" aria-atomic="true">
        {manage.announcement}
      </div>
    </div>
  );
}
