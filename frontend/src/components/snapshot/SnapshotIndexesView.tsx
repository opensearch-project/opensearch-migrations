"use client";

import {
  SnapshotIndex,
  SnapshotIndexStatus,
  SnapshotStatus,
} from "@/generated/api/types.gen";
import Box from "@cloudscape-design/components/box";
import Header from "@cloudscape-design/components/header";
import SpaceBetween from "@cloudscape-design/components/space-between";
import StatusIndicator from "@cloudscape-design/components/status-indicator";
import Alert from "@cloudscape-design/components/alert";
import SnapshotIndexesTable from "./SnapshotIndexesTable";

interface SnapshotIndexesViewProps {
  readonly isLoading: boolean;
  readonly snapshotIndexes:
    | SnapshotIndex[]
    | SnapshotIndexStatus[]
    | null
    | undefined;
  readonly error: string | null;
  readonly snapshotStatus?: SnapshotStatus | null;
}

export default function SnapshotIndexesView({
  isLoading,
  snapshotIndexes,
  error,
  snapshotStatus,
}: SnapshotIndexesViewProps) {
  const hasData = snapshotIndexes && snapshotIndexes.length > 0;

  return (
    <SpaceBetween size="l">
      <Header
        variant="h2"
        description="Review indexes that will be included in the snapshot"
      >
        Review items
      </Header>

      {isLoading && (
        <Box padding="l">
          <StatusIndicator type="loading">Loading indexes...</StatusIndicator>
        </Box>
      )}

      {error && (
        <Alert type="error" header="Error loading indexes">
          {error}
        </Alert>
      )}

      {!isLoading && !error && (
        <>
          {hasData ? (
            <SnapshotIndexesTable
              indexes={snapshotIndexes}
              maxHeight="300px"
              emptyText="There are no indexes on the source cluster."
              snapshotStatus={snapshotStatus}
            />
          ) : (
            <Alert type="info" header="No indexes available">
              No indexes found in the snapshot.
            </Alert>
          )}
        </>
      )}
    </SpaceBetween>
  );
}
