"use client";

import { useState, useEffect, useRef, useCallback } from "react";
import AceEditor, { IAnnotation } from "react-ace";
import { usePlayground } from "@/context/PlaygroundContext";
import { usePlaygroundActions } from "@/hooks/usePlaygroundActions";
import { SaveState, SaveStatus } from "@/types/SaveStatus";

// Import ace-builds core
import ace from "ace-builds";
import beautify from "ace-builds/src-noconflict/ext-beautify";

// Import modes
import "ace-builds/src-noconflict/mode-json";
import "ace-builds/src-noconflict/mode-javascript";
import "ace-builds/src-noconflict/theme-github";
import "ace-builds/src-noconflict/ext-language_tools";
// This seems like it should be imported from the line above, but there are missing values (like showing highlighted text) without this
import "ace-builds/css/theme/github.css";

// Import workers
import jsonWorkerUrl from "ace-builds/src-noconflict/worker-json";
import javascriptWorkerUrl from "ace-builds/src-noconflict/worker-javascript";
import { defaultContent } from "./DefaultTransformationContent";

// Configure Ace to use the imported workers
ace.config.setModuleUrl("ace/mode/json_worker", jsonWorkerUrl);
ace.config.setModuleUrl("ace/mode/javascript_worker", javascriptWorkerUrl);

interface AceEditorComponentProps {
  itemId: string;
  mode: "json" | "javascript";
  formatRef: React.RefObject<(() => void) | null>;
  onSaveStatusChange: (status: SaveState) => void;
}

export default function AceEditorComponent({
  itemId,
  mode,
  formatRef,
  onSaveStatusChange,
}: Readonly<AceEditorComponentProps>) {
  const { state } = usePlayground();
  const { updateTransformation } = usePlaygroundActions();
  const [content, setContent] = useState("");
  // Use refs for validation errors to prevent re-renders
  const validationErrorsRef = useRef<IAnnotation[]>([]);
  const editorRef = useRef<AceEditor>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [dimensions, setDimensions] = useState({ width: 500, height: 300 });

  // Track if content has been initialized
  const contentInitializedRef = useRef<boolean>(false);

  // Find the transformation by ID
  const transformation = state.transformations.find((t) => t.id === itemId);

  // Set up ResizeObserver to monitor container size
  useEffect(() => {
    if (!containerRef.current) return;

    const resizeObserver = new ResizeObserver((entries) => {
      const { width, height } = entries[0].contentRect;
      // Subtract some padding for better appearance
      setDimensions({
        width: Math.max(width - 20, 100), // Ensure minimum width
        height: Math.max(height - 20, 100), // Ensure minimum height
      });
    });

    resizeObserver.observe(containerRef.current);
    return () => resizeObserver.disconnect();
  }, []);

  // Reset initialization flag when itemId changes
  useEffect(() => {
    contentInitializedRef.current = false;
  }, [itemId]);

  // Initialize content from the transformation only once per itemId
  useEffect(() => {
    if (transformation && !contentInitializedRef.current) {
      setContent(transformation.content ?? defaultContent);
      onSaveStatusChange({
        status: SaveStatus.SAVED,
        savedAt: new Date(Date.now()),
        errors: [],
      });
      contentInitializedRef.current = true;
    }
  }, [itemId, transformation, onSaveStatusChange]);

  // Save the current content to local storage
  const saveContent = useCallback(() => {
    // Immediately set content to the exact content from the editor ref (our state may be stale)
    const editorContent = editorRef.current?.editor.getValue();
    const activeContent = editorContent ?? content;
    // Skip update if transformation doesn't exist or if content is unchanged.
    if (!transformation || activeContent === transformation.content) return;

    // Check for validation errors
    const blockingErrors = validationErrorsRef.current.filter(
      (e) => e.type === "error",
    );
    if (blockingErrors.length > 0) {
      onSaveStatusChange({
        status: SaveStatus.BLOCKED,
        savedAt: null,
        errors: blockingErrors,
      });
      return;
    }

    updateTransformation(itemId, transformation.name, activeContent);
    onSaveStatusChange({
      status: SaveStatus.SAVED,
      savedAt: new Date(Date.now()),
      errors: [],
    });
  }, [
    content,
    itemId,
    transformation,
    updateTransformation,
    onSaveStatusChange,
    editorRef,
  ]);

  // Format the code based on the mode
  const formatCode = useCallback(() => {
    if (!content) return;
    try {
      if (editorRef.current) {
        beautify.beautify(editorRef.current.editor.session);
      }
    } catch (error) {
      console.error(`Error formatting code for ${itemId}:`, error);
    }
    saveContent();
  }, [content, saveContent, itemId]);

  // Handle keyboard shortcuts
  const handleKeyDown = useCallback(
    (event: KeyboardEvent) => {
      // Check for Ctrl+S or Cmd+S
      if ((event.ctrlKey || event.metaKey) && event.key === "s") {
        event.preventDefault();
        saveContent();
      }
    },
    [saveContent],
  );

  // Add keyboard event listener
  useEffect(() => {
    const editor = editorRef.current?.editor;
    if (editor) {
      editor.container.addEventListener("keydown", handleKeyDown);
    }

    return () => {
      if (editor) {
        editor.container.removeEventListener("keydown", handleKeyDown);
      }
    };
  }, [handleKeyDown]);

  const handleChange = (newContent: string) => {
    if (newContent === content) return; // Skip if content is unchanged
    setContent(newContent);

    // Skip update if transformation doesn't exist
    if (!transformation) {
      return;
    }

    // Check for validation errors
    const blockingErrors = validationErrorsRef.current.filter(
      (e) => e.type === "error",
    );
    if (blockingErrors.length > 0) {
      onSaveStatusChange({
        status: SaveStatus.BLOCKED,
        savedAt: null,
        errors: blockingErrors,
      });
    } else {
      // Mark as unsaved if content is different from saved content
      const isSaved = transformation.content === newContent;
      if (!isSaved) {
        onSaveStatusChange({
          status: SaveStatus.UNSAVED,
          savedAt: null,
          errors: [],
        });
      }
    }

    saveContent();
  };

  // Expose formatCode function to parent component via ref
  useEffect(() => {
    formatRef.current = formatCode;
  }, [formatCode, formatRef]);

  return (
    <div
      ref={containerRef}
      style={{ width: "100%", height: "100%", minHeight: "200px" }}
    >
      <AceEditor
        ref={editorRef}
        mode={mode}
        theme="github"
        value={content}
        onChange={handleChange}
        onValidate={(errors) => {
          // The UI gets "twitchy" if we set state (and therefore re-render) on every validation
          // So we're using a ref to store the errors instead
          validationErrorsRef.current = errors as IAnnotation[];
        }}
        name={itemId}
        debounceChangePeriod={500}
        width={`${dimensions.width}px`}
        height={`${dimensions.height}px`}
        editorProps={{ $blockScrolling: false }}
        setOptions={{
          enableBasicAutocompletion: true,
        }}
        showGutter={true}
        showPrintMargin={false}
        highlightActiveLine={true}
        minLines={10}
        tabSize={2}
        commands={beautify.commands.map((command) => ({
          name: command.name,
          bindKey:
            typeof command.bindKey === "string"
              ? { win: command.bindKey, mac: command.bindKey }
              : command.bindKey,
          exec: command.exec,
        }))}
      />
    </div>
  );
}
