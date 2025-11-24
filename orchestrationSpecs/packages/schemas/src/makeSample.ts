import { z } from 'zod';
import {OVERALL_MIGRATION_CONFIG} from "./userSchemas";
import {
    ARGO_WORKFLOW_SCHEMA,
    PARAMETERIZED_MIGRATION_CONFIG,
    PARAMETERIZED_MIGRATION_CONFIG_ARRAYS
} from "./argoSchemas";
import {fullUnwrapType, unwrapSchema, ZOD_OPTIONAL_TYPES} from "./schemaUtilities";

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
    schema = unwrapSchema(schema, ZOD_OPTIONAL_TYPES);
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

const INDENT_FACTOR = 4;
const COMMENT_FACTOR = 1;

function renderUnionField(
    fieldSchema: z.ZodTypeAny,
    key: string,
    indent: Readonly<Indentation>
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
            yaml += `${makeHeader(indent)}${key}: ${sampleValue}\n`;
        } else {
            yaml += `${makeHeader(indent)}${key}:\n`;
        }

        // Show each union option as commented examples
        options.forEach((option: z.ZodTypeAny, idx: number) => {
            const optionConstructor = unwrapSchema(option).constructor.name;
            const optionType = getTypeName(option);

            if (optionConstructor === 'ZodObject' || optionConstructor === 'ZodRecord') {
                const nextIndent = indent.incrementComment();
                yaml += `${makeHeader(nextIndent)}## Option ${idx + 1} (${optionType}):\n`;
                yaml += schemaToYamlWithComments(option, nextIndent);
            } else {
                // For simple types in union
                yaml += `${makeHeader(indent)}## Option ${idx + 1}: ${optionType}\n`;
            }
        });
    } else {
        // All scalar types - show as pipe-separated list on same line
        const scalarTypes = options.map((option: z.ZodTypeAny) => getTypeName(option)).join(' | ');
        yaml += `${makeHeader(indent)}${key}:  # ${scalarTypes}\n`;
    }

    return yaml;
}

export const createIndentation = (comment: number, spaces: number) => {
    const rval = {
        comment,
        spaces,
        incrementComment(doShift: boolean = true) {
            return createIndentation(comment + (doShift ? COMMENT_FACTOR : 0), spaces);
        },
        incrementSpaces(doShift: boolean = true) {
            return createIndentation(comment, spaces + (doShift ? INDENT_FACTOR : 0));
        }
    };
    return rval as Readonly<typeof rval>;
};

export type Indentation = ReturnType<typeof createIndentation>;

function makeHeader(i: Indentation) {
    return '#'.repeat(i.comment) + ' '.repeat(i.spaces);
}

function renderArrayElement(elementSchema: z.ZodTypeAny, indent: Indentation): string {
    const unwrapped = unwrapSchema(elementSchema);
    const elementConstructor = unwrapped.constructor.name;

    if (elementConstructor === 'ZodObject') {
        let yaml = `\n${makeHeader(indent)}-\n`;
        return yaml + schemaToYamlWithComments(elementSchema, indent);
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
                    yaml += `${makeHeader(indent)}## Option ${idx + 1} (${optionType}):\n`;
                    yaml += schemaToYamlWithComments(option, indent);
                } else {
                    // Simple type in union
                    const sampleValue = generateSampleFromSchema(option);
                    yaml += `${makeHeader(indent)}## Option ${idx + 1}: ${optionType}\n`;
                    yaml += `${makeHeader(indent)}- ${sampleValue || ''}\n`;
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

function schemaToYamlWithComments(schema: z.ZodTypeAny, incomingIndent: Readonly<Indentation>): string {
    let yaml = '';

    const unwrapped = unwrapSchema(schema);
    const unwrappedConstructor = unwrapped.constructor.name;
    const currentIndent = incomingIndent.incrementSpaces();

    if (unwrappedConstructor === 'ZodObject' || unwrappedConstructor === 'ZodRecord') {
        const shape = unwrappedConstructor === 'ZodObject'
            ? (unwrapped as z.ZodObject<any>).shape
            : { '<NAME>': (unwrapped as any)._def?.valueType };

        for (const key in shape) {
            const fieldSchema = shape[key];
            const description = fullUnwrapType(fieldSchema).description;
            const typeName = getTypeName(fieldSchema);
            const optional = isOptional(fieldSchema);
            const nextIndent = currentIndent.incrementComment(optional);

            if (description) { // Build comment line
                yaml += `${makeHeader(nextIndent)}# ${description}\n`;
            }

            const unwrappedField = unwrapSchema(fieldSchema);
            const fieldConstructor = unwrappedField.constructor.name;

            if (fieldConstructor === 'ZodObject' || fieldConstructor === 'ZodRecord') {
                yaml += `${makeHeader(nextIndent)}${key}:\n`;
                yaml += schemaToYamlWithComments(fieldSchema, nextIndent);
            } else if (fieldConstructor === 'ZodArray') {
                const elementSchema = (unwrappedField as any).element || (unwrappedField as any)._def?.type;
                if (elementSchema) {
                    yaml += `${makeHeader(nextIndent)}${key}:`;
                    yaml += renderArrayElement(elementSchema, nextIndent);
                }
            } else if (fieldConstructor === 'ZodUnion') {
                yaml += renderUnionField(fieldSchema, key, nextIndent);
            } else {
                const sampleValue = generateSampleFromSchema(fieldSchema);
                if (sampleValue !== '') {
                    yaml += `${makeHeader(nextIndent)}${key}: ${sampleValue}\n`;
                } else {
                    yaml += `${makeHeader(nextIndent)}${key}:  # ${typeName}\n`;
                }
            }
        }
    }

    return yaml;
}

function schemaToYamlWithCommentsTop(schema: z.ZodTypeAny): string {
    return schemaToYamlWithComments(schema, createIndentation(0, -1*INDENT_FACTOR));
}

export async function main() {
    console.info(schemaToYamlWithCommentsTop(OVERALL_MIGRATION_CONFIG));
}

// Run if executed directly
if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch(error => {
        console.error('Fatal error:', error);
        process.exit(2);
    });
}
