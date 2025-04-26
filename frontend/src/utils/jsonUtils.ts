export const readFileAsText = (file: File): Promise<string> => {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(new Error(`Failed to read file: ${file.name}`));
    reader.readAsText(file);
  });
};

export const validateJsonContent = (content: string): string | null => {
  try {
    // Try parsing as regular JSON first
    JSON.parse(content);
    return null;
  } catch (e) {
    // If not regular JSON, check if it's newline-delimited JSON
    return validateNewlineDelimitedJson(content);
  }
};

export const validateNewlineDelimitedJson = (content: string): string | null => {
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
  return null; // All lines are valid JSON
};

export const prettyPrintJson = (json: string): string => {
  try {
    const parsed = JSON.parse(json);
    return JSON.stringify(parsed, null, 2);
  } catch (e) {
    // For newline-delimited JSON, try to pretty print each line
    try {
      const lines = json.trim().split("\n");
      return lines
        .map(line => {
          if (!line.trim()) return "";
          const parsed = JSON.parse(line);
          return JSON.stringify(parsed, null, 2);
        })
        .join("\n\n");
    } catch {
      return json; // Return original if all attempts fail
    }
  }
};
