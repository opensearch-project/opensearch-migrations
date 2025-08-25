"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import WorkflowWizard, { WorkflowWizardStep } from "@/components/common/WorkflowWizard";
import SourceConfigure from "@/components/connection/SourceConfigure";
import TargetConfigure from "@/components/connection/TargetConfigure";
import SnapshotCreator from "@/components/snapshot/SnapshotCreator";
import SnapshotReview from "@/components/snapshot/SnapshotReview";

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
      content: <SnapshotReview sessionName={sessionName} />,
    },
    {
      title: "Snapshot Creation",
      description: "Create or select snapshot",
      content: <SnapshotCreator sessionName={sessionName} />,
    },
  ];

  return (
    <WorkflowWizard
      steps={steps}
      sessionName={sessionName}
      onCancel={handleCancel}
      onSubmit={handleSubmit}
      submitButtonText="Complete migration"
    />
  );
}
