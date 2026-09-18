import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { EnvironmentReferenceGroup } from "./environmentDiagnostics";
import type { ConnectivityTargetState } from "./connectivityChecks";
import {
  buildValidityItems,
  ValidityDashboard,
} from "./ValidityDashboard";


const secretGroup: EnvironmentReferenceGroup = {
  id: "environment:secret:source-creds",
  label: "source-creds",
  typeLabel: "Kubernetes Secrets",
  status: "valid",
  diagnostics: [],
  references: [
    {
      id: "secret:source-creds",
      category: "secret",
      displayName: "Source credentials",
      name: "source-creds",
      path: ["sourceClusters", "source", "authConfig", "basic", "secretName"],
    },
  ],
};


const sourceState: ConnectivityTargetState = {
  target: {
    id: "source:source",
    kind: "source",
    refName: "source",
    label: "Source source",
    editPath: ["sourceClusters", "source"],
  },
  status: "valid",
};


const repositoryState: ConnectivityTargetState = {
  target: {
    id: "repository:source:repo",
    kind: "repository",
    refName: "repo",
    label: "Repository repo",
    editPath: [
      "sourceClusters",
      "source",
      "snapshotInfo",
      "repos",
      "repo",
    ],
  },
  status: "partially_verified",
};


describe("validity dashboard", () => {
  beforeEach(() => {
    globalThis.localStorage.clear();
  });

  it("keeps cluster, repository, and credential checks in stable order", () => {
    const items = buildValidityItems(
      [secretGroup],
      [repositoryState, sourceState],
    );

    expect(items.map(({ label }) => label)).toEqual([
      "source",
      "repo",
      "source-creds",
    ]);
  });

  it("keeps details collapsed until the user selects a check category", async () => {
    render(
      <ValidityDashboard
        connectivityLoading={false}
        connectivityProblem=""
        connectivityStates={[]}
        environmentGroups={[secretGroup]}
        onCheckConnectivity={vi.fn()}
      />,
    );

    const tab = screen.getByRole("tab", { name: /Kubernetes Secrets/i });
    expect(tab).toHaveTextContent("Kubernetes Secrets · Valid");
    expect(screen.queryByText(/configured reference is available/)).toBeNull();

    await userEvent.click(tab);

    expect(screen.getByText(
      "Valid: the configured reference is available.",
    )).toBeInTheDocument();
    expect(screen.getByRole("separator", {
      name: "Resize validity details",
    })).toBeInTheDocument();

    await userEvent.click(tab);
    expect(screen.queryByText(/configured reference is available/)).toBeNull();
  });

  it("fits expanded details once and lets the user resize the pinned panel", async () => {
    const scrollHeight = vi.spyOn(
      HTMLElement.prototype,
      "scrollHeight",
      "get",
    ).mockReturnValue(212);
    render(
      <ValidityDashboard
        connectivityLoading={false}
        connectivityProblem=""
        connectivityStates={[]}
        environmentGroups={[secretGroup]}
        onCheckConnectivity={vi.fn()}
      />,
    );

    await userEvent.click(screen.getByRole("tab", {
      name: /Kubernetes Secrets/i,
    }));

    const panel = screen.getByRole("tabpanel");
    const separator = screen.getByRole("separator", {
      name: "Resize validity details",
    });
    expect(panel).toHaveStyle({ height: "212px" });
    expect(separator).toHaveAttribute("aria-valuenow", "212");

    separator.focus();
    fireEvent.keyDown(separator, { key: "ArrowDown" });
    expect(panel).toHaveStyle({ height: "236px" });
    expect(separator).toHaveAttribute("aria-valuenow", "236");
    scrollHeight.mockRestore();
  });
});
