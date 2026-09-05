import { useEffect } from "react";
import type { QueryClient } from "@tanstack/react-query";


export function useOperationEvents(queryClient: QueryClient) {
  useEffect(() => {
    const source = new EventSource("/api/v1/operations/events");
    source.addEventListener("operation-updated", () => {
      void queryClient.invalidateQueries({ queryKey: ["operations"] });
    });
    return () => source.close();
  }, [queryClient]);
}
