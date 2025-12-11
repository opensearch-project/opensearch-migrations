/**
 * GroupRenderer Component
 * 
 * Renders a group of fields as a Cloudscape Container section.
 * Groups are defined in the schema metadata and organize related fields.
 */

import React from 'react';
import {
  Container,
  Header,
  SpaceBetween,
  ExpandableSection,
} from '@cloudscape-design/components';
import { FieldRenderer } from './FieldRenderer';
import type { GroupConfig, ValidationError } from '../../types';

/**
 * Props for GroupRenderer
 */
export interface GroupRendererProps {
  /** Group configuration */
  group: GroupConfig;
  /** Current form values */
  values: Record<string, unknown>;
  /** Map of field path to error */
  errorsByPath: Map<string, ValidationError>;
  /** Callback when a field value changes */
  onChange: (path: string, value: unknown) => void;
  /** Callback when a field is blurred */
  onBlur?: (path: string) => void;
  /** Whether all fields are disabled */
  disabled?: boolean;
}

/**
 * Helper to get nested value from object by path
 */
function getNestedValue(obj: Record<string, unknown>, path: string): unknown {
  const parts = path.split('.');
  let current: unknown = obj;
  
  for (const part of parts) {
    if (current && typeof current === 'object') {
      current = (current as Record<string, unknown>)[part];
    } else {
      return undefined;
    }
  }
  
  return current;
}

/**
 * Render a group of fields in a Container
 */
export function GroupRenderer({
  group,
  values,
  errorsByPath,
  onChange,
  onBlur,
  disabled = false,
}: GroupRendererProps): React.ReactElement {
  // Check if group has any errors
  const hasGroupErrors = group.fields.some(field => errorsByPath.has(field.path));
  
  // Render fields
  const renderFields = () => (
    <SpaceBetween size="l">
      {group.fields.map(field => (
        <FieldRenderer
          key={field.path}
          field={field}
          value={getNestedValue(values, field.path)}
          error={errorsByPath.get(field.path)?.message}
          onChange={(value) => onChange(field.path, value)}
          onBlur={() => onBlur?.(field.path)}
          disabled={disabled}
        />
      ))}
    </SpaceBetween>
  );

  // If collapsible, use ExpandableSection
  if (group.collapsible) {
    return (
      <ExpandableSection
        headerText={group.title}
        defaultExpanded={!group.defaultCollapsed}
        variant="container"
        headerDescription={group.description}
      >
        {renderFields()}
      </ExpandableSection>
    );
  }

  // Standard Container
  return (
    <Container
      header={
        <Header
          variant="h2"
          description={group.description}
        >
          {group.title}
          {hasGroupErrors && (
            <span style={{ color: 'var(--color-text-status-error)', marginLeft: '8px' }}>
              (has errors)
            </span>
          )}
        </Header>
      }
    >
      {renderFields()}
    </Container>
  );
}

/**
 * Props for GroupList
 */
export interface GroupListProps {
  /** Array of group configurations */
  groups: GroupConfig[];
  /** Current form values */
  values: Record<string, unknown>;
  /** Map of field path to error */
  errorsByPath: Map<string, ValidationError>;
  /** Callback when a field value changes */
  onChange: (path: string, value: unknown) => void;
  /** Callback when a field is blurred */
  onBlur?: (path: string) => void;
  /** Whether all fields are disabled */
  disabled?: boolean;
}

/**
 * Render multiple groups
 */
export function GroupList({
  groups,
  values,
  errorsByPath,
  onChange,
  onBlur,
  disabled,
}: GroupListProps): React.ReactElement {
  return (
    <SpaceBetween size="l">
      {groups.map(group => (
        <GroupRenderer
          key={group.id}
          group={group}
          values={values}
          errorsByPath={errorsByPath}
          onChange={onChange}
          onBlur={onBlur}
          disabled={disabled}
        />
      ))}
    </SpaceBetween>
  );
}

export default GroupRenderer;
