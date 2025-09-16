"use client";

import { StatusIndicator } from "@cloudscape-design/components";
import { mapStatus, durationFromTimes } from "./statusUtils";
import { StepState, StepStateWithPause } from "@/generated/api/types.gen";

/**
 * Displays a status indicator based on the status state
 */
export function StatusDisplay({
  status,
}: Readonly<{ status?: StepState | StepStateWithPause | null }>) {
  return status ? (
    <StatusIndicator type={mapStatus(status)}></StatusIndicator>
  ) : (
    <span>-</span>
  );
}

/**
 * Displays a formatted date or a default if undefined
 */
export function DateDisplay({ date }: Readonly<{ date?: Date | null }>) {
  return <>{date != null ? new Date(date).toLocaleString() : "-"}</>;
}

/**
 * Displays the duration between two timestamps or a default if not available
 */
export function DurationDisplay({
  started,
  finished,
}: Readonly<{ started?: Date | null; finished?: Date | null }>) {
  return <>{durationFromTimes(started, finished) || "-"}</>;
}

/**
 * Displays a percentage value
 */
export function ProgressDisplay({
  percentage,
}: Readonly<{ percentage?: number | null }>) {
  return <>{percentage != null ? `${percentage}%` : "-"}</>;
}

/**
 * Displays estimated time to completion
 */
export function ETADisplay({ etaMs }: Readonly<{ etaMs?: number | null }>) {
  return <>{etaMs ? `${Math.floor(etaMs / 60000)} minutes` : "N/A"}</>;
}

/**
 * Displays a simple text value or a default if undefined
 */
export function TextDisplay({ text }: Readonly<{ text?: string | null }>) {
  return <>{text ?? "-"}</>;
}
