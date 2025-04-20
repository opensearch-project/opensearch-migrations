"use client";

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

function TransformationSectionContent() {
  const { state } = usePlayground();
  const { addTransformation } = usePlaygroundActions();

  const handleAddTransformation = () => {
    addTransformation(`Transformation ${state.transformations.length + 1}`, "");
  };

  return (
    <Container header={<Header variant="h3">Transformations</Header>}>
      <SpaceBetween size="m">
        {state.transformations.length === 0 ? (
          <Box>No transformations. Add one to get started.</Box>
        ) : (
          state.transformations.map((transform) => (
            <Box key={transform.id}>{transform.name}</Box>
          ))
        )}
        <Button onClick={handleAddTransformation}>Add Transformation</Button>
      </SpaceBetween>
    </Container>
  );
}

export default function TransformationSection() {
  return (
    <PlaygroundProvider>
      <TransformationSectionContent />
    </PlaygroundProvider>
  );
}
