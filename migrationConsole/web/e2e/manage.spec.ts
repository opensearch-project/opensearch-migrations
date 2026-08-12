import { expect, test, type Page } from "@playwright/test";

import { manageSnapshot } from "../src/test/fixtures";


async function mockManageApi(page: Page) {
  let snapshot = structuredClone(manageSnapshot);
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
    .toBeDisabled();

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
