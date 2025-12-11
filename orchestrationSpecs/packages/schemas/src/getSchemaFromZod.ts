import {extendZodWithOpenApi, OpenApiGeneratorV3, OpenAPIRegistry} from "@asteasolutions/zod-to-openapi";
import {z} from "zod";

extendZodWithOpenApi(z);

/**
 * Convert a Zod schema (ZodObject, ZodArray, or ZodDefault wrapping these) to a JSON Schema object
 * @param schema - The Zod schema to convert
 * @param schemaName - Optional name for the schema (defaults to "Schema")
 * @returns The JSON Schema representation
 */
export function zodSchemaToJsonSchema(
    schema: z.ZodObject<any> | z.ZodArray<any> | z.ZodDefault<z.ZodObject<any>> | z.ZodDefault<z.ZodArray<any>>,
    schemaName: string = "Schema"
): any {
    const registry = new OpenAPIRegistry();

    // Unwrap ZodDefault to get the inner schema and extract default value
    let innerSchema: z.ZodObject<any> | z.ZodArray<any>;
    let defaultValue: any = undefined;
    
    if (schema instanceof z.ZodDefault) {
        innerSchema = (schema as z.ZodDefault<any>).def.innerType;
        const defVal = (schema as z.ZodDefault<any>).def.defaultValue;
        // defaultValue can be a function or a direct value depending on Zod version
        defaultValue = typeof defVal === 'function' ? defVal() : defVal;
    } else {
        innerSchema = schema;
    }

    // Extract exampleValue from schema meta (works on both wrapped and unwrapped schemas)
    // In Zod v4, meta() is a method that returns the meta object when called without arguments
    const schemaMeta = typeof schema.meta === 'function' ? schema.meta() : undefined;
    const exampleValue = schemaMeta?.exampleValue;

    // Wrap arrays in an object for registration
    const schemaToRegister = innerSchema instanceof z.ZodArray
        ? z.object({ items: innerSchema })
        : z.object({}).merge(innerSchema);

    registry.register(schemaName, schemaToRegister);

    const generator = new OpenApiGeneratorV3(registry.definitions);
    const components = generator.generateComponents();

    const jsonSchema = components.components?.schemas?.[schemaName] as Record<string, any> | undefined;
    
    // Add the default value to the JSON Schema if present
    if (jsonSchema && defaultValue !== undefined) {
        jsonSchema.default = defaultValue;
    }
    
    // Add the exampleValue to the JSON Schema if present
    // This is used by form builders for initialization without affecting validation
    if (jsonSchema && exampleValue !== undefined) {
        jsonSchema.exampleValue = exampleValue;
    }
    
    return jsonSchema;
}
