"use client";

import { Button, ButtonDropdown } from "@cloudscape-design/components";
import SpaceBetween from "@cloudscape-design/components/space-between";
import DebugCommands from "@/components/debug/DebugCommands";
import { CLUSTER_SCENARIOS } from "@/components/connection/mockData/clusterScenarios";

interface ClusterDebugControlsProps {
  readonly onScenarioSelect: (scenario: keyof typeof CLUSTER_SCENARIOS) => void;
  readonly onReset: () => void;
}

export function ClusterDebugControls({
  onScenarioSelect,
  onReset,
}: ClusterDebugControlsProps) {
  return (
    <DebugCommands>
      <SpaceBetween size="xs" direction="horizontal">
        <ButtonDropdown
          items={[
            { id: "noAuth", text: "No Authentication" },
            { id: "basicAuthArn", text: "Basic Authentication ARN" },
            { id: "sigV4Auth", text: "SigV4 Authentication" },
            { id: "withVersionOverride", text: "With Version Override" },
            { id: "incomplete", text: "Incomplete Data" },
          ]}
          onItemClick={({ detail }) =>
            onScenarioSelect(detail.id as keyof typeof CLUSTER_SCENARIOS)
          }
        >
          Simulate Cluster Scenario
        </ButtonDropdown>
        <Button onClick={onReset}>Reset to API Data</Button>
      </SpaceBetween>
    </DebugCommands>
  );
}
