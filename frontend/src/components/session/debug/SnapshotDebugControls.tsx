import React from 'react';
import SpaceBetween from "@cloudscape-design/components/space-between";
import { Button, ButtonDropdown } from "@cloudscape-design/components";
import { SNAPSHOT_CONFIG_SCENARIOS } from '../mockData/snapshotConfigScenarios';
import DebugCommands from '@/components/playground/debug/DebugCommands';

interface SnapshotDebugControlsProps {
  readonly onScenarioSelect: (scenario: keyof typeof SNAPSHOT_CONFIG_SCENARIOS) => void;
  readonly onReset: () => void;
}

export function SnapshotDebugControls({ onScenarioSelect, onReset }: SnapshotDebugControlsProps) {
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
            onScenarioSelect(detail.id as keyof typeof SNAPSHOT_CONFIG_SCENARIOS)
          }
        >
          Simulate Scenario
        </ButtonDropdown>
        <Button onClick={onReset}>
          Reset to API Data
        </Button>
      </SpaceBetween>
    </DebugCommands>
  );
}
