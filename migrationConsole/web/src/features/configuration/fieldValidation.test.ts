import { expect, test } from "vitest";

import { fieldValidationProblem } from "./fieldValidation";


test("returns schema help immediately when a value does not match", () => {
  expect(fieldValidationProblem(
    "Not Valid",
    "^[a-z0-9-]+$",
    "Use lowercase letters, numbers, and hyphens.",
  )).toBe("Use lowercase letters, numbers, and hyphens.");
});


test("accepts matching and empty values without showing an error", () => {
  expect(fieldValidationProblem("valid-name", "^[a-z0-9-]+$")).toBe("");
  expect(fieldValidationProblem("", "^[a-z0-9-]+$")).toBe("");
});
