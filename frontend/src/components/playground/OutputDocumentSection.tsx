import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import { usePlayground } from "../../context/PlaygroundContext";

// Inner component that uses the usePlayground hook
export default function OutputDocumentSection() {
  const { state } = usePlayground();

  return (
    <Container header={<Header variant="h3">Output Documents</Header>}>
      <SpaceBetween size="m">
        {state.outputDocuments.length === 0 ? (
          <Box>No output documents.</Box>
        ) : (
          state.outputDocuments.map((doc) => <Box key={doc.id}>{doc.name}</Box>)
        )}
      </SpaceBetween>
    </Container>
  );
}
