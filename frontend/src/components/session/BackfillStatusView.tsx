"use client";

import { useState, useEffect } from "react";
import { SessionStatusProps } from "./types";
import { StatusFieldDefinition } from "./statusUtils";
import { useBackfillStatus } from "./apiHooks";
import StatusContainer from "./StatusContainer";
import {
  StatusDisplay,
  DateDisplay,
  DurationDisplay,
  ProgressDisplay,
  ETADisplay,
} from "./statusComponents";
import { BACKFILL_SCENARIOS } from "./mockData/backfillScenarios";
import { BackfillDebugControls } from "./debug/BackfillDebugControls";
import { BackfillStatus } from "@/generated/api";

export default function BackfillStatusView({
  sessionName,
}: Readonly<SessionStatusProps>) {
  const {
    isLoading: apiLoading,
    data: apiBackfillData,
    error,
  } = useBackfillStatus(sessionName);

  const [debugData, setDebugData] = useState<BackfillStatus | null>(null);
  const [isLoading, setIsLoading] = useState(apiLoading);
  const [backfillData, setBackfillData] = useState<BackfillStatus | null>(null);

  useEffect(() => {
    if (!debugData) {
      // Only update if there's actual API data
      if (apiBackfillData) {
        setBackfillData(apiBackfillData);
      } else {
        setBackfillData(null);
      }
      setIsLoading(apiLoading);
    }
  }, [apiBackfillData, apiLoading, debugData]);

  const applyDebugScenario = (scenario: keyof typeof BACKFILL_SCENARIOS) => {
    setDebugData(BACKFILL_SCENARIOS[scenario]);
    setBackfillData(BACKFILL_SCENARIOS[scenario]);
    setIsLoading(false);
  };

  const resetToApiData = () => {
    setDebugData(null);
  };

  const fields: StatusFieldDefinition[] = [
    {
      label: "Status",
      value: <StatusDisplay status={backfillData?.status} />,
    },
    {
      label: "Progress",
      value: (
        <ProgressDisplay percentage={backfillData?.percentage_completed} />
      ),
    },
    {
      label: "ETA",
      value: <ETADisplay etaMs={backfillData?.eta_ms} />,
    },
    {
      label: "Started",
      value: <DateDisplay date={backfillData?.started} />,
    },
    {
      label: "Finished",
      value: <DateDisplay date={backfillData?.finished} />,
    },
    {
      label: "Duration",
      value: (
        <DurationDisplay
          started={backfillData?.started}
          finished={backfillData?.finished}
        />
      ),
    },
  ];

  return (
    <>
      <StatusContainer
        title="Backfill"
        isLoading={isLoading}
        error={error}
        data={backfillData}
        fields={fields}
        columns={2}
      />
      <BackfillDebugControls
        onScenarioSelect={applyDebugScenario}
        onReset={resetToApiData}
      />
    </>
  );
}
