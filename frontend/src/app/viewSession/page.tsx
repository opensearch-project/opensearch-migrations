"use client";

import React, { Suspense } from "react";
import { Header, Spinner } from "@cloudscape-design/components";
import { useSearchParams } from "next/navigation";
import SessionOverviewView from "@/components/session/SessionOverviewView";
import SnapshotStatusView from "@/components/session/SnapshotStatusView";
import SpaceBetween from "@cloudscape-design/components/space-between";
import MetadataStatusView from "@/components/session/MetadataStatusView";

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

  return (
    <SpaceBetween size="m">
      <Header variant="h1">Migration Session - {sessionName}</Header>
      {sessionName && (
        <SpaceBetween size="l">
          <SessionOverviewView sessionName={sessionName} />
          <SnapshotStatusView sessionName={sessionName} />
          <MetadataStatusView sessionName={sessionName} />
        </SpaceBetween>
      )}
    </SpaceBetween>
  );
}
