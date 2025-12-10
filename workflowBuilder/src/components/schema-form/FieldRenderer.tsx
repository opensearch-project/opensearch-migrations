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

import React, { useState } from 'react';
import { createDebugLogger } from '../../lib';
import {
  FormField,
  Input,
  Select,
  Checkbox,
  Textarea,
  Toggle,
  SpaceBetween,
  Button,
  ExpandableSection,
  Box,
  ColumnLayout,
} from '@cloudscape-design/components';
import type { SelectProps } from '@cloudscape-design/components';
import type { FieldConfig } from '../../types';

/**
 * Props for FieldRenderer
 */
export interface FieldRendererProps {
  /** Field configuration from schema */
  field: FieldConfig;
  /** Current field value */
  value: unknown;
  /** Error message for this field */
  error?: string | undefined;
  /** Callback when value changes */
  onChange: (value: unknown) => void;
  /** Callback when field is blurred */
  onBlur?: (() => void) | undefined;
  /** Whether the field is disabled */
  disabled?: boolean | undefined;
  /** Depth level for nested rendering */
  depth?: number;
  /** Context for generating default values (e.g., parent record key) */
  context?: FieldContext | undefined;
  /** Whether this field is currently focused (for focus sync highlighting) */
  isFocused?: boolean | undefined;
  /** Callback when field receives focus due to value change (for focus sync) */
  onFieldFocus?: ((path: string, source: 'change' | 'focus' | 'blur') => void) | undefined;
  /** Unique ID for DOM targeting (for focus sync scrolling) */
  fieldId?: string | undefined;
}

/**
 * Context passed down for generating contextual default values
 */
export interface FieldContext {
  /** The key of the parent record entry (e.g., "my-source" for sourceClusters.my-source) */
  parentRecordKey?: string;
  /** The type of parent (source or target cluster) */
  parentType?: 'source' | 'target';
}

/**
 * Generate default values for a union variant based on context
 */
function generateVariantDefaults(
  variantKey: string,
  variantFields: FieldConfig[],
  context?: FieldContext,
): Record<string, unknown> {
  const defaults: Record<string, unknown> = {};
  
  for (const field of variantFields) {
    // Check for schema default first
    if (field.defaultValue !== undefined) {
      defaults[field.name] = field.defaultValue;
      continue;
    }
    
    // Generate contextual defaults
    if (variantKey === 'basic' && field.name === 'secretName' && context?.parentRecordKey) {
      // For basic auth, default secretName to <parentKey>-auth
      defaults[field.name] = `${context.parentRecordKey}-auth`;
    } else if (variantKey === 'sigv4' && field.name === 'service') {
      // Default service to 'es' for SigV4
      defaults[field.name] = 'es';
    }
  }
  
  return defaults;
}

/**
 * Infer the field type from metadata and typeInfo
 */
function inferFieldTypeFromConfig(field: FieldConfig): string {
  const { meta, typeInfo } = field;
  
  // First priority: explicit fieldType in metadata
  if (meta.fieldType) {
    return meta.fieldType;
  }
  
  // Second priority: infer from typeInfo
  if (typeInfo?.type) {
    switch (typeInfo.type) {
      case 'union':
        return 'union';
      case 'object':
        return 'object';
      case 'record':
        return 'record';
      case 'array':
        return 'array';
      case 'boolean':
        return 'checkbox';
      case 'number':
        return 'number';
      case 'enum':
        return 'select';
      case 'string':
      default:
        return 'text';
    }
  }
  
  return 'text';
}

/**
 * Render a record field (dynamic key-value pairs)
 */
function RecordFieldRenderer({
  field,
  value,
  onChange,
  onBlur,
  disabled = false,
  depth = 0,
  context: _context,
}: FieldRendererProps): React.ReactElement {
  const recordValue = (value as Record<string, unknown>) || {};
  const [newKey, setNewKey] = useState('');
  
  const handleAddEntry = () => {
    if (newKey && !recordValue[newKey]) {
      const newValue = { ...recordValue, [newKey]: {} };
      onChange(newValue);
      setNewKey('');
    }
  };
  
  const handleRemoveEntry = (key: string) => {
    const { [key]: _, ...rest } = recordValue;
    onChange(rest);
  };
  
  const handleEntryChange = (key: string, entryValue: unknown) => {
    onChange({ ...recordValue, [key]: entryValue });
  };
  
  const entries = Object.entries(recordValue);
  const itemTitle = field.meta.itemTitle || 'Item';
  const addButtonText = field.meta.addButtonText || `Add ${itemTitle}`;
  
  // Determine parent type from field path
  const parentType: 'source' | 'target' | undefined = 
    field.path.includes('sourceClusters') ? 'source' : 
    field.path.includes('targetClusters') ? 'target' : 
    undefined;
  
  // Separate basic and advanced fields
  const basicFields = field.children?.filter(f => !f.meta.advanced) || [];
  const advancedFields = field.children?.filter(f => f.meta.advanced) || [];
  
  return (
    <SpaceBetween size="m">
      {entries.length === 0 ? (
        <Box color="text-status-inactive" padding="s">
          No {itemTitle.toLowerCase()}s configured. Click "{addButtonText}" to add one.
        </Box>
      ) : (
        entries.map(([key, entryValue]) => {
          // Create context for this entry
          const entryContext: FieldContext = {
            parentRecordKey: key,
            parentType,
          };
          
          return (
            <ExpandableSection
              key={key}
              headerText={`${itemTitle}: ${key}`}
              defaultExpanded={entries.length === 1}
              variant="container"
              headerActions={
                <Button
                  variant="icon"
                  iconName="remove"
                  onClick={() => handleRemoveEntry(key)}
                  disabled={disabled}
                />
              }
            >
              {field.children && field.children.length > 0 ? (
                <SpaceBetween size="m">
                  {/* Render basic (non-advanced) fields */}
                  {basicFields.map(childField => {
                    const childValue = (entryValue as Record<string, unknown>)?.[childField.name];
                    
                    return (
                      <FieldRenderer
                        key={childField.path}
                        field={{
                          ...childField,
                          path: `${field.path}.${key}.${childField.name}`,
                        }}
                        value={childValue}
                        onChange={(newChildValue) => {
                          handleEntryChange(key, {
                            ...(entryValue as Record<string, unknown>),
                            [childField.name]: newChildValue,
                          });
                        }}
                        onBlur={onBlur}
                        disabled={disabled}
                        depth={depth + 1}
                        context={entryContext}
                      />
                    );
                  })}
                  
                  {/* Render advanced fields in collapsible section */}
                  {advancedFields.length > 0 && (
                    <ExpandableSection
                      headerText="Advanced Settings"
                      variant="footer"
                      defaultExpanded={false}
                    >
                      <SpaceBetween size="m">
                        {advancedFields.map(childField => {
                          const childValue = (entryValue as Record<string, unknown>)?.[childField.name];
                          
                          return (
                            <FieldRenderer
                              key={childField.path}
                              field={{
                                ...childField,
                                path: `${field.path}.${key}.${childField.name}`,
                              }}
                              value={childValue}
                              onChange={(newChildValue) => {
                                handleEntryChange(key, {
                                  ...(entryValue as Record<string, unknown>),
                                  [childField.name]: newChildValue,
                                });
                              }}
                              onBlur={onBlur}
                              disabled={disabled}
                              depth={depth + 1}
                              context={entryContext}
                            />
                          );
                        })}
                      </SpaceBetween>
                    </ExpandableSection>
                  )}
                </SpaceBetween>
              ) : (
                <Input
                  value={typeof entryValue === 'string' ? entryValue : JSON.stringify(entryValue)}
                  onChange={({ detail }) => handleEntryChange(key, detail.value)}
                  disabled={disabled}
                />
              )}
            </ExpandableSection>
          );
        })
      )}
      
      {/* Add new entry */}
      <ColumnLayout columns={2}>
        <Input
          value={newKey}
          onChange={({ detail }) => setNewKey(detail.value)}
          placeholder={`Enter ${itemTitle.toLowerCase()} name...`}
          disabled={disabled}
        />
        <Button
          onClick={handleAddEntry}
          disabled={disabled || !newKey}
        >
          {addButtonText}
        </Button>
      </ColumnLayout>
    </SpaceBetween>
  );
}

/**
 * Render an array field
 */
function ArrayFieldRenderer({
  field,
  value,
  onChange,
  onBlur,
  disabled = false,
  depth = 0,
  context,
}: FieldRendererProps): React.ReactElement {
  const arrayValue = (value as unknown[]) || [];
  
  const handleAddItem = () => {
    const newItem = field.children ? {} : '';
    onChange([...arrayValue, newItem]);
  };
  
  const handleRemoveItem = (index: number) => {
    const newArray = [...arrayValue];
    newArray.splice(index, 1);
    onChange(newArray);
  };
  
  const handleItemChange = (index: number, itemValue: unknown) => {
    const newArray = [...arrayValue];
    newArray[index] = itemValue;
    onChange(newArray);
  };
  
  const itemTitle = field.meta.itemTitle || 'Item';
  const addButtonText = field.meta.addButtonText || `Add ${itemTitle}`;
  
  // Separate basic and advanced fields
  const basicFields = field.children?.filter(f => !f.meta.advanced) || [];
  const advancedFields = field.children?.filter(f => f.meta.advanced) || [];
  
  return (
    <SpaceBetween size="m">
      {arrayValue.length === 0 ? (
        <Box color="text-status-inactive" padding="s">
          No {itemTitle.toLowerCase()}s configured. Click "{addButtonText}" to add one.
        </Box>
      ) : (
        arrayValue.map((item, index) => (
          <ExpandableSection
            key={index}
            headerText={`${itemTitle} ${index + 1}`}
            defaultExpanded={arrayValue.length === 1}
            variant="container"
            headerActions={
              <Button
                variant="icon"
                iconName="remove"
                onClick={() => handleRemoveItem(index)}
                disabled={disabled}
              />
            }
          >
            {field.children && field.children.length > 0 ? (
              <SpaceBetween size="m">
                {/* Render basic (non-advanced) fields */}
                {basicFields.map(childField => {
                  const childValue = (item as Record<string, unknown>)?.[childField.name];
                  
                  return (
                    <FieldRenderer
                      key={childField.path}
                      field={{
                        ...childField,
                        path: `${field.path}[${index}].${childField.name}`,
                      }}
                      value={childValue}
                      onChange={(newChildValue) => {
                        handleItemChange(index, {
                          ...(item as Record<string, unknown>),
                          [childField.name]: newChildValue,
                        });
                      }}
                      onBlur={onBlur}
                      disabled={disabled}
                      depth={depth + 1}
                      context={context}
                    />
                  );
                })}
                
                {/* Render advanced fields in collapsible section */}
                {advancedFields.length > 0 && (
                  <ExpandableSection
                    headerText="Advanced Settings"
                    variant="footer"
                    defaultExpanded={false}
                  >
                    <SpaceBetween size="m">
                      {advancedFields.map(childField => {
                        const childValue = (item as Record<string, unknown>)?.[childField.name];
                        
                        return (
                          <FieldRenderer
                            key={childField.path}
                            field={{
                              ...childField,
                              path: `${field.path}[${index}].${childField.name}`,
                            }}
                            value={childValue}
                            onChange={(newChildValue) => {
                              handleItemChange(index, {
                                ...(item as Record<string, unknown>),
                                [childField.name]: newChildValue,
                              });
                            }}
                            onBlur={onBlur}
                            disabled={disabled}
                            depth={depth + 1}
                            context={context}
                          />
                        );
                      })}
                    </SpaceBetween>
                  </ExpandableSection>
                )}
              </SpaceBetween>
            ) : (
              <Input
                value={typeof item === 'string' ? item : JSON.stringify(item)}
                onChange={({ detail }) => handleItemChange(index, detail.value)}
                disabled={disabled}
              />
            )}
          </ExpandableSection>
        ))
      )}
      
      <Button
        onClick={handleAddItem}
        disabled={disabled}
      >
        {addButtonText}
      </Button>
    </SpaceBetween>
  );
}

/**
 * Render a union field with variant selector and nested fields
 */
function UnionFieldRenderer({
  field,
  value,
  onChange,
  onBlur,
  disabled = false,
  depth = 0,
  context,
}: FieldRendererProps): React.ReactElement {
  const { meta } = field;
  const unionValue = (value as Record<string, unknown>) || {};
  
  // Build variant options from field.variants (populated by openapi-parser)
  let variantOptions: SelectProps.Option[] = [];
  
  if (field.variants && field.variants.length > 0) {
    variantOptions = field.variants.map(v => ({
      label: v.label,
      value: v.key,
    }));
  } else if (meta.options && meta.options.length > 0) {
    variantOptions = meta.options.map(opt => ({
      label: opt.label,
      value: opt.value,
      ...(opt.description && { description: opt.description }),
    }));
  } else if (field.typeInfo?.unionTypes) {
    // Fallback: build options from typeInfo
    const variantLabels = meta.variantLabels || {};
    variantOptions = field.typeInfo.unionTypes.map((ut, idx) => {
      let discriminatorValue = `variant-${idx}`;
      let label = `Option ${idx + 1}`;
      
      // Check if shape has a 'type' discriminator with literal value
      if (ut.shape && ut.shape['type']) {
        const discType = ut.shape['type'];
        if (discType.literalValue) {
          const litVal = Array.isArray(discType.literalValue) 
            ? discType.literalValue[0] 
            : discType.literalValue;
          discriminatorValue = String(litVal);
          label = variantLabels[discriminatorValue] ?? 
            String(litVal).charAt(0).toUpperCase() + String(litVal).slice(1);
        }
      }
      // Check if shape has a single property (property-name discriminator pattern)
      else if (ut.shape) {
        const shapeKeys = Object.keys(ut.shape);
        if (shapeKeys.length === 1) {
          discriminatorValue = shapeKeys[0];
          label = variantLabels[discriminatorValue] ?? 
            discriminatorValue.charAt(0).toUpperCase() + discriminatorValue.slice(1);
        }
      }
      
      return { label, value: discriminatorValue };
    });
  }
  
  // Determine current selected variant
  // Check for property-name discriminator pattern (e.g., { basic: {...} })
  let currentVariant: string | undefined;
  if (unionValue) {
    // First check if any variant key exists as a property in the value
    const variantKeys = variantOptions.map(opt => opt.value);
    for (const key of variantKeys) {
      if (key && unionValue[key] !== undefined) {
        currentVariant = key;
        break;
      }
    }
    // Fallback: check for 'type' discriminator
    if (!currentVariant && unionValue['type']) {
      currentVariant = unionValue['type'] as string;
    }
  }
  
  const selectedOption = variantOptions.find(opt => opt.value === currentVariant) ?? null;
  
  // Find the selected variant's fields
  const selectedVariantConfig = field.variants?.find(v => v.key === currentVariant);
  const variantFields = selectedVariantConfig?.fields || [];
  
  // Get the inner value for the selected variant
  const innerValue = currentVariant ? (unionValue[currentVariant] as Record<string, unknown>) || {} : {};
  
  const handleVariantChange = (newVariant: string | undefined) => {
    if (newVariant) {
      // Find the variant config to get its fields for default generation
      const variantConfig = field.variants?.find(v => v.key === newVariant);
      const variantFieldConfigs = variantConfig?.fields || [];
      
      // Generate default values for the new variant based on context
      const defaults = generateVariantDefaults(newVariant, variantFieldConfigs, context);
      
      // Create value with the variant key as property name and defaults
      // This handles schemas like authConfig: { basic: { secretName: "my-source-auth" } }
      onChange({ [newVariant]: defaults });
    }
  };
  
  const handleInnerValueChange = (fieldName: string, fieldValue: unknown) => {
    if (currentVariant) {
      onChange({
        [currentVariant]: {
          ...innerValue,
          [fieldName]: fieldValue,
        },
      });
    }
  };
  
  return (
    <SpaceBetween size="m">
      {/* Variant selector */}
      <Select
        selectedOption={selectedOption}
        onChange={({ detail }) => handleVariantChange(detail.selectedOption?.value)}
        options={variantOptions}
        placeholder="Select type..."
        disabled={disabled}
      />
      
      {/* Render nested fields for the selected variant */}
      {currentVariant && variantFields.length > 0 && (
        <Box padding={{ left: 'l' }}>
          <SpaceBetween size="m">
            {variantFields.map(childField => {
              // Get the field name from the child field
              // The child field path is like "sourceClusters.*.authConfig.basic.basic.secretName"
              // We need to extract just the field name within the variant
              const fieldName = childField.name;
              const childValue = innerValue[fieldName];
              
              return (
                <FieldRenderer
                  key={childField.path}
                  field={{
                    ...childField,
                    path: `${field.path}.${currentVariant}.${fieldName}`,
                  }}
                  value={childValue}
                  onChange={(newValue) => handleInnerValueChange(fieldName, newValue)}
                  onBlur={onBlur}
                  disabled={disabled}
                  depth={depth + 1}
                  context={context}
                />
              );
            })}
          </SpaceBetween>
        </Box>
      )}
    </SpaceBetween>
  );
}

/**
 * Render an object field with nested children
 */
function ObjectFieldRenderer({
  field,
  value,
  onChange,
  onBlur,
  disabled = false,
  depth = 0,
  context,
}: FieldRendererProps): React.ReactElement {
  const objectValue = (value as Record<string, unknown>) || {};
  
  const handleChildChange = (childName: string, childValue: unknown) => {
    onChange({ ...objectValue, [childName]: childValue });
  };
  
  if (!field.children || field.children.length === 0) {
    return (
      <Box color="text-status-inactive" padding="s">
        No fields defined for this object.
      </Box>
    );
  }
  
  // Separate basic and advanced fields
  const basicFields = field.children.filter(f => !f.meta.advanced);
  const advancedFields = field.children.filter(f => f.meta.advanced);
  
  return (
    <Box padding={{ left: depth > 0 ? 'l' : undefined }}>
      <SpaceBetween size="m">
        {/* Render basic (non-advanced) fields */}
        {basicFields.map(childField => {
          const childValue = objectValue[childField.name];
          
          return (
            <FieldRenderer
              key={childField.path}
              field={childField}
              value={childValue}
              onChange={(newValue) => handleChildChange(childField.name, newValue)}
              onBlur={onBlur}
              disabled={disabled}
              depth={depth + 1}
              context={context}
            />
          );
        })}
        
        {/* Render advanced fields in collapsible section */}
        {advancedFields.length > 0 && (
          <ExpandableSection
            headerText="Advanced Settings"
            variant="footer"
            defaultExpanded={false}
          >
            <SpaceBetween size="m">
              {advancedFields.map(childField => {
                const childValue = objectValue[childField.name];
                
                return (
                  <FieldRenderer
                    key={childField.path}
                    field={childField}
                    value={childValue}
                    onChange={(newValue) => handleChildChange(childField.name, newValue)}
                    onBlur={onBlur}
                    disabled={disabled}
                    depth={depth + 1}
                    context={context}
                  />
                );
              })}
            </SpaceBetween>
          </ExpandableSection>
        )}
      </SpaceBetween>
    </Box>
  );
}

// Create debug logger for field rendering
const log = createDebugLogger({ prefix: '[FieldRenderer]' });

/**
 * Generate a unique field ID for DOM targeting based on field path
 */
function generateFieldId(path: string): string {
  // Convert path to a valid HTML ID by replacing special characters
  const fieldId = `field-${path.replace(/\./g, '-').replace(/\[/g, '-').replace(/\]/g, '')}`;
  log.debug('generateFieldId', { inputPath: path, outputId: fieldId });
  return fieldId;
}

/**
 * CSS class for focused field highlight
 */
const FIELD_FOCUS_HIGHLIGHT_STYLE = {
  backgroundColor: '#e6f7ff',
  borderLeft: '3px solid #0972d3',
  paddingLeft: '12px',
  marginLeft: '-15px',
  transition: 'background-color 0.3s ease-out, border-left 0.3s ease-out',
  borderRadius: '4px',
};

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
  isFocused = false,
  onFieldFocus,
  fieldId: providedFieldId,
}: FieldRendererProps): React.ReactElement {
  const { meta } = field;
  const fieldType = inferFieldTypeFromConfig(field);
  const isDisabled = disabled || meta.disabled;
  const isReadOnly = meta.readOnly;
  
  // Generate field ID for DOM targeting
  const fieldId = providedFieldId || generateFieldId(field.path);
  
  // Log field rendering with path information (useful for debugging focus sync)
  log.debug('FieldRenderer render', {
    path: field.path,
    fieldId,
    fieldType,
    isFocused,
    hasArrayIndex: field.path.includes('['),
    depth,
  });
  
  // Wrapper for onChange that also triggers focus notification
  const handleChange = (newValue: unknown) => {
    onChange(newValue);
    // Notify about focus change due to value change
    if (onFieldFocus) {
      onFieldFocus(field.path, 'change');
    }
  };
  
  // Style for focused state
  const focusStyle = isFocused ? FIELD_FOCUS_HIGHLIGHT_STYLE : {};

  // Handle complex types with special renderers
  if (fieldType === 'record') {
    return (
      <div id={fieldId} data-field-path={field.path} style={focusStyle}>
        <FormField
          label={meta.title || field.name}
          description={meta.description}
          errorText={error}
        >
          <RecordFieldRenderer
            field={field}
            value={value}
            onChange={handleChange}
            onBlur={onBlur}
            disabled={isDisabled}
            depth={depth}
            context={context}
            isFocused={isFocused}
            onFieldFocus={onFieldFocus}
          />
        </FormField>
      </div>
    );
  }

  if (fieldType === 'array') {
    return (
      <div id={fieldId} data-field-path={field.path} style={focusStyle}>
        <FormField
          label={meta.title || field.name}
          description={meta.description}
          errorText={error}
        >
          <ArrayFieldRenderer
            field={field}
            value={value}
            onChange={handleChange}
            onBlur={onBlur}
            disabled={isDisabled}
            depth={depth}
            context={context}
            isFocused={isFocused}
            onFieldFocus={onFieldFocus}
          />
        </FormField>
      </div>
    );
  }

  if (fieldType === 'object' && field.children && field.children.length > 0) {
    return (
      <div id={fieldId} data-field-path={field.path} style={focusStyle}>
        <FormField
          label={meta.title || field.name}
          description={meta.description}
          errorText={error}
        >
          <ObjectFieldRenderer
            field={field}
            value={value}
            onChange={handleChange}
            onBlur={onBlur}
            disabled={isDisabled}
            depth={depth}
            context={context}
            isFocused={isFocused}
            onFieldFocus={onFieldFocus}
          />
        </FormField>
      </div>
    );
  }

  // Handle union types with the dedicated renderer
  if (fieldType === 'union') {
    return (
      <div id={fieldId} data-field-path={field.path} style={focusStyle}>
        <FormField
          label={meta.title || field.name}
          description={meta.description}
          errorText={error}
        >
          <UnionFieldRenderer
            field={field}
            value={value}
            onChange={handleChange}
            onBlur={onBlur}
            disabled={isDisabled}
            depth={depth}
            context={context}
            isFocused={isFocused}
            onFieldFocus={onFieldFocus}
          />
        </FormField>
      </div>
    );
  }

  // Render the appropriate input component for primitive types
  const renderInput = () => {
    // Build common input props, only including defined values
    const inputProps = {
      value: value !== null && value !== undefined && typeof value !== 'object' 
        ? String(value) 
        : '',
      onChange: ({ detail }: { detail: { value: string } }) => handleChange(detail.value),
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
              handleChange(isNaN(num) ? detail.value : num);
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
            onChange={({ detail }) => handleChange(detail.selectedOption?.value)}
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
            onChange={({ detail }) => handleChange(detail.checked)}
            {...(isDisabled !== undefined && { disabled: isDisabled })}
          >
            {meta.description ?? ''}
          </Checkbox>
        );

      case 'toggle':
        return (
          <Toggle
            checked={Boolean(value)}
            onChange={({ detail }) => handleChange(detail.checked)}
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
            onChange={({ detail }) => handleChange(detail.value)}
            invalid={!!error}
            rows={4}
            {...(onBlur && { onBlur })}
            {...(meta.placeholder && { placeholder: meta.placeholder })}
            {...(isDisabled !== undefined && { disabled: isDisabled })}
            {...(isReadOnly !== undefined && { readOnly: isReadOnly })}
          />
        );

      case 'union': {
        // Handle discriminated unions - render as a select for the discriminator
        const unionValue = value as Record<string, unknown> | undefined;
        
        // Build variant options from field.variants (populated by openapi-parser)
        let variantOptions: SelectProps.Option[] = [];
        
        if (field.variants && field.variants.length > 0) {
          variantOptions = field.variants.map(v => ({
            label: v.label,
            value: v.key,
          }));
        } else if (meta.options && meta.options.length > 0) {
          variantOptions = meta.options.map(opt => ({
            label: opt.label,
            value: opt.value,
            ...(opt.description && { description: opt.description }),
          }));
        } else if (field.typeInfo?.unionTypes) {
          // Fallback: build options from typeInfo
          const variantLabels = meta.variantLabels || {};
          variantOptions = field.typeInfo.unionTypes.map((ut, idx) => {
            let discriminatorValue = `variant-${idx}`;
            let label = `Option ${idx + 1}`;
            
            // Check if shape has a 'type' discriminator with literal value
            if (ut.shape && ut.shape['type']) {
              const discType = ut.shape['type'];
              if (discType.literalValue) {
                const litVal = Array.isArray(discType.literalValue) 
                  ? discType.literalValue[0] 
                  : discType.literalValue;
                discriminatorValue = String(litVal);
                label = variantLabels[discriminatorValue] ?? 
                  String(litVal).charAt(0).toUpperCase() + String(litVal).slice(1);
              }
            }
            // Check if shape has a single property (property-name discriminator pattern)
            else if (ut.shape) {
              const shapeKeys = Object.keys(ut.shape);
              if (shapeKeys.length === 1) {
                discriminatorValue = shapeKeys[0];
                label = variantLabels[discriminatorValue] ?? 
                  discriminatorValue.charAt(0).toUpperCase() + discriminatorValue.slice(1);
              }
            }
            
            return { label, value: discriminatorValue };
          });
        }
        
        // Determine current selected variant
        // Check for property-name discriminator pattern (e.g., { basic: {...} })
        let currentVariant: string | undefined;
        if (unionValue) {
          // First check if any variant key exists as a property in the value
          const variantKeys = variantOptions.map(opt => opt.value);
          for (const key of variantKeys) {
            if (key && unionValue[key] !== undefined) {
              currentVariant = key;
              break;
            }
          }
          // Fallback: check for 'type' discriminator
          if (!currentVariant && unionValue['type']) {
            currentVariant = unionValue['type'] as string;
          }
        }
        
        const selectedOption = variantOptions.find(opt => opt.value === currentVariant) ?? null;
        
        return (
          <Select
            selectedOption={selectedOption}
            onChange={({ detail }) => {
              const newVariant = detail.selectedOption?.value;
              if (newVariant) {
                // Create value with the variant key as property name
                // This handles schemas like authConfig: { basic: {...} }
                onChange({ [newVariant]: {} });
              }
            }}
            options={variantOptions}
            placeholder="Select type..."
            invalid={!!error}
            {...(isDisabled !== undefined && { disabled: isDisabled })}
          />
        );
      }

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
      <div id={fieldId} data-field-path={field.path} style={focusStyle}>
        <FormField
          label={meta.title || field.name}
          errorText={error}
          constraintText={meta.constraintText}
        >
          {renderInput()}
        </FormField>
      </div>
    );
  }

  return (
    <div id={fieldId} data-field-path={field.path} style={focusStyle}>
      <FormField
        label={meta.title || field.name}
        description={meta.description}
        errorText={error}
        constraintText={meta.constraintText}
      >
        {renderInput()}
      </FormField>
    </div>
  );
}

/**
 * Check if a field should be highlighted based on the focused path.
 * Only exact path matches are highlighted to avoid highlighting parent containers.
 */
function isFieldFocused(focusedPath: string | null | undefined, fieldPath: string): boolean {
  if (!focusedPath) {
    return false;
  }
  // Only exact match - don't highlight parent containers or child fields
  return focusedPath === fieldPath;
}

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
  /** Currently focused field path (for focus sync highlighting) */
  focusedPath?: string | null;
  /** Callback when a field receives focus due to value change (for focus sync) */
  onFieldFocus?: ((path: string, source: 'change' | 'focus' | 'blur') => void) | undefined;
}

export function FieldList({
  fields,
  values,
  errors,
  onChange,
  onBlur,
  disabled,
  focusedPath,
  onFieldFocus,
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
            isFocused={isFieldFocused(focusedPath, field.path)}
            onFieldFocus={onFieldFocus}
          />
        );
      })}
    </SpaceBetween>
  );
}

export default FieldRenderer;
