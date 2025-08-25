"use client";

import { useState } from "react";
import { useCreateSnapshot, useSnapshotDelete } from "../../hooks/apiFetch";
import { formatBytes } from "@/utils/sizeLimits";
import SnapshotIndexesTable from "./SnapshotIndexesTable";
import { Box, Button, Header, SpaceBetween, StatusIndicator, Alert }from "@cloudscape-design/components";

interface SnapshotControllerProps {
  readonly sessionName: string;
}

export default function SnapshotCreator({ sessionName }: SnapshotControllerProps) {
  const [isCreatingSnapshot, setIsCreatingSnapshot] = useState(false);
  const [createSnapshotError, setCreateSnapshotError] = useState<string | null>(null);
  
  // Poll status when creating a snapshot
  const {
    isLoading: isLoadingStatus,
    data: snapshotStatusData,
    error: snapshotStatusError,
    isPolling,
    startPolling,
    stopPolling
  } = usePollingSnapshotStatus(sessionName, isCreatingSnapshot);
  
  // Extract indexes from status data, ensure it's always an array
  const indexes = snapshotStatusData?.indexes || [];

  const handleCreateSnapshot = async () => {
    setIsCreatingSnapshot(true);
    setCreateSnapshotError(null);
    
    try {
      const result = await createSnapshot(sessionName);
      
      if (!result.success) {
        setCreateSnapshotError(result.error || "Failed to create snapshot");
        setIsCreatingSnapshot(false);
        return;
      }
      
      // Start polling for status updates
      startPolling();
    } catch (error) {
      setCreateSnapshotError(error instanceof Error ? error.message : "Unknown error occurred");
      setIsCreatingSnapshot(false);
    }
  };


  const renderSnapshotStatus = () => {
    if (!isPolling && !snapshotStatusData) return null;
    
    if (isLoadingStatus && !snapshotStatusData) {
      return (
        <Box padding="l">
          <StatusIndicator type="loading">Loading snapshot status...</StatusIndicator>
        </Box>
      );
    }
    
    if (snapshotStatusError) {
      return (
        <Alert type="error" header="Error loading snapshot status">
          {snapshotStatusError}
        </Alert>
      );
    }
    
    if (snapshotStatusData) {
      // We already have the collection set up at the component top level

      return (
        <SpaceBetween size="l">
          <Header variant="h2">Snapshot Status</Header>
          
          <SpaceBetween size="s">
            <Box variant="awsui-key-label">Status</Box>
            <StatusIndicator type={
              snapshotStatusData.status === "Completed" ? "success" :
              snapshotStatusData.status === "Failed" ? "error" :
              "in-progress"
            }>
              {snapshotStatusData.status} ({snapshotStatusData.percentage_completed}%)
            </StatusIndicator>
            
            {snapshotStatusData.started && (
              <Box>
                <Box variant="awsui-key-label">Started</Box>
                <Box>{new Date(snapshotStatusData.started).toLocaleString()}</Box>
              </Box>
            )}
            
            {snapshotStatusData.finished && (
              <Box>
                <Box variant="awsui-key-label">Finished</Box>
                <Box>{new Date(snapshotStatusData.finished).toLocaleString()}</Box>
              </Box>
            )}
            
            {snapshotStatusData.shard_total !== null && (
              <Box>
                <Box variant="awsui-key-label">Shards</Box>
                <Box>{snapshotStatusData.shard_complete || 0} / {snapshotStatusData.shard_total} completed</Box>
              </Box>
            )}
            
            {snapshotStatusData.data_total_bytes !== null && snapshotStatusData.data_processed_bytes !== null && (
              <Box>
                <Box variant="awsui-key-label">Data</Box>
                <Box>{formatBytes(snapshotStatusData.data_processed_bytes)} / {formatBytes(snapshotStatusData.data_total_bytes)}</Box>
              </Box>
            )}
          </SpaceBetween>
          
          {/* Index details section */}
          {indexes.length > 0 && (
            <SpaceBetween size="m">
              <Header variant="h3">Indexes</Header>
              <SnapshotIndexesTable 
                indexes={indexes}
                maxHeight="300px"
                emptyText="No indexes were found in this snapshot."
              />
            </SpaceBetween>
          )}
        </SpaceBetween>
      );
    }
    
    return null;
  };
  
  return (
    <SpaceBetween size="l">
      <SpaceBetween size="m">
        <Button
          variant="primary"
          onClick={handleCreateSnapshot}
          disabled={isCreatingSnapshot || isLoadingConfig || !!configError}
          loading={isCreatingSnapshot}
        >
          Create Snapshot
        </Button>
        
        {createSnapshotError && (
          <Alert type="error" header="Error creating snapshot">
            {createSnapshotError}
          </Alert>
        )}
      </SpaceBetween>
      
      {/* Snapshot Status */}
      {renderSnapshotStatus()}
    </SpaceBetween>
  );
}
