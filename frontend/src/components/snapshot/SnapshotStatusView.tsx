"use client";

import { useState, useEffect } from "react";
import { StatusFieldDefinition } from "@/components/session/statusUtils";
import StatusContainer from "@/components/session/StatusContainer";
import {
  StatusDisplay,
  DateDisplay,
  DurationDisplay,
  ProgressDisplay,
  ETADisplay,
} from "@/components/session/statusComponents";
import { SNAPSHOT_SCENARIOS } from "./mockData/snapshotScenarios";
import { SnapshotDebugControls } from "./debug/SnapshotDebugControls";
import { SnapshotStatus } from "@/generated/api";

export interface SessionStatusProps {
  readonly sessionName: string;
}

export interface SnapshotStatusViewProps {
  readonly isLoading: boolean;
  readonly data: SnapshotStatus | null | undefined;
  readonly error: string | null;
}

export default function SnapshotStatusView({
  isLoading: apiLoading,
  data,
  error,
}: SnapshotStatusViewProps) {
  const [debugData, setDebugData] = useState<SnapshotStatus | null>(null);
  const [isLoading, setIsLoading] = useState(apiLoading);
  const [snapshotData, setSnapshotData] = useState<SnapshotStatus | null>(null);

  useEffect(() => {
    if (!debugData) {
      // Only update if there's actual API data
      if (data) {
        setSnapshotData(data);
      } else {
        setSnapshotData(null);
      }
      setIsLoading(apiLoading);
    }
  }, [data, apiLoading, debugData]);

  const applyDebugScenario = (scenario: keyof typeof SNAPSHOT_SCENARIOS) => {
    setDebugData(SNAPSHOT_SCENARIOS[scenario]);
    setSnapshotData(SNAPSHOT_SCENARIOS[scenario]);
    setIsLoading(false);
  };

  const resetToApiData = () => {
    setDebugData(null);
  };

  const fields: StatusFieldDefinition[] = [
    {
      label: "Status",
      value: <StatusDisplay status={snapshotData?.status} />,
    },
    {
      label: "Started",
      value: <DateDisplay date={snapshotData?.started} />,
    },
    {
      label: "Finished",
      value: <DateDisplay date={snapshotData?.finished} />,
    },
    {
      label: "Duration",
      value: (
        <DurationDisplay
          started={snapshotData?.started}
          finished={snapshotData?.finished}
        />
      ),
    },
    {
      label: "Progress",
      value: (
        <ProgressDisplay percentage={snapshotData?.percentage_completed} />
      ),
    },
    {
      label: "ETA",
      value: <ETADisplay etaMs={snapshotData?.eta_ms} />,
    },
  ];

  return (
    <>
      <StatusContainer
        title="Snapshot"
        isLoading={isLoading}
        error={error}
        data={snapshotData}
        fields={fields}
        columns={2}
        goToLocation="snapshot"
      />

      <SnapshotDebugControls
        onScenarioSelect={applyDebugScenario}
        onReset={resetToApiData}
      />
    </>
  );
}
