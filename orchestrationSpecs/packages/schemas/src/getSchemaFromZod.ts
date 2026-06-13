import {extendZodWithOpenApi, OpenApiGeneratorV3, OpenAPIRegistry} from "@asteasolutions/zod-to-openapi";
import {z} from "zod";
import {FieldMeta} from "./userSchemas";

extendZodWithOpenApi(z);

const POLLUTION_KEYS = new Set(["__proto__", "constructor", "prototype"]);

function isSafePlainObject(value: unknown): value is Record<string, unknown> {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
        return false;
    }
    if (value === Object.prototype) {
        return false;
    }
    const proto = Object.getPrototypeOf(value);
    return proto === Object.prototype || proto === null;
}

function safeObjectEntries(obj: Record<string, unknown>) {
    return Object.keys(obj)
        .filter(key => !POLLUTION_KEYS.has(key))
        .map(key => [key, obj[key]] as const);
}

function safeObjectValues(obj: Record<string, unknown>) {
    return Object.keys(obj)
        .filter(key => !POLLUTION_KEYS.has(key))
        .map(key => obj[key]);
}

/** Unwrap Zod wrappers (optional, default, etc.) to reach the type that holds .meta(). */
function unwrapZod(schema: z.ZodType): z.ZodType {
    if ('unwrap' in schema && typeof (schema as any).unwrap === 'function') return unwrapZod((schema as any).unwrap());
    if ('removeDefault' in schema && typeof (schema as any).removeDefault === 'function') return unwrapZod((schema as any).removeDefault());
    return schema;
}

function fieldMeta(schema: z.ZodType): FieldMeta | undefined {
    const direct = schema.meta() as FieldMeta | undefined;
    const unwrapped = unwrapZod(schema);
    const inner = unwrapped === schema ? undefined : unwrapped.meta() as FieldMeta | undefined;
    return {
        ...(inner ?? {}),
        ...(direct ?? {}),
    };
}

/** Walk a generated JSON Schema and inject x- extensions from Zod .meta(). */
function injectMetaExtensions(jsonSchema: any, zodSchema: z.ZodType): void {
    const unwrapped = unwrapZod(zodSchema);
    if (unwrapped instanceof z.ZodObject) {
        if (!isSafePlainObject(jsonSchema?.properties)) return;
        for (const [key, propSchema] of safeObjectEntries(jsonSchema.properties)) {
            if (!Object.hasOwn((unwrapped as z.ZodObject<any>).shape, key)) continue;
            if (!isSafePlainObject(propSchema)) continue;
            const fieldZod = (unwrapped as z.ZodObject<any>).shape[key];
            const meta = fieldMeta(fieldZod);
            if (meta?.checksumFor?.length) propSchema['x-checksum-for'] = meta.checksumFor;
            if (meta?.changeRestriction) propSchema['x-change-restriction'] = meta.changeRestriction;
            if (meta?.uiHint) propSchema['x-ui-hint'] = meta.uiHint;
            injectMetaExtensions(propSchema, fieldZod);
        }
        return;
    }

    if (unwrapped instanceof z.ZodRecord && isSafePlainObject(jsonSchema?.additionalProperties)) {
        injectMetaExtensions(jsonSchema.additionalProperties, (unwrapped as z.ZodRecord<any, any>).valueType);
        return;
    }

    if (unwrapped instanceof z.ZodArray && isSafePlainObject(jsonSchema?.items)) {
        injectMetaExtensions(jsonSchema.items, (unwrapped as z.ZodArray<any>).element);
        return;
    }

    if (unwrapped instanceof z.ZodUnion && Array.isArray(jsonSchema?.anyOf)) {
        const options = (unwrapped as z.ZodUnion<any>).options as z.ZodType[];
        jsonSchema.anyOf.forEach((branch: unknown, index: number) => {
            if (isSafePlainObject(branch) && options[index]) {
                injectMetaExtensions(branch, options[index]);
            }
        });
    }
}

function makeBareNullableSchemasAjvCompatible(jsonSchema: any): void {
    if (Array.isArray(jsonSchema)) {
        jsonSchema.forEach(makeBareNullableSchemasAjvCompatible);
        return;
    }
    if (!isSafePlainObject(jsonSchema)) {
        return;
    }

    if (jsonSchema.nullable === true && jsonSchema.type === undefined
        && jsonSchema.$ref === undefined
        && jsonSchema.oneOf === undefined
        && jsonSchema.anyOf === undefined
        && jsonSchema.allOf === undefined) {
        jsonSchema.type = ["string", "number", "boolean", "object", "array", "null"];
        delete jsonSchema.nullable;
    }

    safeObjectValues(jsonSchema).forEach(makeBareNullableSchemasAjvCompatible);
}

function removeRawUiHintMetadata(jsonSchema: any): void {
    if (Array.isArray(jsonSchema)) {
        jsonSchema.forEach(removeRawUiHintMetadata);
        return;
    }
    if (!isSafePlainObject(jsonSchema)) {
        return;
    }
    delete jsonSchema.uiHint;
    safeObjectValues(jsonSchema).forEach(removeRawUiHintMetadata);
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
    injectMetaExtensions(result, schemaToRegister);
    removeRawUiHintMetadata(result);
    makeBareNullableSchemasAjvCompatible(result);
    return result;
}
