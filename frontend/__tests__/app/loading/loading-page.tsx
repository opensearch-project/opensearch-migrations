import React from "react";
import { render, screen, waitFor, act } from "@testing-library/react";
import LoadingPage from "@/app/loading/page";
import { server } from "@tests/__utils__/mswServer";
import { http, HttpResponse } from "msw";
import { getSiteReadiness, setSiteReadiness } from "@/lib/site-readiness";

jest.mock("@/lib/site-readiness", () => ({
  getSiteReadiness: jest.fn(() => false),
  setSiteReadiness: jest.fn(),
}));

jest.mock("next/navigation", () => ({
  useRouter: () => ({
    push: jest.fn(),
  }),
  useSearchParams: () => ({
    get: jest.fn(),
  }),
}));

describe("LoadingPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("short-circuits to ready UI when cached", async () => {
    (getSiteReadiness as jest.Mock).mockReturnValueOnce(true);
    let serviceCalled = false;
    server.use(
      http.get("http://localhost/system/health", () => {
        serviceCalled = true;
        return HttpResponse.json({ status: "ok", checks: {} });
      })
    );

    render(<LoadingPage />);
    await waitFor(() =>
      expect(screen.getByTestId("start-migration-button")).toBeInTheDocument()
    );
    expect(setSiteReadiness).not.toHaveBeenCalled();
    expect(serviceCalled).toBe(false);
  });

  it("polls then shows ready UI on success", async () => {
    server.use(
      http.get("http://localhost/system/health", () =>
        HttpResponse.json({ status: "ok", checks: {} })
      )
    );

    render(<LoadingPage />);
    expect(screen.getByText("Setup is in progress")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByTestId("start-migration-button")).toBeInTheDocument()
    );
    expect(setSiteReadiness).toHaveBeenCalledWith(true);
  });

  it("shows error details on failure response", async () => {
    server.use(
      http.get("http://localhost/system/health", () =>
        HttpResponse.json({ status: "error", checks: {} }, { status: 503 })
      )
    );

    render(<LoadingPage />);
    await screen.findByText(/Details/i);
    expect(screen.getByText(/Error Message/i)).toBeInTheDocument();
    expect(setSiteReadiness).not.toHaveBeenCalled();
  });

  it("shows network error and then eventually succeeds", async () => {
    jest.useFakeTimers(); // Don't wait wall clock time for this test
    let callCount = 0;
    server.use(
      http.get("http://localhost/system/health", () => {
        switch (++callCount) {
          case 1:
            throw new Error("Connection Unavailable");
          case 2:
            return HttpResponse.json(
              { status: "error", checks: { count: callCount } },
              { status: 503 }
            );
          default:
            return HttpResponse.json({ status: "ok", checks: {} });
        }
      })
    );

    render(<LoadingPage />);
    await screen.findByText(/Details/i);
    expect(screen.getByText(/Error Message/i)).toBeInTheDocument();
    await waitFor(
      () =>
        expect(screen.getByTestId("start-migration-button")).toBeEnabled(),
      { timeout: 11_000 }
    );
    expect(callCount).toBe(3);
    expect(setSiteReadiness).toHaveBeenCalledWith(true);
    jest.useRealTimers();
  });
});
