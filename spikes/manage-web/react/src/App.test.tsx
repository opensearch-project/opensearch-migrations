import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { App } from "./App";

describe("patch-driven resource tree", () => {
  it("preserves the focused DOM row and expansion state during a status update", () => {
    render(<App />);

    const proxyRow = screen.getByRole("treeitem", {
      name: /capture-proxy/i,
    });
    const captureGroup = screen.getByRole("treeitem", {
      name: /^Capture, warning$/i,
    });
    proxyRow.focus();

    fireEvent.click(
      screen.getByRole("button", { name: "Simulate status refresh" }),
    );

    expect(
      screen.getByRole("treeitem", { name: /capture-proxy/i }),
    ).toBe(proxyRow);
    expect(proxyRow).toHaveFocus();
    expect(captureGroup).toHaveAttribute("aria-expanded", "true");
  });

  it("inserts edit rows without replacing or defocusing the selected row", async () => {
    render(<App />);

    const proxyRow = screen.getByRole("treeitem", {
      name: /capture-proxy/i,
    });
    proxyRow.focus();

    fireEvent.click(
      screen.getByRole("button", { name: "Edit configuration" }),
    );

    const insertedRow = await screen.findByRole(
      "treeitem",
      { name: /Proxy configuration, changed/i },
      { timeout: 1_500 },
    );

    expect(
      screen.getByRole("treeitem", { name: /capture-proxy/i }),
    ).toBe(proxyRow);
    expect(proxyRow).toHaveFocus();
    expect(proxyRow).toHaveAttribute("aria-selected", "true");
    expect(insertedRow).toHaveAttribute("data-inserted", "true");
  });

  it("adds a transform incrementally while retaining the unrelated focused row", async () => {
    render(<App />);

    fireEvent.click(
      screen.getByRole("button", { name: "Edit configuration" }),
    );
    await screen.findByRole(
      "treeitem",
      { name: /Replayer configuration, changed/i },
      { timeout: 1_500 },
    );

    const proxyRow = screen.getByRole("treeitem", {
      name: /capture-proxy/i,
    });
    proxyRow.focus();
    fireEvent.click(screen.getByRole("tab", { name: "Configuration" }));
    const addTransform = screen.getByRole("button", { name: "Add transform" });
    await waitFor(() => expect(addTransform).toBeEnabled());
    fireEvent.click(addTransform);

    const transformRow = await screen.findByRole("treeitem", {
      name: /^Transform, changed$/i,
    });

    expect(
      screen.getByRole("treeitem", { name: /capture-proxy/i }),
    ).toBe(proxyRow);
    expect(proxyRow).toHaveFocus();
    expect(transformRow).toHaveAttribute("data-inserted", "true");

    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Transform added" }),
      ).toBeDisabled(),
    );
  });
});
