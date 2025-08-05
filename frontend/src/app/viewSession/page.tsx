"use client";

import React, { Suspense, useEffect, useState } from "react";
import {
  SpaceBetween,
  Header,
  Button,
  Spinner,
} from "@cloudscape-design/components";
import { sessionStatus, snapshotStatus } from "@/generated/api";
import DebugCommands from "@/components/playground/debug/DebugCommands";
import { useSearchParams } from "next/navigation";
import SessionStatusView from "@/components/session/SessionStatus";

export default function ViewSessionPage() {
  return (
    <Suspense fallback={<Spinner size="large" />}>
      <ViewSessionPageInner />
    </Suspense>
  );
}

function ViewSessionPageInner() {
  const searchParams = useSearchParams();
  const sessionName = searchParams.get("sessionName");

  const [isReady, setIsReady] = useState(false);
  const [sessionData, setSessionData] = useState<any | null>(null);

  const fetchSession = async () => {
    if (!sessionName) {
      setSessionData({ status: "Not Found" });
      setIsReady(true);
      return;
    }
    try {
      const res = await sessionStatus({ path: { session_name: sessionName } });
      const res2 = await snapshotStatus({ path: { session_name: "fake" } });
      if (res.response.status === 200 && res2.response.status === 200) {
        const data: any = res.data;
        data.snapshot = res2.data;
        setSessionData(data);
        setIsReady(true);
        return;
      }
    } catch (err) {
      console.error("Error loading session:", err);
    }
    setSessionData(null);
    setIsReady(true);
  };

  useEffect(() => {
    fetchSession();
  }, [sessionName]);

  return (
    <SpaceBetween size="m">
      <Header variant="h1">Migration Session - {sessionName}</Header>
      {!isReady && <Spinner size="large"></Spinner>}
      {isReady && <SessionStatusView session={sessionData}></SessionStatusView>}
      <DebugCommands>
        <SpaceBetween size="xs" direction="horizontal">
          <Button onClick={() => fetchSession()}>Reload</Button>
          <Button onClick={() => setIsReady(false)}>Simulate Loading</Button>
          <Button
            onClick={() => {
              setIsReady(false);
              setSessionData(null);
            }}
          >
            Reset
          </Button>
        </SpaceBetween>
      </DebugCommands>
    </SpaceBetween>
  );
}
