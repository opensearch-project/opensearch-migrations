/**
 * Bidirectional Sync
 * 
 * Core sync logic between form state and code content.
 * Handles converting form values to YAML/JSON and parsing
 * code content back to form state.
 */

import { z } from 'zod';
import get from 'lodash.get';
import set from 'lodash.set';
import { parseContent, serializeContent } from './yaml-parser';
import { zodErrorToFieldErrors, mapErrorsToLines } from './error-mapper';
import type { CodeFormat, FieldError, ParseError } from '../types';

/**
 * Result of syncing content to form state
 */
export interface SyncToFormResult<T = unknown> {
  success: boolean;
  data?: T;
  parseError?: ParseError;
  validationErrors: FieldError[];
}

/**
 * Result of syncing form state to content
 */
export interface SyncToContentResult {
  success: boolean;
  content: string;
  error?: string;
}

/**
 * Convert form state to code content
 */
export function formStateToContent<T extends Record<string, unknown>>(
  state: T,
  format: CodeFormat
): SyncToContentResult {
  try {
    const content = serializeContent(state, format);
    return {
      success: true,
      content,
    };
  } catch (err) {
    const error = err as Error;
    return {
      success: false,
      content: '',
      error: error.message || 'Failed to serialize form state',
    };
  }
}

/**
 * Convert code content to form state with validation
 */
export function contentToFormState<T>(
  content: string,
  format: CodeFormat,
  schema: z.ZodType<T>
): SyncToFormResult<T> {
  // First, parse the content
  const parseResult = parseContent<unknown>(content, format);
  
  if (!parseResult.success) {
    return {
      success: false,
      parseError: parseResult.error,
      validationErrors: [],
    };
  }
  
  // Then validate against schema
  const validationResult = schema.safeParse(parseResult.data);
  
  if (!validationResult.success) {
    const errors = zodErrorToFieldErrors(validationResult.error);
    const errorsWithLines = mapErrorsToLines(errors, content, format);
    
    return {
      success: false,
      data: parseResult.data as T, // Return parsed data even if invalid
      validationErrors: errorsWithLines,
    };
  }
  
  return {
    success: true,
    data: validationResult.data,
    validationErrors: [],
  };
}

/**
 * Apply a partial update to form state
 */
export function applyPartialUpdate<T extends Record<string, unknown>>(
  currentState: T,
  path: string,
  value: unknown
): T {
  // Create a deep clone to avoid mutation
  const newState = JSON.parse(JSON.stringify(currentState)) as T;
  
  // Use lodash.set to update the nested path
  set(newState, path, value);
  
  return newState;
}

/**
 * Get a value from form state by path
 */
export function getValueByPath<T extends Record<string, unknown>>(
  state: T,
  path: string
): unknown {
  return get(state, path);
}

/**
 * Check if two states are equal (deep comparison)
 */
export function areStatesEqual<T>(state1: T, state2: T): boolean {
  return JSON.stringify(state1) === JSON.stringify(state2);
}

/**
 * Merge partial state into current state
 */
export function mergeState<T extends Record<string, unknown>>(
  currentState: T,
  partialState: Partial<T>
): T {
  return {
    ...currentState,
    ...partialState,
  };
}

/**
 * Deep merge two objects
 */
export function deepMerge<T extends Record<string, unknown>>(
  target: T,
  source: Partial<T>
): T {
  const result = { ...target };
  
  for (const key of Object.keys(source) as Array<keyof T>) {
    const sourceValue = source[key];
    const targetValue = target[key];
    
    if (
      sourceValue !== undefined &&
      typeof sourceValue === 'object' &&
      sourceValue !== null &&
      !Array.isArray(sourceValue) &&
      typeof targetValue === 'object' &&
      targetValue !== null &&
      !Array.isArray(targetValue)
    ) {
      result[key] = deepMerge(
        targetValue as Record<string, unknown>,
        sourceValue as Record<string, unknown>
      ) as T[keyof T];
    } else if (sourceValue !== undefined) {
      result[key] = sourceValue as T[keyof T];
    }
  }
  
  return result;
}

/**
 * Extract changed paths between two states
 */
export function getChangedPaths<T extends Record<string, unknown>>(
  oldState: T,
  newState: T,
  basePath: string = ''
): string[] {
  const changes: string[] = [];
  
  const allKeys = new Set([
    ...Object.keys(oldState),
    ...Object.keys(newState),
  ]);
  
  for (const key of allKeys) {
    const path = basePath ? `${basePath}.${key}` : key;
    const oldValue = oldState[key];
    const newValue = newState[key];
    
    if (oldValue === newValue) {
      continue;
    }
    
    if (
      typeof oldValue === 'object' &&
      oldValue !== null &&
      !Array.isArray(oldValue) &&
      typeof newValue === 'object' &&
      newValue !== null &&
      !Array.isArray(newValue)
    ) {
      // Recurse into nested objects
      const nestedChanges = getChangedPaths(
        oldValue as Record<string, unknown>,
        newValue as Record<string, unknown>,
        path
      );
      changes.push(...nestedChanges);
    } else {
      changes.push(path);
    }
  }
  
  return changes;
}

/**
 * Create a sync handler that manages bidirectional updates
 */
export function createSyncHandler<T extends Record<string, unknown>>(
  schema: z.ZodType<T>,
  initialState: T,
  format: CodeFormat
) {
  let currentState = initialState;
  let currentContent = serializeContent(initialState, format);
  let lastChangeSource: 'form' | 'editor' | null = null;
  
  return {
    /**
     * Get current state
     */
    getState: () => currentState,
    
    /**
     * Get current content
     */
    getContent: () => currentContent,
    
    /**
     * Get last change source
     */
    getLastChangeSource: () => lastChangeSource,
    
    /**
     * Update from form field change
     */
    updateFromForm: (path: string, value: unknown): SyncToContentResult => {
      currentState = applyPartialUpdate(currentState, path, value);
      const result = formStateToContent(currentState, format);
      
      if (result.success) {
        currentContent = result.content;
        lastChangeSource = 'form';
      }
      
      return result;
    },
    
    /**
     * Update from editor content change
     */
    updateFromEditor: (content: string): SyncToFormResult<T> => {
      const result = contentToFormState(content, format, schema);
      
      if (result.success && result.data) {
        currentState = result.data;
        currentContent = content;
        lastChangeSource = 'editor';
      } else if (result.data) {
        // Even if validation failed, update content
        currentContent = content;
        lastChangeSource = 'editor';
      }
      
      return result;
    },
    
    /**
     * Change format (yaml <-> json)
     */
    changeFormat: (newFormat: CodeFormat): SyncToContentResult => {
      format = newFormat;
      const result = formStateToContent(currentState, format);
      
      if (result.success) {
        currentContent = result.content;
      }
      
      return result;
    },
    
    /**
     * Reset to initial state
     */
    reset: () => {
      currentState = initialState;
      currentContent = serializeContent(initialState, format);
      lastChangeSource = null;
    },
  };
}

/**
 * Validate content against schema and return errors with line numbers
 */
export function validateContent<T>(
  content: string,
  format: CodeFormat,
  schema: z.ZodType<T>
): FieldError[] {
  const parseResult = parseContent<unknown>(content, format);
  
  if (!parseResult.success) {
    // Return parse error as a field error
    return [{
      path: '',
      message: parseResult.error?.message || 'Parse error',
      code: 'parse_error',
      line: parseResult.error?.line,
      column: parseResult.error?.column,
    }];
  }
  
  const validationResult = schema.safeParse(parseResult.data);
  
  if (!validationResult.success) {
    const errors = zodErrorToFieldErrors(validationResult.error);
    return mapErrorsToLines(errors, content, format);
  }
  
  return [];
}
