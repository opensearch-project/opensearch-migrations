"use client";

import { Suspense, useCallback } from "react";
import { useSearchParams, useRouter } from "next/navigation";
import WorkflowWizard, {
  WorkflowWizardStep,
} from "@/components/common/WorkflowWizard";
import SourceConfigure from "@/components/connection/SourceConfigure";
import TargetConfigure from "@/components/connection/TargetConfigure";
import SnapshotCreator from "@/components/snapshot/SnapshotCreator";
import SnapshotReview from "@/components/snapshot/SnapshotReview";
import { Alert } from "@cloudscape-design/components";

export default function SnapshotPage() {
  return (
    <Suspense fallback={null}>
      <SnapshotPageInner></SnapshotPageInner>
    </Suspense>
  );
}

function SnapshotPageInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const sessionName = searchParams?.get("sessionName") ?? "";

  const onSubmit = useCallback(
    () => router.push(`/metadata?sessionName=${sessionName}`),
    [router, sessionName],
  );

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
      submitButtonText="Complete Snapshot Creation"
      onSubmit={onSubmit}
    />
  );
}
