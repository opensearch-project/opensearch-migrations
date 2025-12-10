/**
 * Editor State Types
 * 
 * Types for managing the code editor state, including
 * content, annotations, and synchronization status.
 */

/**
 * Annotation type for code editor markers
 */
export type AnnotationType = 'error' | 'warning' | 'info';

/**
 * A single annotation/marker in the code editor
 */
export interface EditorAnnotation {
  /** Row number (0-indexed for Ace editor) */
  row: number;
  /** Column number (0-indexed for Ace editor) */
  column: number;
  /** Annotation text/message */
  text: string;
  /** Type of annotation */
  type: AnnotationType;
  /** Optional end row for range annotations */
  endRow?: number;
  /** Optional end column for range annotations */
  endColumn?: number;
}

/**
 * Supported code formats
 */
export type CodeFormat = 'yaml' | 'json';

/**
 * State of the code editor
 */
export interface CodeEditorState {
  /** Current content of the editor */
  content: string;
  /** Current format (yaml or json) */
  format: CodeFormat;
  /** Annotations to display (errors, warnings) */
  annotations: EditorAnnotation[];
  /** Whether content has been modified since last sync */
  isDirty: boolean;
  /** Parse error if content is invalid */
  parseError?: string;
  /** Line number of parse error */
  parseErrorLine?: number;
  /** Whether the editor is currently focused */
  isFocused: boolean;
  /** Cursor position */
  cursorPosition?: CursorPosition;
}

/**
 * Cursor position in the editor
 */
export interface CursorPosition {
  /** Row number (0-indexed) */
  row: number;
  /** Column number (0-indexed) */
  column: number;
}

/**
 * Selection range in the editor
 */
export interface SelectionRange {
  /** Start position */
  start: CursorPosition;
  /** End position */
  end: CursorPosition;
}

/**
 * Editor marker for highlighting specific ranges
 */
export interface EditorMarker {
  /** Unique identifier for the marker */
  id: string;
  /** Start row (0-indexed) */
  startRow: number;
  /** Start column (0-indexed) */
  startColumn: number;
  /** End row (0-indexed) */
  endRow: number;
  /** End column (0-indexed) */
  endColumn: number;
  /** CSS class for styling */
  className: string;
  /** Marker type */
  type: 'text' | 'line' | 'fullLine' | 'screenLine';
  /** Whether marker is in front of text */
  inFront?: boolean;
}

/**
 * Synchronization status between form and editor
 */
export type SyncStatus = 'synced' | 'form-ahead' | 'editor-ahead' | 'conflict';

/**
 * Synchronization state
 */
export interface SyncState {
  /** Current sync status */
  status: SyncStatus;
  /** Source of last change */
  lastChangeSource: 'form' | 'editor' | null;
  /** Timestamp of last sync */
  lastSyncTime?: number;
  /** Whether sync is in progress */
  isSyncing: boolean;
  /** Pending changes to sync */
  pendingChanges: boolean;
}

/**
 * Editor configuration options
 */
export interface EditorConfig {
  /** Theme name */
  theme?: string;
  /** Font size in pixels */
  fontSize?: number;
  /** Tab size in spaces */
  tabSize?: number;
  /** Whether to show line numbers */
  showLineNumbers?: boolean;
  /** Whether to show gutter */
  showGutter?: boolean;
  /** Whether to highlight active line */
  highlightActiveLine?: boolean;
  /** Whether to enable word wrap */
  wordWrap?: boolean;
  /** Whether editor is read-only */
  readOnly?: boolean;
  /** Minimum number of lines to show */
  minLines?: number;
  /** Maximum number of lines to show */
  maxLines?: number;
}

/**
 * Editor change event
 */
export interface EditorChangeEvent {
  /** New content */
  content: string;
  /** Change delta from Ace */
  delta?: unknown;
  /** Whether change was from user input */
  isUserInput: boolean;
}
