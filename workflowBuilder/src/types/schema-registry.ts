/**
 * Schema Registry Types
 * 
 * Types for managing schema-driven form configuration,
 * including field configs, groups, and form structure.
 */

import { z } from 'zod';
import type { FieldMeta, GroupMeta } from './field-metadata';

/**
 * Configuration for a single form field extracted from schema
 */
export interface FieldConfig {
  /** Dot-notation path to the field (e.g., "source.endpoint") */
  path: string;
  /** The Zod schema for this field */
  schema: z.ZodTypeAny;
  /** UI metadata extracted from schema */
  meta: FieldMeta;
  /** Default value for the field */
  defaultValue: unknown;
  /** Whether the field is required */
  required: boolean;
  /** Parent path for nested objects */
  parentPath?: string;
  /** Field name (last segment of path) */
  name: string;
}

/**
 * Configuration for a group of fields (rendered as a section)
 */
export interface GroupConfig {
  /** Unique identifier for the group */
  id: string;
  /** Display title for the group */
  title: string;
  /** Optional description */
  description?: string;
  /** Sort order (lower = first) */
  order: number;
  /** Fields belonging to this group */
  fields: FieldConfig[];
  /** Whether the group is collapsible */
  collapsible?: boolean;
  /** Whether the group starts collapsed */
  defaultCollapsed?: boolean;
}

/**
 * Complete form configuration derived from schema
 */
export interface FormConfig {
  /** The root Zod schema */
  schema: z.ZodTypeAny;
  /** Organized groups of fields */
  groups: GroupConfig[];
  /** Quick lookup map from path to field config */
  fieldMap: Map<string, FieldConfig>;
  /** All field configs in flat array */
  allFields: FieldConfig[];
  /** Group metadata definitions */
  groupMeta: Map<string, GroupMeta>;
}

/**
 * Options for parsing schema into form config
 */
export interface SchemaParseOptions {
  /** Default group for fields without explicit group */
  defaultGroup?: string;
  /** Whether to include hidden fields */
  includeHidden?: boolean;
  /** Custom group definitions */
  groups?: GroupMeta[];
}

/**
 * Result of schema parsing
 */
export interface SchemaParseResult {
  /** Whether parsing succeeded */
  success: boolean;
  /** The form configuration if successful */
  config?: FormConfig;
  /** Error message if parsing failed */
  error?: string;
}

/**
 * Field value change event
 */
export interface FieldChangeEvent {
  /** Path to the changed field */
  path: string;
  /** New value */
  value: unknown;
  /** Previous value */
  previousValue?: unknown;
}

/**
 * Form state snapshot
 */
export interface FormState {
  /** Current form values */
  values: Record<string, unknown>;
  /** Whether form has been modified */
  isDirty: boolean;
  /** Whether form is currently validating */
  isValidating: boolean;
  /** Whether form has been submitted */
  isSubmitted: boolean;
  /** Touched field paths */
  touchedFields: Set<string>;
}
