"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  Alert,
  Box,
  Button,
  ColumnLayout,
  Container,
  ExpandableSection,
  Header,
  SpaceBetween,
  Spinner,
} from "@cloudscape-design/components";
import { systemHealth } from "@/generated/api";
import { getSiteReadiness, setSiteReadiness } from "@/lib/site-readiness";
import { withTimeLimit } from "@/utils/async";
import DebugCommands from "@/components/debug/DebugCommands";
import Image from "next/image";

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

  const startMigration = () => router.push("/migration");

  return (
    <SpaceBetween size="l">
      <Header
        variant="h1"
        actions={
          <SpaceBetween direction="horizontal" size="xs">
            <Button iconName="refresh" disabled={isReady} />
          </SpaceBetween>
        }
      >
        OpenSearch Migration Assistant
      </Header>

      <Container
        header={
          <Header variant="h2" description="Steps to migrate your cluster.">
            Migration Overview
          </Header>
        }
      >
        <ColumnLayout columns={3} variant="text-grid">
          <SpaceBetween size="xs">
            <Box fontSize="heading-s" fontWeight="bold">
              <span>Step 1: Create snapshot</span>
            </Box>
            <Box variant="p">
              Create a copy of your source clusters data with a snapshot.
            </Box>
          </SpaceBetween>

          <SpaceBetween size="xs">
            <Box fontSize="heading-s" fontWeight="bold">
              <span>Step 2: Migrate metadata</span>
            </Box>
            <Box variant="p">
              Create the structure of your clusters indices and mappings.
            </Box>
          </SpaceBetween>

          <SpaceBetween size="xs">
            <Box fontSize="heading-s" fontWeight="bold">
              <span>Step 3: Execute backfill</span>
            </Box>
            <Box variant="p">Reindex data into the target cluster.</Box>
          </SpaceBetween>
        </ColumnLayout>

        <Box textAlign="center">
          <Button
            variant="primary"
            onClick={startMigration}
            disabled={!isReady}
            data-testid="overview-start-migration"
          >
            Start migration data
          </Button>
        </Box>
      </Container>

      <Container
        header={
          <Header
            variant="h2"
            actions={
              <Button
                variant="primary"
                disabled={!isReady}
                onClick={startMigration}
                data-testid="start-migration-button"
              >
                Start migration data
              </Button>
            }
          >
            Migration Assistant Setup
          </Header>
        }
      >
        <SpaceBetween size="l">
          <Alert
            type={isReady ? "success" : "info"}
            header={isReady ? "Setup is complete" : "Setup is in progress"}
            dismissible={false}
          >
            {!isReady && <Spinner size="normal" />}
            {isReady
              ? "The Migration Assistant has been successfully created. You can now proceed with the data migration process."
              : "The Migration Assistant setup is in progress. This process typically takes 10–15 minutes to complete. You may close this page and return later as your progress will be saved."}
            {!isReady && errorMessage && (
              <ExpandableSection headerText="Details">
                <pre>Error Message: {errorMessage}</pre>
              </ExpandableSection>
            )}
          </Alert>

          <Box textAlign="center">
            <Image
              src="/robot-dog-162x114.svg"
              width={162}
              height={114}
              alt=""
              style={{ alignContent: "center" }}
            />
            <br />
            <Box variant="p">
              Welcome to your OpenSearch Migration Assistant. Please wait while
              setup is in progress.
            </Box>
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
