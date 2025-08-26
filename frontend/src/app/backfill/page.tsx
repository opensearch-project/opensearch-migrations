"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { Alert, Box } from "@cloudscape-design/components";
import WorkflowWizard, {
  WorkflowWizardStep,
} from "@/components/common/WorkflowWizard";

export default function BackfillPage() {
  return (
    <Suspense fallback={null}>
      <BackfillPageInner></BackfillPageInner>
    </Suspense>
  );
}

function BackfillPageInner() {
  const searchParams = useSearchParams();
  const sessionName = searchParams?.get("sessionName") ?? "";

  if (!sessionName) {
    return (
      <Alert type="error" header={`Unable to find an associated session`}>
        Please create a session or adjust the sessionName parameter in the url.
      </Alert>
    );
  }

  const steps: WorkflowWizardStep[] = [
    {
      title: "Review Backfill Setup",
      description: "Review the configuration before starting the backfill",
      content: <Box>Placeholder</Box>,
    },
    {
      title: "Backfill Execution",
      description: "Trigger the backfill and monitor its progress",
      content: <Box>Placeholder</Box>,
    },
  ];

  return (
    <WorkflowWizard
      steps={steps}
      sessionName={sessionName}
      submitButtonText="Complete Backfill"
    />
  );
}
