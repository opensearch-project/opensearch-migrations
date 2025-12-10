/**
 * useFocusSync Hook Tests
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useFocusSync } from './useFocusSync';

describe('useFocusSync', () => {
  const yamlContent = `name: test
config:
  host: localhost
  port: 8080`;

  beforeEach(() => {
    vi.useFakeTimers();
    document.body.innerHTML = '';
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  describe('initialization', () => {
    it('should initialize with null focus state', () => {
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
        })
      );

      expect(result.current.focusState.focusedPath).toBeNull();
      expect(result.current.focusState.focusSource).toBeNull();
      expect(result.current.focusState.focusedLine).toBeNull();
      expect(result.current.focusedPath).toBeNull();
      expect(result.current.focusedLine).toBeNull();
    });

    it('should use default config when not provided', () => {
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
        })
      );

      expect(result.current.config.highlightDuration).toBe(2000);
      expect(result.current.config.autoScroll).toBe(true);
      expect(result.current.config.debounceDelay).toBe(100);
      expect(result.current.config.enabled).toBe(true);
    });

    it('should merge custom config with defaults', () => {
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
          config: {
            highlightDuration: 5000,
            enabled: false,
          },
        })
      );

      expect(result.current.config.highlightDuration).toBe(5000);
      expect(result.current.config.enabled).toBe(false);
      expect(result.current.config.autoScroll).toBe(true); // Default
      expect(result.current.config.debounceDelay).toBe(100); // Default
    });
  });

  describe('setFocusFromForm', () => {
    it('should set focus state when called with valid path', () => {
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
        })
      );

      act(() => {
        result.current.setFocusFromForm('name');
        vi.advanceTimersByTime(100); // Debounce delay
      });

      expect(result.current.focusState.focusedPath).toBe('name');
      expect(result.current.focusState.focusSource).toBe('form');
      expect(result.current.focusState.focusedLine).toBe(1);
    });

    it('should find correct line for nested path', () => {
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
        })
      );

      act(() => {
        result.current.setFocusFromForm('config.host');
        vi.advanceTimersByTime(100);
      });

      expect(result.current.focusState.focusedPath).toBe('config.host');
      expect(result.current.focusState.focusedLine).toBe(3);
    });

    it('should call onFocusChange callback', () => {
      const onFocusChange = vi.fn();
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
          onFocusChange,
        })
      );

      act(() => {
        result.current.setFocusFromForm('name');
        vi.advanceTimersByTime(100);
      });

      expect(onFocusChange).toHaveBeenCalledWith(
        expect.objectContaining({
          path: 'name',
          source: 'form',
          line: 1,
        })
      );
    });

    it('should not set focus when disabled', () => {
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
          config: { enabled: false },
        })
      );

      act(() => {
        result.current.setFocusFromForm('name');
        vi.advanceTimersByTime(100);
      });

      expect(result.current.focusState.focusedPath).toBeNull();
    });

    it('should debounce rapid focus changes', () => {
      const onFocusChange = vi.fn();
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
          onFocusChange,
        })
      );

      act(() => {
        result.current.setFocusFromForm('name');
        vi.advanceTimersByTime(50); // Half of debounce delay
        result.current.setFocusFromForm('config.host');
        vi.advanceTimersByTime(100);
      });

      // Only the second call should have gone through
      expect(onFocusChange).toHaveBeenCalledTimes(1);
      expect(result.current.focusState.focusedPath).toBe('config.host');
    });
  });

  describe('setFocusFromEditor', () => {
    it('should set focus state when called with valid position', () => {
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
        })
      );

      act(() => {
        result.current.setFocusFromEditor(1, 1);
        vi.advanceTimersByTime(100);
      });

      expect(result.current.focusState.focusedPath).toBe('name');
      expect(result.current.focusState.focusSource).toBe('editor');
      expect(result.current.focusState.focusedLine).toBe(1);
    });

    it('should find correct path for nested position', () => {
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
        })
      );

      act(() => {
        result.current.setFocusFromEditor(3, 3);
        vi.advanceTimersByTime(100);
      });

      expect(result.current.focusState.focusedPath).toBe('config.host');
    });

    it('should clear focus when no path found at position', () => {
      const { result } = renderHook(() =>
        useFocusSync({
          content: '',
          format: 'yaml',
        })
      );

      // First set some focus
      act(() => {
        result.current.setFocusFromEditor(1, 1);
        vi.advanceTimersByTime(100);
      });

      expect(result.current.focusState.focusedPath).toBeNull();
    });

    it('should call onFocusChange callback', () => {
      const onFocusChange = vi.fn();
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
          onFocusChange,
        })
      );

      act(() => {
        result.current.setFocusFromEditor(1, 1);
        vi.advanceTimersByTime(100);
      });

      expect(onFocusChange).toHaveBeenCalledWith(
        expect.objectContaining({
          path: 'name',
          source: 'editor',
          line: 1,
        })
      );
    });
  });

  describe('clearFocus', () => {
    it('should clear focus state', () => {
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
        })
      );

      // Set focus first
      act(() => {
        result.current.setFocusFromForm('name');
        vi.advanceTimersByTime(100);
      });

      expect(result.current.focusState.focusedPath).toBe('name');

      // Clear focus
      act(() => {
        result.current.clearFocus();
      });

      expect(result.current.focusState.focusedPath).toBeNull();
      expect(result.current.focusState.focusSource).toBeNull();
      expect(result.current.focusState.focusedLine).toBeNull();
    });

    it('should call onFocusChange with null', () => {
      const onFocusChange = vi.fn();
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
          onFocusChange,
        })
      );

      act(() => {
        result.current.setFocusFromForm('name');
        vi.advanceTimersByTime(100);
      });

      onFocusChange.mockClear();

      act(() => {
        result.current.clearFocus();
      });

      expect(onFocusChange).toHaveBeenCalledWith(null);
    });
  });

  describe('isPathFocused', () => {
    it('should return true for focused path', () => {
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
        })
      );

      act(() => {
        result.current.setFocusFromForm('name');
        vi.advanceTimersByTime(100);
      });

      expect(result.current.isPathFocused('name')).toBe(true);
    });

    it('should return false for non-focused path', () => {
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
        })
      );

      act(() => {
        result.current.setFocusFromForm('name');
        vi.advanceTimersByTime(100);
      });

      expect(result.current.isPathFocused('config.host')).toBe(false);
    });

    it('should return false when no focus', () => {
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
        })
      );

      expect(result.current.isPathFocused('name')).toBe(false);
    });

    it('should handle path normalization', () => {
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
        })
      );

      act(() => {
        result.current.setFocusFromForm('config.host');
        vi.advanceTimersByTime(100);
      });

      // Should match with different notation
      expect(result.current.isPathFocused('config.host')).toBe(true);
    });
  });

  describe('auto-clear timeout', () => {
    it('should auto-clear focus after highlight duration', () => {
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
          config: { highlightDuration: 1000 },
        })
      );

      act(() => {
        result.current.setFocusFromForm('name');
        vi.advanceTimersByTime(100); // Debounce
      });

      expect(result.current.focusState.focusedPath).toBe('name');

      act(() => {
        vi.advanceTimersByTime(1000); // Highlight duration
      });

      expect(result.current.focusState.focusedPath).toBeNull();
    });

    it('should not auto-clear when highlightDuration is 0', () => {
      const { result } = renderHook(() =>
        useFocusSync({
          content: yamlContent,
          format: 'yaml',
          config: { highlightDuration: 0 },
        })
      );

      act(() => {
        result.current.setFocusFromForm('name');
        vi.advanceTimersByTime(100);
      });

      expect(result.current.focusState.focusedPath).toBe('name');

      act(() => {
        vi.advanceTimersByTime(10000);
      });

      expect(result.current.focusState.focusedPath).toBe('name');
    });
  });

  describe('content changes', () => {
    it('should clear focus when path no longer exists in content', () => {
      const { result, rerender } = renderHook(
        ({ content }) =>
          useFocusSync({
            content,
            format: 'yaml',
          }),
        { initialProps: { content: yamlContent } }
      );

      act(() => {
        result.current.setFocusFromForm('name');
        vi.advanceTimersByTime(100);
      });

      expect(result.current.focusState.focusedPath).toBe('name');

      // Change content to remove the path
      rerender({ content: 'other: value' });

      expect(result.current.focusState.focusedPath).toBeNull();
    });

    it('should update line number when content changes', () => {
      const { result, rerender } = renderHook(
        ({ content }) =>
          useFocusSync({
            content,
            format: 'yaml',
          }),
        { initialProps: { content: yamlContent } }
      );

      act(() => {
        result.current.setFocusFromForm('config.host');
        vi.advanceTimersByTime(100);
      });

      expect(result.current.focusState.focusedLine).toBe(3);

      // Change content to shift lines
      const newContent = `extra: line
name: test
config:
  host: localhost
  port: 8080`;

      rerender({ content: newContent });

      expect(result.current.focusState.focusedPath).toBe('config.host');
      expect(result.current.focusState.focusedLine).toBe(4);
    });
  });
});
