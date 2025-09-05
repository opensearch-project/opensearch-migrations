"use client";

import { Box, SpaceBetween } from "@cloudscape-design/components";

export interface WizardStepIndicatorProps {
  readonly stepIndex: number;
  readonly totalSteps: number;
}

export default function WizardStepIndicator({
  stepIndex,
  totalSteps,
}: WizardStepIndicatorProps) {
  return (
    <Box padding="s">
      <SpaceBetween size="s">
        <Box fontSize="heading-m">
          Current Step: {stepIndex + 1} of {totalSteps}
        </Box>
      </SpaceBetween>
    </Box>
  );
}
