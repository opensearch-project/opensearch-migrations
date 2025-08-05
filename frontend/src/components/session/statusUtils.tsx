'use client';

import { StatusIndicatorProps } from '@cloudscape-design/components';
import { StepState } from '@/generated/api/types.gen';

export function formatDuration(seconds: number): string {
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
