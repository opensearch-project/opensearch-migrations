import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import DefaultPage from "@/app/page";
import { server } from "@tests/__utils__/mswServer";
import { delay, http, HttpResponse } from "msw";
import { getSiteReadiness, setSiteReadiness } from "@/lib/site-readiness";
import { useRouter } from "next/navigation";

jest.mock("@/lib/site-readiness", () => ({
  getSiteReadiness: jest.fn(() => false),
  setSiteReadiness: jest.fn(),
}));

const replaceMock = jest.fn();
jest.mock("next/navigation", () => ({
  useRouter: () => ({
    replace: replaceMock,
  }),
}));

describe("DefaultPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("loads page if backend service is responsive from cache", async () => {
    (getSiteReadiness as jest.Mock).mockReturnValueOnce(true);
    let serviceCalled = false;
    server.use(
      http.get("http://localhost/system/health", () => {
        serviceCalled = true;
        HttpResponse.json({ status: "ok", checks: {} });
      })
    );

    render(<DefaultPage />);
    expect(
      screen.getByText(/Welcome to Migration Assistant/i)
    ).toBeInTheDocument();
    expect(setSiteReadiness).not.toHaveBeenCalled();
    expect(serviceCalled).toBe(false);
    expect(replaceMock).toHaveBeenCalledTimes(0);
  });

  it("loads page if backend service is responsive", async () => {
    server.use(
      http.get("http://localhost/system/health", () => {
        HttpResponse.json({ status: "ok", checks: {} });
      })
    );

    render(<DefaultPage />);
    expect(
      screen.getByText(/Welcome to Migration Assistant/i)
    ).toBeInTheDocument();
    expect(setSiteReadiness).not.toHaveBeenCalled();
    expect(replaceMock).toHaveBeenCalledTimes(0);
  });

  it("routes to loading page if backend service is non-responsive, on timeout", async () => {
    jest.useFakeTimers(); // Don't wait wall clock time for this test
    server.use(
      http.get("http://localhost/system/health", async () => {
        await delay("infinite");
        return HttpResponse.json(
          { status: "error", checks: {} },
          { status: 503 }
        );
      })
    );

    render(<DefaultPage />);
    expect(
      screen.getByText(/Welcome to Migration Assistant/i)
    ).toBeInTheDocument();
    expect(setSiteReadiness).not.toHaveBeenCalled();
    await waitFor(() => expect(replaceMock).toHaveBeenCalledWith("/loading"), {
      timeout: 6_000,
    });
  });

  it("routes to loading page if backend service is non-responsive, on error response", async () => {
    server.use(
      http.get("http://localhost/system/health", () =>
        HttpResponse.json({ status: "error", checks: {} }, { status: 503 })
      )
    );

    render(<DefaultPage />);
    expect(
      screen.getByText(/Welcome to Migration Assistant/i)
    ).toBeInTheDocument();
    expect(setSiteReadiness).not.toHaveBeenCalled();
    await waitFor(() => expect(replaceMock).toHaveBeenCalledWith("/loading"));
  });

  it("routes to loading page if backend service is non-responsive, on system error", async () => {
    server.use(
      http.get("http://localhost/system/health", () => {
        throw Error("Unexpected error!");
      })
    );

    render(<DefaultPage />);
    expect(
      screen.getByText(/Welcome to Migration Assistant/i)
    ).toBeInTheDocument();
    expect(setSiteReadiness).not.toHaveBeenCalled();
    await waitFor(() => expect(replaceMock).toHaveBeenCalledWith("/loading"));
  });
});
