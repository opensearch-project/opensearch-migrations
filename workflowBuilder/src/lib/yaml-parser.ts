/**
 * YAML/JSON Parser
 * 
 * Enhanced parsing utilities with position tracking for error mapping.
 * Supports bidirectional conversion between form state and code content.
 */

import YAML from 'yaml';
import type { ParseError, CodeFormat } from '../types';
import { createDebugLogger } from './debug-logger';

// Create a logger for YAML parser operations
const log = createDebugLogger({ prefix: '[YamlParser]' });

/**
 * Result of parsing YAML or JSON content
 */
export interface ParseResult<T = unknown> {
  success: boolean;
  data?: T;
  error?: ParseError;
}

/**
 * Parse YAML content with error position tracking
 */
export function parseYaml<T = unknown>(content: string): ParseResult<T> {
  try {
    // Use YAML library with position tracking
    const doc = YAML.parseDocument(content, {
      keepSourceTokens: true,
    });
    
    // Check for parsing errors
    if (doc.errors.length > 0) {
      const firstError = doc.errors[0];
      const pos = firstError.pos?.[0] ?? 0;
      const lineInfo = getLineFromPosition(content, pos);
      
      return {
        success: false,
        error: {
          message: firstError.message,
          line: lineInfo.line,
          column: lineInfo.column,
          snippet: getSnippetAtLine(content, lineInfo.line),
        },
      };
    }
    
    // Check for warnings that might indicate issues
    if (doc.warnings.length > 0) {
      // Log warnings but don't fail
      console.warn('YAML warnings:', doc.warnings);
    }
    
    const data = doc.toJS() as T;
    
    return {
      success: true,
      data,
    };
  } catch (err) {
    const error = err as Error & { pos?: number[] };
    const pos = error.pos?.[0] ?? 0;
    const lineInfo = getLineFromPosition(content, pos);
    
    return {
      success: false,
      error: {
        message: error.message || 'Failed to parse YAML',
        line: lineInfo.line,
        column: lineInfo.column,
        snippet: getSnippetAtLine(content, lineInfo.line),
      },
    };
  }
}

/**
 * Parse JSON content with error position tracking
 */
export function parseJson<T = unknown>(content: string): ParseResult<T> {
  try {
    const data = JSON.parse(content) as T;
    
    return {
      success: true,
      data,
    };
  } catch (err) {
    const error = err as SyntaxError & { message: string };
    
    // Try to extract position from error message
    // JSON.parse errors often include "at position X"
    const posMatch = error.message.match(/position\s+(\d+)/i);
    const pos = posMatch ? parseInt(posMatch[1], 10) : 0;
    const lineInfo = getLineFromPosition(content, pos);
    
    return {
      success: false,
      error: {
        message: error.message || 'Failed to parse JSON',
        line: lineInfo.line,
        column: lineInfo.column,
        snippet: getSnippetAtLine(content, lineInfo.line),
      },
    };
  }
}

/**
 * Parse content based on format
 */
export function parseContent<T = unknown>(
  content: string,
  format: CodeFormat
): ParseResult<T> {
  if (format === 'yaml') {
    return parseYaml<T>(content);
  } else {
    return parseJson<T>(content);
  }
}

/**
 * Serialize data to YAML
 */
export function serializeToYaml(data: unknown): string {
  return YAML.stringify(data, {
    indent: 2,
    lineWidth: 0, // Don't wrap lines
    nullStr: '~',
  });
}

/**
 * Serialize data to JSON
 */
export function serializeToJson(data: unknown): string {
  return JSON.stringify(data, null, 2);
}

/**
 * Serialize data to the specified format
 */
export function serializeContent(
  data: unknown,
  format: CodeFormat
): string {
  if (format === 'yaml') {
    return serializeToYaml(data);
  } else {
    return serializeToJson(data);
  }
}

/**
 * Get line and column from character position
 */
function getLineFromPosition(
  content: string,
  position: number
): { line: number; column: number } {
  const lines = content.substring(0, position).split('\n');
  const line = lines.length;
  const column = (lines[lines.length - 1]?.length ?? 0) + 1;
  
  return { line, column };
}

/**
 * Get a snippet of content at a specific line
 */
function getSnippetAtLine(content: string, line: number): string {
  const lines = content.split('\n');
  const lineIndex = line - 1;
  
  if (lineIndex >= 0 && lineIndex < lines.length) {
    return lines[lineIndex].trim();
  }
  
  return '';
}

/**
 * Get the field path at a specific position in YAML content
 */
export function getPathAtPosition(
  content: string,
  line: number,
  column: number,
  format: CodeFormat
): string | null {
  log.group(`getPathAtPosition(line=${line}, col=${column}, format=${format})`);
  
  let result: string | null;
  if (format === 'yaml') {
    result = getPathAtPositionYaml(content, line, column);
  } else {
    result = getPathAtPositionJson(content, line, column);
  }
  
  log.debug('Result path:', result);
  log.groupEnd();
  return result;
}

/**
 * Get field path at position in YAML
 * Handles array items (lines starting with `- `) by tracking array indices
 * and outputting bracket notation (e.g., `migrationConfigs[0].skipApprovals`)
 */
function getPathAtPositionYaml(
  content: string,
  targetLine: number,
  _column: number
): string | null {
  const lines = content.split('\n');
  
  // First, build a complete map of the YAML structure
  const structure: Array<{
    line: number;
    indent: number;
    effectiveIndent: number; // The indent level for path hierarchy purposes
    key?: string;
    isArrayItem: boolean;
    arrayIndex?: number;
    isInlineArrayKey?: boolean; // True if this is a key on the same line as array dash
  }> = [];
  
  // Track array indices at each indent level
  const arrayCounters: Map<number, number> = new Map();
  
  // Build the structure map
  for (let lineNum = 0; lineNum < lines.length; lineNum++) {
    const line = lines[lineNum];
    const trimmed = line.trimStart();
    
    // Skip empty lines and comments
    if (!trimmed || trimmed.startsWith('#')) {
      continue;
    }
    
    const indent = line.length - trimmed.length;
    const isArrayItem = trimmed.startsWith('- ');
    
    // Reset array counters for indents we've moved back from
    for (const [indentLevel] of arrayCounters) {
      if (indentLevel > indent) {
        arrayCounters.delete(indentLevel);
      }
    }
    
    if (isArrayItem) {
      // Get current array index for this indent level
      const currentIndex = arrayCounters.get(indent) ?? 0;
      
      // Increment for next array item at this level
      arrayCounters.set(indent, currentIndex + 1);
      
      // Check if there's a key after the dash
      const afterDash = trimmed.substring(2).trim();
      const keyMatch = afterDash.match(/^([^:]+):/);
      
      // Always add the array item entry
      structure.push({
        line: lineNum + 1,
        indent,
        effectiveIndent: indent,
        isArrayItem: true,
        arrayIndex: currentIndex,
      });
      
      // If there's also a key on the same line, add it as a separate entry
      // with an effective indent that's deeper than the array item
      if (keyMatch) {
        structure.push({
          line: lineNum + 1,
          indent, // Physical indent is the same
          effectiveIndent: indent + 2, // But effective indent is deeper (child of array item)
          key: keyMatch[1].trim(),
          isArrayItem: false,
          isInlineArrayKey: true,
        });
      }
    } else {
      // Regular key: value line
      const keyMatch = trimmed.match(/^([^:]+):/);
      if (keyMatch) {
        structure.push({
          line: lineNum + 1,
          indent,
          effectiveIndent: indent,
          key: keyMatch[1].trim(),
          isArrayItem: false,
        });
      }
    }
  }
  
  // Now find the path for the target line by walking the structure
  const pathStack: string[] = [];
  const indentStack: number[] = []; // Tracks effective indents
  
  for (const item of structure) {
    if (item.line > targetLine) {
      break;
    }
    
    // Pop items from stack that are at same or greater effective indent
    // For regular keys, we pop items at >= their effective indent
    // For array items, we also need to handle the case where we're moving to a sibling
    while (indentStack.length > 0 && indentStack[indentStack.length - 1] >= item.effectiveIndent) {
      indentStack.pop();
      pathStack.pop();
    }
    
    if (item.isArrayItem) {
      // This is an array item
      pathStack.push(`[${item.arrayIndex}]`);
      indentStack.push(item.effectiveIndent);
    } else if (item.key) {
      pathStack.push(item.key);
      indentStack.push(item.effectiveIndent);
    }
    
    // If this is the target line, we're done
    // But we need to process all entries for this line (array item + inline key)
    if (item.line === targetLine) {
      // Check if there's another entry for the same line (inline key after array dash)
      const nextIdx = structure.indexOf(item) + 1;
      if (nextIdx < structure.length && structure[nextIdx].line === targetLine) {
        // Continue to process the inline key
        continue;
      }
      break;
    }
  }
  
  if (pathStack.length === 0) {
    return null;
  }
  
  // Build the final path
  let path = '';
  for (const part of pathStack) {
    if (part.startsWith('[')) {
      // Array index - append directly without dot
      path += part;
    } else if (path === '') {
      // First item
      path = part;
    } else {
      // Regular key - append with dot
      path += '.' + part;
    }
  }
  
  return path;
}

/**
 * Get field path at position in JSON
 */
function getPathAtPositionJson(
  content: string,
  targetLine: number,
  _column: number
): string | null {
  const lines = content.split('\n');
  const pathStack: string[] = [];
  let braceDepth = 0;
  
  for (let lineNum = 0; lineNum < lines.length && lineNum <= targetLine - 1; lineNum++) {
    const line = lines[lineNum];
    
    // Look for key first (before counting braces on this line)
    const keyMatch = line.match(/"([^"]+)"\s*:/);
    if (keyMatch) {
      const key = keyMatch[1];
      
      // Adjust path stack to current depth (before any brace on this line)
      while (pathStack.length >= braceDepth) {
        pathStack.pop();
      }
      
      pathStack.push(key);
    }
    
    // Track braces after processing the key
    for (let i = 0; i < line.length; i++) {
      const char = line[i];
      
      if (char === '{') {
        braceDepth++;
      } else if (char === '}') {
        braceDepth--;
        // Pop path when closing brace reduces depth below path length
        while (pathStack.length > braceDepth) {
          pathStack.pop();
        }
      }
    }
  }
  
  if (pathStack.length === 0) {
    return null;
  }
  
  return pathStack.join('.');
}

/**
 * Get the line number for a specific field path
 */
export function getLineForPath(
  content: string,
  path: string,
  format: CodeFormat
): number | null {
  log.group(`getLineForPath(path="${path}", format=${format})`);
  
  const lines = content.split('\n');
  const pathParts = path.split('.');
  log.debug('Path parts:', pathParts);
  
  let result: number | null;
  if (format === 'yaml') {
    result = getLineForPathYaml(lines, pathParts);
  } else {
    result = getLineForPathJson(lines, pathParts);
  }
  
  log.debug('Result line:', result);
  log.groupEnd();
  return result;
}

/**
 * Get line for path in YAML
 * Uses a more flexible approach that tracks indent levels rather than exact spacing
 */
function getLineForPathYaml(
  lines: string[],
  pathParts: string[]
): number | null {
  log.debug('getLineForPathYaml - searching for path parts:', pathParts);
  
  // Track the indent level for each matched path part
  // -1 means we're looking for the first key at any indent (usually 0)
  const indentStack: number[] = [-1];
  let pathIndex = 0;
  
  for (let lineNum = 0; lineNum < lines.length; lineNum++) {
    const line = lines[lineNum];
    const trimmed = line.trimStart();
    
    if (!trimmed || trimmed.startsWith('#')) {
      continue;
    }
    
    const indent = line.length - trimmed.length;
    
    // Extract key, handling quoted keys
    const keyMatch = trimmed.match(/^(['"]?)([^:'"]+)\1\s*:/);
    if (!keyMatch) {
      continue;
    }
    
    const key = keyMatch[2].trim();
    const lastIndent = indentStack[indentStack.length - 1];
    
    // Check if this key is at the expected level (greater than last matched indent)
    // For the first key (pathIndex=0), accept any indent (usually 0)
    // For subsequent keys, require indent > lastIndent
    const isAtExpectedLevel = pathIndex === 0 
      ? indent >= 0 
      : indent > lastIndent;
    
    const lookingFor = pathParts[pathIndex];
    log.debug(
      `Line ${lineNum + 1}: key="${key}", indent=${indent}, lastIndent=${lastIndent}, ` +
      `isAtExpectedLevel=${isAtExpectedLevel}, lookingFor="${lookingFor}", match=${key === lookingFor}`
    );
    
    if (isAtExpectedLevel && key === pathParts[pathIndex]) {
      pathIndex++;
      indentStack.push(indent);
      log.debug(`  -> Matched! pathIndex now ${pathIndex}, indentStack:`, [...indentStack]);
      
      if (pathIndex === pathParts.length) {
        log.debug(`  -> Found complete path at line ${lineNum + 1}`);
        return lineNum + 1;
      }
    } else if (indent <= lastIndent && pathIndex > 0) {
      // We've moved back to a sibling or parent level without finding the next key
      // This means the path doesn't exist in the current structure
      // Pop the stack and try to continue from a higher level
      log.debug(`  -> Indent decreased, popping stack`);
      while (indentStack.length > 1 && indent <= indentStack[indentStack.length - 1]) {
        indentStack.pop();
        if (pathIndex > 0) {
          pathIndex--;
        }
      }
      log.debug(`  -> After pop: pathIndex=${pathIndex}, indentStack:`, [...indentStack]);
      
      // Check if this key matches at the new level
      const newLastIndent = indentStack[indentStack.length - 1];
      if (indent > newLastIndent && key === pathParts[pathIndex]) {
        pathIndex++;
        indentStack.push(indent);
        log.debug(`  -> Re-matched at new level! pathIndex now ${pathIndex}`);
        
        if (pathIndex === pathParts.length) {
          log.debug(`  -> Found complete path at line ${lineNum + 1}`);
          return lineNum + 1;
        }
      }
    }
  }
  
  log.debug('getLineForPathYaml - path not found');
  return null;
}

/**
 * Get line for path in JSON
 */
function getLineForPathJson(
  lines: string[],
  pathParts: string[]
): number | null {
  let pathIndex = 0;
  let braceDepth = 0;
  let expectedDepth = 1;
  
  for (let lineNum = 0; lineNum < lines.length; lineNum++) {
    const line = lines[lineNum];
    
    // Check for key match BEFORE counting braces on this line
    // This ensures we match keys at the correct depth
    const keyPattern = new RegExp(`"${escapeRegExp(pathParts[pathIndex])}"\\s*:`);
    const match = line.match(keyPattern);
    
    if (match && braceDepth === expectedDepth) {
      pathIndex++;
      
      if (pathIndex === pathParts.length) {
        return lineNum + 1;
      }
      
      // Only increment expected depth if this line opens a new object
      if (line.includes('{')) {
        expectedDepth++;
      }
    }
    
    // Count braces after checking for key match
    for (const char of line) {
      if (char === '{') braceDepth++;
      if (char === '}') {
        braceDepth--;
        // Adjust expected depth when closing braces
        if (expectedDepth > braceDepth + 1) {
          expectedDepth = braceDepth + 1;
        }
      }
    }
  }
  
  return null;
}

/**
 * Escape special regex characters in a string
 */
function escapeRegExp(string: string): string {
  return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Validate that content is valid YAML or JSON
 */
export function isValidContent(content: string, format: CodeFormat): boolean {
  const result = parseContent(content, format);
  return result.success;
}

/**
 * Convert content between formats
 */
export function convertFormat(
  content: string,
  fromFormat: CodeFormat,
  toFormat: CodeFormat
): ParseResult<string> {
  // Parse the source format
  const parseResult = parseContent(content, fromFormat);
  
  if (!parseResult.success) {
    return {
      success: false,
      error: parseResult.error,
    };
  }
  
  // Serialize to target format
  const serialized = serializeContent(parseResult.data, toFormat);
  
  return {
    success: true,
    data: serialized,
  };
}
