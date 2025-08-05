'use client';

import { KeyValuePairs } from '@cloudscape-design/components';
import { SessionStatusProps } from './types';
import { useSessionOverview } from './apiHooks';
import StatusContainer from './StatusContainer';

export default function SessionOverviewView({ sessionName }: SessionStatusProps) {
  const { isLoading, data: sessionData, error } = useSessionOverview(sessionName);

  const loadingItems = [
    { label: 'Session' },
    { label: 'Created At' }
  ];
  
  return (
    <StatusContainer 
      title="Session Overview" 
      isLoading={isLoading}
      error={error}
      loadingItems={loadingItems}
      columns={3}
    >
      {sessionData && (
        <KeyValuePairs
          columns={3}
          items={[
            {
              label: 'Session',
              value: sessionData.name
            },
            {
              label: 'Created At',
              value: new Date(sessionData.created).toLocaleString()
            }
          ]}
        />
      )}
    </StatusContainer>
  );
}
