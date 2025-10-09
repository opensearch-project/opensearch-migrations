"use client";

import { SessionStatusProps } from "./types";
import { useSessionOverview } from "@/hooks/apiFetch";
import StatusContainer from "./StatusContainer";
import { StatusFieldDefinition } from "./statusUtils";
import { DateDisplay, TextDisplay } from "./statusComponents";

export default function SessionOverviewView({
  sessionName,
}: Readonly<SessionStatusProps>) {
  const {
    isLoading,
    data: sessionData,
    error,
  } = useSessionOverview(sessionName);

  const fields: StatusFieldDefinition[] = [
    {
      label: "Session",
      value: <TextDisplay text={sessionData?.name} />,
    },
    {
      label: "Created At",
      value: <DateDisplay date={sessionData?.created} />,
    },
  ];

  return (
    <StatusContainer
      title="Session Overview"
      isLoading={isLoading}
      error={error}
      data={sessionData}
      fields={fields}
      columns={3}
    />
  );
}
