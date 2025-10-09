"use client";

import { useState, useEffect } from "react";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import {
  FileSystemSnapshotSource,
  S3SnapshotSource,
  SnapshotConfig,
} from "@/generated/api";
import {
  Alert,
  Header,
  KeyValuePairs,
  KeyValuePairsProps,
  StatusIndicator,
} from "@cloudscape-design/components";
import { SnapshotConfigDebugControls } from "@/components/snapshot/debug/SnapshotConfigDebugControls";
import { SNAPSHOT_CONFIG_SCENARIOS } from "./mockData/snapshotConfigScenarios";

interface SnapshotFormProps {
  readonly isLoading: boolean;
  readonly snapshotConfig: SnapshotConfig | null | undefined;
  readonly error: Error | string | null | undefined;
}

export default function SnapshotConfigView({
  isLoading: apiLoading,
  snapshotConfig: apiSnapshotData,
  error: apiError,
}: SnapshotFormProps) {
  const [debugData, setDebugData] = useState<SnapshotConfig | null>(null);
  const [isLoading, setIsLoading] = useState(apiLoading);
  const [snapshotConfig, setSnapshotConfig] = useState<
    SnapshotConfig | null | undefined
  >(apiSnapshotData);
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
  const applyDebugScenario = (
    scenario: keyof typeof SNAPSHOT_CONFIG_SCENARIOS,
  ) => {
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
        <StatusIndicator type="loading">
          Loading snapshot configuration…
        </StatusIndicator>
      </Box>
    );
  }

  if (error || !snapshotConfig) {
    return (
      <Alert type="error" header="Failed to load snapshot configuration">
        {error instanceof Error ? error.message : String(error)}
      </Alert>
    );
  }

  const configItems: KeyValuePairsProps.Item[] = [
    { label: "Snapshot Name", value: snapshotConfig.snapshot_name },
    { label: "Repository Name", value: snapshotConfig.repository_name },
    {
      label: "Storage Type",
      value: snapshotConfig.source.type === "s3" ? "Amazon S3" : "File System",
    },
  ];

  if (snapshotConfig.source.type === "s3") {
    const source = snapshotConfig.source as S3SnapshotSource;
    configItems.push(
      { label: "S3 URI", value: source.uri },
      { label: "AWS Region", value: source.region },
    );
  }
  if (snapshotConfig.source.type === "filesytem") {
    const source = snapshotConfig.source as FileSystemSnapshotSource;
    configItems.push({ label: "Path", value: source.path });
  }
  configItems.push({
    label: "Index Allowlist",
    value: snapshotConfig?.index_allow.length ? (
      snapshotConfig.index_allow.join(", ")
    ) : (
      <i>Default - All indexes</i>
    ),
  });

  return (
    <SpaceBetween size="l">
      <Header variant="h2">Snapshot Configuration</Header>

      <KeyValuePairs items={configItems} columns={2} />

      <SnapshotConfigDebugControls
        onScenarioSelect={applyDebugScenario}
        onReset={resetToApiData}
      />
    </SpaceBetween>
  );
}
