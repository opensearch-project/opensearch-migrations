import React, { useState, useEffect } from "react";
import Modal from "@cloudscape-design/components/modal";
import Box from "@cloudscape-design/components/box";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Button from "@cloudscape-design/components/button";
import FormField from "@cloudscape-design/components/form-field";
import Input from "@cloudscape-design/components/input";
import CodeEditor, {
  CodeEditorProps,
} from "@cloudscape-design/components/code-editor";
import { InputDocument } from "@/context/PlaygroundContext";
import { prettyPrintJson, validateJsonContent } from "@/utils/jsonUtils";

// Ace config details are pulled significantly from https://github.com/cloudscape-design/components/issues/703
// Base imports for ace editor
import ace from "ace-builds";
import "ace-builds/src-noconflict/ext-language_tools";
import "ace-builds/css/ace.css";

// Theme - Dawn
import "ace-builds/src-noconflict/theme-dawn";
import "ace-builds/css/theme/dawn.css";

// Language support - Json
import "ace-builds/src-noconflict/mode-json";
import "ace-builds/src-noconflict/snippets/json";

// CSP-compliant mode support
// From: https://cloudscape.design/components/code-editor/?tabId=api
ace.config.set("useStrictCSP", true);
ace.config.set("loadWorkerFromBlob", false);

import jsonWorkerPath from "ace-builds/src-noconflict/worker-json";
ace.config.setModuleUrl("ace/mode/json_worker", jsonWorkerPath);

interface EditDocumentModalProps {
  document: InputDocument | null;
  visible: boolean;
  onDismiss: () => void;
  onSave: (id: string, name: string, content: string) => void;
}

export const EditDocumentModal: React.FC<EditDocumentModalProps> = ({
  document,
  visible,
  onDismiss,
  onSave,
}) => {
  const [name, setName] = useState("");
  const [content, setContent] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [preferences, setPreferences] = useState<
    CodeEditorProps.Preferences | undefined
  >(undefined);

  // Reset form when document changes
  useEffect(() => {
    if (document) {
      setName(document.name);
      setContent(prettyPrintJson(document.content));
      setError(null);
    }
  }, [document]);

  const handleSave = () => {
    if (!content) {
      setError("Content cannot be empty");
      return;
    }
    try {
      const validationError = validateJsonContent(content);
      if (validationError) {
        setError(`Invalid JSON: ${validationError}`);
        return;
      }

      if (document) {
        onSave(document.id, name, JSON.stringify(JSON.parse(content)));
      }
      onDismiss();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unknown error");
    }
  };

  const handleCodeChange = (value: string) => {
    setContent(value);
    setError(null);
  };

  return (
    <Modal
      visible={visible}
      onDismiss={onDismiss}
      header="Edit Document"
      size="large"
      footer={
        <Box float="right">
          <SpaceBetween direction="horizontal" size="xs">
            <Button variant="link" onClick={onDismiss}>
              Cancel
            </Button>
            <Button variant="primary" onClick={handleSave} disabled={!!error}>
              Save
            </Button>
          </SpaceBetween>
        </Box>
      }
    >
      <SpaceBetween size="m">
        <FormField label="Document name" errorText={null}>
          <Input
            value={name}
            onChange={({ detail }) => setName(detail.value)}
          />
        </FormField>

        <FormField label="Content" errorText={error}>
          <CodeEditor
            language="json"
            value={content}
            themes={{ dark: [], light: ["dawn"] }}
            onDelayedChange={({ detail }) => handleCodeChange(detail.value)}
            preferences={preferences}
            onPreferencesChange={(e) => setPreferences(e.detail)}
            ace={ace}
            i18nStrings={{
              loadingState: "Loading code editor",
              errorState: "There was an error loading the code editor.",
              errorStateRecovery: "Retry",
              editorGroupAriaLabel: "Code editor",
              statusBarGroupAriaLabel: "Status bar",
              cursorPosition: (row, column) => `Ln ${row}, Col ${column}`,
              errorsTab: "Errors",
              warningsTab: "Warnings",
              preferencesButtonAriaLabel: "Preferences",
              paneCloseButtonAriaLabel: "Close",
              preferencesModalHeader: "Preferences",
              preferencesModalCancel: "Cancel",
              preferencesModalConfirm: "Confirm",
              preferencesModalWrapLines: "Wrap lines",
              preferencesModalTheme: "Theme",
              preferencesModalLightThemes: "Light themes",
              preferencesModalDarkThemes: "Dark themes",
            }}
          />
        </FormField>
      </SpaceBetween>
    </Modal>
  );
};
