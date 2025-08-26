"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import WorkflowWizard, {
  WorkflowWizardStep,
} from "@/components/common/WorkflowWizard";
import SourceConfigure from "@/components/connection/SourceConfigure";
import TargetConfigure from "@/components/connection/TargetConfigure";
import { Alert, Box } from "@cloudscape-design/components";

export default function SnapshotPage() {
  return (
    <Suspense fallback={null}>
      <SnapshotPageInner></SnapshotPageInner>
    </Suspense>
  );
}

function SnapshotPageInner() {
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
      title: "Snapshot Review",
      description: "Review snapshot details",
      content: <Box>Placeholder</Box>,
    },
    {
      title: "Snapshot Creation",
      description: "Create or select snapshot",
      content: <Box>Placeholder</Box>,
    },
  ];

  return (
    <WorkflowWizard
      steps={steps}
      sessionName={sessionName}
      submitButtonText="Complete Snapshot Creation"
    />
  );
}
