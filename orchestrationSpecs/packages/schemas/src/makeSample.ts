import { z } from 'zod';
import {OVERALL_MIGRATION_CONFIG} from "./userSchemas";
import {
    ARGO_WORKFLOW_SCHEMA,
    PARAMETERIZED_MIGRATION_CONFIG,
    PARAMETERIZED_MIGRATION_CONFIG_ARRAYS
} from "./argoSchemas";

// Helper to unwrap Zod schema modifiers
function unwrapSchema(schema: z.ZodTypeAny): z.ZodTypeAny {
    const constructorName = schema.constructor.name;

    if (constructorName === 'ZodDefault' || constructorName === 'ZodOptional' || constructorName === 'ZodNullable') {
        const innerType = (schema as any)._def?.innerType;
        if (innerType) {
            return unwrapSchema(innerType as z.ZodTypeAny);
        }
    }

    return schema;
}

// Check if schema is optional
function isOptional(schema: z.ZodTypeAny): boolean {
    return schema.constructor.name === 'ZodOptional';
}

// Check if array has minimum requirement
function getArrayMinLength(schema: z.ZodTypeAny): number | undefined {
    const unwrapped = unwrapSchema(schema);
    const checks = (unwrapped as any).def?.checks || [];
    const minCheck = checks
        .filter((c: any) => (c._zod?.def?.check ?? c.check) === 'min_length')
        .map((c: any) => c._zod?.def?.minimum ?? c.minimum)[0];
    return minCheck;
}

// Get the type name for display
function getTypeName(schema: z.ZodTypeAny): string {
    const unwrapped = unwrapSchema(schema);
    const constructorName = unwrapped.constructor.name;

    if (constructorName === 'ZodLiteral') {
        // Try multiple ways to get the value
        const value = (unwrapped as any)._def?.value
            ?? (unwrapped as any).def?.value
            ?? (unwrapped as any).def?.values?.[0]
            ?? (unwrapped as any)._def?.values?.[0];
        return typeof value === 'string' ? `"${value}"` : String(value);
    }
    if (constructorName === 'ZodEnum') {
        const values = (unwrapped as any)._def?.values || [];
        return values.map((v: any) => `"${v}"`).join(' | ');
    }
    if (constructorName === 'ZodString') return 'string';
    if (constructorName === 'ZodNumber') return 'number';
    if (constructorName === 'ZodBoolean') return 'boolean';
    if (constructorName === 'ZodArray') return 'array';
    if (constructorName === 'ZodObject') return 'object';
    if (constructorName === 'ZodRecord') return 'record';
    if (constructorName === 'ZodUnion') {
        const options = (unwrapped as any)._def?.options || [];
        const types = options.map((opt: z.ZodTypeAny) => getTypeName(opt));
        return types.join(' | ');
    }

    return 'unknown';
}

// Function to generate sample data from schema using defaults
function generateSampleFromSchema(schema: z.ZodTypeAny): any {
    const constructorName = schema.constructor.name;

    if (constructorName === 'ZodDefault') {
        const defaultValue = (schema as any)._def?.defaultValue;
        return typeof defaultValue === 'function' ? defaultValue() : defaultValue;
    }

    const unwrapped = unwrapSchema(schema);
    const unwrappedConstructor = unwrapped.constructor.name;

    if (unwrappedConstructor === 'ZodObject') {
        const shape = (unwrapped as z.ZodObject<any>).shape;
        const result: any = {};
        for (const key in shape) {
            result[key] = generateSampleFromSchema(shape[key]);
        }
        return result;
    } else if (unwrappedConstructor === 'ZodString') {
        return '';
    } else if (unwrappedConstructor === 'ZodNumber') {
        return '';
    } else if (unwrappedConstructor === 'ZodBoolean') {
        return '';
    } else if (unwrappedConstructor === 'ZodArray') {
        return [];
    } else if (unwrappedConstructor === 'ZodRecord') {
        return {};
    } else if (unwrappedConstructor === 'ZodUnion') {
        return '';
    }

    return '';
}

function renderUnionField(
    fieldSchema: z.ZodTypeAny,
    key: string,
    spaces: string,
    commentPrefix: string,
    indent: number,
    commentDepth: number
): string {
    const unwrappedField = unwrapSchema(fieldSchema);
    const options = (unwrappedField as any)._def?.options || [];
    const sampleValue = generateSampleFromSchema(fieldSchema);
    let yaml = '';

    // Check if any option is a complex type
    const hasComplexType = options.some((option: z.ZodTypeAny) => {
        const optionConstructor = unwrapSchema(option).constructor.name;
        return optionConstructor === 'ZodObject' || optionConstructor === 'ZodRecord';
    });

    if (hasComplexType) {
        // Has complex types - show full structure
        if (sampleValue !== '') {
            yaml += `${spaces}${commentPrefix}${key}: ${sampleValue}\n`;
        } else {
            yaml += `${spaces}${commentPrefix}${key}:\n`;
        }

        // Show each union option as commented examples
        options.forEach((option: z.ZodTypeAny, idx: number) => {
            const optionConstructor = unwrapSchema(option).constructor.name;
            const optionType = getTypeName(option);

            if (optionConstructor === 'ZodObject' || optionConstructor === 'ZodRecord') {
                yaml += `${spaces}${commentPrefix}## Option ${idx + 1} (${optionType}):\n`;
                yaml += schemaToYamlWithComments(option, indent + 1, commentDepth + 1);
            } else {
                // For simple types in union
                yaml += `${spaces}${commentPrefix}## Option ${idx + 1}: ${optionType}\n`;
            }
        });
    } else {
        // All scalar types - show as pipe-separated list on same line
        const scalarTypes = options.map((option: z.ZodTypeAny) => getTypeName(option)).join(' | ');
        yaml += `${spaces}${commentPrefix}${key}:  # ${scalarTypes}\n`;
    }

    return yaml;
}

function renderArrayElement(elementSchema: z.ZodTypeAny, indent: number, commentDepth: number): string {
    const spaces = '  '.repeat(indent);
    const commentPrefix = '#'.repeat(commentDepth);
    const unwrapped = unwrapSchema(elementSchema);
    const elementConstructor = unwrapped.constructor.name;

    if (elementConstructor === 'ZodObject') {
        let yaml = `\n`;
        const objectYaml = schemaToYamlWithComments(elementSchema, indent + 1, 0);
        // Prefix first line with dash, indent rest
        const lines = objectYaml.split('\n');
        lines.forEach((line, idx) => {
            if (line.trim()) {
                const leadingSpaces = line.length - line.trimStart().length;
                const preservedIndent = ' '.repeat(leadingSpaces);

                if (idx === 0) {
                    yaml += `${spaces}${preservedIndent}${commentPrefix}-\n`;
                    yaml += `${spaces}${preservedIndent}  ${commentPrefix}${line.trimStart()}\n`;
                } else {
                    yaml += `${spaces}${preservedIndent}  ${commentPrefix}${line.trimStart()}\n`;
                }
            }
        });
        return yaml;
    } else if (elementConstructor === 'ZodUnion') {
        // Handle union types in arrays
        const options = (unwrapped as any)._def?.options || [];

        // Check if any option is a complex type
        const hasComplexType = options.some((option: z.ZodTypeAny) => {
            const optionConstructor = unwrapSchema(option).constructor.name;
            return optionConstructor === 'ZodObject' || optionConstructor === 'ZodRecord';
        });

        if (hasComplexType) {
            let yaml = `\n`;
            // Show each union option as a separate array element example
            options.forEach((option: z.ZodTypeAny, idx: number) => {
                const optionConstructor = unwrapSchema(option).constructor.name;
                const optionType = getTypeName(option);

                if (optionConstructor === 'ZodObject' || optionConstructor === 'ZodRecord') {
                    yaml += `${spaces}${commentPrefix}## Option ${idx + 1} (${optionType}):\n`;
                    const objectYaml = schemaToYamlWithComments(option, indent + 1, commentDepth);
                    const lines = objectYaml.split('\n');
                    lines.forEach((line, lineIdx) => {
                        if (line.trim()) {
                            const leadingSpaces = line.length - line.trimStart().length;
                            const preservedIndent = ' '.repeat(leadingSpaces);

                            if (lineIdx === 0) {
                                yaml += `${spaces}${preservedIndent}${commentPrefix}-\n`;
                                yaml += `${spaces}${preservedIndent}  ${commentPrefix}${line.trimStart()}\n`;
                            } else {
                                yaml += `${spaces}${preservedIndent}  ${commentPrefix}${line.trimStart()}\n`;
                            }
                        }
                    });
                } else {
                    // Simple type in union
                    const sampleValue = generateSampleFromSchema(option);
                    yaml += `${spaces}${commentPrefix}## Option ${idx + 1}: ${optionType}\n`;
                    yaml += `${spaces}${commentPrefix}- ${sampleValue || ''}\n`;
                }
            });
            return yaml;
        } else {
            // All scalar types - show as inline array with type info
            const scalarTypes = options.map((option: z.ZodTypeAny) => getTypeName(option)).join(' | ');
            const sampleValue = generateSampleFromSchema(elementSchema);
            return ` [${sampleValue || ''}]  # (${scalarTypes})[]\n`;
        }
    } else {
        // Scalar types use block notation
        const sampleValue = generateSampleFromSchema(elementSchema);
        const typeName = getTypeName(elementSchema);
        return ` [${sampleValue || ''}]  # ${typeName}[]\n`;
    }
}

function schemaToYamlWithComments(schema: z.ZodTypeAny, indent = 0, incomingCommentDepth = 0): string {
    const spaces = '  '.repeat(indent);
    let yaml = '';

    const unwrapped = unwrapSchema(schema);
    const unwrappedConstructor = unwrapped.constructor.name;

    if (unwrappedConstructor === 'ZodObject' || unwrappedConstructor === 'ZodRecord') {
        const shape = unwrappedConstructor === 'ZodObject'
            ? (unwrapped as z.ZodObject<any>).shape
            : { '<NAME>': (unwrapped as any)._def?.valueType };

        for (const key in shape) {
            const fieldSchema = shape[key];
            const description = fieldSchema.description;
            const typeName = getTypeName(fieldSchema);
            const hasDefault = fieldSchema.constructor.name === 'ZodDefault';
            const optional = isOptional(fieldSchema);
            let commentDepth = incomingCommentDepth + (optional ? 1 : 0);

            const commentPrefix = '#'.repeat(commentDepth);

            // Build comment line
            if (description) {
                yaml += `${spaces}${commentPrefix}# ${description}\n`;
            }

            const unwrappedField = unwrapSchema(fieldSchema);
            const fieldConstructor = unwrappedField.constructor.name;

            if (fieldConstructor === 'ZodObject' || fieldConstructor === 'ZodRecord') {
                yaml += `${spaces}${commentPrefix}${key}:\n`;
                yaml += schemaToYamlWithComments(fieldSchema, indent + 1, commentDepth);
            } else if (fieldConstructor === 'ZodArray') {
                // Check if array has minimum length requirement
                const minLength = getArrayMinLength(fieldSchema);
                const arrayCommentDepth = (minLength === undefined || minLength === 0)
                    ? commentDepth + 1
                    : commentDepth;

                // Get the element schema
                const elementSchema = (unwrappedField as any).element || (unwrappedField as any)._def?.type;
                if (elementSchema) {
                    yaml += `${spaces}${commentPrefix}${key}:`;
                    yaml += renderArrayElement(elementSchema, indent, arrayCommentDepth);
                }
            } else if (fieldConstructor === 'ZodUnion') {
                yaml += renderUnionField(fieldSchema, key, spaces, commentPrefix, indent, commentDepth);
            } else {
                const sampleValue = generateSampleFromSchema(fieldSchema);
                if (sampleValue !== '') {
                    yaml += `${spaces}${commentPrefix}${key}: ${sampleValue}\n`;
                } else {
                    yaml += `${spaces}${commentPrefix}${key}:  # ${typeName}\n`;
                }
            }
        }
    }

    return yaml;
}

async function main() {
    console.info(schemaToYamlWithComments(OVERALL_MIGRATION_CONFIG));
    // console.info(schemaToYamlWithComments(PARAMETERIZED_MIGRATION_CONFIG));
}

main();