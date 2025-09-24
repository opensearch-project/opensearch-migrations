"use client";

import React from "react";
import { StatusIndicatorProps } from "@cloudscape-design/components";
import { StepState, StepStateWithPause } from "@/generated/api/types.gen";
import Spinner from "@cloudscape-design/components/spinner";
import Box from "@cloudscape-design/components/box";

/**
 * A field definition for a status item that can be used to generate both loading and data states.
 */
export interface StatusFieldDefinition {
  label: string;
  /** Function that extracts and formats the value from data */
  value: React.ReactNode;
}

function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return `${h}h ${m}m ${s.toFixed(0)}s`;
}

export function mapStatus(
  state: StepState | StepStateWithPause,
): StatusIndicatorProps.Type {
  switch (state) {
    case "Pending":
    case "Paused":
      return "pending";
    case "Running":
      return "in-progress";
    case "Completed":
      return "success";
    case "Failed":
      return "error";
  }
}

export function durationFromTimes(
  start: Date | undefined | null,
  end: Date | undefined | null,
) {
  return (
    start != null &&
    end != null &&
    formatDuration((end.getTime() - start.getTime()) / 1000)
  );
}

/**
 * Generates KeyValuePairs items for loading state based on field definitions.
 */
export function generateLoadingItems(fields: StatusFieldDefinition[]) {
  return fields.map((field) => ({
    label: field.label,
    value: (
      <Box padding="xxs">
        <Spinner size="normal" />
      </Box>
    ),
  }));
}

/**
 * Generates KeyValuePairs items for data state.
 */
export function generateDataItems(fields: StatusFieldDefinition[]) {
  return fields.map((field) => ({
    label: field.label,
    value: field.value,
  }));
}
