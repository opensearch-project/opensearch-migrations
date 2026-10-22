export interface EditorDisplayPreferences {
  showDocumentation: boolean;
  showExpert: boolean;
  showOptional: boolean;
}


export const DEFAULT_EDITOR_DISPLAY_PREFERENCES: EditorDisplayPreferences = {
  showDocumentation: true,
  showExpert: false,
  showOptional: true,
};


const STORAGE_PREFIX = "workflow-manage:editor-display:";


function storageKey(resourceType: string): string {
  return `${STORAGE_PREFIX}${resourceType.trim().toLocaleLowerCase()}`;
}


function normalizeEditorDisplayPreferences(
  preferences: EditorDisplayPreferences,
): EditorDisplayPreferences {
  return preferences.showExpert
    ? { ...preferences, showOptional: true }
    : preferences;
}


export function readEditorDisplayPreferences(
  resourceType: string,
): EditorDisplayPreferences {
  try {
    const stored = globalThis.localStorage.getItem(storageKey(resourceType));
    if (!stored) return { ...DEFAULT_EDITOR_DISPLAY_PREFERENCES };
    const parsed = JSON.parse(stored) as Partial<EditorDisplayPreferences>;
    return normalizeEditorDisplayPreferences({
      showDocumentation: typeof parsed.showDocumentation === "boolean"
        ? parsed.showDocumentation
        : DEFAULT_EDITOR_DISPLAY_PREFERENCES.showDocumentation,
      showExpert: typeof parsed.showExpert === "boolean"
        ? parsed.showExpert
        : DEFAULT_EDITOR_DISPLAY_PREFERENCES.showExpert,
      showOptional: typeof parsed.showOptional === "boolean"
        ? parsed.showOptional
        : DEFAULT_EDITOR_DISPLAY_PREFERENCES.showOptional,
    });
  } catch {
    return { ...DEFAULT_EDITOR_DISPLAY_PREFERENCES };
  }
}


export function writeEditorDisplayPreferences(
  resourceType: string,
  preferences: EditorDisplayPreferences,
): void {
  try {
    globalThis.localStorage.setItem(
      storageKey(resourceType),
      JSON.stringify(normalizeEditorDisplayPreferences(preferences)),
    );
  } catch {
    // Browser privacy and storage policies must not prevent configuration edits.
  }
}
