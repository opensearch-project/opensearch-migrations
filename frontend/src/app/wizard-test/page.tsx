"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { Box, Container, Header } from "@cloudscape-design/components";
import WorkflowWizard, { WorkflowWizardStep } from "@/components/common/WorkflowWizard";

export default function TestWizardPage() {
  return (
    <Suspense fallback={null}>
      <Container
        header={
          <Header variant="h1">
            Wizard Test Page
          </Header>
        }
      >
        <Box padding="m">
          This page demonstrates the reusable wizard component with URL-based step persistence.
          Try navigating between steps and refreshing the page - you should remain on the same step.
        </Box>
        <TestWizardPageInner />
      </Container>
    </Suspense>
  );
}

function TestWizardPageInner() {
  const searchParams = useSearchParams();
  const sessionName = searchParams?.get("sessionName") ?? "test-session";

  const handleSubmit = () => {
    console.log("Test wizard submitted");
    alert("Wizard submitted successfully!");
  };

  const handleCancel = () => {
    console.log("Test wizard canceled");
    if (confirm("Are you sure you want to cancel?")) {
      window.location.href = "/";
    }
  };

  const steps: WorkflowWizardStep[] = [
    {
      title: "Step One",
      description: "First step",
      content: (
        <Box padding="l">
          <h3>Step One Content</h3>
          <p>This is the content for step one. Click Next to proceed.</p>
          <p>Notice the URL now contains ?step=0&sessionName=test-session</p>
        </Box>
      ),
    },
    {
      title: "Step Two",
      description: "Second step",
      content: (
        <Box padding="l">
          <h3>Step Two Content</h3>
          <p>
            This is the content for step two. Try refreshing the page - 
            you should still be on step two after refresh because of the URL parameter.
          </p>
          <p>Notice the URL now contains ?step=1&sessionName=test-session</p>
        </Box>
      ),
    },
    {
      title: "Step Three",
      description: "Third step",
      content: (
        <Box padding="l">
          <h3>Step Three Content</h3>
          <p>
            This is the content for step three. Try manually changing the URL parameter to ?step=0 or ?step=1
            and see how the wizard reflects that change.
          </p>
          <p>Notice the URL now contains ?step=2&sessionName=test-session</p>
        </Box>
      ),
    },
  ];

  return (
    <WorkflowWizard
      steps={steps}
      sessionName={sessionName}
      onCancel={handleCancel}
      onSubmit={handleSubmit}
      submitButtonText="Complete Test"
    />
  );
}
