import { z, ZodTypeAny } from 'zod';
import {parse} from "yaml";
import * as fs from "node:fs";
import {OVERALL_MIGRATION_CONFIG, zodSchemaToJsonSchema} from "@opensearch-migrations/schemas";

const COMMAND_LINE_HELP_MESSAGE = `Usage: constrain-user-schema [input-file|-]

Return a new json schema that constrains all values other than 'skipApproval' fields to the
values that are already present in the input.  This can be used to lock a schema down so 
that only the operational configuration is mutable while restricting the user from making 
changes that would change the setup/outcome of a migration.
`

type PathSeg = string | "*";
type Path = PathSeg[];

/**
 * Does a pattern path (with "*" wildcards) match a concrete path?
 * e.g. ["steps", "*", "skipApproval"] matches ["steps", "step1", "skipApproval"]
 */
function pathMatches(pattern: Path, actual: Path): boolean {
    if (pattern.length !== actual.length) return false;
    for (let i = 0; i < pattern.length; i++) {
        const p = pattern[i];
        const a = actual[i];
        if (p !== "*" && p !== a) return false;
    }
    return true;
}

function isSkipApprovalPath(path: Path, skipPatterns: Path[]): boolean {
    return skipPatterns.some((p) => pathMatches(p, path));
}

function findSkipApprovalPatterns(
    schema: unknown,
    path: Path = []
): Path[] {
    const s = schema as any;
    const patterns: Path[] = [];

    // Very lightweight "unwrap" of things like optional(), default(), effects(), branded()
    const def: any = s?._def;
    if (def?.innerType || def?.schema || def?.type) {
        const inner = def.innerType ?? def.schema ?? def.type;
        return findSkipApprovalPatterns(inner, path);
    }

    // ZodObject
    if (s instanceof z.ZodObject) {
        const shape = s.shape;
        for (const [key, valueSchema] of Object.entries(shape)) {
            const nextPath = [...path, key];

            if (key === "skipApproval") {
                patterns.push(nextPath);
            }

            patterns.push(...findSkipApprovalPatterns(valueSchema, nextPath));
        }
        return patterns;
    }

    // ZodArray
    if (s instanceof z.ZodArray) {
        const elem = s.element;
        const nextPath = [...path, "*"]; // wildcard index
        patterns.push(...findSkipApprovalPatterns(elem, nextPath));
        return patterns;
    }

    // ZodRecord
    if (s instanceof z.ZodRecord) {
        const valueType = s._def.valueType;
        const nextPath = [...path, "*"]; // wildcard key
        patterns.push(...findSkipApprovalPatterns(valueType, nextPath));
        return patterns;
    }

    // Other types: nothing to do
    return patterns;
}

function buildLockedSchemaFromValue(
    value: unknown,
    path: Path,
    skipPatterns: Path[]
): ZodTypeAny {
    // If this path is exactly a skipApproval field, allow any boolean
    if (isSkipApprovalPath(path, skipPatterns)) {
        return z.boolean();
    }

    // null
    if (value === null) {
        return z.literal(null);
    }

    // primitives
    const vt = typeof value;
    if (vt === "string" || vt === "number" || vt === "boolean") {
        return z.literal(value as any);
    }

    // array
    if (Array.isArray(value)) {
        const itemSchemas = value.map((item, index) =>
            buildLockedSchemaFromValue(item, [...path, String(index)], skipPatterns)
        );

        if (itemSchemas.length === 0) {
            // empty tuple schema
            return z.tuple([] as []);
        } else {
            // tuple with fixed items
            return z.tuple(itemSchemas as [ZodTypeAny, ...ZodTypeAny[]]);
        }
    }

    // object
    if (vt === "object") {
        const obj = value as Record<string, unknown>;
        const shape: Record<string, ZodTypeAny> = {};

        for (const [key, v] of Object.entries(obj)) {
            const childPath: Path = [...path, key];
            shape[key] = buildLockedSchemaFromValue(v, childPath, skipPatterns);
        }

        return z.object(shape).strict(); // no extra keys allowed
    }

    // fallback – you can refine as needed
    return z.any();
}

export function makeLockedConfigSchema<TSchema extends ZodTypeAny>(
    data: unknown,
    baseSchema: TSchema
): ZodTypeAny {
    // 1. Validate the data with the original schema
    const parsed = baseSchema.parse(data);

    // 2. Discover where "skipApproval" lives in the schema
    const skipPatterns = findSkipApprovalPatterns(baseSchema);

    // 3. Build the literal-locked schema from the parsed data,
    //    only allowing booleans at skipApproval locations
    return buildLockedSchemaFromValue(parsed, [], skipPatterns);
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
    const lockedSchema = makeLockedConfigSchema(data, OVERALL_MIGRATION_CONFIG);

    console.log('Locked schema created successfully!');
    process.stdout.write(JSON.stringify(zodSchemaToJsonSchema(lockedSchema as any), null, 2));
}

if (require.main === module) {
    main().catch((error) => {
        console.error('Unhandled error:', error);
        process.exit(1);
    });
}
