import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { EnvironmentReferenceGroup } from "./environmentDiagnostics";
import { ValidityDashboard } from "./ValidityDashboard";


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


describe("validity dashboard", () => {
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

    await userEvent.click(tab);
    expect(screen.queryByText(/configured reference is available/)).toBeNull();
  });
});
