import { useState, useEffect, useRef } from 'react';
import { sessionGet, snapshotStatus } from '@/generated/api/sdk.gen';

function useFetchData<T>(
  fetchFn: (sessionName: string) => Promise<{response: {status: number}, data: T}>,
  sessionName: string,
  componentName: string = 'component'
) {
  const [isLoading, setIsLoading] = useState(true);
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Use a ref to store the fetchFn to avoid dependency changes triggering re-fetches
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

export function useSessionOverview(sessionName: string) {
  const fetchSession = async (name: string) => {
    return await sessionGet({ path: { session_name: name } });
  };

  return useFetchData(fetchSession, sessionName, 'session overview');
}

export function useSnapshotStatus(sessionName: string) {
  const fetchSnapshot = async (name: string) => {
    return await snapshotStatus({ path: { session_name: name } });
  };

  return useFetchData(fetchSnapshot, sessionName, 'snapshot status');
}
