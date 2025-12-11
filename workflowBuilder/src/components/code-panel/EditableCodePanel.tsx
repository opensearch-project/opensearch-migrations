/**
 * EditableCodePanel Component
 * 
 * An editable code editor panel with error annotations.
 * Supports bidirectional sync with form state.
 */

import React, { useEffect, useRef, useMemo } from 'react';
import { createDebugLogger } from '../../lib';
import {
  Container,
  Header,
  SpaceBetween,
  Box,
  CopyToClipboard,
  Alert,
  StatusIndicator,
} from '@cloudscape-design/components';
import { FormatToggle } from './FormatToggle';
import type { EditorAnnotation, CodeFormat } from '../../types';

// Import Ace editor
import ace from 'ace-builds';
import 'ace-builds/src-noconflict/mode-yaml';
import 'ace-builds/src-noconflict/mode-json';
import 'ace-builds/src-noconflict/theme-tomorrow';

// Create debug logger for code panel
const log = createDebugLogger({ prefix: '[CodePanel]' });

/**
 * Props for EditableCodePanel
 */
export interface EditableCodePanelProps {
  /** Current content */
  content: string;
  /** Current format */
  format: CodeFormat;
  /** Callback when content changes */
  onContentChange: (content: string) => void;
  /** Callback when format changes */
  onFormatChange: (format: CodeFormat) => void;
  /** Error annotations to display */
  annotations?: EditorAnnotation[];
  /** Parse error message */
  parseError?: string | null;
  /** Line to highlight (for errors/warnings) */
  highlightLine?: number;
  /** Line to highlight for focus sync (separate from error highlighting) */
  focusedLine?: number | null;
  /** Source of the focus event - only scroll when 'form' */
  focusSource?: 'form' | 'editor' | null;
  /** Callback when cursor position changes */
  onCursorChange?: (line: number, column: number) => void;
  /** Whether the editor is read-only */
  readOnly?: boolean;
  /** Whether content is being validated */
  isValidating?: boolean;
  /** Header title */
  title?: string;
  /** Header description */
  description?: string;
}

/**
 * Editable code panel with error annotations
 */
export function EditableCodePanel({
  content,
  format,
  onContentChange,
  onFormatChange,
  annotations = [],
  parseError,
  highlightLine,
  focusedLine,
  focusSource,
  onCursorChange,
  readOnly = false,
  isValidating = false,
  title = 'Configuration Editor',
  description = 'Edit your configuration directly in YAML or JSON',
}: EditableCodePanelProps): React.ReactElement {
  const editorRef = useRef<HTMLDivElement>(null);
  const aceEditorRef = useRef<ace.Ace.Editor | null>(null);
  const isInternalChange = useRef(false);

  // Annotations are already in Ace format (0-indexed rows from createEditorAnnotations)
  const aceAnnotations = useMemo(() => {
    return annotations.map(ann => ({
      row: ann.row, // Already 0-indexed from createEditorAnnotations
      column: ann.column,
      text: ann.text,
      type: ann.type,
    }));
  }, [annotations]);

  // Initialize Ace editor
  useEffect(() => {
    if (editorRef.current && !aceEditorRef.current) {
      const editor = ace.edit(editorRef.current);
      editor.setTheme('ace/theme/tomorrow');
      editor.setReadOnly(readOnly);
      editor.setShowPrintMargin(false);
      editor.setHighlightActiveLine(true);
      editor.renderer.setScrollMargin(10, 10, 0, 0);
      editor.setOptions({
        fontSize: '14px',
        fontFamily: "Monaco, Menlo, 'Ubuntu Mono', monospace",
        showGutter: true,
        highlightGutterLine: true,
        wrap: false,
        tabSize: 2,
        useSoftTabs: true,
        enableBasicAutocompletion: false,
        enableLiveAutocompletion: false,
      });

      // Handle content changes
      editor.on('change', () => {
        if (!isInternalChange.current) {
          const newContent = editor.getValue();
          onContentChange(newContent);
        }
      });

      aceEditorRef.current = editor;
    }

    return () => {
      if (aceEditorRef.current) {
        aceEditorRef.current.destroy();
        aceEditorRef.current = null;
      }
    };
  }, []);

  // Update read-only state
  useEffect(() => {
    if (aceEditorRef.current) {
      aceEditorRef.current.setReadOnly(readOnly);
    }
  }, [readOnly]);

  // Update editor mode when format changes
  useEffect(() => {
    if (aceEditorRef.current) {
      const mode = format === 'yaml' ? 'ace/mode/yaml' : 'ace/mode/json';
      aceEditorRef.current.session.setMode(mode);
    }
  }, [format]);

  // Update content when it changes externally
  useEffect(() => {
    if (aceEditorRef.current) {
      const currentContent = aceEditorRef.current.getValue();
      if (currentContent !== content) {
        isInternalChange.current = true;
        const cursorPosition = aceEditorRef.current.getCursorPosition();
        aceEditorRef.current.setValue(content, -1);
        aceEditorRef.current.moveCursorToPosition(cursorPosition);
        isInternalChange.current = false;
      }
    }
  }, [content]);

  // Update annotations
  useEffect(() => {
    if (aceEditorRef.current) {
      aceEditorRef.current.session.setAnnotations(aceAnnotations);
    }
  }, [aceAnnotations]);

  // Handle line highlighting
  useEffect(() => {
    if (aceEditorRef.current && highlightLine && highlightLine > 0) {
      const editor = aceEditorRef.current;
      
      // Clear previous markers
      const markers = editor.session.getMarkers(false);
      if (markers) {
        Object.keys(markers).forEach(id => {
          const marker = markers[parseInt(id, 10)];
          if (marker && marker.clazz === 'ace-highlight-line') {
            editor.session.removeMarker(parseInt(id, 10));
          }
        });
      }

      // Add highlight marker
      const Range = ace.require('ace/range').Range;
      const range = new Range(highlightLine - 1, 0, highlightLine - 1, Infinity);
      editor.session.addMarker(range, 'ace-highlight-line', 'fullLine', false);

      // Scroll to line
      editor.scrollToLine(highlightLine - 1, true, true, () => {});
    }
  }, [highlightLine]);

  // Add error markers for annotations
  useEffect(() => {
    if (aceEditorRef.current) {
      const editor = aceEditorRef.current;
      
      // Clear previous error markers
      const markers = editor.session.getMarkers(false);
      if (markers) {
        Object.keys(markers).forEach(id => {
          const marker = markers[parseInt(id, 10)];
          if (marker && marker.clazz?.startsWith('ace-error-')) {
            editor.session.removeMarker(parseInt(id, 10));
          }
        });
      }

      // Add error markers
      const Range = ace.require('ace/range').Range;
      annotations.forEach(ann => {
        if (ann.type === 'error') {
          // ann.row is already 0-indexed from createEditorAnnotations
          const range = new Range(ann.row, 0, ann.row, Infinity);
          editor.session.addMarker(range, 'ace-error-line', 'fullLine', false);
        }
      });
    }
  }, [annotations]);

  // Handle cursor change for focus sync
  useEffect(() => {
    if (aceEditorRef.current && onCursorChange) {
      const editor = aceEditorRef.current;
      
      const handleCursorChange = () => {
        const position = editor.getCursorPosition();
        // Convert from 0-indexed to 1-indexed for external use
        const line = position.row + 1;
        const column = position.column + 1;
        log.debug('Cursor change event', { 
          aceRow: position.row, 
          aceColumn: position.column,
          externalLine: line,
          externalColumn: column,
        });
        onCursorChange(line, column);
      };

      editor.selection.on('changeCursor', handleCursorChange);
      log.debug('Cursor change listener attached');

      return () => {
        editor.selection.off('changeCursor', handleCursorChange);
        log.debug('Cursor change listener detached');
      };
    }
  }, [onCursorChange]);

  // Handle focus line highlighting (separate from error highlighting)
  useEffect(() => {
    if (aceEditorRef.current) {
      const editor = aceEditorRef.current;
      
      log.debug('Focus line effect triggered', { focusedLine, focusSource });
      
      // Clear previous focus markers
      const markers = editor.session.getMarkers(false);
      let clearedCount = 0;
      if (markers) {
        Object.keys(markers).forEach(id => {
          const marker = markers[parseInt(id, 10)];
          if (marker && marker.clazz === 'ace-focus-highlight') {
            editor.session.removeMarker(parseInt(id, 10));
            clearedCount++;
          }
        });
      }
      if (clearedCount > 0) {
        log.debug('Cleared previous focus markers', { count: clearedCount });
      }

      // Add focus marker if focusedLine is set
      if (focusedLine && focusedLine > 0) {
        const Range = ace.require('ace/range').Range;
        const aceRow = focusedLine - 1;
        const range = new Range(aceRow, 0, aceRow, Infinity);
        const markerId = editor.session.addMarker(range, 'ace-focus-highlight', 'fullLine', false);
        log.debug('Added focus highlight marker', { 
          focusedLine, 
          aceRow, 
          markerId,
        });

        // Only scroll when focus comes from form, not when user clicks in editor
        if (focusSource === 'form') {
          editor.scrollToLine(aceRow, true, true, () => {});
          log.debug('Scrolled to focused line', { aceRow });
        } else {
          log.debug('Skipping scroll - focus source is editor', { focusSource });
        }
      } else {
        log.debug('No focus line to highlight', { focusedLine });
      }
    }
  }, [focusedLine, focusSource]);

  // Status indicator
  const statusIndicator = useMemo(() => {
    if (isValidating) {
      return <StatusIndicator type="loading">Validating...</StatusIndicator>;
    }
    if (parseError) {
      return <StatusIndicator type="error">Parse Error</StatusIndicator>;
    }
    if (annotations.some(a => a.type === 'error')) {
      return <StatusIndicator type="error">Validation Errors</StatusIndicator>;
    }
    return <StatusIndicator type="success">Valid</StatusIndicator>;
  }, [isValidating, parseError, annotations]);

  return (
    <Container
      header={
        <Header
          variant="h2"
          description={description}
          actions={
            <SpaceBetween direction="horizontal" size="s">
              {statusIndicator}
              <FormatToggle format={format} onChange={onFormatChange} />
              <CopyToClipboard
                copyButtonAriaLabel="Copy configuration"
                copySuccessText="Configuration copied"
                copyErrorText="Failed to copy"
                textToCopy={content}
              />
            </SpaceBetween>
          }
        >
          {title}
        </Header>
      }
    >
      <SpaceBetween size="s">
        {/* Parse error alert */}
        {parseError && (
          <Alert type="error" header="Parse Error">
            {parseError}
          </Alert>
        )}
        
        {/* Editor */}
        <Box>
          <div
            ref={editorRef}
            style={{
              width: '100%',
              height: 'calc(100vh - 320px)',
              minHeight: '400px',
              border: parseError ? '2px solid #d91515' : '1px solid #e9ebed',
              borderRadius: '8px',
            }}
          />
        </Box>
      </SpaceBetween>
      
      <style>{`
        .ace-highlight-line {
          background-color: #fff3cd !important;
          position: absolute;
        }
        .ace-error-line {
          background-color: #ffebee !important;
          position: absolute;
        }
        .ace-focus-highlight {
          background-color: #e6f7ff !important;
          border-left: 3px solid #0972d3 !important;
          position: absolute;
          transition: background-color 0.3s ease-out;
        }
        .ace_gutter-cell.ace_error {
          background-color: #d91515;
          color: white;
        }
        .ace_gutter-cell.ace_warning {
          background-color: #f89c24;
          color: white;
        }
        .ace_gutter-cell.ace_info {
          background-color: #0972d3;
          color: white;
        }
      `}</style>
    </Container>
  );
}

export default EditableCodePanel;
