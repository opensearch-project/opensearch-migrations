"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { Box } from "@cloudscape-design/components";
import WorkflowWizard, { WorkflowWizardStep } from "@/components/common/WorkflowWizard";

export default function WizardPage() {
  return (
    <Suspense fallback={null}>
      <WizardPageInner></WizardPageInner>
    </Suspense>
  );
}

function WizardPageInner() {
  const searchParams = useSearchParams();
  const sessionName = searchParams?.get("sessionName") ?? "";

  if (!sessionName) {
    // TODO: lets automatically create a session
    console.warn("No sessionName provided");
  }

  const handleSubmit = () => {
    console.log("Wizard submitted with sessionName:", sessionName);
  };

  const handleCancel = () => {
    // TODO: Modal confirmation, are you sure you want to leave?
    console.log("Wizard canceled");
  };

  const steps: WorkflowWizardStep[] = [
    {
      title: "Metadata Evaluation",
      description: "Review snapshot settings",
      content: <Box>Placeholder</Box>,
    },
    {
      title: "Metadata Migration",
      description: "Review snapshot settings",
      content: <Box>Placeholder</Box>,
    },
  ];

  return (
    <WorkflowWizard
      steps={steps}
      sessionName={sessionName}
      onCancel={handleCancel}
      onSubmit={handleSubmit}
      submitButtonText="Complete"
    />
  );
}
