"use client";

import { FC } from "react";
import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import FormField from "@cloudscape-design/components/form-field";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import Alert from "@cloudscape-design/components/alert";
import Input from "@cloudscape-design/components/input";
import ExpandableSection from "@cloudscape-design/components/expandable-section";
import Select from "@cloudscape-design/components/select";
import Checkbox from "@cloudscape-design/components/checkbox";

interface BackfillStepProps {
  sessionName: string;
}

const BackfillStep: FC<BackfillStepProps> = ({ sessionName }) => {
  return (
    <Container header={<Header variant="h2">Data Backfill Configuration</Header>}>
      <SpaceBetween size="l">
        <Box>
          <Alert type="info">
            Configure data backfill settings for session: {sessionName}
          </Alert>
        </Box>
        
        <FormField
          label="Indices to Backfill"
          description="Specify which indices to include in the backfill"
        >
          <Input
            placeholder="e.g. logs-*, metrics-*"
            value=""
            onChange={() => {}}
          />
        </FormField>
        
        <FormField
          label="Backfill Method"
          description="Choose the method for data backfilling"
        >
          <Select
            options={[
              { label: "Snapshot-based backfill", value: "snapshot" },
              { label: "Real-time streaming", value: "realtime" },
              { label: "Hybrid approach", value: "hybrid" }
            ]}
            selectedOption={{ label: "Snapshot-based backfill", value: "snapshot" }}
            onChange={() => {}}
          />
        </FormField>
        
        <ExpandableSection headerText="Performance Settings">
          <SpaceBetween size="m">
            <FormField
              label="Batch Size"
              description="Number of documents to process in each batch"
            >
              <Input
                type="number"
                placeholder="1000"
                value="1000"
                onChange={() => {}}
              />
            </FormField>
            
            <FormField
              label="Concurrency"
              description="Number of concurrent processes"
            >
              <Input
                type="number"
                placeholder="4"
                value="4"
                onChange={() => {}}
              />
            </FormField>
            
            <FormField
              label="Additional Options"
            >
              <Checkbox
                checked={true}
                onChange={() => {}}
              >
                Enable throttling during peak hours
              </Checkbox>
            </FormField>
          </SpaceBetween>
        </ExpandableSection>
      </SpaceBetween>
    </Container>
  );
};

export default BackfillStep;
