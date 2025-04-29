"use client";

import { useState, useEffect, useRef } from "react";
import AceEditor, { IAnnotation } from "react-ace";
import Box from "@cloudscape-design/components/box";
import { usePlayground } from "@/context/PlaygroundContext";
import { usePlaygroundActions } from "@/hooks/usePlaygroundActions";

// Import ace-builds core
import ace from "ace-builds";

// Import modes
import "ace-builds/src-noconflict/mode-json";
import "ace-builds/src-noconflict/mode-javascript";
import "ace-builds/src-noconflict/theme-github";
import "ace-builds/src-noconflict/ext-language_tools";

// Import workers
import jsonWorkerUrl from "ace-builds/src-noconflict/worker-json";
import javascriptWorkerUrl from "ace-builds/src-noconflict/worker-javascript";

// Configure Ace to use the imported workers
ace.config.setModuleUrl("ace/mode/json_worker", jsonWorkerUrl);
ace.config.setModuleUrl("ace/mode/javascript_worker", javascriptWorkerUrl);

interface AceEditorComponentProps {
  itemId: string;
  mode?: "json" | "javascript";
}

export default function AceEditorComponent({
  itemId,
  mode = "json",
}: Readonly<AceEditorComponentProps>) {
  const { state } = usePlayground();
  const { updateTransformation } = usePlaygroundActions();
  const [content, setContent] = useState("");
  const [validationErrors, setValidationErrors] = useState<IAnnotation[]>([]);
  const isUpdatingRef = useRef(false);

  // Find the transformation by ID
  const transformation = state.transformations.find((t) => t.id === itemId);

  // Initialize content from the transformation
  useEffect(() => {
    // Skip if we're in the middle of updating
    if (isUpdatingRef.current) {
      return;
    }

    if (transformation) {
      setContent(transformation.content || "");
    }
  }, [transformation]);

  // Handle content change and save (debounce is handled internally by AceEditor)
  const handleChange = (newContent: string) => {
    setContent(newContent);

    // Skip update if transformation doesn't exist
    if (!transformation) {
      return;
    }

    // Skip update if content is the same
    if (transformation.content === newContent) {
      return;
    }

    // Only save if there are no validation errors
    if (validationErrors.length === 0) {
      // Set flag to prevent re-initialization from the useEffect
      isUpdatingRef.current = true;

      // Update the transformation
      updateTransformation(itemId, transformation.name, newContent);

      // Reset flag after a short delay to allow state to settle
      setTimeout(() => {
        isUpdatingRef.current = false;
      }, 100);
    }
  };

  return (
    <Box padding="s" variant="code">
      <AceEditor
        mode={mode}
        theme="github"
        value={content}
        onChange={handleChange}
        onValidate={(errors) => {
          setValidationErrors(errors as IAnnotation[]);
        }}
        name={itemId}
        debounceChangePeriod={100}
        width="100%"
        editorProps={{ $blockScrolling: true }}
        setOptions={{
          enableBasicAutocompletion: true,
        }}
      />
    </Box>
  );
}
