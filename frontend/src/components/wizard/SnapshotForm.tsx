"use client";

import { useState, useEffect } from "react";
import FormField from "@cloudscape-design/components/form-field";
import Input from "@cloudscape-design/components/input";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import { SnapshotConfig } from "@/generated/api";
import { Alert, StatusIndicator } from "@cloudscape-design/components";
import RadioGroup from "@cloudscape-design/components/radio-group";
import { SnapshotDebugControls } from "../session/debug/SnapshotDebugControls";
import { SNAPSHOT_CONFIG_SCENARIOS } from "../session/mockData/snapshotConfigScenarios";

interface SnapshotFormProps {
  readonly isLoading: boolean;
  readonly snapshotConfig: SnapshotConfig | null | undefined;
  readonly error: any;
}

export default function SnapshotForm({
  isLoading: apiLoading,
  snapshotConfig: apiSnapshotData,
  error: apiError,
}: SnapshotFormProps) {
  const [debugData, setDebugData] = useState<SnapshotConfig | null>(null);
  const [isLoading, setIsLoading] = useState(apiLoading);
  const [snapshotConfig, setSnapshotConfig] = useState<SnapshotConfig | null | undefined>(apiSnapshotData);
  const [error, setError] = useState(apiError);
  
  // Update data when API data changes, unless we're using debug data
  useEffect(() => {
    if (!debugData) {
      if (apiSnapshotData) {
        setSnapshotConfig(apiSnapshotData);
      } else {
        setSnapshotConfig(null);
      }
      setIsLoading(apiLoading);
      setError(apiError);
    }
  }, [apiSnapshotData, apiLoading, apiError, debugData]);
  
  // Debug scenario handlers
  const applyDebugScenario = (scenario: keyof typeof SNAPSHOT_CONFIG_SCENARIOS) => {
    const scenarioData = SNAPSHOT_CONFIG_SCENARIOS[scenario];
    setDebugData(scenarioData);
    setSnapshotConfig(scenarioData);
    setIsLoading(false);
    setError(null);
  };
  
  const resetToApiData = () => {
    setDebugData(null);
    setSnapshotConfig(apiSnapshotData);
    setIsLoading(apiLoading);
    setError(apiError);
  };

  if (isLoading) {
    return (
      <Box padding="xl">
        <StatusIndicator type="loading">Loading snapshot configuration…</StatusIndicator>
      </Box>
    );
  }

  if (error) {
    return (
      <Alert type="error" header="Failed to load snapshot configuration">
        {String((error as any)?.message ?? error)}
      </Alert>
    );
  }

  return (
    <SpaceBetween size="l">
      <FormField 
        label="Snapshot Name" 
        description="The name of the snapshot"
      >
        <Input 
          placeholder="my-snapshot"
          value={snapshotConfig?.snapshot_name ?? ""}
          onChange={() => {}}
          disabled
        />
      </FormField>

      <FormField 
        label="Repository Name" 
        description="The name of the repository where the snapshot will be stored"
      >
        <Input 
          placeholder="my-snapshot-repository"
          value={snapshotConfig?.repository_name ?? ""}
          onChange={() => {}}
          disabled
        />
      </FormField>
      
      <FormField 
        label="Storage Type" 
        description="Select the storage type for the snapshot"
      >
        <RadioGroup
          items={[
            { value: "s3", label: "Amazon S3" },
            { value: "filesytem", label: "File System" }
          ]}
          value={snapshotConfig?.source.type ?? "s3"}
          onChange={() => {}}
        />
      </FormField>
      
      {snapshotConfig?.source.type === "s3" && (
        <SpaceBetween size="m">
          <FormField label="S3 URI" description="S3 bucket URI where snapshots will be stored">
            <Input 
              placeholder="s3://my-bucket/path/to/repository"
              value={snapshotConfig.source.uri ?? ""}
              onChange={() => {}}
              disabled
            />
          </FormField>
          
          <FormField label="AWS Region" description="AWS Region for the S3 bucket">
            <Input 
              placeholder="us-east-1"
              value={snapshotConfig.source.region ?? ""}
              onChange={() => {}}
              disabled
            />
          </FormField>
        </SpaceBetween>
      )}

      {snapshotConfig?.source.type === "filesytem" && (
        <FormField label="Path" description="File system path where snapshots will be stored">
          <Input 
            placeholder="/path/to/snapshot/repository"
            value={(snapshotConfig.source as any).path ?? ""}
            onChange={() => {}}
            disabled
          />
        </FormField>
      )}

      <FormField 
        label="Index Allowlist" 
        description="List of indexes to include in the snapshot (comma-separated)"
      >
        <Input 
          placeholder="index1,index2,index3"
          value={snapshotConfig?.index_allow.join(",") ?? ""}
          onChange={() => {}}
          disabled
        />
      </FormField>
      
      <SnapshotDebugControls 
        onScenarioSelect={applyDebugScenario}
        onReset={resetToApiData}
      />
    </SpaceBetween>
  );
}
