/**
 * Record Field Renderer
 * 
 * Renders dynamic key-value pairs (record fields) with add/remove functionality.
 * Handles nested object structures within record entries.
 */

import React, { useState } from 'react';
import {
  SpaceBetween,
  Button,
  ExpandableSection,
  Box,
  ColumnLayout,
  Input,
} from '@cloudscape-design/components';
import type { BaseFieldRendererProps, FieldContext } from './BaseFieldRenderer';

// Forward declaration to avoid circular dependency
let FieldRenderer: React.ComponentType<BaseFieldRendererProps>;

/**
 * Set the FieldRenderer component reference (called from main FieldRenderer)
 */
export function setFieldRendererRef(component: React.ComponentType<BaseFieldRendererProps>) {
  FieldRenderer = component;
}

/**
 * Render a record field (dynamic key-value pairs)
 */
export function RecordFieldRenderer({
  field,
  value,
  onChange,
  onBlur,
  disabled = false,
  depth = 0,
  context: _context,
}: BaseFieldRendererProps): React.ReactElement {
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
                  {/* Render all fields */}
                  {field.children.map(childField => {
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

export default RecordFieldRenderer;
