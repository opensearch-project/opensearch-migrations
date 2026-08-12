import createClient from "openapi-fetch";

import type { paths } from "./schema.generated";


const client = createClient<paths>({
  baseUrl: window.location.origin,
  fetch: (...args) => globalThis.fetch(...args),
});


export async function getHealth() {
  const { data, error, response } = await client.GET(
    "/api/v1/system/health",
  );
  if (!response.ok || error || !data) {
    throw new Error("Workflow Manage server is unavailable");
  }
  return data;
}
