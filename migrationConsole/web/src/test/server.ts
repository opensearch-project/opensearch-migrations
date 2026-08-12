import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";

import { manageSnapshot } from "./fixtures";

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
);
