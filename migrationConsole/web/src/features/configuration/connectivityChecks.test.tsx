import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { server } from "../../test/server";
import { manageSnapshot } from "../../test/fixtures";
import type { ConnectivityTarget } from "../../api/client";
import {
  applyBrowserEditOperation,
  createBrowserConfigDraft,
  type BrowserConfigDraft,
} from "./browserDraft";
import {
  ConnectivityDialog,
  runtimeConnectivityTargets,
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


function Harness({
  draft,
  provisionalTargets = [],
}: Readonly<{
  draft: BrowserConfigDraft;
  provisionalTargets?: ConnectivityTarget[];
}>) {
  const connectivity = useConnectivityChecks(
    draft,
    ["sourceClusters", "source"],
    0,
    true,
    provisionalTargets,
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


function renderHarness(
  draft: BrowserConfigDraft,
  provisionalTargets: ConnectivityTarget[] = [],
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <Harness draft={draft} provisionalTargets={provisionalTargets} />
    </QueryClientProvider>,
  );
}


describe("configuration connectivity checks", () => {
  it("derives stable runtime targets from resource edit capabilities", () => {
    const source = structuredClone(
      manageSnapshot.nodes["resource:captureproxies:capture"],
    );
    source.label = "legacy";
    source.resourceType = "Source cluster";
    source.capabilities = [{
      kind: "edit",
      editTargetId: "edit:sourceClusters.legacy",
      label: "Edit legacy",
    }];
    const repository = structuredClone(source);
    repository.label = "archive";
    repository.resourceType = "Snapshot repository";
    repository.capabilities = [{
      kind: "edit",
      editTargetId: (
        "edit:sourceClusters.legacy.snapshotInfo.repos.archive"
      ),
      label: "Edit archive",
    }];

    expect(runtimeConnectivityTargets([source, repository])).toEqual([
      {
        editPath: [
          "sourceClusters",
          "legacy",
          "snapshotInfo",
          "repos",
          "archive",
        ],
        id: "repository:legacy:archive",
        kind: "repository",
        label: "Repository archive",
        refName: "archive",
      },
      {
        editPath: ["sourceClusters", "legacy"],
        id: "source:legacy",
        kind: "source",
        label: "Source legacy",
        refName: "legacy",
      },
    ]);
  });

  it("shows runtime targets as pending while inventory is loading", async () => {
    let resolveInventory: ((value: Response) => void) | undefined;
    const draft = createBrowserConfigDraft(document);
    const target: ConnectivityTarget = {
      id: "source:source",
      kind: "source",
      refName: "source",
      label: "Source source",
      editPath: ["sourceClusters", "source"],
    };
    server.use(
      http.post(
        "*/api/v1/config/connectivity/inventory",
        () => new Promise<Response>((resolve) => {
          resolveInventory = resolve;
        }),
      ),
    );

    renderHarness(draft, [target]);

    expect(screen.getByTestId("status")).toHaveTextContent("pending");
    expect(screen.getByTestId("status-source:source"))
      .toHaveTextContent("pending");
    await waitFor(() => expect(resolveInventory).toBeDefined());
    resolveInventory?.(HttpResponse.json({
      configNonce: draft.draftRevision,
      targets: [target],
    }));
  });

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

  it("automatically starts checks for the current unverified targets", async () => {
    const draft = createBrowserConfigDraft(document);
    let starts = 0;
    let queuedOperation: Record<string, unknown> | null = null;
    let resolveStart: ((response: Response) => void) | undefined;
    server.use(
      http.post(
        "*/api/v1/config/connectivity/inventory",
        () => HttpResponse.json({
          configNonce: draft.draftRevision,
          targets: [{
            id: "source:source",
            kind: "source",
            refName: "source",
            label: "Source source",
            editPath: ["sourceClusters", "source"],
          }],
        }),
      ),
      http.post(
        "*/api/v1/config/connectivity/checks",
        async ({ request }) => {
          starts += 1;
          const body = await request.json() as {
            configNonce: string;
            targetIds: string[];
          };
          queuedOperation = {
            id: "operation-connectivity-source",
            kind: "connectivity-check",
            label: "Check source",
            status: "queued",
            targetIds: body.targetIds,
            createdAt: "2026-09-18T00:00:00Z",
            updatedAt: "2026-09-18T00:00:00Z",
            message: "Queued",
            result: {},
          };
          return new Promise<Response>((resolve) => {
            resolveStart = resolve;
          });
        },
      ),
      http.get("*/api/v1/operations", () => HttpResponse.json({
        operations: queuedOperation ? [queuedOperation] : [],
      })),
    );

    renderHarness(draft);

    await waitFor(() => expect(screen.getByTestId("status"))
      .toHaveTextContent("checking"));
    await waitFor(() => expect(starts).toBe(1));
    expect(resolveStart).toBeDefined();
    resolveStart?.(HttpResponse.json(queuedOperation, { status: 202 }));
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
      .toHaveTextContent("pending"));
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
