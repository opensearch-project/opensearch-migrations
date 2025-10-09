import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
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
      }),
    );

    render(<LoadingPage />);
    await waitFor(() =>
      expect(screen.getByTestId("start-migration-button")).toBeInTheDocument(),
    );
    expect(setSiteReadiness).not.toHaveBeenCalled();
    expect(serviceCalled).toBe(false);
  });

  it("polls then shows ready UI on success", async () => {
    server.use(
      http.get("http://localhost/system/health", () =>
        HttpResponse.json({ status: "ok", checks: {} }),
      ),
    );

    render(<LoadingPage />);
    expect(screen.getByText("Setup is in progress")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByTestId("start-migration-button")).toBeInTheDocument(),
    );
    expect(setSiteReadiness).toHaveBeenCalledWith(true);
  });

  it("shows error details on failure response", async () => {
    let callCount = 0;
    server.use(
      http.get("http://localhost/system/health", () => {
        callCount++;
        return HttpResponse.json(
          { status: "error", checks: {} },
          { status: 503 },
        );
      }),
    );

    render(<LoadingPage />);
    await screen.findByText(/Details/i);
    expect(screen.getByText(/Error Message/i)).toBeInTheDocument();
    expect(setSiteReadiness).not.toHaveBeenCalled();
    expect(screen.getByTestId("start-migration-button")).toBeDisabled();
    expect(callCount).toBeGreaterThanOrEqual(1);
  });
});
