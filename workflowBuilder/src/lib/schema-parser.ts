/**
 * Schema Parser (Legacy)
 * 
 * This module provides Zod schema parsing utilities for backward compatibility.
 * For new code, use openapi-parser.ts which works with JSON Schema directly.
 * 
 * @deprecated Use openapi-parser.ts for new code
 */
import { z } from 'zod';
import type { FieldConfig, FieldMeta, FieldType, SchemaTypeInfo, GroupConfig, FormConfig, GroupMeta, JSONSchema7 } from '../types';

// Type guard helpers for Zod 4 schema types
type ZodTypeDef = z.ZodTypeAny['def'];

/**
 * Get the Zod type name from a schema
 */
function getZodTypeName(schema: z.ZodTypeAny): string {
  if (!schema) {
    return 'unknown';
  }
  
  const def = schema.def as ZodTypeDef & { typeName?: string } | undefined;
  
  if (def?.typeName) {
    return def.typeName;
  }
  
  const constructorName = schema.constructor?.name;
  if (constructorName && constructorName.startsWith('Zod')) {
    return constructorName;
  }
  
  const schemaAny = schema as unknown as Record<string, unknown>;
  if (schemaAny._zod && typeof schemaAny._zod === 'object') {
    const zodInfo = schemaAny._zod as Record<string, unknown>;
    if (zodInfo.def && typeof zodInfo.def === 'object') {
      const zodDef = zodInfo.def as Record<string, unknown>;
      if (zodDef.type && typeof zodDef.type === 'string') {
        return `Zod${zodDef.type.charAt(0).toUpperCase()}${zodDef.type.slice(1)}`;
      }
    }
  }
  
  return 'unknown';
}

/**
 * Get metadata from a Zod schema using Zod 4's .meta() API
 */
export function getSchemaMetadata(schema: z.ZodTypeAny): FieldMeta | undefined {
  try {
    const schemaWithMeta = schema as unknown as { meta?: () => FieldMeta | undefined };
    if (typeof schemaWithMeta.meta === 'function') {
      const meta = schemaWithMeta.meta();
      if (meta) {
        return meta;
      }
    }
    
    const schemaAny = schema as unknown as Record<string, unknown>;
    if (schemaAny._zod && typeof schemaAny._zod === 'object') {
      const zodMeta = schemaAny._zod as Record<string, unknown>;
      if (zodMeta.meta) {
        return zodMeta.meta as FieldMeta;
      }
    }
    
    const def = schema.def as unknown as Record<string, unknown>;
    if (def.meta) {
      return def.meta as FieldMeta;
    }
    
    const schemaWithDesc = schema as unknown as { description?: string };
    if (schemaWithDesc.description && typeof schemaWithDesc.description === 'string') {
      return { description: schemaWithDesc.description };
    }
    
    return undefined;
  } catch {
    return undefined;
  }
}

/**
 * Unwrap optional, nullable, default, and effect wrappers to get the inner schema
 */
export function unwrapSchema(schema: z.ZodTypeAny): z.ZodTypeAny {
  const typeName = getZodTypeName(schema);
  const def = schema.def as unknown as Record<string, unknown>;
  
  switch (typeName) {
    case 'ZodOptional':
    case 'ZodNullable': {
      const innerType = def.innerType as z.ZodTypeAny | undefined;
      if (innerType) return unwrapSchema(innerType);
      break;
    }
    case 'ZodDefault': {
      const innerType = def.innerType as z.ZodTypeAny | undefined;
      if (innerType) return unwrapSchema(innerType);
      break;
    }
    case 'ZodEffects': {
      const innerSchema = def.schema as z.ZodTypeAny | undefined;
      if (innerSchema) return unwrapSchema(innerSchema);
      break;
    }
    case 'ZodPipeline': {
      const inSchema = def.in as z.ZodTypeAny | undefined;
      if (inSchema) return unwrapSchema(inSchema);
      break;
    }
    case 'ZodBranded':
    case 'ZodCatch': {
      const innerType = def.innerType as z.ZodTypeAny | undefined;
      if (innerType) return unwrapSchema(innerType);
      break;
    }
  }
  
  return schema;
}

/**
 * Check if a schema is optional
 */
export function isSchemaOptional(schema: z.ZodTypeAny): boolean {
  const typeName = getZodTypeName(schema);
  const def = schema.def as unknown as Record<string, unknown>;
  
  if (typeName === 'ZodOptional' || typeName === 'ZodDefault') {
    return true;
  }
  
  if (typeName === 'ZodNullable' || typeName === 'ZodEffects') {
    const innerType = (def.innerType ?? def.schema) as z.ZodTypeAny | undefined;
    if (innerType) return isSchemaOptional(innerType);
  }
  
  return false;
}

/**
 * Check if a schema has a default value
 */
export function hasSchemaDefault(schema: z.ZodTypeAny): boolean {
  const typeName = getZodTypeName(schema);
  const def = schema.def as unknown as Record<string, unknown>;
  
  if (typeName === 'ZodDefault') {
    return true;
  }
  
  if (typeName === 'ZodOptional' || typeName === 'ZodNullable' || typeName === 'ZodEffects') {
    const innerType = (def.innerType ?? def.schema) as z.ZodTypeAny | undefined;
    if (innerType) return hasSchemaDefault(innerType);
  }
  
  return false;
}

/**
 * Get the default value from a schema
 */
export function getSchemaDefault(schema: z.ZodTypeAny): unknown {
  const typeName = getZodTypeName(schema);
  const def = schema.def as unknown as Record<string, unknown>;
  
  if (typeName === 'ZodDefault') {
    const defaultValue = def.defaultValue;
    if (typeof defaultValue === 'function') {
      try {
        return (defaultValue as () => unknown)();
      } catch {
        return undefined;
      }
    }
    return defaultValue;
  }
  
  if (typeName === 'ZodOptional' || typeName === 'ZodNullable' || typeName === 'ZodEffects') {
    const innerType = (def.innerType ?? def.schema) as z.ZodTypeAny | undefined;
    if (innerType) return getSchemaDefault(innerType);
  }
  
  return undefined;
}

/**
 * Infer the field type from a Zod schema
 */
export function inferFieldType(schema: z.ZodTypeAny): FieldType {
  const unwrapped = unwrapSchema(schema);
  const meta = getSchemaMetadata(schema);
  
  if (meta?.fieldType) {
    return meta.fieldType;
  }
  
  const typeName = getZodTypeName(unwrapped);
  const def = unwrapped.def as unknown as Record<string, unknown>;
  
  switch (typeName) {
    case 'ZodString': {
      const checks = def.checks as Array<{ kind: string }> | undefined;
      if (checks) {
        for (const check of checks) {
          if (check.kind === 'email') return 'email';
          if (check.kind === 'url') return 'url';
        }
      }
      return 'text';
    }
    case 'ZodNumber':
      return 'number';
    case 'ZodBoolean':
      return 'toggle';
    case 'ZodEnum':
      return 'select';
    case 'ZodLiteral':
      return 'text';
    case 'ZodArray': {
      const elementType = def.type as z.ZodTypeAny | undefined;
      if (elementType) {
        const unwrappedElement = unwrapSchema(elementType);
        if (getZodTypeName(unwrappedElement) === 'ZodString') {
          return 'tags';
        }
      }
      return 'array';
    }
    case 'ZodRecord':
      return 'record';
    case 'ZodUnion':
    case 'ZodDiscriminatedUnion':
      return 'union';
    case 'ZodObject':
      return 'object';
    default:
      return 'text';
  }
}

/**
 * Extract schema type information
 */
export function extractSchemaTypeInfo(schema: z.ZodTypeAny): SchemaTypeInfo {
  const unwrapped = unwrapSchema(schema);
  const isOptional = isSchemaOptional(schema);
  const hasDefault = hasSchemaDefault(schema);
  const defaultValue = getSchemaDefault(schema);
  
  let isNullable = false;
  let checkSchema = schema;
  let checkTypeName = getZodTypeName(checkSchema);
  while (checkTypeName === 'ZodOptional' || checkTypeName === 'ZodDefault') {
    const checkDef = checkSchema.def as unknown as Record<string, unknown>;
    checkSchema = checkDef.innerType as z.ZodTypeAny;
    checkTypeName = getZodTypeName(checkSchema);
  }
  if (checkTypeName === 'ZodNullable') {
    isNullable = true;
  }
  
  const base = { isOptional, isNullable, hasDefault, defaultValue };
  const typeName = getZodTypeName(unwrapped);
  const def = unwrapped.def as unknown as Record<string, unknown>;
  
  switch (typeName) {
    case 'ZodString':
      return { ...base, type: 'string' };
    case 'ZodNumber':
      return { ...base, type: 'number' };
    case 'ZodBoolean':
      return { ...base, type: 'boolean' };
    case 'ZodLiteral':
      return { ...base, type: 'literal', literalValue: def.value ?? def.values };
    case 'ZodEnum': {
      const values = def.values as string[] | Record<string, string> | undefined;
      const enumValues = Array.isArray(values) ? values : values ? Object.values(values) : [];
      return { ...base, type: 'enum', enumValues };
    }
    case 'ZodArray': {
      const elementType = def.type as z.ZodTypeAny | undefined;
      const result: SchemaTypeInfo = { ...base, type: 'array' };
      if (elementType) {
        result.innerType = extractSchemaTypeInfo(elementType);
      }
      return result;
    }
    case 'ZodRecord': {
      const keyType = def.keyType as z.ZodTypeAny | undefined;
      const valueType = def.valueType as z.ZodTypeAny | undefined;
      const result: SchemaTypeInfo = { ...base, type: 'record' };
      if (keyType) {
        result.keyType = extractSchemaTypeInfo(keyType);
      }
      if (valueType) {
        result.valueType = extractSchemaTypeInfo(valueType);
      }
      return result;
    }
    case 'ZodUnion':
    case 'ZodDiscriminatedUnion': {
      const options = def.options as z.ZodTypeAny[] | undefined;
      const result: SchemaTypeInfo = { ...base, type: 'union' };
      if (options) {
        result.unionTypes = options.map(extractSchemaTypeInfo);
      }
      return result;
    }
    case 'ZodObject': {
      const shape: Record<string, SchemaTypeInfo> = {};
      const schemaShape = (def.shape ?? {}) as Record<string, z.ZodTypeAny>;
      for (const [key, value] of Object.entries(schemaShape)) {
        shape[key] = extractSchemaTypeInfo(value);
      }
      return { ...base, type: 'object', shape };
    }
    default:
      return { ...base, type: 'unknown' };
  }
}

/**
 * Get the shape of an object schema
 */
function getObjectShape(schema: z.ZodTypeAny): Record<string, z.ZodTypeAny> {
  const def = schema.def as unknown as Record<string, unknown>;
  return (def.shape ?? {}) as Record<string, z.ZodTypeAny>;
}

/**
 * Create a placeholder JSON Schema for a Zod schema
 * This is used for backward compatibility when working with Zod schemas
 */
function createPlaceholderJsonSchema(schema: z.ZodTypeAny): JSONSchema7 {
  const typeName = getZodTypeName(unwrapSchema(schema));
  const meta = getSchemaMetadata(schema);
  
  const jsonSchema: JSONSchema7 = {};
  
  switch (typeName) {
    case 'ZodString':
      jsonSchema.type = 'string';
      break;
    case 'ZodNumber':
      jsonSchema.type = 'number';
      break;
    case 'ZodBoolean':
      jsonSchema.type = 'boolean';
      break;
    case 'ZodArray':
      jsonSchema.type = 'array';
      break;
    case 'ZodObject':
      jsonSchema.type = 'object';
      break;
    case 'ZodRecord':
      jsonSchema.type = 'object';
      jsonSchema.additionalProperties = true;
      break;
    default:
      jsonSchema.type = 'string';
  }
  
  if (meta?.description) {
    jsonSchema.description = meta.description;
  }
  
  const defaultValue = getSchemaDefault(schema);
  if (defaultValue !== undefined) {
    jsonSchema.default = defaultValue;
  }
  
  return jsonSchema;
}

/**
 * Extract field configurations from an object schema
 * @deprecated Use extractFieldConfigsFromSchema from openapi-parser.ts
 */
export function extractFieldConfigs(
  schema: z.ZodTypeAny,
  basePath = '',
  parentGroup?: string,
  flatten = false,
): FieldConfig[] {
  const fields: FieldConfig[] = [];
  const unwrapped = unwrapSchema(schema);
  const typeName = getZodTypeName(unwrapped);
  
  if (typeName === 'ZodObject') {
    const shape = getObjectShape(unwrapped);
    
    for (const [key, fieldSchema] of Object.entries(shape)) {
      const path = basePath ? `${basePath}.${key}` : key;
      const meta = getSchemaMetadata(fieldSchema) ?? {};
      const group = meta.group ?? parentGroup;
      const typeInfo = extractSchemaTypeInfo(fieldSchema);
      const fieldType = inferFieldType(fieldSchema);
      
      const fieldMeta: FieldMeta = { ...meta };
      if (group !== undefined) {
        fieldMeta.group = group;
      }
      
      const fieldConfig: FieldConfig = {
        path,
        name: key,
        jsonSchema: createPlaceholderJsonSchema(fieldSchema),
        meta: fieldMeta,
        defaultValue: getSchemaDefault(fieldSchema),
        required: !isSchemaOptional(fieldSchema),
        typeInfo,
      };
      
      if (basePath) {
        fieldConfig.parentPath = basePath;
      }
      
      const innerSchema = unwrapSchema(fieldSchema);
      const innerTypeName = getZodTypeName(innerSchema);
      const innerDef = innerSchema.def as unknown as Record<string, unknown>;
      
      if (innerTypeName === 'ZodObject' && fieldType === 'object') {
        const childFields = extractFieldConfigs(innerSchema, path, group, flatten);
        
        if (flatten) {
          fields.push(...childFields);
        } else {
          fieldConfig.children = childFields;
          fields.push(fieldConfig);
        }
      } else if (innerTypeName === 'ZodRecord') {
        const valueSchema = innerDef.valueType as z.ZodTypeAny | undefined;
        if (valueSchema) {
          const unwrappedValue = unwrapSchema(valueSchema);
          if (getZodTypeName(unwrappedValue) === 'ZodObject') {
            fieldConfig.children = extractFieldConfigs(unwrappedValue, `${path}.*`, group, false);
          }
        }
        fields.push(fieldConfig);
      } else if (innerTypeName === 'ZodArray') {
        const elementSchema = innerDef.type as z.ZodTypeAny | undefined;
        if (elementSchema) {
          const unwrappedElement = unwrapSchema(elementSchema);
          if (getZodTypeName(unwrappedElement) === 'ZodObject') {
            fieldConfig.children = extractFieldConfigs(unwrappedElement, `${path}[]`, group, false);
          }
        }
        fields.push(fieldConfig);
      } else if (innerTypeName === 'ZodUnion' || innerTypeName === 'ZodDiscriminatedUnion') {
        const options = innerDef.options as z.ZodTypeAny[] | undefined;
        
        let discriminatorKey = 'type';
        if (innerTypeName === 'ZodDiscriminatedUnion' && innerDef.discriminator) {
          discriminatorKey = innerDef.discriminator as string;
        }
        
        if (options) {
          fieldConfig.variants = options.map((option, index) => {
            const optionMeta = getSchemaMetadata(option) ?? {};
            const unwrappedOption = unwrapSchema(option);
            
            let discriminatorValue = optionMeta.discriminator ?? `variant-${index}`;
            let label = optionMeta.title ?? discriminatorValue;
            
            if (getZodTypeName(unwrappedOption) === 'ZodObject') {
              const optionShape = getObjectShape(unwrappedOption);
              const discriminatorField = optionShape[discriminatorKey];
              
              if (discriminatorField) {
                const unwrappedDiscriminator = unwrapSchema(discriminatorField);
                const discTypeName = getZodTypeName(unwrappedDiscriminator);
                const discDef = unwrappedDiscriminator.def as unknown as Record<string, unknown>;
                
                if (discTypeName === 'ZodLiteral') {
                  const litValue = discDef.value ?? discDef.values;
                  if (litValue !== undefined) {
                    discriminatorValue = String(litValue);
                    if (!optionMeta.title) {
                      label = discriminatorValue.charAt(0).toUpperCase() + discriminatorValue.slice(1);
                    }
                  }
                }
              }
            }
            
            let variantFields: FieldConfig[] = [];
            if (getZodTypeName(unwrappedOption) === 'ZodObject') {
              variantFields = extractFieldConfigs(unwrappedOption, `${path}.${discriminatorValue}`, group, false);
            }
            
            return {
              key: discriminatorValue,
              label,
              jsonSchema: createPlaceholderJsonSchema(option),
              fields: variantFields,
            };
          });
        }
        fields.push(fieldConfig);
      } else {
        fields.push(fieldConfig);
      }
    }
  }
  
  return fields.sort((a, b) => {
    const orderA = a.meta.order ?? 999;
    const orderB = b.meta.order ?? 999;
    return orderA - orderB;
  });
}

/**
 * Extract flattened field configurations
 * @deprecated Use extractFieldConfigsFromSchema from openapi-parser.ts
 */
export function extractFlattenedFieldConfigs(
  schema: z.ZodTypeAny,
  basePath = '',
  parentGroup?: string,
): FieldConfig[] {
  return extractFieldConfigs(schema, basePath, parentGroup, true);
}

/**
 * Get all field paths from a schema
 */
export function getAllFieldPaths(schema: z.ZodTypeAny): string[] {
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
  
  const fields = extractFieldConfigs(schema);
  traverse(fields);
  return paths;
}

/**
 * Find a field config by path
 */
export function findFieldByPath(
  fields: FieldConfig[],
  path: string,
): FieldConfig | undefined {
  for (const field of fields) {
    if (field.path === path) {
      return field;
    }
    if (field.children) {
      const found = findFieldByPath(field.children, path);
      if (found) return found;
    }
    if (field.variants) {
      for (const variant of field.variants) {
        const found = findFieldByPath(variant.fields, path);
        if (found) return found;
      }
    }
  }
  return undefined;
}

/**
 * Check if a field should be visible based on showWhen condition
 */
export function evaluateShowWhen(
  condition: FieldMeta['showWhen'],
  values: Record<string, unknown>,
): boolean {
  if (!condition) return true;
  
  const { field, value, operator = 'eq' } = condition;
  const fieldValue = getValueByPath(values, field);
  
  switch (operator) {
    case 'eq':
      return fieldValue === value;
    case 'neq':
      return fieldValue !== value;
    case 'in':
      return Array.isArray(value) && value.includes(fieldValue);
    case 'notIn':
      return Array.isArray(value) && !value.includes(fieldValue);
    default:
      return true;
  }
}

/**
 * Get value from object by dot-notation path
 */
export function getValueByPath(obj: Record<string, unknown>, path: string): unknown {
  const parts = path.split('.');
  let current: unknown = obj;
  
  for (const part of parts) {
    if (current === null || current === undefined) {
      return undefined;
    }
    if (typeof current !== 'object') {
      return undefined;
    }
    current = (current as Record<string, unknown>)[part];
  }
  
  return current;
}

/**
 * Set value in object by dot-notation path
 */
export function setValueByPath(
  obj: Record<string, unknown>,
  path: string,
  value: unknown,
): Record<string, unknown> {
  const parts = path.split('.');
  const result = { ...obj };
  let current: Record<string, unknown> = result;
  
  for (let i = 0; i < parts.length - 1; i++) {
    const part = parts[i]!;
    if (current[part] === undefined || current[part] === null) {
      const nextPart = parts[i + 1];
      current[part] = nextPart && /^\d+$/.test(nextPart) ? [] : {};
    } else {
      current[part] = Array.isArray(current[part])
        ? [...(current[part] as unknown[])]
        : { ...(current[part] as Record<string, unknown>) };
    }
    current = current[part] as Record<string, unknown>;
  }
  
  const lastPart = parts[parts.length - 1];
  if (lastPart) {
    current[lastPart] = value;
  }
  
  return result;
}

/**
 * Build default values object from schema
 */
export function buildDefaultValues(schema: z.ZodTypeAny): Record<string, unknown> {
  const unwrapped = unwrapSchema(schema);
  const typeName = getZodTypeName(unwrapped);
  
  if (typeName === 'ZodObject') {
    const result: Record<string, unknown> = {};
    const shape = getObjectShape(unwrapped);
    
    for (const [key, fieldSchema] of Object.entries(shape)) {
      const defaultValue = getSchemaDefault(fieldSchema);
      if (defaultValue !== undefined) {
        result[key] = defaultValue;
      } else {
        const innerSchema = unwrapSchema(fieldSchema);
        const innerTypeName = getZodTypeName(innerSchema);
        if (innerTypeName === 'ZodObject') {
          result[key] = buildDefaultValues(innerSchema);
        } else if (innerTypeName === 'ZodArray') {
          result[key] = [];
        } else if (innerTypeName === 'ZodRecord') {
          result[key] = {};
        }
      }
    }
    
    return result;
  }
  
  return {};
}

/**
 * Options for building form configuration
 */
export interface BuildFormConfigOptions {
  groups?: GroupMeta[];
  includeAdvanced?: boolean;
}

/**
 * Predefined group metadata
 */
const PREDEFINED_GROUP_META: Record<string, GroupMeta> = {
  'source-clusters': { id: 'source-clusters', title: 'Source Clusters', order: 1 },
  'target-clusters': { id: 'target-clusters', title: 'Target Clusters', order: 2 },
  'migration-configs': { id: 'migration-configs', title: 'Migration Configurations', order: 3 },
  'authentication': { id: 'authentication', title: 'Authentication', order: 4 },
  'snapshot-repo': { id: 'snapshot-repo', title: 'Snapshot Repository', order: 5 },
  'replayer-options': { id: 'replayer-options', title: 'Replayer Options', order: 6, collapsible: true, defaultCollapsed: true },
  'rfs-options': { id: 'rfs-options', title: 'RFS Options', order: 7, collapsible: true, defaultCollapsed: true },
  'metadata-options': { id: 'metadata-options', title: 'Metadata Options', order: 8, collapsible: true, defaultCollapsed: true },
  'snapshot-options': { id: 'snapshot-options', title: 'Snapshot Options', order: 9, collapsible: true, defaultCollapsed: true },
  'resource-requirements': { id: 'resource-requirements', title: 'Resource Requirements', order: 10, collapsible: true, defaultCollapsed: true },
  'advanced': { id: 'advanced', title: 'Advanced Options', order: 99, collapsible: true, defaultCollapsed: true, advanced: true },
  'general': { id: 'general', title: 'General', order: 0 },
};

/**
 * Build a complete form configuration from a Zod schema
 * @deprecated Use buildFormConfigFromSchema from openapi-parser.ts
 */
export function buildFormConfig(
  schema: z.ZodTypeAny,
  options: BuildFormConfigOptions = {},
): FormConfig {
  const { groups: customGroups = [], includeAdvanced = true } = options;
  
  const allFields = extractFlattenedFieldConfigs(schema);
  
  const fieldMap = new Map<string, FieldConfig>();
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
  addToFieldMap(allFields);
  
  const advancedFields = allFields.filter(f => f.meta.advanced);
  const basicFields = allFields.filter(f => !f.meta.advanced);
  
  const groupIds = new Set<string>();
  function collectGroups(fieldList: FieldConfig[]) {
    for (const field of fieldList) {
      if (field.meta.group) {
        groupIds.add(field.meta.group);
      }
      if (field.children) {
        collectGroups(field.children);
      }
      if (field.variants) {
        for (const variant of field.variants) {
          collectGroups(variant.fields);
        }
      }
    }
  }
  collectGroups(allFields);
  
  const groups: GroupConfig[] = [];
  
  for (const groupId of groupIds) {
    const customGroup = customGroups.find(g => g.id === groupId);
    const predefinedGroup = PREDEFINED_GROUP_META[groupId];
    
    const groupFields = allFields.filter(f => f.meta.group === groupId);
    
    if (groupFields.length === 0) continue;
    
    const isAdvancedGroup = groupFields.every(f => f.meta.advanced);
    
    if (isAdvancedGroup && !includeAdvanced) continue;
    
    const groupConfig: GroupConfig = {
      id: groupId,
      title: customGroup?.title ?? predefinedGroup?.title ?? groupId,
      order: customGroup?.order ?? predefinedGroup?.order ?? 999,
      fields: groupFields,
    };
    
    const description = customGroup?.description ?? predefinedGroup?.description;
    if (description) {
      groupConfig.description = description;
    }
    
    const collapsible = customGroup?.collapsible ?? predefinedGroup?.collapsible;
    if (collapsible !== undefined) {
      groupConfig.collapsible = collapsible;
    }
    
    const defaultCollapsed = customGroup?.defaultCollapsed ?? predefinedGroup?.defaultCollapsed;
    if (defaultCollapsed !== undefined) {
      groupConfig.defaultCollapsed = defaultCollapsed;
    }
    
    if (isAdvancedGroup) {
      groupConfig.advanced = true;
    }
    
    groups.push(groupConfig);
  }
  
  const ungroupedFields = allFields.filter(f => !f.meta.group);
  if (ungroupedFields.length > 0) {
    groups.unshift({
      id: 'general',
      title: 'General',
      order: 0,
      fields: ungroupedFields,
    });
  }
  
  groups.sort((a, b) => a.order - b.order);
  
  return {
    schema,
    groups,
    fieldMap,
    allFields,
    advancedFields,
    basicFields,
    defaultValues: buildDefaultValues(schema),
  };
}
