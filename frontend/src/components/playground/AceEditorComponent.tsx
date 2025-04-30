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
// This seems like it should be imported from the line above, but there are missing values (like showing highlighted text) without this
import "ace-builds/css/theme/github.css";

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
  // Use a ref instead of state for validation errors to prevent re-renders
  const validationErrorsRef = useRef<IAnnotation[]>([]);

  // Find the transformation by ID
  const transformation = state.transformations.find((t) => t.id === itemId);

  // Initialize content from the transformation
  useEffect(() => {
    if (transformation) {
      setContent(transformation.content || "");
    }
  }, [transformation]);

  // Handle content change and save (debounce is handled internally by AceEditor)
  const handleChange = (newContent: string) => {
    setContent(newContent);
    console.log("Current content:", newContent);
    console.log("Validation errors:", validationErrorsRef.current);

    // Skip update if transformation doesn't exist
    if (!transformation) {
      return;
    }

    // Skip update if content is the same
    if (transformation.content === newContent) {
      return;
    }

    console.log("Updating transformation:", transformation.name);
    updateTransformation(itemId, transformation.name, newContent);
  };

  return (
    <Box padding="s" variant="code">
      <AceEditor
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
        width="500px"
        editorProps={{ $blockScrolling: false }}
        setOptions={{
          enableBasicAutocompletion: true,
        }}
        showGutter={true}
        showPrintMargin={true}
        highlightActiveLine={true}
        minLines={10}
        tabSize={2}
      />
    </Box>
  );
}
