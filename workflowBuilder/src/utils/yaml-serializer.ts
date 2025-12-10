import { stringify } from "yaml";

/**
 * Convert object to YAML string with proper formatting
 */
export function toYaml(obj: object): string {
  return stringify(obj, {
    indent: 2,
    lineWidth: 0, // Disable line wrapping
    nullStr: "~",
    defaultStringType: "QUOTE_DOUBLE",
    defaultKeyType: "PLAIN",
  });
}

/**
 * Find the line number for a given JSON path in YAML output
 * Path format: "sourceClusters.source.endpoint"
 */
export function getYamlLineForPath(yaml: string, path: string): number {
  const lines = yaml.split("\n");
  const pathParts = path.split(".");
  
  let currentIndent = 0;
  let pathIndex = 0;
  
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const trimmed = line.trimStart();
    const lineIndent = line.length - trimmed.length;
    
    // Skip empty lines and comments
    if (trimmed === "" || trimmed.startsWith("#")) {
      continue;
    }
    
    // Check if this line matches the current path part
    const keyMatch = trimmed.match(/^([a-zA-Z0-9_-]+):/);
    if (keyMatch) {
      const key = keyMatch[1];
      
      // If we're at the right indent level and key matches
      if (lineIndent === currentIndent && key === pathParts[pathIndex]) {
        pathIndex++;
        
        // If we've matched all parts, return this line (1-indexed)
        if (pathIndex === pathParts.length) {
          return i + 1;
        }
        
        // Move to next indent level
        currentIndent = lineIndent + 2;
      }
    }
  }
  
  return -1; // Not found
}

/**
 * Build a map of field paths to line numbers
 */
export function buildYamlLineMap(yaml: string): Map<string, number> {
  const lineMap = new Map<string, number>();
  const lines = yaml.split("\n");
  const pathStack: { key: string; indent: number }[] = [];
  
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const trimmed = line.trimStart();
    const lineIndent = line.length - trimmed.length;
    
    // Skip empty lines and comments
    if (trimmed === "" || trimmed.startsWith("#")) {
      continue;
    }
    
    // Pop stack items that are at same or higher indent
    while (pathStack.length > 0 && pathStack[pathStack.length - 1].indent >= lineIndent) {
      pathStack.pop();
    }
    
    // Check if this line has a key
    const keyMatch = trimmed.match(/^([a-zA-Z0-9_-]+):/);
    if (keyMatch) {
      const key = keyMatch[1];
      pathStack.push({ key, indent: lineIndent });
      
      // Build full path
      const fullPath = pathStack.map(p => p.key).join(".");
      lineMap.set(fullPath, i + 1); // 1-indexed
    }
  }
  
  return lineMap;
}
