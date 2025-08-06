'use client';

import { SessionStatusProps } from './types';
import { useSessionOverview } from './apiHooks';
import StatusContainer from './StatusContainer';
import { StatusFieldDefinition } from './statusUtils';

export default function SessionOverviewView({ sessionName }: Readonly<SessionStatusProps>) {
  const { isLoading, data: sessionData, error } = useSessionOverview(sessionName);

  const fields: StatusFieldDefinition<typeof sessionData>[] = [
    { 
      label: 'Session',
      valueSupplier: (data) => data?.name
    },
    { 
      label: 'Created At',
      valueSupplier: (data) => data?.created && new Date(data.created).toLocaleString() || '-'
    }
  ];
  
  return (
    <StatusContainer 
      title="Session Overview" 
      isLoading={isLoading}
      error={error}
      data={sessionData}
      fields={fields}
      columns={3}
    />
  );
}
