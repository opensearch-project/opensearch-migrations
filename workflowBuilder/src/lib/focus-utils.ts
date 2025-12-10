/**
 * Focus Utilities
 * 
 * Utility functions for focus synchronization between form and code editor.
 * Provides enhanced line/path finding, scrolling, and highlighting capabilities.
 */

import type { Ace } from 'ace-builds';
import type { CodeFormat, EditorMarker, LineMapping } from '../types';
import { getLineForPath, getPathAtPosition } from './yaml-parser';
import { createDebugLogger } from './debug-logger';

// Create a logger for focus utilities
const log = createDebugLogger({ prefix: '[FocusUtils]' });

/**
 * Default focus configuration values
 */
export const DEFAULT_FOCUS_CONFIG = {
  highlightDuration: 2000,
  autoScroll: true,
  debounceDelay: 100,
  enabled: true,
} as const;

/**
 * CSS class for focus highlight in editor
 */
export const FOCUS_HIGHLIGHT_CLASS = 'ace-focus-highlight';

/**
 * CSS class for focus highlight in form fields
 */
export const FIELD_FOCUS_HIGHLIGHT_CLASS = 'field-focus-highlight';

/**
 * Find the line mapping for a field path in content
 * Enhanced version that returns full LineMapping with start/end positions
 * 
 * @param content - The YAML or JSON content
 * @param path - The field path (dot-notation)
 * @param format - The content format
 * @returns LineMapping with position info, or null if not found
 */
export function findLineForFieldPath(
  content: string,
  path: string,
  format: CodeFormat
): LineMapping | null {
  const line = getLineForPath(content, path, format);
  
  if (line === null) {
    return null;
  }
  
  const lines = content.split('\n');
  const lineContent = lines[line - 1] || '';
  
  // Find the column where the key starts
  let column = 1;
  if (format === 'yaml') {
    const keyMatch = lineContent.match(/^(\s*)([^:]+):/);
    if (keyMatch) {
      column = (keyMatch[1]?.length || 0) + 1;
    }
  } else {
    const keyMatch = lineContent.match(/^(\s*)"([^"]+)"\s*:/);
    if (keyMatch) {
      column = (keyMatch[1]?.length || 0) + 1;
    }
  }
  
  // Calculate end position (end of line for simple values)
  const endColumn = lineContent.length + 1;
  
  // Check if value spans multiple lines (for objects/arrays)
  let endLine = line;
  
  // Look for the next sibling or parent to determine end line
  if (format === 'yaml') {
    const currentIndent = lineContent.length - lineContent.trimStart().length;
    for (let i = line; i < lines.length; i++) {
      const nextLine = lines[i];
      const trimmed = nextLine.trimStart();
      
      // Skip empty lines and comments
      if (!trimmed || trimmed.startsWith('#')) {
        continue;
      }
      
      const nextIndent = nextLine.length - trimmed.length;
      
      // If we find a line at same or lower indent, we've found the end
      if (nextIndent <= currentIndent && i > line - 1) {
        endLine = i; // Previous line was the end
        break;
      }
      
      endLine = i + 1;
    }
  }
  
  return {
    path,
    line,
    column,
    endLine,
    endColumn,
  };
}

/**
 * Find the field path at a cursor position in content
 * Enhanced version with better accuracy for nested structures
 * 
 * @param content - The YAML or JSON content
 * @param line - The line number (1-indexed)
 * @param column - The column number (1-indexed)
 * @param format - The content format
 * @returns The field path at the position, or null if not found
 */
export function findPathAtCursorPosition(
  content: string,
  line: number,
  column: number,
  format: CodeFormat
): string | null {
  return getPathAtPosition(content, line, column, format);
}

/**
 * Scroll the Ace editor to a specific line
 * 
 * @param editor - The Ace editor instance
 * @param line - The line number to scroll to (1-indexed)
 * @param animate - Whether to animate the scroll
 */
export function scrollToLine(
  editor: Ace.Editor,
  line: number,
  animate: boolean = true
): void {
  if (!editor) {
    return;
  }
  
  // Convert to 0-indexed
  const row = line - 1;
  
  // Get the editor's visible row count
  const visibleRows = editor.getLastVisibleRow() - editor.getFirstVisibleRow();
  
  // Calculate the target row to center the line
  const targetRow = Math.max(0, row - Math.floor(visibleRows / 2));
  
  if (animate) {
    editor.scrollToRow(targetRow);
  } else {
    editor.scrollToRow(targetRow);
  }
  
  // Also move cursor to the line
  editor.gotoLine(line, 0, animate);
}

/**
 * Scroll a form field element into view
 * 
 * @param element - The element to scroll into view
 * @param container - Optional container element (defaults to nearest scrollable parent)
 * @param animate - Whether to animate the scroll
 */
export function scrollToElement(
  element: HTMLElement,
  container?: HTMLElement,
  animate: boolean = true
): void {
  if (!element) {
    return;
  }
  
  const scrollOptions: ScrollIntoViewOptions = {
    behavior: animate ? 'smooth' : 'auto',
    block: 'center',
    inline: 'nearest',
  };
  
  if (container) {
    // Use getBoundingClientRect for accurate position calculation
    // This works correctly for elements inside nested containers and Cloudscape components
    const elementRect = element.getBoundingClientRect();
    const containerRect = container.getBoundingClientRect();
    
    // Calculate the scroll position needed to center the element in the container
    // Formula: current scroll + (element position relative to container) - (half container height) + (half element height)
    const scrollTop = container.scrollTop + (elementRect.top - containerRect.top) - (containerRect.height / 2) + (elementRect.height / 2);
    
    // Clamp to valid scroll range
    const maxScroll = container.scrollHeight - container.clientHeight;
    const clampedScrollTop = Math.max(0, Math.min(scrollTop, maxScroll));
    
    if (animate) {
      container.scrollTo({
        top: clampedScrollTop,
        behavior: 'smooth',
      });
    } else {
      container.scrollTop = clampedScrollTop;
    }
  } else {
    element.scrollIntoView(scrollOptions);
  }
}

/**
 * Create an Ace editor marker for focus highlighting
 * 
 * @param line - The line number to highlight (1-indexed)
 * @param endLine - Optional end line for multi-line highlights
 * @returns EditorMarker configuration
 */
export function createFocusHighlightMarker(
  line: number,
  endLine?: number
): EditorMarker {
  const startRow = line - 1; // Convert to 0-indexed
  const endRow = (endLine ?? line) - 1;
  
  return {
    startRow,
    startColumn: 0,
    endRow,
    endColumn: 1, // Full line marker
    className: FOCUS_HIGHLIGHT_CLASS,
    type: 'fullLine',
    inFront: false,
  };
}

/**
 * Generate a consistent DOM ID for a field based on its path
 * 
 * @param path - The field path (dot-notation)
 * @returns A valid DOM ID string
 */
export function calculateFieldElementId(path: string): string {
  // Replace dots and special characters with dashes
  // Prefix with 'field-' to ensure valid ID
  const sanitized = path
    .replace(/\./g, '-')
    .replace(/\[/g, '-')
    .replace(/\]/g, '')
    .replace(/[^a-zA-Z0-9-_]/g, '-');
  
  return `field-${sanitized}`;
}

/**
 * Find a field element by its path
 * 
 * @param path - The field path
 * @param container - Optional container to search within
 * @returns The field element, or null if not found
 */
export function findFieldElement(
  path: string,
  container?: HTMLElement
): HTMLElement | null {
  log.group(`findFieldElement(path="${path}")`);
  const searchRoot = container || document;
  
  // Primary: Try to find by data-field-path attribute (most reliable)
  log.debug('Strategy 1: Exact data-field-path match');
  let element = searchRoot.querySelector(`[data-field-path="${path}"]`) as HTMLElement | null;
  
  if (element) {
    log.debug('  -> Found with exact path');
    log.groupEnd();
    return element;
  }
  
  // Try alternate path formats if not found
  // The code editor might use dot notation (migrationConfigs.0.skipApprovals)
  // while the DOM uses bracket notation (migrationConfigs[0].skipApprovals)
  log.debug('Strategy 2: Convert dot notation to bracket notation');
  // Convert dot notation to bracket notation: migrationConfigs.0.field -> migrationConfigs[0].field
  const bracketPath = path.replace(/\.(\d+)(?=\.|$)/g, '[$1]');
  if (bracketPath !== path) {
    log.debug(`  -> Trying bracket path: "${bracketPath}"`);
    element = searchRoot.querySelector(`[data-field-path="${bracketPath}"]`) as HTMLElement | null;
    if (element) {
      log.debug('  -> Found with bracket notation');
      log.groupEnd();
      return element;
    }
  }
  
  log.debug('Strategy 3: Convert bracket notation to dot notation');
  // Convert bracket notation to dot notation: migrationConfigs[0].field -> migrationConfigs.0.field
  const dotPath = path.replace(/\[(\d+)\]/g, '.$1');
  if (dotPath !== path) {
    log.debug(`  -> Trying dot path: "${dotPath}"`);
    element = searchRoot.querySelector(`[data-field-path="${dotPath}"]`) as HTMLElement | null;
    if (element) {
      log.debug('  -> Found with dot notation');
      log.groupEnd();
      return element;
    }
  }
  
  // Secondary: Try to find by ID
  log.debug('Strategy 4: Find by element ID');
  const id = calculateFieldElementId(path);
  // Escape special characters in ID for querySelector
  const escapedId = CSS.escape(id);
  log.debug(`  -> Trying ID: "${id}"`);
  element = searchRoot.querySelector(`#${escapedId}`) as HTMLElement | null;
  
  if (element) {
    log.debug('  -> Found by ID');
    log.groupEnd();
    return element;
  }
  
  // Tertiary: Try partial path matching for dynamic paths (e.g., record entries)
  // This handles cases where the path contains dynamic keys like "sourceClusters.my-source.endpoint"
  log.debug('Strategy 5: Partial path matching');
  element = findFieldElementByPartialPath(path, searchRoot);
  
  if (element) {
    log.debug('  -> Found with partial path matching');
  } else {
    log.debug('  -> Element NOT FOUND');
  }
  
  log.groupEnd();
  return element;
}

/**
 * Find a field element by partial path matching
 * Useful for dynamic paths where the exact path may vary (e.g., record entries with user-defined keys)
 * 
 * @param path - The field path to search for
 * @param searchRoot - The root element to search within
 * @returns The field element, or null if not found
 */
function findFieldElementByPartialPath(
  path: string,
  searchRoot: HTMLElement | Document
): HTMLElement | null {
  // Get all elements with data-field-path attribute
  const allFieldElements = searchRoot.querySelectorAll('[data-field-path]');
  
  // Normalize the search path
  const normalizedSearchPath = normalizePath(path);
  const searchParts = normalizedSearchPath.split('.');
  
  for (const el of allFieldElements) {
    const elementPath = el.getAttribute('data-field-path');
    if (!elementPath) continue;
    
    const normalizedElementPath = normalizePath(elementPath);
    
    // Exact match after normalization
    if (normalizedElementPath === normalizedSearchPath) {
      return el as HTMLElement;
    }
    
    // Check if the element path ends with the same field name
    // This helps match paths like "sourceClusters.my-source.endpoint" 
    // when searching for "sourceClusters.*.endpoint"
    const elementParts = normalizedElementPath.split('.');
    if (elementParts.length === searchParts.length) {
      let matches = true;
      for (let i = 0; i < searchParts.length; i++) {
        // Allow wildcard matching with '*'
        if (searchParts[i] !== '*' && searchParts[i] !== elementParts[i]) {
          matches = false;
          break;
        }
      }
      if (matches) {
        return el as HTMLElement;
      }
    }
  }
  
  return null;
}

/**
 * Apply focus highlight styling to a field element
 * 
 * @param element - The element to highlight
 * @param duration - Duration in ms to show highlight (0 for permanent)
 * @returns Cleanup function to remove highlight
 */
export function applyFieldHighlight(
  element: HTMLElement,
  duration: number = 2000
): () => void {
  if (!element) {
    return () => {};
  }
  
  // Add highlight class
  element.classList.add(FIELD_FOCUS_HIGHLIGHT_CLASS);
  
  // Set up cleanup
  let timeoutId: ReturnType<typeof setTimeout> | null = null;
  
  const cleanup = () => {
    element.classList.remove(FIELD_FOCUS_HIGHLIGHT_CLASS);
    if (timeoutId) {
      clearTimeout(timeoutId);
      timeoutId = null;
    }
  };
  
  // Auto-remove after duration (if duration > 0)
  if (duration > 0) {
    timeoutId = setTimeout(cleanup, duration);
  }
  
  return cleanup;
}

/**
 * Check if a path is a parent of another path
 * 
 * @param parentPath - The potential parent path
 * @param childPath - The potential child path
 * @returns True if parentPath is a parent of childPath
 */
export function isParentPath(parentPath: string, childPath: string): boolean {
  if (!parentPath || !childPath) {
    return false;
  }
  
  return childPath.startsWith(parentPath + '.');
}

/**
 * Get the parent path of a field path
 * 
 * @param path - The field path
 * @returns The parent path, or null if at root level
 */
export function getParentPath(path: string): string | null {
  if (!path) {
    return null;
  }
  
  const lastDotIndex = path.lastIndexOf('.');
  
  if (lastDotIndex === -1) {
    return null;
  }
  
  return path.substring(0, lastDotIndex);
}

/**
 * Normalize a field path for comparison
 * Handles array indices and special characters
 * 
 * @param path - The field path to normalize
 * @returns Normalized path string
 */
export function normalizePath(path: string): string {
  return path
    .replace(/\[(\d+)\]/g, '.$1') // Convert array[0] to array.0
    .replace(/^\./, '') // Remove leading dot
    .replace(/\.$/, ''); // Remove trailing dot
}

/**
 * Compare two paths for equality (with normalization)
 * 
 * @param path1 - First path
 * @param path2 - Second path
 * @returns True if paths are equivalent
 */
export function pathsEqual(path1: string, path2: string): boolean {
  return normalizePath(path1) === normalizePath(path2);
}

/**
 * Find and expand any collapsed ExpandableSection ancestors of a target element.
 * This is necessary because Cloudscape ExpandableSection components hide their
 * content when collapsed, making it impossible to scroll to elements inside them.
 * 
 * Handles both:
 * - Container variant ExpandableSections (used for record/array entries)
 * - Footer variant ExpandableSections (used for "Advanced Settings")
 * 
 * @param element - The target element whose ancestors should be expanded
 * @returns Promise that resolves when all sections are expanded
 */
export async function expandParentSections(element: HTMLElement): Promise<void> {
  if (!element) {
    return;
  }
  
  // Find all ancestor expandable sections that are collapsed
  // Cloudscape ExpandableSection uses specific class names and aria attributes
  const sectionsToExpand: HTMLElement[] = [];
  
  // Method 1: Walk up the DOM tree to find all collapsed expandable sections
  let current: HTMLElement | null = element;
  
  while (current) {
    // Look for Cloudscape expandable section wrapper
    // Cloudscape uses class names like "awsui_expandable-section_*" or "awsui_root_gwq0h_*"
    const expandableWrapper = current.closest('[class*="expandable-section"], [class*="awsui_root_gwq0h"]') as HTMLElement | null;
    if (expandableWrapper && expandableWrapper !== current) {
      // Find the expand/collapse element within this section
      // Cloudscape uses <span role="button"> with aria-expanded attribute, NOT <button>
      const expandButton = expandableWrapper.querySelector('[aria-expanded="false"]') as HTMLElement | null;
      if (expandButton && !sectionsToExpand.includes(expandButton)) {
        sectionsToExpand.push(expandButton);
      }
      // Move to parent of this wrapper to continue searching for nested sections
      current = expandableWrapper.parentElement;
      continue;
    }
    
    current = current.parentElement;
  }
  
  // Method 2: Search for any collapsed sections that contain the element
  // This is important for "Advanced Settings" sections where the element might be
  // inside a collapsed section but the section wrapper is not a direct ancestor
  // Note: Cloudscape uses <span role="button"> not <button>
  const allExpandElements = document.querySelectorAll('[aria-expanded="false"]');
  
  for (const expandEl of allExpandElements) {
    // Find the expandable section wrapper containing this element
    const section = expandEl.closest('[class*="expandable-section"], [class*="awsui_root_gwq0h"]');
    if (section) {
      // Check if the section contains our target element
      // Even when collapsed, Cloudscape keeps the content in DOM (just hidden)
      if (section.contains(element)) {
        if (!sectionsToExpand.includes(expandEl as HTMLElement)) {
          sectionsToExpand.push(expandEl as HTMLElement);
        }
      }
    }
  }
  
  // Method 3: Find sections by checking if element's path matches any collapsed section's content
  // This handles cases where the element might be deeply nested
  const elementPath = element.getAttribute('data-field-path');
  
  if (elementPath) {
    // Find all collapsed expandable sections and check if they contain fields with matching path prefix
    for (const expandEl of allExpandElements) {
      const section = expandEl.closest('[class*="expandable-section"], [class*="awsui_root_gwq0h"]');
      if (section && !sectionsToExpand.includes(expandEl as HTMLElement)) {
        // Check if any field inside this section has a path that is a prefix of our target path
        // or if our target path is inside this section
        const fieldsInSection = section.querySelectorAll('[data-field-path]');
        for (const field of fieldsInSection) {
          const fieldPath = field.getAttribute('data-field-path');
          if (fieldPath && (fieldPath === elementPath || elementPath.startsWith(fieldPath + '.'))) {
            sectionsToExpand.push(expandEl as HTMLElement);
            break;
          }
        }
      }
    }
  }
  
  // Expand sections from outermost to innermost
  // Sort by DOM depth (outermost first) to ensure parent sections expand before children
  sectionsToExpand.sort((a, b) => {
    const depthA = getElementDepth(a);
    const depthB = getElementDepth(b);
    return depthA - depthB;
  });
  
  // Click each expand button with delays for DOM updates
  for (const button of sectionsToExpand) {
    // Double-check the button is still collapsed before clicking
    if (button.getAttribute('aria-expanded') === 'false') {
      button.click();
      // Wait for the DOM to update after expanding
      // Cloudscape animations typically take ~150ms
      await new Promise(resolve => setTimeout(resolve, 100));
    }
  }
  
  // Additional wait for all animations to complete
  if (sectionsToExpand.length > 0) {
    await new Promise(resolve => setTimeout(resolve, 150));
  }
}

/**
 * Get the depth of an element in the DOM tree
 * @param element - The element to measure
 * @returns The depth (number of ancestors)
 */
function getElementDepth(element: HTMLElement): number {
  let depth = 0;
  let current: HTMLElement | null = element;
  while (current) {
    depth++;
    current = current.parentElement;
  }
  return depth;
}

/**
 * Attempt to scroll to a field element with retry logic.
 * This function handles the case where the element might be inside a collapsed
 * section and needs to be revealed first.
 * 
 * @param path - The field path to scroll to
 * @param container - The container element to scroll within
 * @param maxRetries - Maximum number of retry attempts (default: 3)
 * @param retryDelay - Delay between retries in ms (default: 100)
 * @returns Promise that resolves to true if scroll was successful, false otherwise
 */
export async function scrollToFieldWithRetry(
  path: string,
  container: HTMLElement,
  maxRetries: number = 3,
  retryDelay: number = 100
): Promise<boolean> {
  log.group(`scrollToFieldWithRetry(path="${path}")`);
  
  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    log.debug(`Attempt ${attempt + 1}/${maxRetries + 1}`);
    
    // Try to find the element
    const element = findFieldElement(path, container);
    
    if (element) {
      // Check if element is visible (not inside a collapsed section)
      const isVisible = isElementVisible(element);
      log.debug(`Element found, isVisible=${isVisible}`);
      
      if (!isVisible) {
        // Try to expand parent sections
        log.debug('Element not visible, expanding parent sections...');
        await expandParentSections(element);
        // Wait for DOM updates
        await new Promise(resolve => setTimeout(resolve, retryDelay));
        continue;
      }
      
      // Element found and visible, scroll to it
      log.debug('Scrolling to element');
      scrollToElement(element, container, true);
      log.groupEnd();
      return true;
    }
    
    log.debug('Element not found');
    // Element not found, wait and retry
    if (attempt < maxRetries) {
      await new Promise(resolve => setTimeout(resolve, retryDelay));
    }
  }
  
  // All retries exhausted, try one more time with partial path matching
  // This handles cases where the exact path doesn't exist but a similar one does
  log.debug('All retries exhausted, trying partial path matching...');
  const partialElement = findFieldElementByPartialPath(path, container);
  
  if (partialElement) {
    log.debug('Found element with partial path matching');
    await expandParentSections(partialElement);
    await new Promise(resolve => setTimeout(resolve, retryDelay));
    scrollToElement(partialElement, container, true);
    log.groupEnd();
    return true;
  }
  
  log.debug('FAILED to scroll to field');
  log.groupEnd();
  return false;
}

/**
 * Check if an element is visible in the DOM (not hidden by collapsed sections, display:none, etc.)
 * 
 * @param element - The element to check
 * @returns True if the element is visible
 */
export function isElementVisible(element: HTMLElement): boolean {
  if (!element) {
    return false;
  }
  
  // Check if element has zero dimensions (hidden)
  const rect = element.getBoundingClientRect();
  if (rect.width === 0 && rect.height === 0) {
    return false;
  }
  
  // Check computed style
  const style = window.getComputedStyle(element);
  if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
    return false;
  }
  
  // Check if any parent is hidden
  let parent = element.parentElement;
  while (parent) {
    const parentStyle = window.getComputedStyle(parent);
    if (parentStyle.display === 'none' || parentStyle.visibility === 'hidden') {
      return false;
    }
    parent = parent.parentElement;
  }
  
  return true;
}
