/**
 * Field Type Definitions
 * 
 * Type definitions for schema-driven form generation.
 * All types are designed to work with JSON Schema as the single source of truth.
 * Zod is only used internally for runtime validation, not for type definitions.
 */

import type { JSONSchema7 } from './json-schema.types';

/**
 * Supported field types for form rendering.
 * These map to Cloudscape Design System components.
 */
export type FieldType =
  | 'text'       // Input component
  | 'number'     // Input with type="number"
  | 'select'     // Select component
  | 'multiselect'// Multiselect component
  | 'checkbox'   // Checkbox component
  | 'toggle'     // Toggle component
  | 'textarea'   // Textarea component
  | 'password'   // Input with type="password"
  | 'url'        // Input with URL validation
  | 'email'      // Input with email validation
  | 'radio'      // RadioGroup component
  | 'tiles'      // Tiles component
  | 'slider'     // Slider component
  | 'tags'       // TagEditor for string arrays
  | 'record'     // Dynamic key-value pairs (additionalProperties)
  | 'array'      // Array of items
  | 'object'     // Nested object
  | 'union';     // Union type (anyOf/oneOf)

/**
 * Option for select/multiselect/radio/tiles fields
 */
export interface SelectOption {
  label: string;
  value: string;
  description?: string;
  disabled?: boolean;
  iconName?: string;
  tags?: string[];
}

/**
 * Field metadata extracted from JSON Schema for UI rendering.
 * These properties come from the schema's custom extensions (e.g., `order`, `advanced`, `fieldType`).
 */
export interface FieldMeta {
  // UI Presentation
  title?: string;
  placeholder?: string;
  constraintText?: string;
  helpText?: string;
  description?: string;

  // Field Configuration
  fieldType?: FieldType;
  options?: SelectOption[];
  disabled?: boolean;
  readOnly?: boolean;

  // Layout & Visibility
  order?: number;
  group?: string;
  hidden?: boolean;
  advanced?: boolean;
  colSpan?: 1 | 2;

  // Validation Display
  errorMessages?: Record<string, string>;

  // Conditional Rendering
  showWhen?: {
    field: string;
    value: unknown;
    operator?: 'eq' | 'neq' | 'in' | 'notIn';
  };

  // Record/Array specific
  itemTitle?: string;
  addButtonText?: string;
  minItems?: number;
  maxItems?: number;

  // Union specific
  discriminator?: string;
  variantLabels?: Record<string, string>;

  // Form initialization
  /** Example value for form initialization (from Zod .meta()) */
  exampleValue?: unknown;
}

/**
 * Schema type information extracted from JSON Schema during parsing.
 * This provides a normalized view of the schema's type structure.
 */
export interface SchemaTypeInfo {
  type: 'string' | 'number' | 'boolean' | 'array' | 'object' | 'record' | 'union' | 'literal' | 'enum' | 'unknown';
  isOptional: boolean;
  isNullable: boolean;
  hasDefault: boolean;
  defaultValue?: unknown;
  innerType?: SchemaTypeInfo;     // For arrays
  keyType?: SchemaTypeInfo;       // For records (always string in JSON Schema)
  valueType?: SchemaTypeInfo;     // For records (additionalProperties schema)
  unionTypes?: SchemaTypeInfo[];  // For unions (anyOf/oneOf)
  literalValue?: unknown;         // For const
  enumValues?: string[];          // For enum
  shape?: Record<string, SchemaTypeInfo>; // For objects
}

/**
 * Complete field configuration for form rendering.
 * This is the primary data structure used by form components.
 */
export interface FieldConfig {
  /** Dot-notation path to the field (e.g., "sourceClusters.main.endpoint") */
  path: string;
  
  /** Field name (last segment of path) */
  name: string;
  
  /** JSON Schema for this field */
  jsonSchema: JSONSchema7;
  
  /** Extracted metadata for UI rendering */
  meta: FieldMeta;
  
  /** Default value from schema */
  defaultValue: unknown;
  
  /** Whether the field is required */
  required: boolean;
  
  /** Parent path for nested fields */
  parentPath?: string;
  
  /** Schema type information */
  typeInfo: SchemaTypeInfo;
  
  /** Child fields for objects/records/arrays */
  children?: FieldConfig[];
  
  /** For union types (anyOf/oneOf), the variant schemas */
  variants?: Array<{
    key: string;
    label: string;
    jsonSchema: JSONSchema7;
    fields: FieldConfig[];
  }>;
}

/**
 * Field value change event
 */
export interface FieldChangeEvent {
  path: string;
  value: unknown;
  previousValue?: unknown;
}

/**
 * Field focus event for editor sync
 */
export interface FieldFocusEvent {
  path: string;
  element?: HTMLElement;
  source: 'change' | 'focus' | 'blur';
}
