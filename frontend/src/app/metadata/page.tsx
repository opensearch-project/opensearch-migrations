"use client";

import { Suspense, useCallback } from "react";
import { useSearchParams, useRouter } from "next/navigation";
import { Alert } from "@cloudscape-design/components";
import MetadataMigrateView from "@/components/metadata/MetadataMigrateView";
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
  const router = useRouter();
  const searchParams = useSearchParams();
  const sessionName = searchParams?.get("sessionName") ?? "";

  const onSubmit = useCallback(
    () => router.push(`/backfill?sessionName=${sessionName}`),
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
      title: "Metadata Evaluation",
      description: "Review metadata actions",
      content: (
        <MetadataMigrateView
          dryRun={true}
          sessionName={sessionName}
        ></MetadataMigrateView>
      ),
    },
    {
      title: "Metadata Migration",
      description: "Migrate metadata settings",
      content: (
        <MetadataMigrateView
          dryRun={false}
          sessionName={sessionName}
        ></MetadataMigrateView>
      ),
    },
  ];

  return (
    <WorkflowWizard
      steps={steps}
      sessionName={sessionName}
      submitButtonText="Complete Metadata Migration"
      onSubmit={onSubmit}
    />
  );
}
