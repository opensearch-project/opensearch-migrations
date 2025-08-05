import { useState, useEffect, useRef } from 'react';
import { sessionGet, snapshotStatus } from '@/generated/api/sdk.gen';
import { StepState } from '@/generated/api/types.gen';
import { StepStatusInfo } from './types';

// Generic hook for fetching any type of data
export function useFetchData<T>(
  fetchFn: (sessionName: string) => Promise<{response: {status: number}, data: T}>,
  sessionName: string,
  componentName: string = 'component'
) {
  const [isLoading, setIsLoading] = useState(true);
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Use a ref to store the fetchFn to avoid dependency changes triggering refetches
  const fetchFnRef = useRef(fetchFn);

  useEffect(() => {
    async function fetchData() {
      if (!sessionName) {
        setError('No session name provided');
        setIsLoading(false);
        return;
      }
      
      try {
        const response = await fetchFnRef.current(sessionName);
        if (response.response.status === 200) {
          setData(response.data);
        } else {
          setError(`API Error: ${response.response.status} - Failed to fetch ${componentName} data`);
        }
      } catch (err) {
        console.error(`Error fetching ${componentName} data:`, err);
        setError(`${err instanceof Error ? err.message : 'Unknown error'}\n\n${err instanceof Error && err.stack ? err.stack : ''}`);
      } finally {
        setIsLoading(false);
      }
    }

    fetchData();
    // Only depend on sessionName to prevent continuous refetching
  }, [sessionName, componentName]);

  return { isLoading, data, error };
}

// Hook for fetching session overview data
export function useSessionOverview(sessionName: string) {
  const fetchSession = async (name: string) => {
    return await sessionGet({ path: { session_name: name } });
  };

  return useFetchData(fetchSession, sessionName, 'session overview');
}

// Hook for fetching snapshot status data
export function useSnapshotStatus(sessionName: string) {
  const fetchSnapshot = async (name: string) => {
    return await snapshotStatus({ path: { session_name: name } });
  };

  return useFetchData(fetchSnapshot, sessionName, 'snapshot status');
}

// Hook for fetching metadata status data (mock)
export function useMetadataStatus(sessionName: string) {
  const fetchMetadata = async (name: string): Promise<{
    response: { status: number };
    data: StepStatusInfo;
  }> => {
    // This is a placeholder for the actual API call
    return {
      response: { status: 200 },
      data: {
        status: "Pending" as StepState,
        started: undefined,
        finished: undefined,
        percentage_completed: 0,
        eta_ms: null
      }
    };
  };

  return useFetchData(fetchMetadata, sessionName, 'metadata status');
}

// Hook for fetching backfill status data (mock)
export function useBackfillStatus(sessionName: string) {
  const fetchBackfill = async (name: string): Promise<{
    response: { status: number };
    data: StepStatusInfo;
  }> => {
    // This is a placeholder for the actual API call
    return {
      response: { status: 200 },
      data: {
        status: "Pending" as StepState,
        started: undefined,
        finished: undefined,
        percentage_completed: 0,
        eta_ms: null
      }
    };
  };

  return useFetchData(fetchBackfill, sessionName, 'backfill status');
}
