'use client';

import {
  KeyValuePairs,
  StatusIndicator
} from '@cloudscape-design/components';
import { SessionStatusProps } from './types';
import { mapStatus, durationFromTimes } from './statusUtils';
import { useSnapshotStatus } from './apiHooks';
import StatusContainer from './StatusContainer';

export default function SnapshotStatusView({ sessionName }: SessionStatusProps) {
  const { isLoading, data: snapshotData, error } = useSnapshotStatus(sessionName);

  const loadingItems = [
    { label: 'Status' },
    { label: 'Started' },
    { label: 'Finished' },
    { label: 'Duration' },
    { label: 'Progress' }
  ];

  return (
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
          }
        ]}
      />
      )}
    </StatusContainer>
  );
}
