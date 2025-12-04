import {z} from "zod";
import {zodSchemaToJsonSchema} from "./getSchemaFromZod";
import {PARAMETERIZED_MIGRATION_CONFIG} from "./argoSchemas";

const userJsonSchema = zodSchemaToJsonSchema(z.array(PARAMETERIZED_MIGRATION_CONFIG))
console.log(JSON.stringify(userJsonSchema, null, 2));
