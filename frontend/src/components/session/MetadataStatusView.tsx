"use client";

import { useState, useEffect } from "react";
import { MetadataData, SessionStatusProps } from "./types";
import { StatusFieldDefinition } from "./statusUtils";
import { useMetadataStatus } from "./apiHooks";
import StatusContainer from "./StatusContainer";
import {
  StatusDisplay,
  DateDisplay,
  DurationDisplay,
  TextDisplay,
} from "./statusComponents";
import { METADATA_SCENARIOS } from "./mockData/metadataScenarios";
import { MetadataDebugControls } from "./debug/MetadataDebugControls";
import {
  ClusterVersions,
} from "./statusComponents/MetadataProgress";

export default function MetadataStatusView({
  sessionName,
}: Readonly<SessionStatusProps>) {
  const {
    isLoading: apiLoading,
    data: apiMetadataData,
    error,
  } = useMetadataStatus(sessionName);

  const [debugData, setDebugData] = useState<MetadataData | null>(null);
  const [isLoading, setIsLoading] = useState(apiLoading);
  const [metadataData, setMetadataData] = useState<MetadataData | null>(null);

  useEffect(() => {
    if (!debugData) {
      // Only update if there's actual API data
      if (apiMetadataData) {
        setMetadataData(apiMetadataData as MetadataData);
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
    {
      label: "Clusters",
      value: <ClusterVersions metadata={metadataData} />,
    },
    {
      label: "Errors",
      value:
        metadataData?.errors && metadataData.errors.length > 0 ? (
          <ul style={{ margin: 0, paddingLeft: "1rem" }}>
            {metadataData.errors.map((error, idx) => (
              <li key={idx}>{error}</li>
            ))}
          </ul>
        ) : metadataData?.errorMessage ? (
          <ul style={{ margin: 0, paddingLeft: "1rem" }}>
              <li key="err-msg">{metadataData.errorMessage}</li>
          </ul>
        ) : (
          <TextDisplay text="-" />
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
      />
      <MetadataDebugControls
        onScenarioSelect={applyDebugScenario}
        onReset={resetToApiData}
      />
    </>
  );
}
