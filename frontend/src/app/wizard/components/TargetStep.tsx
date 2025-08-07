"use client";

import { FC } from "react";
import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import FormField from "@cloudscape-design/components/form-field";
import Input from "@cloudscape-design/components/input";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import Alert from "@cloudscape-design/components/alert";

interface TargetStepProps {
  sessionName: string;
}

const TargetStep: FC<TargetStepProps> = ({ sessionName }) => {
  return (
    <Container header={<Header variant="h2">Target Configuration</Header>}>
      <SpaceBetween size="l">
        <Box>
          <Alert type="info">
            Configure the target cluster for migration session: {sessionName}
          </Alert>
        </Box>
        
        <FormField
          label="Target Endpoint"
          description="Enter the endpoint URL for your target cluster"
        >
          <Input 
            placeholder="https://target-cluster-endpoint:9200"
            value=""
            onChange={() => {}}
          />
        </FormField>
        
        <FormField
          label="Target Authentication"
          description="Select authentication method for your target cluster"
        >
          <Input 
            placeholder="Basic Auth / AWS IAM / etc."
            value=""
            onChange={() => {}}
          />
        </FormField>
      </SpaceBetween>
    </Container>
  );
};

export default TargetStep;
