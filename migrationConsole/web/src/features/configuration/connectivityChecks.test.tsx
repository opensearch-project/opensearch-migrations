import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { server } from "../../test/server";
import {
  applyBrowserEditOperation,
  createBrowserConfigDraft,
  type BrowserConfigDraft,
} from "./browserDraft";
import {
  ConnectivityDialog,
  useConnectivityChecks,
  type ConnectivityTargetState,
} from "./connectivityChecks";


const document = {
  modelVersion: "1" as const,
  persistedRevision: "saved-1",
  rawYaml: `
sourceClusters:
  source:
    endpoint: https://source.example.com:9200
    version: ES 7.10
targetClusters: {}
snapshotMigrationConfigs: []
`,
};


function Harness({ draft }: Readonly<{ draft: BrowserConfigDraft }>) {
  const connectivity = useConnectivityChecks(
    draft,
    ["sourceClusters", "source"],
    0,
  );
  return (
    <>
      <span data-testid="status">
        {connectivity.selectedState?.status ?? "loading"}
      </span>
      <span data-testid="inventory-loading">
        {String(connectivity.inventoryLoading)}
      </span>
      {connectivity.states.map((state) => (
        <span data-testid={`status-${state.target.id}`} key={state.target.id}>
          {state.status}
        </span>
      ))}
      <button
        onClick={() => {
          const id = connectivity.selectedState?.target.id;
          if (id) void connectivity.start([id]);
        }}
        type="button"
      >
        Check
      </button>
    </>
  );
}


function renderHarness(draft: BrowserConfigDraft) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <Harness draft={draft} />
    </QueryClientProvider>,
  );
}


describe("configuration connectivity checks", () => {
  it("reports loading while the initial inventory is being resolved", async () => {
    let resolveInventory: ((value: Response) => void) | undefined;
    const draft = createBrowserConfigDraft(document);
    server.use(
      http.post(
        "*/api/v1/config/connectivity/inventory",
        () => new Promise<Response>((resolve) => {
          resolveInventory = resolve;
        }),
      ),
    );

    renderHarness(draft);

    await waitFor(() => expect(screen.getByTestId("inventory-loading"))
      .toHaveTextContent("true"));
    await waitFor(() => expect(resolveInventory).toBeDefined());
    resolveInventory?.(HttpResponse.json({
      configNonce: draft.draftRevision,
      targets: [],
    }));
    await waitFor(() => expect(screen.getByTestId("inventory-loading"))
      .toHaveTextContent("false"));
  });

  it("shows Valid only when every applicable target is current and passing", () => {
    const states: ConnectivityTargetState[] = [
      {
        target: {
          id: "source:source",
          kind: "source",
          refName: "source",
          label: "Source source",
          editPath: ["sourceClusters", "source"],
        },
        status: "valid",
      },
      {
        target: {
          id: "target:target",
          kind: "target",
          refName: "target",
          label: "Target target",
          editPath: ["targetClusters", "target"],
        },
        status: "not_applicable",
      },
    ];

    render(
      <ConnectivityDialog
        loading={false}
        onCheck={() => undefined}
        onClose={() => undefined}
        problem=""
        states={states}
      />,
    );

    expect(screen.getByLabelText("Overall connectivity"))
      .toHaveTextContent("Valid");
    expect(screen.getByLabelText("Overall connectivity"))
      .toHaveTextContent("All applicable configured connections passed");
  });

  it("marks a completed result stale when the draft nonce changes", async () => {
    let completedOperation: Record<string, unknown> | null = null;
    server.use(
      http.post(
        "*/api/v1/config/connectivity/inventory",
        async ({ request }) => {
          const body = await request.json() as { configNonce: string };
          return HttpResponse.json({
            configNonce: body.configNonce,
            targets: [{
              id: "source:source",
              kind: "source",
              refName: "source",
              label: "Source source",
              editPath: ["sourceClusters", "source"],
            }],
          });
        },
      ),
      http.post(
        "*/api/v1/config/connectivity/checks",
        async ({ request }) => {
          const body = await request.json() as {
            configNonce: string;
            targetIds: string[];
          };
          completedOperation = {
            id: "operation-connectivity-source",
            kind: "connectivity-check",
            label: "Check source:source",
            status: "succeeded",
            targetIds: body.targetIds,
            createdAt: "2026-09-16T13:00:00Z",
            updatedAt: "2026-09-16T13:00:01Z",
            message: "All checks passed.",
            result: {
              configNonce: body.configNonce,
              status: "valid",
              summary: "All checks passed.",
              checks: [{
                targetId: "source:source",
                kind: "source",
                refName: "source",
                label: "Source source",
                editPath: ["sourceClusters", "source"],
                checkedAt: "2026-09-16T13:00:01Z",
                status: "valid",
                summary: "Connected.",
                stages: [],
              }],
            },
          };
          return HttpResponse.json(completedOperation, { status: 202 });
        },
      ),
      http.get("*/api/v1/operations", () => HttpResponse.json({
        operations: completedOperation ? [completedOperation] : [],
      })),
    );
    const initial = createBrowserConfigDraft(document);
    const view = renderHarness(initial);

    await waitFor(() => expect(screen.getByTestId("status"))
      .toHaveTextContent("not_checked"));
    fireEvent.click(screen.getByRole("button", { name: "Check" }));
    await waitFor(() => expect(screen.getByTestId("status"))
      .toHaveTextContent("valid"));

    const changed = applyBrowserEditOperation(initial, {
      op: "set",
      path: ["sourceClusters", "source", "allowInsecure"],
      value: true,
    });
    view.rerender(
      <QueryClientProvider client={new QueryClient()}>
        <Harness draft={changed} />
      </QueryClientProvider>,
    );

    await waitFor(() => expect(screen.getByTestId("status"))
      .toHaveTextContent("stale"));
  });

  it("retains the newest completed result independently for every target", async () => {
    const draft = createBrowserConfigDraft(document);
    server.use(
      http.post(
        "*/api/v1/config/connectivity/inventory",
        () => HttpResponse.json({
          configNonce: draft.draftRevision,
          targets: [
            {
              id: "source:source",
              kind: "source",
              refName: "source",
              label: "Source source",
              editPath: ["sourceClusters", "source"],
            },
            {
              id: "target:target",
              kind: "target",
              refName: "target",
              label: "Target target",
              editPath: ["targetClusters", "target"],
            },
          ],
        }),
      ),
      http.get("*/api/v1/operations", () => HttpResponse.json({
        operations: [
          {
            id: "operation-source",
            kind: "connectivity-check",
            label: "Check source",
            status: "succeeded",
            targetIds: ["source:source"],
            createdAt: "2026-09-16T13:01:00Z",
            updatedAt: "2026-09-16T13:01:01Z",
            message: "Connected.",
            result: {
              configNonce: draft.draftRevision,
              status: "valid",
              summary: "Connected.",
              checks: [{
                targetId: "source:source",
                kind: "source",
                refName: "source",
                label: "Source source",
                editPath: ["sourceClusters", "source"],
                checkedAt: "2026-09-16T13:01:01Z",
                status: "valid",
                summary: "Connected.",
                stages: [],
              }],
            },
          },
          {
            id: "operation-target",
            kind: "connectivity-check",
            label: "Check target",
            status: "failed",
            targetIds: ["target:target"],
            createdAt: "2026-09-16T13:00:00Z",
            updatedAt: "2026-09-16T13:00:01Z",
            message: "Connection failed.",
            result: {
              configNonce: draft.draftRevision,
              status: "failed",
              summary: "Connection failed.",
              checks: [{
                targetId: "target:target",
                kind: "target",
                refName: "target",
                label: "Target target",
                editPath: ["targetClusters", "target"],
                checkedAt: "2026-09-16T13:00:01Z",
                status: "failed",
                summary: "Connection failed.",
                stages: [],
              }],
            },
          },
        ],
      })),
    );

    renderHarness(draft);

    await waitFor(() => expect(screen.getByTestId("status-source:source"))
      .toHaveTextContent("valid"));
    expect(screen.getByTestId("status-target:target"))
      .toHaveTextContent("failed");
  });
});
