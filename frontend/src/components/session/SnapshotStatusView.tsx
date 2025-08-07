'use client';

import { useState, useEffect } from 'react';
import { SessionStatusProps, SnapshotData } from './types';
import { StatusFieldDefinition } from './statusUtils';
import { useSnapshotStatus } from './apiHooks';
import StatusContainer from './StatusContainer';
import { 
  StatusDisplay, 
  DateDisplay,
  DurationDisplay,
  ProgressDisplay,
  ETADisplay 
} from './statusComponents';
import { SNAPSHOT_SCENARIOS } from './mockData/snapshotScenarios';
import { SnapshotDebugControls } from './debug/SnapshotDebugControls';

export default function SnapshotStatusView({ sessionName }: Readonly<SessionStatusProps>) {
  const { isLoading: apiLoading, data: apiSnapshotData, error } = useSnapshotStatus(sessionName);
  
  const [debugData, setDebugData] = useState<SnapshotData | null>(null);
  const [isLoading, setIsLoading] = useState(apiLoading);
  const [snapshotData, setSnapshotData] = useState<SnapshotData | null>(null);
  
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
  
  const resetToApiData = () => {
    setDebugData(null);
  };

  const fields: StatusFieldDefinition<SnapshotData>[] = [
    {
      label: 'Status',
      valueSupplier: (data) => <StatusDisplay status={data.status} />
    },
    {
      label: 'Started',
      valueSupplier: (data) => <DateDisplay date={data.started} />
    },
    {
      label: 'Finished',
      valueSupplier: (data) => <DateDisplay date={data.finished} />
    },
    {
      label: 'Duration',
      valueSupplier: (data) => <DurationDisplay started={data.started} finished={data.finished} />
    },
    {
      label: 'Progress',
      valueSupplier: (data) => <ProgressDisplay percentage={data.percentage_completed} />
    },
    {
      label: 'ETA',
      valueSupplier: (data) => <ETADisplay etaMs={data.eta_ms} />
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
      
      <SnapshotDebugControls 
        onScenarioSelect={applyDebugScenario} 
        onReset={resetToApiData} 
      />
    </>
  );
}
