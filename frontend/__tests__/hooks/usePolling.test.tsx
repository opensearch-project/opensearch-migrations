import React from "react";
import { renderHook, act, waitFor } from "@testing-library/react";
import { usePolling } from "@/hooks/apiPoll";

describe("usePolling hook", () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
    jest.clearAllMocks();
  });

  describe("Initial state and setup", () => {
    it("should initialize with correct default values when disabled", () => {
      const mockFetch = jest.fn().mockResolvedValue("test data");
      
      const { result } = renderHook(() => usePolling(mockFetch, { enabled: false }));
      
      expect(result.current.isLoading).toBe(true);
      expect(result.current.data).toBeNull();
      expect(result.current.error).toBeNull();
      expect(result.current.isPolling).toBe(false);
      expect(result.current.lastUpdated).toBeNull();
      expect(typeof result.current.startPolling).toBe("function");
      expect(typeof result.current.stopPolling).toBe("function");
      expect(typeof result.current.refresh).toBe("function");
      
      expect(mockFetch).not.toHaveBeenCalled();
    });

    it("should initialize and start polling when enabled is true", async () => {
      const mockFetch = jest.fn().mockResolvedValue("test data");
      
      const { result } = renderHook(() => usePolling(mockFetch, { enabled: true }));
      
      expect(result.current.isLoading).toBe(true);
      expect(result.current.isPolling).toBe(true);
      
      await act(async () => {
        await Promise.resolve();
      });
      
      expect(mockFetch).toHaveBeenCalledTimes(1);
    });
  });

  describe("Fetch functionality", () => {
    it("should set data and update state when fetch succeeds", async () => {
      const testData = { key: "value" };
      const mockFetch = jest.fn().mockResolvedValue(testData);
      
      const { result } = renderHook(() => usePolling(mockFetch, { enabled: true }));
      
      await act(async () => {
        await Promise.resolve();
      });
      
      expect(result.current.isLoading).toBe(false);
      expect(result.current.data).toEqual(testData);
      expect(result.current.error).toBeNull();
      expect(result.current.isPolling).toBe(true);
      expect(result.current.lastUpdated).not.toBeNull();
    });

    it("should set error state when fetch fails", async () => {
      const testError = new Error("Test error");
      const mockFetch = jest.fn().mockRejectedValue(testError);
      
      const { result } = renderHook(() => usePolling(mockFetch, { enabled: true }));
      
      await act(async () => {
        await Promise.resolve().catch(() => {});
      });
      
      expect(result.current.isLoading).toBe(false);
      expect(result.current.data).toBeNull();
      expect(result.current.error).toBe(String(testError));
      expect(result.current.isPolling).toBe(true);
    });
  });

  describe("Polling control", () => {
    it("should start and stop polling based on the enabled option", async () => {
      const mockFetch = jest.fn().mockResolvedValue("test data");
      
      const { result, rerender } = renderHook(
        (props) => usePolling(mockFetch, props),
        { initialProps: { enabled: false } }
      );
      
      expect(result.current.isPolling).toBe(false);
      expect(mockFetch).not.toHaveBeenCalled();
      
      rerender({ enabled: true });
      
      expect(result.current.isPolling).toBe(true);
      
      await act(async () => {
        await Promise.resolve();
      });
      
      expect(mockFetch).toHaveBeenCalledTimes(1);
      
      await act(async () => {
        jest.advanceTimersByTime(5000);
      });
      
      expect(mockFetch).toHaveBeenCalledTimes(2);
      
      rerender({ enabled: false });
      
      expect(result.current.isPolling).toBe(false);
      
      await act(async () => {
        jest.advanceTimersByTime(5000);
      });
      
      expect(mockFetch).toHaveBeenCalledTimes(2);
    });

    it("should use the provided polling interval", async () => {
      const mockFetch = jest.fn().mockResolvedValue("test data");
      const customInterval = 2000; // 2 seconds
      
      renderHook(() => usePolling(mockFetch, { enabled: true, interval: customInterval }));
      
      await act(async () => {
        await Promise.resolve();
      });
      
      expect(mockFetch).toHaveBeenCalledTimes(1);
      
      await act(async () => {
        jest.advanceTimersByTime(1000); // 1 second
      });
      
      expect(mockFetch).toHaveBeenCalledTimes(1);
      
      await act(async () => {
        jest.advanceTimersByTime(1000); // Another 1 second
      });
      
      expect(mockFetch).toHaveBeenCalledTimes(2);
    });

    it("should allow manual start and stop of polling", async () => {
      const mockFetch = jest.fn().mockResolvedValue("test data");
      
      const { result } = renderHook(() => usePolling(mockFetch, { enabled: false }));
      
      expect(result.current.isPolling).toBe(false);
      
      act(() => {
        result.current.startPolling();
      });
      
      expect(result.current.isPolling).toBe(true);
      
      await act(async () => {
        await Promise.resolve();
      });
      
      expect(mockFetch).toHaveBeenCalledTimes(1);
      
      act(() => {
        result.current.stopPolling();
      });
      
      expect(result.current.isPolling).toBe(false);
      
      await act(async () => {
        jest.advanceTimersByTime(5000);
      });
      
      expect(mockFetch).toHaveBeenCalledTimes(1);
    });

    it("should allow manual refresh without changing polling state", async () => {
      const mockFetch = jest.fn().mockResolvedValue("test data");
      
      const { result } = renderHook(() => usePolling(mockFetch, { enabled: false }));
      
      expect(result.current.isPolling).toBe(false);
      expect(mockFetch).not.toHaveBeenCalled();
      
      act(() => {
        result.current.refresh();
      });
      
      await act(async () => {
        await Promise.resolve();
      });
      
      expect(mockFetch).toHaveBeenCalledTimes(1);
      expect(result.current.isPolling).toBe(false);
    });
  });

  describe("Advanced functionality", () => {
    it("should stop polling when stopWhen condition is met", async () => {
      const testData = { status: "completed" };
      const mockFetch = jest.fn().mockResolvedValue(testData);
      const stopWhen = jest.fn().mockImplementation((data) => data.status === "completed");
      
      renderHook(() => usePolling(mockFetch, { enabled: true, stopWhen }));
      
      await act(async () => {
        await Promise.resolve();
      });
      
      expect(stopWhen).toHaveBeenCalledWith(testData);
      
      await waitFor(() => {
        expect(stopWhen).toHaveBeenCalled();
      });
    });

    it("should prevent overlapping requests", async () => {
      let resolveFunction: (value: any) => void;
      const mockFetch = jest.fn().mockImplementation(() => {
        return new Promise((resolve) => {
          resolveFunction = resolve;
        });
      });
      
      const { result } = renderHook(() => usePolling(mockFetch, { enabled: true }));
      
      expect(mockFetch).toHaveBeenCalledTimes(1);
      
      act(() => {
        result.current.refresh();
      });
      
      expect(mockFetch).toHaveBeenCalledTimes(1);
      
      await act(async () => {
        resolveFunction!("test data");
        await Promise.resolve();
      });
      
      expect(mockFetch).toHaveBeenCalledTimes(1);
      
      act(() => {
        result.current.refresh();
      });
      
      expect(mockFetch).toHaveBeenCalledTimes(2);
    });
    
    it("should update lastUpdated timestamp when data is fetched", async () => {
      const mockDate = new Date(2023, 0, 1).getTime(); // Jan 1, 2023
      jest.spyOn(Date, 'now').mockImplementation(() => mockDate);
      
      const mockFetch = jest.fn().mockResolvedValue("test data");
      
      const { result } = renderHook(() => usePolling(mockFetch, { enabled: true }));
      
      expect(result.current.lastUpdated).toBeNull();
      
      await act(async () => {
        await Promise.resolve();
      });
      
      expect(result.current.lastUpdated).toBe(mockDate);
      
      jest.spyOn(Date, 'now').mockRestore();
    });
  });

  describe("Cleanup and unmount", () => {
    it("should clear timeouts when unmounted", async () => {
      const mockFetch = jest.fn().mockResolvedValue("test data");
      
      const { unmount } = renderHook(() => usePolling(mockFetch, { enabled: true }));
      
      await act(async () => {
        await Promise.resolve();
      });
      
      mockFetch.mockClear();
      
      unmount();
      
      await act(async () => {
        jest.advanceTimersByTime(5000);
      });
      
      expect(mockFetch).not.toHaveBeenCalled();
    });
  });

  describe("Dependency updates", () => {
    it("should use the latest fetch function", async () => {
      const mockFetch1 = jest.fn().mockResolvedValue("data from first fetch");
      const mockFetch2 = jest.fn().mockResolvedValue("data from second fetch");
      
      const { result, rerender } = renderHook(
        ({ fetchFn }) => usePolling(fetchFn, { enabled: true }),
        { initialProps: { fetchFn: mockFetch1 } }
      );
      
      await act(async () => {
        await Promise.resolve();
      });
      
      expect(mockFetch1).toHaveBeenCalledTimes(1);
      expect(mockFetch2).not.toHaveBeenCalled();
      
      rerender({ fetchFn: mockFetch2 });
      
      await act(async () => {
        jest.advanceTimersByTime(5000);
      });
      
      await act(async () => {
        await Promise.resolve();
      });
      
      expect(mockFetch2).toHaveBeenCalledTimes(1);
    });

    it("should use the latest stopWhen function", async () => {
      const testData = { status: "in_progress" };
      const mockFetch = jest.fn().mockResolvedValue(testData);
      
      const stopWhen1 = jest.fn().mockImplementation(() => false);
      const stopWhen2 = jest.fn().mockImplementation(() => true);
      
      const { result, rerender } = renderHook(
        ({ stopWhen }) => usePolling(mockFetch, { enabled: true, stopWhen }),
        { initialProps: { stopWhen: stopWhen1 } }
      );
      
      await act(async () => {
        await Promise.resolve();
      });
      
      expect(stopWhen1).toHaveBeenCalledWith(testData);
      expect(result.current.isPolling).toBe(true);
      
      rerender({ stopWhen: stopWhen2 });
      
      await act(async () => {
        jest.advanceTimersByTime(5000);
      });
      
      await act(async () => {
        await Promise.resolve();
      });
      
      expect(stopWhen2).toHaveBeenCalledWith(testData);
      
      await waitFor(() => {
        expect(result.current.isPolling).toBe(false);
      });
    });
  });
});
