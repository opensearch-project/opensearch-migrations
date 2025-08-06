'use client';

import { useState, useEffect } from 'react';
import { StatusIndicator, Button, ButtonDropdown } from '@cloudscape-design/components';
import SpaceBetween from '@cloudscape-design/components/space-between';
import DebugCommands from '@/components/playground/debug/DebugCommands';
import { SessionStatusProps } from './types';
import { mapStatus, durationFromTimes, StatusFieldDefinition } from './statusUtils';
import { useSnapshotStatus } from './apiHooks';
import StatusContainer from './StatusContainer';
import { StepState } from '@/generated/api/types.gen';

type SnapshotData = {
  status: StepState;
  percentage_completed: number;
  eta_ms: number | null;
  started?: string;
  finished?: string;
};

// Debug scenario mock data
const SNAPSHOT_SCENARIOS = {
  notStarted: {
    status: "Pending" as StepState,
    percentage_completed: 0,
    eta_ms: null,
    started: undefined,
    finished: undefined,
  },
  inProgress: {
    status: "Running" as StepState,
    percentage_completed: 25,
    eta_ms: 7200000, // 2 hours in milliseconds
    started: new Date(Date.now() - 1800000).toISOString(), // Started 30 minutes ago
    finished: undefined,
  },
  almostDone: {
    status: "Running" as StepState,
    percentage_completed: 99,
    eta_ms: 60000, // 1 minute in milliseconds
    started: new Date(Date.now() - 3600000).toISOString(), // Started 1 hour ago
    finished: undefined,
  },
  completed: {
    status: "Completed" as StepState,
    percentage_completed: 100,
    eta_ms: null,
    started: new Date(Date.now() - 3600000).toISOString(), // Started 1 hour ago
    finished: new Date().toISOString(), // Just finished
  },
  failed: {
    status: "Failed" as StepState,
    percentage_completed: 45,
    eta_ms: null,
    started: new Date(Date.now() - 1800000).toISOString(), // Started 30 minutes ago
    finished: new Date(Date.now() - 600000).toISOString(), // Failed 10 minutes ago
  }
};

export default function SnapshotStatusView({ sessionName }: SessionStatusProps) {
  const { isLoading: apiLoading, data: apiSnapshotData, error } = useSnapshotStatus(sessionName);
  
  // Override data for debug mode
  const [debugData, setDebugData] = useState<SnapshotData | null>(null);
  const [isLoading, setIsLoading] = useState(apiLoading);
  const [snapshotData, setSnapshotData] = useState<SnapshotData | null>(null);
  
  // Use effect to update from API unless we're in debug mode
  useEffect(() => {
    if (!debugData) {
      // Only update if there's actual API data
      if (apiSnapshotData) {
        setSnapshotData(apiSnapshotData as unknown as SnapshotData);
      } else {
        setSnapshotData(null);
      }
      setIsLoading(apiLoading);
    }
  }, [apiSnapshotData, apiLoading, debugData]);
  
  const applyDebugScenario = (scenario: keyof typeof SNAPSHOT_SCENARIOS) => {
    setDebugData(SNAPSHOT_SCENARIOS[scenario]);
    setSnapshotData(SNAPSHOT_SCENARIOS[scenario]);
    setIsLoading(false);
  };

  // Define the fields once for both loading and data display
  const fields: StatusFieldDefinition<SnapshotData>[] = [
    {
      label: 'Status',
      valueSupplier: (data) => <StatusIndicator type={mapStatus(data.status)}></StatusIndicator>
    },
    {
      label: 'Started',
      valueSupplier: (data) => data.started != undefined && new Date(data.started).toLocaleString() || '-'
    },
    {
      label: 'Finished',
      valueSupplier: (data) => data.finished != undefined && new Date(data.finished).toLocaleString() || '-'
    },
    {
      label: 'Duration',
      valueSupplier: (data) => durationFromTimes(data.started, data.finished) || '-'
    },
    {
      label: 'Progress',
      valueSupplier: (data) => `${data.percentage_completed}%`
    },
    {
      label: 'ETA',
      valueSupplier: (data) => data.eta_ms ? `${Math.floor(data.eta_ms / 60000)} minutes` : 'N/A'
    }
  ];

  return (
    <>
      <StatusContainer
        title="Snapshot"
        isLoading={isLoading}
        error={error}
        data={snapshotData}
        fields={fields}
        columns={2}
      />
      
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
            onItemClick={({ detail }) => applyDebugScenario(detail.id as keyof typeof SNAPSHOT_SCENARIOS)}
          >
            Simulate Scenario
          </ButtonDropdown>
          <Button onClick={() => {setDebugData(null)}}>Reset to API Data</Button>
        </SpaceBetween>
      </DebugCommands>
    </>
  );
}
