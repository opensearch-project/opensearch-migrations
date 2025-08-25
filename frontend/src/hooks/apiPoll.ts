"use client";

import { snapshotStatus, SnapshotStatus } from "@/generated/api";
import { useCallback, useEffect, useRef, useState } from "react";

export type UsePollingOptions<T> = {
  enabled?: boolean; // start in polling mode
  interval?: number; // ms, default 5000
  immediate?: boolean; // run once immediately (default true)
  stopWhen?: (data: T) => boolean; // stop condition
  stopOnError?: boolean; // default true
  deps?: unknown[]; // extra deps that should restart polling
};

export type UsePollingResult<T> = {
  isLoading: boolean;
  data: T | null;
  error: Error | null;
  isPolling: boolean;
  startPolling: () => void;
  stopPolling: () => void;
  refresh: () => void;
  lastUpdated: number | null;
};

function usePolling<T>(
  fetchFn: () => Promise<T>,
  {
    enabled = false,
    interval = 5000,
    immediate = true,
    stopWhen,
    stopOnError = true,
    deps = [],
  }: UsePollingOptions<T> = {},
): UsePollingResult<T> {
  const [isLoading, setIsLoading] = useState(true);
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [isPolling, setIsPolling] = useState<boolean>(enabled);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);

  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, []);

  // keep isPolling in sync with caller's enabled flag
  useEffect(() => {
    setIsPolling(enabled);
  }, [enabled]);

  const runOnce = useCallback(async () => {
    try {
      const result = await fetchFn();
      if (!mountedRef.current) return;
      setData(result);
      setError(null);
      setLastUpdated(Date.now());
      if (stopWhen?.(result)) setIsPolling(false);
    } catch (e) {
      if (!mountedRef.current) return;
      setError(e instanceof Error ? e : new Error(String(e)));
      if (stopOnError) setIsPolling(false);
    } finally {
      if (mountedRef.current) setIsLoading(false);
    }
  }, [fetchFn, stopWhen, stopOnError]);

  useEffect(() => {
    // clear any existing timer
    if (timerRef.current) {
      clearInterval(timerRef.current);
      timerRef.current = null;
    }

    if (immediate) {
      // fire once on (re)start
      runOnce();
    }

    if (isPolling) {
      timerRef.current = setInterval(runOnce, interval);
    }

    return () => {
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
    };
    // restart when these change
  }, [isPolling, interval, runOnce, immediate, ...deps]); // eslint-disable-line react-hooks/exhaustive-deps

  const startPolling = useCallback(() => setIsPolling(true), []);
  const stopPolling = useCallback(() => setIsPolling(false), []);
  const refresh = useCallback(() => {
    void runOnce();
  }, [runOnce]);

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
  isPollingEnabled = false,
  interval = 5000,
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
    enabled: isPollingEnabled,
    interval,
    // stop when terminal state reached
    stopWhen: (d) => d.status === "Completed" || d.status === "Failed",
    deps: [sessionName], // restart when session changes
  });
}
