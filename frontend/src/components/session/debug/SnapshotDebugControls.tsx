'use client';

import { Button, ButtonDropdown } from '@cloudscape-design/components';
import SpaceBetween from '@cloudscape-design/components/space-between';
import DebugCommands from '@/components/playground/debug/DebugCommands';
import { SNAPSHOT_SCENARIOS } from '../mockData/snapshotScenarios';

interface SnapshotDebugControlsProps {
  onScenarioSelect: (scenario: keyof typeof SNAPSHOT_SCENARIOS) => void;
  onReset: () => void;
}

export function SnapshotDebugControls({ onScenarioSelect, onReset }: SnapshotDebugControlsProps) {
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
          onItemClick={({ detail }) => onScenarioSelect(detail.id as keyof typeof SNAPSHOT_SCENARIOS)}
        >
          Simulate Scenario
        </ButtonDropdown>
        <Button onClick={onReset}>Reset to API Data</Button>
      </SpaceBetween>
    </DebugCommands>
  );
}
