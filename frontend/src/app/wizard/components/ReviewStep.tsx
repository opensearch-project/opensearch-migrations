"use client";

import { FC } from "react";
import Container from "@cloudscape-design/components/container";
import Header from "@cloudscape-design/components/header";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Box from "@cloudscape-design/components/box";
import Alert from "@cloudscape-design/components/alert";
import ColumnLayout from "@cloudscape-design/components/column-layout";
import StatusIndicator from "@cloudscape-design/components/status-indicator";

interface ReviewStepProps {
  sessionName: string;
}

const ReviewStep: FC<ReviewStepProps> = ({ sessionName }) => {
  return (
    <Container header={<Header variant="h2">Review Migration Configuration</Header>}>
      <SpaceBetween size="l">
        <Box>
          <Alert type="info">
            Review your migration configuration for session: {sessionName}
          </Alert>
        </Box>
        
        <Header variant="h3">Migration Details</Header>
        
        <ColumnLayout columns={2} variant="text-grid">
          <div>
            <Box variant="awsui-key-label">Session Name</Box>
            <div>{sessionName || "Not specified"}</div>
          </div>
          <div>
            <Box variant="awsui-key-label">Status</Box>
            <StatusIndicator type="pending">Ready to start</StatusIndicator>
          </div>
        </ColumnLayout>
        
        <Header variant="h3">Source Configuration</Header>
        <ColumnLayout columns={2} variant="text-grid">
          <div>
            <Box variant="awsui-key-label">Endpoint</Box>
            <div>https://source-cluster-endpoint:9200</div>
          </div>
          <div>
            <Box variant="awsui-key-label">Authentication</Box>
            <div>Basic Auth</div>
          </div>
        </ColumnLayout>
        
        <Header variant="h3">Target Configuration</Header>
        <ColumnLayout columns={2} variant="text-grid">
          <div>
            <Box variant="awsui-key-label">Endpoint</Box>
            <div>https://target-cluster-endpoint:9200</div>
          </div>
          <div>
            <Box variant="awsui-key-label">Authentication</Box>
            <div>Basic Auth</div>
          </div>
        </ColumnLayout>
        
        <Header variant="h3">Snapshot Details</Header>
        <ColumnLayout columns={2} variant="text-grid">
          <div>
            <Box variant="awsui-key-label">Snapshot Type</Box>
            <div>New Snapshot</div>
          </div>
          <div>
            <Box variant="awsui-key-label">Snapshot Name</Box>
            <div>my-migration-snapshot</div>
          </div>
        </ColumnLayout>
        
        <Header variant="h3">Migration Components</Header>
        <ColumnLayout columns={2} variant="text-grid">
          <div>
            <Box variant="awsui-key-label">Metadata Migration</Box>
            <StatusIndicator type="success">Configured</StatusIndicator>
          </div>
          <div>
            <Box variant="awsui-key-label">Data Backfill</Box>
            <StatusIndicator type="success">Configured</StatusIndicator>
          </div>
        </ColumnLayout>
        
        <Box textAlign="center">
          <Alert type="success">
            Your migration is ready to start. Click "Start migration" to begin the process.
          </Alert>
        </Box>
      </SpaceBetween>
    </Container>
  );
};

export default ReviewStep;
