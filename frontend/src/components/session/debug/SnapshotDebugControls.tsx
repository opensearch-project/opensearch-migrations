import React from 'react';
import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Button from "@cloudscape-design/components/button";
import { SNAPSHOT_CONFIG_SCENARIOS } from '../mockData/snapshotConfigScenarios';
import { SNAPSHOT_SCENARIOS } from '../mockData/snapshotScenarios';

interface SnapshotDebugControlsProps {
  readonly onScenarioSelect: (scenario: keyof typeof SNAPSHOT_CONFIG_SCENARIOS) => void;
  readonly onReset: () => void;
}

export function SnapshotDebugControls({ onScenarioSelect, onReset }: SnapshotDebugControlsProps) {
  return (
    <Container
      header={
        <Header variant="h2">Debug Controls (Dev Only)</Header>
      }
    >
      <SpaceBetween size="s">
        <Button onClick={() => onScenarioSelect('s3Config')}>
          S3 Config
        </Button>
        
        <Button onClick={() => onScenarioSelect('fsConfig')}>
          File System Config
        </Button>
        
        <Button onClick={() => onScenarioSelect('emptyConfig')}>
          Empty Config
        </Button>
        
        <Button onClick={onReset}>
          Reset to API Data
        </Button>
      </SpaceBetween>
    </Container>
  );
}
