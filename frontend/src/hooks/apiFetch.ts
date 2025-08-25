import { useState, useEffect, useRef } from "react";
import {
  backfillStatus,
  clusterSource,
  clusterTarget,
  metadataStatus,
  sessionGet,
  snapshotConfig,
  snapshotIndexes,
  snapshotStatus,
  snapshotCreate,
  snapshotDelete,
} from "@/generated/api/sdk.gen";

type SessionPromise<T> = (
  sessionName: string,
) => Promise<{ response: { status: number }; data: T }>;

type UseFetchOptions = {
  retries?: number; // how many extra tries (default 1)
  retryStatuses?: number[]; // which statuses to retry (default [404, 500])
  baseDelayMs?: number; // initial delay between retries
  backoffFactor?: number; // exponential factor
};

function sleep(ms: number) {
  return new Promise((res) => setTimeout(res, ms));
}

function useFetchData<T>(
  fetchFn: SessionPromise<T>,
  sessionName: string,
  componentName = "component",
  {
    retries = 1,
    retryStatuses = [404, 500],
    baseDelayMs = 600,
    backoffFactor = 2,
  }: UseFetchOptions = {},
) {
  const [isLoading, setIsLoading] = useState(true);
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);

  // keep the latest fetchFn without retriggering the effect
  const fetchFnRef = useRef<SessionPromise<T>>(fetchFn);
  useEffect(() => {
    fetchFnRef.current = fetchFn;
  }, [fetchFn]);

  useEffect(() => {
    let cancelled = false;

    async function run() {
      if (!sessionName) {
        setError("No session name provided");
        setIsLoading(false);
        return;
      }

      setIsLoading(true);
      setError(null);

      for (let attempt = 0; attempt <= retries; attempt++) {
        try {
          const resp = await fetchFnRef.current(sessionName);
          const status = resp.response.status;

          if (status === 200) {
            if (!cancelled) {
              setData(resp.data);
              setIsLoading(false);
            }
            return;
          }

          const shouldRetry =
            retryStatuses.includes(status) && attempt < retries;

          if (!shouldRetry) {
            if (!cancelled) {
              setError(
                `API Error: ${status} - Failed to fetch ${componentName} data`,
              );
              setIsLoading(false);
            }
            return;
          }

          // wait before next try (exponential backoff + a touch of jitter)
          const delay = Math.round(
            baseDelayMs * Math.pow(backoffFactor, attempt) +
              Math.random() * 100,
          );

          console.warn(
            `[useFetchData] retrying ${componentName} after ${status} (attempt ${attempt + 1}/${retries}) in ${delay}ms`,
          );
          await sleep(delay);
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } catch (e: any) {
          const isLast = attempt >= retries;
          if (isLast) {
            if (!cancelled) {
              setError(`${e?.message ?? "Unknown error"}\n\n${e?.stack ?? ""}`);
              setIsLoading(false);
            }
            return;
          }
          const delay = Math.round(
            baseDelayMs * Math.pow(backoffFactor, attempt) +
              Math.random() * 100,
          );
          console.warn(
            `[useFetchData] retrying ${componentName} after network error (attempt ${attempt + 1}/${retries}) in ${delay}ms`,
            e,
          );
          await sleep(delay);
        }
      }
    }

    run();
    return () => {
      cancelled = true;
    };
  }, [
    sessionName,
    backoffFactor,
    baseDelayMs,
    componentName,
    retries,
    retryStatuses,
  ]);

  return { isLoading, data, error };
}

export function useSourceCluster(sessionName: string) {
  const fetchSourceCluster = async (name: string) => {
    return await clusterSource({ path: { session_name: name } });
  };
  return useFetchData(fetchSourceCluster, sessionName, "source cluster");
}

export function useTargetCluster(sessionName: string) {
  const fetchTargetCluster = async (name: string) => {
    return await clusterTarget({ path: { session_name: name } });
  };
  return useFetchData(fetchTargetCluster, sessionName, "target cluster");
}

export function useSessionOverview(sessionName: string) {
  const fetchSession = async (name: string) => {
    return await sessionGet({ path: { session_name: name } });
  };

  return useFetchData(fetchSession, sessionName, "session overview");
}

export function useSnapshotStatus(sessionName: string) {
  const fetchSnapshot = async (name: string) => {
    return await snapshotStatus({ path: { session_name: name } });
  };

  return useFetchData(fetchSnapshot, sessionName, "snapshot status");
}

export function useSnapshotConfig(sessionName: string) {
  const fetchSnapshotConfig = async (name: string) => {
    return await snapshotConfig({ path: { session_name: name } });
  };

  return useFetchData(
    fetchSnapshotConfig,
    sessionName,
    "snapshot configuration",
  );
}

export function useMetadataStatus(sessionName: string) {
  const fetchMetadata = async (name: string) => {
    return await metadataStatus({ path: { session_name: name } });
  };

  return useFetchData(fetchMetadata, sessionName, "metadata status");
}

export function useBackfillStatus(sessionName: string) {
  const fetchBackfill = async (name: string) => {
    return await backfillStatus({ path: { session_name: name } });
  };

  return useFetchData(fetchBackfill, sessionName, "backfill status");
}

export function useSnapshotIndexes(sessionName: string, indexPattern?: string) {
  const fetchSnapshotIndexes = async (name: string) => {
    return await snapshotIndexes({
      path: { session_name: name },
      query: indexPattern ? { index_pattern: indexPattern } : undefined,
    });
  };

  return useFetchData(fetchSnapshotIndexes, sessionName, "snapshot indexes");
}

export function useSnapshotCreate(sessionName: string) {
  const fetchSnapshotCreate = async (name: string) => {
    return await snapshotCreate({ path: { session_name: name } });
  };
  return useFetchData(fetchSnapshotCreate, sessionName, "snapshot create");
}

export function useSnapshotDelete(sessionName: string) {
  const fetchSnapshotDelete = async (name: string) => {
    return await snapshotDelete({ path: { session_name: name } });
  };
  return useFetchData(fetchSnapshotDelete, sessionName, "snapshot create");
}
