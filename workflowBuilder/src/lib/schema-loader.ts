/**
 * Schema Loader Utilities
 * 
 * Utility functions for fetching and validating JSON Schemas from URLs.
 */

import type { JSONSchema7 } from '../types';
import { DEFAULT_SCHEMA_PATH } from '../types/schema-selector.types';

/**
 * Error thrown when schema loading fails
 */
export class SchemaLoadError extends Error {
  constructor(
    message: string,
    public readonly url: string,
    public readonly cause?: Error
  ) {
    super(message);
    this.name = 'SchemaLoadError';
  }
}

/**
 * Validates that the fetched data has a basic JSON Schema structure.
 * This is a lightweight validation - full schema validation happens during form generation.
 * 
 * @param data - The data to validate
 * @returns True if the data appears to be a valid JSON Schema
 */
export function validateSchemaStructure(data: unknown): data is JSONSchema7 {
  if (typeof data !== 'object' || data === null) {
    return false;
  }

  const schema = data as Record<string, unknown>;

  // Check for common JSON Schema properties
  // A valid schema should have at least a type or $ref or properties or items
  const hasType = typeof schema.type === 'string' || Array.isArray(schema.type);
  const hasRef = typeof schema.$ref === 'string';
  const hasProperties = typeof schema.properties === 'object' && schema.properties !== null;
  const hasItems = typeof schema.items === 'object' || Array.isArray(schema.items);
  const hasOneOf = Array.isArray(schema.oneOf);
  const hasAnyOf = Array.isArray(schema.anyOf);
  const hasAllOf = Array.isArray(schema.allOf);
  const hasConst = 'const' in schema;
  const hasEnum = Array.isArray(schema.enum);
  const hasDefinitions = typeof schema.definitions === 'object' || typeof schema.$defs === 'object';

  return hasType || hasRef || hasProperties || hasItems || hasOneOf || hasAnyOf || hasAllOf || hasConst || hasEnum || hasDefinitions;
}

/**
 * Returns the URL for the default bundled schema.
 * This resolves the relative path based on the current page location.
 * 
 * @returns The absolute URL for the default schema
 */
export function getDefaultSchemaUrl(): string {
  // In development, the schema is served from the generated folder
  // In production, it's copied to the dist folder root
  const baseUrl = new URL(DEFAULT_SCHEMA_PATH, window.location.href);
  return baseUrl.href;
}

/**
 * Fetches and parses a JSON Schema from a URL.
 * 
 * @param url - The URL to fetch the schema from
 * @returns The parsed JSON Schema
 * @throws SchemaLoadError if the fetch fails or the response is not valid JSON Schema
 */
export async function fetchSchema(url: string): Promise<JSONSchema7> {
  let response: Response;

  try {
    response = await fetch(url, {
      method: 'GET',
      headers: {
        'Accept': 'application/json, application/schema+json',
      },
    });
  } catch (error) {
    throw new SchemaLoadError(
      `Failed to fetch schema: ${error instanceof Error ? error.message : 'Network error'}`,
      url,
      error instanceof Error ? error : undefined
    );
  }

  if (!response.ok) {
    throw new SchemaLoadError(
      `Failed to fetch schema: HTTP ${response.status} ${response.statusText}`,
      url
    );
  }

  let data: unknown;
  try {
    data = await response.json();
  } catch (error) {
    throw new SchemaLoadError(
      'Failed to parse schema: Invalid JSON',
      url,
      error instanceof Error ? error : undefined
    );
  }

  if (!validateSchemaStructure(data)) {
    throw new SchemaLoadError(
      'Invalid schema: The response does not appear to be a valid JSON Schema',
      url
    );
  }

  return data;
}

/**
 * Resolves a schema URL, handling both absolute and relative URLs.
 * 
 * @param url - The URL to resolve (can be relative or absolute)
 * @returns The resolved absolute URL
 */
export function resolveSchemaUrl(url: string): string {
  try {
    // Try to parse as absolute URL first
    new URL(url);
    return url;
  } catch {
    // If it fails, treat as relative URL
    return new URL(url, window.location.href).href;
  }
}
