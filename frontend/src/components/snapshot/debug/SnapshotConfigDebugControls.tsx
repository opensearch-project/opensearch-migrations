import React from "react";
import SpaceBetween from "@cloudscape-design/components/space-between";
import { Button, ButtonDropdown } from "@cloudscape-design/components";
import DebugCommands from "@/components/debug/DebugCommands";
import { SNAPSHOT_CONFIG_SCENARIOS } from "@/components/snapshot/mockData/snapshotConfigScenarios";

interface SnapshotConfigDebugControlsProps {
  readonly onScenarioSelect: (
    scenario: keyof typeof SNAPSHOT_CONFIG_SCENARIOS,
  ) => void;
  readonly onReset: () => void;
}

export function SnapshotConfigDebugControls({
  onScenarioSelect,
  onReset,
}: SnapshotConfigDebugControlsProps) {
  return (
    <DebugCommands>
      <SpaceBetween size="s" direction="horizontal">
        <ButtonDropdown
          items={[
            { id: "s3Config", text: "S3 Config" },
            { id: "fsConfig", text: "File System Config" },
            { id: "emptyConfig", text: "Empty Config" },
          ]}
          onItemClick={({ detail }) =>
            onScenarioSelect(
              detail.id as keyof typeof SNAPSHOT_CONFIG_SCENARIOS,
            )
          }
        >
          Simulate Scenario
        </ButtonDropdown>
        <Button onClick={onReset}>Reset to API Data</Button>
      </SpaceBetween>
    </DebugCommands>
  );
}
