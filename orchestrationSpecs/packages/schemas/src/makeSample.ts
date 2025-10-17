import { z } from 'zod';
import {OVERALL_MIGRATION_CONFIG} from "./userSchemas";

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
    const checks = (unwrapped as any)._def?.checks || [];
    const minCheck = checks.find((c: any) => c.kind === 'min');
    return minCheck?.value;
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

// Render array element for bracket notation
function renderArrayElementBracket(elementSchema: z.ZodTypeAny, indent: number, commentDepth: number): string {
    const spaces = '  '.repeat(indent);
    const commentPrefix = '#'.repeat(commentDepth);
    const unwrapped = unwrapSchema(elementSchema);
    const elementConstructor = unwrapped.constructor.name;

    if (elementConstructor === 'ZodObject') {
        let yaml = `${spaces}${commentPrefix}[\n`;
        yaml += schemaToYamlWithComments(elementSchema, indent + 1, commentDepth);
        yaml += `${spaces}${commentPrefix}]\n`;
        return yaml;
    } else {
        const sampleValue = generateSampleFromSchema(elementSchema);
        return `${spaces}${commentPrefix}[${sampleValue || ''}]\n`;
    }
}

// Function to build YAML with comments
function schemaToYamlWithComments(schema: z.ZodTypeAny, indent = 0, commentDepth = 0): string {
    const spaces = '  '.repeat(indent);
    const commentPrefix = '#'.repeat(commentDepth);
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
            const optional = isOptional(fieldSchema);
            const hasDefault = fieldSchema.constructor.name === 'ZodDefault';

            // Build comment line
            if (description) {
                let comment = `${commentPrefix}# `;
                comment += ` ${description}`;
                yaml += `${spaces}${comment}\n`;
            }

            const unwrappedField = unwrapSchema(fieldSchema);
            const fieldConstructor = unwrappedField.constructor.name;

            // Increase comment depth if this field is optional
            const newCommentDepth = optional ? commentDepth + 1 : commentDepth;

            if (fieldConstructor === 'ZodObject' || fieldConstructor === 'ZodRecord') {
                yaml += `${spaces}${commentPrefix}${key}:\n`;
                yaml += schemaToYamlWithComments(fieldSchema, indent + 1, newCommentDepth);
            } else if (fieldConstructor === 'ZodArray') {
                // Check if array has minimum length requirement
                const minLength = getArrayMinLength(fieldSchema);
                const arrayCommentDepth = (minLength === undefined || minLength === 0)
                    ? newCommentDepth + 1
                    : newCommentDepth;

                // Get the element schema
                const elementSchema = (unwrappedField as any).element || (unwrappedField as any)._def?.type;
                if (elementSchema) {
                    yaml += `${spaces}${commentPrefix}${key}: `;
                    const body = renderArrayElementBracket(elementSchema, indent, arrayCommentDepth);
                    if (body.trimStart().replace(/^#*/g, '').trimEnd() !== '[]') {
                        yaml += '\n';
                        yaml += body;
                    } else {
                        yaml += '[]\n';
                    }

                }
            } else if (fieldConstructor === 'ZodUnion') {
                // For unions, show options as comment
                const options = (unwrappedField as any)._def?.options || [];
                const sampleValue = generateSampleFromSchema(fieldSchema);

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
                            yaml += `${spaces}${commentPrefix}  # Option ${idx + 1} (${optionType}):\n`;
                            yaml += schemaToYamlWithComments(option, indent + 1, newCommentDepth + 1);
                        } else {
                            // For simple types in union
                            yaml += `${spaces}${commentPrefix}  # Option ${idx + 1}: ${optionType}\n`;
                        }
                    });
                } else {
                    // All scalar types - show as pipe-separated list on same line
                    const scalarTypes = options.map((option: z.ZodTypeAny) => getTypeName(option)).join(' | ');
                    yaml += `${spaces}${commentPrefix}${key}: # ${scalarTypes}\n`;
                }
            } else {
                const sampleValue = generateSampleFromSchema(fieldSchema);
                if (sampleValue !== '') {
                    yaml += `${spaces}${commentPrefix}${key}: ${sampleValue}\n`;
                } else {
                    yaml += `${spaces}${commentPrefix}${key}: ${typeName}\n`;
                }
            }
        }
    }

    return yaml;
}

async function main() {
    console.info(schemaToYamlWithComments(OVERALL_MIGRATION_CONFIG));
}

main();