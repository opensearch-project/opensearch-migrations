"use client";

import {
  HealthApiResponse,
  snapshotStatus,
  SnapshotStatus,
  systemHealth,
} from "@/generated/api";
import { useCallback, useEffect, useRef, useState } from "react";

export type UsePollingOptions<T> = {
  enabled?: boolean; // start/stop polling; triggers an immediate run when true
  interval?: number; // ms, default 5000
  stopWhen?: (data: T) => boolean; // optional stop condition
};

export type UsePollingResult<T> = {
  isLoading: boolean;
  data: T | null;
  error: string | null;
  isPolling: boolean;
  startPolling: () => void;
  stopPolling: () => void;
  refresh: () => void; // run once now (no overlap)
  lastUpdated: number | null;
};

export function usePolling<T>(
  fetchFn: () => Promise<T>,
  { enabled = false, interval = 5000, stopWhen }: UsePollingOptions<T> = {},
): UsePollingResult<T> {
  const [isLoading, setIsLoading] = useState(true);
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isPolling, setIsPolling] = useState<boolean>(enabled);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);

  // keep latest inputs without bloating deps
  const fetchRef = useRef(fetchFn);
  const stopWhenRef = useRef(stopWhen);
  useEffect(() => {
    fetchRef.current = fetchFn;
  }, [fetchFn]);
  useEffect(() => {
    stopWhenRef.current = stopWhen;
  }, [stopWhen]);

  // lifecycle + concurrency guards
  const runningRef = useRef(false);

  // external control drives local state
  useEffect(() => {
    setIsPolling(enabled);
  }, [enabled]);

  const issueRequest = useCallback(async () => {
    if (runningRef.current) return; // prevent overlap (incl. refresh)
    runningRef.current = true;
    try {
      const result = await fetchRef.current();
      setData(result);
      setError(null); // clear last error on success
      setLastUpdated(Date.now());
      setIsLoading(false);

      if (stopWhenRef.current?.(result)) {
        setIsPolling(false);
      }
    } catch (e) {
      setError(String(e)); // keep polling; error may update next tick
      setIsLoading(false);
    } finally {
      runningRef.current = false;
    }
  }, []);

  useEffect(() => {
    if (!isPolling) return;

    let cancelled = false;
    let timeoutId: ReturnType<typeof setTimeout> | null = null;

    const loop = async () => {
      await issueRequest();
      if (!cancelled) {
        timeoutId = setTimeout(loop, interval);
      }
    };

    // immediately trigger when enabled becomes true
    loop();

    return () => {
      cancelled = true;
      if (timeoutId) clearTimeout(timeoutId);
    };
  }, [isPolling, interval, issueRequest]);

  const startPolling = useCallback(() => setIsPolling(true), []);
  const stopPolling = useCallback(() => setIsPolling(false), []);
  const refresh = useCallback(() => issueRequest(), [issueRequest]);

  return {
    isLoading,
    data,
    error,
    isPolling,
    startPolling,
    stopPolling,
    refresh,
    lastUpdated,
  };
}

export function usePollingSnapshotStatus(
  sessionName: string,
  enabled: boolean,
) {
  const fetchFn = useCallback(async (): Promise<SnapshotStatus> => {
    const response = await snapshotStatus({
      path: { session_name: sessionName },
    });
    if (response.response.status !== 200 || !response.data) {
      throw new Error(
        `API Error: ${response.response.status} - Failed to fetch snapshot status`,
      );
    }
    return response.data;
  }, [sessionName]);

  return usePolling<SnapshotStatus>(fetchFn, {
    enabled,
  });
}

export function usePollingSystemHealth(
  enabled: boolean,
  stopWhen: (data: HealthApiResponse) => boolean,
) {
  const fetchFn = useCallback(async (): Promise<HealthApiResponse> => {
    const res = await systemHealth();
    if (res.response.status !== 200 || !res.data) {
      throw new Error(
        `API Error: ${res.response.status} - ${JSON.stringify(res.error ?? {})}`,
      );
    }
    return res.data;
  }, []);

  return usePolling(fetchFn, {
    enabled,
    stopWhen,
  });
}
