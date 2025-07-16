'use client';

import { client } from '../client/client.gen'

import { useEffect, useState, useRef } from 'react';
import {
  Box,
  Alert,
  Spinner,
  SpaceBetween,
  Button,
  Container,
  Header,
  Select,
} from '@cloudscape-design/components';

interface StatusResponse {
  status: 'ok' | 'proxy_error' | 'service_unavailable' | 'unknown_error';
  detail?: string;
}

const SIMULATED_RESPONSES: Record<string, StatusResponse> = {
  ok: { status: 'ok' },
  proxy_error: {
    status: 'proxy_error',
    detail: 'Failed to proxy request to backend service.',
  },
  service_unavailable: {
    status: 'service_unavailable',
    detail: 'The backend service is temporarily unavailable.',
  },
  unknown_error: {
    status: 'unknown_error',
    detail: 'Network timeout or unexpected error.',
  },
};

export default function DefaultPage() {
  const [status, setStatus] = useState<StatusResponse | null>(null);
  const [showDetails, setShowDetails] = useState(false);
  const [simulate, setSimulate] = useState<{ value: string; label: string }>({
    value: 'service_unavailable',
    label: 'Service Unavailable',
  });

  const pollingRef = useRef<NodeJS.Timeout | null>(null);

  const pollStatus = () => {
    const simulatedResponse = SIMULATED_RESPONSES[simulate.value];
    setStatus(simulatedResponse);

    if (simulatedResponse.status === 'ok' && pollingRef.current) {
      clearInterval(pollingRef.current);
    }
  };

  useEffect(() => {
    client.

    pollStatus(); // initial
    pollingRef.current = setInterval(pollStatus, 5000);

    return () => {
      if (pollingRef.current) clearInterval(pollingRef.current);
    };
  }, [simulate]);

  const renderStatusContent = () => {
    if (!status || status.status !== 'ok') {
      return (
        <SpaceBetween size="m">
          <Box display="flex" alignItems="center" gap="s">
            <Spinner size="large" />
            <Box variant="h2">Setting up Migration Assistant...</Box>
          </Box>

          <Alert type="info">
            The service is starting up. We'll automatically connect once it's available.
          </Alert>

          {status?.status !== 'ok' && (
            <>
              <Button onClick={() => setShowDetails(!showDetails)}>
                {showDetails ? 'Hide Error Details' : 'Show Error Details'}
              </Button>

              {showDetails && status?.detail && (
                <Container header={<Header variant="h2">Backend Info</Header>}>
                  <Box variant="code">{status.detail}</Box>
                </Container>
              )}
            </>
          )}
        </SpaceBetween>
      );
    }

    return (
      <Alert type="success" header="Migration Assistant is ready">
        You may now begin using the service.
      </Alert>
    );
  };

  return (
    <Box margin="l">
      <SpaceBetween size="l">
        <Header variant="h1">Welcome to Migration Assistant</Header>

        <Container header={<Header variant="h2">Simulate Backend Status</Header>}>
          <SpaceBetween size="s">
            <Select
              selectedOption={simulate}
              onChange={({ detail }) => setSimulate(detail.selectedOption)}
              options={[
                { value: 'ok', label: 'OK' },
                { value: 'proxy_error', label: 'Proxy Error' },
                { value: 'service_unavailable', label: 'Service Unavailable' },
                { value: 'unknown_error', label: 'Unknown Error' },
              ]}
              selectedAriaLabel="Selected backend status"
              ariaLabel="Simulated backend response"
              placeholder="Choose a response"
            />
            <Box variant="small">
              The selected status will be polled every 5 seconds.
            </Box>
          </SpaceBetween>
        </Container>

        {renderStatusContent()}
      </SpaceBetween>
    </Box>
  );
}
