/**
 * FieldRenderer Component
 * 
 * Generic field renderer that creates the appropriate Cloudscape
 * input component based on field metadata from the schema.
 * 
 * Handles all field types including:
 * - Primitive types (text, number, boolean, etc.)
 * - Complex types (object, record, array, union)
 */

import React from 'react';
import {
  FormField,
  Input,
  Select,
  Checkbox,
  Textarea,
  Toggle,
  SpaceBetween,
} from '@cloudscape-design/components';
import type { SelectProps } from '@cloudscape-design/components';
import type { FieldConfig } from '../../types';
import {
  type BaseFieldRendererProps,
  type FieldContext,
  inferFieldTypeFromConfig,
  RecordFieldRenderer,
  ArrayFieldRenderer,
  UnionFieldRenderer,
  ObjectFieldRenderer,
  setRecordFieldRendererRef,
  setArrayFieldRendererRef,
  setUnionFieldRendererRef,
  setObjectFieldRendererRef,
} from './field-renderers';

/**
 * Props for FieldRenderer
 */
export interface FieldRendererProps extends BaseFieldRendererProps {}

/**
 * Re-export types for convenience
 */
export type { FieldContext };

/**
 * Render a form field based on its metadata
 */
export function FieldRenderer({
  field,
  value,
  error,
  onChange,
  onBlur,
  disabled = false,
  depth = 0,
  context,
}: FieldRendererProps): React.ReactElement {
  const { meta } = field;
  const fieldType = inferFieldTypeFromConfig(field);
  const isDisabled = disabled || meta.disabled;
  const isReadOnly = meta.readOnly;
  
  // Handle complex types with special renderers
  if (fieldType === 'record') {
    return (
      <FormField
        label={meta.title || field.name}
        description={meta.description}
        errorText={error}
      >
        <RecordFieldRenderer
          field={field}
          value={value}
          onChange={onChange}
          onBlur={onBlur}
          disabled={isDisabled}
          depth={depth}
          context={context}
        />
      </FormField>
    );
  }

  if (fieldType === 'array') {
    return (
      <FormField
        label={meta.title || field.name}
        description={meta.description}
        errorText={error}
      >
        <ArrayFieldRenderer
          field={field}
          value={value}
          onChange={onChange}
          onBlur={onBlur}
          disabled={isDisabled}
          depth={depth}
          context={context}
        />
      </FormField>
    );
  }

  if (fieldType === 'object' && field.children && field.children.length > 0) {
    return (
      <FormField
        label={meta.title || field.name}
        description={meta.description}
        errorText={error}
      >
        <ObjectFieldRenderer
          field={field}
          value={value}
          onChange={onChange}
          onBlur={onBlur}
          disabled={isDisabled}
          depth={depth}
          context={context}
        />
      </FormField>
    );
  }

  // Handle union types with the dedicated renderer
  if (fieldType === 'union') {
    return (
      <FormField
        label={meta.title || field.name}
        description={meta.description}
        errorText={error}
      >
        <UnionFieldRenderer
          field={field}
          value={value}
          onChange={onChange}
          onBlur={onBlur}
          disabled={isDisabled}
          depth={depth}
          context={context}
        />
      </FormField>
    );
  }

  // Render the appropriate input component for primitive types
  const renderInput = () => {
    // Build common input props, only including defined values
    const inputProps = {
      value: value !== null && value !== undefined && typeof value !== 'object' 
        ? String(value) 
        : '',
      onChange: ({ detail }: { detail: { value: string } }) => onChange(detail.value),
      invalid: !!error,
      ...(onBlur && { onBlur }),
      ...(meta.placeholder && { placeholder: meta.placeholder }),
      ...(isDisabled !== undefined && { disabled: isDisabled }),
      ...(isReadOnly !== undefined && { readOnly: isReadOnly }),
    };

    switch (fieldType) {
      case 'text':
      case 'url':
      case 'email':
      case 'password':
        return (
          <Input
            {...inputProps}
            type={fieldType === 'password' ? 'password' : 'text'}
          />
        );

      case 'number':
        return (
          <Input
            {...inputProps}
            onChange={({ detail }) => {
              const num = parseFloat(detail.value);
              onChange(isNaN(num) ? detail.value : num);
            }}
            type="number"
          />
        );

      case 'select': {
        const selectedOption = meta.options?.find(opt => opt.value === String(value));
        const cloudscapeOption: SelectProps.Option | null = selectedOption 
          ? { label: selectedOption.label, value: selectedOption.value }
          : null;
        const cloudscapeOptions: SelectProps.Options = (meta.options ?? []).map(opt => ({
          label: opt.label,
          value: opt.value,
          ...(opt.description && { description: opt.description }),
          ...(opt.disabled && { disabled: opt.disabled }),
        }));
        return (
          <Select
            selectedOption={cloudscapeOption}
            onChange={({ detail }) => onChange(detail.selectedOption?.value)}
            options={cloudscapeOptions}
            placeholder={meta.placeholder ?? 'Select an option'}
            invalid={!!error}
            {...(onBlur && { onBlur })}
            {...(isDisabled !== undefined && { disabled: isDisabled })}
          />
        );
      }

      case 'checkbox':
        return (
          <Checkbox
            checked={Boolean(value)}
            onChange={({ detail }) => onChange(detail.checked)}
            {...(isDisabled !== undefined && { disabled: isDisabled })}
          >
            {meta.description ?? ''}
          </Checkbox>
        );

      case 'toggle':
        return (
          <Toggle
            checked={Boolean(value)}
            onChange={({ detail }) => onChange(detail.checked)}
            {...(isDisabled !== undefined && { disabled: isDisabled })}
          >
            {meta.description ?? ''}
          </Toggle>
        );

      case 'textarea':
        return (
          <Textarea
            value={value !== null && value !== undefined && typeof value !== 'object' 
              ? String(value) 
              : ''}
            onChange={({ detail }) => onChange(detail.value)}
            invalid={!!error}
            rows={4}
            {...(onBlur && { onBlur })}
            {...(meta.placeholder && { placeholder: meta.placeholder })}
            {...(isDisabled !== undefined && { disabled: isDisabled })}
            {...(isReadOnly !== undefined && { readOnly: isReadOnly })}
          />
        );

      case 'object': {
        // For object types without children, show a placeholder
        return (
          <Input
            value="[Complex object - expand to edit]"
            disabled={true}
            readOnly={true}
          />
        );
      }

      default:
        return (
          <Input
            {...inputProps}
            type="text"
          />
        );
    }
  };

  // For checkbox/toggle, we handle the label differently
  if (fieldType === 'checkbox' || fieldType === 'toggle') {
    return (
      <FormField
        label={meta.title || field.name}
        errorText={error}
        constraintText={meta.constraintText}
      >
        {renderInput()}
      </FormField>
    );
  }

  return (
    <FormField
      label={meta.title || field.name}
      description={meta.description}
      errorText={error}
      constraintText={meta.constraintText}
    >
      {renderInput()}
    </FormField>
  );
}

// Set up circular references for the extracted components
setRecordFieldRendererRef(FieldRenderer);
setArrayFieldRendererRef(FieldRenderer);
setUnionFieldRendererRef(FieldRenderer);
setObjectFieldRendererRef(FieldRenderer);

/**
 * Render multiple fields
 */
export interface FieldListProps {
  fields: FieldConfig[];
  values: Record<string, unknown>;
  errors: Map<string, string>;
  onChange: (path: string, value: unknown) => void;
  onBlur?: ((path: string) => void) | undefined;
  disabled?: boolean | undefined;
}

export function FieldList({
  fields,
  values,
  errors,
  onChange,
  onBlur,
  disabled,
}: FieldListProps): React.ReactElement {
  // Helper to get nested value
  const getValue = (path: string): unknown => {
    const parts = path.split('.');
    let current: unknown = values;
    
    for (const part of parts) {
      if (current && typeof current === 'object') {
        current = (current as Record<string, unknown>)[part];
      } else {
        return undefined;
      }
    }
    
    return current;
  };

  return (
    <SpaceBetween size="l">
      {fields.map(field => {
        const fieldError = errors.get(field.path);
        return (
          <FieldRenderer
            key={field.path}
            field={field}
            value={getValue(field.path)}
            {...(fieldError !== undefined && { error: fieldError })}
            onChange={(value) => onChange(field.path, value)}
            {...(onBlur && { onBlur: () => onBlur(field.path) })}
            {...(disabled !== undefined && { disabled })}
          />
        );
      })}
    </SpaceBetween>
  );
}

export default FieldRenderer;
