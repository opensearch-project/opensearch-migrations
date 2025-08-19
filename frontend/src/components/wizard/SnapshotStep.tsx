"use client";

import { FC } from "react";
import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import FormField from "@cloudscape-design/components/form-field";
import RadioGroup from "@cloudscape-design/components/radio-group";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import Alert from "@cloudscape-design/components/alert";
import Input from "@cloudscape-design/components/input";

interface SnapshotStepProps {
  sessionName: string;
}

const SnapshotStep: FC<SnapshotStepProps> = ({ sessionName }) => {
  return (
    <Container header={<Header variant="h2">Snapshot Configuration</Header>}>
      <SpaceBetween size="l">
        <Box>
          <Alert type="info">
            Configure snapshot settings for migration session: {sessionName}
          </Alert>
        </Box>
        
        <FormField
          label="Snapshot Option"
          description="Choose whether to create a new snapshot or use an existing one"
        >
          <RadioGroup
            items={[
              { value: "create", label: "Create new snapshot" },
              { value: "existing", label: "Use existing snapshot" }
            ]}
            value="create"
            onChange={() => {}}
          />
        </FormField>
        
        <FormField
          label="Snapshot Name"
          description="Enter a name for the snapshot"
        >
          <Input
            placeholder="my-migration-snapshot"
            value=""
            onChange={() => {}}
          />
        </FormField>
        
        <FormField
          label="Repository Name"
          description="Enter the repository name for the snapshot"
        >
          <Input
            placeholder="my-snapshot-repository"
            value=""
            onChange={() => {}}
          />
        </FormField>
      </SpaceBetween>
    </Container>
  );
};

export default SnapshotStep;
