/**
 * useJsonSchemaForm Hook Tests
 * 
 * Tests for the JSON Schema form hook, specifically focusing on
 * bidirectional sync between form values and code editor content.
 */

import { describe, it, expect, vi } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useJsonSchemaForm } from './useJsonSchemaForm';
import testSchema from '../__tests__/fixtures/test-workflow-schema.json';
import type { JSONSchema7 } from '../types';

// Cast the imported JSON to JSONSchema7
const schema = testSchema as unknown as JSONSchema7;

// Initial values for testing
const initialValues = {
  name: 'Test Workflow',
  description: 'A test workflow',
  enabled: true,
  priority: 5,
  environment: 'development',
  tags: ['test'],
  config: {
    timeout: 300,
    retries: 3,
    notifyOnComplete: false,
  },
  steps: [
    { name: 'Step 1', action: 'run', parameters: {} },
  ],
  metadata: {},
};

describe('useJsonSchemaForm', () => {
  describe('initialization', () => {
    it('initializes with provided values', () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      expect(result.current.values.name).toBe('Test Workflow');
      expect(result.current.values.enabled).toBe(true);
      expect(result.current.values.priority).toBe(5);
    });

    it('initializes content in YAML format by default', () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      expect(result.current.format).toBe('yaml');
      expect(result.current.content).toContain('name: Test Workflow');
    });

    it('initializes content in JSON format when specified', () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
          initialFormat: 'json',
        })
      );

      expect(result.current.format).toBe('json');
      expect(result.current.content).toContain('"name": "Test Workflow"');
    });
  });

  describe('bidirectional sync: form → editor', () => {
    it('updates content when setValue is called', async () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      act(() => {
        result.current.setValue('name', 'Updated Workflow');
      });

      expect(result.current.values.name).toBe('Updated Workflow');
      expect(result.current.content).toContain('name: Updated Workflow');
    });

    it('updates content when setValues is called', async () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      act(() => {
        result.current.setValues({
          name: 'New Name',
          priority: 8,
        });
      });

      expect(result.current.values.name).toBe('New Name');
      expect(result.current.values.priority).toBe(8);
      expect(result.current.content).toContain('name: New Name');
      expect(result.current.content).toContain('priority: 8');
    });

    it('sets lastChangeSource to form when setValue is called', () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      act(() => {
        result.current.setValue('name', 'Changed');
      });

      expect(result.current.lastChangeSource).toBe('form');
    });
  });

  describe('bidirectional sync: editor → form', () => {
    it('updates values when setContent is called with valid YAML', async () => {
      const onChange = vi.fn();
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
          onChange,
        })
      );

      const newYamlContent = `name: Updated From Editor
description: A test workflow
enabled: true
priority: 7
environment: staging
tags:
  - test
config:
  timeout: 300
  retries: 3
  notifyOnComplete: false
steps:
  - name: Step 1
    action: run
    parameters: {}
metadata: {}`;

      act(() => {
        result.current.setContent(newYamlContent);
      });

      // Values should be updated from the editor content
      expect(result.current.values.name).toBe('Updated From Editor');
      expect(result.current.values.priority).toBe(7);
      expect(result.current.values.environment).toBe('staging');
      
      // onChange should be called with the new values
      expect(onChange).toHaveBeenCalled();
      const lastCall = onChange.mock.calls[onChange.mock.calls.length - 1][0];
      expect(lastCall.name).toBe('Updated From Editor');
    });

    it('updates values when setContent is called with valid JSON', async () => {
      const onChange = vi.fn();
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
          initialFormat: 'json',
          onChange,
        })
      );

      const newJsonContent = JSON.stringify({
        name: 'JSON Updated',
        description: 'Updated via JSON',
        enabled: false,
        priority: 3,
        environment: 'production',
        tags: ['json', 'test'],
        config: {
          timeout: 600,
          retries: 5,
          notifyOnComplete: true,
        },
        steps: [
          { name: 'Step 1', action: 'wait', parameters: {} },
        ],
        metadata: {},
      }, null, 2);

      act(() => {
        result.current.setContent(newJsonContent);
      });

      // Values should be updated from the editor content
      expect(result.current.values.name).toBe('JSON Updated');
      expect(result.current.values.enabled).toBe(false);
      expect(result.current.values.priority).toBe(3);
      expect(result.current.values.environment).toBe('production');
      expect(result.current.values.config.timeout).toBe(600);
      
      // onChange should be called
      expect(onChange).toHaveBeenCalled();
    });

    it('sets lastChangeSource to editor when setContent is called', () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      const newContent = `name: Editor Change
description: test
enabled: true
priority: 5
environment: development
tags: []
config:
  timeout: 300
  retries: 3
  notifyOnComplete: false
steps:
  - name: Step 1
    action: run
    parameters: {}
metadata: {}`;

      act(() => {
        result.current.setContent(newContent);
      });

      expect(result.current.lastChangeSource).toBe('editor');
    });

    it('handles parse errors gracefully', () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      const invalidYaml = `name: Test
  invalid: yaml: content:
    - broken`;

      act(() => {
        result.current.setContent(invalidYaml);
      });

      // Should have a parse error
      expect(result.current.parseError).toBeTruthy();
      expect(result.current.isValid).toBe(false);
    });

    it('handles validation errors from editor content', async () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      // Content with invalid priority (out of range 1-10)
      const invalidContent = `name: Test
description: test
enabled: true
priority: 100
environment: development
tags: []
config:
  timeout: 300
  retries: 3
  notifyOnComplete: false
steps:
  - name: Step 1
    action: run
    parameters: {}
metadata: {}`;

      act(() => {
        result.current.setContent(invalidContent);
      });

      // Should have validation errors
      await waitFor(() => {
        expect(result.current.errors.length).toBeGreaterThan(0);
      });
    });
  });

  describe('format switching', () => {
    it('converts content when format changes from YAML to JSON', () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
          initialFormat: 'yaml',
        })
      );

      expect(result.current.format).toBe('yaml');
      expect(result.current.content).toContain('name: Test Workflow');

      act(() => {
        result.current.setFormat('json');
      });

      expect(result.current.format).toBe('json');
      expect(result.current.content).toContain('"name": "Test Workflow"');
    });

    it('converts content when format changes from JSON to YAML', () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
          initialFormat: 'json',
        })
      );

      expect(result.current.format).toBe('json');

      act(() => {
        result.current.setFormat('yaml');
      });

      expect(result.current.format).toBe('yaml');
      expect(result.current.content).toContain('name: Test Workflow');
    });

    it('preserves values when switching formats', () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      // Change a value
      act(() => {
        result.current.setValue('name', 'Modified Name');
      });

      // Switch format
      act(() => {
        result.current.setFormat('json');
      });

      // Value should be preserved
      expect(result.current.values.name).toBe('Modified Name');
      expect(result.current.content).toContain('"name": "Modified Name"');
    });
  });

  describe('reset functionality', () => {
    it('resets values to initial state', () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      // Modify values
      act(() => {
        result.current.setValue('name', 'Modified');
        result.current.setValue('priority', 10);
      });

      expect(result.current.values.name).toBe('Modified');
      expect(result.current.isDirty).toBe(true);

      // Reset
      act(() => {
        result.current.reset();
      });

      expect(result.current.values.name).toBe('Test Workflow');
      expect(result.current.values.priority).toBe(5);
      expect(result.current.isDirty).toBe(false);
    });

    it('resets content to initial state', () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      // Modify via editor
      act(() => {
        result.current.setContent(`name: Changed
description: test
enabled: true
priority: 5
environment: development
tags: []
config:
  timeout: 300
  retries: 3
  notifyOnComplete: false
steps:
  - name: Step 1
    action: run
    parameters: {}
metadata: {}`);
      });

      expect(result.current.values.name).toBe('Changed');

      // Reset
      act(() => {
        result.current.reset();
      });

      expect(result.current.values.name).toBe('Test Workflow');
      expect(result.current.content).toContain('name: Test Workflow');
    });

    it('clears errors on reset', async () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      // Create an error by setting invalid content
      act(() => {
        result.current.setContent('invalid: yaml: content');
      });

      expect(result.current.parseError).toBeTruthy();

      // Reset
      act(() => {
        result.current.reset();
      });

      expect(result.current.parseError).toBeNull();
      expect(result.current.errors).toHaveLength(0);
    });
  });

  describe('onChange callback', () => {
    it('calls onChange when form values change', () => {
      const onChange = vi.fn();
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
          onChange,
        })
      );

      act(() => {
        result.current.setValue('name', 'New Name');
      });

      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'New Name' })
      );
    });

    it('calls onChange when editor content changes', () => {
      const onChange = vi.fn();
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
          onChange,
        })
      );

      const newContent = `name: Editor Name
description: test
enabled: true
priority: 5
environment: development
tags: []
config:
  timeout: 300
  retries: 3
  notifyOnComplete: false
steps:
  - name: Step 1
    action: run
    parameters: {}
metadata: {}`;

      act(() => {
        result.current.setContent(newContent);
      });

      expect(onChange).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'Editor Name' })
      );
    });
  });

  describe('validation', () => {
    it('validates on form changes', async () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
          validationDelay: 0, // Immediate validation for testing
        })
      );

      // Initially valid
      expect(result.current.isValid).toBe(true);

      // Make an invalid change (empty name when required)
      act(() => {
        result.current.setValue('name', '');
      });

      // Wait for validation
      await waitFor(() => {
        expect(result.current.errors.length).toBeGreaterThan(0);
      });
    });

    it('manual validate returns correct result', () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      // Valid state
      let isValid: boolean;
      act(() => {
        isValid = result.current.validate();
      });
      expect(isValid!).toBe(true);

      // Make invalid
      act(() => {
        result.current.setValue('name', '');
      });

      act(() => {
        isValid = result.current.validate();
      });
      expect(isValid!).toBe(false);
    });
  });

  describe('field helpers', () => {
    it('getFieldValue returns correct value', () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      expect(result.current.getFieldValue('name')).toBe('Test Workflow');
      expect(result.current.getFieldValue('priority')).toBe(5);
      expect(result.current.getFieldValue('config.timeout')).toBe(300);
    });

    it('getFieldError returns error message', async () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
          validationDelay: 0,
        })
      );

      // Make invalid
      act(() => {
        result.current.setValue('name', '');
      });

      // Wait for validation
      await waitFor(() => {
        const error = result.current.getFieldError('name');
        expect(error).toBeDefined();
      });
    });
  });

  describe('dirty state', () => {
    it('isDirty is false initially', () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      expect(result.current.isDirty).toBe(false);
    });

    it('isDirty is true after form change', () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      act(() => {
        result.current.setValue('name', 'Changed');
      });

      expect(result.current.isDirty).toBe(true);
    });

    it('isDirty is true after editor change', () => {
      const { result } = renderHook(() =>
        useJsonSchemaForm({
          jsonSchema: schema,
          initialValues,
        })
      );

      act(() => {
        result.current.setContent(`name: Changed
description: test
enabled: true
priority: 5
environment: development
tags: []
config:
  timeout: 300
  retries: 3
  notifyOnComplete: false
steps:
  - name: Step 1
    action: run
    parameters: {}
metadata: {}`);
      });

      expect(result.current.isDirty).toBe(true);
    });
  });
});
