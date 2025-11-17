import {OVERALL_MIGRATION_CONFIG} from "./userSchemas";
import {zodSchemaToJsonSchema} from "./getSchemaFromZod";

export async function main() {
    const userJsonSchema = zodSchemaToJsonSchema(OVERALL_MIGRATION_CONFIG);
    console.log(JSON.stringify(userJsonSchema, null, 2));
}