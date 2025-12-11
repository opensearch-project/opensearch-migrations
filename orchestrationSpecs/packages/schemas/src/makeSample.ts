import { z } from 'zod';
import {OVERALL_MIGRATION_CONFIG} from "./userSchemas";
import {
    ARGO_WORKFLOW_SCHEMA,
    PARAMETERIZED_MIGRATION_CONFIG,
    PARAMETERIZED_MIGRATION_CONFIG_ARRAYS
} from "./argoSchemas";
import {fullUnwrapType, unwrapSchema, ZOD_OPTIONAL_TYPES} from "./schemaUtilities";

// Path context for tracking descent through the schema
export type SchemaPath = string[];

export interface RecordNameMapping {
    pattern: RegExp;
    replacement: string;
}

// Default mappings - customize this array for your needs
const DEFAULT_RECORD_NAME_MAPPINGS: RecordNameMapping[] = [
    // { pattern: /^clusters$/, replacement: '<CLUSTER_NAME>' },
    // { pattern: /^clusters\[\]\.nodes$/, replacement: '<NODE_ID>' },
    // { pattern: /\.env$/, replacement: '<ENV_VAR>' },
];

// Helper function to get record placeholder name based on path
export function getRecordKeyPlaceholder(path: SchemaPath, mappings: RecordNameMapping[] = DEFAULT_RECORD_NAME_MAPPINGS): string {
    const pathStr = path.join('.');

    for (const mapping of mappings) {
        if (mapping.pattern.test(pathStr)) {
            return mapping.replacement;
        }
    }

    // Default fallback - derive from last path segment
    const lastSegment = path[path.length - 1];
    if (lastSegment) {
        const singular = lastSegment.replace(/s$/, '').replace(/ies$/, 'y');
        const snakeCase = singular.replace(/([a-z])([A-Z])/g, '$1_$2').toUpperCase();
        return `${snakeCase}`;
    }

    return '<KEY>';
}

// Context passed through recursive calls
export interface RenderContext {
    path: SchemaPath;
    recordKeyResolver: (path: SchemaPath) => string;
}

function createDefaultContext(): RenderContext {
    return {
        path: [],
        recordKeyResolver: getRecordKeyPlaceholder
    };
}

function extendPath(ctx: RenderContext, segment: string): RenderContext {
    return {
        ...ctx,
        path: [...ctx.path, segment]
    };
}

function extendPathForArray(ctx: RenderContext): RenderContext {
    return {
        ...ctx,
        path: [...ctx.path.slice(0, -1), `${ctx.path[ctx.path.length - 1]}[]`]
    };
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

// Escape a string for JSON output
function jsonEscape(str: string): string {
    return JSON.stringify(str);
}

// Format a value for JSON output
function formatJsonValue(value: any): string {
    if (value === '') return '""';
    if (value === null) return 'null';
    if (typeof value === 'string') return jsonEscape(value);
    if (typeof value === 'number') return String(value);
    if (typeof value === 'boolean') return String(value);
    if (Array.isArray(value)) return '[]';
    if (typeof value === 'object') return '{}';
    return '""';
}

function renderUnionField(
    fieldSchema: z.ZodTypeAny,
    key: string,
    indent: Readonly<Indentation>,
    isLast: boolean,
    ctx: RenderContext
): string {
    const unwrappedField = unwrapSchema(fieldSchema);
    const options = (unwrappedField as any)._def?.options || [];
    const sampleValue = generateSampleFromSchema(fieldSchema);
    let json = '';
    const comma = isLast ? '' : ',';

    // Check if any option is a complex type
    const hasComplexType = options.some((option: z.ZodTypeAny) => {
        const optionConstructor = unwrapSchema(option).constructor.name;
        return optionConstructor === 'ZodObject' || optionConstructor === 'ZodRecord';
    });

    if (hasComplexType) {
        // Has complex types - show full structure
        if (sampleValue !== '' && typeof sampleValue !== 'object') {
            json += `${makeHeader(indent)}${jsonEscape(key)}: ${formatJsonValue(sampleValue)}${comma}\n`;
        } else {
            json += `${makeHeader(indent)}${jsonEscape(key)}: {}${comma}\n`;
        }

        // Show each union option as commented examples
        options.forEach((option: z.ZodTypeAny, idx: number) => {
            const optionConstructor = unwrapSchema(option).constructor.name;
            const optionType = getTypeName(option);

            if (optionConstructor === 'ZodObject' || optionConstructor === 'ZodRecord') {
                const nextIndent = indent.incrementComment();
                json += `${makeHeader(nextIndent)}## Option ${idx + 1} (${optionType}):\n`;
                json += schemaToJsonWithComments(option, nextIndent, true, ctx);
            } else {
                // For simple types in union
                json += `${makeHeader(indent)}## Option ${idx + 1}: ${optionType}\n`;
            }
        });
    } else {
        // All scalar types - show as pipe-separated list in comment
        const scalarTypes = options.map((option: z.ZodTypeAny) => getTypeName(option)).join(' | ');
        json += `${makeHeader(indent)}${jsonEscape(key)}: ""${comma}  # ${scalarTypes}\n`;
    }

    return json;
}

function renderArrayElement(
    elementSchema: z.ZodTypeAny,
    indent: Indentation,
    ctx: RenderContext,
    comma: string): string
{
    const unwrapped = unwrapSchema(elementSchema);
    const elementConstructor = unwrapped.constructor.name;
    const arrayCtx = extendPathForArray(ctx);

    if (elementConstructor === 'ZodObject') {
        let json = `\n${makeHeader(indent)}[\n`;
        json += schemaToJsonWithComments(elementSchema, indent, true, arrayCtx);
        json += `${makeHeader(indent)}]${comma}`;
        return json;
    } else if (elementConstructor === 'ZodUnion') {
        // Handle union types in arrays
        const options = (unwrapped as any)._def?.options || [];

        // Check if any option is a complex type
        const hasComplexType = options.some((option: z.ZodTypeAny) => {
            const optionConstructor = unwrapSchema(option).constructor.name;
            return optionConstructor === 'ZodObject' || optionConstructor === 'ZodRecord';
        });

        if (hasComplexType) {
            let json = ` []${comma}  # array of union types\n`;
            // Show each union option as a commented example
            options.forEach((option: z.ZodTypeAny, idx: number) => {
                const optionConstructor = unwrapSchema(option).constructor.name;
                const optionType = getTypeName(option);

                if (optionConstructor === 'ZodObject' || optionConstructor === 'ZodRecord') {
                    json += `${makeHeader(indent)}## Option ${idx + 1} (${optionType}):\n`;
                    json += schemaToJsonWithComments(option, indent, true, arrayCtx);
                } else {
                    // Simple type in union
                    json += `${makeHeader(indent)}## Option ${idx + 1}: ${optionType}\n`;
                }
            });
            return json;
        } else {
            // All scalar types - show as inline array with type info
            const scalarTypes = options.map((option: z.ZodTypeAny) => getTypeName(option)).join(' | ');
            return ` []${comma}  # (${scalarTypes})[]\n`;
        }
    } else {
        // Scalar types use empty array notation
        const typeName = getTypeName(elementSchema);
        return ` []${comma}  # ${typeName}[]\n`;
    }
}

function schemaToJsonWithComments(
    schema: z.ZodTypeAny,
    incomingIndent: Readonly<Indentation>,
    wrapInBraces: boolean = false,
    ctx: RenderContext = createDefaultContext()
): string {
    let json = '';

    const unwrapped = unwrapSchema(schema);
    const unwrappedConstructor = unwrapped.constructor.name;
    const currentIndent = incomingIndent.incrementSpaces();

    if (unwrappedConstructor === 'ZodObject' || unwrappedConstructor === 'ZodRecord') {
        const isRecord = unwrappedConstructor === 'ZodRecord';

        const shape = isRecord
            ? { [ctx.recordKeyResolver(ctx.path)]: (unwrapped as any)._def?.valueType }
            : (unwrapped as z.ZodObject<any>).shape;

        const keys = Object.keys(shape);

        if (wrapInBraces) {
            json += `${makeHeader(incomingIndent.incrementSpaces())}{\n`;
        }

        keys.forEach((key, index) => {
            const fieldSchema = shape[key];
            const fieldCtx = extendPath(ctx, key);
            const description = fullUnwrapType(fieldSchema).description;
            const typeName = getTypeName(fieldSchema);
            const optional = isOptional(fieldSchema);
            const nextIndent = currentIndent.incrementComment(optional);
            const isLast = index === keys.length - 1;
            const comma = isLast ? '' : ',';

            if (description) { // Build comment line
                json += `${makeHeader(nextIndent)}# ${description}\n`;
            }

            const unwrappedField = unwrapSchema(fieldSchema);
            const fieldConstructor = unwrappedField.constructor.name;

            if (fieldConstructor === 'ZodObject' || fieldConstructor === 'ZodRecord') {
                json += `${makeHeader(nextIndent)}${jsonEscape(key)}: {\n`;
                json += schemaToJsonWithComments(fieldSchema, nextIndent, false, fieldCtx);
                json += `${makeHeader(nextIndent)}}${comma}\n`;
            } else if (fieldConstructor === 'ZodArray') {
                const elementSchema = (unwrappedField as any).element || (unwrappedField as any)._def?.type;
                if (elementSchema) {
                    const unwrappedElement = unwrapSchema(elementSchema);
                    const elementConstructor = unwrappedElement.constructor.name;

                    if (elementConstructor === 'ZodObject') {
                        json += `${makeHeader(nextIndent)}${jsonEscape(key)}: [\n`;
                        json += schemaToJsonWithComments(elementSchema, nextIndent, true, fieldCtx);
                        json += `${makeHeader(nextIndent)}]${comma}\n`;
                    } else {
                        json += `${makeHeader(nextIndent)}${jsonEscape(key)}:`;
                        json += renderArrayElement(elementSchema, nextIndent, fieldCtx, comma);
                    }
                }
            } else if (fieldConstructor === 'ZodUnion') {
                json += renderUnionField(fieldSchema, key, nextIndent, isLast, fieldCtx);
            } else {
                const sampleValue = generateSampleFromSchema(fieldSchema);
                if (sampleValue !== '' && sampleValue !== undefined) {
                    json += `${makeHeader(nextIndent)}${jsonEscape(key)}: ${formatJsonValue(sampleValue)}${comma}\n`;
                } else {
                    json += `${makeHeader(nextIndent)}${jsonEscape(key)}: ""${comma}  # ${typeName}\n`;
                }
            }
        });

        if (wrapInBraces) {
            json += `${makeHeader(incomingIndent.incrementSpaces())}}\n`;
        }
    }

    return json;
}

function schemaToJsonWithCommentsTop(
    schema: z.ZodTypeAny,
    recordKeyResolver?: (path: SchemaPath) => string
): string {
    const indent = createIndentation(0, -1 * INDENT_FACTOR);
    const ctx: RenderContext = {
        path: [],
        recordKeyResolver: recordKeyResolver ?? getRecordKeyPlaceholder
    };
    let json = '{\n';
    json += schemaToJsonWithComments(schema, indent, false, ctx);
    json += '}\n';
    return json;
}

export async function main() {
    // Example with custom record key resolver using regex matching
    const customResolver = (path: SchemaPath): string => {
        const pathStr = path.join('.');

        // Add your custom mappings here - checked in order
        const mappings: RecordNameMapping[] = [
            // { pattern: /^clusters$/, replacement: '<CLUSTER_NAME>' },
            // { pattern: /\.env$/, replacement: '<ENV_VAR>' },
            // { pattern: /\.labels$/, replacement: '<LABEL_KEY>' },
        ];

        for (const mapping of mappings) {
            if (mapping.pattern.test(pathStr)) {
                return mapping.replacement;
            }
        }

        // Fall back to default behavior
        return getRecordKeyPlaceholder(path);
    };

    console.info(schemaToJsonWithCommentsTop(OVERALL_MIGRATION_CONFIG,
        (path: SchemaPath) => {
            switch (path[path.length - 1]) {
                case "sourceClusters": return "source";
                case "targetClusters": return "target";
                default: return getRecordKeyPlaceholder(path);
            }
        }));
}

// Run if executed directly
if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch(error => {
        console.error('Fatal error:', error);
        process.exit(2);
    });
}