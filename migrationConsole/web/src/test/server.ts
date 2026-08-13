import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";

import { configDraft, manageSnapshot } from "./fixtures";

export const server = setupServer(
  http.get("*/api/v1/system/health", () =>
    HttpResponse.json({
      status: "ok",
      apiVersion: "v1",
    }),
  ),
  http.get("*/api/v1/manage/state", () =>
    HttpResponse.json(manageSnapshot),
  ),
  http.get("*/api/v1/operations", () =>
    HttpResponse.json({ operations: [] }),
  ),
  http.get("*/api/v1/approvals/review", ({ request }) => {
    const targetId = new URL(request.url).searchParams.get("targetId") ?? "";
    return HttpResponse.json({
      targetId,
      nodeId: "approval-node",
      gateName: "evaluatemetadata.source-target-snapshot-main",
      gateRevision: "11",
      workflowName: "migration",
      resourceId: "resource:snapshotmigrations:migration-0",
      resourceKind: "SnapshotMigration",
      resourceName: "migration-0",
      stage: "Metadata evaluation",
      effect: (
        "Approving allows metadata evaluation to complete and advances "
        + "to metadata migration."
      ),
      reason: null,
      snapshotRevision: manageSnapshot.revision,
    });
  }),
  http.post("*/api/v1/resets/plan", () =>
    HttpResponse.json({
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
      warnings: [],
    }),
  ),
  http.get("*/api/v1/config", () =>
    HttpResponse.json(configDraft),
  ),
  http.post("*/api/v1/config/review", () =>
    HttpResponse.json({
      draftRevision: configDraft.draftRevision,
      baseRevision: configDraft.baseRevision,
      dirty: configDraft.dirty,
      valid: configDraft.editState.validation.valid,
      validationMessages: configDraft.editState.validation.errors,
      changes: [],
    }),
  ),
  http.get("*/api/v1/outputs", ({ request }) => {
    const targetId = new URL(request.url).searchParams.get("targetId") ?? "";
    return HttpResponse.json({
      targetId,
      resourceId: "resource:snapshotmigrations:migration-0",
      outputs: [{
        id: "managed-output:fixture",
        targetId,
        resourceId: "resource:snapshotmigrations:migration-0",
        resourcePlural: "snapshotmigrations",
        resourceName: "migration-0",
        outputName: "metadataEvaluate",
        stage: "Evaluate",
        stageOrder: 0,
        attempt: "migration",
        timestamp: "2026-08-13T12:00:00Z",
        source: "s3://outputs/evaluate.json",
        contentType: "application/json",
      }],
    });
  }),
  http.get("*/api/v1/outputs/content", () =>
    HttpResponse.json({
      descriptor: {
        id: "managed-output:fixture",
        targetId: "output:snapshotmigrations:migration-0:metadataEvaluate",
        resourceId: "resource:snapshotmigrations:migration-0",
        resourcePlural: "snapshotmigrations",
        resourceName: "migration-0",
        outputName: "metadataEvaluate",
        stage: "Evaluate",
        stageOrder: 0,
        attempt: "migration",
        timestamp: "2026-08-13T12:00:00Z",
        source: "s3://outputs/evaluate.json",
        contentType: "application/json",
      },
      content: "{\"documents\":12}",
      inline: true,
      size: 16,
      message: null,
    }),
  ),
  http.post("*/api/v1/config/removal-impact", async ({ request }) => {
    const body = await request.json() as {
      path: string[];
    };
    return HttpResponse.json({
      targetPath: body.path,
      targetLabel: body.path.at(-1) ?? "configuration",
      affected: [],
    });
  }),
  http.get("*/api/v1/external-resources", () =>
    HttpResponse.json({
      nodeId: "edit:traffic.transform.configMap",
      draftRevision: configDraft.draftRevision,
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
      }, {
        name: "near-match",
        kind: "ConfigMap",
        group: "",
        version: "v1",
        keys: ["README"],
        status: "warn",
        message: "No JavaScript-looking key",
        current: false,
      }],
    }),
  ),
  http.get("*/api/v1/external-resources/details", () =>
    HttpResponse.json({
      nodeId: "edit:traffic.transform.configMap",
      draftRevision: configDraft.draftRevision,
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
  ),
  http.post("*/api/v1/external-resources/save", () =>
    HttpResponse.json({
      draft: {
        ...configDraft,
        dirty: true,
        draftRevision: "config-draft-external-save",
      },
      name: "transform-code",
      kind: "ConfigMap",
      message: "ConfigMap updated: transform-code",
    }),
  ),
);
