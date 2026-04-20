import {extendZodWithOpenApi, OpenApiGeneratorV3, OpenAPIRegistry} from "@asteasolutions/zod-to-openapi";
import {z} from "zod";
import {FieldMeta} from "./userSchemas";

extendZodWithOpenApi(z);

/** Unwrap Zod wrappers (optional, default, etc.) to reach the type that holds .meta(). */
function unwrapZod(schema: z.ZodType): z.ZodType {
    if ('unwrap' in schema && typeof (schema as any).unwrap === 'function') return unwrapZod((schema as any).unwrap());
    if ('removeDefault' in schema && typeof (schema as any).removeDefault === 'function') return unwrapZod((schema as any).removeDefault());
    return schema;
}

/** Walk a generated JSON Schema and inject x- extensions from Zod .meta(). */
function injectMetaExtensions(jsonSchema: any, zodSchema: z.ZodType): void {
    if (!(zodSchema instanceof z.ZodObject) || !jsonSchema?.properties) return;
    for (const [key, propSchema] of Object.entries(jsonSchema.properties) as [string, any][]) {
        const fieldZod = (zodSchema as z.ZodObject<any>).shape[key];
        if (!fieldZod) continue;
        const meta = fieldZod.meta() as FieldMeta | undefined;
        if (meta?.checksumFor?.length) propSchema['x-checksum-for'] = meta.checksumFor;
        if (meta?.changeRestriction) propSchema['x-change-restriction'] = meta.changeRestriction;
        // Recurse into nested objects
        injectMetaExtensions(propSchema, unwrapZod(fieldZod));
    }
}

/**
 * Convert a Zod schema (ZodObject or ZodArray) to a JSON Schema object
 * @param schema - The Zod schema to convert
 * @param schemaName - Optional name for the schema (defaults to "Schema")
 * @returns The JSON Schema representation
 */
export function zodSchemaToJsonSchema(
    schema: z.ZodObject<any> | z.ZodArray<any>,
    schemaName: string = "Schema"
): any {
    const registry = new OpenAPIRegistry();

    // Wrap arrays in an object for registration
    const schemaToRegister = schema instanceof z.ZodArray
        ? z.object({ items: schema })
        : z.object({}).merge(schema);

    registry.register(schemaName, schemaToRegister);

    const generator = new OpenApiGeneratorV3(registry.definitions);
    const components = generator.generateComponents();

    const result = components.components?.schemas?.[schemaName];
    injectMetaExtensions(result, schema);
    return result;
}