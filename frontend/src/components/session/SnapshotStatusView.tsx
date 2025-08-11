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
        setSnapshotData(apiSnapshotData as SnapshotData);
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
      value: <StatusDisplay status={snapshotData?.status} />
    },
    {
      label: 'Started',
      value: <DateDisplay date={snapshotData?.started} />
    },
    {
      label: 'Finished',
      value: <DateDisplay date={snapshotData?.finished} />
    },
    {
      label: 'Duration',
      value: <DurationDisplay started={snapshotData?.started} finished={snapshotData?.finished} />
    },
    {
      label: 'Progress',
      value: <ProgressDisplay percentage={snapshotData?.percentage_completed} />
    },
    {
      label: 'ETA',
      value: <ETADisplay etaMs={snapshotData?.eta_ms} />
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
