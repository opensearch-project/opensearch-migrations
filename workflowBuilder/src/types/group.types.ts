/**
 * Group type definitions for organizing form fields into sections
 */
import type { z } from 'zod';
import type { FieldConfig } from './field.types';
import type { JSONSchema7 } from './json-schema.types';

/**
 * Configuration for a form group/section
 */
export interface GroupConfig {
  /** Unique identifier for the group */
  id: string;
  
  /** Display title for the group */
  title: string;
  
  /** Optional description shown below the title */
  description?: string;
  
  /** Order for sorting groups */
  order: number;
  
  /** Fields belonging to this group */
  fields: FieldConfig[];
  
  /** Whether the group can be collapsed */
  collapsible?: boolean;
  
  /** Whether the group starts collapsed */
  defaultCollapsed?: boolean;
  
  /** Whether the entire group is considered advanced */
  advanced?: boolean;
  
  /** Optional icon name for the group header */
  icon?: string;
  
  /** Parent group ID for nested groups */
  parentGroupId?: string;
  
  /** Child groups for nested structure */
  childGroups?: GroupConfig[];
}

/**
 * Metadata for defining groups in schema
 */
export interface GroupMeta {
  id: string;
  title: string;
  description?: string;
  order?: number;
  collapsible?: boolean;
  defaultCollapsed?: boolean;
  advanced?: boolean;
  icon?: string;
  parentGroupId?: string;
}

/**
 * Complete form configuration built from schema
 */
export interface FormConfig {
  /** 
   * Original Zod schema (legacy - used by schema-parser.ts)
   * @deprecated Use jsonSchema instead for new code
   */
  schema?: z.ZodTypeAny;
  
  /** JSON Schema (used by openapi-parser.ts) */
  jsonSchema?: JSONSchema7;
  
  /** Organized groups with their fields */
  groups: GroupConfig[];
  
  /** Map of field path to field config for quick lookup */
  fieldMap: Map<string, FieldConfig>;
  
  /** Flat list of all fields */
  allFields: FieldConfig[];
  
  /** Fields marked as advanced */
  advancedFields: FieldConfig[];
  
  /** Fields not marked as advanced (basic/default) */
  basicFields: FieldConfig[];
  
  /** Default values object built from schema defaults */
  defaultValues: Record<string, unknown>;
}

/**
 * Options for building form configuration
 */
export interface FormConfigOptions {
  /** Custom group definitions to override schema-derived groups */
  groups?: GroupMeta[];
  
  /** Whether to include hidden fields */
  includeHidden?: boolean;
  
  /** Whether to include advanced fields in the main form */
  includeAdvanced?: boolean;
  
  /** Default group for fields without explicit group assignment */
  defaultGroup?: string;
  
  /** Whether to flatten nested objects into dot-notation paths */
  flattenObjects?: boolean;
  
  /** Maximum depth for nested object flattening */
  maxDepth?: number;
}

/**
 * Predefined group IDs for common sections
 */
export const PREDEFINED_GROUPS = {
  SOURCE_CLUSTERS: 'source-clusters',
  TARGET_CLUSTERS: 'target-clusters',
  MIGRATION_CONFIGS: 'migration-configs',
  AUTHENTICATION: 'authentication',
  SNAPSHOT_REPO: 'snapshot-repo',
  REPLAYER_OPTIONS: 'replayer-options',
  RFS_OPTIONS: 'rfs-options',
  METADATA_OPTIONS: 'metadata-options',
  SNAPSHOT_OPTIONS: 'snapshot-options',
  RESOURCE_REQUIREMENTS: 'resource-requirements',
  ADVANCED: 'advanced',
  GENERAL: 'general',
} as const;

export type PredefinedGroupId = typeof PREDEFINED_GROUPS[keyof typeof PREDEFINED_GROUPS];
