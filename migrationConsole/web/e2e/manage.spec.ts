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
    const request = route.request().postDataJSON() as {
      operation?: { op?: string; path?: string[]; value?: unknown };
    };
    draft = {
      ...draft,
      dirty: true,
      draftRevision: `${draft.draftRevision}-next`,
    };
    if (
      request.operation?.op === "set"
      && request.operation.path?.join(".")
        === "sourceClusters.legacy.authConfig"
      && request.operation.value === "sigv4"
    ) {
      const sourceClusters = draft.editState.nodes.find(
        (node) => node.id === "edit:sourceClusters",
      );
      const legacy = sourceClusters?.children.find(
        (node) => node.id === "edit:sourceClusters.legacy",
      );
      const auth = legacy?.children.find(
        (node) => node.id === "edit:sourceClusters.legacy.authConfig",
      );
      if (auth) {
        auth.value = "sigv4";
        auth.label = "Authentication: < sigv4 >";
        auth.children = [{
          id: "edit:sourceClusters.legacy.authConfig.sigv4.region",
          path: [
            "sourceClusters",
            "legacy",
            "authConfig",
            "sigv4",
            "region",
          ],
          label: "Signing region: us-east-1",
          value: "us-east-1",
          valueAuthored: true,
          valueKind: "scalar",
          valueType: "string",
          presence: "required",
          required: true,
          status: "ok",
          statusCounts: {
            errors: 0,
            warnings: 0,
            required: 0,
            gated: 0,
            blocked: 0,
          },
          diagnostics: [],
          children: [],
        }];
      }
    }
    if (
      request.operation?.op === "removeConfig"
      && request.operation.path?.join(".") === "sourceClusters.legacy"
    ) {
      const sourceClusters = draft.editState.nodes.find(
        (node) => node.id === "edit:sourceClusters",
      );
      if (sourceClusters) {
        sourceClusters.children = sourceClusters.children.filter(
          (node) => node.id !== "edit:sourceClusters.legacy",
        );
      }
    }
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify(draft),
    });
  });
  await page.route("**/api/v1/config/removal-impact", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        targetPath: ["sourceClusters", "legacy"],
        targetLabel: "legacy",
        affected: [{
          path: ["traffic", "proxies", "capture"],
          fieldPath: ["traffic", "proxies", "capture", "source"],
          reason: "source=legacy",
        }, {
          path: ["traffic", "replayers", "replay"],
          fieldPath: [
            "traffic",
            "replayers",
            "replay",
            "fromCapturedTraffic",
          ],
          reason: "fromCapturedTraffic=capture",
        }],
      }),
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
    makeCaptureSource() {
      const capture = snapshot.nodes["resource:captureproxies:capture"];
      capture.label = "legacy";
      capture.description = "sourceconfigs/legacy";
      capture.valueSummary = "Deployed";
      capture.resourcePlural = "sourceconfigs";
      capture.resourceName = "legacy";
      capture.capabilities = capture.capabilities.map((capability) => (
        capability.kind === "edit"
          ? {
            ...capability,
            editTargetId: "edit:sourceClusters.legacy",
            label: "Edit legacy",
          }
          : capability
      ));
    },
    setCaptureEditTarget(targetId: string) {
      const capture = snapshot.nodes["resource:captureproxies:capture"];
      capture.capabilities = capture.capabilities.map((capability) => (
        capability.kind === "edit"
          ? { ...capability, editTargetId: targetId }
          : capability
      ));
    },
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

  await page.getByRole("button", { name: "Edit configuration" }).click();
  await expect(page.getByRole("heading", { name: "Edit capture" })).toBeVisible();
  const configTree = page.getByRole("table", { name: "Configuration fields" });
  const endpoint = configTree.getByRole("textbox", { name: "Endpoint" });
  await endpoint.fill("https://saved.example.com:9200");
  await expect(
    configTree.getByRole("button", { name: "Apply" }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "Save configuration" }).click();
  await expect(page.getByText("Saved configuration")).toBeVisible();

  await page.getByRole("checkbox", { name: "Show optional fields" }).check();
  await configTree.getByRole("row", { name: /Timeout/ }).click();
  await expect(page.getByText("Generated value")).toBeVisible();
  await expect(page.getByText("runtime timeout")).toBeVisible();

  await configTree.getByRole("row", {
    name: /ConfigMap/,
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
  await page.getByRole("button", { name: "Save configuration" }).click();
  await expect(page.getByText("Saved configuration")).toBeVisible();
});


test("updates variant fields in place beneath their selector", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop interaction coverage");
  await mockManageApi(page);
  await page.goto("/");

  await page.getByRole("button", { name: "Edit configuration" }).click();
  await expect(page.getByRole("button", { name: "Add resource" })).toBeVisible();
  const config = page.getByRole("table", { name: "Configuration fields" });
  const auth = config.getByRole("row", { name: /Authentication/ });
  await auth.getByRole("combobox", { name: "Authentication" })
    .selectOption("sigv4");

  const region = config.getByRole("row", { name: /Signing region/ });
  await expect(region).toBeVisible();
  await expect(
    auth.locator("xpath=following-sibling::tr[1]"),
  ).toContainText("Signing region");
  await expect(region).toBeInViewport();
});


test("pins ancestor rows while scrolling nested configuration", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop scrolling coverage");
  await mockManageApi(page);
  await page.goto("/");

  await page.getByRole("button", { name: "Edit configuration" }).click();
  const config = page.getByRole("table", { name: "Configuration fields" });
  await page.locator(".config-table-panel").hover();
  await page.mouse.wheel(0, 220);

  const context = page.getByRole("navigation", {
    name: "Current configuration path",
  });
  await expect(
    context.getByRole("button", { name: /^Source clusters 1 setting/ }),
  ).toBeVisible();
  await expect(
    context.getByRole("button", { name: /^legacy 6 settings/ }),
  ).toBeVisible();
  await context.getByRole("button", { name: /Source clusters/ }).click();
  await expect(
    config.getByRole("row", { name: /Source clusters/ }),
  ).toBeInViewport();
});


test("transitions scoped parents before their full row scrolls away", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop scrolling coverage");
  const api = await mockManageApi(page);
  api.setCaptureEditTarget("edit:sourceClusters.legacy.endpoint");
  await page.goto("/");

  await page.getByRole("button", { name: "Edit configuration" }).click();
  await expect(
    page.getByRole("button", { name: "Add resource" }),
  ).toBeVisible();
  await page.getByRole("checkbox", { name: "Show optional fields" }).check();
  await page.getByRole("checkbox", { name: "Show expert fields" }).check();

  const config = page.getByRole("table", { name: "Configuration fields" });
  const authentication = config.getByRole("row", { name: /Authentication/ });
  const columnHeader = config.getByRole("columnheader", { name: "Setting" });
  const panel = page.locator(".config-table-panel");
  await panel.hover();
  const context = page.getByRole("navigation", {
    name: "Current configuration path",
  });
  const pinnedAuthentication = context.getByRole("button", {
    name: /^Authentication/,
  });
  for (
    let attempt = 0;
    attempt < 20 && await pinnedAuthentication.count() === 0;
    attempt += 1
  ) {
    await page.mouse.wheel(0, 20);
  }
  await expect(
    pinnedAuthentication,
  ).toBeVisible();
  await expect(
    context.getByRole("button", { name: /^legacy/ }),
  ).toHaveCount(0);

  const authenticationBox = await authentication.boundingBox();
  const headerBox = await columnHeader.boundingBox();
  expect(authenticationBox).not.toBeNull();
  expect(headerBox).not.toBeNull();
  expect(authenticationBox!.y + authenticationBox!.height)
    .toBeGreaterThan(headerBox!.y + headerBox!.height);
});


test("keeps the resource overview visible during scoped editing", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop interaction coverage");
  await mockManageApi(page);
  await page.goto("/");

  const resources = page.getByRole("tree", { name: "Workflow resources" });
  await page.getByRole("button", { name: "Edit configuration" }).click();

  await expect(resources).toBeVisible();
  await expect(page.getByRole("heading", { name: "Activity" })).toBeVisible();
  await expect(
    page.getByRole("table", { name: "Configuration fields" }),
  ).toBeVisible();
});


test("keeps a removed resource in context for the edit session", async ({ page }, testInfo) => {
  const api = await mockManageApi(page);
  api.makeCaptureSource();
  await page.goto("/");

  await page.getByRole("button", { name: "Edit configuration" }).click();
  await expect(page.getByRole("button", { name: "Revert unsaved changes" }))
    .toBeVisible();
  await expect(page.getByRole("button", { name: "Save configuration" }))
    .toBeVisible();
  await expect(page.getByRole("button", { name: "Save and submit" }))
    .toBeVisible();
  await expect(page.getByRole("button", { name: "Exit editing" }))
    .toBeVisible();

  await page.getByRole("button", { name: "Remove legacy" }).click();
  const dialog = page.getByRole("dialog", { name: "Remove legacy?" });
  await expect(dialog.getByText("traffic.proxies.capture")).toBeVisible();
  await expect(dialog.getByText("traffic.replayers.replay")).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("removal-impact.png"),
    fullPage: true,
  });
  await dialog.getByRole("button", { name: "Confirm removal" }).click();

  if (testInfo.project.name === "narrow") {
    await page.getByRole("button", { name: "Open resources" }).click();
  }
  const removed = page.getByRole("treeitem", {
    name: /^legacy, Marked for removal$/,
  });
  await expect(removed).toBeVisible();
  await expect(removed).toHaveAttribute("aria-selected", "true");
  if (testInfo.project.name === "narrow") {
    await page.getByRole("button", { name: "Close resources" }).click();
  }
  await expect(page.getByText(
    "This legacy is marked for removal from the configuration.",
  )).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("removed-resource.png"),
    fullPage: true,
  });
});


test("guards browser back navigation without closing the editor", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "one browser is sufficient");
  await mockManageApi(page);
  await page.goto("/");

  await page.getByRole("button", { name: "Edit configuration" }).click();
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
  await expect(page.getByRole("button", { name: "Edit configuration" }))
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

  await page.getByRole("button", { name: "Edit configuration" }).click();
  await expect(page.getByRole("heading", { name: "Edit capture" })).toBeVisible();
  await page.getByRole("checkbox", { name: "Show optional fields" }).check();
  const configTree = page.getByRole("table", { name: "Configuration fields" });
  await configTree.getByRole("row", { name: /Timeout/ }).click();
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
