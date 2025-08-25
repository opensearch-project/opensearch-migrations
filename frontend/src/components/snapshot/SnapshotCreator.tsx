"use client";

import { useState, useEffect } from "react";
import { usePollingSnapshotStatus } from "@/hooks/apiPoll";
import { Box, Button, SpaceBetween, Alert, Spinner }from "@cloudscape-design/components";
import SnapshotStatusView from "./SnapshotStatusView";
import SnapshotIndexesTable from "./SnapshotIndexesTable";
import { useSnapshotCreateAction, useSnapshotDeleteAction } from "@/hooks/apiAction";

interface SnapshotControllerProps {
  readonly sessionName: string;
}

export default function SnapshotCreator({ sessionName }: SnapshotControllerProps) {
  const [isPollingEnabled, setIsPollingEnabled] = useState(false);
  const [snapshotInProgress, setSnapshotInProgress] = useState(false);

  const {
    run: createSnapshot,
    reset: resetCreate,
    isLoading: isCreating,
    data: createResponse,
    error: createError,
  } = useSnapshotCreateAction();

  const {
    run: removeSnapshot,
    reset: resetDelete,
    isLoading: isDeleting,
    data: deleteResponse,
    error: deleteError,
  } = useSnapshotDeleteAction();
  
  const { 
    isLoading: isStatusLoading,
    data: snapshotStatus, 
    error: statusError,
    isPolling,
    lastUpdated
  } = usePollingSnapshotStatus(sessionName, isPollingEnabled);

  useEffect(() => {
    if (snapshotStatus?.status === "Completed" || snapshotStatus?.status === "Failed") {
      setIsPollingEnabled(false);
      setSnapshotInProgress(false);
    }
  }, [snapshotStatus]);

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
  const error = createError || deleteError || statusError;
  
  return (
    <SpaceBetween size="l">
      <SnapshotStatusView sessionName={sessionName} />
      
      {error && (
        <Alert type="error" header="Error">
          {error}
        </Alert>
      )}
      
      <SpaceBetween direction="horizontal" size="m">
        <Button
          onClick={takeSnapshot}
          loading={isCreating || snapshotInProgress}
          disabled={isCreating || isDeleting || snapshotInProgress || isPolling}
        >
          Take Snapshot
        </Button>
        <Button
          onClick={deleteSnapshot}
          loading={isDeleting}
          disabled={isCreating || isDeleting || !snapshotStatus}
        >
          Delete Snapshot
        </Button>
      </SpaceBetween>
      
      {(
        <Box>
          <Spinner /> Polling for snapshot updates...
          {lastUpdated && (
            <Box variant="p">
              Last updated: {new Date(lastUpdated).toLocaleTimeString()}
            </Box>
          )}
        </Box>
      )}

      {isStatusLoading && !snapshotStatus?.indexes && (
        <Box padding="l">
          <Spinner /> Loading indexes...
        </Box>
      )}

      {statusError && (
        <Alert type="error" header="Error loading indexes">
          {String(statusError)}
        </Alert>
      )}

      {snapshotStatus?.indexes && snapshotStatus.indexes.length > 0 ? (
        <SnapshotIndexesTable 
          indexes={snapshotStatus.indexes}
          maxHeight="300px"
          showFooter={true}
          emptyText="No indexes were found in this snapshot."
          snapshotStatus={snapshotStatus}
        />
      ) : !isStatusLoading && !statusError && (
        <Alert type="info" header="No indexes available">
          No indexes found in the snapshot.
        </Alert>
      )}
    </SpaceBetween>
  );
}
