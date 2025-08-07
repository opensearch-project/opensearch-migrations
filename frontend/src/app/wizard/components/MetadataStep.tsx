"use client";

import { FC } from "react";
import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import FormField from "@cloudscape-design/components/form-field";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import Alert from "@cloudscape-design/components/alert";
import Checkbox from "@cloudscape-design/components/checkbox";
import ExpandableSection from "@cloudscape-design/components/expandable-section";

interface MetadataStepProps {
  sessionName: string;
}

const MetadataStep: FC<MetadataStepProps> = ({ sessionName }) => {
  return (
    <Container header={<Header variant="h2">Metadata Migration</Header>}>
      <SpaceBetween size="l">
        <Box>
          <Alert type="info">
            Configure metadata migration settings for session: {sessionName}
          </Alert>
        </Box>
        
        <FormField
          label="Metadata Options"
          description="Select which metadata components to migrate"
        >
          <SpaceBetween size="m">
            <Checkbox
              checked={true}
              onChange={() => {}}
            >
              Index templates
            </Checkbox>
            <Checkbox
              checked={true}
              onChange={() => {}}
            >
              Index mappings
            </Checkbox>
            <Checkbox
              checked={true}
              onChange={() => {}}
            >
              Index settings
            </Checkbox>
            <Checkbox
              checked={true}
              onChange={() => {}}
            >
              Aliases
            </Checkbox>
          </SpaceBetween>
        </FormField>
        
        <ExpandableSection headerText="Advanced Settings">
          <SpaceBetween size="m">
            <FormField
              label="Transformation Rules"
              description="Configure custom transformation rules for metadata"
            >
              <Checkbox
                checked={false}
                onChange={() => {}}
              >
                Apply transformation rules
              </Checkbox>
            </FormField>
            
            <FormField
              label="Version Compatibility"
              description="Configure compatibility settings"
            >
              <Checkbox
                checked={true}
                onChange={() => {}}
              >
                Apply version compatibility transformations
              </Checkbox>
            </FormField>
          </SpaceBetween>
        </ExpandableSection>
      </SpaceBetween>
    </Container>
  );
};

export default MetadataStep;
