import React from "react";
import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import Button from "@cloudscape-design/components/button";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import FileUpload from "@cloudscape-design/components/file-upload";
import FormField from "@cloudscape-design/components/form-field";
import CodeView from "@cloudscape-design/code-view/code-view";
import javascriptHighlight from "@cloudscape-design/code-view/highlight/javascript";

import { usePlayground } from "../../context/PlaygroundContext";
import { usePlaygroundActions } from "../../hooks/usePlaygroundActions";
import { Popover } from "@cloudscape-design/components";

export default function InputDocumentSection() {
  const { state } = usePlayground();
  const { addInputDocument } = usePlaygroundActions();

  const [uploadFileList, setUploadFileList] = React.useState<File[]>([]);
  const [fileErrors, setFileErrors] = React.useState<(string | null)[]>([]);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();

    if (uploadFileList.length === 0) return;
    setFileErrors([]);

    try {
      for (const [i, file] of uploadFileList.entries()) {
        // Read file content
        const content = await readFileAsText(file);

        // Validate JSON or newline-delimited JSON
        const potentialError = validateJsonContent(content);
        if (potentialError) {
          setFileErrors([...fileErrors, potentialError]);
          continue; // Skip this file if there's an error
        }

        // Add as new input document
        addInputDocument(file.name, content);
        // Remove the file from the upload list
        setUploadFileList((prev) => prev.filter((_, index) => index !== i));
      }
    } catch (error) {
      console.error("Error processing files:", error);
    }
  };

  // Helper function to read file content
  const readFileAsText = (file: File): Promise<string> => {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as string);
      reader.onerror = () =>
        reject(new Error(`Failed to read file: ${file.name}`));
      reader.readAsText(file);
    });
  };

  // Helper function to validate JSON content
  const validateJsonContent = (content: string): string | null => {
    try {
      // Try parsing as regular JSON first
      JSON.parse(content);
      return null;
    } catch (e) {
      // If not regular JSON, check if it's newline-delimited JSON
      const lines = content.trim().split("\n");

      for (const line of lines) {
        if (line.trim()) {
          try {
            JSON.parse(line); // Will throw if invalid
            return null; // Valid JSON found
          } catch (lineError: unknown) {
            const errorMessage =
              lineError instanceof Error ? lineError.message : "Unknown error";
            return `Invalid JSON format in file: ${errorMessage}`;
          }
        }
      }
      return "File is not valid JSON data";
    }
  };

  const prettyPrintJson = (json: string): string => {
    try {
      const parsed = JSON.parse(json);
      return JSON.stringify(parsed, null, 2);
    } catch (e) {
      console.error("Error pretty printing JSON:", e);
      return json; // Return original if error occurs
    }
  };

  return (
    <Container header={<Header variant="h3">Input Documents</Header>}>
      <SpaceBetween size="m">
        {state.inputDocuments.length === 0 ? (
          <Box>No input documents.</Box>
        ) : (
          state.inputDocuments.map((doc) => (
            <Popover
              key={doc.id}
              size="large"
              fixedWidth
              renderWithPortal
              content={
                <CodeView
                  content={prettyPrintJson(doc.content)}
                  highlight={javascriptHighlight}
                />
              }
            >
              <Box>{doc.name}</Box>
            </Popover>
          ))
        )}
        <form onSubmit={handleSubmit}>
          <SpaceBetween size="xs">
            <FormField>
              <FileUpload
                onChange={({ detail }) => {
                  setUploadFileList(detail.value);
                  setFileErrors([]);
                }}
                value={uploadFileList}
                multiple
                accept="application/json"
                i18nStrings={{
                  uploadButtonText: (e) => (e ? "Choose files" : "Choose file"),
                  dropzoneText: (e) =>
                    e ? "Drop files to upload" : "Drop file to upload",
                  removeFileAriaLabel: (e) => `Remove file ${e + 1}`,
                }}
                fileErrors={fileErrors}
                showFileLastModified
                showFileSize
                tokenLimit={5}
                constraintText="JSON input documents"
              />
            </FormField>
            {uploadFileList.length > 0 && (
              <Button variant="primary">Upload</Button>
            )}
          </SpaceBetween>
        </form>
      </SpaceBetween>
    </Container>
  );
}
