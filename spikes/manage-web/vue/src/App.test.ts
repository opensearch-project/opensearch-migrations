import { nextTick } from "vue";
import { mount, type VueWrapper } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App.vue";

describe("Vue patch-driven resource tree", () => {
  let wrapper: VueWrapper;

  beforeEach(() => {
    vi.useFakeTimers();
    wrapper = mount(App, { attachTo: document.body });
  });

  afterEach(() => {
    wrapper.unmount();
    document.body.innerHTML = "";
    vi.useRealTimers();
  });

  it("preserves exact DOM identity and focus during a status update", async () => {
    const before = row("resource-proxy");
    before.focus();
    expect(document.activeElement).toBe(before);

    await wrapper.get('[data-testid="refresh-control"]').trigger("click");
    await nextTick();

    expect(row("resource-proxy")).toBe(before);
    expect(document.activeElement).toBe(before);
    expect(before.getAttribute("aria-selected")).toBe("true");
  });

  it("inserts edit rows without replacing or defocusing the selected row", async () => {
    const before = row("resource-proxy");
    before.focus();

    await wrapper.get('[data-testid="edit-mode-control"]').trigger("click");
    vi.advanceTimersByTime(500);
    await nextTick();

    expect(row("resource-proxy")).toBe(before);
    expect(document.activeElement).toBe(before);
    expect(row("config-proxy").dataset.inserted).toBe("true");
    expect(row("config-listen-port")).toBeTruthy();
  });

  it("adds a transform branch while preserving an unrelated focused row", async () => {
    await wrapper.get('[data-testid="edit-mode-control"]').trigger("click");
    vi.advanceTimersByTime(500);
    await nextTick();

    const before = row("resource-proxy");
    before.focus();
    const configurationTab = wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text().includes("Configuration"));
    if (!configurationTab) {
      throw new Error("Configuration tab was not rendered");
    }
    await configurationTab.trigger("click");
    await wrapper
      .get('[data-testid="add-transform-control"]')
      .trigger("click");
    vi.advanceTimersByTime(200);
    await nextTick();

    expect(row("resource-proxy")).toBe(before);
    expect(document.activeElement).toBe(before);
    expect(row("config-transform").dataset.inserted).toBe("true");
    expect(row("config-transform-file")).toBeTruthy();
  });

  function row(nodeId: string): HTMLElement {
    const element = wrapper
      .get(`[data-node-id="${nodeId}"]`)
      .element;
    if (!(element instanceof HTMLElement)) {
      throw new Error(`Expected HTML row ${nodeId}`);
    }
    return element;
  }
});
