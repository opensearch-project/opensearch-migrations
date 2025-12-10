/**
 * Error Mapper Tests
 * 
 * Tests for error mapping utilities including line number calculation
 * and editor annotation creation.
 */

import { describe, it, expect } from 'vitest';
import {
  zodErrorToFieldErrors,
  mapErrorsToLines,
  createEditorAnnotations,
  getErrorForPath,
  getErrorsForPrefix,
  buildErrorMap,
  buildErrorsByLine,
  formatFieldError,
  getFieldNameFromPath,
  hasErrors,
  countErrors,
  getFirstErrorMessage,
  mergeErrors,
} from './error-mapper';
import { z } from 'zod';
import type { FieldError } from '../types';

describe('error-mapper', () => {
  describe('zodErrorToFieldErrors', () => {
    it('converts Zod errors to FieldError array', () => {
      const schema = z.object({
        name: z.string().min(1),
        age: z.number().min(0),
      });

      const result = schema.safeParse({ name: '', age: -1 });
      expect(result.success).toBe(false);

      if (!result.success) {
        const fieldErrors = zodErrorToFieldErrors(result.error);
        expect(fieldErrors.length).toBeGreaterThan(0);
        expect(fieldErrors.some(e => e.path === 'name')).toBe(true);
        expect(fieldErrors.some(e => e.path === 'age')).toBe(true);
      }
    });

    it('handles nested paths', () => {
      const schema = z.object({
        config: z.object({
          timeout: z.number().min(0),
        }),
      });

      const result = schema.safeParse({ config: { timeout: -1 } });
      expect(result.success).toBe(false);

      if (!result.success) {
        const fieldErrors = zodErrorToFieldErrors(result.error);
        expect(fieldErrors.some(e => e.path === 'config.timeout')).toBe(true);
      }
    });
  });

  describe('mapErrorsToLines', () => {
    it('maps errors to correct line numbers in YAML', () => {
      const yamlContent = `name: Test
description: A description
priority: 5`;

      const errors: FieldError[] = [
        { path: 'name', message: 'Name is required', code: 'custom' },
        { path: 'description', message: 'Description too short', code: 'custom' },
        { path: 'priority', message: 'Priority out of range', code: 'custom' },
      ];

      const mappedErrors = mapErrorsToLines(errors, yamlContent, 'yaml');

      // name is on line 1
      expect(mappedErrors.find(e => e.path === 'name')?.line).toBe(1);
      // description is on line 2
      expect(mappedErrors.find(e => e.path === 'description')?.line).toBe(2);
      // priority is on line 3
      expect(mappedErrors.find(e => e.path === 'priority')?.line).toBe(3);
    });

    it('maps errors to correct line numbers in JSON', () => {
      const jsonContent = `{
  "name": "Test",
  "description": "A description",
  "priority": 5
}`;

      const errors: FieldError[] = [
        { path: 'name', message: 'Name is required', code: 'custom' },
        { path: 'description', message: 'Description too short', code: 'custom' },
        { path: 'priority', message: 'Priority out of range', code: 'custom' },
      ];

      const mappedErrors = mapErrorsToLines(errors, jsonContent, 'json');

      // name is on line 2 (after opening brace)
      expect(mappedErrors.find(e => e.path === 'name')?.line).toBe(2);
      // description is on line 3
      expect(mappedErrors.find(e => e.path === 'description')?.line).toBe(3);
      // priority is on line 4
      expect(mappedErrors.find(e => e.path === 'priority')?.line).toBe(4);
    });

    it('handles nested paths in YAML', () => {
      const yamlContent = `name: Test
config:
  timeout: 300
  retries: 3`;

      const errors: FieldError[] = [
        { path: 'config.timeout', message: 'Timeout too high', code: 'custom' },
        { path: 'config.retries', message: 'Too many retries', code: 'custom' },
      ];

      const mappedErrors = mapErrorsToLines(errors, yamlContent, 'yaml');

      // config.timeout is on line 3
      expect(mappedErrors.find(e => e.path === 'config.timeout')?.line).toBe(3);
      // config.retries is on line 4
      expect(mappedErrors.find(e => e.path === 'config.retries')?.line).toBe(4);
    });
  });

  describe('createEditorAnnotations', () => {
    it('creates annotations with correct 0-indexed row numbers', () => {
      const errors: FieldError[] = [
        { path: 'name', message: 'Name is required', code: 'custom', line: 1, column: 1 },
        { path: 'priority', message: 'Priority out of range', code: 'custom', line: 3, column: 1 },
      ];

      const annotations = createEditorAnnotations(errors);

      // Line 1 should become row 0 (0-indexed)
      expect(annotations[0].row).toBe(0);
      expect(annotations[0].text).toBe('Name is required');

      // Line 3 should become row 2 (0-indexed)
      expect(annotations[1].row).toBe(2);
      expect(annotations[1].text).toBe('Priority out of range');
    });

    it('filters out errors without line numbers', () => {
      const errors: FieldError[] = [
        { path: 'name', message: 'Name is required', code: 'custom', line: 1 },
        { path: 'other', message: 'Other error', code: 'custom' }, // No line number
      ];

      const annotations = createEditorAnnotations(errors);

      expect(annotations.length).toBe(1);
      expect(annotations[0].text).toBe('Name is required');
    });

    it('sets correct column values (0-indexed)', () => {
      const errors: FieldError[] = [
        { path: 'name', message: 'Error', code: 'custom', line: 1, column: 5 },
      ];

      const annotations = createEditorAnnotations(errors);

      // Column 5 should become column 4 (0-indexed)
      expect(annotations[0].column).toBe(4);
    });

    it('defaults column to 0 when not provided', () => {
      const errors: FieldError[] = [
        { path: 'name', message: 'Error', code: 'custom', line: 1 },
      ];

      const annotations = createEditorAnnotations(errors);

      // Default column (1) should become 0 (0-indexed)
      expect(annotations[0].column).toBe(0);
    });
  });

  describe('getErrorForPath', () => {
    it('returns error for exact path match', () => {
      const errors: FieldError[] = [
        { path: 'name', message: 'Name error', code: 'custom' },
        { path: 'age', message: 'Age error', code: 'custom' },
      ];

      const error = getErrorForPath(errors, 'name');
      expect(error?.message).toBe('Name error');
    });

    it('returns undefined for non-existent path', () => {
      const errors: FieldError[] = [
        { path: 'name', message: 'Name error', code: 'custom' },
      ];

      const error = getErrorForPath(errors, 'other');
      expect(error).toBeUndefined();
    });
  });

  describe('getErrorsForPrefix', () => {
    it('returns errors matching prefix', () => {
      const errors: FieldError[] = [
        { path: 'config', message: 'Config error', code: 'custom' },
        { path: 'config.timeout', message: 'Timeout error', code: 'custom' },
        { path: 'config.retries', message: 'Retries error', code: 'custom' },
        { path: 'name', message: 'Name error', code: 'custom' },
      ];

      const configErrors = getErrorsForPrefix(errors, 'config');
      expect(configErrors.length).toBe(3);
      expect(configErrors.every(e => e.path.startsWith('config'))).toBe(true);
    });
  });

  describe('buildErrorMap', () => {
    it('builds map with first error for each path', () => {
      const errors: FieldError[] = [
        { path: 'name', message: 'First error', code: 'custom' },
        { path: 'name', message: 'Second error', code: 'custom' },
        { path: 'age', message: 'Age error', code: 'custom' },
      ];

      const map = buildErrorMap(errors);
      expect(map.size).toBe(2);
      expect(map.get('name')?.message).toBe('First error');
      expect(map.get('age')?.message).toBe('Age error');
    });
  });

  describe('buildErrorsByLine', () => {
    it('groups errors by line number', () => {
      const errors: FieldError[] = [
        { path: 'name', message: 'Name error', code: 'custom', line: 1 },
        { path: 'alias', message: 'Alias error', code: 'custom', line: 1 },
        { path: 'age', message: 'Age error', code: 'custom', line: 3 },
      ];

      const byLine = buildErrorsByLine(errors);
      expect(byLine.get(1)?.length).toBe(2);
      expect(byLine.get(3)?.length).toBe(1);
    });

    it('ignores errors without line numbers', () => {
      const errors: FieldError[] = [
        { path: 'name', message: 'Name error', code: 'custom', line: 1 },
        { path: 'other', message: 'Other error', code: 'custom' },
      ];

      const byLine = buildErrorsByLine(errors);
      expect(byLine.size).toBe(1);
    });
  });

  describe('formatFieldError', () => {
    it('formats error with path', () => {
      const error: FieldError = { path: 'name', message: 'is required', code: 'custom' };
      expect(formatFieldError(error)).toBe('name: is required');
    });

    it('formats error without path', () => {
      const error: FieldError = { path: '', message: 'General error', code: 'custom' };
      expect(formatFieldError(error)).toBe('General error');
    });
  });

  describe('getFieldNameFromPath', () => {
    it('extracts last part of path', () => {
      expect(getFieldNameFromPath('config.timeout')).toBe('Timeout');
      expect(getFieldNameFromPath('name')).toBe('Name');
    });

    it('converts camelCase to Title Case', () => {
      expect(getFieldNameFromPath('notifyOnComplete')).toBe('Notify On Complete');
      expect(getFieldNameFromPath('maxRetries')).toBe('Max Retries');
    });
  });

  describe('hasErrors', () => {
    it('returns true when errors exist', () => {
      expect(hasErrors([{ path: 'name', message: 'Error', code: 'custom' }])).toBe(true);
    });

    it('returns false when no errors', () => {
      expect(hasErrors([])).toBe(false);
    });
  });

  describe('countErrors', () => {
    it('returns correct count', () => {
      const errors: FieldError[] = [
        { path: 'a', message: 'Error', code: 'custom' },
        { path: 'b', message: 'Error', code: 'custom' },
        { path: 'c', message: 'Error', code: 'custom' },
      ];
      expect(countErrors(errors)).toBe(3);
    });
  });

  describe('getFirstErrorMessage', () => {
    it('returns first error message', () => {
      const errors: FieldError[] = [
        { path: 'a', message: 'First', code: 'custom' },
        { path: 'b', message: 'Second', code: 'custom' },
      ];
      expect(getFirstErrorMessage(errors)).toBe('First');
    });

    it('returns undefined for empty array', () => {
      expect(getFirstErrorMessage([])).toBeUndefined();
    });
  });

  describe('mergeErrors', () => {
    it('merges multiple error arrays', () => {
      const errors1: FieldError[] = [{ path: 'a', message: 'Error A', code: 'custom' }];
      const errors2: FieldError[] = [{ path: 'b', message: 'Error B', code: 'custom' }];

      const merged = mergeErrors(errors1, errors2);
      expect(merged.length).toBe(2);
    });

    it('deduplicates by path', () => {
      const errors1: FieldError[] = [{ path: 'a', message: 'First', code: 'custom' }];
      const errors2: FieldError[] = [{ path: 'a', message: 'Second', code: 'custom' }];

      const merged = mergeErrors(errors1, errors2);
      expect(merged.length).toBe(1);
      expect(merged[0].message).toBe('First');
    });
  });

  describe('end-to-end annotation flow', () => {
    it('annotations should have correct row for Ace editor (0-indexed, no double subtraction)', () => {
      // This test verifies the full flow from YAML content to Ace editor annotations
      // The issue was that createEditorAnnotations converts to 0-indexed rows,
      // but EditableCodePanel was subtracting 1 again, causing off-by-one errors.
      
      const yamlContent = `name: Test
description: A description
priority: invalid`;

      const errors: FieldError[] = [
        { path: 'priority', message: 'Priority must be a number', code: 'custom' },
      ];

      // Step 1: Map errors to lines
      const mappedErrors = mapErrorsToLines(errors, yamlContent, 'yaml');
      
      // priority is on line 3 (1-indexed)
      expect(mappedErrors[0].line).toBe(3);

      // Step 2: Create editor annotations (converts to 0-indexed)
      const annotations = createEditorAnnotations(mappedErrors);
      
      // Line 3 should become row 2 (0-indexed for Ace)
      // This is the final row value that should be passed to Ace
      expect(annotations[0].row).toBe(2);
      
      // The annotation row should be ready for Ace editor without further modification
      // If EditableCodePanel subtracts 1 again, the error would appear on row 1 (line 2)
      // instead of row 2 (line 3) where it should be
    });

    it('first line error should have row 0 for Ace editor', () => {
      const yamlContent = `name: 
description: A description`;

      const errors: FieldError[] = [
        { path: 'name', message: 'Name is required', code: 'custom' },
      ];

      const mappedErrors = mapErrorsToLines(errors, yamlContent, 'yaml');
      expect(mappedErrors[0].line).toBe(1);

      const annotations = createEditorAnnotations(mappedErrors);
      
      // Line 1 should become row 0 (0-indexed for Ace)
      expect(annotations[0].row).toBe(0);
      
      // If EditableCodePanel subtracts 1 again, this would become row -1
      // which would cause the annotation to not display or display incorrectly
    });
  });
});
