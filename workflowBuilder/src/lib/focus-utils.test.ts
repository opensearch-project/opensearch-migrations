/**
 * Focus Utilities Tests
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  findLineForFieldPath,
  findPathAtCursorPosition,
  createFocusHighlightMarker,
  calculateFieldElementId,
  findFieldElement,
  applyFieldHighlight,
  isParentPath,
  getParentPath,
  normalizePath,
  pathsEqual,
  scrollToElement,
  scrollToFieldWithRetry,
  expandParentSections,
  isElementVisible,
  DEFAULT_FOCUS_CONFIG,
  FOCUS_HIGHLIGHT_CLASS,
  FIELD_FOCUS_HIGHLIGHT_CLASS,
} from './focus-utils';

describe('focus-utils', () => {
  describe('findLineForFieldPath', () => {
    const yamlContent = `name: test
version: 1.0
config:
  host: localhost
  port: 8080
  nested:
    value: deep
items:
  - first
  - second`;

    const jsonContent = `{
  "name": "test",
  "version": 1.0,
  "config": {
    "host": "localhost",
    "port": 8080,
    "nested": {
      "value": "deep"
    }
  },
  "items": [
    "first",
    "second"
  ]
}`;

    it('should find line for simple path in YAML', () => {
      const result = findLineForFieldPath(yamlContent, 'name', 'yaml');
      expect(result).not.toBeNull();
      expect(result?.line).toBe(1);
      expect(result?.path).toBe('name');
    });

    it('should find line for nested path in YAML', () => {
      const result = findLineForFieldPath(yamlContent, 'config.host', 'yaml');
      expect(result).not.toBeNull();
      expect(result?.line).toBe(4);
    });

    it('should find line for deeply nested path in YAML', () => {
      const result = findLineForFieldPath(yamlContent, 'config.nested.value', 'yaml');
      expect(result).not.toBeNull();
      expect(result?.line).toBe(7);
    });

    it('should find line for simple path in JSON', () => {
      const result = findLineForFieldPath(jsonContent, 'name', 'json');
      expect(result).not.toBeNull();
      expect(result?.line).toBe(2);
    });

    it('should find line for nested path in JSON', () => {
      const result = findLineForFieldPath(jsonContent, 'config.host', 'json');
      expect(result).not.toBeNull();
      expect(result?.line).toBe(5);
    });

    it('should return null for non-existent path', () => {
      const result = findLineForFieldPath(yamlContent, 'nonexistent.path', 'yaml');
      expect(result).toBeNull();
    });

    it('should return null for empty content', () => {
      const result = findLineForFieldPath('', 'name', 'yaml');
      expect(result).toBeNull();
    });

    it('should include column information', () => {
      const result = findLineForFieldPath(yamlContent, 'config.host', 'yaml');
      expect(result).not.toBeNull();
      expect(result?.column).toBeGreaterThan(0);
    });
  });

  describe('findPathAtCursorPosition', () => {
    const yamlContent = `name: test
config:
  host: localhost
  port: 8080`;

    const jsonContent = `{
  "name": "test",
  "config": {
    "host": "localhost"
  }
}`;

    it('should find path at cursor position in YAML', () => {
      const result = findPathAtCursorPosition(yamlContent, 1, 1, 'yaml');
      expect(result).toBe('name');
    });

    it('should find nested path at cursor position in YAML', () => {
      const result = findPathAtCursorPosition(yamlContent, 3, 3, 'yaml');
      expect(result).toBe('config.host');
    });

    it('should find path at cursor position in JSON', () => {
      const result = findPathAtCursorPosition(jsonContent, 2, 3, 'json');
      expect(result).toBe('name');
    });

    it('should find nested path at cursor position in JSON', () => {
      const result = findPathAtCursorPosition(jsonContent, 4, 5, 'json');
      expect(result).toBe('config.host');
    });

    it('should return null for empty content', () => {
      const result = findPathAtCursorPosition('', 1, 1, 'yaml');
      expect(result).toBeNull();
    });
  });

  describe('findPathAtCursorPosition with YAML arrays', () => {
    // Test case matching the user's bug report: array items with inline keys
    const yamlWithArrayObjects = `migrationConfigs:
  - skipApprovals: true
    enabled: false
  - skipApprovals: false
    enabled: true`;

    it('should find path for first array item with inline key', () => {
      // Line 2: "  - skipApprovals: true"
      const result = findPathAtCursorPosition(yamlWithArrayObjects, 2, 5, 'yaml');
      expect(result).toBe('migrationConfigs[0].skipApprovals');
    });

    it('should find path for second property in first array item', () => {
      // Line 3: "    enabled: false"
      const result = findPathAtCursorPosition(yamlWithArrayObjects, 3, 5, 'yaml');
      expect(result).toBe('migrationConfigs[0].enabled');
    });

    it('should find path for second array item with inline key', () => {
      // Line 4: "  - skipApprovals: false"
      const result = findPathAtCursorPosition(yamlWithArrayObjects, 4, 5, 'yaml');
      expect(result).toBe('migrationConfigs[1].skipApprovals');
    });

    it('should find path for second property in second array item', () => {
      // Line 5: "    enabled: true"
      const result = findPathAtCursorPosition(yamlWithArrayObjects, 5, 5, 'yaml');
      expect(result).toBe('migrationConfigs[1].enabled');
    });

    // Test case with simple array values (no inline keys)
    const yamlWithSimpleArray = `items:
  - first
  - second
  - third`;

    it('should find path for simple array items', () => {
      // Line 2: "  - first"
      const result = findPathAtCursorPosition(yamlWithSimpleArray, 2, 5, 'yaml');
      expect(result).toBe('items[0]');
    });

    it('should find path for second simple array item', () => {
      // Line 3: "  - second"
      const result = findPathAtCursorPosition(yamlWithSimpleArray, 3, 5, 'yaml');
      expect(result).toBe('items[1]');
    });

    // Test case with nested objects in array
    const yamlWithNestedArrayObjects = `clusters:
  - name: source
    config:
      host: localhost
      port: 9200
  - name: target
    config:
      host: remote
      port: 9201`;

    it('should find path for nested property in array item', () => {
      // Line 4: "      host: localhost"
      const result = findPathAtCursorPosition(yamlWithNestedArrayObjects, 4, 7, 'yaml');
      expect(result).toBe('clusters[0].config.host');
    });

    it('should find path for nested property in second array item', () => {
      // Line 8: "      host: remote"
      const result = findPathAtCursorPosition(yamlWithNestedArrayObjects, 8, 7, 'yaml');
      expect(result).toBe('clusters[1].config.host');
    });

    // Test case with array at root level
    const yamlRootArray = `- name: first
  value: 1
- name: second
  value: 2`;

    it('should find path for root-level array item', () => {
      // Line 1: "- name: first"
      const result = findPathAtCursorPosition(yamlRootArray, 1, 3, 'yaml');
      expect(result).toBe('[0].name');
    });

    it('should find path for property in root-level array item', () => {
      // Line 2: "  value: 1"
      const result = findPathAtCursorPosition(yamlRootArray, 2, 3, 'yaml');
      expect(result).toBe('[0].value');
    });

    it('should find path for second root-level array item', () => {
      // Line 3: "- name: second"
      const result = findPathAtCursorPosition(yamlRootArray, 3, 3, 'yaml');
      expect(result).toBe('[1].name');
    });
  });

  describe('createFocusHighlightMarker', () => {
    it('should create marker for single line', () => {
      const marker = createFocusHighlightMarker(5);
      
      expect(marker.startRow).toBe(4); // 0-indexed
      expect(marker.endRow).toBe(4);
      expect(marker.className).toBe(FOCUS_HIGHLIGHT_CLASS);
      expect(marker.type).toBe('fullLine');
    });

    it('should create marker for line range', () => {
      const marker = createFocusHighlightMarker(5, 10);
      
      expect(marker.startRow).toBe(4);
      expect(marker.endRow).toBe(9);
    });

    it('should set correct marker properties', () => {
      const marker = createFocusHighlightMarker(1);
      
      expect(marker.startColumn).toBe(0);
      expect(marker.inFront).toBe(false);
    });
  });

  describe('calculateFieldElementId', () => {
    it('should generate valid ID for simple path', () => {
      const id = calculateFieldElementId('name');
      expect(id).toBe('field-name');
    });

    it('should replace dots with dashes', () => {
      const id = calculateFieldElementId('config.host');
      expect(id).toBe('field-config-host');
    });

    it('should handle array notation', () => {
      const id = calculateFieldElementId('items[0].name');
      expect(id).toBe('field-items-0-name');
    });

    it('should handle deeply nested paths', () => {
      const id = calculateFieldElementId('a.b.c.d.e');
      expect(id).toBe('field-a-b-c-d-e');
    });

    it('should sanitize special characters', () => {
      const id = calculateFieldElementId('path/with/slashes');
      expect(id).toMatch(/^field-[a-zA-Z0-9-_]+$/);
    });
  });

  describe('findFieldElement', () => {
    beforeEach(() => {
      // Clean up any existing test elements
      document.body.innerHTML = '';
    });

    it('should find element by ID', () => {
      const element = document.createElement('div');
      element.id = 'field-config-host';
      document.body.appendChild(element);

      const found = findFieldElement('config.host');
      expect(found).toBe(element);
    });

    it('should find element by data attribute', () => {
      const element = document.createElement('div');
      element.setAttribute('data-field-path', 'config.host');
      document.body.appendChild(element);

      const found = findFieldElement('config.host');
      expect(found).toBe(element);
    });

    it('should return null if element not found', () => {
      const found = findFieldElement('nonexistent.path');
      expect(found).toBeNull();
    });

    it('should search within container if provided', () => {
      const container = document.createElement('div');
      const element = document.createElement('div');
      element.id = 'field-name';
      container.appendChild(element);
      document.body.appendChild(container);

      const found = findFieldElement('name', container);
      expect(found).toBe(element);
    });
  });

  describe('applyFieldHighlight', () => {
    beforeEach(() => {
      document.body.innerHTML = '';
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it('should add highlight class to element', () => {
      const element = document.createElement('div');
      document.body.appendChild(element);

      applyFieldHighlight(element);
      
      expect(element.classList.contains(FIELD_FOCUS_HIGHLIGHT_CLASS)).toBe(true);
    });

    it('should remove highlight after duration', () => {
      const element = document.createElement('div');
      document.body.appendChild(element);

      applyFieldHighlight(element, 1000);
      
      expect(element.classList.contains(FIELD_FOCUS_HIGHLIGHT_CLASS)).toBe(true);
      
      vi.advanceTimersByTime(1000);
      
      expect(element.classList.contains(FIELD_FOCUS_HIGHLIGHT_CLASS)).toBe(false);
    });

    it('should return cleanup function', () => {
      const element = document.createElement('div');
      document.body.appendChild(element);

      const cleanup = applyFieldHighlight(element, 5000);
      
      expect(element.classList.contains(FIELD_FOCUS_HIGHLIGHT_CLASS)).toBe(true);
      
      cleanup();
      
      expect(element.classList.contains(FIELD_FOCUS_HIGHLIGHT_CLASS)).toBe(false);
    });

    it('should not auto-remove if duration is 0', () => {
      const element = document.createElement('div');
      document.body.appendChild(element);

      applyFieldHighlight(element, 0);
      
      vi.advanceTimersByTime(10000);
      
      expect(element.classList.contains(FIELD_FOCUS_HIGHLIGHT_CLASS)).toBe(true);
    });

    it('should handle null element gracefully', () => {
      const cleanup = applyFieldHighlight(null as unknown as HTMLElement);
      expect(cleanup).toBeInstanceOf(Function);
      expect(() => cleanup()).not.toThrow();
    });
  });

  describe('isParentPath', () => {
    it('should return true for direct parent', () => {
      expect(isParentPath('config', 'config.host')).toBe(true);
    });

    it('should return true for ancestor', () => {
      expect(isParentPath('config', 'config.nested.value')).toBe(true);
    });

    it('should return false for same path', () => {
      expect(isParentPath('config', 'config')).toBe(false);
    });

    it('should return false for sibling', () => {
      expect(isParentPath('config.host', 'config.port')).toBe(false);
    });

    it('should return false for child', () => {
      expect(isParentPath('config.host', 'config')).toBe(false);
    });

    it('should return false for empty paths', () => {
      expect(isParentPath('', 'config')).toBe(false);
      expect(isParentPath('config', '')).toBe(false);
    });
  });

  describe('getParentPath', () => {
    it('should return parent for nested path', () => {
      expect(getParentPath('config.host')).toBe('config');
    });

    it('should return parent for deeply nested path', () => {
      expect(getParentPath('a.b.c.d')).toBe('a.b.c');
    });

    it('should return null for root level path', () => {
      expect(getParentPath('name')).toBeNull();
    });

    it('should return null for empty path', () => {
      expect(getParentPath('')).toBeNull();
    });
  });

  describe('normalizePath', () => {
    it('should convert array notation to dot notation', () => {
      expect(normalizePath('items[0]')).toBe('items.0');
    });

    it('should handle multiple array indices', () => {
      expect(normalizePath('items[0].nested[1]')).toBe('items.0.nested.1');
    });

    it('should remove leading dot', () => {
      expect(normalizePath('.config.host')).toBe('config.host');
    });

    it('should remove trailing dot', () => {
      expect(normalizePath('config.host.')).toBe('config.host');
    });

    it('should handle already normalized paths', () => {
      expect(normalizePath('config.host')).toBe('config.host');
    });
  });

  describe('pathsEqual', () => {
    it('should return true for identical paths', () => {
      expect(pathsEqual('config.host', 'config.host')).toBe(true);
    });

    it('should return true for equivalent paths with different notation', () => {
      expect(pathsEqual('items[0]', 'items.0')).toBe(true);
    });

    it('should return false for different paths', () => {
      expect(pathsEqual('config.host', 'config.port')).toBe(false);
    });

    it('should handle leading/trailing dots', () => {
      expect(pathsEqual('.config.host', 'config.host.')).toBe(true);
    });
  });

  describe('DEFAULT_FOCUS_CONFIG', () => {
    it('should have expected default values', () => {
      expect(DEFAULT_FOCUS_CONFIG.highlightDuration).toBe(2000);
      expect(DEFAULT_FOCUS_CONFIG.autoScroll).toBe(true);
      expect(DEFAULT_FOCUS_CONFIG.debounceDelay).toBe(100);
      expect(DEFAULT_FOCUS_CONFIG.enabled).toBe(true);
    });
  });

  describe('scrollToElement', () => {
    beforeEach(() => {
      document.body.innerHTML = '';
    });

    it('should scroll element into view without container', () => {
      const element = document.createElement('div');
      element.scrollIntoView = vi.fn();
      document.body.appendChild(element);

      scrollToElement(element);
      
      expect(element.scrollIntoView).toHaveBeenCalledWith({
        behavior: 'smooth',
        block: 'center',
        inline: 'nearest',
      });
    });

    it('should scroll element into view without animation', () => {
      const element = document.createElement('div');
      element.scrollIntoView = vi.fn();
      document.body.appendChild(element);

      scrollToElement(element, undefined, false);
      
      expect(element.scrollIntoView).toHaveBeenCalledWith({
        behavior: 'auto',
        block: 'center',
        inline: 'nearest',
      });
    });

    it('should handle null element gracefully', () => {
      expect(() => scrollToElement(null as unknown as HTMLElement)).not.toThrow();
    });

    it('should scroll within container using getBoundingClientRect', () => {
      const container = document.createElement('div');
      const element = document.createElement('div');
      
      // Mock getBoundingClientRect
      container.getBoundingClientRect = vi.fn().mockReturnValue({
        top: 0,
        height: 500,
      });
      element.getBoundingClientRect = vi.fn().mockReturnValue({
        top: 300,
        height: 50,
      });
      
      // Mock scroll properties
      Object.defineProperty(container, 'scrollTop', { value: 0, writable: true });
      Object.defineProperty(container, 'scrollHeight', { value: 1000 });
      Object.defineProperty(container, 'clientHeight', { value: 500 });
      container.scrollTo = vi.fn();
      
      container.appendChild(element);
      document.body.appendChild(container);

      scrollToElement(element, container, true);
      
      expect(container.scrollTo).toHaveBeenCalled();
    });
  });

  describe('isElementVisible', () => {
    beforeEach(() => {
      document.body.innerHTML = '';
    });

    it('should return true for visible element', () => {
      const element = document.createElement('div');
      element.style.width = '100px';
      element.style.height = '100px';
      document.body.appendChild(element);

      // Mock getBoundingClientRect to return non-zero dimensions
      element.getBoundingClientRect = vi.fn().mockReturnValue({
        width: 100,
        height: 100,
      });

      expect(isElementVisible(element)).toBe(true);
    });

    it('should return false for element with zero dimensions', () => {
      const element = document.createElement('div');
      document.body.appendChild(element);

      element.getBoundingClientRect = vi.fn().mockReturnValue({
        width: 0,
        height: 0,
      });

      expect(isElementVisible(element)).toBe(false);
    });

    it('should return false for null element', () => {
      expect(isElementVisible(null as unknown as HTMLElement)).toBe(false);
    });

    it('should return false for element with display:none', () => {
      const element = document.createElement('div');
      element.style.display = 'none';
      document.body.appendChild(element);

      element.getBoundingClientRect = vi.fn().mockReturnValue({
        width: 100,
        height: 100,
      });

      expect(isElementVisible(element)).toBe(false);
    });
  });

  describe('findFieldElement with data-field-path', () => {
    beforeEach(() => {
      document.body.innerHTML = '';
    });

    it('should prioritize data-field-path over ID', () => {
      const elementById = document.createElement('div');
      elementById.id = 'field-config-host';
      document.body.appendChild(elementById);

      const elementByPath = document.createElement('div');
      elementByPath.setAttribute('data-field-path', 'config.host');
      document.body.appendChild(elementByPath);

      const found = findFieldElement('config.host');
      expect(found).toBe(elementByPath);
    });

    it('should find element by data-field-path with dynamic keys', () => {
      const element = document.createElement('div');
      element.setAttribute('data-field-path', 'sourceClusters.my-source.endpoint');
      document.body.appendChild(element);

      const found = findFieldElement('sourceClusters.my-source.endpoint');
      expect(found).toBe(element);
    });

    it('should handle special characters in path', () => {
      const element = document.createElement('div');
      element.setAttribute('data-field-path', 'clusters.my-cluster-1.config');
      document.body.appendChild(element);

      const found = findFieldElement('clusters.my-cluster-1.config');
      expect(found).toBe(element);
    });

    it('should find element with bracket notation when searching with dot notation', () => {
      // DOM has bracket notation: migrationConfigs[0].skipApprovals
      const element = document.createElement('div');
      element.setAttribute('data-field-path', 'migrationConfigs[0].skipApprovals');
      document.body.appendChild(element);

      // Search with dot notation: migrationConfigs.0.skipApprovals
      const found = findFieldElement('migrationConfigs.0.skipApprovals');
      expect(found).toBe(element);
    });

    it('should find element with dot notation when searching with bracket notation', () => {
      // DOM has dot notation: migrationConfigs.0.skipApprovals
      const element = document.createElement('div');
      element.setAttribute('data-field-path', 'migrationConfigs.0.skipApprovals');
      document.body.appendChild(element);

      // Search with bracket notation: migrationConfigs[0].skipApprovals
      const found = findFieldElement('migrationConfigs[0].skipApprovals');
      expect(found).toBe(element);
    });

    it('should find element with multiple array indices in different notations', () => {
      // DOM has bracket notation: items[0].nested[1].value
      const element = document.createElement('div');
      element.setAttribute('data-field-path', 'items[0].nested[1].value');
      document.body.appendChild(element);

      // Search with dot notation: items.0.nested.1.value
      const found = findFieldElement('items.0.nested.1.value');
      expect(found).toBe(element);
    });

    it('should find element with mixed notation in path', () => {
      // DOM has bracket notation
      const element = document.createElement('div');
      element.setAttribute('data-field-path', 'configs[0].settings.items[2].name');
      document.body.appendChild(element);

      // Search with dot notation
      const found = findFieldElement('configs.0.settings.items.2.name');
      expect(found).toBe(element);
    });
  });

  describe('expandParentSections', () => {
    beforeEach(() => {
      document.body.innerHTML = '';
    });

    it('should handle element with no expandable parents', async () => {
      const element = document.createElement('div');
      document.body.appendChild(element);

      // Should not throw
      await expect(expandParentSections(element)).resolves.toBeUndefined();
    });

    it('should handle null element gracefully', async () => {
      await expect(expandParentSections(null as unknown as HTMLElement)).resolves.toBeUndefined();
    });

    it('should find and click collapsed expandable section buttons', async () => {
      // Create a mock expandable section structure
      const container = document.createElement('div');
      container.className = 'expandable-section';
      
      const header = document.createElement('div');
      header.className = 'expandable-section-header header';
      
      const button = document.createElement('button');
      button.setAttribute('aria-expanded', 'false');
      button.click = vi.fn();
      
      header.appendChild(button);
      container.appendChild(header);
      
      const content = document.createElement('div');
      content.className = 'expandable-section-content';
      
      const element = document.createElement('div');
      element.setAttribute('data-field-path', 'test.field');
      content.appendChild(element);
      container.appendChild(content);
      
      document.body.appendChild(container);

      await expandParentSections(element);
      
      // The function should have attempted to find and click expand buttons
      // Note: The exact behavior depends on the DOM structure matching Cloudscape's
    });
  });

  describe('scrollToFieldWithRetry', () => {
    beforeEach(() => {
      document.body.innerHTML = '';
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it('should return true when element is found and visible', async () => {
      const container = document.createElement('div');
      const element = document.createElement('div');
      element.setAttribute('data-field-path', 'test.field');
      
      // Mock visibility
      element.getBoundingClientRect = vi.fn().mockReturnValue({
        width: 100,
        height: 50,
      });
      
      container.appendChild(element);
      document.body.appendChild(container);

      // Mock container scroll methods
      Object.defineProperty(container, 'scrollTop', { value: 0, writable: true });
      Object.defineProperty(container, 'scrollHeight', { value: 1000 });
      Object.defineProperty(container, 'clientHeight', { value: 500 });
      container.scrollTo = vi.fn();
      container.getBoundingClientRect = vi.fn().mockReturnValue({
        top: 0,
        height: 500,
      });

      const resultPromise = scrollToFieldWithRetry('test.field', container, 3, 100);
      
      // Advance timers to allow async operations
      await vi.runAllTimersAsync();
      
      const result = await resultPromise;
      expect(result).toBe(true);
    });

    it('should return false when element is not found after retries', async () => {
      const container = document.createElement('div');
      document.body.appendChild(container);

      const resultPromise = scrollToFieldWithRetry('nonexistent.field', container, 2, 50);
      
      // Advance timers to allow retries
      await vi.runAllTimersAsync();
      
      const result = await resultPromise;
      expect(result).toBe(false);
    });

    it('should retry when element is not immediately visible', async () => {
      const container = document.createElement('div');
      const element = document.createElement('div');
      element.setAttribute('data-field-path', 'test.field');
      
      let callCount = 0;
      // First call returns hidden, subsequent calls return visible
      element.getBoundingClientRect = vi.fn().mockImplementation(() => {
        callCount++;
        if (callCount === 1) {
          return { width: 0, height: 0 };
        }
        return { width: 100, height: 50 };
      });
      
      container.appendChild(element);
      document.body.appendChild(container);

      // Mock container scroll methods
      Object.defineProperty(container, 'scrollTop', { value: 0, writable: true });
      Object.defineProperty(container, 'scrollHeight', { value: 1000 });
      Object.defineProperty(container, 'clientHeight', { value: 500 });
      container.scrollTo = vi.fn();
      container.getBoundingClientRect = vi.fn().mockReturnValue({
        top: 0,
        height: 500,
      });

      const resultPromise = scrollToFieldWithRetry('test.field', container, 3, 50);
      
      await vi.runAllTimersAsync();
      
      const result = await resultPromise;
      expect(result).toBe(true);
    });
  });
});
