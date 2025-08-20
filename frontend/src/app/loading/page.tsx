"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  Box,
  Button,
  Container,
  Header,
  SpaceBetween,
  StatusIndicator,
  Alert,
  KeyValuePairs,
  Spinner,
  ExpandableSection,
} from "@cloudscape-design/components";
import { systemHealth } from "@/generated/api";
import { getSiteReadiness, setSiteReadiness } from "@/lib/site-readiness";
import { withTimeLimit } from "@/utils/async";
import DebugCommands from "@/components/playground/debug/DebugCommands";

const DEFAULT_POLLING_INTERVAL_MS = 5000;

export default function LoadingPage() {
  const [isReady, setIsReady] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const router = useRouter();

  useEffect(() => {
    const pollHealth = async () => {
      if (getSiteReadiness()) {
        setIsReady(true);
        return true;
      }
      try {
        const res = await withTimeLimit(
          systemHealth(),
          DEFAULT_POLLING_INTERVAL_MS,
        );
        if (res.data?.status === "ok") {
          setIsReady(true);
          setSiteReadiness(true);
          return true;
        } else {
          setErrorMessage(JSON.stringify(res.error, null, 2));
        }
      } catch (err) {
        const formatted =
          err instanceof Error ? { name: err.name, message: err.message } : err;
        setErrorMessage(JSON.stringify(formatted, null, 2));
      }
      return false;
    };

    const startPolling = async () => {
      if (await pollHealth()) return;
      const interval = setInterval(async () => {
        if (await pollHealth()) clearInterval(interval);
      }, DEFAULT_POLLING_INTERVAL_MS);
    };

    startPolling();
  }, []);

  return (
    <SpaceBetween size="l">
      <Header
        variant="h1"
        actions={
          <SpaceBetween direction="horizontal" size="xs">
            <Button iconName="refresh" disabled={isReady}></Button>
          </SpaceBetween>
        }
      >
        OpenSearch Migration Assistant
      </Header>
      <Box variant="p">
        Monitor the progress of your migration setup and prepare for next steps.
      </Box>

      <Container
        header={<Header variant="h2">CloudFormation Setup in Progress</Header>}
      >
        <SpaceBetween size="l">
          <Alert
            type="info"
            header={isReady ? "Setup complete" : "Setup in progress"}
            dismissible={false}
          >
            {!isReady && <Spinner size="normal" />}
            {isReady
              ? "The CloudFormation stack has been successfully created. You can now proceed with the data migration process."
              : "The CloudFormation stack is currently being created. This process typically takes 10–15 minutes to complete."}
            {!isReady && errorMessage && (
              <ExpandableSection headerText="Details">
                <pre>Error Message: {errorMessage}</pre>
              </ExpandableSection>
            )}
          </Alert>

          <KeyValuePairs
            items={[
              {
                label: "Status",
                value: isReady ? (
                  <StatusIndicator type="success">Complete</StatusIndicator>
                ) : (
                  <StatusIndicator type="in-progress">
                    In progress
                  </StatusIndicator>
                ),
              },
            ]}
          />

          <Box variant="p">
            You will be notified when the CloudFormation setup is complete. You
            can close this page and return later – your progress will be saved.
          </Box>
        </SpaceBetween>
      </Container>

      <Container
        header={
          <Header
            variant="h2"
            actions={
              isReady && (
                <Button
                  variant="primary"
                  onClick={() => router.push("/migration")}
                  data-testid="start-migration-button"
                >
                  Start data migration
                </Button>
              )
            }
          >
            Next Steps
          </Header>
        }
      >
        <SpaceBetween size="m">
          <Box variant="p">
            {isReady
              ? "Your infrastructure is ready. You can now begin the data migration process."
              : "Once the infrastructure setup is complete, you'll need to configure your migration parameters."}
          </Box>
        </SpaceBetween>
      </Container>
      <DebugCommands>
        <SpaceBetween size="xs" direction="horizontal">
          <Button onClick={() => setIsReady(true)}>Simulate Loaded</Button>
          <Button onClick={() => setIsReady(false)}>Simulate Loading</Button>
          <Button
            onClick={() =>
              setErrorMessage(
                JSON.stringify({ error: "simulated", details: "N/A" }),
              )
            }
          >
            Set Error
          </Button>
          <Button
            onClick={() => {
              setIsReady(false);
              setErrorMessage(null);
            }}
          >
            Reset
          </Button>
        </SpaceBetween>
      </DebugCommands>
    </SpaceBetween>
  );
}
