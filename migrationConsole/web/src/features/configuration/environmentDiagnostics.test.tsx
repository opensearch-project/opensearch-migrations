import { render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { server } from "../../test/server";
import {
  applyBrowserEditOperation,
  createBrowserConfigDraft,
  type BrowserConfigDraft,
} from "./browserDraft";
import {
  environmentDiagnosticNonce,
  useEnvironmentDiagnostics,
} from "./environmentDiagnostics";


const document = {
  modelVersion: "1" as const,
  persistedRevision: "saved-1",
  rawYaml: `
sourceClusters:
  source:
    endpoint: https://source.example.com:9200
    version: ES 7.10
    allowInsecure: false
    authConfig:
      basic:
        secretName: source-creds
targetClusters: {}
snapshotMigrationConfigs: []
`,
};


function Harness({
  draft,
}: Readonly<{ draft: BrowserConfigDraft }>) {
  const state = useEnvironmentDiagnostics(draft, 0);
  return (
    <>
      <span data-testid="lifecycle">{state.lifecycle}</span>
      <span data-testid="message">{state.diagnostics[0]?.message ?? ""}</span>
    </>
  );
}


describe("configuration environment diagnostics", () => {
  it("changes the nonce only for configured external references", () => {
    const initial = createBrowserConfigDraft(document);
    const unrelated = applyBrowserEditOperation(initial, {
      op: "set",
      path: ["sourceClusters", "source", "allowInsecure"],
      value: true,
    });
    const changedReference = applyBrowserEditOperation(unrelated, {
      op: "set",
      path: [
        "sourceClusters",
        "source",
        "authConfig",
        "basic",
        "secretName",
      ],
      value: "other-creds",
    });

    expect(environmentDiagnosticNonce(unrelated.editState.nodes))
      .toBe(environmentDiagnosticNonce(initial.editState.nodes));
    expect(environmentDiagnosticNonce(changedReference.editState.nodes))
      .not.toBe(environmentDiagnosticNonce(initial.editState.nodes));
  });

  it("keeps a superseded response from replacing newer diagnostics", async () => {
    let releaseFirst: (() => void) | undefined;
    const firstPending = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });
    server.use(
      http.post("*/api/v1/config/diagnostics", async ({ request }) => {
        const body = await request.json() as {
          draftNonce: string;
          rawYaml: string;
        };
        if (body.rawYaml.includes("source-creds")) {
          await firstPending;
          return HttpResponse.json({
            draftNonce: body.draftNonce,
            status: "error",
            diagnostics: [{
              severity: "error",
              message: "old result",
              path: [],
            }],
          });
        }
        return HttpResponse.json({
          draftNonce: body.draftNonce,
          status: "valid",
          diagnostics: [],
        });
      }),
    );
    const initial = createBrowserConfigDraft(document);
    const changed = applyBrowserEditOperation(initial, {
      op: "set",
      path: [
        "sourceClusters",
        "source",
        "authConfig",
        "basic",
        "secretName",
      ],
      value: "other-creds",
    });
    const view = render(<Harness draft={initial} />);
    await waitFor(() => expect(screen.getByTestId("lifecycle"))
      .toHaveTextContent("checking"));

    view.rerender(<Harness draft={changed} />);
    await waitFor(() => expect(screen.getByTestId("lifecycle"))
      .toHaveTextContent("valid"));
    releaseFirst?.();

    await waitFor(() => expect(screen.getByTestId("message"))
      .toHaveTextContent(""));
    expect(screen.getByTestId("lifecycle")).toHaveTextContent("valid");
  });
});
