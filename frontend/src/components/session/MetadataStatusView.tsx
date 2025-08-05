'use client';

import {
  KeyValuePairs,
  StatusIndicator
} from '@cloudscape-design/components';
import { SessionStatusProps } from './types';
import { mapStatus, durationFromTimes } from './statusUtils';
import { useMetadataStatus } from './apiHooks';
import StatusContainer from './StatusContainer';

export default function MetadataStatusView({ sessionName }: SessionStatusProps) {
  const { isLoading, data: metadataData, error } = useMetadataStatus(sessionName);

  return (
    <StatusContainer
      title="Metadata"
      isLoading={isLoading}
      error={error}
      columns={2}
    >
      {metadataData && (
      <KeyValuePairs
        columns={2}
        items={[
          {
            label: 'Status',
            value: <StatusIndicator type={mapStatus(metadataData.status)}></StatusIndicator>
          },
          {
            label: 'Started',
            value: metadataData.started != undefined && new Date(metadataData.started).toLocaleString()
          },
          {
            label: 'Finished',
            value: metadataData.finished != undefined && new Date(metadataData.finished).toLocaleString()
          },
          {
            label: 'Duration',
            value: durationFromTimes(metadataData.started, metadataData.finished)
          }
        ]}
      />
      )}
    </StatusContainer>
  );
}
