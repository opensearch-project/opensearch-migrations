"use client";

import React, { Suspense } from "react";
import { Alert, Header, Spinner } from "@cloudscape-design/components";
import { useSearchParams } from "next/navigation";
import SessionOverviewView from "@/components/session/SessionOverviewView";
import SnapshotStatusView from "@/components/snapshot/SnapshotStatusView";
import SpaceBetween from "@cloudscape-design/components/space-between";
import MetadataStatusView from "@/components/metadata/MetadataStatusView";
import BackfillStatusView from "@/components/backfill/BackfillStatusView";
import { useSnapshotStatus } from "@/hooks/apiFetch";

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
  const {
    isLoading: snapshotIsLoading,
    data: snapshotData,
    error: snapshotError,
  } = useSnapshotStatus(sessionName ?? "");

  if (!sessionName) {
    return (
      <Alert type="error" header={`Unable to find an associated session`}>
        Please create a session or adjust the sessionName parameter in the url.
      </Alert>
    );
  }

  return (
    <SpaceBetween size="m">
      <Header variant="h1">Migration Session - {sessionName}</Header>
      {sessionName && (
        <SpaceBetween size="l">
          <SessionOverviewView sessionName={sessionName} />
          <SnapshotStatusView
            isLoading={snapshotIsLoading}
            data={snapshotData}
            error={snapshotError}
          />
          <MetadataStatusView sessionName={sessionName} />
          <BackfillStatusView sessionName={sessionName} />
        </SpaceBetween>
      )}
    </SpaceBetween>
  );
}
