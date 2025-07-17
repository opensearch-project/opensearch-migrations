'use client';

import { useEffect, useState } from 'react';
import Header from '@cloudscape-design/components/header';
import Container from '@cloudscape-design/components/container';
import SpaceBetween from '@cloudscape-design/components/space-between';
import Spinner from '@cloudscape-design/components/spinner';
import StatusIndicator from '@cloudscape-design/components/status-indicator';
import Flashbar from '@cloudscape-design/components/flashbar';
import Box from '@cloudscape-design/components/box';
// import CopyToClipboard from '@/components/copy-to-clipboard';
import type { paths } from '@/lib/ot';
import createClient from "openapi-fetch";

const client = createClient<paths>({ baseUrl: "http://127.0.0.1:8000" });



export default function Page() {
  const [isReady, setIsReady] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    setTimeout(async () => {
      try {
        const res = await client.GET("/system/health");
        if (res.data?.status === 'ok') {
          setIsReady(true);
          setErrorMessage(null);
        } else {
          console.log("Details: \nerror: " + res.error + "\nbodyUsed: " + res.response.bodyUsed + "\nstatusText:" + res.response.statusText + "\nbody?: " + res.data?.status + "\nChecks?:" + res.data?.checks)

          // const body = await res.response. + ""
          setIsReady(false);
          setErrorMessage("Unable to process due to: " + JSON.stringify(res.data, null, 3));
        }
      } catch (err: any) {
        setIsReady(false);
        setErrorMessage(err.message);
        console.log("Error Response: \n " + JSON.stringify(err, null, 3));
      }
    });
  });

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
