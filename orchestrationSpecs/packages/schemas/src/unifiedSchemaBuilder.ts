import fs from "node:fs";
import path from "node:path";
import childProcess from "node:child_process";
import {OVERALL_MIGRATION_CONFIG} from "./userSchemas";
import {zodSchemaToJsonSchema} from "./getSchemaFromZod";
import {injectKafkaBrokerConfigSchema} from "./kafkaBrokerConfigSchema";

export const STRIMZI_OPENAPI_API_PATH = "/openapi/v3/apis/kafka.strimzi.io/v1";
export const UNIFIED_SCHEMA_PATH_ENV = "MIGRATION_UNIFIED_SCHEMA_PATH";
export const ALLOW_FALLBACK_UNIFIED_SCHEMA_ENV = "MIGRATION_ALLOW_FALLBACK_UNIFIED_SCHEMA";

export type UnifiedSchemaMode = "full";
export type UnifiedSchemaSource = "file" | "live-cluster" | "fallback-artifact" | "generated";

export interface LoadedUnifiedSchema {
    schema: Record<string, unknown>;
    mode: UnifiedSchemaMode;
    source: UnifiedSchemaSource;
    detail: string;
}

export interface UnifiedSchemaBuildOptions {
    strimziSchemaPath?: string;
}

function packageRoot(...segments: string[]) {
    return path.resolve(__dirname, "..", ...segments);
}

export function getFallbackUnifiedSchemaCandidatePaths() {
    const relativeSchemaPath = path.join("packages", "schemas", "generated", "workflowMigration.schema.json");
    return [
        packageRoot("generated", "workflowMigration.schema.json"),
        path.resolve(process.cwd(), "generated", "workflowMigration.schema.json"),
        path.resolve(process.cwd(), relativeSchemaPath),
        path.resolve(process.cwd(), "..", relativeSchemaPath),
    ];
}

export function getFallbackUnifiedSchemaPath() {
    return getFallbackUnifiedSchemaCandidatePaths()[0];
}

function findFallbackUnifiedSchemaPath() {
    return getFallbackUnifiedSchemaCandidatePaths().find(candidate => fs.existsSync(candidate));
}

function clone<T>(value: T): T {
    return JSON.parse(JSON.stringify(value));
}

function getAutoCreateProperties(schema: any): Record<string, unknown> {
    return schema.properties.kafkaClusterConfiguration.additionalProperties.anyOf[0]
        .properties.autoCreate.properties;
}

function addSchemaMetadata(schema: Record<string, unknown>, mode: UnifiedSchemaMode, detail: string) {
    schema.$schema = "http://json-schema.org/draft-07/schema#";
    schema.$id = "https://opensearch.org/schemas/workflowMigration.schema.json";
    schema.title = "OpenSearch Migration Workflow Configuration";
    schema["x-orchestration-specs-strimzi-schema-mode"] = mode;
    schema["x-orchestration-specs-strimzi-schema-detail"] = detail;
    return schema;
}

function normalizeSchemaDetail(detail: string) {
    const relative = path.relative(process.cwd(), detail);
    return relative && !relative.startsWith("..") ? relative : detail;
}

function findSchemaKeyByKind(definitions: Record<string, unknown>, kind: string) {
    const exact = Object.keys(definitions).find(k => k.endsWith(`.${kind}`));
    if (!exact) {
        throw new Error(`Unable to locate schema definition for Strimzi kind '${kind}'`);
    }
    return exact;
}

function findSpecSchema(schemaNode: any): any {
    if (!schemaNode || typeof schemaNode !== "object") {
        return undefined;
    }
    if (schemaNode.properties?.spec) {
        return schemaNode.properties.spec;
    }
    if (Array.isArray(schemaNode.allOf)) {
        for (const item of schemaNode.allOf) {
            const spec = findSpecSchema(item);
            if (spec) {
                return spec;
            }
        }
    }
    return undefined;
}

function rewriteRefs(value: unknown): unknown {
    if (Array.isArray(value)) {
        return value.map(rewriteRefs);
    }
    if (!value || typeof value !== "object") {
        return value;
    }

    const updated: Record<string, unknown> = {};
    for (const [key, inner] of Object.entries(value)) {
        if (key === "$ref" && typeof inner === "string") {
            updated[key] = inner.replace("#/components/schemas/", "#/$defs/");
        } else {
            updated[key] = rewriteRefs(inner);
        }
    }
    return updated;
}

function makeSchemaPartial(value: unknown): unknown {
    if (Array.isArray(value)) {
        return value.map(makeSchemaPartial);
    }
    if (!value || typeof value !== "object") {
        return value;
    }

    const updated: Record<string, unknown> = {};
    for (const [key, inner] of Object.entries(value)) {
        if (key === "required" && Array.isArray(inner)) {
            continue;
        }
        updated[key] = makeSchemaPartial(inner);
    }
    return updated;
}

function collectReferencedDefinitionNames(value: unknown, target = new Set<string>()) {
    if (Array.isArray(value)) {
        value.forEach(v => collectReferencedDefinitionNames(v, target));
        return target;
    }
    if (!value || typeof value !== "object") {
        return target;
    }

    for (const [key, inner] of Object.entries(value)) {
        if (key === "$ref" && typeof inner === "string" && inner.startsWith("#/components/schemas/")) {
            target.add(inner.substring("#/components/schemas/".length));
        } else {
            collectReferencedDefinitionNames(inner, target);
        }
    }
    return target;
}

function extractStrimziDefsFromOpenApi(openApi: any) {
    const definitions = openApi?.components?.schemas;
    if (!definitions || typeof definitions !== "object") {
        throw new Error("Strimzi OpenAPI document does not have components.schemas");
    }

    const kafkaKey = findSchemaKeyByKind(definitions, "Kafka");
    const nodePoolKey = findSchemaKeyByKind(definitions, "KafkaNodePool");
    const topicKey = findSchemaKeyByKind(definitions, "KafkaTopic");

    const kafkaSpec = findSpecSchema(definitions[kafkaKey]);
    const nodePoolSpec = findSpecSchema(definitions[nodePoolKey]);
    const topicSpec = findSpecSchema(definitions[topicKey]);
    if (!kafkaSpec || !nodePoolSpec || !topicSpec) {
        throw new Error("Unable to locate Strimzi spec schemas for Kafka/KafkaNodePool/KafkaTopic");
    }

    const requiredDefs = new Set<string>();
    [kafkaSpec, nodePoolSpec, topicSpec].forEach(spec => collectReferencedDefinitionNames(spec, requiredDefs));

    const queue = [...requiredDefs];
    while (queue.length > 0) {
        const next = queue.pop()!;
        const nextSchema = definitions[next];
        if (!nextSchema) {
            continue;
        }
        const before = requiredDefs.size;
        collectReferencedDefinitionNames(nextSchema, requiredDefs);
        if (requiredDefs.size > before) {
            for (const name of [...requiredDefs]) {
                if (!queue.includes(name) && name !== next) {
                    queue.push(name);
                }
            }
        }
    }

    const defs: Record<string, unknown> = {};
    for (const name of requiredDefs) {
        defs[name] = makeSchemaPartial(rewriteRefs(definitions[name]));
    }
    defs.StrimziKafkaSpec = makeSchemaPartial(rewriteRefs(kafkaSpec));
    defs.StrimziKafkaNodePoolSpec = makeSchemaPartial(rewriteRefs(nodePoolSpec));
    defs.StrimziKafkaTopicSpec = makeSchemaPartial(rewriteRefs(topicSpec));
    return defs;
}

function injectStrimziRefs(schema: Record<string, unknown>, defs: Record<string, unknown>) {
    const enriched = clone(schema) as any;
    injectKafkaBrokerConfigSchema(defs);
    enriched.$defs = {
        ...(enriched.$defs ?? {}),
        ...defs,
    };

    const autoCreateProps = getAutoCreateProperties(enriched);
    autoCreateProps.clusterSpecOverrides = {
        $ref: "#/$defs/StrimziKafkaSpec",
        description: "Strimzi Kafka.spec overrides merged into the workflow-managed Kafka resource.",
    };
    autoCreateProps.nodePoolSpecOverrides = {
        $ref: "#/$defs/StrimziKafkaNodePoolSpec",
        description: "Strimzi KafkaNodePool.spec overrides merged into the workflow-managed node pool resource.",
    };
    autoCreateProps.topicSpecOverrides = {
        $ref: "#/$defs/StrimziKafkaTopicSpec",
        description: "Strimzi KafkaTopic.spec overrides merged into workflow-created Kafka topics.",
    };
    return enriched;
}

function buildBaseUnifiedSchema() {
    return zodSchemaToJsonSchema(OVERALL_MIGRATION_CONFIG);
}

export function buildUnifiedSchema(options: UnifiedSchemaBuildOptions = {}): LoadedUnifiedSchema {
    if (options.strimziSchemaPath && fs.existsSync(options.strimziSchemaPath)) {
        const base = buildBaseUnifiedSchema();
        const openApi = JSON.parse(fs.readFileSync(options.strimziSchemaPath, "utf-8"));
        const fullSchema = injectStrimziRefs(base, extractStrimziDefsFromOpenApi(openApi));
        return {
            schema: addSchemaMetadata(fullSchema, "full", normalizeSchemaDetail(options.strimziSchemaPath)),
            mode: "full",
            source: "file",
            detail: normalizeSchemaDetail(options.strimziSchemaPath),
        };
    }

    if (process.env[ALLOW_FALLBACK_UNIFIED_SCHEMA_ENV] === "true") {
        const fallbackPath = findFallbackUnifiedSchemaPath();
        if (fallbackPath) {
            const schema = JSON.parse(fs.readFileSync(fallbackPath, "utf-8"));
            return {
                schema,
                mode: (schema["x-orchestration-specs-strimzi-schema-mode"] as UnifiedSchemaMode) ?? "full",
                source: "fallback-artifact",
                detail: fallbackPath,
            };
        }
    }

    throw new Error("No Strimzi schema path was provided and no fallback unified schema artifact was found");
}

function readStrimziOpenApiFromCluster() {
    const output = childProcess.execFileSync("kubectl", [
        "get",
        "--raw",
        STRIMZI_OPENAPI_API_PATH,
    ], {encoding: "utf-8"});
    return JSON.parse(output);
}

export function loadUnifiedSchema(): LoadedUnifiedSchema {
    const explicitPath = process.env[UNIFIED_SCHEMA_PATH_ENV];
    if (explicitPath && fs.existsSync(explicitPath)) {
        const schema = JSON.parse(fs.readFileSync(explicitPath, "utf-8"));
        return {
            schema,
            mode: (schema["x-orchestration-specs-strimzi-schema-mode"] as UnifiedSchemaMode) ?? "full",
            source: "file",
            detail: explicitPath,
        };
    }

    try {
        const base = buildBaseUnifiedSchema();
        const openApi = readStrimziOpenApiFromCluster();
        const fullSchema = injectStrimziRefs(base, extractStrimziDefsFromOpenApi(openApi));
        return {
            schema: addSchemaMetadata(fullSchema, "full", STRIMZI_OPENAPI_API_PATH),
            mode: "full",
            source: "live-cluster",
            detail: STRIMZI_OPENAPI_API_PATH,
        };
    } catch {
        // Fall through to explicit opt-in fallback resolution below.
    }

    if (process.env[ALLOW_FALLBACK_UNIFIED_SCHEMA_ENV] === "true") {
        const fallbackPath = findFallbackUnifiedSchemaPath();
        if (fallbackPath) {
            const schema = JSON.parse(fs.readFileSync(fallbackPath, "utf-8"));
            return {
                schema,
                mode: (schema["x-orchestration-specs-strimzi-schema-mode"] as UnifiedSchemaMode) ?? "full",
                source: "fallback-artifact",
                detail: fallbackPath,
            };
        }
    }

    return buildUnifiedSchema();
}

export function assertUnifiedSchemaIsUsable(loaded: LoadedUnifiedSchema) {
    if (loaded.mode !== "full") {
        throw new Error(`The migration config validator requires a full unified schema, got '${loaded.mode}' from ${loaded.detail}`);
    }
}
