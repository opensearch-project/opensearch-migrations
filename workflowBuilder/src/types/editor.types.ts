/**
 * Editor type definitions for code panel and bidirectional sync
 */

/**
 * Supported code formats
 */
export type CodeFormat = 'yaml' | 'json';

/**
 * Ace editor annotation for inline error display
 */
export interface EditorAnnotation {
  /** Line number (0-indexed) */
  row: number;
  
  /** Column number */
  column: number;
  
  /** Annotation text */
  text: string;
  
  /** Annotation type */
  type: 'error' | 'warning' | 'info';
}

/**
 * Ace editor marker for highlighting
 */
export interface EditorMarker {
  /** Start row */
  startRow: number;
  
  /** Start column */
  startColumn: number;
  
  /** End row */
  endRow: number;
  
  /** End column */
  endColumn: number;
  
  /** CSS class for styling */
  className: string;
  
  /** Marker type */
  type: 'text' | 'line' | 'fullLine' | 'screenLine';
  
  /** Whether marker is in front of text */
  inFront?: boolean;
}

/**
 * Cursor position in editor
 */
export interface CursorPosition {
  row: number;
  column: number;
}

/**
 * Selection range in editor
 */
export interface SelectionRange {
  start: CursorPosition;
  end: CursorPosition;
}

/**
 * Complete editor state
 */
export interface EditorState {
  /** Current content */
  content: string;
  
  /** Current format (yaml/json) */
  format: CodeFormat;
  
  /** Validation annotations */
  annotations: EditorAnnotation[];
  
  /** Highlight markers */
  markers: EditorMarker[];
  
  /** Parse error message (if content is invalid) */
  parseError: string | null;
  
  /** Current cursor position */
  cursorPosition?: CursorPosition;
  
  /** Current selection */
  selection?: SelectionRange;
  
  /** Whether editor has focus */
  hasFocus: boolean;
  
  /** Whether content has been modified */
  isDirty: boolean;
}

/**
 * State for bidirectional sync between form and editor
 */
export interface SyncState {
  /** Source of the last change */
  lastChangeSource: 'form' | 'editor' | null;
  
  /** Whether there are unsaved changes */
  isDirty: boolean;
  
  /** Whether validation is in progress */
  isValidating: boolean;
  
  /** Whether sync is in progress */
  isSyncing: boolean;
  
  /** Timestamp of last sync */
  lastSyncTime?: number;
  
  /** Error during sync (if any) */
  syncError?: string;
}

/**
 * Editor configuration options
 */
export interface EditorConfig {
  /** Theme name */
  theme?: string;
  
  /** Font size in pixels */
  fontSize?: number;
  
  /** Tab size */
  tabSize?: number;
  
  /** Whether to show line numbers */
  showLineNumbers?: boolean;
  
  /** Whether to show gutter */
  showGutter?: boolean;
  
  /** Whether to highlight active line */
  highlightActiveLine?: boolean;
  
  /** Whether to show print margin */
  showPrintMargin?: boolean;
  
  /** Whether to wrap lines */
  wrap?: boolean;
  
  /** Whether editor is read-only */
  readOnly?: boolean;
  
  /** Minimum number of lines */
  minLines?: number;
  
  /** Maximum number of lines */
  maxLines?: number;
}

/**
 * Editor action types
 */
export type EditorAction =
  | { type: 'SET_CONTENT'; payload: string }
  | { type: 'SET_FORMAT'; payload: CodeFormat }
  | { type: 'SET_ANNOTATIONS'; payload: EditorAnnotation[] }
  | { type: 'SET_MARKERS'; payload: EditorMarker[] }
  | { type: 'SET_PARSE_ERROR'; payload: string | null }
  | { type: 'SET_CURSOR'; payload: CursorPosition }
  | { type: 'SET_SELECTION'; payload: SelectionRange | undefined }
  | { type: 'SET_FOCUS'; payload: boolean }
  | { type: 'SET_DIRTY'; payload: boolean }
  | { type: 'RESET' };

/**
 * Line mapping for path-to-line resolution
 */
export interface LineMapping {
  /** Field path */
  path: string;
  
  /** Line number (1-indexed) */
  line: number;
  
  /** Column number */
  column: number;
  
  /** End line (for multi-line values) */
  endLine?: number;
  
  /** End column */
  endColumn?: number;
}

/**
 * Result of path-to-line mapping
 */
export interface PathLineMap {
  /** Map of path to line info */
  pathToLine: Map<string, LineMapping>;
  
  /** Map of line to paths (for reverse lookup) */
  lineToPath: Map<number, string[]>;
}

/**
 * Focus state for bidirectional sync highlighting
 */
export interface FocusState {
  /** Currently focused field path (from form or editor) */
  focusedPath: string | null;
  
  /** Source of the focus event */
  focusSource: 'form' | 'editor' | null;
  
  /** Line number in editor corresponding to focused path */
  focusedLine: number | null;
  
  /** Timestamp of last focus change (for debouncing/timeout) */
  focusTimestamp: number | null;
}

/**
 * Focus event emitted when a field gains focus
 */
export interface FocusEvent {
  /** Field path that gained focus */
  path: string;
  
  /** Source of the focus */
  source: 'form' | 'editor';
  
  /** Optional line number (when source is editor) */
  line?: number;
  
  /** Optional element reference (when source is form) */
  element?: HTMLElement;
}

/**
 * Configuration for focus behavior
 */
export interface FocusConfig {
  /** Duration in ms to show highlight (default: 2000) */
  highlightDuration: number;
  
  /** Whether to auto-scroll to focused element (default: true) */
  autoScroll: boolean;
  
  /** Debounce delay for focus events in ms (default: 100) */
  debounceDelay: number;
  
  /** Whether focus feature is enabled (default: true) */
  enabled: boolean;
}
