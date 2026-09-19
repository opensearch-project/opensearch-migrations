import { useEffect, useState } from "react";
import type { QueryClient } from "@tanstack/react-query";

import { connectEventSource, type EventConnectionState } from "./eventSource";

export type { EventConnectionState } from "./eventSource";


export function savedConfigurationRevision(
  eventData: unknown,
): string | null {
  try {
    const data = JSON.parse(String(eventData)) as {
      reason?: unknown;
      persistedRevision?: unknown;
    };
    return (
      data.reason === "configuration-saved"
      && typeof data.persistedRevision === "string"
    )
      ? data.persistedRevision
      : null;
  } catch {
    return null;
  }
}


export function useManageEvents(
  queryClient: QueryClient,
  onConfigurationSaved?: (persistedRevision: string) => void,
) {
  const [connection, setConnection] =
    useState<EventConnectionState>("connecting");

  useEffect(() => connectEventSource("/api/v1/manage/events", {
    onStateChange: setConnection,
    onRecovered: () => {
      void queryClient.invalidateQueries({ queryKey: ["manage-state"] });
    },
    listeners: {
      heartbeat: () => setConnection("live"),
      "state-invalidated": (event) => {
        setConnection("live");
        void queryClient.invalidateQueries({ queryKey: ["manage-state"] });
        const persistedRevision = savedConfigurationRevision(event.data);
        if (persistedRevision) onConfigurationSaved?.(persistedRevision);
      },
    },
  }), [onConfigurationSaved, queryClient]);

  return connection;
}
