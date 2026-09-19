import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import { manageSnapshot } from "../../test/fixtures";
import { server } from "../../test/server";
import { RuntimeStatusDock } from "./RuntimeStatusDock";


const capture = manageSnapshot.nodes[
  "resource:captureproxies:capture"
];
const replay = manageSnapshot.nodes[
  "resource:trafficreplays:replay"
];


function renderDock(
  activeNode: typeof capture | null = capture,
  standalone = false,
) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <RuntimeStatusDock
        activeNode={activeNode}
        nodes={manageSnapshot.nodes}
        standalone={standalone}
      />
    </QueryClientProvider>,
  );
}


describe("runtime status dock", () => {
  it("pins resource status across runtime navigation", async () => {
    const user = userEvent.setup();
    const first = renderDock();

    const captureStatus = await screen.findByRole("region", {
      name: "Runtime status for capture",
    });
    await user.click(within(captureStatus).getByRole("button", {
      name: "Pin runtime status for capture",
    }));
    await waitFor(() => {
      expect(globalThis.localStorage.getItem(
        "workflow-manage-runtime-status:pinned:v1",
      )).toContain(capture.id);
    });

    first.unmount();
    renderDock(replay);

    expect(await screen.findByRole("region", {
      name: "Runtime status for replay",
    })).toBeInTheDocument();
    expect(screen.getByRole("region", {
      name: "Runtime status for capture",
    })).toBeInTheDocument();
  });

  it("forces a refresh from the compact status header", async () => {
    const forces: string[] = [];
    server.use(http.get(
      "*/api/v1/nodes/:nodeId/runtime-status",
      ({ params, request }) => {
        forces.push(new URL(request.url).searchParams.get("force") ?? "");
        return HttpResponse.json({
          nodeId: params.nodeId,
          observedAt: "2026-09-20T14:00:00Z",
          pollAfterMs: null,
          sections: [],
        });
      },
    ));
    const user = userEvent.setup();
    renderDock();

    const status = await screen.findByRole("region", {
      name: "Runtime status for capture",
    });
    await waitFor(() => expect(forces).toEqual(["false"]));
    await user.click(within(status).getByRole("button", {
      name: "Refresh runtime status for capture",
    }));

    await waitFor(() => expect(forces).toEqual(["false", "true"]));
    const observation = within(status).getByRole("time").parentElement;
    const time = within(observation as HTMLElement).getByRole("time");
    const refresh = within(observation as HTMLElement).getByRole("button", {
      name: "Refresh runtime status for capture",
    });
    expect(time.compareDocumentPosition(refresh)
      & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("uses the same top-edge resize and reordering as curl panes", async () => {
    const user = userEvent.setup();
    globalThis.localStorage.setItem(
      "workflow-manage-runtime-status:pinned:v1",
      JSON.stringify([{
        id: `runtime-status:${replay.id}`,
        nodeId: replay.id,
        nodeLabel: replay.label,
        resourceType: replay.resourceType,
        pinned: true,
        expanded: true,
        height: 220,
      }]),
    );
    renderDock();

    const captureStatus = await screen.findByRole("region", {
      name: "Runtime status for capture",
    });
    const resize = within(captureStatus).getByRole("slider", {
      name: "Resize runtime status for capture",
    });
    expect(captureStatus.firstElementChild).toContainElement(resize);
    resize.focus();
    await user.keyboard("{ArrowUp}");
    expect(resize).toHaveAttribute("aria-valuenow", "240");

    fireEvent.keyDown(within(captureStatus).getByRole("button", {
      name: "Reorder runtime status for capture",
    }), {
      altKey: true,
      key: "ArrowDown",
    });
    const statuses = screen.getAllByRole("region", {
      name: /Runtime status for/,
    });
    expect(statuses[0]).toHaveAccessibleName("Runtime status for replay");
    expect(statuses[1]).toHaveAccessibleName("Runtime status for capture");
  });

  it("restores the current status workspace in standalone mode", async () => {
    const runtime = renderDock();
    await screen.findByRole("region", {
      name: "Runtime status for capture",
    });
    await waitFor(() => {
      expect(globalThis.localStorage.getItem(
        "workflow-manage-runtime-status:workspace:v1",
      )).toContain(capture.id);
    });
    runtime.unmount();

    renderDock(null, true);
    expect(await screen.findByRole("region", {
      name: "Runtime status for capture",
    })).toBeInTheDocument();
    expect(screen.queryByRole("button", {
      name: /Pin runtime status/,
    })).not.toBeInTheDocument();
    const status = screen.getByRole("region", {
      name: "Runtime status for capture",
    });
    const resize = within(status).getByRole("slider", {
      name: "Resize runtime status for capture",
    });
    expect(status.lastElementChild).toContainElement(resize);
    resize.focus();
    await userEvent.setup().keyboard("{ArrowDown}");
    expect(resize).toHaveAttribute("aria-valuenow", "240");
  });
});
