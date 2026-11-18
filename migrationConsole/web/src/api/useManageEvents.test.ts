import { describe, expect, it } from "vitest";

import { savedConfigurationRevision } from "./useManageEvents";


describe("savedConfigurationRevision", () => {
  it("returns the revision from a configuration-saved invalidation", () => {
    expect(savedConfigurationRevision(JSON.stringify({
      reason: "configuration-saved",
      persistedRevision: "saved-2",
    }))).toBe("saved-2");
  });

  it("ignores unrelated and malformed events", () => {
    expect(savedConfigurationRevision(JSON.stringify({
      reason: "runtime-refreshed",
      persistedRevision: "saved-2",
    }))).toBeNull();
    expect(savedConfigurationRevision("{")).toBeNull();
  });
});
