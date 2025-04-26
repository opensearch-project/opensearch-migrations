import React from "react";
import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import Button from "@cloudscape-design/components/button";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import FileUpload from "@cloudscape-design/components/file-upload";
import FormField from "@cloudscape-design/components/form-field";
import Spinner from "@cloudscape-design/components/spinner";

import { usePlayground } from "@/context/PlaygroundContext";
import { usePlaygroundActions } from "@/hooks/usePlaygroundActions";
import { useFileUpload } from "@/hooks/useFileUpload";
import { DocumentItemWithPopoverCodeView } from "./DocumentItemWithPopoverCodeView";

export default function InputDocumentSection() {
  const { state } = usePlayground();
  const { addInputDocument, removeInputDocument } = usePlaygroundActions();
  const {
    files,
    setFiles,
    errors,
    setErrors,
    isProcessing,
    processFiles,
    clearSuccessfulFiles,
  } = useFileUpload();

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();

    const results = await processFiles();

    // Add successful documents to the playground state
    results.forEach((result) => {
      if (result.success && result.content) {
        addInputDocument(result.fileName, result.content);
      }
    });

    // Clear successfully processed files from the upload list
    clearSuccessfulFiles(results);
  };

  return (
    <Container header={<Header variant="h3">Input Documents</Header>}>
      <SpaceBetween size="m">
        {state.inputDocuments.length === 0 ? (
          <Box>No input documents.</Box>
        ) : (
          state.inputDocuments.map((doc) => (
            <DocumentItemWithPopoverCodeView
              key={doc.id}
              document={doc}
              onDelete={removeInputDocument}
            />
          ))
        )}

        <form onSubmit={handleSubmit}>
          <SpaceBetween size="xs">
            <FormField errorText={errors.length > 0 ? errors : undefined}>
              <FileUpload
                onChange={({ detail }) => {
                  setFiles(detail.value);
                  setErrors([]);
                }}
                value={files}
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
                tokenLimit={5}
                constraintText="JSON or newline-delimited JSON files only"
              />
            </FormField>

            {isProcessing ? (
              <Spinner />
            ) : (
              files.length > 0 && (
                <Button variant="primary" disabled={isProcessing}>
                  Upload
                </Button>
              )
            )}
          </SpaceBetween>
        </form>
      </SpaceBetween>
    </Container>
  );
}
