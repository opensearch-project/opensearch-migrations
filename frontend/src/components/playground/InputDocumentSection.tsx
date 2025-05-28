import React, { useState } from "react";
import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import Button from "@cloudscape-design/components/button";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import FileUpload from "@cloudscape-design/components/file-upload";
import FormField from "@cloudscape-design/components/form-field";
import Spinner from "@cloudscape-design/components/spinner";

import { usePlayground, InputDocument } from "@/context/PlaygroundContext";
import { usePlaygroundActions } from "@/hooks/usePlaygroundActions";
import { useJSONFileUpload } from "@/hooks/useJSONFileUpload";
import { MAX_DOCUMENT_SIZE_MB } from "@/utils/sizeLimits";
import { DocumentItemWithPopoverCodeView } from "./DocumentItemWithPopoverCodeView";
import { EditDocumentModal } from "./EditDocumentModal";

export default function InputDocumentSection() {
  const { state, isQuotaExceeded } = usePlayground();
  const { addInputDocument, removeInputDocument, updateInputDocument } =
    usePlaygroundActions();

  // State for edit modal
  const [editingDocument, setEditingDocument] = useState<InputDocument | null>(
    null,
  );
  const [isEditModalVisible, setIsEditModalVisible] = useState(false);

  // Handler for edit button click
  const handleEditDocument = (document: InputDocument) => {
    setEditingDocument(document);
    setIsEditModalVisible(true);
  };

  // Handler for save changes
  const handleSaveDocument = (id: string, name: string, content: string) => {
    try {
      updateInputDocument(id, name, content);
    } catch (error) {
      console.error("Failed to update document:", error);
    }
  };
  const {
    files,
    setFiles,
    errors,
    setErrors,
    isProcessing,
    processFiles,
    clearSuccessfulFiles,
  } = useJSONFileUpload();

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();

    const results = await processFiles();
    const newErrors: string[] = [...errors];

    // Add successful documents to the playground state
    for (const result of results) {
      if (result.success && result.content) {
        try {
          addInputDocument(result.fileName, result.content);
        } catch (error) {
          // Handle storage limit error
          const errorMsg =
            error instanceof Error
              ? error.message
              : "Unknown error adding document";
          newErrors.push(errorMsg);
          result.success = false;
          result.error = errorMsg;
        }
      }
    }

    // Update errors if any occurred during document addition
    if (newErrors.length > errors.length) {
      setErrors(newErrors);
    }

    // Clear successfully processed files from the upload list
    clearSuccessfulFiles(results);
  };

  // Determine error text for file upload form field
  const getErrorText = () => {
    if (isQuotaExceeded) {
      return "Local storage quota exceeded. Please remove some documents before adding more.";
    }
    if (errors.length > 0) {
      return errors;
    }
    return undefined;
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
              onEdit={handleEditDocument}
            />
          ))
        )}

        <EditDocumentModal
          document={editingDocument}
          visible={isEditModalVisible}
          onDismiss={() => setIsEditModalVisible(false)}
          onSave={handleSaveDocument}
        />

        <form onSubmit={handleSubmit}>
          <SpaceBetween size="xs">
            <FormField errorText={getErrorText()}>
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
                constraintText={`JSON or newline-delimited JSON files only. Maximum file size: ${MAX_DOCUMENT_SIZE_MB}MB`}
              />
            </FormField>

            {isProcessing ? (
              <Spinner />
            ) : (
              files.length > 0 && (
                <Button
                  variant="primary"
                  disabled={isProcessing ?? isQuotaExceeded}
                >
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
