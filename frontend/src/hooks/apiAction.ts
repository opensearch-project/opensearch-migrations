import {
  metadataMigrate,
  snapshotCreate,
  snapshotDelete,
} from "@/generated/api";
import { useCallback, useRef, useState } from "react";
import { SessionPromise } from "./apiFetch";

export function useAsyncAction<T>(
  actionFn: SessionPromise<T>,
  componentName: string,
) {
  const [isLoading, setIsLoading] = useState(false);
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Use a ref to store the fetchFn to avoid dependency changes triggering re-fetches
  const actionFnRef = useRef<SessionPromise<T>>(actionFn);

  const run = useCallback(
    async (sessionName: string) => {
      setIsLoading(true);
      setError(null);
      try {
        const response = await actionFnRef.current(sessionName);
        if (response.response.status === 200) {
          setData(response.data);
        } else {
          setError(
            `API Error: ${response.response.status} - Failed to fetch ${componentName} data`,
          );
        }
      } catch (err) {
        console.error(`Error fetching ${componentName} data:`, err);
        setError(`${err instanceof Error ? err.message : "Unknown error"}`);
      } finally {
        setIsLoading(false);
      }
    },
    [actionFnRef, componentName],
  );

  const reset = useCallback(() => {
    setData(null);
    setError(null);
  }, []);

  return { run, reset, isLoading, data, error };
}

const defaultArgs = (name: string) => {
  return { path: { session_name: name } };
};

export function useMetadataMigrateAction(dryRun: boolean) {
  return useAsyncAction(
    async (sessionName: string) => {
      return await metadataMigrate({
        ...defaultArgs(sessionName),
        body: { dryRun },
      });
    },
    `metadata ${dryRun ? "evaluate" : "migrate"}`,
  );
}

export function useSnapshotCreateAction() {
  return useAsyncAction(async (sessionName: string) => {
    return await snapshotCreate(defaultArgs(sessionName));
  }, "snapshot create");
}

export function useSnapshotDeleteAction() {
  return useAsyncAction(async (sessionName: string) => {
    return await snapshotDelete(defaultArgs(sessionName));
  }, "snapshot delete");
}
