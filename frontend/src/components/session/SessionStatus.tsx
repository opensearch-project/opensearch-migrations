'use client';

import Header from '@cloudscape-design/components/header';
import Container from '@cloudscape-design/components/container';
import SpaceBetween from '@cloudscape-design/components/space-between';
import {
  KeyValuePairs,
  StatusIndicator,
  StatusIndicatorProps
} from '@cloudscape-design/components';
import { SessionStatus, StepState } from '@/generated/api';

function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return `${h}h ${m}m ${s.toFixed(0)}s`;
}

function mapStatus(state: StepState): StatusIndicatorProps.Type {
    switch(state) {
        case "Pending":
            return "pending";
        case "Running":
            return "in-progress";
        case "Completed":
            return "success";
        case "Failed":
            return "error";
    }
}

interface SessionStatusProps {
    session: SessionStatus;
}

export default function SessionStatusView({session}: SessionStatusProps) {
    if (!session?.name) return null;

  return (
    <SpaceBetween size="l">
      <Container header={<Header variant="h2">Session Overview</Header>}>
        <KeyValuePairs
          columns={3}
          items={[
            {
              label: 'Session',
              value: session.name
            },
            {
              label: 'Created At',
              value: new Date(session.created).toLocaleString()
            }
          ]}
        />
      </Container>

    {/* Snapshot */}
    <Container header={<Header variant="h2">Snapshot</Header>}>
        <KeyValuePairs
          columns={2}
          items={[
            {
              label: 'Status',
              value: <StatusIndicator type={mapStatus(session.snapshot.status)}></StatusIndicator>
            },
            {
              label: 'Started',
              value: session.snapshot.started != undefined && new Date(session.snapshot.started).toLocaleString()
            },
            {
                label: 'Finished',
                value: session.snapshot.finished != undefined && new Date(session.snapshot.finished).toLocaleString()
            },
            {
                label: 'Duration',
                value: durationFromTimes(session.snapshot.started, session.snapshot.finished)
            }
          ]}
        />
      </Container>

      {/* Metadata */}
      <Container header={<Header variant="h2">Metadata</Header>}>
        <KeyValuePairs
          columns={2}
          items={[
            {
              label: 'Status',
              value: <StatusIndicator type={mapStatus(session.metadata.status)}></StatusIndicator>
            },
            {
              label: 'Started',
              value: session.metadata.started != undefined && new Date(session.metadata.started).toLocaleString()
            },
            {
                label: 'Finished',
                value: session.metadata.finished != undefined && new Date(session.metadata.finished).toLocaleString()
            },
            {
                label: 'Duration',
                value: durationFromTimes(session.metadata.started, session.metadata.finished)
            }
          ]}
        />
      </Container>

      {/* Backfill */}
      <Container header={<Header variant="h2">Backfill</Header>}>
        <SpaceBetween size="xxl">
          <KeyValuePairs
            columns={2}
            items={[
                {
                    label: 'Status',
                    value: <StatusIndicator type={mapStatus(session.backfill.status)}></StatusIndicator>
                  },
                  {
                    label: 'Started',
                    value: session.backfill.started != undefined && new Date(session.backfill.started).toLocaleString()
                  },
                  {
                      label: 'Finished',
                      value: session.backfill.finished != undefined && new Date(session.backfill.finished).toLocaleString()
                  },
                  {
                      label: 'Duration',
                      value: durationFromTimes(session.backfill.started, session.backfill.finished)
                  }
            ]}
          />
        </SpaceBetween>
      </Container>
    </SpaceBetween>
  );
}

function durationFromTimes(start: string | undefined | null, end: string | undefined | null) {
  return start != null
    && end != null
    && formatDuration(((new Date(end).getTime() - new Date(start).getTime()))/1000);
}
