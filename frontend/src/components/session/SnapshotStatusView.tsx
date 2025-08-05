'use client';

import { useState, useEffect } from 'react';
import {
  KeyValuePairs,
  StatusIndicator,
  Button,
  ButtonDropdown
} from '@cloudscape-design/components';
import SpaceBetween from '@cloudscape-design/components/space-between';
import DebugCommands from '@/components/playground/debug/DebugCommands';
import { SessionStatusProps } from './types';
import { mapStatus, durationFromTimes } from './statusUtils';
import { useSnapshotStatus } from './apiHooks';
import StatusContainer from './StatusContainer';
import { StepState } from '@/generated/api/types.gen';

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
  const [debugData, setDebugData] = useState<any>(null);
  const [isLoading, setIsLoading] = useState(apiLoading);
  const [snapshotData, setSnapshotData] = useState(apiSnapshotData);
  
  // Use effect to update from API unless we're in debug mode
  useEffect(() => {
    if (!debugData) {
      setSnapshotData(apiSnapshotData);
      setIsLoading(apiLoading);
    }
  }, [apiSnapshotData, apiLoading, debugData]);
  
  const applyDebugScenario = (scenario: keyof typeof SNAPSHOT_SCENARIOS) => {
    setDebugData(SNAPSHOT_SCENARIOS[scenario]);
    setSnapshotData(SNAPSHOT_SCENARIOS[scenario]);
    setIsLoading(false);
  };

  const loadingItems = [
    { label: 'Status' },
    { label: 'Started' },
    { label: 'Finished' },
    { label: 'Duration' },
    { label: 'Progress' },
    { label: 'ETA' }
  ];

  return (
    <>
      <StatusContainer
        title="Snapshot"
        isLoading={isLoading}
        error={error}
        loadingItems={loadingItems}
        columns={2}
      >
        {snapshotData && (
        <KeyValuePairs
          columns={2}
          items={[
            {
              label: 'Status',
              value: <StatusIndicator type={mapStatus(snapshotData.status)}></StatusIndicator>
            },
            {
              label: 'Started',
              value: snapshotData.started != undefined && new Date(snapshotData.started).toLocaleString()
            },
            {
              label: 'Finished',
              value: snapshotData.finished != undefined && new Date(snapshotData.finished).toLocaleString()
            },
            {
              label: 'Duration',
              value: durationFromTimes(snapshotData.started, snapshotData.finished)
            },
            {
              label: 'Progress',
              value: `${snapshotData.percentage_completed}%`
            },
            {
              label: 'ETA',
              value: snapshotData.eta_ms ? `${Math.floor(snapshotData.eta_ms / 60000)} minutes` : 'N/A'
            }
          ]}
        />
        )}
      </StatusContainer>
      
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
