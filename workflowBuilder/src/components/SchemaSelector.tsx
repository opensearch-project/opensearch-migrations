/**
 * SchemaSelector Component
 * 
 * A dropdown selector that allows users to choose between the default
 * bundled schema and custom schema URLs for the Migration Configuration Builder.
 */

import React, { useCallback } from 'react';
import {
  Select,
  Input,
  FormField,
  SpaceBetween,
  Alert,
  Button,
  Spinner,
  Box,
  SelectProps,
  Popover,
  StatusIndicator,
} from '@cloudscape-design/components';
import type { SchemaSelectorProps, SchemaSourceType } from '../types/schema-selector.types';
import { SCHEMA_OPTIONS } from '../types/schema-selector.types';

/**
 * Schema selector component for choosing between default and custom schemas.
 */
export function SchemaSelector({
  sourceType,
  customUrl,
  resolvedUrl,
  isLoading,
  error,
  onSourceTypeChange,
  onCustomUrlChange,
  onReload,
}: SchemaSelectorProps): React.ReactElement {
  // Convert schema options to Cloudscape Select format
  const selectOptions: SelectProps.Option[] = SCHEMA_OPTIONS.map((option) => ({
    label: option.label,
    value: option.value,
    description: option.description,
  }));

  // Find the currently selected option
  const selectedOption = selectOptions.find((opt) => opt.value === sourceType) || selectOptions[0];

  // Handle source type change from dropdown
  const handleSourceTypeChange = useCallback(
    (event: { detail: SelectProps.ChangeDetail }) => {
      const newType = event.detail.selectedOption.value as SchemaSourceType;
      onSourceTypeChange(newType);
    },
    [onSourceTypeChange]
  );

  // Handle custom URL input change
  const handleCustomUrlChange = useCallback(
    (event: { detail: { value: string } }) => {
      onCustomUrlChange(event.detail.value);
    },
    [onCustomUrlChange]
  );

  // Determine the display value for the URL field
  const displayUrl = sourceType === 'default' ? resolvedUrl : customUrl;
  const isUrlDisabled = sourceType === 'default' || isLoading;

  return (
    <div className="schema-selector">
      <SpaceBetween size="s" direction="horizontal" alignItems="end">
        {/* Schema source dropdown */}
        <FormField label="Schema Source" stretch={false}>
          <Select
            selectedOption={selectedOption}
            onChange={handleSourceTypeChange}
            options={selectOptions}
            disabled={isLoading}
            expandToViewport
          />
        </FormField>

        {/* Schema URL field - always shown */}
        <FormField 
          label="Schema URL" 
          stretch={true} 
          className="schema-selector__url-field"
          info={
            sourceType === 'default' ? (
              <Popover
                dismissButton={false}
                position="top"
                size="small"
                triggerType="custom"
                content={
                  <StatusIndicator type="info">
                    Select &quot;Custom URL&quot; to modify the schema URL
                  </StatusIndicator>
                }
              >
                <Box color="text-status-info" fontSize="body-s">
                  <span style={{ cursor: 'help' }}>ⓘ</span>
                </Box>
              </Popover>
            ) : undefined
          }
        >
          <Input
            value={displayUrl}
            onChange={handleCustomUrlChange}
            placeholder="https://example.com/schema.json"
            disabled={isUrlDisabled}
            type="url"
            readOnly={sourceType === 'default'}
          />
        </FormField>

        {/* Reload button */}
        {onReload && (
          <Box margin={{ top: 'l' }}>
            <Button
              iconName="refresh"
              variant="icon"
              onClick={onReload}
              disabled={isLoading || (sourceType === 'custom' && !customUrl.trim())}
              ariaLabel="Reload schema"
            />
          </Box>
        )}

        {/* Loading indicator */}
        {isLoading && (
          <Box margin={{ top: 'l' }}>
            <Spinner size="normal" />
          </Box>
        )}
      </SpaceBetween>

      {/* Error message */}
      {error && (
        <Box margin={{ top: 's' }}>
          <Alert type="error" dismissible={false}>
            {error}
          </Alert>
        </Box>
      )}

      {/* Warning when custom URL is empty */}
      {sourceType === 'custom' && !customUrl.trim() && !error && (
        <Box margin={{ top: 's' }}>
          <Alert type="info" dismissible={false}>
            Enter a URL to load a custom JSON Schema.
          </Alert>
        </Box>
      )}
    </div>
  );
}

export default SchemaSelector;
