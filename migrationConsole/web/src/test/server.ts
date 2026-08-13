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
  http.get("*/api/v1/config", () =>
    HttpResponse.json(configDraft),
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
