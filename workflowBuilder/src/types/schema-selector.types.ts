/**
 * Schema Selector Types
 * 
 * Type definitions for the schema selection feature that allows users
 * to choose between the default bundled schema and custom schema URLs.
 */

import type { JSONSchema7 } from './json-schema.types';

/**
 * Schema source type - either the default bundled schema or a custom URL
 */
export type SchemaSourceType = 'default' | 'custom';

/**
 * Schema selector option for the dropdown
 */
export interface SchemaOption {
  /** Display label for the option */
  label: string;
  /** Value identifier */
  value: SchemaSourceType;
  /** Optional description */
  description?: string;
}

/**
 * Schema selection state
 */
export interface SchemaSelectionState {
  /** The type of schema source selected */
  sourceType: SchemaSourceType;
  /** Custom URL when sourceType is 'custom' */
  customUrl: string;
  /** The resolved URL to fetch the schema from */
  resolvedUrl: string;
}

/**
 * Schema loading state
 */
export interface SchemaLoadingState {
  /** Whether the schema is currently being loaded */
  isLoading: boolean;
  /** Error message if schema loading failed */
  error: string | null;
  /** The loaded schema, or null if not yet loaded */
  schema: JSONSchema7 | null;
}

/**
 * Return type for useSchemaSelection hook
 */
export interface UseSchemaSelectionReturn {
  /** The type of schema source selected */
  sourceType: SchemaSourceType;
  /** Custom URL when sourceType is 'custom' */
  customUrl: string;
  /** The resolved URL to fetch the schema from */
  resolvedUrl: string;
  /** Set the schema source type */
  setSourceType: (type: SchemaSourceType) => void;
  /** Set the custom URL */
  setCustomUrl: (url: string) => void;
  /** Reset to default schema */
  resetToDefault: () => void;
}

/**
 * Return type for useSchemaLoader hook
 */
export interface UseSchemaLoaderReturn {
  /** The loaded schema, or null if not yet loaded */
  schema: JSONSchema7 | null;
  /** Whether the schema is currently being loaded */
  isLoading: boolean;
  /** Error message if schema loading failed */
  error: string | null;
  /** Reload the schema from the current URL */
  reload: () => void;
}

/**
 * Props for SchemaSelector component
 */
export interface SchemaSelectorProps {
  /** The type of schema source selected */
  sourceType: SchemaSourceType;
  /** Custom URL when sourceType is 'custom' */
  customUrl: string;
  /** The resolved URL (used to display the default schema URL) */
  resolvedUrl: string;
  /** Whether the schema is currently being loaded */
  isLoading: boolean;
  /** Error message if schema loading failed */
  error: string | null;
  /** Callback when source type changes */
  onSourceTypeChange: (type: SchemaSourceType) => void;
  /** Callback when custom URL changes */
  onCustomUrlChange: (url: string) => void;
  /** Callback to reload the schema */
  onReload?: () => void;
}

/**
 * localStorage key for persisting schema selection
 */
export const SCHEMA_SELECTION_STORAGE_KEY = 'migration-builder-schema-selection';

/**
 * Default schema path (relative to the app's base URL)
 */
export const DEFAULT_SCHEMA_PATH = './workflow-schema.json';

/**
 * Schema selector dropdown options
 */
export const SCHEMA_OPTIONS: SchemaOption[] = [
  {
    label: 'Default Schema',
    value: 'default',
    description: 'Use the bundled workflow schema',
  },
  {
    label: 'Custom URL',
    value: 'custom',
    description: 'Load schema from a custom URL',
  },
];
