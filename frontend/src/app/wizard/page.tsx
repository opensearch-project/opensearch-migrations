"use client";

import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import SpaceBetween from "@cloudscape-design/components/space-between";
import Wizard from "@cloudscape-design/components/wizard";
import SourceConfigure from "@/components/wizard/SourceConfigure";
import TargetConfigure from "@/components/wizard/TargetConfigure";
import { Box } from "@cloudscape-design/components";
import SessionOverviewView from "@/components/session/SessionOverviewView";
import SnapshotStatusView from "@/components/session/SnapshotStatusView";
import MetadataStatusView from "@/components/session/MetadataStatusView";

export default function WizardPage() {
  return (
    <Suspense fallback={null}>
      <WizardPageInner></WizardPageInner>
    </Suspense>
  );
}

function WizardPageInner() {
  const searchParams = useSearchParams();
  const [activeStepIndex, setActiveStepIndex] = useState(0);

  const sessionName = searchParams?.get("sessionName") ?? "";

  if (!sessionName) {
    // TODO: lets automatically create a session
    console.warn("No sessionName provided");
  }

  const handleNavigate = ({
    detail,
  }: {
    detail: { requestedStepIndex: number };
  }) => {
    setActiveStepIndex(detail.requestedStepIndex);
  };

  const handleSubmit = () => {
    console.log("Wizard submitted with sessionName:", sessionName);
  };

  const handleCancel = () => {
    // TODO: Modal confirmation, are you sure you want to leave?
    console.log("Wizard canceled");
  };

  const steps = [
    {
      title: "Source",
      description: "Configure source cluster",
      content: <SourceConfigure sessionName={sessionName} />,
    },
    {
      title: "Target",
      description: "Configure target cluster",
      content: <TargetConfigure sessionName={sessionName} />,
    },
    {
      title: "Snapshot",
      description: "Create or select snapshot",
      content: <SnapshotCreate sessionName={sessionName} />,
    },
    {
      title: "Metadata",
      description: "Migrate metadata",
      content: <Box>Placeholder</Box>,
    },
    {
      title: "Backfill",
      description: "Configure data backfill",
      content: <Box>Placeholder</Box>,
    },
    {
      title: "Review",
      description: "Review and start migration",
      content: (
        <SpaceBetween size="l">
          <SessionOverviewView sessionName={sessionName} />
          <SnapshotStatusView sessionName={sessionName} />
          <MetadataStatusView sessionName={sessionName} />
        </SpaceBetween>
      ),
    },
  ];

  return (
    <Wizard
      steps={steps}
      i18nStrings={{
        stepNumberLabel: (stepNumber) => `Step ${stepNumber}`,
        collapsedStepsLabel: (stepNumber, stepsCount) =>
          `Step ${stepNumber} of ${stepsCount}`,
        cancelButton: "Cancel",
        previousButton: "Previous",
        nextButton: "Next",
        submitButton: "Complete migration",
      }}
      onCancel={handleCancel}
      onNavigate={handleNavigate}
      onSubmit={handleSubmit}
      activeStepIndex={activeStepIndex}
    />
  );
}
