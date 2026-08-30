import { describe, expect, test } from "vitest";

import { classifyLogSeverity } from "./logSeverity";


describe("classifyLogSeverity", () => {
  test.each([
    ['time=now level=ERROR msg="executor error"', "error"],
    ['{"severity":"fatal","message":"crashed"}', "error"],
    ["Error: output file is missing", "error"],
    ["Timed out waiting for proxy endpoint readiness", "error"],
    ["WARN connection is slow", "warning"],
    ['time=now level=WARNING msg="retrying"', "warning"],
    ['time=now level=INFO msg="failed" error="exit status 1"', null],
    ["request completed without error", null],
  ])("classifies %s", (message, expected) => {
    expect(classifyLogSeverity(message)).toBe(expected);
  });

  test("preserves errors reported by the stream", () => {
    expect(classifyLogSeverity("connection closed", "error")).toBe("error");
  });
});
