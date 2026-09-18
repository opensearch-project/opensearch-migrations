import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { manageSnapshot } from "../../test/fixtures";
import type { ConnectivityTargetState } from "../configuration/connectivityChecks";
import { ResourceWorkspace } from "./ResourceWorkspace";


describe("resource workspace connectivity", () => {
  it("keeps a pending connectivity section visible before checks start", () => {
    const connectivityState: ConnectivityTargetState = {
      target: {
        id: "source:source",
        kind: "source",
        refName: "source",
        label: "Source source",
        editPath: ["sourceClusters", "source"],
      },
      status: "pending",
    };

    render(
      <QueryClientProvider client={new QueryClient()}>
        <ResourceWorkspace
          connectivityState={connectivityState}
          node={manageSnapshot.nodes["resource:captureproxies:capture"]}
          onCheckConnectivity={vi.fn()}
        />
      </QueryClientProvider>,
    );

    expect(screen.getByRole("heading", { name: "Connectivity" }))
      .toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Pending" }))
      .toBeInTheDocument();
  });

  it("shows the current resource check and allows it to be rerun", async () => {
    const onCheckConnectivity = vi.fn();
    const connectivityState: ConnectivityTargetState = {
      target: {
        id: "source:source",
        kind: "source",
        refName: "source",
        label: "Source source",
        editPath: ["sourceClusters", "source"],
      },
      status: "valid",
      check: {
        targetId: "source:source",
        kind: "source",
        refName: "source",
        label: "Source source",
        editPath: ["sourceClusters", "source"],
        checkedAt: "2026-09-18T12:00:00Z",
        status: "valid",
        summary: "Connected.",
        stages: [],
      },
    };

    render(
      <QueryClientProvider client={new QueryClient()}>
        <ResourceWorkspace
          connectivityState={connectivityState}
          node={manageSnapshot.nodes["resource:captureproxies:capture"]}
          onCheckConnectivity={onCheckConnectivity}
        />
      </QueryClientProvider>,
    );

    await userEvent.click(screen.getByRole("button", { name: "Valid" }));
    expect(screen.getByText("Connected.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Recheck" }));
    expect(onCheckConnectivity).toHaveBeenCalledWith(["source:source"]);
  });
});
