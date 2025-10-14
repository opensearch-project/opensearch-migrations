import {extendZodWithOpenApi, OpenApiGeneratorV3, OpenAPIRegistry} from "@asteasolutions/zod-to-openapi";
import {OVERALL_MIGRATION_CONFIG} from "./userSchemas";
import {z} from "zod";
import {PARAMETERIZED_MIGRATION_CONFIG, PARAMETERIZED_MIGRATION_CONFIG_ARRAYS} from "./argoSchemas";

extendZodWithOpenApi(z);

const topObject = z.array(PARAMETERIZED_MIGRATION_CONFIG)

const registry = new OpenAPIRegistry();
registry.register("OverallMigrationArgoWorkflowConfig", topObject);

const generator = new OpenApiGeneratorV3(registry.definitions);
const components = generator.generateComponents();

const userJsonSchema = components.components?.schemas?.OverallMigrationArgoWorkflowConfig;
console.log(JSON.stringify(userJsonSchema, null, 2));
