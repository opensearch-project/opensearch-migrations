export const readFileAsText = (file: File) => file.text();

export const validateJsonContent = (content: string): string | null => {
  try {
    JSON.parse(content);
    return null;
  } catch {
    // If not regular JSON, check if it's newline-delimited JSON
    return validateNewlineDelimitedJson(content);
  }
};

export const validateNewlineDelimitedJson = (
  content: string,
): string | null => {
  const lines = content.trim().split("\n");

  for (const line of lines) {
    if (line.trim()) {
      try {
        JSON.parse(line);
      } catch (lineError) {
        return `Invalid JSON format: ${lineError instanceof Error ? lineError.message : "Unknown error"}`;
      }
    }
  }
  return null;
};

/**
 * Checks if the content is newline-delimited JSON
 * @param content The string content to check
 * @returns true if the content is valid newline-delimited JSON with at least 2 lines
 */
export const isNewlineDelimitedJson = (content: string): boolean => {
  const lines = content.trim().split("\n");

  // If there's only one line, it's not NDJSON
  if (lines.filter((line) => line.trim()).length <= 1) {
    return false;
  }

  // Check if all non-empty lines are valid JSON
  for (const line of lines) {
    if (line.trim()) {
      try {
        JSON.parse(line);
      } catch {
        return false;
      }
    }
  }

  return true;
};

/**
 * Pretty prints a JSON string
 * @param json The JSON string to pretty print
 * @returns A formatted JSON string with proper indentation
 */
export const prettyPrintJson = (json: string): string => {
  try {
    const parsed = JSON.parse(json);
    return JSON.stringify(parsed, null, 2);
  } catch {
    // If parsing fails, return the original string
    return json;
  }
};

/**
 * Calculates the size of a JSON string in bytes
 * @param jsonString The JSON string to measure
 * @returns The size in bytes
 */
export const getJsonSizeInBytes = (jsonString: string): number => {
  return new Blob([jsonString]).size;
};
