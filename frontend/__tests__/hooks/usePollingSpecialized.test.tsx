import React from "react";
import { renderHook, act, waitFor } from "@testing-library/react";
import { usePollingSnapshotStatus, usePollingSystemHealth } from "@/hooks/apiPoll";
import * as api from "@/generated/api";

jest.mock("@/generated/api", () => ({
  snapshotStatus: jest.fn(),
  systemHealth: jest.fn()
}));

describe("usePollingSnapshotStatus hook", () => {
  beforeEach(() => {
    jest.useFakeTimers();
    jest.clearAllMocks();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it("should call snapshotStatus API with correct session name", async () => {
    const mockResponseData = {
      status: "completed",
      progress: 100
    };

    const mockApiResponse = {
      data: mockResponseData,
      response: { status: 200 }
    };

    (api.snapshotStatus as jest.Mock).mockResolvedValue(mockApiResponse);

    const sessionName = "test-session";
    const { result } = renderHook(() => usePollingSnapshotStatus(sessionName, true));

    await act(async () => {
      await Promise.resolve();
    });

    expect(api.snapshotStatus).toHaveBeenCalledWith({
      path: { session_name: sessionName }
    });

    expect(result.current.data).toEqual(mockResponseData);
    expect(result.current.isLoading).toBe(false);
    expect(result.current.error).toBeNull();
    expect(result.current.isPolling).toBe(true);
  });

  it("should handle API error response", async () => {
    const errorStatus = 500;
    const mockErrorResponse = {
      data: null,
      response: { status: errorStatus }
    };

    (api.snapshotStatus as jest.Mock).mockResolvedValue(mockErrorResponse);

    const sessionName = "test-session";
    const { result } = renderHook(() => usePollingSnapshotStatus(sessionName, true));

    await act(async () => {
      await Promise.resolve().catch(() => {});
    });

    expect(result.current.error).toBe(
      `Error: API Error: ${errorStatus} - Failed to fetch snapshot status`
    );
    expect(result.current.data).toBeNull();
    expect(result.current.isLoading).toBe(false);
  });

  it("should handle network or other errors", async () => {
    const testError = new Error("Network error");
    
    (api.snapshotStatus as jest.Mock).mockRejectedValue(testError);

    const sessionName = "test-session";
    const { result } = renderHook(() => usePollingSnapshotStatus(sessionName, true));

    await act(async () => {
      await Promise.resolve().catch(() => {});
    });

    expect(result.current.error).toBe(String(testError));
    expect(result.current.data).toBeNull();
    expect(result.current.isLoading).toBe(false);
  });
});

describe("usePollingSystemHealth hook", () => {
  beforeEach(() => {
    jest.useFakeTimers();
    jest.clearAllMocks();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it("should call systemHealth API and process the response", async () => {
    const mockHealthData = {
      status: "green",
      services: {
        database: "healthy"
      }
    };

    const mockApiResponse = {
      data: mockHealthData,
      response: { status: 200 }
    };

    (api.systemHealth as jest.Mock).mockResolvedValue(mockApiResponse);

    const mockStopWhen = jest.fn().mockImplementation((data) => data.status === "green");

    const { result } = renderHook(() => 
      usePollingSystemHealth(true, mockStopWhen)
    );

    await act(async () => {
      await Promise.resolve();
    });

    expect(api.systemHealth).toHaveBeenCalled();

    expect(result.current.data).toEqual(mockHealthData);
    expect(result.current.isLoading).toBe(false);
    expect(result.current.error).toBeNull();

    expect(mockStopWhen).toHaveBeenCalledWith(mockHealthData);

    await waitFor(() => {
      expect(result.current.isPolling).toBe(false);
    });
  });

  it("should handle API error response", async () => {
    const errorStatus = 500;
    const mockError = { message: "Internal server error" };
    const mockErrorResponse = {
      data: null,
      error: mockError,
      response: { status: errorStatus }
    };

    (api.systemHealth as jest.Mock).mockResolvedValue(mockErrorResponse);

    const mockStopWhen = jest.fn();

    const { result } = renderHook(() => 
      usePollingSystemHealth(true, mockStopWhen)
    );

    await act(async () => {
      await Promise.resolve().catch(() => {});
    });

    expect(result.current.error).toBe(
      `Error: API Error: ${errorStatus} - ${JSON.stringify(mockError)}`
    );
    expect(result.current.data).toBeNull();
    expect(result.current.isLoading).toBe(false);

    expect(mockStopWhen).not.toHaveBeenCalled();
  });

  it("should continue polling until stopWhen returns true", async () => {
    const firstResponse = {
      data: { status: "yellow" },
      response: { status: 200 }
    };

    const secondResponse = {
      data: { status: "green" },
      response: { status: 200 }
    };

    (api.systemHealth as jest.Mock)
      .mockResolvedValueOnce(firstResponse)
      .mockResolvedValueOnce(secondResponse);

    const mockStopWhen = jest.fn().mockImplementation((data) => data.status === "green");

    const { result } = renderHook(() => 
      usePollingSystemHealth(true, mockStopWhen)
    );

    await act(async () => {
      await Promise.resolve();
    });

    expect(mockStopWhen).toHaveBeenCalledWith(firstResponse.data);
    expect(result.current.isPolling).toBe(true);

    await act(async () => {
      jest.advanceTimersByTime(5000);
    });

    await act(async () => {
      await Promise.resolve();
    });

    expect(mockStopWhen).toHaveBeenCalledWith(secondResponse.data);
    
    await waitFor(() => {
      expect(result.current.isPolling).toBe(false);
    });
  });
});
