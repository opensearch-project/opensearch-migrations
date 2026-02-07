import {z} from "zod";
import {zodSchemaToJsonSchema} from "./getSchemaFromZod";
import {ARGO_MIGRATION_CONFIGS_ARRAY} from "./argoSchemas";

const userJsonSchema = zodSchemaToJsonSchema(z.array(ARGO_MIGRATION_CONFIGS_ARRAY))
console.log(JSON.stringify(userJsonSchema, null, 2));
