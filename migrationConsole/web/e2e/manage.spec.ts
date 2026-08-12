import { expect, test, type Page } from "@playwright/test";

import { configDraft, manageSnapshot } from "../src/test/fixtures";


async function mockManageApi(page: Page) {
  let snapshot = structuredClone(manageSnapshot);
  let draft = structuredClone(configDraft);
  await page.route("**/api/v1/system/health", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ status: "ok", apiVersion: "v1" }),
    });
  });
  await page.route("**/api/v1/manage/state", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify(snapshot),
    });
  });
  await page.route("**/api/v1/manage/events", async (route) => {
    await route.fulfill({
      contentType: "text/event-stream",
      headers: { "Cache-Control": "no-cache" },
      body: "retry: 60000\nevent: heartbeat\ndata: {}\n\n",
    });
  });
  await page.route("**/api/v1/config", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify(draft),
    });
  });
  await page.route("**/api/v1/config/operations", async (route) => {
    draft = {
      ...draft,
      dirty: true,
      draftRevision: `${draft.draftRevision}-next`,
    };
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify(draft),
    });
  });
  await page.route("**/api/v1/config/save", async (route) => {
    draft = {
      ...draft,
      dirty: false,
      baseRevision: draft.draftRevision,
    };
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify(draft),
    });
  });
  await page.route("**/api/v1/config/discard", async (route) => {
    draft = structuredClone(configDraft);
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify(draft),
    });
  });
  await page.route("**/api/v1/external-resources?*", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        nodeId: "edit:traffic.transform.configMap",
        draftRevision: draft.draftRevision,
        displayName: "Transform ConfigMap",
        rows: [{
          name: "transform-code",
          kind: "ConfigMap",
          group: "",
          version: "v1",
          keys: ["main.js", "settings.json"],
          status: "matching",
          message: "",
          current: true,
        }],
      }),
    });
  });
  await page.route("**/api/v1/external-resources/select", async (route) => {
    draft = {
      ...draft,
      dirty: true,
      draftRevision: `${draft.draftRevision}-selected`,
    };
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify(draft),
    });
  });
  await page.route("**/api/v1/external-resources/details?*", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        nodeId: "edit:traffic.transform.configMap",
        draftRevision: draft.draftRevision,
        displayName: "Transform ConfigMap",
        name: "transform-code",
        kind: "ConfigMap",
        resourceType: null,
        keys: ["main.js", "settings.json"],
        fieldValues: {
          name: "transform-code",
          contents: "export default () => true;",
        },
        hiddenFields: [],
        missing: false,
        message: null,
      }),
    });
  });
  await page.route("**/api/v1/external-resources/save", async (route) => {
    draft = {
      ...draft,
      dirty: true,
      draftRevision: `${draft.draftRevision}-external-save`,
    };
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        draft,
        name: "transform-code",
        kind: "ConfigMap",
        message: "ConfigMap updated: transform-code",
      }),
    });
  });
  return {
    insertCapture() {
      const insertedId = "resource:captureproxies:capture-next";
      snapshot = {
        ...snapshot,
        revision: "snapshot-with-insertion",
        nodes: {
          ...snapshot.nodes,
          "group:Live Traffic Migration:Capture": {
            ...snapshot.nodes["group:Live Traffic Migration:Capture"],
            revision: "capture-group-2",
            childIds: [
              ...snapshot.nodes["group:Live Traffic Migration:Capture"].childIds,
              insertedId,
            ],
          },
          [insertedId]: {
            ...snapshot.nodes["resource:captureproxies:capture"],
            id: insertedId,
            revision: "capture-next-1",
            label: "capture-next",
            parentId: "group:Live Traffic Migration:Capture",
          },
        },
      };
    },
  };
}


test("edits generic configuration and selects a ConfigMap key", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop interaction coverage");
  await mockManageApi(page);
  await page.goto("/");

  await page.getByRole("button", { name: "Edit capture" }).click();
  await expect(page.getByRole("heading", { name: "Edit capture" })).toBeVisible();
  const configTree = page.getByRole("tree", { name: "Configuration fields" });

  await page.getByRole("checkbox", { name: "Show optional fields" }).check();
  await configTree.getByRole("treeitem", { name: /Timeout: 30/ }).click();
  await expect(page.getByText("Generated value")).toBeVisible();
  await expect(page.getByText("runtime timeout")).toBeVisible();

  await configTree.getByRole("treeitem", {
    name: /ConfigMap: transform-code/,
  }).click();
  await page.getByRole("button", {
    name: "Browse Kubernetes resources",
  }).click();
  await expect(
    page.getByLabel("Keys in transform-code").getByText("settings.json"),
  ).toBeVisible();
  await page.getByRole("button", { name: "Inspect transform-code" }).click();
  await expect(page.getByText("export default () => true;")).toBeVisible();
  await page.getByRole("button", { name: "Back to resources" }).click();
  await page.getByRole("button", {
    name: "Use transform-code and key main.js",
  }).click();
  await expect(page.getByText("Unsaved changes")).toBeVisible();
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(page.getByText("Saved configuration")).toBeVisible();
});


test("keeps the resource overview visible during scoped editing", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop interaction coverage");
  await mockManageApi(page);
  await page.goto("/");

  const resources = page.getByRole("tree", { name: "Workflow resources" });
  await page.getByRole("button", { name: "Edit capture" }).click();

  await expect(resources).toBeVisible();
  await expect(page.getByRole("heading", { name: "Activity" })).toBeVisible();
  await expect(
    page.getByRole("tree", { name: "Configuration fields" }),
  ).toBeVisible();
});


test("guards browser back navigation without closing the editor", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "one browser is sufficient");
  await mockManageApi(page);
  await page.goto("/");

  await page.getByRole("button", { name: "Edit capture" }).click();
  await expect(page.getByRole("heading", { name: "Edit capture" })).toBeVisible();

  const dialogPromise = page.waitForEvent("dialog");
  await page.evaluate("window.history.back()");
  const dialog = await dialogPromise;
  expect(dialog.message()).toBe(
    "Leave Workflow Manage? Active operations will continue in the cluster.",
  );
  await dialog.dismiss();

  await expect(page.getByRole("heading", { name: "Edit capture" })).toBeVisible();
});


test("supports the read-only resource workflow", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop interaction coverage");
  const api = await mockManageApi(page);
  await page.goto("/");

  const tree = page.getByRole("tree", { name: "Workflow resources" });
  const capture = tree.getByRole("treeitem", { name: /^capture, Ready$/ });
  await expect(capture).toBeVisible();
  await capture.click();
  await expect(page.getByRole("heading", { name: "capture" })).toBeVisible();
  await expect(page.getByText("Load balancer is unavailable in this cluster"))
    .toBeVisible();
  await expect(page.getByRole("button", { name: "Edit capture" }))
    .toBeEnabled();

  await capture.focus();
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  const replay = tree.getByRole("treeitem", { name: /^replay, Running$/ });
  await expect(replay).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "replay" })).toBeVisible();

  api.insertCapture();
  await page.getByRole("button", { name: "Refresh state" }).click();
  const inserted = tree.getByRole(
    "treeitem",
    { name: /^capture-next, Ready$/ },
  );
  await expect(inserted).toBeVisible();
  await expect(inserted).toHaveCSS("animation-name", "row-insert");
});


test("keeps tree and activity reachable at narrow width", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "narrow", "narrow interaction coverage");
  await mockManageApi(page);
  await page.goto("/");

  await page.getByRole("button", { name: "Open resources" }).click();
  const tree = page.getByRole("tree", { name: "Workflow resources" });
  await expect(tree).toBeVisible();
  await tree.getByRole("treeitem", { name: /^replay, Running$/ }).click();
  await expect(page.getByRole("heading", { name: "replay" })).toBeVisible();

  const activity = page.getByRole("complementary");
  await expect(activity).toBeInViewport();
  await expect(activity.getByRole("heading", { name: "Activity" })).toBeVisible();
  await expect(activity.getByText("Deploy replay")).toBeVisible();

  const scrollWidth = await page.evaluate<number>(
    "document.documentElement.scrollWidth",
  );
  const clientWidth = await page.evaluate<number>(
    "document.documentElement.clientWidth",
  );
  const hasHorizontalOverflow = scrollWidth > clientWidth;
  expect(hasHorizontalOverflow).toBe(false);
});


test("keeps configuration editing usable at narrow width", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "narrow", "narrow interaction coverage");
  await mockManageApi(page);
  await page.goto("/");

  await page.getByRole("button", { name: "Edit capture" }).click();
  await expect(page.getByRole("heading", { name: "Edit capture" })).toBeVisible();
  await page.getByRole("checkbox", { name: "Show optional fields" }).check();
  const configTree = page.getByRole("tree", { name: "Configuration fields" });
  await configTree.getByRole("treeitem", { name: /Timeout: 30/ }).click();
  await expect(page.getByText("runtime timeout")).toBeVisible();

  const scrollWidth = await page.evaluate<number>(
    "document.documentElement.scrollWidth",
  );
  const clientWidth = await page.evaluate<number>(
    "document.documentElement.clientWidth",
  );
  expect(scrollWidth > clientWidth).toBe(false);
});


test("disables insertion motion when reduced motion is requested", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "one browser is sufficient");
  await page.emulateMedia({ reducedMotion: "reduce" });
  const api = await mockManageApi(page);
  await page.goto("/");

  api.insertCapture();
  await page.getByRole("button", { name: "Refresh state" }).click();
  const inserted = page.getByRole(
    "treeitem",
    { name: /^capture-next, Ready$/ },
  );
  await expect(inserted).toBeVisible();
  await expect(inserted).toHaveCSS("animation-name", "none");
});
