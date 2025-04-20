import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import Button from "@cloudscape-design/components/button";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import {
  PlaygroundProvider,
  usePlayground,
} from "../../context/PlaygroundContext";
import { usePlaygroundActions } from "../../hooks/usePlaygroundActions";

function InputDocumentSectionContent() {
  const { state } = usePlayground();
  const { addInputDocument } = usePlaygroundActions();

  const handleAddDocument = () => {
    addInputDocument(`Document ${state.inputDocuments.length + 1}`, "");
  };

  return (
    <Container header={<Header variant="h3">Input Documents</Header>}>
      <SpaceBetween size="m">
        {state.inputDocuments.length === 0 ? (
          <Box>No input documents.</Box>
        ) : (
          state.inputDocuments.map((doc) => <Box key={doc.id}>{doc.name}</Box>)
        )}
        <Button onClick={handleAddDocument}>Add Input Document</Button>
      </SpaceBetween>
    </Container>
  );
}

export default function InputDocumentSection() {
  return (
    <PlaygroundProvider>
      <InputDocumentSectionContent />
    </PlaygroundProvider>
  );
}
