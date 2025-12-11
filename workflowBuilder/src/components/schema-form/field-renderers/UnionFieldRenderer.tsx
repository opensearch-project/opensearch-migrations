/**
 * Union Field Renderer
 * 
 * Renders union fields with variant selector and nested fields.
 * Handles discriminated unions and property-name discriminator patterns.
 */

import React from 'react';
import {
  SpaceBetween,
  Select,
  Box,
} from '@cloudscape-design/components';
import type { SelectProps } from '@cloudscape-design/components';
import type { BaseFieldRendererProps } from './BaseFieldRenderer';

// Import the helper function
import { generateVariantDefaults } from './BaseFieldRenderer';

// Forward declaration to avoid circular dependency
let FieldRenderer: React.ComponentType<BaseFieldRendererProps>;

/**
 * Set the FieldRenderer component reference (called from main FieldRenderer)
 */
export function setFieldRendererRef(component: React.ComponentType<BaseFieldRendererProps>) {
  FieldRenderer = component;
}

/**
 * Render a union field with variant selector and nested fields
 */
export function UnionFieldRenderer({
  field,
  value,
  onChange,
  onBlur,
  disabled = false,
  depth = 0,
  context,
}: BaseFieldRendererProps): React.ReactElement {
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

export default UnionFieldRenderer;
