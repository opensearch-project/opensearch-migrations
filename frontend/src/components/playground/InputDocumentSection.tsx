import React from "react";
import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import Button from "@cloudscape-design/components/button";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import FileUpload from "@cloudscape-design/components/file-upload";
import FormField from "@cloudscape-design/components/form-field";

import { usePlayground } from "../../context/PlaygroundContext";
import { usePlaygroundActions } from "../../hooks/usePlaygroundActions";

export default function InputDocumentSection() {
  const { state } = usePlayground();
  const { addInputDocument } = usePlaygroundActions();

  const [uploadFileList, setUploadFileList] = React.useState<File[]>([]);

  const handleAddDocument = () => {
    addInputDocument(`Document ${state.inputDocuments.length + 1}`, "");
  };

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();

    if (uploadFileList.length === 0) return;

    try {
      for (const file of uploadFileList) {
        // Read file content
        const content = await readFileAsText(file);

        // Validate JSON or newline-delimited JSON
        validateJsonContent(content);

        // Add as new input document
        addInputDocument(file.name, content);
      }

      // Clear the upload file list on success
      setUploadFileList([]);
    } catch (error) {
      console.error("Error processing files:", error);
      // Could add error handling UI here
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
  const validateJsonContent = (content: string): void => {
    try {
      // Try parsing as regular JSON first
      JSON.parse(content);
    } catch (e) {
      // If not regular JSON, check if it's newline-delimited JSON
      const lines = content.trim().split("\n");
      let isValid = false;

      for (const line of lines) {
        if (line.trim()) {
          try {
            JSON.parse(line); // Will throw if invalid
            isValid = true;
          } catch (lineError: unknown) {
            const errorMessage =
              lineError instanceof Error ? lineError.message : "Unknown error";
            throw new Error(`Invalid JSON format in file: ${errorMessage}`);
          }
        }
      }

      if (!isValid) {
        throw new Error("File contains no valid JSON data");
      }
    }
  };

  return (
    <Container header={<Header variant="h3">Input Documents</Header>}>
      <SpaceBetween size="m">
        {state.inputDocuments.length === 0 ? (
          <Box>No input documents.</Box>
        ) : (
          state.inputDocuments.map((doc) => <Box key={doc.id}>{doc.name}</Box>)
        )}
        <form onSubmit={handleSubmit}>
          <FormField label="Form field label" description="Description">
            <FileUpload
              onChange={({ detail }) => {
                setUploadFileList(detail.value);
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
              showFileLastModified
              showFileSize
              tokenLimit={3}
              constraintText="JSON input documents"
            />
          </FormField>
          <Button variant="primary">Upload</Button>
        </form>
      </SpaceBetween>
    </Container>
  );
}
