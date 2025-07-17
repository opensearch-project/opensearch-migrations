'use client';

import { useEffect, useState } from 'react';
import Header from '@cloudscape-design/components/header';
import Container from '@cloudscape-design/components/container';
import SpaceBetween from '@cloudscape-design/components/space-between';
import Spinner from '@cloudscape-design/components/spinner';
import StatusIndicator from '@cloudscape-design/components/status-indicator';
import Flashbar from '@cloudscape-design/components/flashbar';
import Box from '@cloudscape-design/components/box';
import { healthSystemHealthGet } from '@/lib/client';
// import CopyToClipboard from '@/components/copy-to-clipboard';

export default function Page() {
  const [isReady, setIsReady] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    const interval = setInterval(async () => {
      try {
        const res = await healthSystemHealthGet()
        if (res.data?.status === 'ok') {
          setIsReady(true);
          setErrorMessage(null);
        } else {
          const body = res.error + ""
          setIsReady(false);
          setErrorMessage(body);
        }
      } catch (err: any) {
        setIsReady(false);
        setErrorMessage(err.message);
      }
    }, 10000);

    return () => clearInterval(interval);
  }, []);

  return (
    <SpaceBetween size="m">
      <Header variant="h1">Migration Assistant Status</Header>

      <Container>
        <SpaceBetween size="m">
          {isReady ? (
            <StatusIndicator type="success">Migration Assistant is ready</StatusIndicator>
          ) : (
            <SpaceBetween size="s">
              <Spinner size="big" />
              <Box variant="p">Checking status...</Box>
            </SpaceBetween>
          )}

          {errorMessage && (
            <Flashbar
              items={[
                {
                  type: 'error',
                  content: (
                    <SpaceBetween size="xs">
                      <Box variant="p">{errorMessage}</Box>
                      {/* <CopyToClipboard text={errorMessage} /> */}
                    </SpaceBetween>
                  ),
                  id: 'error-message',
                },
              ]}
            />
          )}
        </SpaceBetween>
      </Container>
    </SpaceBetween>
  );
}
