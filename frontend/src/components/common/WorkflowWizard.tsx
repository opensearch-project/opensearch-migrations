"use client";

import { useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Wizard, WizardProps } from "@cloudscape-design/components";

export interface WorkflowWizardStep {
  title: string;
  description: string;
  content: React.ReactNode;
}

export interface WorkflowWizardProps {
  readonly steps: WorkflowWizardStep[];
  readonly sessionName: string;
  readonly i18nStrings?: WizardProps.I18nStrings;
  readonly onSubmit?: () => void;
  readonly onCancel?: () => void;
  readonly submitButtonText?: string;
}

export default function WorkflowWizard({
  steps,
  sessionName,
  i18nStrings,
  onSubmit,
  onCancel,
  submitButtonText = "Complete",
}: WorkflowWizardProps) {
  const router = useRouter();
  const searchParams = useSearchParams();

  // Initialize activeStepIndex from URL or default to 0
  const stepParam = searchParams?.get("step");
  const initialStep = stepParam ? Number.parseInt(stepParam, 10) : 0;

  const [activeStepIndex, setActiveStepIndex] = useState(initialStep);

  // Update URL when activeStepIndex changes
  useEffect(() => {
    const current = new URLSearchParams(
      Array.from(searchParams?.entries() ?? []),
    );
    current.set("step", activeStepIndex.toString());

    // If we have a sessionName, keep it in the URL
    if (sessionName && !current.has("sessionName")) {
      current.set("sessionName", sessionName);
    }

    const search = current.toString();
    const query = search ? `?${search}` : "";

    // Update URL without refreshing the page
    router.replace(`${globalThis.location.pathname}${query}`, {
      scroll: false,
    });
  }, [activeStepIndex, sessionName, router, searchParams]);

  const handleNavigate = ({
    detail,
  }: {
    detail: { requestedStepIndex: number };
  }) => {
    setActiveStepIndex(detail.requestedStepIndex);
  };

  const handleSubmit = () => {
    console.log("Wizard submitted with sessionName:", sessionName);
    onSubmit?.();
  };

  const handleCancel = () => {
    console.log("Wizard canceled");
    onCancel?.();
  };

  const defaultI18nStrings: WizardProps.I18nStrings = {
    stepNumberLabel: (stepNumber) => `Step ${stepNumber}`,
    collapsedStepsLabel: (stepNumber, stepsCount) =>
      `Step ${stepNumber} of ${stepsCount}`,
    cancelButton: "Cancel",
    previousButton: "Previous",
    nextButton: "Next",
    submitButton: submitButtonText,
  };

  // Enhance each step content with the step indicator
  const enhancedSteps = steps.map((step) => ({
    ...step,
    content: <>{step.content}</>,
  }));

  return (
    <Wizard
      steps={enhancedSteps}
      i18nStrings={i18nStrings ?? defaultI18nStrings}
      onCancel={handleCancel}
      onNavigate={handleNavigate}
      onSubmit={handleSubmit}
      activeStepIndex={activeStepIndex}
    />
  );
}
