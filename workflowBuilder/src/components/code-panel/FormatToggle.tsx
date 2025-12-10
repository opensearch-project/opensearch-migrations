/**
 * FormatToggle Component
 * 
 * A toggle button to switch between YAML and JSON formats.
 */

import React from 'react';
import { SegmentedControl } from '@cloudscape-design/components';
import type { CodeFormat } from '../../types';

/**
 * Props for FormatToggle
 */
export interface FormatToggleProps {
  /** Current format */
  format: CodeFormat;
  /** Callback when format changes */
  onChange: (format: CodeFormat) => void;
  /** Whether the toggle is disabled */
  disabled?: boolean;
}

/**
 * Toggle between YAML and JSON formats
 */
export function FormatToggle({
  format,
  onChange,
  disabled: _disabled = false,
}: FormatToggleProps): React.ReactElement {
  // Note: SegmentedControl doesn't support disabled prop directly
  // We keep the prop for API consistency but don't use it
  return (
    <SegmentedControl
      selectedId={format}
      onChange={({ detail }) => onChange(detail.selectedId as CodeFormat)}
      label="Format"
      options={[
        { id: 'yaml', text: 'YAML' },
        { id: 'json', text: 'JSON' },
      ]}
    />
  );
}

export default FormatToggle;
