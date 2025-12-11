/**
 * Array Field Renderer
 * 
 * Renders array fields with add/remove functionality for items.
 * Supports both primitive arrays and arrays of objects.
 */

import React from 'react';
import {
  SpaceBetween,
  Button,
  ExpandableSection,
  Box,
  Input,
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
 * Render an array field
 */
export function ArrayFieldRenderer({
  field,
  value,
  onChange,
  onBlur,
  disabled = false,
  depth = 0,
  context,
}: BaseFieldRendererProps): React.ReactElement {
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
                {/* Render all fields */}
                {field.children.map(childField => {
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

export default ArrayFieldRenderer;
