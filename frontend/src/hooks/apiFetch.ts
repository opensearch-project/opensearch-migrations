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

export type SessionPromise<T> = (
  sessionName: string,
) => Promise<{ response: { status: number }; data: T }>;

function useFetchData<T>(
  fetchFn: SessionPromise<T>,
  sessionName: string,
  componentName: string = "component",
) {
  const [isLoading, setIsLoading] = useState(true);
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Use a ref to store the fetchFn to avoid dependency changes triggering re-fetches
  const fetchFnRef = useRef<SessionPromise<T>>(fetchFn);
  useEffect(() => {
    async function fetchData() {
      if (!sessionName) {
        setError("No session name provided");
        setIsLoading(false);
        return;
      }

      try {
        const response = await fetchFnRef.current(sessionName);
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
    }
    fetchData();
  }, [sessionName, componentName]);
  return { isLoading, data, error };
}

const defaultArgs = (name: string) => {
  return { path: { session_name: name } };
};

export function useSourceCluster(sessionName: string) {
  const fetchSourceCluster = async (name: string) => {
    return await clusterSource(defaultArgs(name));
  };
  return useFetchData(fetchSourceCluster, sessionName, "source cluster");
}

export function useTargetCluster(sessionName: string) {
  const fetchTargetCluster = async (name: string) => {
    return await clusterTarget(defaultArgs(name));
  };
  return useFetchData(fetchTargetCluster, sessionName, "target cluster");
}

export function useSessionOverview(sessionName: string) {
  const fetchSession = async (name: string) => {
    return await sessionGet(defaultArgs(name));
  };

  return useFetchData(fetchSession, sessionName, "session overview");
}

export function useSnapshotStatus(sessionName: string) {
  const fetchSnapshot = async (name: string) => {
    return await snapshotStatus(defaultArgs(name));
  };

  return useFetchData(fetchSnapshot, sessionName, "snapshot status");
}

export function useSnapshotConfig(sessionName: string) {
  const fetchSnapshotConfig = async (name: string) => {
    return await snapshotConfig(defaultArgs(name));
  };

  return useFetchData(
    fetchSnapshotConfig,
    sessionName,
    "snapshot configuration",
  );
}

export function useMetadataStatus(sessionName: string) {
  const fetchMetadata = async (name: string) => {
    return await metadataStatus(defaultArgs(name));
  };

  return useFetchData(fetchMetadata, sessionName, "metadata status");
}

export function useBackfillStatus(sessionName: string) {
  const fetchBackfill = async (name: string) => {
    return await backfillStatus(defaultArgs(name));
  };

  return useFetchData(fetchBackfill, sessionName, "backfill status");
}

export function useSnapshotIndexes(sessionName: string, indexPattern?: string) {
  const fetchSnapshotIndexes = async (name: string) => {
    return await snapshotIndexes({
      ...defaultArgs(name),
      query: indexPattern ? { index_pattern: indexPattern } : undefined,
    });
  };

  return useFetchData(fetchSnapshotIndexes, sessionName, "snapshot indexes");
}

export function useSnapshotCreate(sessionName: string) {
  const fetchSnapshotCreate = async (name: string) => {
    return await snapshotCreate(defaultArgs(name));
  };
  return useFetchData(fetchSnapshotCreate, sessionName, "snapshot create");
}

export function useSnapshotDelete(sessionName: string) {
  const fetchSnapshotDelete = async (name: string) => {
    return await snapshotDelete(defaultArgs(name));
  };
  return useFetchData(fetchSnapshotDelete, sessionName, "snapshot create");
}
