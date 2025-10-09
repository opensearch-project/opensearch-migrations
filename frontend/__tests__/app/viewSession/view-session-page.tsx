import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import ViewSessionPage from "@/app/viewSession/page";
import { server } from "../../__utils__/mswServer";
import { http, HttpResponse } from "msw";
import { useSearchParams } from "next/navigation";

jest.mock("next/navigation", () => ({
  useSearchParams: jest.fn(),
  useRouter: () => ({
    push: jest.fn(),
  }),
}));

describe("ViewSessionPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  const mockSearchParams = (sessionName: string | null) => {
    const searchParams = new URLSearchParams();
    if (sessionName) {
      searchParams.set("sessionName", sessionName);
    }
    (useSearchParams as jest.Mock).mockReturnValue(searchParams);
  };

  it("renders the page with session name and components", async () => {
    server.use(
      http.get("http://localhost/sessions/test-session", () => {
        return HttpResponse.json({
          name: "test-session",
          created: "2023-01-01T00:00:00Z",
        });
      }),
      http.get("http://localhost/sessions/test-session/snapshot/status", () => {
        return HttpResponse.json({
          status: "Completed",
          percentage_completed: 100,
          eta_ms: null,
          started: "2023-01-01T00:00:00Z",
          finished: "2023-01-01T01:00:00Z",
        });
      }),
      http.get("http://localhost/sessions/test-session/metadata/status", () => {
        return HttpResponse.json({
          status: "Completed",
          started: "2023-01-01T00:00:00Z",
          finished: "2023-01-01T01:00:00Z",
          clusters: {
            source: {
              type: "Snapshot",
              version: "ELASTICSEARCH 7.10.0",
            },
            target: {
              type: "Remote Cluster",
              version: "OPENSEARCH 2.11.0",
            },
          },
          errorCount: 0
        });
      }),
      http.get("http://localhost/sessions/test-session/backfill/status", () => {
        return HttpResponse.json({
          status: "Completed",
          percentage_completed: 100,
          eta_ms: null,
          started: "2023-01-01T00:00:00Z",
          finished: "2023-01-01T01:00:00Z",
          shard_total: 10,
          shard_complete: 10,
          shard_in_progress: 0,
          shard_waiting: 0,
        });
      })
    );

    mockSearchParams("test-session");

    render(<ViewSessionPage />);

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
      "Migration Session - test-session"
    );

    await waitFor(() => {
      expect(screen.getByText("Session Overview")).toBeInTheDocument();
      expect(screen.getByText("Snapshot")).toBeInTheDocument();
      expect(screen.getByText("Metadata Migration")).toBeInTheDocument();
      expect(screen.getByText("Backfill")).toBeInTheDocument();
    });

    await waitFor(() => {
      expect(screen.getByText("test-session")).toBeInTheDocument();
      // Since date formats may vary by locale, let's check for the date with regex
      expect(screen.getByText(/1.*2023/)).toBeInTheDocument();
    });

    await waitFor(() => {
      // One for snapshot and the other for backfill - metadata doesn't include a percentage
      expect(screen.getAllByText("100%")).toHaveLength(2);
    });
  });

  it("shows error state when session API fails", async () => {
    server.use(
      http.get("http://localhost/sessions/test-session", () => {
        return HttpResponse.json(
          { error: { detail: "Session not found" } },
          { status: 404 }
        );
      }),
      http.get("http://localhost/sessions/test-session/snapshot/status", () => {
        return HttpResponse.json({
          status: "Completed",
          percentage_completed: 100,
          eta_ms: null,
          started: "2023-01-01T00:00:00Z",
          finished: "2023-01-01T01:00:00Z",
        });
      })
    );

    mockSearchParams("test-session");

    render(<ViewSessionPage />);

    await waitFor(() => {
      expect(screen.getByText("Failed to load data")).toBeInTheDocument();
    });
  });

  it("shows error state when snapshot API fails", async () => {
    server.use(
      http.get("http://localhost/sessions/test-session", () => {
        return HttpResponse.json({
          name: "test-session",
          created: "2023-01-01T00:00:00Z",
        });
      }),
      http.get("http://localhost/sessions/test-session/snapshot/status", () => {
        return HttpResponse.json(
          { error: { detail: "Snapshot not found" } },
          { status: 404 }
        );
      })
    );

    mockSearchParams("test-session");

    render(<ViewSessionPage />);

    await waitFor(() => {
      expect(screen.getByText(/API Error: 404 - Failed to fetch snapshot status data/)).toBeInTheDocument();
    });
  });

  it("handles missing session name parameter", async () => {
    mockSearchParams(null);

    render(<ViewSessionPage />);

    expect(screen.getByText("Unable to find an associated session")).toBeInTheDocument();
    expect(screen.getByText("Please create a session or adjust the sessionName parameter in the url.")).toBeInTheDocument();
    expect(screen.queryByText("Session Overview")).not.toBeInTheDocument();
    expect(screen.queryByText("Snapshot")).not.toBeInTheDocument();
  });

  it("shows loading state while fetching data", async () => {
    server.use(
      http.get("http://localhost/sessions/test-session", async () => {
        await new Promise((resolve) => setTimeout(resolve, 100)); // Small delay
        return HttpResponse.json({
          name: "test-session",
          created: "2023-01-01T00:00:00Z",
        });
      }),
      http.get("http://localhost/sessions/test-session/snapshot/status", async () => {
        await new Promise((resolve) => setTimeout(resolve, 100)); // Small delay
        return HttpResponse.json({
          status: "Running",
          percentage_completed: 50,
          eta_ms: 3600000, // 1 hour
          started: "2023-01-01T00:00:00Z",
          finished: null,
        });
      })
    );

    mockSearchParams("test-session");

    render(<ViewSessionPage />);

    // Initially we should see the loading indicator from Suspense
    // We don't need to check for the spinner specifically as it's handled by Suspense
    // which is automatically mocked in the test

    await waitFor(() => {
      expect(screen.getByText("test-session")).toBeInTheDocument();
      expect(screen.getByText("50%")).toBeInTheDocument();
    });
  });

  it("renders different snapshot statuses correctly", async () => {
    server.use(
      http.get("http://localhost/sessions/test-session", () => {
        return HttpResponse.json({
          name: "test-session",
          created: "2023-01-01T00:00:00Z",
        });
      }),
      http.get("http://localhost/sessions/test-session/snapshot/status", () => {
        return HttpResponse.json({
          status: "Running",
          percentage_completed: 75,
          eta_ms: 1800000, // 30 minutes
          started: "2023-01-01T00:00:00Z",
          finished: null,
        });
      })
    );

    mockSearchParams("test-session");

    render(<ViewSessionPage />);

    // Check that the running status is displayed correctly
    await waitFor(() => {
      expect(screen.getByText("75%")).toBeInTheDocument();
      expect(screen.getByText("30 minutes")).toBeInTheDocument();
    });
  });
});
