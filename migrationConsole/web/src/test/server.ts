import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";

export const server = setupServer(
  http.get("*/api/v1/system/health", () =>
    HttpResponse.json({
      status: "ok",
      apiVersion: "v1",
    }),
  ),
);
