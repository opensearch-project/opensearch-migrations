"use client";

import { useEffect, useState } from "react";
import Header from "@cloudscape-design/components/header";
import Container from "@cloudscape-design/components/container";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Button from "@cloudscape-design/components/button";
import Alert from "@cloudscape-design/components/alert";
import ExpandableSection from "@cloudscape-design/components/expandable-section";
import { useRouter } from "next/navigation";
import { systemHealth } from "@/generated/api";
import { Box, Spinner } from "@cloudscape-design/components";
import { getSiteReadiness, setSiteReadiness } from "@/lib/site-readiness";
import { withTimeLimit } from "@/utils/async";

const DEFAULT_POLLING_INTERVAL_MS = 5000;

export default function LoadingPage() {
  const [isReady, setIsReady] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const router = useRouter();

  useEffect(() => {
    const pollHealth = async () => {
      try {
        const res = await withTimeLimit(
          systemHealth(),
          DEFAULT_POLLING_INTERVAL_MS,
        );

        if (res.data?.status === "ok" || getSiteReadiness()) {
          setIsReady(true);
          setErrorMessage(null);
          setSiteReadiness(true);
          return true;
        } else {
          setIsReady(false);
          setErrorMessage(JSON.stringify(res.error, null, 2));
        }
      } catch (err) {
        console.error(err);
        if (err instanceof Error) {
          setErrorMessage(
            JSON.stringify(
              {
                name: err.name,
                message: err.message,
              },
              null,
              2,
            ),
          );
        } else {
          // Fallback for other kinds of errors
          setErrorMessage(JSON.stringify(err, null, 2));
        }
        setIsReady(false);
      }
      return false;
    };

    const startPolling = async () => {
      const ready = await pollHealth();
      if (ready) return;

      const interval = setInterval(async () => {
        const success = await pollHealth();
        if (success) clearInterval(interval);
      }, DEFAULT_POLLING_INTERVAL_MS);
    };

    startPolling();
  }, []);

  return (
    <SpaceBetween size="l">
      <Header variant="h1">Migration Assistant is getting ready</Header>

      <Container>
        <SpaceBetween size="m">
          {isReady ? (
            <Alert
              type="success"
              action={
                <Button variant="primary" onClick={() => router.push("/home")}>
                  Enter
                </Button>
              }
              header="Migration assistant is ready"
            ></Alert>
          ) : (
            <Alert
              type="info"
              header="Waiting for Migration Assistant to be ready..."
            >
              <Box variant="span" display="inline">
                <Spinner size="normal" />{" "}
                {errorMessage && (
                  <ExpandableSection headerText="Details">
                    <pre>Error Message: {errorMessage}</pre>
                  </ExpandableSection>
                )}
              </Box>
            </Alert>
          )}
        </SpaceBetween>
      </Container>
    </SpaceBetween>
  );
}
