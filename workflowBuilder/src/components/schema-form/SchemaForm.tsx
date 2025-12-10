/**
 * SchemaForm Component
 * 
 * Main form component that renders a complete form based on schema metadata.
 * Composes GroupRenderer and FieldRenderer to create the form structure.
 */

import React, { useMemo } from 'react';
import {
  Form,
  SpaceBetween,
  Alert,
} from '@cloudscape-design/components';
import { GroupList } from './GroupRenderer';
import { FieldList } from './FieldRenderer';
import type { FormConfig, FieldError, FieldConfig } from '../../types';

/**
 * Props for SchemaForm
 */
export interface SchemaFormProps {
  /** Form configuration from schema */
  formConfig: FormConfig;
  /** Current form values */
  values: Record<string, unknown>;
  /** Map of field path to error */
  errorsByPath: Map<string, FieldError>;
  /** Callback when a field value changes */
  onChange: (path: string, value: unknown) => void;
  /** Callback when a field is blurred */
  onBlur?: (path: string) => void;
  /** Whether the form is disabled */
  disabled?: boolean;
  /** Whether the form is loading */
  loading?: boolean;
  /** Global error message */
  errorText?: string;
  /** Form header content */
  header?: React.ReactNode;
  /** Form actions (buttons) */
  actions?: React.ReactNode;
  /** Additional form variant */
  variant?: 'full-page' | 'embedded';
  /** Currently focused field path (for focus sync highlighting) */
  focusedPath?: string | null;
  /** Callback when a field receives focus due to value change (for focus sync) */
  onFieldFocus?: (path: string, source: 'change' | 'focus' | 'blur') => void;
}

/**
 * Main schema-driven form component
 */
export function SchemaForm({
  formConfig,
  values,
  errorsByPath,
  onChange,
  onBlur,
  disabled = false,
  loading = false,
  errorText,
  header,
  actions,
  variant = 'embedded',
  focusedPath,
  onFieldFocus,
}: SchemaFormProps): React.ReactElement {
  const { groups, allFields } = formConfig;
  
  // Check if there are any errors
  const hasErrors = errorsByPath.size > 0;
  
  // Find fields that aren't in any group
  const ungroupedFields = useMemo(() => {
    const groupedPaths = new Set<string>();
    groups.forEach(group => {
      group.fields.forEach(field => groupedPaths.add(field.path));
    });
    return allFields.filter(field => !groupedPaths.has(field.path));
  }, [groups, allFields]);
  
  // Convert FieldError map to string map for FieldList
  const errorsAsStrings = useMemo(() => {
    const map = new Map<string, string>();
    errorsByPath.forEach((error, path) => {
      map.set(path, error.message);
    });
    return map;
  }, [errorsByPath]);
  
  return (
    <Form
      header={header}
      actions={actions}
      errorText={errorText}
      variant={variant}
    >
      <SpaceBetween size="l">
        {/* Global error alert */}
        {hasErrors && (
          <Alert type="error" header="Validation Errors">
            Please fix the errors below before proceeding.
          </Alert>
        )}
        
        {/* Render grouped fields */}
        {groups.length > 0 && (
          <GroupList
            groups={groups}
            values={values}
            errorsByPath={errorsByPath}
            onChange={onChange}
            onBlur={onBlur}
            disabled={disabled || loading}
            focusedPath={focusedPath}
            onFieldFocus={onFieldFocus}
          />
        )}
        
        {/* Render ungrouped fields */}
        {ungroupedFields.length > 0 && (
          <FieldList
            fields={ungroupedFields}
            values={values}
            errors={errorsAsStrings}
            onChange={onChange}
            onBlur={onBlur}
            disabled={disabled || loading}
            focusedPath={focusedPath}
            onFieldFocus={onFieldFocus}
          />
        )}
      </SpaceBetween>
    </Form>
  );
}

/**
 * Props for SimpleSchemaForm (without groups)
 */
export interface SimpleSchemaFormProps {
  /** Array of field configurations */
  fields: FieldConfig[];
  /** Current form values */
  values: Record<string, unknown>;
  /** Map of field path to error message */
  errors: Map<string, string>;
  /** Callback when a field value changes */
  onChange: (path: string, value: unknown) => void;
  /** Callback when a field is blurred */
  onBlur?: (path: string) => void;
  /** Whether the form is disabled */
  disabled?: boolean;
}

/**
 * Simple form without groups - just renders fields
 */
export function SimpleSchemaForm({
  fields,
  values,
  errors,
  onChange,
  onBlur,
  disabled = false,
}: SimpleSchemaFormProps): React.ReactElement {
  return (
    <FieldList
      fields={fields}
      values={values}
      errors={errors}
      onChange={onChange}
      onBlur={onBlur}
      disabled={disabled}
    />
  );
}

export default SchemaForm;
