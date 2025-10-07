"use client";

import { useState, useEffect } from "react";
import { SessionStatusProps } from "@/components/session/types";
import { StatusFieldDefinition } from "@/components/session/statusUtils";
import { useMetadataStatus } from "@/hooks/apiFetch";
import StatusContainer from "@/components/session/StatusContainer";
import {
  StatusDisplay,
  DateDisplay,
  DurationDisplay,
} from "@/components/session/statusComponents";
import { METADATA_SCENARIOS } from "./mockData/metadataScenarios";
import { MetadataDebugControls } from "./debug/MetadataDebugControls";
import { MetadataStatus } from "@/generated/api";

export default function MetadataStatusView({
  sessionName,
}: Readonly<SessionStatusProps>) {
  const {
    isLoading: apiLoading,
    data: apiMetadataData,
    error,
  } = useMetadataStatus(sessionName);

  const [debugData, setDebugData] = useState<MetadataStatus | null>(null);
  const [isLoading, setIsLoading] = useState(apiLoading);
  const [metadataData, setMetadataData] = useState<MetadataStatus | null>(null);

  useEffect(() => {
    if (!debugData) {
      // Only update if there's actual API data
      if (apiMetadataData) {
        setMetadataData(apiMetadataData);
      } else {
        setMetadataData(null);
      }
      setIsLoading(apiLoading);
    }
  }, [apiMetadataData, apiLoading, debugData]);

  const applyDebugScenario = (scenario: keyof typeof METADATA_SCENARIOS) => {
    setDebugData(METADATA_SCENARIOS[scenario]);
    setMetadataData(METADATA_SCENARIOS[scenario]);
    setIsLoading(false);
  };

  const resetToApiData = () => {
    setDebugData(null);
  };

  const fields: StatusFieldDefinition[] = [
    {
      label: "Status",
      value: <StatusDisplay status={metadataData?.status} />,
    },
    {
      label: "Started",
      value: <DateDisplay date={metadataData?.started} />,
    },
    {
      label: "Finished",
      value: <DateDisplay date={metadataData?.finished} />,
    },
    {
      label: "Duration",
      value: (
        <DurationDisplay
          started={metadataData?.started}
          finished={metadataData?.finished}
        />
      ),
    },
  ];

  return (
    <>
      <StatusContainer
        title="Metadata Migration"
        isLoading={isLoading}
        error={error}
        data={metadataData}
        fields={fields}
        columns={2}
        goToLocation="metadata"
      />
      <MetadataDebugControls
        onScenarioSelect={applyDebugScenario}
        onReset={resetToApiData}
      />
    </>
  );
}
