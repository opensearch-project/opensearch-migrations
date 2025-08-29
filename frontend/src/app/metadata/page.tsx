"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { Alert, Box } from "@cloudscape-design/components";
import WorkflowWizard, {
  WorkflowWizardStep,
} from "@/components/common/WorkflowWizard";

export default function MetadataPage() {
  return (
    <Suspense fallback={null}>
      <MetadataPageInner></MetadataPageInner>
    </Suspense>
  );
}

function MetadataPageInner() {
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
      submitButtonText="Complete Metadata Migration"
    />
  );
}
