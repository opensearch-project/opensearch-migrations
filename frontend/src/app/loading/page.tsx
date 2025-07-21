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

export default function LandingPage() {
  const [isReady, setIsReady] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const router = useRouter();

  useEffect(() => {
    const pollHealth = async () => {
      try {
        const res = await systemHealth();

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
        setIsReady(false);
        setErrorMessage(JSON.stringify(err, null, 2));
      }
      return false;
    };

    const startPolling = async () => {
      const ready = await pollHealth();
      if (ready) return;

      const interval = setInterval(async () => {
        const success = await pollHealth();
        if (success) clearInterval(interval);
      }, 5000);
    };

    startPolling();
  }, []);

  return (
    <SpaceBetween size="l">
      <Header variant="h1">Migration Assistant is getting ready</Header>

      <Container>
        <SpaceBetween size="m">
          {isReady ? (
            <Button variant="primary" onClick={() => router.push("/home")}>
              Enter
            </Button>
          ) : (
            <Alert type="info">
              <Box variant="span" display="inline">
                <Spinner size="normal" />{" "}
                <Box variant="span" margin={{ left: "xs" }}>
                  Waiting for Migration Assistant to be ready...
                </Box>
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
