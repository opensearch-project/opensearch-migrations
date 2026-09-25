import {extendZodWithOpenApi, OpenApiGeneratorV3, OpenAPIRegistry} from "@asteasolutions/zod-to-openapi";
import {z} from "zod";
import {FieldMeta} from "./userSchemas";
import {getDescription} from "./schemaUtilities";

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

/** Unwrap Zod wrappers (optional, default, etc.) to reach the type that holds .meta(). */
function unwrapZod(schema: z.ZodType): z.ZodType {
    if (schema instanceof z.ZodArray) return schema;
    if ('unwrap' in schema && typeof (schema as any).unwrap === 'function') return unwrapZod((schema as any).unwrap());
    if ('removeDefault' in schema && typeof (schema as any).removeDefault === 'function') return unwrapZod((schema as any).removeDefault());
    if (schema instanceof z.ZodPipe) {
        const def = schema._def;
        return unwrapZod((def.in instanceof z.ZodTransform ? def.out : def.in) as z.ZodType);
    }
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

function isExpertDescription(description: string | undefined): boolean {
    return Boolean(description) && (/^\s*\[Expert\]/i.test(description ?? "") || /^\s*Expert\b/i.test(description ?? ""));
}

function applyMetaExtensions(jsonSchema: Record<string, unknown>, zodSchema: z.ZodType): void {
    const meta = fieldMeta(zodSchema);
    if (meta?.checksumFor?.length) jsonSchema['x-checksum-for'] = meta.checksumFor;
    if (meta?.changeRestriction) jsonSchema['x-change-restriction'] = meta.changeRestriction;
    if (meta?.essential) jsonSchema['x-essential'] = true;
    if (meta?.uiHint) jsonSchema['x-ui-hint'] = meta.uiHint;
    if (meta?.externalRef) jsonSchema['x-external-ref'] = meta.externalRef;
    if (meta?.effectiveDefault) jsonSchema['x-effective-default'] = meta.effectiveDefault;
    if (meta?.expert || isExpertDescription(getDescription(zodSchema as z.ZodTypeAny) ?? String(jsonSchema.description ?? ""))) {
        jsonSchema['x-expert'] = true;
    }
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
            applyMetaExtensions(propSchema, fieldZod);
            injectMetaExtensions(propSchema, fieldZod);
        }
        return;
    }

    if (unwrapped instanceof z.ZodRecord && isSafePlainObject(jsonSchema?.additionalProperties)) {
        const valueType = (unwrapped as z.ZodRecord<any, any>).valueType;
        applyMetaExtensions(jsonSchema.additionalProperties, valueType);
        injectMetaExtensions(jsonSchema.additionalProperties, valueType);
        return;
    }

    if (unwrapped instanceof z.ZodArray && isSafePlainObject(jsonSchema?.items)) {
        const itemZod = (unwrapped as z.ZodArray<any>).element;
        applyMetaExtensions(jsonSchema.items, itemZod);
        injectMetaExtensions(jsonSchema.items, itemZod);
        return;
    }

    const unionOptions = (unwrapped instanceof z.ZodUnion || unwrapped instanceof z.ZodDiscriminatedUnion)
        ? (unwrapped as z.ZodUnion<any> | z.ZodDiscriminatedUnion<any, any>).options as z.ZodType[]
        : undefined;
    const jsonUnionBranches = Array.isArray(jsonSchema?.anyOf)
        ? jsonSchema.anyOf
        : Array.isArray(jsonSchema?.oneOf) ? jsonSchema.oneOf : undefined;
    if (unionOptions && jsonUnionBranches) {
        const options = unionOptions;
        jsonUnionBranches.forEach((branch: unknown, index: number) => {
            if (isSafePlainObject(branch) && options[index]) {
                injectMetaExtensions(branch, options[index]);
            }
        });
    }
}

function makeBareNullableSchemasAjvCompatible(jsonSchema: unknown): unknown {
    if (Array.isArray(jsonSchema)) {
        return jsonSchema.map(makeBareNullableSchemasAjvCompatible);
    }
    if (!isSafePlainObject(jsonSchema)) {
        return jsonSchema;
    }

    const converted = Object.fromEntries(
        safeObjectEntries(jsonSchema).map(([key, value]) => [
            key,
            makeBareNullableSchemasAjvCompatible(value),
        ]),
    );
    if (jsonSchema.nullable === true && jsonSchema.type === undefined
        && jsonSchema.$ref === undefined
        && jsonSchema.oneOf === undefined
        && jsonSchema.anyOf === undefined
        && jsonSchema.allOf === undefined) {
        return Object.fromEntries([
            ...safeObjectEntries(converted)
                .filter(([key]) => key !== "nullable"),
            ["type", ["string", "number", "boolean", "object", "array", "null"]],
        ]);
    }
    return converted;
}

const RAW_UI_HINT_KEYS = new Set([
    "uiHint",
    "externalRef",
    "effectiveDefault",
    "essential",
    "expert",
]);

function removeRawUiHintMetadata(jsonSchema: unknown): unknown {
    if (Array.isArray(jsonSchema)) {
        return jsonSchema.map(removeRawUiHintMetadata);
    }
    if (!isSafePlainObject(jsonSchema)) {
        return jsonSchema;
    }
    return Object.fromEntries(
        safeObjectEntries(jsonSchema)
            .filter(([key]) => !RAW_UI_HINT_KEYS.has(key))
            .map(([key, value]) => [key, removeRawUiHintMetadata(value)]),
    );
}

/**
 * Convert a Zod schema (ZodObject or ZodArray) to a JSON Schema object
 * @param schema - The Zod schema to convert
 * @param schemaName - Optional name for the schema (defaults to "Schema")
 * @returns The JSON Schema representation
 */
export function zodSchemaToJsonSchema(
    schema: z.ZodType,
    schemaName: string = "Schema"
): any {
    const registry = new OpenAPIRegistry();
    const unwrapped = unwrapZod(schema);
    if (!(unwrapped instanceof z.ZodObject) && !(unwrapped instanceof z.ZodArray)) {
        throw new Error("JSON schema generation requires an object or array schema");
    }

    // Wrap arrays in an object for registration
    const schemaToRegister = unwrapped instanceof z.ZodArray
        ? z.object({ items: unwrapped })
        : z.object({}).merge(unwrapped);

    registry.register(schemaName, schemaToRegister);

    const generator = new OpenApiGeneratorV3(registry.definitions);
    const components = generator.generateComponents();

    let result: any = components.components?.schemas?.[schemaName];
    injectMetaExtensions(result, schemaToRegister);
    result = removeRawUiHintMetadata(result);
    result = makeBareNullableSchemasAjvCompatible(result);
    return result;
}
