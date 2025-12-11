/**
 * Tests for OpenAPI/JSON Schema Parser
 */
import { describe, it, expect } from 'vitest';
import {
    inferFieldTypeFromSchema,
    extractSchemaTypeInfo,
    extractFieldMeta,
    extractFieldConfigsFromSchema,
    buildDefaultValuesFromSchema,
    buildFormConfigFromSchema,
    getAllFieldPathsFromSchema,
    findFieldByPathInSchema,
} from './openapi-parser';
import type { JSONSchema7 } from '../types/json-schema.types';

describe('inferFieldTypeFromSchema', () => {
    it('should infer text for string type', () => {
        expect(inferFieldTypeFromSchema({ type: 'string' })).toBe('text');
    });

    it('should infer url for string with uri format', () => {
        expect(inferFieldTypeFromSchema({ type: 'string', format: 'uri' })).toBe('url');
    });

    it('should infer url for string with url format', () => {
        expect(inferFieldTypeFromSchema({ type: 'string', format: 'url' })).toBe('url');
    });

    it('should infer email for string with email format', () => {
        expect(inferFieldTypeFromSchema({ type: 'string', format: 'email' })).toBe('email');
    });

    it('should infer password for string with password format', () => {
        expect(inferFieldTypeFromSchema({ type: 'string', format: 'password' })).toBe('password');
    });

    it('should use explicit fieldType', () => {
        expect(inferFieldTypeFromSchema({ type: 'string', fieldType: 'textarea' })).toBe('textarea');
    });

    it('should infer select for enum', () => {
        expect(inferFieldTypeFromSchema({ enum: ['a', 'b', 'c'] })).toBe('select');
    });

    it('should infer union for anyOf', () => {
        expect(inferFieldTypeFromSchema({ anyOf: [{}, {}] })).toBe('union');
    });

    it('should infer union for oneOf', () => {
        expect(inferFieldTypeFromSchema({ oneOf: [{}, {}] })).toBe('union');
    });

    it('should infer number for number type', () => {
        expect(inferFieldTypeFromSchema({ type: 'number' })).toBe('number');
    });

    it('should infer number for integer type', () => {
        expect(inferFieldTypeFromSchema({ type: 'integer' })).toBe('number');
    });

    it('should infer toggle for boolean type', () => {
        expect(inferFieldTypeFromSchema({ type: 'boolean' })).toBe('toggle');
    });

    it('should infer tags for array of strings', () => {
        expect(inferFieldTypeFromSchema({
            type: 'array',
            items: { type: 'string' }
        })).toBe('tags');
    });

    it('should infer array for array of objects', () => {
        expect(inferFieldTypeFromSchema({
            type: 'array',
            items: { type: 'object', properties: { name: { type: 'string' } } }
        })).toBe('array');
    });

    it('should infer record for object with additionalProperties', () => {
        expect(inferFieldTypeFromSchema({
            type: 'object',
            additionalProperties: { type: 'string' }
        })).toBe('record');
    });

    it('should infer object for object with properties', () => {
        expect(inferFieldTypeFromSchema({
            type: 'object',
            properties: { name: { type: 'string' } }
        })).toBe('object');
    });
});

describe('extractSchemaTypeInfo', () => {
    it('should extract string type info', () => {
        const info = extractSchemaTypeInfo({ type: 'string' });
        expect(info.type).toBe('string');
        expect(info.isOptional).toBe(false);
        expect(info.isNullable).toBe(false);
        expect(info.hasDefault).toBe(false);
    });

    it('should extract number type info', () => {
        const info = extractSchemaTypeInfo({ type: 'number' });
        expect(info.type).toBe('number');
    });

    it('should extract boolean type info', () => {
        const info = extractSchemaTypeInfo({ type: 'boolean' });
        expect(info.type).toBe('boolean');
    });

    it('should detect nullable from type array', () => {
        const info = extractSchemaTypeInfo({ type: ['string', 'null'] });
        expect(info.type).toBe('string');
        expect(info.isNullable).toBe(true);
    });

    it('should extract default value', () => {
        const info = extractSchemaTypeInfo({ type: 'string', default: 'hello' });
        expect(info.hasDefault).toBe(true);
        expect(info.defaultValue).toBe('hello');
    });

    it('should extract enum type info', () => {
        const info = extractSchemaTypeInfo({ enum: ['a', 'b', 'c'] });
        expect(info.type).toBe('enum');
        expect(info.enumValues).toEqual(['a', 'b', 'c']);
    });

    it('should extract literal type info', () => {
        const info = extractSchemaTypeInfo({ const: 'fixed' });
        expect(info.type).toBe('literal');
        expect(info.literalValue).toBe('fixed');
    });

    it('should extract array type info with inner type', () => {
        const info = extractSchemaTypeInfo({
            type: 'array',
            items: { type: 'string' }
        });
        expect(info.type).toBe('array');
        expect(info.innerType?.type).toBe('string');
    });

    it('should extract record type info with value type', () => {
        const info = extractSchemaTypeInfo({
            type: 'object',
            additionalProperties: { type: 'number' }
        });
        expect(info.type).toBe('record');
        expect(info.valueType?.type).toBe('number');
    });

    it('should extract object type info with shape', () => {
        const info = extractSchemaTypeInfo({
            type: 'object',
            properties: {
                name: { type: 'string' },
                age: { type: 'number' }
            }
        });
        expect(info.type).toBe('object');
        expect(info.shape?.name.type).toBe('string');
        expect(info.shape?.age.type).toBe('number');
    });

    it('should extract union type info', () => {
        const info = extractSchemaTypeInfo({
            anyOf: [
                { type: 'string' },
                { type: 'number' }
            ]
        });
        expect(info.type).toBe('union');
        expect(info.unionTypes).toHaveLength(2);
        expect(info.unionTypes?.[0].type).toBe('string');
        expect(info.unionTypes?.[1].type).toBe('number');
    });
});

describe('extractFieldMeta', () => {
    it('should extract all metadata fields', () => {
        const schema: JSONSchema7 = {
            type: 'string',
            title: 'Test Field',
            description: 'A test field',
            placeholder: 'Enter value',
            constraintText: 'Must be valid',
            helpText: 'Help text here',
            fieldType: 'text',
            order: 1,
            group: 'test-group',
            hidden: false,
            advanced: true,
            colSpan: 2,
        };

        const meta = extractFieldMeta(schema);

        expect(meta.title).toBe('Test Field');
        expect(meta.description).toBe('A test field');
        expect(meta.placeholder).toBe('Enter value');
        expect(meta.constraintText).toBe('Must be valid');
        expect(meta.helpText).toBe('Help text here');
        expect(meta.fieldType).toBe('text');
        expect(meta.order).toBe(1);
        expect(meta.group).toBe('test-group');
        expect(meta.hidden).toBe(false);
        expect(meta.advanced).toBe(true);
        expect(meta.colSpan).toBe(2);
    });
});

describe('extractFieldConfigsFromSchema', () => {
    it('should extract fields from object schema', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                name: { type: 'string', title: 'Name' },
                age: { type: 'number', title: 'Age' },
            },
            required: ['name'],
        };

        const fields = extractFieldConfigsFromSchema(schema);

        expect(fields).toHaveLength(2);
        expect(fields[0].name).toBe('name');
        expect(fields[0].required).toBe(true);
        expect(fields[1].name).toBe('age');
        expect(fields[1].required).toBe(false);
    });

    it('should handle nested objects', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                config: {
                    type: 'object',
                    properties: {
                        endpoint: { type: 'string' },
                    },
                },
            },
        };

        const fields = extractFieldConfigsFromSchema(schema);

        expect(fields[0].children).toHaveLength(1);
        expect(fields[0].children![0].path).toBe('config.endpoint');
    });

    it('should sort fields by order', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                third: { type: 'string', order: 3 },
                first: { type: 'string', order: 1 },
                second: { type: 'string', order: 2 },
            },
        };

        const fields = extractFieldConfigsFromSchema(schema);

        expect(fields[0].name).toBe('first');
        expect(fields[1].name).toBe('second');
        expect(fields[2].name).toBe('third');
    });

    it('should inherit group from parent', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                config: {
                    type: 'object',
                    group: 'parent-group',
                    properties: {
                        endpoint: { type: 'string' },
                    },
                },
            },
        };

        const fields = extractFieldConfigsFromSchema(schema);

        expect(fields[0].meta.group).toBe('parent-group');
        expect(fields[0].children![0].meta.group).toBe('parent-group');
    });

    it('should handle union types with variants', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                auth: {
                    anyOf: [
                        {
                            type: 'object',
                            title: 'Basic Auth',
                            properties: {
                                type: { const: 'basic' },
                                username: { type: 'string' },
                            },
                        },
                        {
                            type: 'object',
                            title: 'Token Auth',
                            properties: {
                                type: { const: 'token' },
                                token: { type: 'string' },
                            },
                        },
                    ],
                },
            },
        };

        const fields = extractFieldConfigsFromSchema(schema);

        expect(fields[0].variants).toHaveLength(2);
        expect(fields[0].variants![0].key).toBe('basic');
        expect(fields[0].variants![0].label).toBe('Basic Auth');
        expect(fields[0].variants![1].key).toBe('token');
        expect(fields[0].variants![1].label).toBe('Token Auth');
    });

    it('should handle record types', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                clusters: {
                    type: 'object',
                    additionalProperties: {
                        type: 'object',
                        properties: {
                            endpoint: { type: 'string' },
                        },
                    },
                },
            },
        };

        const fields = extractFieldConfigsFromSchema(schema);

        expect(fields[0].children).toHaveLength(1);
        expect(fields[0].children![0].path).toBe('clusters.*.endpoint');
    });

    it('should handle array types', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                items: {
                    type: 'array',
                    items: {
                        type: 'object',
                        properties: {
                            name: { type: 'string' },
                        },
                    },
                },
            },
        };

        const fields = extractFieldConfigsFromSchema(schema);

        expect(fields[0].children).toHaveLength(1);
        expect(fields[0].children![0].path).toBe('items[].name');
    });
});

describe('buildDefaultValuesFromSchema', () => {
    it('should build default values from schema', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                name: { type: 'string', default: 'default-name' },
                count: { type: 'number', default: 10 },
                enabled: { type: 'boolean', default: true },
            },
        };

        const defaults = buildDefaultValuesFromSchema(schema);

        expect(defaults.name).toBe('default-name');
        expect(defaults.count).toBe(10);
        expect(defaults.enabled).toBe(true);
    });

    it('should handle nested objects', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                config: {
                    type: 'object',
                    properties: {
                        endpoint: { type: 'string', default: 'http://localhost' },
                    },
                },
            },
        };

        const defaults = buildDefaultValuesFromSchema(schema);

        expect((defaults.config as Record<string, unknown>).endpoint).toBe('http://localhost');
    });

    it('should initialize arrays as empty', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                items: { type: 'array', items: { type: 'string' } },
            },
        };

        const defaults = buildDefaultValuesFromSchema(schema);

        expect(defaults.items).toEqual([]);
    });

    it('should initialize records as empty objects', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                clusters: {
                    type: 'object',
                    additionalProperties: { type: 'string' },
                },
            },
        };

        const defaults = buildDefaultValuesFromSchema(schema);

        expect(defaults.clusters).toEqual({});
    });

    it('should merge exampleValue with nested property defaults', () => {
        // This tests the main bug fix: when root-level exampleValue is partial,
        // nested defaults should still be included
        const schema: JSONSchema7 = {
            type: 'object',
            exampleValue: {
                skipApprovals: false,
                sourceClusters: { source1: { endpoint: 'https://source:9200' } },
            },
            properties: {
                skipApprovals: { type: 'boolean' },
                sourceClusters: {
                    type: 'object',
                    additionalProperties: {
                        type: 'object',
                        properties: {
                            endpoint: { type: 'string' },
                        },
                    },
                },
                snapshotConfig: {
                    type: 'object',
                    properties: {
                        snapshotNameConfig: {
                            type: 'object',
                            properties: {
                                snapshotNamePrefix: { type: 'string', default: 'migration-snapshot' },
                            },
                        },
                    },
                },
            },
        };

        const defaults = buildDefaultValuesFromSchema(schema);

        // exampleValue fields should be present
        expect(defaults.skipApprovals).toBe(false);
        expect(defaults.sourceClusters).toEqual({ source1: { endpoint: 'https://source:9200' } });
        
        // Nested defaults NOT in exampleValue should also be present
        expect((defaults.snapshotConfig as Record<string, unknown>)).toBeDefined();
        const snapshotConfig = defaults.snapshotConfig as Record<string, unknown>;
        const snapshotNameConfig = snapshotConfig.snapshotNameConfig as Record<string, unknown>;
        expect(snapshotNameConfig.snapshotNamePrefix).toBe('migration-snapshot');
    });

    it('should preserve exampleValue when it conflicts with defaults', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            exampleValue: {
                name: 'example-name',
            },
            properties: {
                name: { type: 'string', default: 'default-name' },
            },
        };

        const defaults = buildDefaultValuesFromSchema(schema);

        // exampleValue should take precedence over default
        expect(defaults.name).toBe('example-name');
    });

    it('should deeply merge nested objects from exampleValue and defaults', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            exampleValue: {
                level1: {
                    level2a: {
                        valueFromExample: 'example',
                    },
                },
            },
            properties: {
                level1: {
                    type: 'object',
                    properties: {
                        level2a: {
                            type: 'object',
                            properties: {
                                valueFromExample: { type: 'string' },
                                valueFromDefault: { type: 'string', default: 'default' },
                            },
                        },
                        level2b: {
                            type: 'object',
                            properties: {
                                onlyDefault: { type: 'string', default: 'only-default' },
                            },
                        },
                    },
                },
            },
        };

        const defaults = buildDefaultValuesFromSchema(schema);

        const level1 = defaults.level1 as Record<string, unknown>;
        const level2a = level1.level2a as Record<string, unknown>;
        const level2b = level1.level2b as Record<string, unknown>;

        // exampleValue should be preserved
        expect(level2a.valueFromExample).toBe('example');
        // Default at same level should be filled in
        expect(level2a.valueFromDefault).toBe('default');
        // Default at different path should be filled in
        expect(level2b.onlyDefault).toBe('only-default');
    });

    it('should handle arrays in exampleValue correctly', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            exampleValue: {
                items: ['item1', 'item2'],
            },
            properties: {
                items: { type: 'array', items: { type: 'string' } },
                otherItems: { type: 'array', items: { type: 'string' } },
            },
        };

        const defaults = buildDefaultValuesFromSchema(schema);

        // Array from exampleValue should be preserved
        expect(defaults.items).toEqual(['item1', 'item2']);
        // Array not in exampleValue should default to empty
        expect(defaults.otherItems).toEqual([]);
    });

    it('should handle empty exampleValue objects', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            exampleValue: {},
            properties: {
                name: { type: 'string', default: 'default-name' },
                count: { type: 'number', default: 42 },
            },
        };

        const defaults = buildDefaultValuesFromSchema(schema);

        // Defaults should be used since exampleValue is empty
        expect(defaults.name).toBe('default-name');
        expect(defaults.count).toBe(42);
    });

    it('should prefer exampleValue over default at property level', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                config: {
                    type: 'object',
                    exampleValue: { setting: 'from-example' },
                    default: { setting: 'from-default' },
                    properties: {
                        setting: { type: 'string' },
                    },
                },
            },
        };

        const defaults = buildDefaultValuesFromSchema(schema);

        // exampleValue should take precedence over default
        expect((defaults.config as Record<string, unknown>).setting).toBe('from-example');
    });

    it('should handle property-level exampleValue with nested defaults', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                config: {
                    type: 'object',
                    exampleValue: { 
                        partialSetting: 'from-example' 
                    },
                    properties: {
                        partialSetting: { type: 'string' },
                        nestedConfig: {
                            type: 'object',
                            properties: {
                                deepDefault: { type: 'string', default: 'deep-value' },
                            },
                        },
                    },
                },
            },
        };

        const defaults = buildDefaultValuesFromSchema(schema);

        const config = defaults.config as Record<string, unknown>;
        // exampleValue field should be present
        expect(config.partialSetting).toBe('from-example');
        // Nested default should also be present
        const nestedConfig = config.nestedConfig as Record<string, unknown>;
        expect(nestedConfig.deepDefault).toBe('deep-value');
    });

    it('should preserve explicit false and 0 values from exampleValue', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            exampleValue: {
                enabled: false,
                count: 0,
                name: '',
            },
            properties: {
                enabled: { type: 'boolean', default: true },
                count: { type: 'number', default: 100 },
                name: { type: 'string', default: 'default' },
            },
        };

        const defaults = buildDefaultValuesFromSchema(schema);

        // Explicit false, 0, and empty string should be preserved
        expect(defaults.enabled).toBe(false);
        expect(defaults.count).toBe(0);
        expect(defaults.name).toBe('');
    });
});

describe('buildFormConfigFromSchema', () => {
    it('should build form config with groups', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                name: { type: 'string', group: 'general' },
                endpoint: { type: 'string', group: 'connection' },
            },
        };

        const config = buildFormConfigFromSchema(schema);

        expect(config.groups.length).toBeGreaterThanOrEqual(2);
        expect(config.allFields).toHaveLength(2);
        expect(config.fieldMap.size).toBe(2);
    });

    it('should use structure-based grouping from top-level properties', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                sourceClusters: {
                    type: 'object',
                    properties: {
                        endpoint: { type: 'string' },
                    },
                },
                targetClusters: {
                    type: 'object',
                    properties: {
                        endpoint: { type: 'string' },
                    },
                },
            },
        };

        const config = buildFormConfigFromSchema(schema);

        // Top-level properties become groups
        expect(config.groups.length).toBe(2);
        expect(config.groups.map(g => g.id)).toContain('sourceClusters');
        expect(config.groups.map(g => g.id)).toContain('targetClusters');
        
        // Group titles are inferred from property names
        const sourceGroup = config.groups.find(g => g.id === 'sourceClusters');
        expect(sourceGroup?.title).toBe('Source Clusters');
    });

    it('should separate advanced and basic fields', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                basic: { type: 'string' },
                advanced: { type: 'string', advanced: true },
            },
        };

        const config = buildFormConfigFromSchema(schema);

        expect(config.basicFields).toHaveLength(1);
        expect(config.advancedFields).toHaveLength(1);
    });

    it('should infer group title from property name using camelCase to Title Case', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                migrationConfigs: {
                    type: 'object',
                    properties: {
                        name: { type: 'string' },
                    },
                },
            },
        };

        const config = buildFormConfigFromSchema(schema);

        const group = config.groups.find(g => g.id === 'migrationConfigs');
        expect(group?.title).toBe('Migration Configs');
    });
});

describe('getAllFieldPathsFromSchema', () => {
    it('should get all field paths', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                name: { type: 'string' },
                config: {
                    type: 'object',
                    properties: {
                        endpoint: { type: 'string' },
                    },
                },
            },
        };

        const paths = getAllFieldPathsFromSchema(schema);

        expect(paths).toContain('name');
        expect(paths).toContain('config');
        expect(paths).toContain('config.endpoint');
    });
});

describe('findFieldByPathInSchema', () => {
    it('should find field by path', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                name: { type: 'string', title: 'Name' },
            },
        };

        const fields = extractFieldConfigsFromSchema(schema);
        const field = findFieldByPathInSchema(fields, 'name');

        expect(field).toBeDefined();
        expect(field?.meta.title).toBe('Name');
    });

    it('should find nested field by path', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                config: {
                    type: 'object',
                    properties: {
                        endpoint: { type: 'string', title: 'Endpoint' },
                    },
                },
            },
        };

        const fields = extractFieldConfigsFromSchema(schema);
        const field = findFieldByPathInSchema(fields, 'config.endpoint');

        expect(field).toBeDefined();
        expect(field?.meta.title).toBe('Endpoint');
    });

    it('should return undefined for non-existent path', () => {
        const schema: JSONSchema7 = {
            type: 'object',
            properties: {
                name: { type: 'string' },
            },
        };

        const fields = extractFieldConfigsFromSchema(schema);
        const field = findFieldByPathInSchema(fields, 'nonexistent');

        expect(field).toBeUndefined();
    });
});
