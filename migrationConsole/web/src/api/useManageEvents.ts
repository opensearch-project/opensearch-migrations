import { useEffect, useState } from "react";
import type { QueryClient } from "@tanstack/react-query";


export type EventConnectionState = "connecting" | "live" | "reconnecting";


export function useManageEvents(queryClient: QueryClient) {
  const [connection, setConnection] =
    useState<EventConnectionState>("connecting");

  useEffect(() => {
    const source = new EventSource("/api/v1/manage/events");
    source.onopen = () => setConnection("live");
    source.onerror = () => setConnection("reconnecting");
    source.addEventListener("heartbeat", () => setConnection("live"));
    source.addEventListener("state-invalidated", () => {
      setConnection("live");
      void queryClient.invalidateQueries({ queryKey: ["manage-state"] });
    });
    return () => source.close();
  }, [queryClient]);

  return connection;
}
