import { z, ZodTypeAny } from 'zod';
import {parse} from "yaml";
import * as fs from "node:fs";
import {OVERALL_MIGRATION_CONFIG, zodSchemaToJsonSchema} from "@opensearch-migrations/schemas";
import {Console} from "console";
import {parseUserConfig} from "./userConfigReader";

global.console = new Console({
    stdout: process.stderr,
    stderr: process.stderr
});

const COMMAND_LINE_HELP_MESSAGE = `Usage: constrain-user-schema [input-file|-]

Return a new json schema that constrains all values other than 'skipApproval' fields to the
values that are already present in the input.  This can be used to lock a schema down so 
that only the operational configuration is mutable while restricting the user from making 
changes that would change the setup/outcome of a migration.
`

type PathSeg = string | "*";
type Path = PathSeg[];

const SKIP_FIELD_REGEX = /^skip(?:.*)?Approvals?$/;

function isSkipKeyName(key: string): boolean {
    return SKIP_FIELD_REGEX.test(key);
}

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

function unwrapSchema(schema: unknown): unknown {
    const s = schema as any;
    const def = s?._def;

    if (s instanceof z.ZodOptional ||
        s instanceof z.ZodNullable ||
        s instanceof z.ZodDefault) {
        return def.innerType;
    }

    return schema;
}

function findSkipApprovalPatterns(
    schema: unknown,
    path: Path = []
): Path[] {
    const s0 = schema as any;
    const s = unwrapSchema(s0) as any;  // <-- only unwrap known wrappers
    const patterns: Path[] = [];

    if (s instanceof z.ZodObject) {
        const shape = s.shape;  // getter, fine
        for (const [key, valueSchema] of Object.entries(shape)) {
            const nextPath = [...path, key];

            if (isSkipKeyName(key)) {
                patterns.push(nextPath);
            }

            patterns.push(...findSkipApprovalPatterns(valueSchema, nextPath));
        }
        return patterns;
    } else if (s instanceof z.ZodArray) {
        const elem = s.element;
        const nextPath = [...path, "*"]; // wildcard index
        patterns.push(...findSkipApprovalPatterns(elem, nextPath));
        return patterns;
    } else if (s instanceof z.ZodRecord) {
        const valueType = s._def.valueType;
        const nextPath = [...path, "*"]; // wildcard key
        patterns.push(...findSkipApprovalPatterns(valueType, nextPath));
        return patterns;
    }

    return patterns;
}

function getSkipKeysForPath(path: Path, skipPatterns: Path[]): string[] {
    const keys = new Set<string>();

    for (const pattern of skipPatterns) {
        // We’re at path `path`, skip fields will be one segment deeper
        if (pattern.length !== path.length + 1) continue;

        const keySeg = pattern[pattern.length - 1];
        if (keySeg === "*") {
            continue; // skip wild wildcard at the end (shouldn't be skip anyway)
        }

        const prefixPattern = pattern.slice(0, -1); // everything but the last segment
        if (pathMatches(prefixPattern, path)) {
            keys.add(keySeg as string);
        }
    }

    return [...keys];
}

function isValueType(value: unknown) {
    const vt = typeof value;
    return vt === "string" || vt === "number" || vt === "boolean"
}

function buildLockedSchemaFromValue(
    value: unknown,
    path: Path,
    skipPatterns: Path[]
): ZodTypeAny {
    if (isSkipApprovalPath(path, skipPatterns)) {
        return z.boolean().optional(); // If this was a skip field, always allow mutable boolean
    } else if (value === null) {
        return z.literal(null);
    } else if (isValueType(value)) {
        return z.literal(value as any);
    } else if (Array.isArray(value)) {
        const itemSchemas = value.map((item, index) =>
            buildLockedSchemaFromValue(item, [...path, String(index)], skipPatterns)
        );

        if (itemSchemas.length === 0) {
            return z.tuple([] as []);
        } else {
            return z.tuple(itemSchemas as [ZodTypeAny, ...ZodTypeAny[]]);
        }
    } else if (typeof value === "object") {
        const obj = value as Record<string, unknown>;
        const shape: Record<string, ZodTypeAny> = {};

        for (const [key, v] of Object.entries(obj)) {
            const childPath: Path = [...path, key];
            shape[key] = buildLockedSchemaFromValue(v, childPath, skipPatterns);
        }

        // Add any skip fields at this level if they haven't already been added
        const skipKeysHere = getSkipKeysForPath(path, skipPatterns);
        for (const skipKey of skipKeysHere) {
            if (!(skipKey in shape)) {
                shape[skipKey] = z.boolean().optional();
            }
        }

        return z.object(shape).strict(); // no extra NON-skip keys allowed
    } else {
        return z.any();
    }
}


export function makeLockedConfigSchema<TSchema extends ZodTypeAny>(
    data: unknown,
    baseSchema: TSchema
): ZodTypeAny {
    const skipPatterns = findSkipApprovalPatterns(baseSchema);
    return buildLockedSchemaFromValue(data, [], skipPatterns);
}

export async function main() {
    const args = process.argv.slice(2);

    if (args.length === 0) {
        console.error("Error: no args provided.");
        console.error(COMMAND_LINE_HELP_MESSAGE);
        process.exit(2);
    }

    let data
    try {
        data = await parseUserConfig(args[0]);
    } catch (error) {
        console.error('Error loading YAML:', error);
        console.error(COMMAND_LINE_HELP_MESSAGE);
        process.exit(3);
    }

    // Create the locked schema
    const lockedSchema = makeLockedConfigSchema(data, OVERALL_MIGRATION_CONFIG);

    console.log('Locked schema created successfully!');
    process.stdout.write(JSON.stringify(zodSchemaToJsonSchema(lockedSchema as any), null, 2));
}

if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch((error) => {
        console.error('Unhandled error:', error);
        process.exit(1);
    });
}
