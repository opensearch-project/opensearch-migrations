'use client';

import { SnapshotIndexes } from "@/generated/api/types.gen";
import Box from "@cloudscape-design/components/box";
import Header from "@cloudscape-design/components/header";
import SpaceBetween from "@cloudscape-design/components/space-between";
import StatusIndicator from "@cloudscape-design/components/status-indicator";
import Alert from "@cloudscape-design/components/alert";
import SnapshotIndexesTable from "./SnapshotIndexesTable";

interface SnapshotIndexesViewProps {
  isLoading: boolean;
  snapshotIndexes: SnapshotIndexes | null;
  error: string | null;
}

export default function SnapshotIndexesView({
  isLoading,
  snapshotIndexes,
  error
}: SnapshotIndexesViewProps) {
  const hasData = snapshotIndexes && snapshotIndexes.indexes.length > 0;

  return (
    <SpaceBetween size="l">
      <Header variant="h2" description="Review indexes that will be included in the snapshot">Review items</Header>

      {isLoading && (
        <Box padding="l">
          <StatusIndicator type="loading">Loading indexes...</StatusIndicator>
        </Box>
      )}

      {error && (
        <Alert type="error" header="Error loading indexes">
          {String(error)}
        </Alert>
      )}

      {!isLoading && !error && (
        <>
          {hasData ? (
            <SnapshotIndexesTable 
              indexes={snapshotIndexes.indexes} 
              maxHeight="300px"
              emptyText="There are no indexes on the source cluster."
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
