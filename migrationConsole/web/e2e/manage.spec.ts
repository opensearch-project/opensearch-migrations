import { expect, test, type Locator, type Page } from "@playwright/test";

import { manageSnapshot } from "../src/test/fixtures";


interface BrowserAnimation {
  id: string;
  playState: string;
  effect: {
    getKeyframes: () => Array<Record<string, unknown>>;
    getTiming: () => { fill: string };
  } | null;
}


interface BrowserAnimatedElement {
  dataset: { nodeId?: string };
  getAnimations: () => BrowserAnimation[];
}


interface BrowserPositionedElement {
  getBoundingClientRect: () => { top: number };
}


interface BrowserMotionWindow {
  __treeMotionSamples?: number[];
  performance: { now: () => number };
  requestAnimationFrame: (callback: () => void) => number;
}


interface BrowserControlledMotionWindow extends BrowserMotionWindow {
  __nativeTreeRequestAnimationFrame?: (callback: () => void) => number;
  __queuedTreeAnimationFrames?: Array<() => void>;
}


interface BrowserButtonElement {
  click: () => void;
  ownerDocument: {
    querySelector: (selector: string) => BrowserAnimatedElement | null;
  };
}


function animatedNodeIds(elements: unknown[]): string[] {
  const nodeIds: string[] = [];
  for (const row of elements as BrowserAnimatedElement[]) {
    const nodeId = row.dataset.nodeId;
    if (!nodeId) continue;
    for (const animation of row.getAnimations()) {
      if (animation.id === "tree-layout-transition") {
        nodeIds.push(nodeId);
        break;
      }
    }
  }
  return nodeIds;
}


function treeTransitionDetails(elements: unknown[]) {
  const details: Array<{
    fill: string | undefined;
    usesOpacity: boolean;
    yDistance: number;
  }> = [];
  for (const row of elements as BrowserAnimatedElement[]) {
    for (const animation of row.getAnimations()) {
      if (animation.id !== "tree-layout-transition") continue;
      const keyframes = animation.effect?.getKeyframes() ?? [];
      const transform = keyframes[0]?.["transform"];
      const match = typeof transform === "string"
        ? /^translate\([^,]+,\s*(-?[\d.]+)px\)$/.exec(transform)
        : null;
      details.push({
        fill: animation.effect?.getTiming().fill,
        usesOpacity: keyframes.some(
          (keyframe) => keyframe["opacity"] !== undefined,
        ),
        yDistance: match?.[1] ? Math.abs(Number(match[1])) : 0,
      });
    }
  }
  return details;
}


function treeLayoutAnimationCount(elements: unknown[]): number {
  let count = 0;
  for (const row of elements as BrowserAnimatedElement[]) {
    for (const animation of row.getAnimations()) {
      if (animation.id === "tree-layout-transition") count += 1;
    }
  }
  return count;
}


async function beginTreeMotionSampling(row: Locator) {
  await row.evaluate((element: BrowserPositionedElement) => {
    const motion = globalThis as unknown as BrowserMotionWindow;
    motion.__treeMotionSamples = [];
    const startedAt = motion.performance.now();
    const sample = () => {
      motion.__treeMotionSamples?.push(
        element.getBoundingClientRect().top,
      );
      if (motion.performance.now() - startedAt < 700) {
        motion.requestAnimationFrame(sample);
      }
    };
    motion.requestAnimationFrame(sample);
  });
}


async function readTreeMotionSamples(page: Page) {
  await page.waitForTimeout(725);
  return page.evaluate(() => (
    (globalThis as typeof globalThis & {
      __treeMotionSamples?: number[];
    }).__treeMotionSamples ?? []
  ));
}


function largestFrameDelta(samples: number[]) {
  return samples.slice(1).reduce(
    (largest, position, index) => Math.max(
      largest,
      Math.abs(position - samples[index]),
    ),
    0,
  );
}


function configurationYaml(secretName = "") {
  return `sourceClusters:
  legacy:
    endpoint: https://legacy.example.com:9200
    version: ES 7.10
    allowInsecure: false
    authConfig:
      basic:
        secretName: ${JSON.stringify(secretName)}
targetClusters:
  target:
    endpoint: https://target.example.com:9200
snapshotMigrationConfigs: []
traffic:
  kafkaClusters:
    default:
      autoCreate: {}
  proxies:
    capture:
      source: legacy
      kafka: default
      proxyConfig:
        listenPort: 9201
  replayers:
    replay:
      fromCapturedTraffic: capture
      toTarget: target
`;
}


async function mockManageApi(page: Page) {
  let snapshot = structuredClone(manageSnapshot);
  let rawYaml = configurationYaml();
  let persistedRevision = "config-base-1";
  let operations: Array<Record<string, unknown>> = [];
  const operation = (kind: string, label: string, message: string) => ({
    id: `operation-${kind}-${operations.length + 1}`,
    kind,
    label,
    status: "waiting",
    targetIds: ["resource:captureproxies:capture"],
    createdAt: "2026-08-13T13:00:00Z",
    updatedAt: "2026-08-13T13:00:01Z",
    message,
    detail: null,
    result: {},
  });
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
  await page.route("**/api/v1/operations", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ operations }),
    });
  });
  await page.route("**/api/v1/operations/events", async (route) => {
    await route.fulfill({
      contentType: "text/event-stream",
      headers: { "Cache-Control": "no-cache" },
      body: "retry: 60000\nevent: heartbeat\ndata: {}\n\n",
    });
  });
  await page.route("**/api/v1/config/document", async (route) => {
    if (route.request().method() === "PUT") {
      const request = route.request().postDataJSON() as {
        rawYaml: string;
      };
      rawYaml = request.rawYaml;
      persistedRevision = `${persistedRevision}-saved`;
    }
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        modelVersion: "1",
        persistedRevision,
        rawYaml,
      }),
    });
  });
  await page.route("**/api/v1/config/review", async (route) => {
    const valid = !rawYaml.includes('secretName: ""');
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        persistedRevision,
        valid,
        validationMessages: valid ? [] : ["Credentials secret is required."],
        changes: [{
          resourceId: "resource:captureproxies:capture",
          resourceLabel: "capture",
          path: "serviceType",
          label: "Service type",
          kind: "field",
        }],
      }),
    });
  });
  await page.route("**/api/v1/config/preflight", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        checkedResources: 0,
        allowed: true,
        issues: [],
      }),
    });
  });
  await page.route("**/api/v1/config/submit", async (route) => {
    const accepted = operation(
      "submit",
      "Submit workflow configuration",
      "Workflow accepted; waiting for refreshed cluster state",
    );
    operations = [accepted, ...operations];
    snapshot.workflow = null;
    snapshot.problems = [{
      source: "argo",
      message: (
        '404: workflows.argoproj.io "migration-workflow" not found'
      ),
      retryable: true,
    }];
    await route.fulfill({
      status: 202,
      contentType: "application/json",
      body: JSON.stringify(accepted),
    });
  });
  await page.route("**/api/v1/outputs?*", async (route) => {
    const targetId = new URL(route.request().url()).searchParams.get(
      "targetId",
    );
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        targetId,
        resourceId: "resource:captureproxies:capture",
        outputs: [{
          id: "managed-output:evaluate",
          targetId: (
            "output:snapshotmigrations:migration-0:metadataEvaluate"
          ),
          resourceId: "resource:captureproxies:capture",
          resourcePlural: "snapshotmigrations",
          resourceName: "migration-0",
          outputName: "metadataEvaluate",
          stage: "Evaluate",
          stageOrder: 0,
          attempt: "migration-1",
          timestamp: "2026-08-13T12:00:00Z",
          source: "s3://outputs/evaluate.json",
          contentType: "application/json",
        }, {
          id: "managed-output:migrate",
          targetId: (
            "output:snapshotmigrations:migration-0:metadataMigrate"
          ),
          resourceId: "resource:captureproxies:capture",
          resourcePlural: "snapshotmigrations",
          resourceName: "migration-0",
          outputName: "metadataMigrate",
          stage: "Migrate",
          stageOrder: 1,
          attempt: "migration-1",
          timestamp: "2026-08-13T12:05:00Z",
          source: "s3://outputs/migrate.json",
          contentType: "application/json",
        }],
      }),
    });
  });
  await page.route("**/api/v1/outputs/content?*", async (route) => {
    const outputId = new URL(route.request().url()).searchParams.get(
      "outputId",
    );
    const migrate = outputId?.includes("migrate");
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        descriptor: {
          id: outputId,
          targetId: migrate
            ? "output:snapshotmigrations:migration-0:metadataMigrate"
            : "output:snapshotmigrations:migration-0:metadataEvaluate",
          resourceId: "resource:captureproxies:capture",
          resourcePlural: "snapshotmigrations",
          resourceName: "migration-0",
          outputName: migrate ? "metadataMigrate" : "metadataEvaluate",
          stage: migrate ? "Migrate" : "Evaluate",
          stageOrder: migrate ? 1 : 0,
          attempt: "migration-1",
          timestamp: migrate
            ? "2026-08-13T12:05:00Z"
            : "2026-08-13T12:00:00Z",
          source: migrate
            ? "s3://outputs/migrate.json"
            : "s3://outputs/evaluate.json",
          contentType: "application/json",
        },
        content: migrate
          ? "{\"stage\":\"migrate\"}"
          : "{\"stage\":\"evaluate\"}",
        inline: true,
        size: 20,
        message: null,
      }),
    });
  });
  await page.route("**/api/v1/approval-gates", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        workflowName: "migration",
        gates: [],
      }),
    });
  });
  await page.route("**/api/v1/approvals/review?*", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        targetId: "approval:approval-node",
        nodeId: "approval-node",
        gateName: "evaluatemetadata.source-target-snapshot-main",
        gateRevision: "11",
        workflowName: "migration",
        resourceId: "resource:captureproxies:capture",
        resourceKind: "SnapshotMigration",
        resourceName: "migration-0",
        stage: "Metadata evaluation",
        effect: (
          "Approving allows metadata evaluation to complete and advances "
          + "to metadata migration."
        ),
        reason: null,
        snapshotRevision: snapshot.revision,
      }),
    });
  });
  await page.route("**/api/v1/approvals", async (route) => {
    const accepted = operation(
      "approve",
      "Approve Metadata evaluation",
      "Approval accepted; waiting for workflow reconciliation",
    );
    operations = [accepted, ...operations];
    await route.fulfill({
      status: 202,
      contentType: "application/json",
      body: JSON.stringify(accepted),
    });
  });
  await page.route("**/api/v1/resets/plan", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        token: "reset-token",
        requestTargetId: "reset:captureproxies:capture",
        targets: [{
          plural: "captureproxies",
          type: "captureproxy",
          name: "capture",
          path: "captureproxy.capture",
          phase: "Ready",
          dependsOn: [],
        }],
        messages: [],
        warnings: ["The proxy endpoint will be removed."],
      }),
    });
  });
  await page.route("**/api/v1/resets", async (route) => {
    const accepted = operation(
      "reset",
      "Reset captureproxy.capture",
      "Reset accepted; removing 1 resource",
    );
    operations = [accepted, ...operations];
    await route.fulfill({
      status: 202,
      contentType: "application/json",
      body: JSON.stringify(accepted),
    });
  });
  await page.route("**/api/v1/external-resources", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        nodeId: (
          "edit:sourceClusters.legacy.authConfig.basic.secretName"
        ),
        displayName: "HTTP Basic Auth Secret",
        rows: [{
          name: "source-creds",
          kind: "Secret",
          group: "",
          version: "v1",
          keys: ["username", "password"],
          status: "matching",
          message: "",
          current: false,
        }],
      }),
    });
  });
  await page.route("**/api/v1/external-resources/select", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ accepted: true }),
    });
  });
  await page.route("**/api/v1/external-resources/details", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        nodeId: (
          "edit:sourceClusters.legacy.authConfig.basic.secretName"
        ),
        displayName: "HTTP Basic Auth Secret",
        name: "source-creds",
        kind: "Secret",
        resourceType: null,
        keys: ["username", "password"],
        fieldValues: {
          name: "source-creds",
          type: "kubernetes.io/basic-auth",
        },
        hiddenFields: ["username", "password"],
        missing: false,
        message: null,
      }),
    });
  });
  await page.route("**/api/v1/external-resources/save", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        name: "source-creds",
        kind: "Secret",
        message: "Secret updated: source-creds",
      }),
    });
  });
  return {
    enableManagedActions() {
      const capture = snapshot.nodes["resource:captureproxies:capture"];
      capture.capabilities.push({
        kind: "output",
        outputTargetId: (
          "output:snapshotmigrations:migration-0:metadataEvaluate"
        ),
        label: "View metadata output",
      }, {
        kind: "approve",
        approvalTargetId: "approval:approval-node",
        label: "Approve metadata",
      });
    },
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
    makeSourceValid() {
      rawYaml = configurationYaml("source-creds");
    },
    makeConfigValid() {
      rawYaml = configurationYaml("source-creds");
    },
    configureRolloutViews() {
      snapshot.nodes["resource:captureproxies:capture"].configPresence = {
        deployed: true,
        submitted: false,
        pending: false,
      };
      snapshot.nodes["resource:trafficreplays:replay"].configPresence = {
        deployed: false,
        submitted: true,
        pending: true,
      };
    },
    makeSourceInvalid() {
      rawYaml = configurationYaml();
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


test("edits generic configuration and selects a Kubernetes Secret", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop interaction coverage");
  await mockManageApi(page);
  await page.goto("/");

  await page.getByRole("button", { name: "Edit configuration" }).click();
  await expect(page.getByRole("heading", { name: "Edit capture" })).toBeVisible();
  const configTree = page.getByRole("table", { name: "Configuration fields" });
  const endpoint = configTree.getByRole("row", {
    name: /Endpoint https:\/\/legacy\.example\.com/,
  }).getByRole("textbox", { name: "Endpoint" });
  await endpoint.fill("https://saved.example.com:9200");
  await expect(
    configTree.getByRole("button", { name: "Apply" }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "Save configuration" }).click();
  await expect(page.getByText("Saved configuration")).toBeVisible();

  await page.getByRole("checkbox", { name: "Show optional fields" }).check();
  const insecureHelp = (
    "When true, disables TLS certificate verification when connecting "
    + "to the cluster. Use only for development or self-signed certificates."
  );
  await expect(page.getByText(insecureHelp).first()).toBeVisible();
  await page.getByRole("checkbox", {
    name: "Show field documentation",
  }).uncheck();
  await expect(page.getByText(insecureHelp)).toHaveCount(0);

  const secretRow = configTree.getByRole("row", {
    name: /secretName|Credentials secret/,
  });
  await secretRow.getByRole("button", { name: /Configure$/ }).click();
  const selector = page.getByRole("dialog", {
    name: "HTTP Basic Auth Secret",
  });
  await expect(selector).toBeVisible();
  await expect(
    selector.getByRole("button", {
      name: "Use source-creds",
    }),
  ).toBeVisible();
  await selector.getByRole("button", {
    name: "Details for source-creds",
  }).click();
  const details = page.getByRole("dialog", { name: "source-creds" });
  await expect(details.getByText("Present, hidden").first()).toBeVisible();
  await details.getByRole("button", {
    name: "Close Kubernetes resource selector",
  }).click();
  await expect(selector).toBeVisible();
  await selector.getByRole("button", {
    name: "Use source-creds",
  }).click();
  await expect(selector).toHaveCount(0);
  await expect(page.getByText("Unsaved changes")).toBeVisible();
  await page.getByRole("button", { name: "Save configuration" }).click();
  await expect(page.getByText("Saved configuration")).toBeVisible();
});


test("updates variant fields in place beneath their selector", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop interaction coverage");
  await mockManageApi(page);
  await page.goto("/");

  await page.getByRole("button", { name: "Edit configuration" }).click();
  const addSource = page.getByRole("region", {
    name: "Resource navigation",
  }).getByRole("button", { name: "Add source cluster" });
  await expect(
    addSource,
  ).toBeVisible();
  await expect(addSource).toHaveCSS(
    "animation-name",
    "tree-icon-enter",
  );
  await expect(
    page.getByRole("region", { name: "Resource navigation" })
      .getByRole("button", { name: "Add source cluster" }),
  ).toBeVisible();
  const config = page.getByRole("table", { name: "Configuration fields" });
  const auth = config.getByRole("row", {
    name: /Auth Config basic HTTP Basic authentication/,
  });
  const authSelect = auth.getByRole("combobox", { name: "Auth Config" });
  await auth.scrollIntoViewIfNeeded();
  const authBefore = await auth.boundingBox();
  expect(authBefore).not.toBeNull();
  await authSelect.selectOption("sigv4");

  const updatedAuth = config.getByRole("row", {
    name: /Auth Config sigv4 AWS SigV4/,
  });
  const region = config.getByRole("row", { name: /Region/ });
  await expect(region).toBeVisible();
  await expect(
    updatedAuth.locator("xpath=following-sibling::tr[1]"),
  ).toContainText("Region");
  await expect(region).toBeInViewport();
  await page.waitForTimeout(450);
  const authAfter = await updatedAuth.boundingBox();
  expect(authAfter).not.toBeNull();
  expect(Math.abs(authAfter!.y - authBefore!.y)).toBeLessThanOrEqual(1);
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
    context.getByRole("button", { name: /^Sources 1 setting/ }),
  ).toBeVisible();
  await expect(
    context.getByRole("button", { name: /^legacy 5 settings/ }),
  ).toBeVisible();
  await context.getByRole("button", { name: /Sources/ }).click();
  await expect(
    config.getByRole("row", { name: /^Collapse Sources/ }),
  ).toBeInViewport();
});


test("animates collapsed rows without clamping the editor scroll position", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop scrolling coverage");
  await mockManageApi(page);
  await page.goto("/");

  await page.getByRole("button", { name: "Edit configuration" }).click();
  await page.getByRole("checkbox", { name: "Show optional fields" }).check();
  const config = page.getByRole("table", { name: "Configuration fields" });
  const legacy = config.getByRole("row", { name: /Collapse legacy/ });
  const panel = page.locator(".config-table-panel");
  await page.addStyleTag({
    content: ".config-table-panel { height: 260px; max-height: 260px; }",
  });
  await panel.evaluate((element: unknown) => {
    (element as { scrollTop: number }).scrollTop = 180;
  });
  const scrollTopBefore = await panel.evaluate((element: unknown) =>
    (element as { scrollTop: number }).scrollTop);
  expect(scrollTopBefore).toBeGreaterThan(100);

  await config.getByRole("button", {
    name: "Collapse Sources",
  }).dispatchEvent("click");

  await expect(legacy).toHaveClass(/removing/);
  await expect(legacy).toHaveCSS("animation-name", "row-remove");
  await expect(legacy).toHaveCount(0);

  const scrollTopAfter = await panel.evaluate((element: unknown) =>
    (element as { scrollTop: number }).scrollTop);
  expect(Math.abs(scrollTopAfter - scrollTopBefore)).toBeLessThanOrEqual(1);
});


test("transitions scoped parents before their full row scrolls away", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop scrolling coverage");
  const api = await mockManageApi(page);
  api.setCaptureEditTarget("edit:sourceClusters.legacy.endpoint");
  await page.goto("/");

  await page.getByRole("button", { name: "Edit configuration" }).click();
  await expect(
    page.getByRole("region", { name: "Resource navigation" })
      .getByRole("button", { name: "Add source cluster" }),
  ).toBeVisible();
  await page.getByRole("checkbox", { name: "Show optional fields" }).check();
  await page.getByRole("checkbox", { name: "Show expert fields" }).check();

  const config = page.getByRole("table", { name: "Configuration fields" });
  const authentication = config.getByRole("row", {
    name: /Collapse Auth Config/,
  });
  const columnHeader = config.getByRole("columnheader", { name: "Setting" });
  const panel = page.locator(".config-table-panel");
  await page.addStyleTag({
    content: ".config-table-panel { height: 260px; max-height: 260px; }",
  });
  await panel.hover();
  const context = page.getByRole("navigation", {
    name: "Current configuration path",
  });
  const pinnedAuthentication = context.getByRole("button", {
    name: /^Auth Config/,
  });
  for (
    let attempt = 0;
    attempt < 40 && await pinnedAuthentication.count() === 0;
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
  await expect(page.getByRole("heading", {
    name: "Workflow dependencies",
  })).toBeVisible();
  await expect(
    page.getByRole("table", { name: "Configuration fields" }),
  ).toBeVisible();
  await expect(resources.getByText("Deploy replay")).toHaveCount(0);
  await page.screenshot({
    animations: "disabled",
    path: testInfo.outputPath("configuration-only-navigation.png"),
  });
});


test("keeps valid status compact in navigation without an editor footer", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop layout coverage");
  const api = await mockManageApi(page);
  api.makeCaptureSource();
  api.makeSourceValid();
  await page.goto("/");

  await page.getByRole("button", { name: "Edit configuration" }).click();
  const legacy = page.getByRole("treeitem", { name: /^legacy$/ });
  const valid = legacy.getByLabel("Configuration valid");
  await expect(valid).toBeVisible();
  const validBox = await valid.boundingBox();
  expect(validBox).not.toBeNull();
  expect(validBox!.width).toBeLessThanOrEqual(24);
  await expect(page.getByRole("heading", { name: "Validation" }))
    .toHaveCount(0);
  await page.waitForTimeout(450);
  await expect(page.locator(".config-property-row.inserted")).toHaveCount(0);
  await expect(page.locator(".config-property-row.context-transition"))
    .toHaveCount(0);
  const config = page.getByRole("table", { name: "Configuration fields" });
  const allowInsecure = config.getByRole("row", { name: /Allow Insecure/ });
  await expect(allowInsecure.locator(".field-status")).toHaveCount(0);
  const documentation = page.getByRole("checkbox", {
    name: "Show field documentation",
  });
  await expect(documentation).toBeChecked();
  const secretDocumentation = page.getByText(
    "Name of a Kubernetes Secret containing",
  );
  await expect(secretDocumentation).toBeVisible();
  await page.screenshot({
    animations: "disabled",
    path: testInfo.outputPath("field-documentation.png"),
  });
  await documentation.uncheck();
  await expect(secretDocumentation).toHaveCount(0);
  await page.waitForTimeout(450);
  await expect(page.locator(".config-property-row.inserted")).toHaveCount(0);
  await expect(page.locator(".config-property-row.context-transition"))
    .toHaveCount(0);
  const compactBox = await allowInsecure.boundingBox();
  const revertBox = await allowInsecure.getByRole("button", {
    name: "Clear Allow Insecure and use the default",
  }).boundingBox();
  expect(compactBox).not.toBeNull();
  expect(revertBox).not.toBeNull();
  expect(compactBox!.height).toBeLessThanOrEqual(42);
  expect(Math.abs(
    compactBox!.y + compactBox!.height / 2
    - revertBox!.y - revertBox!.height / 2,
  )).toBeLessThanOrEqual(2);
  await page.waitForTimeout(50);
  await page.screenshot({
    path: testInfo.outputPath("compact-valid-status.png"),
  });
});


test("taints validation errors and their parent paths", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop layout coverage");
  const api = await mockManageApi(page);
  api.makeCaptureSource();
  api.makeSourceInvalid();
  await page.goto("/");

  await page.getByRole("button", { name: "Edit configuration" }).click();
  const legacy = page.getByRole("treeitem", { name: /^legacy$/ });
  const captureGroup = page.getByRole("treeitem", { name: /^Capture$/ });
  const migrationSection = page.getByRole("treeitem", {
    name: /^Live Traffic Migration$/,
  });
  await expect(legacy).toHaveClass(/validation-error-item/);
  await expect(captureGroup).toHaveClass(/validation-error-ancestor/);
  await expect(migrationSection).toHaveClass(/validation-error-ancestor/);

  const config = page.getByRole("table", { name: "Configuration fields" });
  await expect(config.getByRole("row", { name: /Auth Config/ }))
    .toHaveClass(/validation-error-ancestor/);
  await expect(config.getByRole("row", { name: /Secret Name/ }))
    .toHaveClass(/validation-error-item/);
  await expect(page.getByRole("heading", { name: "Validation" }))
    .toHaveCount(0);
  await page.screenshot({
    path: testInfo.outputPath("validation-error-paths.png"),
    fullPage: true,
  });
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
  await expect(removed).toHaveCSS("background-color", "rgb(223, 229, 232)");
  await expect(removed).toHaveCSS("border-color", "rgba(0, 0, 0, 0)");
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
  const resourceViews = page.getByRole("group", {
    name: "Resource state view",
  });
  await expect(resourceViews.getByRole("button", { name: "All" }))
    .toHaveAttribute("aria-pressed", "true");
  await expect(resourceViews.getByRole("button", { name: "Deployed" }))
    .toBeVisible();
  await expect(resourceViews.getByRole("button", { name: "Submitted" }))
    .toBeVisible();
  await expect(resourceViews.getByRole("button", { name: "Saved config" }))
    .toBeVisible();
  const capture = tree.getByRole("treeitem", { name: /^capture, Ready$/ });
  await expect(capture).toBeVisible();
  await expect(page.getByRole("region", {
    name: "Workflow dependency graph",
  })).toBeVisible();
  await page.screenshot({
    animations: "disabled",
    path: testInfo.outputPath("resource-state-and-dependencies.png"),
  });
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


test("submits pending config without entering edit mode", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop submission coverage");
  const api = await mockManageApi(page);
  api.makeConfigValid();
  await page.goto("/");

  await page.getByRole("button", { name: "Review and submit" }).click();
  const review = page.getByRole("dialog", {
    name: "Submit configuration?",
  });
  await expect(review.getByText("capture")).toBeVisible();
  await expect(review.getByText("Service type")).toBeVisible();
  await expect(page.getByText("Editing configuration")).toHaveCount(0);
  await review.getByRole("button", { name: "Confirm submit" }).click();

  await expect(page.getByText(
    "Workflow accepted; waiting for refreshed cluster state",
  )).toBeVisible();
  await expect(page.getByText(
    '404: workflows.argoproj.io "migration-workflow" not found',
  )).toHaveCount(0);
  await expect(page.getByText("Editing configuration")).toHaveCount(0);
  await expect(page.getByRole("button", {
    name: "Review and submit",
  })).toBeDisabled();
});


test("animates resource filters and entry into configuration mode", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop motion coverage");
  const api = await mockManageApi(page);
  api.makeConfigValid();
  api.configureRolloutViews();
  await page.goto("/");

  const tree = page.getByRole("tree", { name: "Workflow resources" });
  const treeScroller = page.getByTestId("tree-scroller");
  const resourceViews = page.getByRole("group", {
    name: "Resource state view",
  });
  const filterInput = page.getByRole("searchbox", {
    name: "Filter resources",
  });
  await expect(tree.getByRole("treeitem", {
    name: /^replay, Running$/,
  })).toBeVisible();
  const [scrollerBox, resourceViewsBox] = await Promise.all([
    treeScroller.boundingBox(),
    resourceViews.boundingBox(),
  ]);
  expect(scrollerBox).not.toBeNull();
  expect(resourceViewsBox).not.toBeNull();
  expect(resourceViewsBox!.y).toBeGreaterThanOrEqual(
    scrollerBox!.y + scrollerBox!.height - 1,
  );
  const filterY = (await filterInput.boundingBox())!.y;
  const initialIds = await tree.locator(".tree-row").evaluateAll(
    (elements: unknown[]) => (
      (elements as BrowserAnimatedElement[])
        .flatMap((row) => row.dataset.nodeId ?? [])
    ),
  );
  const movingRows = () => tree.locator(".tree-row").evaluateAll(
    animatedNodeIds,
  );

  await page.getByRole("button", { name: "Submitted" }).click();
  await expect.poll(movingRows).not.toEqual([]);
  await expect(tree.locator(".tree-row.layout-moving").first()).toBeVisible();
  const filterAnchors = await movingRows();
  expect(filterAnchors.every((nodeId) => initialIds.includes(nodeId))).toBe(true);
  const transitionDetails = await tree.locator(".tree-row").evaluateAll(
    treeTransitionDetails,
  );
  expect(transitionDetails.length).toBeGreaterThan(0);
  expect(transitionDetails.every(({ fill }) => fill === "both")).toBe(true);
  expect(transitionDetails.some(({ usesOpacity }) => usesOpacity)).toBe(false);
  expect(Math.max(...transitionDetails.map(({ yDistance }) => yDistance)))
    .toBeGreaterThan(20);
  const movingUnselectedRows = tree.locator(
    ".tree-row.layout-moving:not(.selected)",
  );
  await expect(movingUnselectedRows.first()).toHaveCSS(
    "background-color",
    "rgb(255, 255, 255)",
  );
  await page.screenshot({
    path: testInfo.outputPath("resource-layout-transition.png"),
  });
  await page.waitForTimeout(550);

  const replayRow = tree.locator(
    '[data-node-id="resource:trafficreplays:replay"]',
  );
  const replayLabel = replayRow.locator(".tree-row-copy > strong");
  const runtimeRowBox = await replayRow.boundingBox();
  expect(runtimeRowBox).not.toBeNull();
  expect(runtimeRowBox!.height).toBe(42);
  await page.evaluate(() => {
    const motion = globalThis as unknown as BrowserControlledMotionWindow;
    motion.__nativeTreeRequestAnimationFrame =
      motion.requestAnimationFrame.bind(globalThis);
    motion.__queuedTreeAnimationFrames = [];
    motion.requestAnimationFrame = (callback) => {
      motion.__queuedTreeAnimationFrames?.push(callback);
      return 10_000 + (motion.__queuedTreeAnimationFrames?.length ?? 0);
    };
  });
  const immediateAnimationStates = await page.getByRole(
    "button",
    { name: "Edit configuration" },
  ).evaluate(async (button: BrowserButtonElement) => {
    button.click();
    await Promise.resolve();
    const row = button.ownerDocument.querySelector(
      '[data-node-id="resource:trafficreplays:replay"]',
    );
    return row?.getAnimations().flatMap((animation) => (
      [
        "tree-layout-transition",
        "tree-size-transition",
      ].includes(animation.id)
        ? [{ id: animation.id, state: animation.playState }]
        : []
    )) ?? [];
  });
  await page.evaluate(() => {
    const motion = globalThis as unknown as BrowserControlledMotionWindow;
    const nativeFrame = motion.__nativeTreeRequestAnimationFrame;
    const queuedFrames = motion.__queuedTreeAnimationFrames ?? [];
    if (!nativeFrame) return;
    motion.requestAnimationFrame = nativeFrame;
    queuedFrames.forEach((callback) => nativeFrame(callback));
  });
  expect(immediateAnimationStates.map(({ id }) => id)).toEqual(
    expect.arrayContaining([
      "tree-layout-transition",
      "tree-size-transition",
    ]),
  );
  expect(immediateAnimationStates.every(({ state }) => state !== "paused"))
    .toBe(true);
  await expect.poll(movingRows).not.toEqual([]);
  expect((await filterInput.boundingBox())!.y).toBeCloseTo(filterY, 0);
  const rowAnimationDetails = await replayRow.evaluate(
    (element: BrowserAnimatedElement) => (
      element.getAnimations().flatMap((animation) => {
        if (![
          "tree-layout-transition",
          "tree-size-transition",
        ].includes(animation.id)) {
          return [];
        }
        return [{
          id: animation.id,
          keyframes: animation.effect?.getKeyframes() ?? [],
        }];
      })
    ),
  );
  expect(rowAnimationDetails.map(({ id }) => id)).toEqual(
    expect.arrayContaining([
      "tree-layout-transition",
      "tree-size-transition",
    ]),
  );
  const sizeFrames = rowAnimationDetails.find(
    ({ id }) => id === "tree-size-transition",
  )?.keyframes;
  expect(sizeFrames?.[0]?.["height"]).toBe("42px");
  expect(sizeFrames?.at(-1)?.["height"]).toBe("34px");

  const labelFrames = await replayLabel.evaluate(
    (element: BrowserAnimatedElement) => (
      element.getAnimations().find(
        (animation) => animation.id === "tree-label-transition",
      )?.effect?.getKeyframes() ?? []
    ),
  );
  const labelTransform = labelFrames[0]?.["transform"];
  expect(typeof labelTransform).toBe("string");
  expect(labelTransform).not.toBe("translate(0px, 0px)");

  const statusOpacityFrames = await replayRow.locator(
    ".tree-status-slot",
  ).evaluate((element: BrowserAnimatedElement) => (
    element.getAnimations().find(
      (animation) => animation.id === "tree-icon-transition",
    )?.effect?.getKeyframes() ?? []
  ));
  expect(statusOpacityFrames[0]?.["opacity"]).toBe("1");
  expect(statusOpacityFrames.at(-1)?.["opacity"]).toBe("0");

  await beginTreeMotionSampling(replayRow);
  api.insertCapture();
  await page.getByRole("button", { name: "Refresh state" }).click();
  await expect(tree.getByRole("treeitem", {
    name: /^capture-next/,
  })).toBeVisible();
  const motionSamples = await readTreeMotionSamples(page);
  expect(motionSamples.length).toBeGreaterThan(10);
  expect(
    largestFrameDelta(motionSamples),
    JSON.stringify(motionSamples),
  ).toBeLessThan(32);

  await page.screenshot({
    path: testInfo.outputPath("configuration-layout-transition.png"),
  });
  expect((await replayRow.boundingBox())!.height).toBe(34);

  await beginTreeMotionSampling(replayRow);
  await page.getByRole("button", { name: "Exit editing" }).click();
  await page.getByRole("button", { name: "Saved config" }).click();
  const exitMotionSamples = await readTreeMotionSamples(page);
  expect(exitMotionSamples.length).toBeGreaterThan(10);
  expect(
    largestFrameDelta(exitMotionSamples),
    JSON.stringify(exitMotionSamples),
  ).toBeLessThan(34);
  expect((await replayRow.boundingBox())!.height).toBe(42);
});


test("blocks invalid pending config before review", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop submission coverage");
  await mockManageApi(page);
  await page.goto("/");

  const submit = page.getByRole("button", { name: "Review and submit" });
  await expect(submit).toBeDisabled();
  await expect(page.getByText("1 configuration error")).toBeVisible();
  await expect(submit).toHaveAttribute(
    "title",
    "Resolve 1 configuration error before submitting",
  );
});


test("reviews managed output, approval, and reset actions", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "desktop action coverage");
  const api = await mockManageApi(page);
  api.enableManagedActions();
  await page.goto("/");

  const initialActions = page.getByRole("dialog", {
    name: "Review required actions",
  });
  await expect(initialActions.getByText(/advances to metadata migration/))
    .toBeVisible();
  await initialActions.getByRole("button", {
    name: "Close required actions",
  }).click();

  await page.getByRole("button", { name: "View metadata output" }).click();
  const output = page.getByRole("region", { name: "Managed output" });
  await expect(output.getByRole("tab")).toHaveText([
    /Evaluate/,
    /Migrate/,
  ]);
  await expect(output.getByText(/"stage": "evaluate"/)).toBeVisible();
  await output.getByRole("tab", { name: /Migrate/ }).click();
  await expect(output.getByText(/"stage": "migrate"/)).toBeVisible();
  await page.getByRole("button", { name: "Close output" }).click();

  await page.getByRole("button", { name: "Approve metadata" }).click();
  const approval = page.getByRole("dialog", {
    name: "Review required actions",
  });
  await expect(approval.getByText(/advances to metadata migration/))
    .toBeVisible();
  await approval.getByRole("button", {
    name: "Approve",
  }).click();
  await expect(approval.getByText(
    "Action accepted. Waiting for workflow reconciliation.",
  )).toBeVisible();
  await approval.getByRole("button", {
    name: "Close required actions",
  }).click();
  await expect(page.getByText(
    "Approval accepted; waiting for workflow reconciliation",
  )).toBeVisible();

  await page.getByRole("button", { name: "Reset capture" }).click();
  const reset = page.getByRole("dialog", { name: "Review reset plan" });
  await expect(reset.getByText("captureproxy.capture")).toBeVisible();
  await expect(reset.getByText("The proxy endpoint will be removed."))
    .toBeVisible();
  await reset.getByRole("button", { name: "Reset exact plan" }).click();
  await expect(page.getByText("Reset accepted; removing 1 resource"))
    .toBeVisible();
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
  await expect(activity.getByRole("heading", {
    name: "Workflow dependencies",
  })).toBeVisible();
  await expect(activity.getByRole("button", {
    name: "Open replay, Running",
  })).toBeVisible();
  await page.screenshot({
    animations: "disabled",
    fullPage: true,
    path: testInfo.outputPath("narrow-run-and-dependencies.png"),
  });

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
  await page.getByRole("button", { name: "Open resources" }).click();
  await expect(
    page.getByRole("region", { name: "Resource navigation" })
      .getByRole("button", { name: "Add source cluster" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Close resources" }).click();
  await page.getByRole("checkbox", { name: "Show optional fields" }).check();
  const configTree = page.getByRole("table", { name: "Configuration fields" });
  await configTree.getByRole("row", { name: /Allow Insecure/ }).first().click();
  const documentation = page.getByText(
    "When true, disables TLS certificate verification when connecting to the cluster.",
  ).first();
  await expect(documentation).toBeVisible();
  await page.getByRole("checkbox", {
    name: "Show field documentation",
  }).uncheck();
  await expect(documentation).toHaveCount(0);

  const scrollWidth = await page.evaluate<number>(
    "document.documentElement.scrollWidth",
  );
  const clientWidth = await page.evaluate<number>(
    "document.documentElement.clientWidth",
  );
  expect(scrollWidth > clientWidth).toBe(false);
  await page.screenshot({
    animations: "disabled",
    path: testInfo.outputPath("narrow-configuration.png"),
    fullPage: true,
  });
});


test("disables insertion motion when reduced motion is requested", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "one browser is sufficient");
  await page.emulateMedia({ reducedMotion: "reduce" });
  const api = await mockManageApi(page);
  api.configureRolloutViews();
  await page.goto("/");

  api.insertCapture();
  await page.getByRole("button", { name: "Refresh state" }).click();
  const inserted = page.getByRole(
    "treeitem",
    { name: /^capture-next, Ready$/ },
  );
  await expect(inserted).toBeVisible();
  await expect(inserted).toHaveCSS("animation-name", "none");

  const tree = page.getByRole("tree", {
    name: "Workflow resources",
  });
  const layoutAnimationCount = () => tree.locator(".tree-row").evaluateAll(
    treeLayoutAnimationCount,
  );

  await page.getByRole("button", { name: "Submitted" }).click();
  expect(await layoutAnimationCount()).toBe(0);
  await page.getByRole("button", { name: "Edit configuration" }).click();
  expect(await layoutAnimationCount()).toBe(0);
});
