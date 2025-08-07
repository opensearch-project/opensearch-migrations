'use client';

import { StatusIndicator } from '@cloudscape-design/components';
import { mapStatus, durationFromTimes } from './statusUtils';
import { StepState } from '@/generated/api/types.gen';

/**
 * Displays a status indicator based on the status state
 */
export function StatusDisplay({ status }: Readonly<{ status: StepState }>) {
  return <StatusIndicator type={mapStatus(status)}></StatusIndicator>;
}

/**
 * Displays a formatted date or a default if undefined
 */
export function DateDisplay({ date }: Readonly<{ date?: string }>) {
  return <>{date != undefined ? new Date(date).toLocaleString() : '-'}</>;
}

/**
 * Displays the duration between two timestamps or a default if not available
 */
export function DurationDisplay({ started, finished }: Readonly<{ started?: string, finished?: string }>) {
  return <>{durationFromTimes(started, finished) || '-'}</>;
}

/**
 * Displays a percentage value
 */
export function ProgressDisplay({ percentage }: Readonly<{ percentage: number }>) {
  return <>{`${percentage}%`}</>;
}

/**
 * Displays estimated time to completion
 */
export function ETADisplay({ etaMs }: Readonly<{ etaMs: number | null }>) {
  return <>{etaMs ? `${Math.floor(etaMs / 60000)} minutes` : 'N/A'}</>;
}
