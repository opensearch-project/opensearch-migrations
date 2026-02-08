import {zodSchemaToJsonSchema} from "./getSchemaFromZod";
import {ARGO_MIGRATION_CONFIG} from "./argoSchemas";

const userJsonSchema = zodSchemaToJsonSchema(ARGO_MIGRATION_CONFIG)
console.log(JSON.stringify(userJsonSchema, null, 2));
