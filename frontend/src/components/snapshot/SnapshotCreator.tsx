"use client";

import { useState, useEffect } from "react";
import {
  Box,
  Button,
  SpaceBetween,
  Alert,
  Spinner,
  StatusIndicator,
} from "@cloudscape-design/components";
import SnapshotStatusView from "./SnapshotStatusView";
import SnapshotIndexesTable from "./SnapshotIndexesTable";
import {
  useSnapshotCreateAction,
  useSnapshotDeleteAction,
} from "@/hooks/apiAction";
import { usePollingSnapshotStatus } from "@/hooks/apiPoll";

interface SnapshotControllerProps {
  readonly sessionName: string;
}

export default function SnapshotCreator({
  sessionName,
}: SnapshotControllerProps) {
  const [snapshotInProgress, setSnapshotInProgress] = useState(false);

  const {
    run: createSnapshot,
    reset: resetCreate,
    isLoading: isCreating,
    error: createError,
  } = useSnapshotCreateAction();

  const {
    run: removeSnapshot,
    reset: resetDelete,
    isLoading: isDeleting,
    error: deleteError,
  } = useSnapshotDeleteAction();

  const {
    isLoading: isStatusLoading,
    data: snapshotStatus,
    error: statusError,
    isPolling,
    lastUpdated,
  } = usePollingSnapshotStatus(sessionName, true);

  const indexes = snapshotStatus?.indexes ?? [];

  useEffect(() => {
    if (
      snapshotStatus?.status === "Completed" ||
      snapshotStatus?.status === "Failed"
    ) {
      setSnapshotInProgress(false);
    }
  }, [snapshotStatus?.status]);

  const takeSnapshot = async () => {
    resetCreate();
    resetDelete();
    setSnapshotInProgress(true);
    await createSnapshot(sessionName);
  };

  const deleteSnapshot = async () => {
    resetCreate();
    resetDelete();
    await removeSnapshot(sessionName);
  };

  const error = createError ?? deleteError ?? statusError;

  return (
    <SpaceBetween size="l">
      {/* Prefer passing the already-polled data down to avoid double-polling */}
      <SnapshotStatusView
        isLoading={isStatusLoading}
        data={snapshotStatus}
        error={statusError}
      />

      {error && (
        <Alert type="error" header="Error">
          {String(error)}
        </Alert>
      )}

      <SpaceBetween direction="horizontal" size="m">
        <Button
          onClick={takeSnapshot}
          loading={isCreating ?? snapshotInProgress}
          disabled={isCreating ?? isDeleting ?? snapshotInProgress}
          data-testid="take-snapshot"
        >
          Take Snapshot
        </Button>
        <Button
          onClick={deleteSnapshot}
          loading={isDeleting}
          disabled={isCreating ?? isDeleting ?? !snapshotStatus}
          data-testid="delete-snapshot"
        >
          Delete Snapshot
        </Button>
      </SpaceBetween>

      {isPolling && (
        <Box>
          <StatusIndicator type="loading">
            Polling for snapshot updates…
          </StatusIndicator>
          {lastUpdated && (
            <Box variant="p">
              Last updated: {new Date(lastUpdated).toLocaleTimeString()}
            </Box>
          )}
        </Box>
      )}

      {/* Show a one-time loader only if we have never seen indexes */}
      {isStatusLoading && indexes.length === 0 && (
        <Box padding="l">
          <Spinner /> Loading indexes…
        </Box>
      )}

      {statusError && (
        <Alert type="error" header="Error loading indexes">
          {String(statusError)}
        </Alert>
      )}

      {indexes.length > 0 ? (
        <SnapshotIndexesTable
          indexes={indexes}
          maxHeight="300px"
          showTotalsFooter={true}
          emptyText="No indexes were found in this snapshot."
          snapshotStatus={snapshotStatus}
        />
      ) : (
        !isStatusLoading &&
        !statusError && (
          <Alert type="info" header="No indexes available">
            No indexes found in the snapshot.
          </Alert>
        )
      )}
    </SpaceBetween>
  );
}
