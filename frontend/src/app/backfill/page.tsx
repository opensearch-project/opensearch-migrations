"use client";

import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { Box, Wizard } from "@cloudscape-design/components";

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
      title: "Backfill configuration",
      description: "Create or select snapshot",
      content: <Box>Placeholder</Box>,
    },
    {
      title: "Backfill migration",
      description: "Create or select snapshot",
      content: <Box>Placeholder</Box>,
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
