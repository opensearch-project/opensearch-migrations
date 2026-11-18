import fs from "node:fs";
import path from "node:path";
import childProcess from "node:child_process";
import {
    DEFAULT_KAFKA_NODE_POOL_SPEC_OVERRIDES,
    DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES,
    OVERALL_MIGRATION_CONFIG,
} from "./userSchemas";
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
    const kafkaClusterBranches = schema.properties.traffic.properties.kafkaClusters.additionalProperties.anyOf;
    const autoCreateBranch = kafkaClusterBranches.find((branch: any) => branch.properties?.autoCreate);
    if (!autoCreateBranch) {
        throw new Error("Kafka cluster configuration schema is missing an autoCreate branch");
    }
    return autoCreateBranch.properties.autoCreate.properties;
}

function getTopicDefinitionSchemas(schema: any): any[] {
    const kafkaClusterBranches = schema.properties.traffic.properties.kafkaClusters.additionalProperties.anyOf;
    return kafkaClusterBranches.flatMap((branch: any) => {
        const topicDefinition = branch.properties?.topics?.additionalProperties;
        return topicDefinition ? [topicDefinition] : [];
    });
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

type MutableJsonSchema = Record<string, any>;

function resolveDefinitionRef(
    schema: unknown,
    defs: Record<string, unknown>,
): MutableJsonSchema | undefined {
    if (!schema || typeof schema !== "object" || Array.isArray(schema)) {
        return undefined;
    }
    let current = schema as MutableJsonSchema;
    const visited = new Set<string>();
    while (
        typeof current.$ref === "string"
        && current.$ref.startsWith("#/$defs/")
    ) {
        const definitionName = current.$ref.substring("#/$defs/".length);
        if (visited.has(definitionName)) {
            break;
        }
        visited.add(definitionName);
        const definition = defs[definitionName];
        if (!definition || typeof definition !== "object" || Array.isArray(definition)) {
            break;
        }
        current = definition as MutableJsonSchema;
    }
    return current;
}

function schemaVariants(
    schema: unknown,
    defs: Record<string, unknown>,
    visited = new Set<MutableJsonSchema>(),
): MutableJsonSchema[] {
    const resolved = resolveDefinitionRef(schema, defs);
    if (!resolved || visited.has(resolved)) {
        return [];
    }
    visited.add(resolved);
    const branches = resolved.oneOf ?? resolved.anyOf ?? resolved.allOf;
    if (!Array.isArray(branches)) {
        return [resolved];
    }
    return branches.flatMap(branch => schemaVariants(branch, defs, visited));
}

function propertySchemas(
    schema: unknown,
    propertyName: string,
    defs: Record<string, unknown>,
): MutableJsonSchema[] {
    return schemaVariants(schema, defs)
        .map(variant => variant.properties?.[propertyName])
        .filter((property): property is MutableJsonSchema =>
            Boolean(property) && typeof property === "object" && !Array.isArray(property)
        );
}

function markDirectPropertiesExpert(
    schema: unknown,
    defs: Record<string, unknown>,
) {
    for (const variant of schemaVariants(schema, defs)) {
        for (const property of Object.values(variant.properties ?? {})) {
            if (property && typeof property === "object" && !Array.isArray(property)) {
                (property as MutableJsonSchema)["x-expert"] = true;
            }
        }
    }
}

function promoteProperties(
    schema: unknown,
    propertyNames: string[],
    defs: Record<string, unknown>,
    metadata: MutableJsonSchema = {},
) {
    for (const propertyName of propertyNames) {
        for (const property of propertySchemas(schema, propertyName, defs)) {
            delete property["x-expert"];
            Object.assign(property, clone(metadata));
        }
    }
}

function workflowNodePoolSpecSchema(
    nodePoolSpec: unknown,
    definitions: Record<string, unknown>,
    defs: Record<string, unknown>,
): unknown {
    const schema = makeSchemaPartial(rewriteRefs(
        resolveTopLevelComponentReference(nodePoolSpec, definitions),
    )) as MutableJsonSchema;
    markDirectPropertiesExpert(schema, defs);

    promoteProperties(schema, ["replicas"], defs, {
        default: DEFAULT_KAFKA_NODE_POOL_SPEC_OVERRIDES.replicas,
        description: "Number of Kafka broker/controller pods. Size this explicitly for the required throughput and availability.",
        "x-essential": true,
        "x-effective-default": {
            label: `${DEFAULT_KAFKA_NODE_POOL_SPEC_OVERRIDES.replicas} brokers`,
            value: DEFAULT_KAFKA_NODE_POOL_SPEC_OVERRIDES.replicas,
            description: "Workflow-managed Kafka uses three dual-role broker/controller pods unless overridden.",
        },
    });
    promoteProperties(schema, ["storage"], defs, {
        default: DEFAULT_KAFKA_NODE_POOL_SPEC_OVERRIDES.storage,
        description: "Persistent storage allocated to each Kafka node. The 2Gi workflow default is intended for smoke tests; size production storage for ingestion rate and retention duration.",
        "x-essential": true,
        "x-effective-default": {
            label: `${DEFAULT_KAFKA_NODE_POOL_SPEC_OVERRIDES.storage.size} persistent volume per broker`,
            value: DEFAULT_KAFKA_NODE_POOL_SPEC_OVERRIDES.storage,
            description: "Workflow-managed Kafka uses a 2Gi persistent volume per broker unless overridden.",
        },
    });
    promoteProperties(schema, ["resources"], defs, {
        description: "CPU and memory requests and limits for each Kafka broker/controller pod.",
        "x-essential": true,
    });

    for (const storageSchema of propertySchemas(schema, "storage", defs)) {
        markDirectPropertiesExpert(storageSchema, defs);
        promoteProperties(storageSchema, ["type"], defs, {
            "x-essential": true,
        });
        promoteProperties(storageSchema, ["size"], defs, {
            default: DEFAULT_KAFKA_NODE_POOL_SPEC_OVERRIDES.storage.size,
            description: "Persistent volume capacity for each Kafka node. The 2Gi default is only suitable for smoke tests.",
            "x-essential": true,
            "x-effective-default": {
                label: DEFAULT_KAFKA_NODE_POOL_SPEC_OVERRIDES.storage.size,
                value: DEFAULT_KAFKA_NODE_POOL_SPEC_OVERRIDES.storage.size,
                description: "Each broker uses a 2Gi persistent volume unless overridden.",
            },
        });
        promoteProperties(storageSchema, ["class"], defs, {
            description: "Kubernetes StorageClass used to provision each Kafka node volume. Choose a class whose throughput, IOPS, availability zone, expansion, and reclaim behavior fit the workload.",
            "x-essential": true,
        });
        promoteProperties(storageSchema, ["deleteClaim"], defs, {
            default: DEFAULT_KAFKA_NODE_POOL_SPEC_OVERRIDES.storage.deleteClaim,
            description: "Controls the Kafka node PVC, not individual Kafka records. When true (the workflow default), Strimzi deletes the PVC when that Kafka node is removed; the backing volume then follows its PersistentVolume reclaim policy. When false, Strimzi retains the PVC. A replacement Strimzi Kafka resource can reuse retained volumes only as a deliberate recovery of the same logical cluster, using the original cluster identity and configuration plus the correct PVC/PV-to-node mapping. This is not an automatic backup or a way to attach old data to an unrelated new cluster. Explicit storage deletion, including workflow reset --delete-storage, can still remove retained claims.",
            "x-essential": true,
            "x-effective-default": {
                label: "delete storage with removed Kafka nodes",
                value: DEFAULT_KAFKA_NODE_POOL_SPEC_OVERRIDES.storage.deleteClaim,
                description: "The workflow defaults to deleting a Kafka node's PVC when the node is removed.",
            },
        });
    }

    for (const resourcesSchema of propertySchemas(schema, "resources", defs)) {
        markDirectPropertiesExpert(resourcesSchema, defs);
        promoteProperties(resourcesSchema, ["requests"], defs, {
            description: "CPU and memory reserved for each Kafka broker/controller pod.",
            "x-essential": true,
        });
        promoteProperties(resourcesSchema, ["limits"], defs, {
            description: "Maximum CPU and memory available to each Kafka broker/controller pod.",
            "x-essential": true,
        });
    }

    return schema;
}

function workflowTopicSpecSchema(topicSpec: unknown): unknown {
    const schema = makeSchemaPartial(rewriteRefs(topicSpec)) as any;
    if (!schema?.properties || typeof schema.properties !== "object") {
        return schema;
    }
    delete schema.properties.topicName;

    for (const property of Object.values(schema.properties)) {
        if (property && typeof property === "object") {
            (property as Record<string, unknown>)["x-expert"] = true;
        }
    }

    const ownedCountFields = {
        partitions: {
            defaultValue: DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES.partitions,
            description: "Number of partitions for the topic. This can be increased after deployment but cannot be decreased.",
            defaultDescription: "New topics use two partitions unless this value is overridden.",
        },
        replicas: {
            defaultValue: DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES.replicas,
            description: "Replication factor for the topic. Changes to a deployed topic require approval.",
            defaultDescription: "New topics use three replicas unless this value is overridden.",
        },
    } as const;

    for (const [field, metadata] of Object.entries(ownedCountFields)) {
        const importedProperty = schema.properties[field];
        const property = (
            importedProperty && typeof importedProperty === "object"
                ? importedProperty
                : {}
        ) as Record<string, unknown>;
        property.type = "integer";
        property.minimum = Math.max(
            1,
            typeof property.minimum === "number" ? property.minimum : 1,
        );
        property.default = metadata.defaultValue;
        property.description = metadata.description;
        property["x-effective-default"] = {
            label: String(metadata.defaultValue),
            value: metadata.defaultValue,
            description: metadata.defaultDescription,
        };
        property["x-essential"] = true;
        delete property["x-expert"];
        schema.properties[field] = property;
    }

    const config = (
        schema.properties.config && typeof schema.properties.config === "object"
            ? schema.properties.config
            : {type: "object"}
    ) as MutableJsonSchema;
    config.type = "object";
    config.properties = config.properties ?? {};
    for (const property of Object.values(config.properties)) {
        if (property && typeof property === "object" && !Array.isArray(property)) {
            (property as MutableJsonSchema)["x-expert"] = true;
        }
    }
    const retention = (
        config.properties["retention.ms"]
        && typeof config.properties["retention.ms"] === "object"
            ? config.properties["retention.ms"]
            : {type: "integer"}
    ) as MutableJsonSchema;
    retention.type = "integer";
    retention.default = DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES.config["retention.ms"];
    retention.description = "How long Kafka retains topic records, in milliseconds. The seven-day workflow default must cover the expected capture-to-replay interval; increasing it also increases required broker storage.";
    retention["x-essential"] = true;
    retention["x-effective-default"] = {
        label: "7 days",
        value: DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES.config["retention.ms"],
        description: "Topics retain records for seven days unless overridden.",
    };
    delete retention["x-expert"];
    config.properties["retention.ms"] = retention;
    config.description = "Kafka topic retention and advanced topic configuration.";
    config["x-essential"] = true;
    delete config["x-expert"];
    schema.properties.config = config;
    return schema;
}

function resolveTopLevelComponentReference(
    schemaNode: unknown,
    definitions: Record<string, unknown>,
): unknown {
    let current = schemaNode;
    const visited = new Set<string>();
    while (
        current
        && typeof current === "object"
        && typeof (current as any).$ref === "string"
        && (current as any).$ref.startsWith("#/components/schemas/")
    ) {
        const definitionName = (current as any).$ref.substring(
            "#/components/schemas/".length,
        );
        if (visited.has(definitionName) || !definitions[definitionName]) {
            break;
        }
        visited.add(definitionName);
        current = definitions[definitionName];
    }
    return current;
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
    defs.StrimziKafkaNodePoolSpec = workflowNodePoolSpecSchema(
        nodePoolSpec,
        definitions,
        defs,
    );
    defs.StrimziKafkaTopicSpec = workflowTopicSpecSchema(
        resolveTopLevelComponentReference(topicSpec, definitions),
    );
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
        "x-expert": true,
    };
    autoCreateProps.nodePoolSpecOverrides = {
        $ref: "#/$defs/StrimziKafkaNodePoolSpec",
        description: "Kafka broker count, storage, resources, and advanced Strimzi KafkaNodePool.spec overrides merged into the workflow-managed node pool resource.",
        "x-essential": true,
    };
    for (const topicDefinition of getTopicDefinitionSchemas(enriched)) {
        topicDefinition.properties.specOverrides = {
            $ref: "#/$defs/StrimziKafkaTopicSpec",
            description: "Kafka topic settings merged into the generated Strimzi KafkaTopic.spec. Partition and replica counts are common settings; additional Strimzi fields are available in expert mode.",
        };
    }
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
    ], {encoding: "utf-8", stdio: ["ignore", "pipe", "pipe"]});
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
