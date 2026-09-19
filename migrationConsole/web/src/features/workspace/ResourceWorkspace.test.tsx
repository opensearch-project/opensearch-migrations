import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { ApprovalGateSummary, ManageNode } from "../../api/client";
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

    expect(screen.getByRole("region", { name: "Resource checks" }))
      .toBeInTheDocument();
    expect(screen.getByRole("tab", {
      name: /source.*Source Cluster · Pending/i,
    }))
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

    await userEvent.click(screen.getByRole("tab", {
      name: /source.*Source Cluster · Valid/i,
    }));
    expect(screen.getByText("Connected.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Recheck" }));
    expect(onCheckConnectivity).toHaveBeenCalledWith(["source:source"]);
  });
});


const upcomingGate: ApprovalGateSummary = {
  name: "captureproxysetup.capture",
  gateRevision: "21",
  category: "checkpoint",
  state: "upcoming",
  phase: "Created",
  resourceId: "resource:captureproxies:capture",
  resourceKind: "CaptureProxy",
  resourceName: "capture",
  stage: "Capture proxy deployment",
  effect: "Approving deploys the capture proxy.",
  reason: null,
  enabled: true,
  approved: false,
  toggleable: true,
  disabledReason: null,
  approvalTargetId: null,
  outputTargetId: null,
};


describe("resource workspace runtime presentation", () => {
  it("hides preapproval actions after the workflow finishes", () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <ResourceWorkspace
          approvalGates={[upcomingGate]}
          node={manageSnapshot.nodes["resource:captureproxies:capture"]}
          onTogglePreapprovals={vi.fn()}
          workflowPhase="Succeeded"
        />
      </QueryClientProvider>,
    );

    expect(screen.queryByRole("button", { name: "Preapprove" })).toBeNull();
    expect(screen.queryByText("Preapprove upcoming checkpoints")).toBeNull();
  });

  it("keeps resource deletion actions together and removes identity noise", () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <ResourceWorkspace
          node={manageSnapshot.nodes["resource:captureproxies:capture"]}
          onDelete={vi.fn()}
          workflowPhase="Succeeded"
        />
      </QueryClientProvider>,
    );

    const actions = screen.getByLabelText("Available actions");
    const buttons = within(actions).getAllByRole("button");
    const deleteIndex = buttons.findIndex(
      (button) => button.textContent?.includes("Delete resource"),
    );
    const removeIndex = buttons.findIndex(
      (button) => button.textContent?.includes("Remove from configuration"),
    );
    expect(removeIndex).toBe(deleteIndex + 1);
    expect(screen.queryByText("captureproxies/capture")).toBeNull();
    expect(screen.queryByText("RESOURCE")).toBeNull();
    expect(screen.queryByText("Current state")).toBeNull();
  });

  it("summarizes groups as a table of child resources", () => {
    const group = manageSnapshot.nodes[
      "group:Live Traffic Migration:Capture"
    ];
    render(
      <QueryClientProvider client={new QueryClient()}>
        <ResourceWorkspace
          node={group}
          nodes={manageSnapshot.nodes}
          onSelect={vi.fn()}
          workflowPhase="Succeeded"
        />
      </QueryClientProvider>,
    );

    expect(screen.getByText("(GROUP)")).toBeInTheDocument();
    expect(screen.getByRole("region", {
      name: "1 capture resources",
    })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "State" }))
      .toBeInTheDocument();
    expect(screen.getByRole("button", {
      name: /capture\s+Capture proxy/i,
    }))
      .toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Details" })).toBeNull();
  });

  it("omits the group actions column when no resource has an action", () => {
    const snapshot = structuredClone(manageSnapshot);
    const group = snapshot.nodes[
      "group:Live Traffic Migration:Capture"
    ];
    group.childIds.forEach((childId) => {
      snapshot.nodes[childId].capabilities = [];
    });
    render(
      <QueryClientProvider client={new QueryClient()}>
        <ResourceWorkspace
          node={group}
          nodes={snapshot.nodes}
          onSelect={vi.fn()}
          workflowPhase="Succeeded"
        />
      </QueryClientProvider>,
    );

    expect(screen.queryByRole("columnheader", { name: "Actions" }))
      .toBeNull();
  });

  it("shows configuration fields for config-only resources", () => {
    const base = manageSnapshot.nodes[
      "resource:captureproxies:capture"
    ];
    const snapshot: ManageNode = {
      ...base,
      id: "resource:datasnapshots:source-snapshot",
      label: "source-snapshot",
      parentId: "resource:sourceconfigs:source",
      childIds: [],
      phase: "Completed",
      status: "ok",
      resourcePlural: "datasnapshots",
      resourceName: "source-snapshot",
      resourceType: "Data snapshot",
    };
    const source: ManageNode = {
      ...base,
      id: "resource:sourceconfigs:source",
      label: "source",
      childIds: [snapshot.id],
      phase: "Deployed Config",
      status: "unknown",
      resourcePlural: "sourceconfigs",
      resourceName: "source",
      resourceType: "Source cluster",
      capabilities: [],
      comparisons: [],
      details: [
        { label: "Phase", value: "Deployed Config", kind: "phase" },
        { label: "endpoint", value: "https://source:9200", kind: "spec" },
      ],
    };
    render(
      <QueryClientProvider client={new QueryClient()}>
        <ResourceWorkspace
          node={source}
          nodes={{
            [source.id]: source,
            [snapshot.id]: snapshot,
          }}
          onSelect={vi.fn()}
          workflowPhase="Succeeded"
        />
      </QueryClientProvider>,
    );

    const configuration = screen.getByRole("region", {
      name: "Configuration",
    });
    expect(within(configuration).getByRole("columnheader", {
      name: "Saved configuration",
    })).toBeInTheDocument();
    expect(within(configuration).getByText("https://source:9200"))
      .toBeInTheDocument();
    expect(screen.getByRole("region", {
      name: "Related resources",
    })).toBeInTheDocument();
  });
});
