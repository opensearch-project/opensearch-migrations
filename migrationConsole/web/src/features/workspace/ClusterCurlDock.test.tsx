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

import { server } from "../../test/server";
import { ClusterCurlDock } from "./ClusterCurlDock";


const sourceCluster = {
  clusterName: "source",
  nodeId: "resource:sourceconfigs:source",
};
const targetCluster = {
  clusterName: "target",
  nodeId: "resource:targetconfigs:target",
};
const availableClusters = [sourceCluster, targetCluster];


function renderDock(
  activeCluster: typeof sourceCluster | null = sourceCluster,
  standalone = false,
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ClusterCurlDock
        activeCluster={activeCluster}
        availableClusters={availableClusters}
        standalone={standalone}
      />
    </QueryClientProvider>,
  );
}


function successfulCurlHandler(
  requests: Array<Record<string, unknown>>,
) {
  return http.post(
    "*/api/v1/nodes/:nodeId/cluster-curl",
    async ({ params, request }) => {
      requests.push({
        nodeId: params.nodeId,
        ...await request.json() as Record<string, unknown>,
      });
      return HttpResponse.json({
        nodeId: params.nodeId,
        clusterName: "source",
        observedAt: "2026-09-19T18:00:00Z",
        method: "GET",
        path: "/_cat/indices?pretty&v",
        success: true,
        output: "green open test",
        error: null,
      });
    },
  );
}


describe("cluster curl dock", () => {
  it("runs the indices request when a source or target opens", async () => {
    const requests: Array<Record<string, unknown>> = [];
    server.use(successfulCurlHandler(requests));

    renderDock();

    expect(screen.getByRole("textbox", {
      name: "Curl explorer 1 path",
    })).toHaveValue("/_cat/indices?pretty&v");
    expect(await screen.findByText("green open test")).toBeInTheDocument();
    expect(requests).toEqual([{
      body: null,
      headers: [],
      method: "GET",
      nodeId: "resource:sourceconfigs:source",
      path: "/_cat/indices?pretty&v",
    }]);
  });

  it("runs edited requests from refresh and compacts the controls", async () => {
    const requests: Array<Record<string, unknown>> = [];
    server.use(successfulCurlHandler(requests));
    const user = userEvent.setup();
    renderDock();

    await screen.findByText("green open test");
    const path = screen.getByRole("textbox", {
      name: "Curl explorer 1 path",
    });
    await user.clear(path);
    await user.type(path, "/_cluster/health");
    await user.click(screen.getByRole("button", {
      name: "Refresh curl explorer 1",
    }));

    expect(screen.queryByRole("textbox", {
      name: "Curl explorer 1 path",
    })).not.toBeInTheDocument();
    expect(screen.getByText("/_cluster/health")).toBeInTheDocument();
    await waitFor(() => expect(requests.at(-1)).toEqual({
      body: null,
      headers: [],
      method: "GET",
      nodeId: "resource:sourceconfigs:source",
      path: "/_cluster/health",
    }));
    await user.click(screen.getByRole("button", {
      name: "Edit curl explorer 1",
    }));
    expect(screen.getByRole("textbox", {
      name: "Curl explorer 1 path",
    })).toHaveValue("/_cluster/health");
  });

  it("adds a new explorer beside the edited request for the same cluster", async () => {
    server.use(successfulCurlHandler([]));
    const user = userEvent.setup();
    renderDock();

    const originalPath = screen.getByRole("textbox", {
      name: "Curl explorer 1 path",
    });
    await user.clear(originalPath);
    await user.type(originalPath, "/_cluster/health");
    await user.click(screen.getByRole("button", {
      name: "Add curl explorer after explorer 1",
    }));

    const explorers = screen.getAllByRole("region", {
      name: /Curl explorer \d/,
    });
    expect(explorers).toHaveLength(2);
    expect(within(explorers[0]).getByRole("textbox", {
      name: "Curl explorer 1 path",
    })).toHaveValue("/_cluster/health");
    expect(within(explorers[1]).getByRole("textbox", {
      name: "Curl explorer 2 path",
    })).toHaveValue("/_cat/indices?pretty&v");
    expect(within(explorers[1]).getByRole("combobox", {
      name: "Curl explorer 2 cluster",
    })).toHaveValue(sourceCluster.nodeId);
  });

  it("keeps pinned explorers visible throughout the runtime view", async () => {
    server.use(successfulCurlHandler([]));
    const user = userEvent.setup();
    const source = renderDock();

    await user.click(screen.getByRole("button", {
      name: "Pin curl explorer",
    }));

    await waitFor(() => {
      const stored = globalThis.localStorage.getItem(
        "workflow-manage-cluster-curl:pinned:v1",
      );
      expect(stored).not.toBeNull();
      expect(JSON.parse(stored ?? "[]")).toEqual([
        expect.objectContaining({
          clusterName: "source",
          nodeId: "resource:sourceconfigs:source",
          pinned: true,
        }),
      ]);
    });

    source.unmount();
    renderDock(null);

    expect(screen.getByRole("region", {
      name: "Curl explorer 1",
    })).toHaveTextContent("source");
    expect(screen.getByText("/_cat/indices?pretty&v")).toBeInTheDocument();
    await user.click(screen.getByRole("button", {
      name: "Edit curl explorer 1",
    }));
    expect(screen.getByRole("button", {
      name: "Add curl explorer after explorer 1",
    })).toBeInTheDocument();
  });

  it("keeps a launcher available after the last explorer closes", async () => {
    server.use(successfulCurlHandler([]));
    const user = userEvent.setup();
    renderDock(null);

    expect(screen.queryByRole("region", {
      name: /Curl explorer/,
    })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", {
      name: "Add curl explorer",
    }));

    expect(screen.getByRole("combobox", {
      name: "Curl explorer 1 cluster",
    })).toHaveValue(sourceCluster.nodeId);
    await user.click(screen.getByRole("button", {
      name: "Close curl explorer 1",
    }));
    expect(screen.getByRole("button", {
      name: "Add curl explorer",
    })).toBeInTheDocument();
  });

  it("allows each explorer to select its cluster", async () => {
    server.use(successfulCurlHandler([]));
    const user = userEvent.setup();
    renderDock();

    await user.selectOptions(screen.getByRole("combobox", {
      name: "Curl explorer 1 cluster",
    }), targetCluster.nodeId);

    expect(screen.getByRole("combobox", {
      name: "Curl explorer 1 cluster",
    })).toHaveValue(targetCluster.nodeId);
  });

  it("restores the current explorer set in the standalone workspace", async () => {
    server.use(successfulCurlHandler([]));
    const runtime = renderDock();

    await waitFor(() => {
      expect(globalThis.localStorage.getItem(
        "workflow-manage-cluster-curl:workspace:v1",
      )).not.toBeNull();
    });
    runtime.unmount();
    renderDock(null, true);

    expect(screen.getByRole("region", {
      name: "Curl explorer 1",
    })).toHaveTextContent("source");
    expect(screen.getByRole("button", {
      name: "Add curl explorer after explorer 1",
    })).toBeInTheDocument();
    expect(screen.queryByRole("button", {
      name: /Pin curl explorer/,
    })).not.toBeInTheDocument();
  });

  it("reorders explorers without an additional dependency", async () => {
    server.use(successfulCurlHandler([]));
    const user = userEvent.setup();
    renderDock();

    const firstPath = screen.getByRole("textbox", {
      name: "Curl explorer 1 path",
    });
    await user.clear(firstPath);
    await user.type(firstPath, "/first");
    await user.click(screen.getByRole("button", {
      name: "Add curl explorer after explorer 1",
    }));
    const newPath = screen.getByRole("textbox", {
      name: "Curl explorer 2 path",
    });
    await user.clear(newPath);
    await user.type(newPath, "/second");

    fireEvent.keyDown(screen.getByRole("button", {
      name: "Reorder curl explorer 1",
    }), {
      altKey: true,
      key: "ArrowDown",
    });

    const explorers = screen.getAllByRole("region", {
      name: /Curl explorer \d/,
    });
    expect(within(explorers[0]).getByRole("textbox", {
      name: "Curl explorer 1 path",
    })).toHaveValue("/second");
    expect(within(explorers[1]).getByRole("textbox", {
      name: "Curl explorer 2 path",
    })).toHaveValue("/first");
  });

  it("keeps request progress and refresh controls in the summary row", async () => {
    server.use(successfulCurlHandler([]));
    const user = userEvent.setup();
    renderDock();

    await screen.findByText("green open test");
    await user.click(screen.getByRole("button", {
      name: "Refresh curl explorer 1",
    }));

    const explorer = screen.getByRole("region", {
      name: "Curl explorer 1",
    });
    expect(within(explorer).getByText("source")).toBeInTheDocument();
    expect(within(explorer).getByText("GET")).toBeInTheDocument();
    expect(within(explorer).getByRole("button", {
      name: "Refresh curl explorer 1",
    })).toBeInTheDocument();
    expect(within(explorer).queryByText("Completed")).not.toBeInTheDocument();
    const observation = within(explorer).getByRole("time").parentElement;
    const time = within(observation as HTMLElement).getByRole("time");
    const refresh = within(observation as HTMLElement).getByRole("button", {
      name: "Refresh curl explorer 1",
    });
    expect(time.compareDocumentPosition(refresh)
      & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("resizes each explorer from its top divider", async () => {
    server.use(successfulCurlHandler([]));
    const user = userEvent.setup();
    renderDock();

    const explorer = screen.getByRole("region", {
      name: "Curl explorer 1",
    });
    const resizeHandle = screen.getByRole("slider", {
      name: "Resize curl explorer 1",
    });
    expect(explorer.firstElementChild).toContainElement(resizeHandle);
    expect(resizeHandle).toHaveAttribute("aria-valuenow", "220");

    resizeHandle.focus();
    await user.keyboard("{ArrowUp}");

    expect(resizeHandle).toHaveAttribute("aria-valuenow", "240");
    expect(explorer).toHaveStyle({ height: "240px" });
  });

  it("resizes standalone panes from the bottom edge toward the pane above", async () => {
    server.use(successfulCurlHandler([]));
    const runtime = renderDock();
    await waitFor(() => {
      expect(globalThis.localStorage.getItem(
        "workflow-manage-cluster-curl:workspace:v1",
      )).not.toBeNull();
    });
    runtime.unmount();
    const user = userEvent.setup();
    renderDock(null, true);

    const explorer = screen.getByRole("region", {
      name: "Curl explorer 1",
    });
    const resizeHandle = screen.getByRole("slider", {
      name: "Resize curl explorer 1",
    });
    expect(explorer.lastElementChild).toContainElement(resizeHandle);

    resizeHandle.focus();
    await user.keyboard("{ArrowDown}");

    expect(resizeHandle).toHaveAttribute("aria-valuenow", "240");
    expect(explorer).toHaveStyle({ height: "240px" });
  });

  it("expands output independently from editing the curl request", async () => {
    server.use(successfulCurlHandler([]));
    const user = userEvent.setup();
    renderDock();

    expect(await screen.findByText("green open test")).toBeInTheDocument();
    await user.click(screen.getByRole("button", {
      name: "Collapse curl explorer 1",
    }));
    expect(screen.queryByText("green open test")).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", {
      name: "Curl explorer 1 path",
    })).toBeInTheDocument();

    await user.click(screen.getByRole("button", {
      name: "Expand curl explorer 1",
    }));
    expect(screen.getByText("green open test")).toBeInTheDocument();
  });
});
