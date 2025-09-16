"use client";

import { Button, ButtonDropdown } from "@cloudscape-design/components";
import SpaceBetween from "@cloudscape-design/components/space-between";
import DebugCommands from "@/components/debug/DebugCommands";
import { METADATA_SCENARIOS } from "@/components/metadata/mockData/metadataScenarios";

interface MetadataDebugControlsProps {
  readonly onScenarioSelect: (
    scenario: keyof typeof METADATA_SCENARIOS,
  ) => void;
  readonly onReset: () => void;
}

export function MetadataDebugControls({
  onScenarioSelect,
  onReset,
}: MetadataDebugControlsProps) {
  return (
    <DebugCommands>
      <SpaceBetween size="xs" direction="horizontal">
        <ButtonDropdown
          items={[
            { id: "notStarted", text: "Not Started" },
            { id: "inProgress", text: "In Progress" },
            { id: "completedEmpty", text: "Completed (Empty)" },
            { id: "completedWithData", text: "Completed (With Data)" },
            { id: "failed", text: "Failed" },
          ]}
          onItemClick={({ detail }) =>
            onScenarioSelect(detail.id as keyof typeof METADATA_SCENARIOS)
          }
        >
          Simulate Scenario
        </ButtonDropdown>
        <Button onClick={onReset}>Reset to API Data</Button>
      </SpaceBetween>
    </DebugCommands>
  );
}
