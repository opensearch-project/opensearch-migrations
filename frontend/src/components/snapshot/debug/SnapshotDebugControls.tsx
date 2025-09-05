"use client";

import { Button, ButtonDropdown } from "@cloudscape-design/components";
import SpaceBetween from "@cloudscape-design/components/space-between";
import { SNAPSHOT_SCENARIOS } from "@/components/snapshot/mockData/snapshotScenarios";
import DebugCommands from "@/components/debug/DebugCommands";

interface SnapshotDebugControlsProps {
  readonly onScenarioSelect: (
    scenario: keyof typeof SNAPSHOT_SCENARIOS,
  ) => void;
  readonly onReset: () => void;
}

export function SnapshotDebugControls({
  onScenarioSelect,
  onReset,
}: SnapshotDebugControlsProps) {
  return (
    <DebugCommands>
      <SpaceBetween size="xs" direction="horizontal">
        <ButtonDropdown
          items={[
            { id: "notStarted", text: "Not Started" },
            { id: "inProgress", text: "In Progress (2hr ETA)" },
            { id: "almostDone", text: "Almost Done (99%)" },
            { id: "completed", text: "Completed" },
            { id: "failed", text: "Failed" },
          ]}
          onItemClick={({ detail }) =>
            onScenarioSelect(detail.id as keyof typeof SNAPSHOT_SCENARIOS)
          }
        >
          Simulate Scenario
        </ButtonDropdown>
        <Button onClick={onReset}>Reset to API Data</Button>
      </SpaceBetween>
    </DebugCommands>
  );
}
