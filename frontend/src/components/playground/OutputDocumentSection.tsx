import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import {
  usePlayground,
  PlaygroundProvider,
} from "../../context/PlaygroundContext";

// Inner component that uses the usePlayground hook
function OutputDocumentSectionContent() {
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

// Wrapper component that provides the PlaygroundProvider
export default function OutputDocumentSection() {
  return (
    <PlaygroundProvider>
      <OutputDocumentSectionContent />
    </PlaygroundProvider>
  );
}
