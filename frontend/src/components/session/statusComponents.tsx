'use client';

import { StatusIndicator } from '@cloudscape-design/components';
import { mapStatus } from './statusUtils';
import { StepState } from '@/generated/api/types.gen';

/**
 * Displays a status indicator based on the status state
 */
export function StatusDisplay({ status }: Readonly<{ status: StepState }>) {
  return <StatusIndicator type={mapStatus(status)}></StatusIndicator>;
}

/**
 * Displays a formatted date or '-' if undefined
 */
export function DateDisplay({ date }: Readonly<{ date?: string }>) {
  return <>{date != undefined ? new Date(date).toLocaleString() : '-'}</>;
}
