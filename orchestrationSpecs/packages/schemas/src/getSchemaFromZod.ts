import {extendZodWithOpenApi, OpenApiGeneratorV3, OpenAPIRegistry} from "@asteasolutions/zod-to-openapi";
import {z} from "zod";

extendZodWithOpenApi(z);

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

    return components.components?.schemas?.[schemaName];
}