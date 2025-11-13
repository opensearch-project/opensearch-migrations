import { z } from 'zod';
import {parse} from "yaml";
import * as fs from "node:fs";
import {OVERALL_MIGRATION_CONFIG} from "@opensearch-migrations/schemas";  // <-- Replace with your schema import

const COMMAND_LINE_HELP_MESSAGE = `Usage: constrain-user-schema [input-file|-]

Return a new json schema that constrains all values other than 'skipApproval' fields to the
values that are already present in the input.  This can be used to lock a schema down so 
that only the operational configuration is mutable while restricting the user from making 
changes that would change the setup/outcome of a migration.
`

type MutableFieldMatcher = (path: string[], key: string) => boolean;

function lockZodSchema<T extends z.ZodTypeAny>(
    schema: T,
    data: unknown,
    isMutable: MutableFieldMatcher = (path, key) => key === 'skipApproval'
): z.ZodTypeAny {
    return lockZodSchemaInternal(schema, data, [], isMutable);
}

function lockZodSchemaInternal(
    schema: z.ZodTypeAny,
    data: unknown,
    path: string[],
    isMutable: MutableFieldMatcher
): z.ZodTypeAny {
    const def = (schema as any)._def;

    // Unwrap optionals and nullables
    if (schema instanceof z.ZodOptional) {
        const unwrapped = (schema as any)._def.innerType;
        const innerLocked = lockZodSchemaInternal(unwrapped, data, path, isMutable);
        return innerLocked.optional();
    }

    if (schema instanceof z.ZodNullable) {
        const unwrapped = (schema as any)._def.innerType;
        const innerLocked = lockZodSchemaInternal(unwrapped, data, path, isMutable);
        return innerLocked.nullable();
    }

    // Unwrap defaults
    if (schema instanceof z.ZodDefault) {
        const unwrapped = (schema as any)._def.innerType;
        return lockZodSchemaInternal(unwrapped, data, path, isMutable);
    }

    // Unwrap effects (refinements, transforms, etc)
    if (def.typeName === 'ZodEffects') {
        const unwrapped = def.schema;
        return lockZodSchemaInternal(unwrapped, data, path, isMutable);
    }

    // Handle objects
    if (schema instanceof z.ZodObject) {
        const shape = (schema as any)._def.shape();
        const newShape: Record<string, z.ZodTypeAny> = {};

        for (const [key, fieldSchema] of Object.entries(shape)) {
            const fieldPath = [...path, key];
            const fieldData = (data as any)?.[key];

            if (isMutable(fieldPath, key)) {
                // Keep this field mutable - preserve original schema
                newShape[key] = fieldSchema as z.ZodTypeAny;
            } else {
                // Lock this field
                newShape[key] = lockZodSchemaInternal(
                    fieldSchema as z.ZodTypeAny,
                    fieldData,
                    fieldPath,
                    isMutable
                );
            }
        }

        return z.object(newShape);
    }

    // Handle arrays
    if (schema instanceof z.ZodArray) {
        if (!Array.isArray(data)) {
            return z.literal(data as any);
        }

        const elementSchema = (schema as any)._def.type;

        // Lock each element in the array
        const lockedElements = (data as any[]).map((item, index) =>
            lockZodSchemaInternal(
                elementSchema,
                item,
                [...path, String(index)],
                isMutable
            )
        );

        // Create a tuple schema with exact length
        return z.tuple(lockedElements as any);
    }

    // Handle unions
    if (schema instanceof z.ZodUnion) {
        const options = (schema as any)._def.options as z.ZodTypeAny[];

        for (const option of options) {
            const result = option.safeParse(data);
            if (result.success) {
                return lockZodSchemaInternal(option, data, path, isMutable);
            }
        }

        // If no match, fall through to literal
    }

    // Handle discriminated unions
    if (def.typeName === 'ZodDiscriminatedUnion') {
        const optionsMap = def.optionsMap as Map<any, z.ZodTypeAny>;

        for (const option of optionsMap.values()) {
            const result = option.safeParse(data);
            if (result.success) {
                return lockZodSchemaInternal(option, data, path, isMutable);
            }
        }
    }

    // Handle records/maps
    if (schema instanceof z.ZodRecord) {
        if (typeof data !== 'object' || data === null) {
            return z.literal(data as any);
        }

        const valueSchema = (schema as any)._def.valueType;
        const newShape: Record<string, z.ZodTypeAny> = {};

        for (const [key, value] of Object.entries(data)) {
            const fieldPath = [...path, key];
            if (isMutable(fieldPath, key)) {
                newShape[key] = valueSchema;
            } else {
                newShape[key] = lockZodSchemaInternal(
                    valueSchema,
                    value,
                    fieldPath,
                    isMutable
                );
            }
        }
        return z.object(newShape);
    }

    // For all primitive types and anything else, create a literal
    return z.literal(data as any);
}

async function readStdin(): Promise<string> {
    return new Promise((resolve, reject) => {
        const chunks: Buffer[] = [];

        process.stdin.on('data', (chunk) => {
            chunks.push(chunk);
        });

        process.stdin.on('end', () => {
            resolve(Buffer.concat(chunks).toString('utf-8'));
        });

        process.stdin.on('error', (error) => {
            reject(error);
        });
    });
}

async function main() {
    const args = process.argv.slice(2);

    let yamlContent: string;

    if (args.length === 0) {
        console.error("Error: no args provided.");
        console.error(COMMAND_LINE_HELP_MESSAGE);
        process.exit(2);
    }

    if (args[0] === '-') {
        try {
            yamlContent = await readStdin();
        } catch (error) {
            console.error("Error reading from stdin:', error");
            console.error(COMMAND_LINE_HELP_MESSAGE);
            process.exit(3);
        }
    } else {
        const yamlPath = args[0];
        try {
            yamlContent = fs.readFileSync(yamlPath, 'utf-8');
        } catch (error) {
            console.error(`Error reading file ${yamlPath}:`, error);
            console.error(COMMAND_LINE_HELP_MESSAGE);
            process.exit(4);
        }
    }

    let data: any;
    try {
        data = parse(yamlContent);
    } catch (error) {
        console.error('Error parsing YAML:', error);
        console.error(COMMAND_LINE_HELP_MESSAGE);
        process.exit(4);
    }

    // Validate the data against the original schema
    const validationResult = OVERALL_MIGRATION_CONFIG.safeParse(data);
    if (!validationResult.success) {
        console.error('Error: YAML does not match the schema:');
        console.error(JSON.stringify(validationResult.error.format(), null, 2));
        process.exit(5);
    }

    // Create the locked schema
    const lockedSchema = lockZodSchema(OVERALL_MIGRATION_CONFIG, data);

    console.log('Locked schema created successfully!');
    process.stdout.write(JSON.stringify(lockedSchema, null, 2));
}

if (require.main === module) {
    main().catch((error) => {
        console.error('Unhandled error:', error);
        process.exit(1);
    });
}

export { lockZodSchema };