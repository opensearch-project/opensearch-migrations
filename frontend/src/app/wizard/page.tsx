"use client";

import { useState } from "react";
import { useSearchParams } from "next/navigation";
import ContentLayout from "@cloudscape-design/components/content-layout";
import Header from "@cloudscape-design/components/header";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Container from "@cloudscape-design/components/container";
import Wizard from "@cloudscape-design/components/wizard";
import SourceStep from "./components/SourceStep";
import TargetStep from "./components/TargetStep";
import SnapshotStep from "./components/SnapshotStep";
import MetadataStep from "./components/MetadataStep";
import BackfillStep from "./components/BackfillStep";
import ReviewStep from "./components/ReviewStep";

export default function WizardPage() {
  const searchParams = useSearchParams();
  const [activeStepIndex, setActiveStepIndex] = useState(0);
  
  // Get sessionName from query parameter or use a default
  const sessionName = searchParams?.get("sessionName") || "";
  
  if (!sessionName) {
    // In a real implementation, you might want to redirect to a session creation page
    // or show an error message if sessionName is required
    console.warn("No sessionName provided");
  }

  const handleNavigate = ({ detail }: { detail: { requestedStepIndex: number } }) => {
    setActiveStepIndex(detail.requestedStepIndex);
  };

  const handleSubmit = () => {
    console.log("Wizard submitted with sessionName:", sessionName);
    // In a real implementation, you would trigger the migration process here
  };

  const handleCancel = () => {
    console.log("Wizard canceled");
    // In a real implementation, you might want to navigate back or clean up
  };

  return (
    <ContentLayout
      defaultPadding
      header={
        <SpaceBetween size="m">
          <Header variant="h1">Migration Wizard</Header>
        </SpaceBetween>
      }
    >
      <Container>
        <Wizard
          steps={[
            {
              title: "Source",
              description: "Configure source cluster",
              content: <SourceStep sessionName={sessionName} />
            },
            {
              title: "Target",
              description: "Configure target cluster",
              content: <TargetStep sessionName={sessionName} />
            },
            {
              title: "Snapshot",
              description: "Create or select snapshot",
              content: <SnapshotStep sessionName={sessionName} />
            },
            {
              title: "Metadata",
              description: "Migrate metadata",
              content: <MetadataStep sessionName={sessionName} />
            },
            {
              title: "Backfill",
              description: "Configure data backfill",
              content: <BackfillStep sessionName={sessionName} />
            },
            {
              title: "Review",
              description: "Review and start migration",
              content: <ReviewStep sessionName={sessionName} />
            }
          ]}
          i18nStrings={{
            stepNumberLabel: stepNumber => `Step ${stepNumber}`,
            collapsedStepsLabel: (stepNumber, stepsCount) =>
              `Step ${stepNumber} of ${stepsCount}`,
            cancelButton: "Cancel",
            previousButton: "Previous",
            nextButton: "Next",
            submitButton: "Start migration",
          }}
          onCancel={handleCancel}
          onNavigate={handleNavigate}
          onSubmit={handleSubmit}
          activeStepIndex={activeStepIndex}
        />
      </Container>
    </ContentLayout>
  );
}
