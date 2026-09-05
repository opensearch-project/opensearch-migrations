import { afterEach, expect, test } from "vitest";

import {
  DEFAULT_EDITOR_DISPLAY_PREFERENCES,
  readEditorDisplayPreferences,
  writeEditorDisplayPreferences,
} from "./editorPreferences";


afterEach(() => globalThis.localStorage.clear());


test("defaults documentation and optional fields on for a new resource type", () => {
  expect(readEditorDisplayPreferences("Source cluster")).toEqual(
    DEFAULT_EDITOR_DISPLAY_PREFERENCES,
  );
});


test("stores display preferences independently by resource type", () => {
  writeEditorDisplayPreferences("Source cluster", {
    showDocumentation: false,
    showExpert: true,
    showOptional: false,
  });

  expect(readEditorDisplayPreferences("Source cluster")).toEqual({
    showDocumentation: false,
    showExpert: true,
    showOptional: false,
  });
  expect(readEditorDisplayPreferences("Target cluster")).toEqual(
    DEFAULT_EDITOR_DISPLAY_PREFERENCES,
  );
});


test("falls back field by field when stored preferences are stale", () => {
  globalThis.localStorage.setItem(
    "workflow-manage:editor-display:source cluster",
    JSON.stringify({ showDocumentation: false }),
  );

  expect(readEditorDisplayPreferences("Source cluster")).toEqual({
    showDocumentation: false,
    showExpert: false,
    showOptional: true,
  });
});
