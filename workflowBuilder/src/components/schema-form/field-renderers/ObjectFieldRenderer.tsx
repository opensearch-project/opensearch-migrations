/**
 * Object Field Renderer
 * 
 * Renders object fields with nested children.
 * Handles structured object types with defined properties.
 */

import React from 'react';
import {
  SpaceBetween,
  Box,
} from '@cloudscape-design/components';
import type { BaseFieldRendererProps } from './BaseFieldRenderer';

// Forward declaration to avoid circular dependency
let FieldRenderer: React.ComponentType<BaseFieldRendererProps>;

/**
 * Set the FieldRenderer component reference (called from main FieldRenderer)
 */
export function setFieldRendererRef(component: React.ComponentType<BaseFieldRendererProps>) {
  FieldRenderer = component;
}

/**
 * Render an object field with nested children
 */
export function ObjectFieldRenderer({
  field,
  value,
  onChange,
  onBlur,
  disabled = false,
  depth = 0,
  context,
}: BaseFieldRendererProps): React.ReactElement {
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
  
  return (
    <Box padding={{ left: depth > 0 ? 'l' : undefined }}>
      <SpaceBetween size="m">
        {/* Render all fields */}
        {field.children.map(childField => {
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
    </Box>
  );
}

export default ObjectFieldRenderer;
