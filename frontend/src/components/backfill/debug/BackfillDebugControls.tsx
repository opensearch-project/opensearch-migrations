"use client";

import { Button, ButtonDropdown } from "@cloudscape-design/components";
import SpaceBetween from "@cloudscape-design/components/space-between";
import DebugCommands from "@/components/debug/DebugCommands";
import { BACKFILL_SCENARIOS } from "@/components/backfill/mockData/backfillScenarios";

interface BackfillDebugControlsProps {
  readonly onScenarioSelect: (
    scenario: keyof typeof BACKFILL_SCENARIOS,
  ) => void;
  readonly onReset: () => void;
}

export function BackfillDebugControls({
  onScenarioSelect,
  onReset,
}: BackfillDebugControlsProps) {
  return (
    <DebugCommands>
      <SpaceBetween size="xs" direction="horizontal">
        <ButtonDropdown
          items={[
            { id: "notStarted", text: "Not Started" },
            { id: "inProgress", text: "In Progress (45%)" },
            { id: "nearCompletion", text: "Near Completion (95%)" },
            { id: "completed", text: "Completed (Success)" },
            { id: "completedWithFailures", text: "Completed (With Failures)" },
            { id: "failed", text: "Failed" },
            { id: "pending", text: "Pending" },
          ]}
          onItemClick={({ detail }) =>
            onScenarioSelect(detail.id as keyof typeof BACKFILL_SCENARIOS)
          }
        >
          Simulate Scenario
        </ButtonDropdown>
        <Button onClick={onReset}>Reset to API Data</Button>
      </SpaceBetween>
    </DebugCommands>
  );
}
