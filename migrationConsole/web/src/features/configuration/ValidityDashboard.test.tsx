import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { EnvironmentReferenceGroup } from "./environmentDiagnostics";
import { ValidityDashboard } from "./ValidityDashboard";


const secretGroup: EnvironmentReferenceGroup = {
  id: "environment:secret",
  label: "Kubernetes Secrets",
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
    {
      id: "secret:target-creds",
      category: "secret",
      displayName: "Target credentials",
      name: "target-creds",
      path: ["targetClusters", "target", "authConfig", "basic", "secretName"],
    },
    {
      id: "secret:proxy-tls",
      category: "secret",
      displayName: "Proxy TLS",
      name: "proxy-tls",
      path: ["traffic", "proxies", "capture", "tls", "secretName"],
    },
    {
      id: "secret:kafka-creds",
      category: "secret",
      displayName: "Kafka credentials",
      name: "kafka-creds",
      path: ["traffic", "kafkaClusters", "main", "secretName"],
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
    expect(tab).toHaveTextContent("Valid · 4");
    expect(screen.queryByText(/Checked source-creds/)).toBeNull();

    await userEvent.click(tab);

    expect(screen.getByText(
      "Checked source-creds, target-creds, proxy-tls, ... (4 checked).",
    )).toBeInTheDocument();
    expect(screen.getByText("kafka-creds")).toBeInTheDocument();

    await userEvent.click(tab);
    expect(screen.queryByText(/Checked source-creds/)).toBeNull();
  });
});
