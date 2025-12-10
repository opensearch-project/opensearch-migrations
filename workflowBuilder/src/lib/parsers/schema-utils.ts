/**
 * Schema Utilities
 * 
 * Reusable utilities for working with JSON Schema structures.
 * Extracted from openapi-parser.ts for better modularity.
 */

import type { JSONSchema7, JSONSchema7TypeName } from '../../types/json-schema.types';
import type { FieldType, SchemaTypeInfo } from '../../types';

/**
 * Convert property name to display title
 * e.g., "sourceClusters" → "Source Clusters"
 * e.g., "migrationConfigs" → "Migration Configs"
 */
export function propertyNameToTitle(name: string): string {
    return name
        // Insert space before uppercase letters
        .replace(/([A-Z])/g, ' $1')
        // Capitalize first letter
        .replace(/^./, str => str.toUpperCase())
        // Clean up any double spaces and trim
        .replace(/\s+/g, ' ')
        .trim();
}

/**
 * Get the primary type from a JSON Schema type field
 */
export function getPrimaryType(schema: JSONSchema7): JSONSchema7TypeName | undefined {
    if (!schema.type) return undefined;
    return Array.isArray(schema.type) ? schema.type[0] : schema.type;
}

/**
 * Check if schema type includes null
 */
export function isNullable(schema: JSONSchema7): boolean {
    if (Array.isArray(schema.type)) {
        return schema.type.includes('null');
    }
    return false;
}

/**
 * Infer field type from JSON Schema
 */
export function inferFieldTypeFromSchema(schema: JSONSchema7): FieldType {
    // Check explicit fieldType in metadata
    if (schema.fieldType) {
        return schema.fieldType as FieldType;
    }

    // Check for enum
    if (schema.enum && schema.enum.length > 0) {
        return 'select';
    }

    // Check for const (single value)
    if (schema.const !== undefined) {
        return 'text';
    }

    // Check for anyOf/oneOf (union types)
    if ((schema.anyOf && schema.anyOf.length > 0) || (schema.oneOf && schema.oneOf.length > 0)) {
        return 'union';
    }

    // Infer from type
    const type = getPrimaryType(schema);

    switch (type) {
        case 'string':
            if (schema.format === 'uri' || schema.format === 'url') return 'url';
            if (schema.format === 'email') return 'email';
            if (schema.format === 'password') return 'password';
            if (schema.format === 'date' || schema.format === 'date-time') return 'text';
            // Check for long text (textarea)
            if (schema.maxLength && schema.maxLength > 200) return 'textarea';
            return 'text';

        case 'number':
        case 'integer':
            return 'number';

        case 'boolean':
            return 'toggle';

        case 'array': {
            // Check if array of strings (tags)
            if (schema.items && !Array.isArray(schema.items)) {
                const itemSchema = schema.items as JSONSchema7;
                const itemType = getPrimaryType(itemSchema);
                if (itemType === 'string' && !itemSchema.properties) {
                    return 'tags';
                }
            }
            return 'array';
        }

        case 'object':
            // Check for record (additionalProperties with schema)
            if (schema.additionalProperties && typeof schema.additionalProperties === 'object') {
                return 'record';
            }
            return 'object';

        default:
            return 'text';
    }
}

/**
 * Extract schema type information from JSON Schema
 */
export function extractSchemaTypeInfo(schema: JSONSchema7): SchemaTypeInfo {
    const type = getPrimaryType(schema);
    const nullable = isNullable(schema);

    const base: SchemaTypeInfo = {
        type: 'unknown',
        isOptional: false, // Determined by parent's required array
        isNullable: nullable,
        hasDefault: schema.default !== undefined,
        defaultValue: schema.default,
    };

    // Handle enum
    if (schema.enum && schema.enum.length > 0) {
        return {
            ...base,
            type: 'enum',
            enumValues: schema.enum.map(String),
        };
    }

    // Handle const (literal)
    if (schema.const !== undefined) {
        return {
            ...base,
            type: 'literal',
            literalValue: schema.const,
        };
    }

    // Handle anyOf/oneOf (union)
    if (schema.anyOf || schema.oneOf) {
        const options = schema.anyOf || schema.oneOf || [];
        return {
            ...base,
            type: 'union',
            unionTypes: options.map(extractSchemaTypeInfo),
        };
    }

    switch (type) {
        case 'string':
            return { ...base, type: 'string' };

        case 'number':
        case 'integer':
            return { ...base, type: 'number' };

        case 'boolean':
            return { ...base, type: 'boolean' };

        case 'array': {
            const result: SchemaTypeInfo = { ...base, type: 'array' };
            if (schema.items && !Array.isArray(schema.items)) {
                result.innerType = extractSchemaTypeInfo(schema.items as JSONSchema7);
            }
            return result;
        }

        case 'object': {
            // Check for record type
            if (schema.additionalProperties && typeof schema.additionalProperties === 'object') {
                return {
                    ...base,
                    type: 'record',
                    valueType: extractSchemaTypeInfo(schema.additionalProperties as JSONSchema7),
                };
            }

            // Regular object with properties
            const shape: Record<string, SchemaTypeInfo> = {};
            if (schema.properties) {
                for (const [key, propSchema] of Object.entries(schema.properties)) {
                    shape[key] = extractSchemaTypeInfo(propSchema);
                }
            }

            return {
                ...base,
                type: 'object',
                shape,
            };
        }

        default:
            return { ...base, type: 'unknown' };
    }
}
