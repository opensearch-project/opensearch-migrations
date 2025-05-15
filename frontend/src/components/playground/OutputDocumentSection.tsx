import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import Alert from "@cloudscape-design/components/alert";
import Spinner from "@cloudscape-design/components/spinner";
import { usePlayground } from "@/context/PlaygroundContext";
import { DocumentItemWithPopoverCodeView } from "./DocumentItemWithPopoverCodeView";
import { ReactNode } from "react";

export default function OutputDocumentSection() {
  const { state } = usePlayground();
  const { isProcessingTransformations, transformationErrors } = state;

  // This code is extracted to deal with sonarqube complaints about the depth of the function
  const renderOutputContent = (): ReactNode => {
    if (isProcessingTransformations) {
      return (
        <Box textAlign="center">
          <Spinner size="large" />
          <Box variant="p" color="text-status-info">
            Processing transformations...
          </Box>
        </Box>
      );
    }

    if (state.outputDocuments.length === 0) {
      return <Box>No output documents.</Box>;
    }

    return state.outputDocuments.map((doc) => (
      <DocumentItemWithPopoverCodeView key={doc.id} document={doc} />
    ));
  };

  return (
    <Container header={<Header variant="h3">Output Documents</Header>}>
      <SpaceBetween size="m">
        {transformationErrors.length > 0 && (
          <Alert type="error" header="Transformation errors">
            <SpaceBetween size="s">
              {transformationErrors.map((error) => {
                const inputDoc = state.inputDocuments.find(
                  (doc) => doc.id === error.documentId,
                );
                const transformation = state.transformations.find(
                  (t) => t.id === error.transformationId,
                );

                return (
                  <Box key={error.documentId}>
                    Error in document `{inputDoc?.name ?? "Unknown"}`
                    {transformation
                      ? ` at transformation "${transformation.name}"`
                      : ""}
                    :{error.message}
                  </Box>
                );
              })}
            </SpaceBetween>
          </Alert>
        )}

        {renderOutputContent()}
      </SpaceBetween>
    </Container>
  );
}
