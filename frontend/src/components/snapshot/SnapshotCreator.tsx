"use client";

import { useState, useEffect } from "react";
import { useSnapshotCreate, useSnapshotDelete, useSnapshotIndexes } from "../../hooks/apiFetch";
import { usePollingSnapshotStatus } from "@/hooks/apiPoll";
import { Box, Button, SpaceBetween, Alert, Spinner }from "@cloudscape-design/components";
import SnapshotStatusView from "./SnapshotStatusView";
import SnapshotIndexesView from "./SnapshotIndexesView";

interface SnapshotControllerProps {
  readonly sessionName: string;
}

export default function SnapshotCreator({ sessionName }: SnapshotControllerProps) {
  const [isPollingEnabled, setIsPollingEnabled] = useState(false);
  const [snapshotInProgress, setSnapshotInProgress] = useState(false);

  const { 
    isLoading: isCreating, 
    data: createResponse, 
    error: createError 
  } = useSnapshotCreate(sessionName);
  
  const { 
    isLoading: isDeleting, 
    data: deleteResponse, 
    error: deleteError 
  } = useSnapshotDelete(sessionName);
  
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
    try {
      setSnapshotInProgress(true);
      setIsPollingEnabled(true);
    } catch (error) {
      console.error("Failed to take snapshot:", error);
      setSnapshotInProgress(false);
      setIsPollingEnabled(false);
    }
  };

  const deleteSnapshot = async () => {
    try {
      setIsPollingEnabled(false);
      setSnapshotInProgress(false);
    } catch (error) {
      console.error("Failed to delete snapshot:", error);
    }
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
      
      {isPolling && (
        <Box>
          <Spinner /> Polling for snapshot updates...
          {lastUpdated && (
            <Box variant="p">
              Last updated: {new Date(lastUpdated).toLocaleTimeString()}
            </Box>
          )}
        </Box>
      )}

      {/* Show indexes with status and selection capability */}
      <SnapshotIndexesView 
        isLoading={isStatusLoading} 
        snapshotIndexes={snapshotStatus?.indexes}
        error={statusError}
        snapshotStatus={snapshotStatus}
      />
    </SpaceBetween>
  );
}
