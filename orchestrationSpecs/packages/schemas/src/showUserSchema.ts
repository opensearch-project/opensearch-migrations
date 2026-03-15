import {loadUnifiedSchema} from "./unifiedSchemaBuilder";

export async function main() {
    const unifiedSchema = loadUnifiedSchema();
    console.log(JSON.stringify(unifiedSchema.schema, null, 2));
}

if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch((error) => {
        console.error('Unhandled error:', error);
        process.exit(1);
    });
}
