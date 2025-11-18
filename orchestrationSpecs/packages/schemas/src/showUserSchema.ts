import {OVERALL_MIGRATION_CONFIG} from "./userSchemas";
import {zodSchemaToJsonSchema} from "./getSchemaFromZod";

export async function main() {
    const userJsonSchema = zodSchemaToJsonSchema(OVERALL_MIGRATION_CONFIG);
    console.log(JSON.stringify(userJsonSchema, null, 2));
}

if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch((error) => {
        console.error('Unhandled error:', error);
        process.exit(1);
    });
}