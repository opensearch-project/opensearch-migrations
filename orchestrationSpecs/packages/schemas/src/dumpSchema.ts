import {extendZodWithOpenApi, OpenApiGeneratorV3, OpenAPIRegistry} from "@asteasolutions/zod-to-openapi";
import {OVERALL_MIGRATION_CONFIG} from "./userSchemas";
import {z} from "zod";

extendZodWithOpenApi(z);

const topObject = z.object({}).merge(OVERALL_MIGRATION_CONFIG)

const registry = new OpenAPIRegistry();
registry.register("OverallMigrationConfig", topObject);

const generator = new OpenApiGeneratorV3(registry.definitions);
const components = generator.generateComponents();

const userJsonSchema = components.components?.schemas?.OverallMigrationConfig;
console.log(JSON.stringify(userJsonSchema, null, 2));
