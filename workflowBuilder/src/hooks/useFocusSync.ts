/**
 * useFocusSync Hook
 * 
 * Custom hook for managing bidirectional focus synchronization
 * between form fields and code editor.
 */

import { useState, useCallback, useRef, useEffect, useMemo } from 'react';
import type { FocusState, FocusConfig, FocusEvent, CodeFormat } from '../types';
import {
  findLineForFieldPath,
  findPathAtCursorPosition,
  findFieldElement,
  scrollToElement,
  scrollToFieldWithRetry,
  applyFieldHighlight,
  DEFAULT_FOCUS_CONFIG,
  pathsEqual,
} from '../lib/focus-utils';
import { createDebugLogger } from '../lib/debug-logger';

// Create a logger for focus sync hook
const log = createDebugLogger({ prefix: '[useFocusSync]' });

/**
 * Options for useFocusSync hook
 */
export interface UseFocusSyncOptions {
  /** Content to search for paths/lines */
  content: string;
  
  /** Format of the content */
  format: CodeFormat;
  
  /** Focus configuration */
  config?: Partial<FocusConfig>;
  
  /** Callback when focus changes */
  onFocusChange?: (event: FocusEvent | null) => void;
  
  /** Container element for form fields (for scrolling) - can be element or ref */
  formContainer?: HTMLElement | null | React.RefObject<HTMLElement | null>;
}

/**
 * Return type for useFocusSync hook
 */
export interface UseFocusSyncReturn {
  /** Current focus state */
  focusState: FocusState;
  
  /** Set focus from form field */
  setFocusFromForm: (path: string, element?: HTMLElement) => void;
  
  /** Set focus from editor cursor position */
  setFocusFromEditor: (line: number, column: number) => void;
  
  /** Clear current focus */
  clearFocus: () => void;
  
  /** Check if a path is currently focused */
  isPathFocused: (path: string) => boolean;
  
  /** Get the focused line number (for editor highlighting) */
  focusedLine: number | null;
  
  /** Get the focused path (for form highlighting) */
  focusedPath: string | null;
  
  /** Effective configuration */
  config: FocusConfig;
}

/**
 * Initial focus state
 */
const INITIAL_FOCUS_STATE: FocusState = {
  focusedPath: null,
  focusSource: null,
  focusedLine: null,
  focusTimestamp: null,
};

/**
 * Helper to resolve container element from ref or direct element
 */
function resolveContainer(
  container: HTMLElement | null | React.RefObject<HTMLElement | null> | undefined
): HTMLElement | undefined {
  if (!container) {
    return undefined;
  }
  // Check if it's a ref object
  if ('current' in container) {
    return container.current ?? undefined;
  }
  return container;
}

/**
 * Hook for managing bidirectional focus synchronization
 */
export function useFocusSync(options: UseFocusSyncOptions): UseFocusSyncReturn {
  const { content, format, config: configOverrides, onFocusChange, formContainer } = options;
  
  // Merge config with defaults
  const config = useMemo<FocusConfig>(() => ({
    ...DEFAULT_FOCUS_CONFIG,
    ...configOverrides,
  }), [configOverrides]);
  
  // Store formContainer in a ref so callbacks always have latest value
  const formContainerRef = useRef(formContainer);
  formContainerRef.current = formContainer;
  
  // Focus state
  const [focusState, setFocusState] = useState<FocusState>(INITIAL_FOCUS_STATE);
  
  // Refs for cleanup and debouncing
  const highlightCleanupRef = useRef<(() => void) | null>(null);
  const clearTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const debounceTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const lastFocusEventRef = useRef<FocusEvent | null>(null);
  
  /**
   * Clear any pending timeouts
   */
  const clearTimeouts = useCallback(() => {
    if (clearTimeoutRef.current) {
      clearTimeout(clearTimeoutRef.current);
      clearTimeoutRef.current = null;
    }
    if (debounceTimeoutRef.current) {
      clearTimeout(debounceTimeoutRef.current);
      debounceTimeoutRef.current = null;
    }
  }, []);
  
  /**
   * Clear highlight cleanup
   */
  const clearHighlight = useCallback(() => {
    if (highlightCleanupRef.current) {
      highlightCleanupRef.current();
      highlightCleanupRef.current = null;
    }
  }, []);
  
  /**
   * Clear focus state
   */
  const clearFocus = useCallback(() => {
    clearTimeouts();
    clearHighlight();
    setFocusState(INITIAL_FOCUS_STATE);
    lastFocusEventRef.current = null;
    onFocusChange?.(null);
  }, [clearTimeouts, clearHighlight, onFocusChange]);
  
  /**
   * Schedule auto-clear of focus after duration
   */
  const scheduleAutoClear = useCallback(() => {
    if (config.highlightDuration > 0) {
      clearTimeoutRef.current = setTimeout(() => {
        clearFocus();
      }, config.highlightDuration);
    }
  }, [config.highlightDuration, clearFocus]);
  
  /**
   * Set focus from form field
   */
  const setFocusFromForm = useCallback((path: string, element?: HTMLElement) => {
    log.group(`setFocusFromForm(path="${path}")`);
    
    if (!config.enabled) {
      log.debug('Focus sync disabled, ignoring');
      log.groupEnd();
      return;
    }
    
    // Debounce rapid focus changes
    clearTimeouts();
    
    debounceTimeoutRef.current = setTimeout(() => {
      // Find the line for this path
      log.debug('Finding line for path...');
      const lineMapping = findLineForFieldPath(content, path, format);
      const line = lineMapping?.line ?? null;
      log.debug(`Line mapping result: line=${line}`);
      
      // Create focus event
      const event: FocusEvent = {
        path,
        source: 'form',
        line: line ?? undefined,
        element,
      };
      
      // Update state
      setFocusState({
        focusedPath: path,
        focusSource: 'form',
        focusedLine: line,
        focusTimestamp: Date.now(),
      });
      
      log.debug('Focus state updated:', { path, line, source: 'form' });
      
      // Store last event
      lastFocusEventRef.current = event;
      
      // Notify callback
      onFocusChange?.(event);
      
      // Schedule auto-clear
      scheduleAutoClear();
      log.groupEnd();
    }, config.debounceDelay);
  }, [config.enabled, config.debounceDelay, content, format, clearTimeouts, onFocusChange, scheduleAutoClear]);
  
  /**
   * Set focus from editor cursor position
   */
  const setFocusFromEditor = useCallback((line: number, column: number) => {
    log.group(`setFocusFromEditor(line=${line}, col=${column})`);
    
    if (!config.enabled) {
      log.debug('Focus sync disabled, ignoring');
      log.groupEnd();
      return;
    }
    
    // Debounce rapid focus changes
    clearTimeouts();
    
    debounceTimeoutRef.current = setTimeout(async () => {
      // Find the path at this position
      log.debug('Finding path at cursor position...');
      const path = findPathAtCursorPosition(content, line, column, format);
      log.debug(`Path resolution result: path="${path}"`);
      
      if (!path) {
        // No path found at this position, clear focus
        log.debug('No path found, clearing focus');
        log.groupEnd();
        clearFocus();
        return;
      }
      
      // Create focus event
      const event: FocusEvent = {
        path,
        source: 'editor',
        line,
      };
      
      // Update state
      setFocusState({
        focusedPath: path,
        focusSource: 'editor',
        focusedLine: line,
        focusTimestamp: Date.now(),
      });
      
      log.debug('Focus state updated:', { path, line, source: 'editor' });
      
      // Store last event
      lastFocusEventRef.current = event;
      
      // Apply highlight to form field if auto-scroll is enabled
      if (config.autoScroll) {
        log.debug('Auto-scroll enabled, scrolling to form field...');
        // Resolve the container from ref or direct element
        const container = resolveContainer(formContainerRef.current);
        
        if (container) {
          // Clear previous highlight
          clearHighlight();
          
          // Use scrollToFieldWithRetry for robust scrolling that handles:
          // - Elements inside collapsed sections (will expand them)
          // - Elements that need time to render
          // - Partial path matching for dynamic paths
          const scrollSuccess = await scrollToFieldWithRetry(path, container, 3, 100);
          log.debug(`Scroll result: success=${scrollSuccess}`);
          
          if (scrollSuccess) {
            // Find the element again after potential expansion and apply highlight
            const element = findFieldElement(path, container);
            if (element) {
              log.debug('Applying highlight to element');
              highlightCleanupRef.current = applyFieldHighlight(element, config.highlightDuration);
            }
          }
        } else {
          log.debug('No container, trying fallback scroll');
          // Fallback: try to find and scroll to element without container
          const element = findFieldElement(path);
          if (element) {
            clearHighlight();
            scrollToElement(element, undefined, true);
            highlightCleanupRef.current = applyFieldHighlight(element, config.highlightDuration);
          }
        }
      }
      
      // Notify callback
      onFocusChange?.(event);
      
      // Schedule auto-clear
      scheduleAutoClear();
      log.groupEnd();
    }, config.debounceDelay);
  }, [config.enabled, config.debounceDelay, config.autoScroll, config.highlightDuration, content, format, formContainer, clearTimeouts, clearFocus, clearHighlight, onFocusChange, scheduleAutoClear]);
  
  /**
   * Check if a path is currently focused
   */
  const isPathFocused = useCallback((path: string): boolean => {
    if (!focusState.focusedPath) {
      return false;
    }
    return pathsEqual(focusState.focusedPath, path);
  }, [focusState.focusedPath]);
  
  // Cleanup on unmount
  useEffect(() => {
    return () => {
      clearTimeouts();
      clearHighlight();
    };
  }, [clearTimeouts, clearHighlight]);
  
  // Clear focus when content changes significantly
  useEffect(() => {
    // If content changes and we have a focused path, verify it still exists
    if (focusState.focusedPath && content) {
      const lineMapping = findLineForFieldPath(content, focusState.focusedPath, format);
      if (!lineMapping) {
        // Path no longer exists in content, clear focus
        clearFocus();
      } else if (lineMapping.line !== focusState.focusedLine) {
        // Line changed, update focus state
        setFocusState(prev => ({
          ...prev,
          focusedLine: lineMapping.line,
        }));
      }
    }
  }, [content, format, focusState.focusedPath, focusState.focusedLine, clearFocus]);
  
  return {
    focusState,
    setFocusFromForm,
    setFocusFromEditor,
    clearFocus,
    isPathFocused,
    focusedLine: focusState.focusedLine,
    focusedPath: focusState.focusedPath,
    config,
  };
}
