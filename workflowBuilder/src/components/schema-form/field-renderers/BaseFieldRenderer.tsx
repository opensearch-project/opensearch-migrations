/**
 * Base Field Renderer
 * 
 * Shared types and utilities for field renderer components.
 * Provides common props interface and helper functions.
 */

import type { FieldConfig } from '../../../types';

/**
 * Props for all field renderer components
 */
export interface BaseFieldRendererProps {
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
export function generateVariantDefaults(
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
export function inferFieldTypeFromConfig(field: FieldConfig): string {
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
