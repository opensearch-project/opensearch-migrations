import { useEffect } from "react";
import type { QueryClient } from "@tanstack/react-query";

import { connectEventSource } from "./eventSource";


export function useOperationEvents(queryClient: QueryClient) {
  useEffect(() => {
    const invalidate = () => {
      void queryClient.invalidateQueries({ queryKey: ["operations"] });
    };
    return connectEventSource("/api/v1/operations/events", {
      onRecovered: invalidate,
      listeners: { "operation-updated": invalidate },
    });
  }, [queryClient]);
}
