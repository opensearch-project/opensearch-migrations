/**
 * Error Mapper
 * 
 * Utilities for mapping validation errors to field paths and
 * line numbers in the code editor. This enables showing validation
 * errors as tooltips on form fields AND inline annotations in the
 * code editor.
 * 
 * This module uses shared line-finding utilities from yaml-parser.ts
 * to ensure consistent line number mapping across YAML and JSON formats.
 * All line-finding logic is centralized in yaml-parser.ts to maintain
 * a single source of truth for position tracking.
 */

import type { ValidationError, EditorAnnotation } from '../types';
import { getLineForPath } from './yaml-parser';

/**
 * Map field errors to line numbers in YAML/JSON content
 * 
 * Uses the shared line-finding utilities from yaml-parser to ensure
 * consistent line number mapping across the application.
 */
export function mapErrorsToLines(
  errors: ValidationError[],
  content: string,
  format: 'yaml' | 'json'
): ValidationError[] {
  return errors.map(error => {
    // Use shared getLineForPath utility for consistent line finding
    const line = getLineForPath(content, error.path, format);
    
    return {
      ...error,
      line: line ?? undefined,
      // Column information is not provided by getLineForPath
      // but could be added in the future if needed
      column: line ? 1 : undefined,
    };
  });
}

/**
 * Create editor annotations from field errors
 */
export function createEditorAnnotations(errors: ValidationError[]): EditorAnnotation[] {
  return errors
    .filter(error => error.line !== undefined)
    .map(error => ({
      row: (error.line || 1) - 1, // Ace editor uses 0-indexed rows
      column: (error.column || 1) - 1,
      text: error.message,
      type: 'error' as const,
    }));
}

/**
 * Get the error for a specific field path
 */
export function getErrorForPath(
  errors: ValidationError[],
  path: string
): ValidationError | undefined {
  return errors.find(e => e.path === path);
}

/**
 * Get all errors for paths that start with a prefix
 */
export function getErrorsForPrefix(
  errors: ValidationError[],
  prefix: string
): ValidationError[] {
  return errors.filter(e => 
    e.path === prefix || e.path.startsWith(prefix + '.')
  );
}

/**
 * Build a map of errors by path for quick lookup
 */
export function buildErrorMap(errors: ValidationError[]): Map<string, ValidationError> {
  const map = new Map<string, ValidationError>();
  
  for (const error of errors) {
    // Only keep the first error for each path
    if (!map.has(error.path)) {
      map.set(error.path, error);
    }
  }
  
  return map;
}

/**
 * Build a map of errors by line number for code editor
 */
export function buildErrorsByLine(errors: ValidationError[]): Map<number, ValidationError[]> {
  const map = new Map<number, ValidationError[]>();
  
  for (const error of errors) {
    if (error.line !== undefined) {
      if (!map.has(error.line)) {
        map.set(error.line, []);
      }
      map.get(error.line)!.push(error);
    }
  }
  
  return map;
}

/**
 * Format a field error for display
 */
export function formatFieldError(error: ValidationError): string {
  if (error.path) {
    return `${error.path}: ${error.message}`;
  }
  return error.message;
}

/**
 * Get a user-friendly field name from a path
 */
export function getFieldNameFromPath(path: string): string {
  const parts = path.split('.');
  const lastPart = parts[parts.length - 1];
  
  // Convert camelCase to Title Case
  return lastPart
    .replace(/([A-Z])/g, ' $1')
    .replace(/^./, str => str.toUpperCase())
    .trim();
}

/**
 * Check if there are any errors
 */
export function hasErrors(errors: ValidationError[]): boolean {
  return errors.length > 0;
}

/**
 * Count total errors
 */
export function countErrors(errors: ValidationError[]): number {
  return errors.length;
}

/**
 * Get the first error message (useful for summary display)
 */
export function getFirstErrorMessage(errors: ValidationError[]): string | undefined {
  return errors[0]?.message;
}

/**
 * Merge multiple error arrays, deduplicating by path
 */
export function mergeErrors(...errorArrays: ValidationError[][]): ValidationError[] {
  const seen = new Set<string>();
  const merged: ValidationError[] = [];
  
  for (const errors of errorArrays) {
    for (const error of errors) {
      if (!seen.has(error.path)) {
        seen.add(error.path);
        merged.push(error);
      }
    }
  }
  
  return merged;
}
