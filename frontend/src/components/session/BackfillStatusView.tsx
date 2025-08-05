'use client';

import SpaceBetween from '@cloudscape-design/components/space-between';
import {
  KeyValuePairs,
  StatusIndicator
} from '@cloudscape-design/components';
import { SessionStatusProps } from './types';
import { mapStatus, durationFromTimes } from './statusUtils';
import { useBackfillStatus } from './apiHooks';
import StatusContainer from './StatusContainer';

export default function BackfillStatusView({ sessionName }: SessionStatusProps) {
  const { isLoading, data: backfillData, error } = useBackfillStatus(sessionName);

  return (
    <StatusContainer
      title="Backfill"
      isLoading={isLoading}
      error={error}
      columns={2}
    >
      {backfillData && (
        <SpaceBetween size="xxl">
        <KeyValuePairs
          columns={2}
          items={[
            {
              label: 'Status',
              value: <StatusIndicator type={mapStatus(backfillData.status)}></StatusIndicator>
            },
            {
              label: 'Started',
              value: backfillData.started != undefined && new Date(backfillData.started).toLocaleString()
            },
            {
              label: 'Finished',
              value: backfillData.finished != undefined && new Date(backfillData.finished).toLocaleString()
            },
            {
              label: 'Duration',
              value: durationFromTimes(backfillData.started, backfillData.finished)
            }
          ]}
        />
        </SpaceBetween>
      )}
    </StatusContainer>
  );
}
