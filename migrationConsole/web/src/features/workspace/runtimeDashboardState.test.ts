import { describe, expect, it } from "vitest";

import {
  CURL_WORKSPACE_STORAGE_KEY,
  hydrateRuntimeDashboardFromHash,
  PINNED_RUNTIME_STATUS_STORAGE_KEY,
  runtimeDashboardUrl,
  RUNTIME_STATUS_WORKSPACE_STORAGE_KEY,
} from "./runtimeDashboardState";


describe("runtime dashboard URL state", () => {
  it("round trips the complete pane layout through a copyable fragment", () => {
    const curl = [{
      id: "curl-1",
      clusterName: "source",
      nodeId: "resource:sourceconfigs:source",
      method: "GET",
      path: "/_cat/indices?pretty&v",
      body: "",
      pinned: true,
      autoRefresh: false,
      expanded: false,
      height: 260,
    }];
    const statuses = [{
      id: "runtime-status:resource:datasnapshots:snapshot",
      nodeId: "resource:datasnapshots:snapshot",
      nodeLabel: "snapshot",
      resourceType: "Data snapshot",
      pinned: true,
      expanded: true,
      height: 320,
    }];
    globalThis.localStorage.setItem(
      CURL_WORKSPACE_STORAGE_KEY,
      JSON.stringify(curl),
    );
    globalThis.localStorage.setItem(
      RUNTIME_STATUS_WORKSPACE_STORAGE_KEY,
      JSON.stringify(statuses),
    );

    const url = new URL(runtimeDashboardUrl());
    expect(url.pathname).toBe("/runtime-dashboard");
    expect(url.hash).toMatch(/^#v1=/);

    globalThis.localStorage.clear();
    globalThis.history.replaceState({}, "", `${url.pathname}${url.hash}`);
    hydrateRuntimeDashboardFromHash();

    expect(JSON.parse(
      globalThis.localStorage.getItem(CURL_WORKSPACE_STORAGE_KEY) ?? "[]",
    )).toEqual(curl);
    expect(JSON.parse(
      globalThis.localStorage.getItem(
        RUNTIME_STATUS_WORKSPACE_STORAGE_KEY,
      ) ?? "[]",
    )).toEqual(statuses);
    expect(JSON.parse(
      globalThis.localStorage.getItem(
        PINNED_RUNTIME_STATUS_STORAGE_KEY,
      ) ?? "[]",
    )).toEqual(statuses);
    globalThis.history.replaceState({}, "", "/");
  });
});
