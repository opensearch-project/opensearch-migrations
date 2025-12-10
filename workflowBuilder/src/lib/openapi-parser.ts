/**
 * OpenAPI/JSON Schema Parser
 * 
 * Parses JSON Schema (generated from Zod via @asteasolutions/zod-to-openapi)
 * and extracts field configurations for form rendering.
 * 
 * This module uses the schema's natural structure for grouping:
 * - Top-level properties become form groups
 * - Group titles are inferred from property names (e.g., "sourceClusters" → "Source Clusters")
 * - No predefined group metadata needed
 */

import type {
    JSONSchema7,
    JSONSchema7TypeName,
} from '../types/json-schema.types';
import type {
    FieldConfig,
    FieldMeta,
    FieldType,
    SchemaTypeInfo,
    GroupConfig,
} from '../types';

/**
 * Form configuration built from JSON Schema
 */
export interface OpenAPIFormConfig {
    /** Original JSON Schema */
    jsonSchema: JSONSchema7;

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
function getPrimaryType(schema: JSONSchema7): JSONSchema7TypeName | undefined {
    if (!schema.type) return undefined;
    return Array.isArray(schema.type) ? schema.type[0] : schema.type;
}

/**
 * Check if schema type includes null
 */
function isNullable(schema: JSONSchema7): boolean {
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
            return { ...base, type: 'object', shape };
        }

        default:
            return base;
    }
}

/**
 * Extract field metadata from JSON Schema
 */
export function extractFieldMeta(schema: JSONSchema7): FieldMeta {
    return {
        title: schema.title,
        description: schema.description,
        placeholder: schema.placeholder,
        constraintText: schema.constraintText,
        helpText: schema.helpText,
        fieldType: schema.fieldType as FieldType | undefined,
        options: schema.options,
        disabled: schema.disabled,
        readOnly: schema.readOnly,
        order: schema.order,
        group: schema.group,
        hidden: schema.hidden,
        advanced: schema.advanced,
        colSpan: schema.colSpan,
        errorMessages: schema.errorMessages,
        showWhen: schema.showWhen,
        itemTitle: schema.itemTitle,
        addButtonText: schema.addButtonText,
        minItems: schema.minItems,
        maxItems: schema.maxItems,
        discriminator: schema.discriminator,
        variantLabels: schema.variantLabels,
    };
}

/**
 * Extract field configurations from JSON Schema
 */
export function extractFieldConfigsFromSchema(
    schema: JSONSchema7,
    basePath = '',
    parentGroup?: string,
    requiredFields?: string[],
): FieldConfig[] {
    const fields: FieldConfig[] = [];

    // Only process object schemas with properties
    if (getPrimaryType(schema) !== 'object' || !schema.properties) {
        return fields;
    }

    // Use schema.required if requiredFields not explicitly provided
    const effectiveRequired = requiredFields ?? schema.required ?? [];

    for (const [key, propSchema] of Object.entries(schema.properties)) {
        const path = basePath ? `${basePath}.${key}` : key;
        const meta = extractFieldMeta(propSchema);
        const group = meta.group ?? parentGroup;
        const typeInfo = extractSchemaTypeInfo(propSchema);
        const fieldType = inferFieldTypeFromSchema(propSchema);
        const isRequired = effectiveRequired.includes(key);

        // Update typeInfo.isOptional based on required array
        typeInfo.isOptional = !isRequired;

        const fieldMeta: FieldMeta = { ...meta };
        if (group !== undefined) {
            fieldMeta.group = group;
        }

        const fieldConfig: FieldConfig = {
            path,
            name: key,
            jsonSchema: propSchema,
            meta: fieldMeta,
            defaultValue: propSchema.default,
            required: isRequired,
            typeInfo,
        };

        // Only set parentPath if basePath is non-empty
        if (basePath) {
            fieldConfig.parentPath = basePath;
        }

        // Handle nested structures based on field type
        if (fieldType === 'object' && propSchema.properties) {
            // Recursively extract nested object fields
            fieldConfig.children = extractFieldConfigsFromSchema(
                propSchema,
                path,
                group,
                propSchema.required || []
            );
        } else if (fieldType === 'record' && propSchema.additionalProperties) {
            // For records, extract the value schema's fields
            const valueSchema = propSchema.additionalProperties as JSONSchema7;
            if (getPrimaryType(valueSchema) === 'object' && valueSchema.properties) {
                fieldConfig.children = extractFieldConfigsFromSchema(
                    valueSchema,
                    `${path}.*`,
                    group,
                    valueSchema.required || []
                );
            }
        } else if (fieldType === 'array' && propSchema.items) {
            // For arrays, extract the element schema's fields
            const itemSchema = propSchema.items as JSONSchema7;
            if (!Array.isArray(itemSchema) && getPrimaryType(itemSchema) === 'object' && itemSchema.properties) {
                fieldConfig.children = extractFieldConfigsFromSchema(
                    itemSchema,
                    `${path}[]`,
                    group,
                    itemSchema.required || []
                );
            }
        } else if (fieldType === 'union') {
            // For unions, extract variants
            const options = propSchema.anyOf || propSchema.oneOf || [];
            const variantLabels = meta.variantLabels || {};

            fieldConfig.variants = options.map((optionSchema, index) => {
                const optionMeta = extractFieldMeta(optionSchema);
                let discriminatorValue = optionMeta.discriminator ?? `variant-${index}`;
                let label = optionMeta.title ?? discriminatorValue;

                // Try to extract discriminator from object properties
                // Also track if we found a property-name discriminator pattern
                let isPropertyNameDiscriminator = false;
                let innerSchema: JSONSchema7 | undefined;
                
                if (getPrimaryType(optionSchema) === 'object' && optionSchema.properties) {
                    // Look for a discriminator field (commonly 'type' or a field with const)
                    for (const [propKey, propValue] of Object.entries(optionSchema.properties)) {
                        if (propValue.const !== undefined) {
                            discriminatorValue = String(propValue.const);
                            label = optionMeta.title ??
                                discriminatorValue.charAt(0).toUpperCase() + discriminatorValue.slice(1);
                            break;
                        }
                        // Also check if the property name itself is the discriminator (e.g., { basic: {...} })
                        // This handles schemas like authConfig where the variant is identified by property name
                        if (optionSchema.required?.includes(propKey) && 
                            Object.keys(optionSchema.properties).length === 1) {
                            discriminatorValue = propKey;
                            isPropertyNameDiscriminator = true;
                            // The inner schema is the value of the discriminator property
                            innerSchema = propValue as JSONSchema7;
                            // Use variantLabels if available, otherwise format the key
                            label = variantLabels[propKey] ?? 
                                optionMeta.title ?? 
                                propertyNameToTitle(propKey);
                            break;
                        }
                    }
                }

                let variantFields: FieldConfig[] = [];
                if (getPrimaryType(optionSchema) === 'object' && optionSchema.properties) {
                    // For property-name discriminator pattern, extract fields from the inner schema
                    // to avoid double nesting (e.g., authConfig.basic.basic.secretName)
                    const schemaToExtract = isPropertyNameDiscriminator && innerSchema 
                        ? innerSchema 
                        : optionSchema;
                    const requiredFields = isPropertyNameDiscriminator && innerSchema
                        ? innerSchema.required || []
                        : optionSchema.required || [];
                    
                    variantFields = extractFieldConfigsFromSchema(
                        schemaToExtract,
                        `${path}.${discriminatorValue}`,
                        group,
                        requiredFields
                    );
                }

                return {
                    key: discriminatorValue,
                    label,
                    jsonSchema: optionSchema,
                    fields: variantFields,
                };
            });
        }

        fields.push(fieldConfig);
    }

    // Sort by order
    return fields.sort((a, b) => {
        const orderA = a.meta.order ?? 999;
        const orderB = b.meta.order ?? 999;
        return orderA - orderB;
    });
}

/**
 * Recursively collect default values from individual property defaults
 * This function traverses the schema structure to find all defaults,
 * including those nested in objects, arrays, and records.
 */
function collectPropertyDefaults(schema: JSONSchema7): Record<string, unknown> {
    const result: Record<string, unknown> = {};

    if (getPrimaryType(schema) !== 'object' || !schema.properties) {
        return result;
    }

    for (const [key, propSchema] of Object.entries(schema.properties)) {
        if (propSchema.default !== undefined) {
            // Property has an explicit default - use it
            result[key] = propSchema.default;
        } else {
            const propType = getPrimaryType(propSchema);
            if (propType === 'object' && propSchema.properties) {
                // Nested object - recursively collect defaults
                const nestedDefaults = collectPropertyDefaults(propSchema);
                // Only include nested defaults if they have values
                if (Object.keys(nestedDefaults).length > 0) {
                    result[key] = nestedDefaults;
                }
            } else if (propType === 'object' && propSchema.additionalProperties) {
                // For records, we can't generate defaults for unknown keys
                result[key] = {};
            } else if (propType === 'array') {
                // Arrays default to empty - we don't auto-populate array items
                // because we don't know how many items the user wants
                result[key] = [];
            }
        }
    }

    return result;
}

/**
 * Build default values for an array item based on its schema
 * This is used to populate defaults when a new array item is added
 */
export function buildArrayItemDefaults(itemSchema: JSONSchema7): Record<string, unknown> {
    if (getPrimaryType(itemSchema) !== 'object' || !itemSchema.properties) {
        return {};
    }
    return collectPropertyDefaults(itemSchema);
}

/**
 * Deep merge two objects, with source values taking precedence
 */
function deepMergeDefaults(target: Record<string, unknown>, source: Record<string, unknown>): Record<string, unknown> {
    const result = { ...target };

    for (const [key, sourceValue] of Object.entries(source)) {
        const targetValue = result[key];

        if (
            sourceValue !== null &&
            typeof sourceValue === 'object' &&
            !Array.isArray(sourceValue) &&
            targetValue !== null &&
            typeof targetValue === 'object' &&
            !Array.isArray(targetValue)
        ) {
            // Both are objects, merge recursively
            result[key] = deepMergeDefaults(
                targetValue as Record<string, unknown>,
                sourceValue as Record<string, unknown>
            );
        } else if (sourceValue !== undefined) {
            // Source value takes precedence
            result[key] = sourceValue;
        }
    }

    return result;
}

/**
 * Build default values from JSON Schema
 * 
 * This function extracts default values from a JSON Schema by:
 * 1. Recursively collecting defaults from individual property definitions
 * 2. If the schema has a root-level `default` property, merge it with collected defaults
 *    (root-level default takes precedence for any overlapping values)
 * 
 * This ensures that both explicitly defined root defaults AND individual property
 * defaults are included in the final default values object.
 */
export function buildDefaultValuesFromSchema(schema: JSONSchema7): Record<string, unknown> {
    // First, recursively collect defaults from individual properties
    const propertyDefaults = collectPropertyDefaults(schema);

    // If there's a root-level default, merge it with property defaults
    // Root-level default takes precedence for any overlapping values
    if (schema.default !== undefined && typeof schema.default === 'object' && schema.default !== null) {
        return deepMergeDefaults(propertyDefaults, schema.default as Record<string, unknown>);
    }

    return propertyDefaults;
}

/**
 * Options for building form configuration from JSON Schema
 */
export interface BuildFormConfigFromSchemaOptions {
    /** Whether to include advanced fields by default */
    includeAdvanced?: boolean;
}

/**
 * Build a complete form configuration from JSON Schema
 * 
 * Supports two grouping strategies:
 * 1. **Explicit group assignment**: When fields have a `group` property, they are grouped by that value
 * 2. **Structure-based grouping**: When top-level properties are objects without explicit groups,
 *    they become groups with their nested properties as fields
 * 
 * Group titles are inferred from:
 * - The schema's `title` property if present
 * - The property name converted to Title Case (e.g., "sourceClusters" → "Source Clusters")
 */
export function buildFormConfigFromSchema(
    schema: JSONSchema7,
    options: BuildFormConfigFromSchemaOptions = {},
): OpenAPIFormConfig {
    const { includeAdvanced = true } = options;

    const groupsMap = new Map<string, GroupConfig>();
    const allFields: FieldConfig[] = [];
    const fieldMap = new Map<string, FieldConfig>();

    // Helper to add fields to the field map recursively
    function addToFieldMap(fieldList: FieldConfig[]) {
        for (const field of fieldList) {
            fieldMap.set(field.path, field);
            if (field.children) {
                addToFieldMap(field.children);
            }
            if (field.variants) {
                for (const variant of field.variants) {
                    addToFieldMap(variant.fields);
                }
            }
        }
    }

    // Helper to get or create a group
    function getOrCreateGroup(groupId: string, order: number, title?: string, description?: string): GroupConfig {
        if (!groupsMap.has(groupId)) {
            groupsMap.set(groupId, {
                id: groupId,
                title: title || propertyNameToTitle(groupId),
                order,
                fields: [],
                description,
            });
        }
        return groupsMap.get(groupId)!;
    }

    // Check if schema uses explicit group assignments
    const hasExplicitGroups = schema.properties && 
        Object.values(schema.properties).some(prop => prop.group !== undefined);

    if (schema.properties) {
        let groupOrder = 0;
        const requiredFields = schema.required ?? [];

        for (const [propName, propSchema] of Object.entries(schema.properties)) {
            groupOrder++;
            
            const propType = getPrimaryType(propSchema);
            const meta = extractFieldMeta(propSchema);
            const typeInfo = extractSchemaTypeInfo(propSchema);
            const isRequired = requiredFields.includes(propName);
            typeInfo.isOptional = !isRequired;
            const isAdvanced = propSchema.advanced || false;

            // Skip advanced fields if not including them
            if (isAdvanced && !includeAdvanced) {
                continue;
            }

            // Get title from schema metadata or infer from property name
            const title = propSchema.title || propertyNameToTitle(propName);
            const description = propSchema.description;

            // Determine grouping strategy
            if (hasExplicitGroups) {
                // Use explicit group assignment
                const groupId = meta.group || 'general';
                const group = getOrCreateGroup(groupId, groupOrder);

                // Create field config
                const fieldConfig: FieldConfig = {
                    path: propName,
                    name: propName,
                    jsonSchema: propSchema,
                    meta: {
                        ...meta,
                        title: meta.title || title,
                    },
                    defaultValue: propSchema.default,
                    required: isRequired,
                    typeInfo,
                };

                // Handle nested structures
                if (propType === 'object' && propSchema.properties) {
                    fieldConfig.children = extractFieldConfigsFromSchema(
                        propSchema,
                        propName,
                        groupId,
                        propSchema.required || []
                    );
                } else if (propType === 'object' && propSchema.additionalProperties) {
                    const valueSchema = propSchema.additionalProperties as JSONSchema7;
                    if (getPrimaryType(valueSchema) === 'object' && valueSchema.properties) {
                        fieldConfig.children = extractFieldConfigsFromSchema(
                            valueSchema,
                            `${propName}.*`,
                            groupId,
                            valueSchema.required || []
                        );
                    }
                } else if (propType === 'array' && propSchema.items) {
                    const itemSchema = propSchema.items as JSONSchema7;
                    if (!Array.isArray(itemSchema) && getPrimaryType(itemSchema) === 'object' && itemSchema.properties) {
                        fieldConfig.children = extractFieldConfigsFromSchema(
                            itemSchema,
                            `${propName}[]`,
                            groupId,
                            itemSchema.required || []
                        );
                    }
                } else if (propSchema.anyOf || propSchema.oneOf) {
                    // Handle union types
                    const options = propSchema.anyOf || propSchema.oneOf || [];
                    const variantLabels = meta.variantLabels || {};
                    
                    fieldConfig.variants = options.map((optionSchema, index) => {
                        const optionMeta = extractFieldMeta(optionSchema);
                        let discriminatorValue = optionMeta.discriminator ?? `variant-${index}`;
                        let label = optionMeta.title ?? discriminatorValue;

                        // Track if we found a property-name discriminator pattern
                        let isPropertyNameDiscriminator = false;
                        let innerSchema: JSONSchema7 | undefined;

                        if (getPrimaryType(optionSchema) === 'object' && optionSchema.properties) {
                            for (const [propKey, propValue] of Object.entries(optionSchema.properties)) {
                                if (propValue.const !== undefined) {
                                    discriminatorValue = String(propValue.const);
                                    label = optionMeta.title ??
                                        discriminatorValue.charAt(0).toUpperCase() + discriminatorValue.slice(1);
                                    break;
                                }
                                // Also check if the property name itself is the discriminator (e.g., { basic: {...} })
                                // This handles schemas like authConfig where the variant is identified by property name
                                if (optionSchema.required?.includes(propKey) && 
                                    Object.keys(optionSchema.properties).length === 1) {
                                    discriminatorValue = propKey;
                                    isPropertyNameDiscriminator = true;
                                    // The inner schema is the value of the discriminator property
                                    innerSchema = propValue as JSONSchema7;
                                    // Use variantLabels if available, otherwise format the key
                                    label = variantLabels[propKey] ?? 
                                        optionMeta.title ?? 
                                        propertyNameToTitle(propKey);
                                    break;
                                }
                            }
                        }

                        let variantFields: FieldConfig[] = [];
                        if (getPrimaryType(optionSchema) === 'object' && optionSchema.properties) {
                            // For property-name discriminator pattern, extract fields from the inner schema
                            // to avoid double nesting (e.g., authConfig.basic.basic.secretName)
                            const schemaToExtract = isPropertyNameDiscriminator && innerSchema 
                                ? innerSchema 
                                : optionSchema;
                            const requiredFields = isPropertyNameDiscriminator && innerSchema
                                ? innerSchema.required || []
                                : optionSchema.required || [];
                            
                            variantFields = extractFieldConfigsFromSchema(
                                schemaToExtract,
                                `${propName}.${discriminatorValue}`,
                                groupId,
                                requiredFields
                            );
                        }

                        return {
                            key: discriminatorValue,
                            label,
                            jsonSchema: optionSchema,
                            fields: variantFields,
                        };
                    });
                }

                group.fields.push(fieldConfig);
                allFields.push(fieldConfig);
                addToFieldMap([fieldConfig]);
            } else {
                // Use structure-based grouping (top-level properties become groups)
                let groupFields: FieldConfig[] = [];

                if (propType === 'object' && propSchema.properties) {
                    // Object with properties - extract nested fields
                    groupFields = extractFieldConfigsFromSchema(
                        propSchema,
                        propName,
                        undefined,
                        propSchema.required || []
                    );
                } else if (propType === 'object' && propSchema.additionalProperties) {
                    // Record type - create a single field for the record
                    const recordField: FieldConfig = {
                        path: propName,
                        name: propName,
                        jsonSchema: propSchema,
                        meta: {
                            ...meta,
                            title: meta.title || title,
                        },
                        defaultValue: propSchema.default,
                        required: isRequired,
                        typeInfo,
                    };

                    const valueSchema = propSchema.additionalProperties as JSONSchema7;
                    if (getPrimaryType(valueSchema) === 'object' && valueSchema.properties) {
                        recordField.children = extractFieldConfigsFromSchema(
                            valueSchema,
                            `${propName}.*`,
                            undefined,
                            valueSchema.required || []
                        );
                    }

                    groupFields = [recordField];
                } else if (propType === 'array') {
                    // Array type - create a single field for the array
                    const arrayField: FieldConfig = {
                        path: propName,
                        name: propName,
                        jsonSchema: propSchema,
                        meta: {
                            ...meta,
                            title: meta.title || title,
                        },
                        defaultValue: propSchema.default ?? [],
                        required: isRequired,
                        typeInfo,
                    };

                    if (propSchema.items && !Array.isArray(propSchema.items)) {
                        const itemSchema = propSchema.items as JSONSchema7;
                        if (getPrimaryType(itemSchema) === 'object' && itemSchema.properties) {
                            arrayField.children = extractFieldConfigsFromSchema(
                                itemSchema,
                                `${propName}[]`,
                                undefined,
                                itemSchema.required || []
                            );
                        }
                    }

                    groupFields = [arrayField];
                } else {
                    // Scalar type - create a single field
                    groupFields = [{
                        path: propName,
                        name: propName,
                        jsonSchema: propSchema,
                        meta: {
                            ...meta,
                            title: meta.title || title,
                        },
                        defaultValue: propSchema.default,
                        required: isRequired,
                        typeInfo,
                    }];
                }

                // Create the group
                const groupConfig: GroupConfig = {
                    id: propName,
                    title,
                    order: propSchema.order ?? groupOrder,
                    fields: groupFields,
                };

                if (description) {
                    groupConfig.description = description;
                }

                // Make record and array groups collapsible by default
                if (propType === 'object' && propSchema.additionalProperties) {
                    groupConfig.collapsible = true;
                    groupConfig.defaultCollapsed = false;
                } else if (propType === 'array') {
                    groupConfig.collapsible = true;
                    groupConfig.defaultCollapsed = false;
                }

                if (isAdvanced) {
                    groupConfig.advanced = true;
                    groupConfig.collapsible = true;
                    groupConfig.defaultCollapsed = true;
                }

                groupsMap.set(propName, groupConfig);
                allFields.push(...groupFields);
                addToFieldMap(groupFields);
            }
        }
    }

    // Convert groups map to array and sort by order
    const groups = Array.from(groupsMap.values()).sort((a, b) => a.order - b.order);

    // Separate advanced and basic fields
    const advancedFields = allFields.filter(f => f.meta.advanced);
    const basicFields = allFields.filter(f => !f.meta.advanced);

    return {
        jsonSchema: schema,
        groups,
        fieldMap,
        allFields,
        advancedFields,
        basicFields,
        defaultValues: buildDefaultValuesFromSchema(schema),
    };
}

/**
 * Get all field paths from a JSON Schema
 */
export function getAllFieldPathsFromSchema(schema: JSONSchema7): string[] {
    const paths: string[] = [];

    function traverse(fields: FieldConfig[]) {
        for (const field of fields) {
            paths.push(field.path);
            if (field.children) {
                traverse(field.children);
            }
            if (field.variants) {
                for (const variant of field.variants) {
                    traverse(variant.fields);
                }
            }
        }
    }

    const fields = extractFieldConfigsFromSchema(schema, '', undefined, schema.required || []);
    traverse(fields);
    return paths;
}

/**
 * Find a field config by path in a list of fields
 */
export function findFieldByPathInSchema(
    fields: FieldConfig[],
    path: string,
): FieldConfig | undefined {
    for (const field of fields) {
        if (field.path === path) {
            return field;
        }
        if (field.children) {
            const found = findFieldByPathInSchema(field.children, path);
            if (found) return found;
        }
        if (field.variants) {
            for (const variant of field.variants) {
                const found = findFieldByPathInSchema(variant.fields, path);
                if (found) return found;
            }
        }
    }
    return undefined;
}

/**
 * Resolve $ref references in a JSON Schema
 * 
 * @param schema - The schema containing $ref
 * @param definitions - The definitions/components to resolve from
 * @returns The resolved schema
 */
export function resolveSchemaRef(
    schema: JSONSchema7,
    definitions: Record<string, JSONSchema7> = {},
): JSONSchema7 {
    if (!schema.$ref) {
        return schema;
    }

    // Parse the $ref path (e.g., "#/components/schemas/MyType" or "#/$defs/MyType")
    const refPath = schema.$ref;
    const parts = refPath.split('/');

    // Remove the leading "#"
    if (parts[0] === '#') {
        parts.shift();
    }

    // Navigate to the referenced schema
    let resolved: JSONSchema7 | undefined;

    if (parts[0] === 'components' && parts[1] === 'schemas' && parts[2]) {
        resolved = definitions[parts[2]];
    } else if (parts[0] === '$defs' && parts[1]) {
        resolved = definitions[parts[1]];
    } else if (parts[0] === 'definitions' && parts[1]) {
        resolved = definitions[parts[1]];
    }

    if (!resolved) {
        console.warn(`Could not resolve $ref: ${refPath}`);
        return schema;
    }

    // Merge the resolved schema with any additional properties from the original
    const { $ref, ...rest } = schema;
    return { ...resolved, ...rest };
}
