'use client';

import React from 'react';
import { StatusIndicatorProps, StatusIndicator } from '@cloudscape-design/components';
import { StepState } from '@/generated/api/types.gen';
import Spinner from '@cloudscape-design/components/spinner';
import Box from '@cloudscape-design/components/box';

/**
 * A field definition for a status item that can be used to generate both loading and data states.
 */
export interface StatusFieldDefinition<T> {
  label: string;
  /** Function that extracts and formats the value from data */
  valueSupplier: (data: T) => React.ReactNode;
  /** Optional placeholder to display during loading instead of spinner */
  placeholder?: React.ReactNode;
}

function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return `${h}h ${m}m ${s.toFixed(0)}s`;
}

export function mapStatus(state: StepState): StatusIndicatorProps.Type {
  switch(state) {
    case "Pending":
      return "pending";
    case "Running":
      return "in-progress";
    case "Completed":
      return "success";
    case "Failed":
      return "error";
  }
}

export function durationFromTimes(start: string | undefined | null, end: string | undefined | null) {
  return start != null
    && end != null
    && formatDuration(((new Date(end).getTime() - new Date(start).getTime()))/1000);
}

/**
 * Generates KeyValuePairs items for loading state based on field definitions.
 */
export function generateLoadingItems<T>(fields: StatusFieldDefinition<T>[]) {
  return fields.map(field => ({
    label: field.label,
    value: (
      <Box padding="xxs">
        {field.placeholder || <Spinner size="normal" />}
      </Box>
    )
  }));
}

/**
 * Generates KeyValuePairs items for data state based on field definitions and data.
 */
export function generateDataItems<T>(fields: StatusFieldDefinition<T>[], data: T) {
  return fields.map(field => ({
    label: field.label,
    value: field.valueSupplier(data)
  }));
}
